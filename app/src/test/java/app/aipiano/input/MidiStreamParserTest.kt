package app.aipiano.input

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiStreamParserTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun parsesNoteOnOffWithRunningStatusAndRealtimeBytes() {
        val messages = mutableListOf<List<Int>>()
        val parser = MidiStreamParser { status, d1, d2 -> messages += listOf(status, d1, d2) }

        val stream = bytes(
            0x90, 60, 100,      // Note-An C4
            0xF8,               // Echtzeit-Takt mitten im Strom, wird ignoriert
            64, 80,             // Running Status: Note-An E4
            0xC0, 5,            // Programmwechsel (ein Datenbyte)
            0x80, 60, 0,        // Note-Aus C4
        )
        parser.feed(stream, 0, stream.size)

        assertEquals(
            listOf(
                listOf(0x90, 60, 100),
                listOf(0x90, 64, 80),
                listOf(0xC0, 5, 0),
                listOf(0x80, 60, 0),
            ),
            messages,
        )
    }

    @Test
    fun ignoresSysExAndDataWithoutStatus() {
        val messages = mutableListOf<List<Int>>()
        val parser = MidiStreamParser { status, d1, d2 -> messages += listOf(status, d1, d2) }

        val stream = bytes(0x40, 0x41, 0xF0, 0x7E, 0x01, 0xF7, 0x90, 62, 90)
        parser.feed(stream, 0, stream.size)

        assertEquals(listOf(listOf(0x90, 62, 90)), messages)
    }
}
