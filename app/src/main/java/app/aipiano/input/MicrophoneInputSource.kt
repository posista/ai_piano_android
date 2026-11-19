package app.aipiano.input

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.aipiano.audio.PitchMath
import app.aipiano.audio.YinPitchDetector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Wandelt Mikrofon-Audio in Noten-Ereignisse um: Tonhöhe per YIN, Lautstärke als Anschlagstärke.
 *
 * Nur einstimmig: Akkorde lassen sich mit einem Mikrofon nicht trennen. Eine Note gilt als
 * gespielt, wenn dieselbe Tonhöhe in zwei aufeinanderfolgenden Fenstern erkannt wird; sie
 * endet, wenn es still wird oder ein anderer Ton erkannt wird.
 *
 * Die Berechtigung RECORD_AUDIO muss vor dem Start erteilt sein (siehe [hasRecordAudioPermission]).
 *
 * @param sensitivity Empfindlichkeit 0 (nur laute Töne) bis 1 (auch leise Töne), live abfragbar
 */
class MicrophoneInputSource(private val sensitivity: () -> Float) : NoteInputSource {

    private val _events = MutableSharedFlow<NoteEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NoteEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow("Mikrofon: inaktiv")
    override val status: StateFlow<String> = _status.asStateFlow()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    override fun start() {
        if (running) return
        running = true
        _status.value = "Mikrofon: startet …"
        thread = Thread { captureLoop() }.apply {
            name = "mic-pitch"
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        thread = null
        _status.value = "Mikrofon: gestoppt"
    }

    @SuppressLint("MissingPermission") // Berechtigung wird vor dem Start geprüft
    private fun captureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            fail("Mikrofon: Audio-Format wird nicht unterstützt")
            return
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, FRAME_SIZE * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail("Mikrofon konnte nicht geöffnet werden")
            return
        }

        val detector = YinPitchDetector(SAMPLE_RATE, FRAME_SIZE)
        val window = FloatArray(FRAME_SIZE)
        val chunk = ShortArray(HOP_SIZE)
        var currentMidi: Int? = null
        var candidate: Int? = null
        var candidateFrames = 0
        var silentFrames = 0

        try {
            record.startRecording()
            _status.value = "Mikrofon aktiv (einstimmig)"
            while (running) {
                val read = record.read(chunk, 0, HOP_SIZE)
                if (read <= 0) continue

                // Fenster um einen Abschnitt nach links schieben und neue Samples anhängen
                System.arraycopy(window, HOP_SIZE, window, 0, FRAME_SIZE - HOP_SIZE)
                for (i in 0 until HOP_SIZE) {
                    window[FRAME_SIZE - HOP_SIZE + i] = if (i < read) chunk[i] / 32768f else 0f
                }

                val level = rms(window)
                val loudEnough = level >= noiseThreshold(sensitivity())
                val pitch = if (loudEnough) detector.detect(window) else null
                val midi = pitch?.let { PitchMath.nearestMidi(it.frequencyHz) }
                    ?.takeIf { it in MIN_MIDI..MAX_MIDI }

                if (midi == null) {
                    candidate = null
                    candidateFrames = 0
                    silentFrames++
                    if (silentFrames >= SILENCE_FRAMES && currentMidi != null) {
                        emit(currentMidi, velocity = 0, isOn = false)
                        currentMidi = null
                    }
                    continue
                }

                silentFrames = 0
                if (midi == candidate) {
                    candidateFrames++
                } else {
                    candidate = midi
                    candidateFrames = 1
                }
                if (candidateFrames >= CONFIRM_FRAMES && midi != currentMidi) {
                    currentMidi?.let { emit(it, velocity = 0, isOn = false) }
                    emit(midi, velocity = velocityFor(level), isOn = true)
                    currentMidi = midi
                }
            }
        } finally {
            currentMidi?.let { emit(it, velocity = 0, isOn = false) }
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // Aufnahme war bereits beendet
            }
            record.release()
        }
    }

    private fun fail(message: String) {
        running = false
        _status.value = message
    }

    private fun emit(midi: Int, velocity: Int, isOn: Boolean) {
        _events.tryEmit(
            NoteEvent(midi = midi, velocity = velocity, isOn = isOn, timestampMs = System.currentTimeMillis()),
        )
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 2048
        const val HOP_SIZE = 1024
        /** So viele aufeinanderfolgende Fenster muss dieselbe Tonhöhe haben (≈ 46 ms je Fenster). */
        const val CONFIRM_FRAMES = 2
        const val SILENCE_FRAMES = 2
        const val MIN_MIDI = 21
        const val MAX_MIDI = 108

        /** Lautstärke, unterhalb derer nichts ausgewertet wird. Höhere Empfindlichkeit = niedrigere Schwelle. */
        fun noiseThreshold(sensitivity: Float): Float =
            0.003f + (1f - sensitivity.coerceIn(0f, 1f)) * 0.1f

        fun rms(samples: FloatArray): Float {
            var sum = 0f
            for (s in samples) sum += s * s
            return sqrt(sum / samples.size)
        }

        fun velocityFor(level: Float): Int = (40 + level * 200f).toInt().coerceIn(40, 127)
    }
}
