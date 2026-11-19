package app.aipiano.ui

/**
 * Geometrie der Klaviatur mit 88 Tasten (A0 = MIDI 21 bis C8 = MIDI 108).
 * Einheit der x-Koordinaten: die Breite einer weißen Taste.
 */
object KeyLayout {
    const val FIRST_KEY = 21
    const val LAST_KEY = 108
    const val WHITE_KEY_COUNT = 52
    const val BLACK_KEY_WIDTH = 0.6f

    private val blackPitchClasses = setOf(1, 3, 6, 8, 10)
    private val lefts = FloatArray(128)

    init {
        var whiteIndex = 0
        for (midi in FIRST_KEY..LAST_KEY) {
            if (isBlack(midi)) {
                // Schwarze Taste sitzt zwischen zwei weißen Tasten
                lefts[midi] = (whiteIndex - 1) + 0.7f
            } else {
                lefts[midi] = whiteIndex.toFloat()
                whiteIndex++
            }
        }
    }

    fun contains(midi: Int): Boolean = midi in FIRST_KEY..LAST_KEY

    fun isBlack(midi: Int): Boolean = (midi % 12) in blackPitchClasses

    /** Linke Kante der Taste in weißen Tasteneinheiten. */
    fun left(midi: Int): Float = lefts[midi]

    /** Breite der Taste in weißen Tasteneinheiten. */
    fun width(midi: Int): Float = if (isBlack(midi)) BLACK_KEY_WIDTH else 1f
}
