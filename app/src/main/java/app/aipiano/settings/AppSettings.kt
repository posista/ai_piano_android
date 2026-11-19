package app.aipiano.settings

import app.aipiano.midi.Hand

/** Woher die gespielten Noten kommen. */
enum class InputSourceType(val label: String) {
    USB_MIDI("USB-MIDI-Keyboard"),
    MICROPHONE("Mikrofon (nur einstimmig)"),
    VIRTUAL("Bildschirmtasten (zum Testen)"),
}

/** Welche Hand geübt wird. Die andere Hand läuft zur Musik mit. */
enum class PracticeHands(val label: String) {
    BOTH("Beide Hände"),
    LEFT("Nur linke Hand"),
    RIGHT("Nur rechte Hand");

    fun includes(hand: Hand): Boolean = when (this) {
        BOTH -> true
        LEFT -> hand == Hand.LEFT
        RIGHT -> hand == Hand.RIGHT
    }
}

data class AppSettings(
    val inputSource: InputSourceType = InputSourceType.USB_MIDI,
    val tempoPercent: Int = 100,
    val practiceHands: PracticeHands = PracticeHands.BOTH,
    /** true = Wartemodus (Stück hält an, bis richtig gespielt), false = Abspielmodus. */
    val waitMode: Boolean = true,
    /** Mikrofon-Empfindlichkeit 0 (nur laute Töne) bis 1 (auch leise Töne). */
    val micSensitivity: Float = 0.6f,
    /** Töne hörbar machen: gespielte Tasten und die Noten, die das Programm selbst spielt. */
    val soundOn: Boolean = true,
    /** Akkordtöne müssen gleichzeitig gehalten werden. Mit dem Mikrofon automatisch aus. */
    val chordsTogether: Boolean = true,
    /** Die nicht geübte Hand ist hörbar (sonst bleibt sie stumm, läuft aber sichtbar mit). */
    val otherHandSound: Boolean = true,
)
