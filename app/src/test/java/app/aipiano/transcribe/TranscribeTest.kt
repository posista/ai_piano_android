package app.aipiano.transcribe

import app.aipiano.midi.MidiParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeTest {

    private val rate = 22_050

    /** Klavierähnlicher Ton mit Obertönen; Anschlag und Ausklingen kurz gehalten. */
    private fun addTone(signal: FloatArray, midi: Int, startSec: Double, endSec: Double, amp: Double) {
        val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val from = (startSec * rate).toInt()
        val to = minOf(signal.size, (endSec * rate).toInt())
        for (i in from until to) {
            val t = (i - from).toDouble() / rate
            val env = minOf(1.0, t / 0.01) * exp(-(i - from).toDouble() / rate * 0.8)
            var v = 0.0
            val partials = doubleArrayOf(1.0, 0.5, 0.33, 0.25)
            for (k in partials.indices) v += partials[k] * sin(2 * PI * f0 * (k + 1) * t)
            signal[i] += (amp * env * v).toFloat()
        }
    }

    /** Erwartet: C4 0–1 s, E4+G4 1–2 s, A4 2–3 s. */
    private fun testSignal(): FloatArray {
        val s = FloatArray(3 * rate)
        addTone(s, 60, 0.0, 1.0, 0.2)
        addTone(s, 64, 1.0, 2.0, 0.2)
        addTone(s, 67, 1.0, 2.0, 0.2)
        addTone(s, 69, 2.0, 3.0, 0.2)
        return s
    }

    private fun assertNear(expected: Long, actual: Long) {
        assertTrue("erwartet ~$expected, war $actual", abs(expected - actual) <= 2L)
    }

    @Test
    fun recognisesSimpleNotesAndChord() {
        val notes = SpectralTranscriber().transcribe(testSignal(), rate)
        val expected = listOf(60 to 0.0, 64 to 1.0, 67 to 1.0, 69 to 2.0)
        for ((midi, start) in expected) {
            assertTrue(
                "Note $midi bei $start s fehlt. Gefunden: $notes",
                notes.any { it.midi == midi && abs(it.startSec - start) < 0.2 },
            )
        }
        assertTrue("Zu viele Fehlnoten: $notes", notes.size <= expected.size + 2)
    }

    @Test
    fun silenceGivesNoNotes() {
        assertTrue(SpectralTranscriber().transcribe(FloatArray(2 * rate), rate).isEmpty())
    }

    @Test
    fun midiWriterRoundTripsThroughParser() {
        val notes = listOf(
            DetectedNote(60, 0.0, 1.0, 90),
            DetectedNote(64, 1.0, 2.5, 80),
            DetectedNote(43, 1.0, 2.0, 70),
        )
        val bytes = MidiWriter.write(notes, "Test")
        val song = MidiParser.parse(bytes, "test.mid")

        assertEquals(3, song.notes.size)
        val byStart = song.notes.sortedWith(compareBy({ it.startMs }, { it.midi }))
        assertEquals(60, byStart[0].midi)
        assertEquals(0L, byStart[0].startMs)
        assertNear(1000L, byStart[0].endMs)
        assertEquals(43, byStart[1].midi)
        assertNear(1000L, byStart[1].startMs)
        assertNear(2500L, song.notes.first { it.midi == 64 }.endMs)
    }

    @Test
    fun wavReaderReadsSixteenBitMono() {
        val samples = shortArrayOf(0, 16384, -16384, 32767)
        val dataLen = samples.size * 2
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.write("RIFF".toByteArray()); out.write(le32(36 + dataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16))
        out.write(le16(1)); out.write(le16(1)); out.write(le32(22_050)); out.write(le32(44_100))
        out.write(le16(2)); out.write(le16(16))
        out.write("data".toByteArray()); out.write(le32(dataLen))
        for (s in samples) out.write(le16(s.toInt()))

        val wav = WavReader.read(out.toByteArray())
        assertEquals(22_050, wav.sampleRate)
        assertEquals(4, wav.samples.size)
        assertEquals(0.5f, wav.samples[1], 0.001f)
        assertEquals(-0.5f, wav.samples[2], 0.001f)
    }
}
