package com.danny.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth LE link to the glasses. The glasses are just a mic and a speaker:
 *  - MIC  notifications: 16 kHz ADPCM audio from the glasses
 *  - SPK  writes:        16 kHz ADPCM audio to play on the glasses
 *  - CTRL text:          commands ("tone", "volume 70", "flush") and events ("button down")
 * Each audio packet: uint16 seq, int16 predictor, uint8 index, ADPCM bytes.
 */
@SuppressLint("MissingPermission")
class GlassesLink(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(text: String)
        fun onConnection(connected: Boolean, name: String?)
        fun onEvent(text: String)
        fun onStat(level: Int, bufferMs: Int)
        fun onMicAudio(pcm: ShortArray, count: Int)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("7b9a0001-3c2e-4f6a-8d1b-2e5f6a7b8c9d")
        val MIC: UUID = UUID.fromString("7b9a0002-3c2e-4f6a-8d1b-2e5f6a7b8c9d")
        val SPK: UUID = UUID.fromString("7b9a0003-3c2e-4f6a-8d1b-2e5f6a7b8c9d")
        val CTRL: UUID = UUID.fromString("7b9a0004-3c2e-4f6a-8d1b-2e5f6a7b8c9d")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val RATE = 16000
        const val FRAME = 320   // samples per speaker packet (20 ms)
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()   // GATT allows one operation at a time
    private val opDone = Semaphore(0)
    private var gatt: BluetoothGatt? = null
    private var micChar: BluetoothGattCharacteristic? = null
    private var spkChar: BluetoothGattCharacteristic? = null
    private var ctrlChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private val micPcm = ShortArray(1024)

    @Volatile var connected = false; private set
    @Volatile private var connecting = false   // a connectGatt attempt is in flight
    private var refreshed = false              // stale-cache refresh already tried on this link
    @Volatile var mtu = 23; private set
    @Volatile var bufferMs = 0; private set
    @Volatile var micPackets = 0; private set
    @Volatile var micLost = 0; private set
    private val gattLock = Object()   // the speaker thread and commands share the GATT queue
    private var lastMicSeq = -1

    private fun log(text: String) { main.post { listener.onLog(text) } }

    // ---------------- connect ----------------

    fun connect() {
        if (connected || connecting || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return log("Bluetooth is off")
        log("Looking for the glasses...")
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        main.postDelayed({
            if (scanning) { stopScan(); log("Glasses not found. Is the LED blinking?") }
        }, 15000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            val device = result.device
            log("Found ${device.name ?: device.address}, connecting...")
            // Close any half-dead attempt before opening a new one (stacked gatts are
            // the classic Android "connects sometimes" bug).
            gatt?.close()
            gatt = null
            connecting = true
            refreshed = false
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan failed ($errorCode)")
        }
    }

    fun disconnect() {
        stopScan()
        connecting = false
        synchronized(streamLock) { streamLen = 0; streamGeneration++ }
        // Close the client interface now - never wait for the callback, and never
        // let a zombie gatt coexist with the next attempt.
        val g = gatt
        gatt = null
        g?.disconnect()
        g?.close()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(517)   // big packets: one 20 ms audio frame each
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = connected
                connected = false
                connecting = false
                g.close()
                if (gatt == g) gatt = null
                opDone.release()
                main.post { listener.onConnection(false, null) }
                // 133 is Android's generic GATT failure - the retry loop handles it.
                if (!was) log(if (status == 133) "Connection failed (133) - will retry"
                              else "Connection failed ($status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var service = g.getService(SERVICE)
            if (service == null && !refreshed) {
                // Stale attribute cache (e.g. after a firmware flash): refresh once and rediscover.
                refreshed = true
                log("Glasses service missing - refreshing the Bluetooth cache")
                runCatching { g.javaClass.getMethod("refresh").invoke(g) }
                g.discoverServices()
                return
            }
            if (service == null) {
                log("Glasses service missing - reconnecting")
                g.disconnect()
                return
            }
            micChar = service.getCharacteristic(MIC)
            spkChar = service.getCharacteristic(SPK)
            ctrlChar = service.getCharacteristic(CTRL)
            worker.execute {
                // Both notification enables must succeed before we call this connected:
                // CTRL first, then MIC, per the protocol.
                val ok = enableNotify(ctrlChar!!) && enableNotify(micChar!!)
                if (!ok) {
                    log("Audio setup failed - reconnecting")
                    main.post { gatt?.disconnect() }   // DISCONNECTED callback drives the retry
                    return@execute
                }
                connected = true
                connecting = false
                lastMicSeq = -1
                main.post { listener.onConnection(true, g.device.name) }
                log("Connected (packet size $mtu)")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opDone.release()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opDone.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(c.uuid, value)
        }

        @Deprecated("Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotification(c.uuid, c.value ?: return)
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (uuid == MIC) {
            if (value.size <= 5) return
            val seq = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
            if (lastMicSeq >= 0) {
                val gap = (seq - lastMicSeq - 1) and 0xFFFF
                micLost += gap
                // Protocol: lost packets leave a time hole - fill it with silence (320 samples
                // per lost 20 ms packet). Guard the size so a glasses reboot (seq reset to 0,
                // looking like a huge gap) doesn't flood the stream.
                if (gap in 1..50) {
                    val silence = ShortArray(gap * FRAME)
                    listener.onMicAudio(silence, silence.size)
                }
            }
            lastMicSeq = seq
            micPackets++
            val pred = ((value[2].toInt() and 0xFF) or (value[3].toInt() shl 8)).toShort().toInt()
            val codec = Adpcm(pred, (value[4].toInt() and 0xFF).coerceAtMost(88))
            val n = codec.decode(value, 5, micPcm)
            listener.onMicAudio(micPcm, n)   // called on the Bluetooth thread
        } else if (uuid == CTRL) {
            val text = String(value, Charsets.UTF_8)
            if (text.startsWith("stat ")) {
                var level = 0
                for (part in text.split(' ')) {
                    val (k, v) = part.split('=').let { if (it.size == 2) it[0] to it[1] else return@let "" to "" }
                    if (k == "level") level = v.toIntOrNull() ?: 0
                    if (k == "buffer") bufferMs = v.toIntOrNull() ?: 0
                }
                main.post { listener.onStat(level, bufferMs) }
            } else {
                main.post { listener.onEvent(text) }
            }
        }
    }

    // ---------------- GATT operations (run on the worker thread) ----------------

    // Starts one GATT operation and waits for its callback. One at a time across threads.
    private fun awaitOp(start: () -> Boolean): Boolean {
        synchronized(gattLock) {
            opDone.drainPermits()
            // Bounded: a wedged stack must not queue-block every later command for minutes.
            repeat(3) {
                if (start()) return opDone.tryAcquire(4, TimeUnit.SECONDS)
                Thread.sleep(5)  // stack busy: try again
            }
            return false
        }
    }

    private fun enableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return false
        return awaitOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
    }

    private fun write(c: BluetoothGattCharacteristic, data: ByteArray, noResponse: Boolean): Boolean {
        val g = gatt ?: return false
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                   else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return awaitOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    // ---------------- public API ----------------

    fun command(text: String) {
        worker.execute { ctrlChar?.let { if (connected) write(it, text.toByteArray(), false) } }
    }

    // Speaker streaming: audio is queued here and a sender thread paces it to the
    // glasses (~0.4 s ahead of real time). Used for one-shot sounds and for AI
    // replies that arrive in bursts while they're still being generated.
    private val streamLock = Object()
    private var streamBuf = ShortArray(RATE * 30)
    private var streamLen = 0
    private var streamGeneration = 0
    @Volatile private var lastQueuedMs = 0L

    /** Queue 16 kHz mono audio for the glasses' speaker. */
    fun streamAudio(pcm: ShortArray, count: Int = pcm.size) {
        synchronized(streamLock) {
            if (streamLen + count > streamBuf.size) streamBuf = streamBuf.copyOf(maxOf(streamBuf.size * 2, streamLen + count))
            System.arraycopy(pcm, 0, streamBuf, streamLen, count)
            streamLen += count
            lastQueuedMs = SystemClock.elapsedRealtime()
            streamLock.notifyAll()
        }
        ensureSender()
    }

    /** Stop the speaker now: drop queued audio here and on the glasses. */
    fun stop() {
        synchronized(streamLock) { streamLen = 0; streamGeneration++ }
        command("flush")
    }

    /** Audio still waiting to be sent, in ms. */
    fun queuedMs(): Int = synchronized(streamLock) { streamLen * 1000 / RATE }

    /** Plays a whole clip; onDone runs roughly when the glasses finish it. */
    fun play(pcm: ShortArray, onDone: (() -> Unit)? = null) {
        streamAudio(pcm)
        onDone?.let { main.postDelayed(it, pcm.size * 1000L / RATE + 700) }
    }

    private var sender: Thread? = null

    private fun ensureSender() {
        if (sender?.isAlive == true) return
        sender = Thread({ senderLoop() }, "glasses-speaker").apply { isDaemon = true; start() }
    }

    private fun senderLoop() {
        val codec = Adpcm()
        val frame = ShortArray(FRAME)
        var seq = 0
        var clockStart = 0L
        var sent = 0L
        var generation = -1
        while (true) {
            var n: Int
            synchronized(streamLock) {
                // Wait for a full frame, or send the tail of a sound once it stops arriving.
                while (streamLen < FRAME &&
                       !(streamLen > 0 && SystemClock.elapsedRealtime() - lastQueuedMs > 80)) {
                    streamLock.wait(if (streamLen > 0) 20 else 1000)
                }
                n = minOf(FRAME, streamLen)
                System.arraycopy(streamBuf, 0, frame, 0, n)
                System.arraycopy(streamBuf, n, streamBuf, 0, streamLen - n)
                streamLen -= n
                if (generation != streamGeneration) { generation = streamGeneration; clockStart = 0 }
            }
            val spk = spkChar
            if (spk == null || !connected) continue
            val now = SystemClock.elapsedRealtime()
            // New burst after a pause (or after a stop): restart the pacing clock.
            if (clockStart == 0L || now > clockStart + sent * 1000 / RATE + 300) {
                clockStart = now; sent = 0; codec.pred = 0; codec.index = 0
            }
            val header = byteArrayOf(
                (seq and 0xFF).toByte(), (seq shr 8 and 0xFF).toByte(),
                (codec.pred and 0xFF).toByte(), (codec.pred shr 8 and 0xFF).toByte(),
                codec.index.toByte())
            write(spk, header + codec.encode(frame, 0, n), true)
            seq = (seq + 1) and 0xFFFF
            sent += n
            val ahead = clockStart + sent * 1000 / RATE - 400 - SystemClock.elapsedRealtime()
            if (ahead > 0) Thread.sleep(ahead)
        }
    }
}
