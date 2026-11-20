package app.aipiano.transcribe

import app.aipiano.midi.MidiParser
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToLong

/** Schreibt erkannte Noten als Standard-MIDI-Datei (Format 0, 120 BPM, 480 Ticks pro Viertel). */
object MidiWriter {

    private const val PPQ = 480
    /** Bei 120 BPM ist eine Viertelnote 0,5 s lang, also 960 Ticks pro Sekunde. */
    private const val TICKS_PER_SECOND = PPQ * 2.0

    fun write(notes: List<DetectedNote>, title: String): ByteArray {
        val events = ArrayList<Triple<Long, Int, ByteArray>>() // Tick, Reihenfolge (Aus vor An), Bytes
        for (note in notes) {
            val on = (note.startSec * TICKS_PER_SECOND).roundToLong()
            val off = max(on + 1, (note.endSec * TICKS_PER_SECOND).roundToLong())
            val velocity = note.velocity.coerceIn(1, 127).toByte()
            events += Triple(on, 1, byteArrayOf(0x90.toByte(), note.midi.toByte(), velocity))
            events += Triple(off, 0, byteArrayOf(0x80.toByte(), note.midi.toByte(), 0))
        }
        events.sortWith(compareBy({ it.first }, { it.second }))

        val track = ByteArrayOutputStream()
        writeMeta(track, 0x03, title.toByteArray(Charsets.UTF_8))
        writeMeta(track, 0x51, byteArrayOf(0x07, 0xA1.toByte(), 0x20)) // 500000 µs pro Viertel = 120 BPM
        var last = 0L
        for ((tick, _, bytes) in events) {
            track.write(varLength(tick - last))
            track.write(bytes)
            last = tick
        }
        writeMeta(track, 0x2F, ByteArray(0))

        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, (PPQ shr 8).toByte(), PPQ.toByte()))
        val trackBytes = track.toByteArray()
        out.write("MTrk".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf((trackBytes.size shr 24).toByte(), (trackBytes.size shr 16).toByte(),
            (trackBytes.size shr 8).toByte(), trackBytes.size.toByte()))
        out.write(trackBytes)
        return out.toByteArray()
    }

    private fun writeMeta(out: ByteArrayOutputStream, type: Int, data: ByteArray) {
        out.write(varLength(0))
        out.write(0xFF)
        out.write(type)
        out.write(varLength(data.size.toLong()))
        out.write(data)
    }

    private fun varLength(value: Long): ByteArray {
        var v = value
        val bytes = ArrayList<Int>()
        bytes += (v and 0x7F).toInt()
        v = v shr 7
        while (v > 0) {
            bytes += ((v and 0x7F) or 0x80).toInt()
            v = v shr 7
        }
        return bytes.reversed().map { it.toByte() }.toByteArray()
    }
}
