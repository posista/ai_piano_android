package app.aipiano.review

import app.aipiano.audio.PitchMath
import app.aipiano.audio.YinPitchDetector
import app.aipiano.midi.MidiParser
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** Robustheitstests für Parser und Tonhöhenerkennung (reine JVM-Tests). */
class AlgorithmReviewTest {

    @Test(timeout = 120_000)
    fun parserSurvivesCorruptedFiles() {
        val base = File("src/main/assets/samples/ode_an_die_freude.mid").readBytes()
        val rnd = Random(42)
        val unexpected = sortedMapOf<String, Int>()
        val messages = sortedMapOf<String, Int>()
        repeat(30_000) {
            var data = base.copyOf()
            repeat(1 + rnd.nextInt(8)) { data[rnd.nextInt(data.size)] = rnd.nextInt(256).toByte() }
            if (rnd.nextInt(5) == 0) data = data.copyOf(rnd.nextInt(data.size))
            try {
                MidiParser.parse(data, "x")
            } catch (e: IllegalArgumentException) {
                messages.merge(e.message ?: "(keine Meldung)", 1, Int::plus)
            } catch (e: Throwable) {
                unexpected.merge("${e::class.java.simpleName}: ${e.message?.take(80)}", 1, Int::plus)
            }
        }
        println("PARSER-MELDUNGEN: $messages")
        println("PARSER-UNERWARTET: $unexpected")
        assertTrue("Unerwartete Ausnahmen: $unexpected", unexpected.isEmpty())
    }

    @Test(timeout = 60_000)
    fun parserHandlesLargeFileQuickly() {
        // 1 Spur, 20 000 Noten (Sechzehntel), ca. 42 Minuten bei 120 BPM
        val track = ArrayList<Byte>()
        fun vl(v: Int) { if (v >= 128) track += (0x80 or (v shr 7)).toByte(); track += (v and 0x7F).toByte() }
        repeat(20_000) { i ->
            vl(0); track += 0x90.toByte(); track += (40 + i % 40).toByte(); track += 90
            vl(120); track += 0x80.toByte(); track += (40 + i % 40).toByte(); track += 0
        }
        vl(0); track += 0xFF.toByte(); track += 0x2F; track += 0
        val len = track.size
        val bytes = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte()) +
            "MTrk".toByteArray() + byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()) +
            track.toByteArray()
        val t0 = System.nanoTime()
        val song = MidiParser.parse(bytes, "gross.mid")
        val ms = (System.nanoTime() - t0) / 1e6
        println("PARSER-GROSS: ${song.notes.size} Noten in $ms ms")
        assertTrue(song.notes.size == 20_000)
    }

    private fun detect(signal: (Int) -> Double): Int? {
        val sr = 44_100
        val frame = FloatArray(2048) { signal(it).toFloat() }
        return YinPitchDetector(sr, 2048).detect(frame)?.let { PitchMath.nearestMidi(it.frequencyHz) }
    }

    /** Klavierähnliches Signal: Obertöne mit Inharmonizität, Abklingen und etwas Rauschen. */
    @Test
    fun yinOnPianoLikeTones() {
        val sr = 44_100.0
        val rnd = Random(1)
        val failures = mutableListOf<String>()
        var total = 0
        for (midi in 33..96) { // A1 bis C7
            total++
            val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val amps = doubleArrayOf(1.0, 0.9, 0.6, 0.45, 0.3, 0.2, 0.15, 0.1)
            val b = 0.0004
            val result = detect { i ->
                val t = i / sr
                var v = 0.0
                for (k in amps.indices) {
                    val n = k + 1
                    val fk = n * f0 * sqrt(1 + b * n * n)
                    if (fk < sr / 2) v += amps[k] * sin(2 * PI * fk * t) * exp(-t * 3.0 * n)
                }
                0.2 * v + 0.01 * (rnd.nextDouble() * 2 - 1)
            }
            if (result != midi) failures += "$midi→$result"
        }
        println("YIN-KLAVIER: ${total - failures.size}/$total richtig, Fehler: $failures")
    }

    /** Starker zweiter Oberton (typisch für tiefe Klaviertöne): Oktavfehler? */
    @Test
    fun yinWithDominantSecondHarmonic() {
        val sr = 44_100.0
        val failures = mutableListOf<String>()
        for (midi in 33..72) {
            val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val result = detect { i ->
                val t = i / sr
                0.15 * (0.4 * sin(2 * PI * f0 * t) + 1.0 * sin(2 * PI * 2 * f0 * t) + 0.5 * sin(2 * PI * 3 * f0 * t))
            }
            if (result != midi) failures += "$midi→$result"
        }
        println("YIN-OBERTON: Fehler bei ${failures.size}/40: $failures")
    }

    /** Wiederholter gleicher Ton ohne Pause (E4 E4): erkennt die Logik einen zweiten Anschlag? */
    @Test
    fun repeatedSameNoteIsOneContinuousPitch() {
        // Dokumentiert nur: YIN liefert in beiden Fenstern dieselbe Tonhöhe, eine neue Note
        // entsteht in MicrophoneInputSource nur nach zwei stillen Fenstern.
        val sr = 44_100.0
        val f0 = 329.63
        val first = detect { i -> 0.3 * sin(2 * PI * f0 * i / sr) }
        val second = detect { i -> 0.3 * sin(2 * PI * f0 * (i + 2048) / sr) }
        println("WIEDERHOLUNG: $first / $second")
    }

    /** Spitzenpegel des Synthesizers bei einem Akkord aus dem Beispielstück. */
    @Test
    fun synthPeakLevelForSampleChord() {
        val sr = 44_100
        val voices = listOf(64 to 96, 48 to 64, 52 to 64, 55 to 64) // E4 rechts + C-Dur links
        var peak = 0.0
        var clipped = 0
        for (i in 0 until sr / 2) {
            var mix = 0.0
            for ((midi, vel) in voices) {
                val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val phase = 2 * PI * f * i / sr
                val env = minOf(1.0, i / (0.005 * sr)) * exp(-i / (1.8 * sr))
                mix += (sin(phase) + 0.45 * sin(2 * phase) + 0.2 * sin(3 * phase)) * env * (vel / 127.0) * 0.35
            }
            if (kotlin.math.abs(mix) > peak) peak = kotlin.math.abs(mix)
            if (kotlin.math.abs(mix) > 1.0) clipped++
        }
        println("SYNTH-PEGEL: Spitze ${"%.2f".format(peak)}, übersteuerte Samples in 0,5 s: $clipped von ${sr / 2}")
    }
}
