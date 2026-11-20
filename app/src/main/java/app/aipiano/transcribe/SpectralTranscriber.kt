package app.aipiano.transcribe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Eine erkannte Note: Tonhöhe, Zeitraum in Sekunden und Anschlagstärke (1..127). */
data class DetectedNote(val midi: Int, val startSec: Double, val endSec: Double, val velocity: Int)

/**
 * Umwandlung von Audio in Noten über ein Frequenzspektrum (ohne ML-Modell).
 *
 * Vorgehen: Das Signal wird auf 22,05 kHz gebracht und in überlappende Fenster zerlegt. Für jedes
 * Fenster wird für jede Taste die Summe der Energie an den ersten vier Obertönen bewertet
 * (harmonische Summe). Taste klingt, wenn die Bewertung eine Schwelle überschreitet und lokal
 * am höchsten ist. Aus aufeinanderfolgenden Fenstern entstehen Noten.
 *
 * Grenzen: Gut bei klaren Einzeltönen oder einfachen Akkorden eines Klaviers oder Keyboards.
 * Bei dichter Musik mit Gesang, Schlagzeug und vielen Instrumenten sind Fehler zu erwarten.
 */
class SpectralTranscriber {

    fun transcribe(input: FloatArray, inputRate: Int): List<DetectedNote> {
        val samples = resample(input, inputRate, TARGET_RATE)
        if (samples.size < FRAME) return emptyList()

        val frames = 1 + (samples.size - FRAME) / HOP
        val pitchCount = MAX_MIDI - MIN_MIDI + 1
        val fft = Fft(FRAME)
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        val binHz = TARGET_RATE.toDouble() / FRAME
        val fundamentals = DoubleArray(pitchCount) { 440.0 * 2.0.pow((MIN_MIDI + it - 69) / 12.0) }

        val salience = Array(frames) { DoubleArray(pitchCount) }
        val level = DoubleArray(frames)
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val magnitude = DoubleArray(FRAME / 2)

        for (f in 0 until frames) {
            val start = f * HOP
            var energy = 0.0
            for (i in 0 until FRAME) {
                val x = samples[start + i].toDouble()
                energy += x * x
                re[i] = x * window[i]
                im[i] = 0.0
            }
            level[f] = sqrt(energy / FRAME)
            if (level[f] < SILENCE_RMS) continue

            fft.transform(re, im)
            var peak = 1e-12
            for (k in 0 until FRAME / 2) {
                magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k])
                if (magnitude[k] > peak) peak = magnitude[k]
            }
            for (p in 0 until pitchCount) {
                var sum = 0.0
                for (h in HARMONIC_WEIGHTS.indices) {
                    val bin = ((h + 1) * fundamentals[p] / binHz).roundToInt()
                    if (bin - 1 < 0 || bin + 1 >= FRAME / 2) continue
                    // Kleines Fenster um den Bin, weil die Frequenz selten genau auf einem Bin liegt
                    val local = max(magnitude[bin - 1], max(magnitude[bin], magnitude[bin + 1]))
                    sum += HARMONIC_WEIGHTS[h] * local / peak
                }
                salience[f][p] = sum
            }
        }

        // Taste klingt in einem Fenster, wenn sie über der Schwelle liegt und lokal das Maximum ist
        val active = Array(frames) { BooleanArray(pitchCount) }
        for (f in 0 until frames) {
            if (level[f] < SILENCE_RMS) continue
            for (p in 0 until pitchCount) {
                val s = salience[f][p]
                val left = if (p > 0) salience[f][p - 1] else 0.0
                val right = if (p < pitchCount - 1) salience[f][p + 1] else 0.0
                active[f][p] = s >= ACTIVE_THRESHOLD && s >= left && s >= right
            }
        }

        val notes = ArrayList<DetectedNote>()
        for (p in 0 until pitchCount) {
            var runStart = -1
            var gap = 0
            for (f in 0..frames) {
                val on = f < frames && active[f][p]
                if (on) {
                    if (runStart < 0) runStart = f
                    gap = 0
                } else if (runStart >= 0) {
                    gap++
                    if (gap >= GAP_FRAMES || f == frames) {
                        val lastActive = f - gap
                        addNote(notes, p + MIN_MIDI, runStart, lastActive, salience, frames)
                        runStart = -1
                        gap = 0
                    }
                }
            }
        }
        return notes.sortedWith(compareBy<DetectedNote> { it.startSec }.thenBy { it.midi })
    }

    private fun addNote(
        notes: MutableList<DetectedNote>,
        midi: Int,
        firstFrame: Int,
        lastFrame: Int,
        salience: Array<DoubleArray>,
        frames: Int,
    ) {
        if (lastFrame - firstFrame + 1 < MIN_FRAMES) return
        val pitch = midi - MIN_MIDI
        var sum = 0.0
        for (f in firstFrame..lastFrame) sum += salience[f][pitch]
        val average = sum / (lastFrame - firstFrame + 1)
        val velocity = (60 + 60 * min(average, 2.0) / 2.0).roundToInt().coerceIn(1, 127)
        val startSec = firstFrame.toDouble() * HOP / TARGET_RATE
        val endSec = min(frames.toDouble() * HOP / TARGET_RATE, (lastFrame + 1).toDouble() * HOP / TARGET_RATE)
        notes += DetectedNote(midi, startSec, endSec, velocity)
    }

    companion object {
        const val TARGET_RATE = 22_050
        const val FRAME = 4096
        const val HOP = 512
        const val MIN_MIDI = 21
        const val MAX_MIDI = 108
        private val HARMONIC_WEIGHTS = doubleArrayOf(1.0, 0.7, 0.5, 0.35)
        private const val ACTIVE_THRESHOLD = 1.5
        private const val SILENCE_RMS = 0.005
        private const val GAP_FRAMES = 2
        private const val MIN_FRAMES = 3

        /** Lineare Neuabtastung auf die Zielrate. */
        fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to || input.isEmpty()) return if (from == to) input else FloatArray(0)
            val outSize = (input.size.toLong() * to / from).toInt()
            return FloatArray(outSize) { i ->
                val pos = i.toDouble() * from / to
                val i0 = pos.toInt()
                val frac = (pos - i0).toFloat()
                val a = input[i0]
                val b = input[min(i0 + 1, input.size - 1)]
                a + (b - a) * frac
            }
        }
    }
}
