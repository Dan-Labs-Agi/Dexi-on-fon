package com.danny.glasses

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Phone mic + speaker fallback used only when the glasses are not connected.
 * Same contract as the glasses link: 16 kHz mono PCM16 in and out.
 * Nothing here changes the glasses path; it only fills in when it's absent.
 */
class PhoneAudio(private val log: (String) -> Unit) {

    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    private var track: AudioTrack? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity with the other permissions
    fun startMic(onPcm: (ShortArray, Int) -> Unit) {
        if (micRunning) return
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { log("Phone microphone unavailable"); rec.release(); return }
        mic = rec
        micRunning = true
        rec.startRecording()
        micThread = Thread {
            val buf = ShortArray(640) // 40 ms chunks, like the glasses frames
            while (micRunning) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onPcm(buf, n)
            }
        }.apply { isDaemon = true; start() }
        log("No glasses - using the phone's microphone and speaker")
    }

    fun stopMic() {
        micRunning = false
        runCatching { micThread?.join(300) }
        micThread = null
        runCatching { mic?.stop(); mic?.release() }
        mic = null
    }

    private fun ensureSpeaker() {
        if (track != null) return
        val min = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    fun play(pcm: ShortArray) {
        ensureSpeaker()
        track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    fun stopSpeaker() {
        runCatching { track?.pause(); track?.flush(); track?.stop(); track?.release() }
        track = null
    }

    fun stopAll() { stopMic(); stopSpeaker() }
}
