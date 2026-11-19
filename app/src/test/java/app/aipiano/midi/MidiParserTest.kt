package app.aipiano.midi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MidiParserTest {

    /** Baut eine minimale Format-0-Datei: eine Viertelnote C4 bei 120 BPM (480 Ticks/Viertel). */
    private fun singleQuarterNote(): ByteArray {
        val track = byteArrayOf(
            0x00, 0x90.toByte(), 60, 100,      // Note-An C4 bei Tick 0
            0x83.toByte(), 0x60, 0x80.toByte(), 60, 0, // Note-Aus nach 480 Ticks
            0x00, 0xFF.toByte(), 0x2F, 0x00,   // Spurende
        )
        val header = byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
            0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte(),
        )
        val trackHeader = byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte(),
            0, 0, 0, track.size.toByte(),
        )
        return header + trackHeader + track
    }

    @Test
    fun parsesQuarterNoteWithDefaultTempo() {
        val song = MidiParser.parse(singleQuarterNote(), "test.mid")

        assertEquals(1, song.notes.size)
        val note = song.notes[0]
        assertEquals(60, note.midi)
        assertEquals(0L, note.startMs)
        assertEquals(500L, note.endMs)  // 480 Ticks bei 120 BPM = 500 ms
        assertEquals(Hand.RIGHT, note.hand) // einzelne Spur: Töne ab C4 (60) gehören zur rechten Hand
    }

    @Test
    fun rejectsNonMidiData() {
        assertThrows(IllegalArgumentException::class.java) {
            MidiParser.parse("kein midi".toByteArray(), "x.mid")
        }
    }

    @Test
    fun parsesBundledSample() {
        // Gradle führt Unit-Tests im Modulordner "app" aus.
        val file = File("src/main/assets/samples/ode_an_die_freude.mid")
        val song = MidiParser.parse(file.readBytes(), file.name)

        assertEquals(2, song.tracks.size)
        assertEquals(54, song.notes.size)            // 30 Melodietöne + 24 Akkordtöne
        assertEquals(30, song.notes.count { it.hand == Hand.RIGHT })
        assertEquals(24, song.notes.count { it.hand == Hand.LEFT })
        // 8 Takte à 2 s bei 120 BPM; die Akkorde enden 40 Ticks (~42 ms) vor dem Taktende
        assertTrue(song.durationMs in 15_900L..16_000L)
        assertTrue(song.notes.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }
}
