package app.aipiano.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Einfacher polyphoner Klavier-Synthesizer ohne Samples: Sinus mit zwei Obertönen,
 * kurzer Anschlag, langsam abklingende Lautstärke und kurzes Ausklingen beim Loslassen.
 * Die Ausgabe läuft über [AudioTrack] im Stream-Modus.
 *
 * Töne werden über einen Schlüssel (z. B. "user-60" oder "song-12") verwaltet, damit
 * ein Note-Aus immer zum passenden Note-An gehört.
 */
class NoteSynth(private val sampleRate: Int = SAMPLE_RATE) {

    private class Voice(
        val key: String,
        val midi: Int,
        val velocity: Double,
        sampleRate: Int,
    ) {
        private val increment = 2 * PI * (440.0 * 2.0.pow((midi - 69) / 12.0)) / sampleRate
        private var phase = 0.0
        private var age = 0
        var releasing = false
            private set
        private var releaseAge = 0
        var finished = false
            private set

        fun markReleased() {
            releasing = true
        }

        /** Nächster Ausgabewert dieser Stimme (ungefähr im Bereich -1..1). */
        fun next(sampleRate: Int): Double {
            phase += increment
            if (phase > 2 * PI) phase -= 2 * PI

            val attack = min(1.0, age / (ATTACK_S * sampleRate))
            var envelope = attack * exp(-age / (DECAY_S * sampleRate))
            if (releasing) {
                envelope *= exp(-releaseAge / (RELEASE_S * sampleRate))
                releaseAge++
            }
            age++
            if (releasing && envelope < SILENCE || age > MAX_AGE_S * sampleRate) finished = true

            val wave = sin(phase) + 0.45 * sin(2 * phase) + 0.2 * sin(3 * phase)
            return wave * envelope * velocity * 0.25
        }
    }

    private val lock = Any()
    private val voices = ArrayList<Voice>()
    @Volatile
    private var running = false
    private var track: AudioTrack? = null

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return
        val output = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, BLOCK_SIZE * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        output.play()
        track = output
        running = true
        Thread { writeLoop(output) }.apply {
            name = "note-synth"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        synchronized(lock) { voices.clear() }
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // bereits gestoppt
            }
            it.release()
        }
        track = null
    }

    fun noteOn(key: String, midi: Int, velocity: Int) {
        if (!running) return
        synchronized(lock) {
            voices.removeAll { it.key == key }
            voices += Voice(key, midi, velocity.coerceIn(1, 127) / 127.0, sampleRate)
            if (voices.size > MAX_VOICES) voices.removeAt(0)
        }
    }

    fun noteOff(key: String) {
        synchronized(lock) {
            voices.filter { it.key == key }.forEach { it.markReleased() }
        }
    }

    private fun writeLoop(output: AudioTrack) {
        val block = ShortArray(BLOCK_SIZE)
        try {
            while (running) {
                render(block)
                output.write(block, 0, block.size) // blockiert, bis Platz im Puffer ist
            }
        } catch (_: Exception) {
            // Ausgabe wurde beendet
        }
    }

    private fun render(out: ShortArray) {
        synchronized(lock) {
            for (i in out.indices) {
                var mix = 0.0
                for (voice in voices) mix += voice.next(sampleRate)
                out[i] = (mix.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            }
            voices.removeAll { it.finished }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BLOCK_SIZE = 512
        const val MAX_VOICES = 32
        const val ATTACK_S = 0.005
        const val DECAY_S = 1.8
        const val RELEASE_S = 0.12
        const val MAX_AGE_S = 8.0
        const val SILENCE = 1e-4
    }
}
