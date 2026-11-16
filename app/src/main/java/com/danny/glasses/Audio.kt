package com.danny.glasses

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** IMA ADPCM, identical to the glasses firmware (first sample in the HIGH nibble). */
class Adpcm(var pred: Int = 0, var index: Int = 0) {
    companion object {
        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
        private val STEP = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60,
            66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371,
            408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878,
            2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
            8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086,
            29794, 32767)
    }

    fun encode(pcm: ShortArray, from: Int, count: Int): ByteArray {
        val out = ByteArray((count + 1) / 2)
        for (i in 0 until count) {
            var step = STEP[index]
            var diff = pcm[from + i] - pred
            var code = 0
            if (diff < 0) { code = 8; diff = -diff }
            var delta = step shr 3
            if (diff >= step) { code = code or 4; diff -= step; delta += step }
            step = step shr 1
            if (diff >= step) { code = code or 2; diff -= step; delta += step }
            step = step shr 1
            if (diff >= step) { code = code or 1; delta += step }
            pred = (if (code and 8 != 0) pred - delta else pred + delta).coerceIn(-32768, 32767)
            index = (index + INDEX[code]).coerceIn(0, 88)
            if (i % 2 == 0) out[i / 2] = (code shl 4).toByte()
            else out[i / 2] = (out[i / 2].toInt() or code).toByte()
        }
        return out
    }

    fun decode(data: ByteArray, from: Int, out: ShortArray): Int {
        var n = 0
        for (b in from until data.size) {
            val byte = data[b].toInt() and 0xFF
            for (half in 0..1) {
                val code = if (half == 0) byte shr 4 else byte and 0x0F
                val step = STEP[index]
                var delta = step shr 3
                if (code and 4 != 0) delta += step
                if (code and 2 != 0) delta += step shr 1
                if (code and 1 != 0) delta += step shr 2
                pred = (if (code and 8 != 0) pred - delta else pred + delta).coerceIn(-32768, 32767)
                index = (index + INDEX[code]).coerceIn(0, 88)
                out[n++] = pred.toShort()
            }
        }
        return n
    }
}

object Wav {
    /** Reads a 16-bit PCM WAV and returns 16 kHz mono samples. */
    fun load16k(file: File): ShortArray {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 1
        var rate = 16000
        var bits = 16
        var pos = 12
        var data: ShortArray? = null
        while (pos + 8 <= buf.limit()) {
            val id = String(buf.array(), pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                val len = minOf(size, buf.limit() - body)
                val frames = len / (2 * channels)
                data = ShortArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until channels) sum += buf.getShort(body + (f * channels + c) * 2)
                    (sum / channels).toShort()
                }
                break
            }
            pos = body + size + (size and 1)
        }
        require(bits == 16) { "only 16-bit WAV supported" }
        return resample(data ?: ShortArray(0), rate, 16000)
    }

    fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        return ShortArray(outLen) { i ->
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val t = x - i0
            (input[i0] * (1 - t) + input[i1] * t).toInt().toShort()
        }
    }
}

/**
 * Streaming 24 kHz -> 16 kHz converter for AI voices (Gemini Live outputs 24 kHz).
 * Upsample x2, low-pass at 7.2 kHz (windowed sinc), keep every 3rd sample.
 * Filtering first stops the 8-12 kHz part of the voice folding back as harshness.
 */
class Resampler24to16 {
    private val taps: FloatArray
    private val hist = FloatArray(512)
    private var inCount = 0L
    private var outCount = 0L

    init {
        val len = 63
        val fc = 7200.0 / 48000.0
        val mid = (len - 1) / 2
        taps = FloatArray(len) { k ->
            val n = (k - mid).toDouble()
            val sinc = if (n == 0.0) 2 * fc else Math.sin(2 * Math.PI * fc * n) / (Math.PI * n)
            val window = 0.54 - 0.46 * Math.cos(2 * Math.PI * k / (len - 1))
            (sinc * window * 2).toFloat()   // x2: makes up for the inserted zeros
        }
    }

    fun reset() { inCount = 0; outCount = 0; hist.fill(0f) }

    fun process(input: ShortArray): ShortArray {
        val out = ShortArray(input.size * 2 / 3 + 2)
        var o = 0
        for (s in input) {
            hist[(inCount and 511).toInt()] = s.toFloat()
            inCount++
            // Output m sits at upsampled index 3m and needs input up to floor(3m/2).
            while (3 * outCount <= 2 * (inCount - 1)) {
                val idx = 3 * outCount
                var acc = 0f
                var k = (idx % 2).toInt()          // only even upsampled indices hold samples
                while (k < taps.size) {
                    val u = idx - k
                    if (u < 0) break
                    val xi = u / 2
                    if (inCount - xi <= 512) acc += taps[k] * hist[(xi and 511).toInt()]
                    k += 2
                }
                out[o++] = acc.toInt().coerceIn(-32768, 32767).toShort()
                outCount++
            }
        }
        return out.copyOf(o)
    }
}
