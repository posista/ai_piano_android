package app.aipiano.midi

/** Zu welcher Hand eine Note gehört. */
enum class Hand { LEFT, RIGHT }

/**
 * Eine einzelne Note eines Stücks, mit Zeiten bereits in Millisekunden umgerechnet
 * (Tempo-Änderungen aus der MIDI-Datei sind berücksichtigt).
 */
data class SongNote(
    val id: Int,
    val midi: Int,
    val startMs: Long,
    val endMs: Long,
    val velocity: Int,
    val track: Int,
    val hand: Hand,
)

/** Eine Spur der MIDI-Datei, nur Spuren mit Noten werden aufgelistet. */
data class TrackInfo(
    val index: Int,
    val name: String,
    val noteCount: Int,
)

/** Ein importiertes Musikstück. [notes] ist nach Startzeit sortiert. */
data class Song(
    val name: String,
    val notes: List<SongNote>,
    val durationMs: Long,
    val tracks: List<TrackInfo>,
    val lowestKey: Int,
    val highestKey: Int,
)
