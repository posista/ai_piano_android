package app.aipiano.midi

import kotlin.math.max

private const val DEFAULT_TEMPO_US = 500_000 // 120 BPM, wenn die Datei kein Tempo angibt

/**
 * Liest Standard-MIDI-Dateien (SMF, Format 0, 1 und 2) mit tick-basierter Zeit.
 *
 * Hand-Zuordnung (vereinfachte Heuristik):
 *  - Hat die Datei mehrere Spuren mit Noten, gehört eine Spur zur linken Hand, wenn ihre
 *    mittlere Tonhöhe unter C4 (MIDI 60) liegt, sonst zur rechten Hand.
 *  - Hat die Datei nur eine Spur, wird jede Note einzeln nach Tonhöhe getrennt (unter C4 = links).
 */
object MidiParser {

    private const val SPLIT_KEY = 60

    private class RawNote(
        val startTick: Long,
        val endTick: Long,
        val key: Int,
        val velocity: Int,
        val track: Int,
    )

    private class RawTrack(
        val name: String?,
        val notes: List<RawNote>,
        val tempos: List<Pair<Long, Int>>,
    )

    /**
     * Parst die Bytes einer MIDI-Datei.
     * @throws IllegalArgumentException mit deutscher Meldung, wenn die Datei ungültig ist.
     */
    fun parse(data: ByteArray, name: String): Song {
        try {
            return parseUnchecked(data, name)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Die MIDI-Datei ist beschädigt oder unvollständig.", e)
        }
    }

    private fun parseUnchecked(data: ByteArray, name: String): Song {
        val reader = ByteReader(data)
        require(data.size >= 14 && reader.ascii(4) == "MThd") {
            "Keine MIDI-Datei (Header fehlt)."
        }
        val headerLength = reader.u32()
        require(headerLength >= 6) { "Ungültiger MIDI-Header." }
        reader.u16() // Format: für die Auswertung unerheblich, alle Spuren werden gelesen
        val trackCount = reader.u16()
        val division = reader.u16()
        reader.skip(headerLength - 6)
        require((division and 0x8000) == 0) { "SMPTE-Zeitcodes werden nicht unterstützt." }
        require(division > 0) { "Ungültige Zeitauflösung in der MIDI-Datei." }

        val tracks = ArrayList<RawTrack>()
        repeat(trackCount) {
            if (reader.remaining() < 8) return@repeat
            val id = reader.ascii(4)
            val length = reader.u32()
            val end = minOf(reader.pos + length, data.size)
            if (id == "MTrk") {
                tracks += parseTrack(reader, end, tracks.size)
            } else {
                reader.pos = end // unbekannte Chunks überspringen
            }
        }
        require(tracks.any { it.notes.isNotEmpty() }) { "Die MIDI-Datei enthält keine Noten." }

        val tempoMap = TempoMap(
            ppq = division,
            changes = tracks.flatMap { it.tempos }.sortedBy { it.first },
        )

        val assigned = assignHands(tracks)
        val built = assigned.map { (note, hand) ->
            SongNote(
                id = 0,
                midi = note.key,
                startMs = Math.round(tempoMap.toMs(note.startTick)),
                endMs = Math.round(tempoMap.toMs(note.endTick)),
                velocity = note.velocity,
                track = note.track,
                hand = hand,
            )
        }.sortedWith(compareBy<SongNote> { it.startMs }.thenBy { it.midi })
            .mapIndexed { index, note -> note.copy(id = index) }

        val trackInfos = tracks.withIndex()
            .filter { it.value.notes.isNotEmpty() }
            .map { (index, track) ->
                TrackInfo(
                    index = index,
                    name = track.name?.takeIf { it.isNotBlank() } ?: "Spur ${index + 1}",
                    noteCount = track.notes.size,
                )
            }

        return Song(
            name = name,
            notes = built,
            durationMs = built.maxOf { it.endMs },
            tracks = trackInfos,
            lowestKey = built.minOf { it.midi },
            highestKey = built.maxOf { it.midi },
        )
    }

    /** Ordnet jeder Note eine Hand zu (siehe Klassenkommentar). */
    private fun assignHands(tracks: List<RawTrack>): List<Pair<RawNote, Hand>> {
        val tracksWithNotes = tracks.withIndex().filter { it.value.notes.isNotEmpty() }
        val multiTrack = tracksWithNotes.size >= 2
        val averageKey = tracksWithNotes.associate { (index, track) ->
            index to track.notes.map { it.key }.average()
        }

        val result = ArrayList<Pair<RawNote, Hand>>()
        for ((index, track) in tracks.withIndex()) {
            for (note in track.notes) {
                val reference = if (multiTrack) averageKey.getValue(index) else note.key.toDouble()
                val hand = if (reference < SPLIT_KEY) Hand.LEFT else Hand.RIGHT
                result += note to hand
            }
        }
        return result
    }

    private fun parseTrack(reader: ByteReader, end: Int, index: Int): RawTrack {
        var tick = 0L
        var runningStatus = 0
        var trackName: String? = null
        val tempos = ArrayList<Pair<Long, Int>>()
        val notes = ArrayList<RawNote>()
        // Offene Noten je (Kanal, Tonhöhe), FIFO-Reihenfolge: Startzeit und Anschlagstärke
        val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()

        fun noteOn(channel: Int, key: Int, velocity: Int) {
            open.getOrPut(channel * 128 + key) { ArrayDeque() }.addLast(tick to velocity)
        }

        fun noteOff(channel: Int, key: Int) {
            val queue = open[channel * 128 + key] ?: return
            if (queue.isEmpty()) return
            val (start, velocity) = queue.removeFirst()
            notes += RawNote(start, tick, key, velocity, index)
        }

        while (reader.pos < end) {
            tick += reader.varLength()
            val first = reader.u8()
            val status: Int
            if ((first and 0x80) != 0) {
                status = first
                if (first < 0xF0) runningStatus = first
            } else {
                require(runningStatus != 0) { "Ungültige Daten in Spur ${index + 1}." }
                status = runningStatus
                reader.pos -= 1 // Datenbyte gehört zum laufenden Status
            }

            when {
                status == 0xFF -> {
                    val type = reader.u8()
                    val length = reader.varLength().toInt()
                    val dataStart = reader.pos
                    var endOfTrack = false
                    when (type) {
                        0x03 -> trackName = String(reader.bytes(dataStart, length), Charsets.UTF_8)
                        0x51 -> if (length == 3) {
                            val b = reader.bytes(dataStart, 3)
                            val microseconds = ((b[0].toInt() and 0xFF) shl 16) or
                                ((b[1].toInt() and 0xFF) shl 8) or
                                (b[2].toInt() and 0xFF)
                            tempos += tick to microseconds
                        }
                        0x2F -> endOfTrack = true
                    }
                    reader.pos = dataStart + length
                    if (endOfTrack) break
                }
                status == 0xF0 || status == 0xF7 -> {
                    reader.pos += reader.varLength().toInt() // SysEx überspringen
                }
                else -> {
                    val kind = status and 0xF0
                    val channel = status and 0x0F
                    val data1 = reader.u8()
                    val data2 = if (kind == 0xC0 || kind == 0xD0) 0 else reader.u8()
                    when (kind) {
                        0x90 -> if (data2 > 0) noteOn(channel, data1, data2) else noteOff(channel, data1)
                        0x80 -> noteOff(channel, data1)
                    }
                }
            }
        }

        // Noten ohne Note-Aus enden am Spurende
        for ((combined, queue) in open) {
            for ((start, velocity) in queue) {
                notes += RawNote(start, tick, combined % 128, velocity, index)
            }
        }
        reader.pos = end
        return RawTrack(trackName, notes, tempos)
    }

    /** Rechnet Ticks in Millisekunden um und berücksichtigt alle Tempo-Änderungen. */
    private class TempoMap(private val ppq: Int, changes: List<Pair<Long, Int>>) {
        private val ticks = ArrayList<Long>()
        private val msAtTick = ArrayList<Double>()
        private val usPerQuarter = ArrayList<Int>()

        init {
            ticks += 0L
            msAtTick += 0.0
            usPerQuarter += DEFAULT_TEMPO_US
            for ((tick, us) in changes) {
                val last = ticks.lastIndex
                if (tick == ticks[last]) {
                    usPerQuarter[last] = us
                    continue
                }
                val ms = msAtTick[last] + (tick - ticks[last]) * usPerQuarter[last] / 1000.0 / ppq
                ticks += tick
                msAtTick += ms
                usPerQuarter += us
            }
        }

        fun toMs(tick: Long): Double {
            var i = ticks.lastIndex
            while (i > 0 && ticks[i] > tick) i--
            return msAtTick[i] + (tick - ticks[i]) * usPerQuarter[i] / 1000.0 / ppq
        }
    }

    /** Sequentieller Byte-Leser mit MIDI-Datentypen (Big Endian, Variable-Length-Quantity). */
    private class ByteReader(private val data: ByteArray) {
        var pos = 0

        fun remaining(): Int = data.size - pos

        fun u8(): Int = data[pos++].toInt() and 0xFF

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Int = (u16() shl 16) or u16()

        fun skip(count: Int) {
            pos += count
        }

        fun bytes(start: Int, length: Int): ByteArray = data.copyOfRange(start, start + length)

        fun ascii(length: Int): String {
            val text = String(bytes(pos, length), Charsets.ISO_8859_1)
            pos += length
            return text
        }

        fun varLength(): Long {
            var value = 0L
            repeat(4) {
                val b = u8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if ((b and 0x80) == 0) return value
            }
            return value
        }
    }
}
