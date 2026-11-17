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
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    private var scanPhase = 0   // 0 = by service UUID, 1 = unfiltered fallback (match by name)
    private val nearby = LinkedHashMap<String, String>()   // fallback scan: address -> "name rssi"
    private var attempts = 0
    private var healthLogged = false
    private val prefs = context.getSharedPreferences("glasses-link", Context.MODE_PRIVATE)
    private var scanRound = 0      // full filtered+unfiltered cycles this attempt
    private var connectRetries = 0 // GATT connect retries on the current target
    private var directTried = false

    fun connect() {
        if (connected || connecting || scanning) return
        attempts++
        if (!healthLogged) { logHealth(); healthLogged = true }
        log("Looking for the glasses... (attempt $attempts)")
        connectRetries = 0
        // In a crowd a scan can miss advertisements; a saved address skips discovery
        // entirely and goes straight for the link.
        val saved = prefs.getString("addr", null)
        if (saved != null && !directTried) {
            directTried = true
            log("Trying the saved glasses address $saved directly - no scan needed")
            val dev = runCatching { adapter?.getRemoteDevice(saved) }.getOrNull()
            if (dev != null) { connectTo(dev); return }
            log("Saved address is not usable - scanning instead")
        }
        scanRound = 0
        startScan(filtered = true)
    }

    /** Open a GATT link, closing any half-dead attempt first (stacked gatts are
     *  the classic Android "connects sometimes" bug). */
    private fun connectTo(device: BluetoothDevice) {
        gatt?.close()
        gatt = null
        connecting = true
        refreshed = false
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            connecting = false
            log("Bluetooth connect blocked - allow the 'Nearby devices' permission")
        }
    }

    /** One-time environment dump: adapter, permissions, network. The log is the only
     *  signal we get from the phone, so make it tell the whole story. */
    private fun logHealth() {
        log("Bluetooth adapter: " + if (adapter?.isEnabled == true) "on" else "OFF - turn Bluetooth on")
        val scan = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val conn = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        log("Permissions: Nearby devices (scan) " + (if (scan) "ok" else "MISSING") +
            ", Bluetooth connect " + (if (conn) "ok" else "MISSING"))
        val net = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none (offline)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                else -> "other"
            }
        }.getOrDefault("unknown")
        log("Phone network: $net")
    }

    private fun statusText(status: Int) = when (status) {
        0 -> "ok"
        8 -> "link lost / supervision timeout"
        19 -> "glasses ended the connection"
        22 -> "glasses not responding (off or out of range)"
        62 -> "connection could not be established"
        133 -> "Android Bluetooth error 133 (usually transient - the retry fixes it)"
        else -> "status $status"
    }

    private fun bondText(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "paired"
        BluetoothDevice.BOND_BONDING -> "pairing..."
        else -> "not paired"
    }

    /**
     * Phase 1: filter on the advertised service UUID. Some firmware builds don't fit the
     * 128-bit UUID in the advertisement, so phase 2 scans unfiltered, collects everything
     * nearby, and connects to the first name starting with "Dani-".
     */
    private fun startScan(filtered: Boolean) {
        val scanner = adapter?.bluetoothLeScanner ?: return log("Bluetooth is off")
        scanning = true
        scanPhase = if (filtered) 0 else 1
        if (!filtered) nearby.clear()
        val filters = if (filtered) listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()) else null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log(if (filtered) "Scan started: filtering for the glasses' service id (15 s)"
            else "Scan started: unfiltered, listing every device nearby (10 s)")
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            log("Bluetooth scan blocked - allow the 'Nearby devices' permission")
            return
        }
        main.postDelayed({
            if (!scanning) return@postDelayed
            stopScan()
            if (filtered) {
                log("Not found by service id - scanning everything nearby...")
                startScan(filtered = false)
            } else {
                val seen = nearby.entries.take(8).joinToString { (a, n) -> "$n [$a]" }
                log("Glasses not found in scan round ${scanRound + 1} of 4.")
                log(if (seen.isEmpty())
                    "The phone sees NO Bluetooth devices at all - the glasses are off, out of range, or already connected to another device (laptop, another phone). Bluetooth scan itself is working."
                    else "Phone sees ${nearby.size} device(s): $seen. No name starting with 'Dani' among them.")
                if (scanRound < 3) {
                    scanRound++
                    log("Keeping at it - round ${scanRound + 1} of 4 starts in 6 s")
                    main.postDelayed({ if (!connected && !connecting && !scanning) startScan(filtered = true) }, 6000)
                } else {
                    log("Glasses NOT FOUND after 4 rounds. Turn the glasses off and on, make sure nothing else is connected to them, then try again.")
                }
            }
        }, if (filtered) 15000 else 10000)
    }

    // Signal strength over time, every 10 s while connected.
    private val rssiPoller = object : Runnable {
        override fun run() {
            if (!connected) return
            runCatching { gatt?.readRemoteRssi() }
            main.postDelayed(this, 10000)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName
            if (scanPhase == 1) {
                if (!nearby.containsKey(device.address) && nearby.size < 30) {
                    val uuids = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString().take(13) } ?: "none"
                    nearby[device.address] = "${name ?: "?"} ${result.rssi}dBm"
                    log("Nearby: ${name ?: "?"} ${device.address} rssi=${result.rssi} svc=$uuids")
                }
                if (name == null || !name.startsWith("Dani")) return
                log("Glasses FOUND by name: $name ${device.address} rssi=${result.rssi}")
            } else {
                log("Glasses FOUND by service id: ${name ?: device.address} rssi=${result.rssi}")
            }
            stopScan()
            log("Connecting to ${device.address} (${bondText(device.bondState)})...")
            connectRetries = 0
            connectTo(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log(when (errorCode) {
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Scan failed: permission missing - allow 'Nearby devices'"
                ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                    "Scan failed: Android is rate-limiting scans - waiting for the next retry"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    "Scan failed: filtered scans unsupported - retrying unfiltered"
                else -> "Scan failed ($errorCode)"
            })
            if (errorCode == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED && scanPhase == 0) {
                main.postDelayed({ if (!connected && !connecting && !scanning) startScan(filtered = false) }, 500)
            }
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
                prefs.edit().putString("addr", g.device.address).apply()   // direct link next time
                connectRetries = 0
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
                if (was) {
                    log("Disconnected: ${statusText(status)}")
                } else if (connectRetries < 3) {
                    // Transient RF loss (crowd, distance) fails connects with 133/62;
                    // spaced retries ride it out.
                    connectRetries++
                    val wait = 500L * connectRetries
                    connecting = true
                    log("Connection failed: ${statusText(status)} - retry $connectRetries/3 in $wait ms")
                    val dev = g.device
                    main.postDelayed({ if (!connected) connectTo(dev) }, wait)
                } else {
                    log("Connection failed 3 times: ${statusText(status)}")
                    if (directTried) {
                        log("Direct link to the saved address failed - scanning for the glasses instead")
                        directTried = false
                        scanRound = 0
                        main.postDelayed({ if (!connected && !connecting && !scanning) startScan(filtered = true) }, 1000)
                    }
                }
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
                val ctrlOk = enableNotify(ctrlChar!!)
                log("CTRL notifications: " + if (ctrlOk) "on" else "FAILED")
                val micOk = enableNotify(micChar!!)
                log("MIC notifications: " + if (micOk) "on" else "FAILED")
                val ok = ctrlOk && micOk
                if (!ok) {
                    log("Audio setup failed - reconnecting")
                    main.post { gatt?.disconnect() }   // DISCONNECTED callback drives the retry
                    return@execute
                }
                connected = true
                connecting = false
                attempts = 0
                lastMicSeq = -1
                main.post { listener.onConnection(true, g.device.name) }
                log("Connected (packet size $mtu)")
                main.post(rssiPoller)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opDone.release()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == 0) log("Glasses signal: $rssi dBm")
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
