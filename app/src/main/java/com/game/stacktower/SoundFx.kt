package com.game.stacktower

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.*

class SoundFx {

    private val rate = 22050
    private val thump = makeThump()
    private val over = makeGameOver()
    private val perfect = Array(9) { makePerfect(it) }

    fun playPerfect(level: Int) = play(perfect[level.coerceIn(0, perfect.size - 1)])
    fun playDrop() = play(thump)
    fun playGameOver() = play(over)

    private fun play(data: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.setNotificationMarkerPosition(data.size - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (e: Exception) {
            // Kalau audio gagal, game tetap jalan tanpa suara
        }
    }

    private fun toPcm(buf: DoubleArray): ShortArray =
        ShortArray(buf.size) { (buf[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }

    // "Ting" lonceng: tiga nada naik cepat, makin tinggi tiap combo
    private fun makePerfect(level: Int): ShortArray {
        val scale = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19)
        val semis = scale[min(level, scale.size - 1)]
        val base = 523.25 * 2.0.pow(semis / 12.0)
        val total = (rate * 0.75).toInt()
        val buf = DoubleArray(total)
        val notes = listOf(base to 0.0, base * 1.5 to 0.07, base * 2.0 to 0.14)
        for ((f, start) in notes) {
            val s0 = (start * rate).toInt()
            var i = 0
            while (s0 + i < total) {
                val t = i.toDouble() / rate
                val env = min(1.0, t / 0.005) * exp(-t * 6.0)
                val w = sin(2 * PI * f * t) +
                        0.3 * sin(2 * PI * 2 * f * t) +
                        0.1 * sin(2 * PI * 3 * f * t)
                buf[s0 + i] += w * env * 0.25
                i++
            }
        }
        return toPcm(buf)
    }

    // "Tuk" rendah saat balok mendarat
    private fun makeThump(): ShortArray {
        val total = (rate * 0.14).toInt()
        val buf = DoubleArray(total)
        var phase = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / rate
            val f = 200.0 - 110.0 * (t / 0.14)
            phase += 2 * PI * f / rate
            buf[i] = sin(phase) * exp(-t * 28.0) * 0.8
        }
        return toPcm(buf)
    }

    // "Wah wah wah waaah" trombon sedih
    private fun makeGameOver(): ShortArray {
        val notes = listOf(
            Triple(311.0, 0.0, 0.28),
            Triple(293.7, 0.30, 0.28),
            Triple(277.2, 0.60, 0.28),
            Triple(261.6, 0.90, 0.95)
        )
        val total = (rate * 1.95).toInt()
        val buf = DoubleArray(total)
        for ((idx, n) in notes.withIndex()) {
            val (f, start, dur) = n
            val s0 = (start * rate).toInt()
            val len = (dur * rate).toInt()
            var phase = 0.0
            var lp = 0.0
            for (i in 0 until len) {
                if (s0 + i >= total) break
                val t = i.toDouble() / rate
                val vib = if (idx == notes.size - 1) 1.0 + 0.025 * sin(2 * PI * 6 * t) else 1.0
                phase += f * vib / rate
                val saw = 2.0 * (phase - floor(phase)) - 1.0
                val alpha = 0.08 + 0.25 * sin(PI * min(1.0, t / dur))
                lp += alpha * (saw - lp)
                val env = min(1.0, t / 0.02) * min(1.0, (dur - t) / 0.06)
                buf[s0 + i] += lp * env * 1.3
            }
        }
        return toPcm(buf)
    }
}