package app.aipiano.midi

/**
 * Umrechnung zwischen MIDI-Tonhöhe und Notenlinien.
 * Eine "Stufe" ist ein Schritt auf dem Notensystem: zwei Stufen liegen auf zwei Linien oder
 * Zwischenräumen, also eine Linie auf die nächste Linie. Stufe = Oktave * 7 + Buchstabe (C=0 … B=6).
 */
object StaffMath {

    /** Buchstabe (0 = C … 6 = B) für jede Tonklasse; schwarze Tasten gehören zum Buchstaben darunter. */
    private val letterOfPitchClass = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
    /** Halbtonabstand jedes natürlichen Buchstabens von C. */
    private val semitoneOfLetter = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /** Stufe der untersten Notenlinie im Violinschlüssel: E4. */
    const val TREBLE_BOTTOM_STEP = 30
    /** Stufe der untersten Notenlinie im Bassschlüssel: G2. */
    const val BASS_BOTTOM_STEP = 18

    fun step(midi: Int): Int {
        val octave = midi / 12 - 1
        return octave * 7 + letterOfPitchClass[midi % 12]
    }

    /** Natürlicher Ton zur Stufe. */
    fun midiOfStep(step: Int): Int {
        val octave = Math.floorDiv(step, 7)
        val letter = Math.floorMod(step, 7)
        return (octave + 1) * 12 + semitoneOfLetter[letter]
    }

    fun isBlack(midi: Int): Boolean = midi % 12 in setOf(1, 3, 6, 8, 10)
}
