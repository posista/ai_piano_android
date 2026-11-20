package app.aipiano.transcribe

import app.aipiano.midi.MidiParser
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Test

/**
 * Misst die Erkennungsgüte: Das Beispielstück wird als Audio gerendert (Sinus mit Obertönen),
 * dann zurückgewandelt. Trefferquote und Fehlerquote werden ausgegeben.
 */
class AccuracyTest {

    @Test(timeout = 300_000)
    fun accuracyOnSampleSong() {
        val rate = 22_050
        val song = MidiParser.parse(File("src/main/assets/samples/ode_an_die_freude.mid").readBytes(), "x")
        val seconds = (song.durationMs / 1000.0) + 1.0
        val signal = FloatArray((seconds * rate).toInt())
        for (note in song.notes) {
            val f0 = 440.0 * 2.0.pow((note.midi - 69) / 12.0)
            val from = (note.startMs / 1000.0 * rate).toInt()
            val to = minOf(signal.size, (note.endMs / 1000.0 * rate).toInt())
            for (i in from until to) {
                val t = (i - from).toDouble() / rate
                val env = minOf(1.0, t / 0.01) * exp(-t * 1.5)
                var v = 0.0
                val partials = doubleArrayOf(1.0, 0.5, 0.33, 0.25)
                for (k in partials.indices) v += partials[k] * sin(2 * PI * f0 * (k + 1) * t)
                signal[i] += (0.15 * env * v).toFloat()
            }
        }

        val detected = SpectralTranscriber().transcribe(signal, rate)
        val expected = song.notes.map { it.midi to it.startMs / 1000.0 }
        val hits = expected.count { (midi, start) ->
            detected.any { it.midi == midi && abs(it.startSec - start) < 0.15 }
        }
        val falsePositives = detected.count { d ->
            expected.none { (midi, start) -> midi == d.midi && abs(d.startSec - start) < 0.15 }
        }
        // Untergrenzen, damit eine Verschlechterung der Erkennung auffällt
        org.junit.Assert.assertTrue("Trefferquote zu niedrig: $hits von ${expected.size}", hits >= expected.size * 0.5)
        org.junit.Assert.assertTrue("Zu viele Fehlnoten: $falsePositives", falsePositives <= detected.size * 0.3)
        println(
            "GENAUIGKEIT: erwartet ${expected.size}, erkannt ${detected.size}, " +
                "richtig $hits (${"%.0f".format(100.0 * hits / expected.size)} %), " +
                "Fehlnoten $falsePositives (${"%.0f".format(100.0 * falsePositives / detected.size.coerceAtLeast(1))} %)",
        )
    }
}
