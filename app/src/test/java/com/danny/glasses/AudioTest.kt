package com.danny.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {
    private fun sine(freq: Double, rate: Int, n: Int, amp: Double = 10000.0) =
        ShortArray(n) { (amp * sin(2 * PI * freq * it / rate)).toInt().toShort() }

    private fun rms(x: ShortArray, skip: Int = 200): Double {
        var s = 0.0
        for (i in skip until x.size) s += x[i].toDouble() * x[i]
        return sqrt(s / (x.size - skip))
    }

    @Test fun adpcmRoundTripIsClose() {
        val pcm = sine(440.0, 16000, 3200)
        val enc = Adpcm()
        val bytes = enc.encode(pcm, 0, pcm.size)
        assertEquals(1600, bytes.size)
        val out = ShortArray(pcm.size)
        val n = Adpcm().decode(bytes, 0, out)
        assertEquals(pcm.size, n)
        var err = 0.0
        for (i in 200 until n) err += (out[i] - pcm[i]).toDouble().let { it * it }
        val snr = 10 * Math.log10(rms(pcm).let { it * it } / (err / (n - 200)))
        println("ADPCM SNR = %.1f dB".format(snr))
        assertTrue("SNR $snr", snr > 20)
    }

    @Test fun adpcmMatchesPythonAudioop() {
        // audioop.lin2adpcm(struct.pack('<4h', 0, 1000, -1000, 32767), 2, None)[0] == b'\x07\x7f'? computed below
        val pcm = shortArrayOf(0, 1000, -1000, 32767)
        val bytes = Adpcm().encode(pcm, 0, 4)
        assertEquals("07 f7", bytes.joinToString(" ") { "%02x".format(it) })
    }

    @Test fun resamplerKeepsVoiceAndCutsHighTones() {
        val r = Resampler24to16()
        val low = r.process(sine(1000.0, 24000, 24000))
        assertEquals(16000.0, low.size.toDouble(), 2.0)
        val lowRatio = rms(low) / (10000 / sqrt(2.0))
        r.reset()
        val high = r.process(sine(10000.0, 24000, 24000))   // would fold to 6 kHz without filtering
        val highRatio = rms(high) / (10000 / sqrt(2.0))
        println("1 kHz kept %.2f, 10 kHz leak %.3f".format(lowRatio, highRatio))
        assertTrue("1 kHz level $lowRatio", lowRatio in 0.9..1.1)
        assertTrue("10 kHz leak $highRatio", highRatio < 0.05)
    }

    @Test fun resamplerWorksInSmallChunks() {
        val whole = sine(1000.0, 24000, 4800)
        val a = Resampler24to16().process(whole)
        val r = Resampler24to16()
        val parts = mutableListOf<Short>()
        var i = 0
        while (i < whole.size) { val n = minOf(337, whole.size - i); parts += r.process(whole.copyOfRange(i, i + n)).toList(); i += n }
        assertEquals(a.toList(), parts)
    }
}
