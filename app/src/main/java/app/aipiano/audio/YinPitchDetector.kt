package app.aipiano.audio

import kotlin.math.log2
import kotlin.math.roundToInt

/** Ergebnis einer Tonhöhenmessung: Grundfrequenz und Sicherheit (1 = sehr sauber, 0 = unsicher). */
data class PitchResult(val frequencyHz: Double, val clarity: Float)

/**
 * Tonhöhenerkennung nach dem YIN-Algorithmus (de Cheveigné und Kawahara, 2002).
 * Eigenständige Umsetzung ohne externe Bibliothek.
 *
 * @param sampleRate Abtastrate in Hz
 * @param frameSize Anzahl Samples pro Analysefenster (Zweierpotenz, z. B. 2048)
 * @param threshold Schwelle für den ersten Tiefpunkt der Differenzfunktion (0.1 bis 0.2 üblich)
 */
class YinPitchDetector(
    private val sampleRate: Int,
    frameSize: Int,
    private val threshold: Float = 0.15f,
) {
    private val halfSize = frameSize / 2
    private val difference = FloatArray(halfSize)
    private val cumulative = FloatArray(halfSize)

    /** Liefert die Grundfrequenz des Fensters oder null, wenn kein eindeutiger Ton erkannt wird. */
    fun detect(frame: FloatArray): PitchResult? {
        // 1) Differenzfunktion: wie ähnlich ist das Signal sich selbst um tau verschoben?
        for (tau in 1 until halfSize) {
            var sum = 0f
            for (j in 0 until halfSize) {
                val d = frame[j] - frame[j + tau]
                sum += d * d
            }
            difference[tau] = sum
        }

        // 2) Kumulativ normierte Differenz (macht die Schwelle unabhängig von der Lautstärke)
        cumulative[0] = 1f
        var running = 0f
        for (tau in 1 until halfSize) {
            running += difference[tau]
            cumulative[tau] = if (running > 0f) difference[tau] * tau / running else 1f
        }

        // 3) Erster Wert unter der Schwelle, dann bis zum lokalen Minimum laufen
        var tau = 2
        while (tau < halfSize) {
            if (cumulative[tau] < threshold) {
                while (tau + 1 < halfSize && cumulative[tau + 1] < cumulative[tau]) tau++
                break
            }
            tau++
        }
        if (tau >= halfSize - 1 || cumulative[tau] >= threshold) return null

        // 4) Parabolische Interpolation: genauere Periodenlänge zwischen Samples
        val s0 = cumulative[tau - 1]
        val s1 = cumulative[tau]
        val s2 = cumulative[tau + 1]
        val denominator = 2f * (2f * s1 - s2 - s0)
        val betterTau = if (denominator != 0f) tau + (s2 - s0) / denominator else tau.toFloat()
        if (betterTau <= 0f) return null

        return PitchResult(
            frequencyHz = sampleRate / betterTau.toDouble(),
            clarity = (1f - s1).coerceIn(0f, 1f),
        )
    }
}

/** Umrechnung zwischen Frequenz und MIDI-Notennummer (Kammerton a' = 440 Hz = MIDI 69). */
object PitchMath {
    fun frequencyToMidi(frequencyHz: Double): Double = 69.0 + 12.0 * log2(frequencyHz / 440.0)

    fun nearestMidi(frequencyHz: Double): Int = frequencyToMidi(frequencyHz).roundToInt()
}
