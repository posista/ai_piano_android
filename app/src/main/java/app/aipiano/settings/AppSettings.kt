package app.aipiano.settings

import app.aipiano.midi.Hand

/** Woher die gespielten Noten kommen. */
enum class InputSourceType(val label: String) {
    USB_MIDI("USB-MIDI-Keyboard"),
    MICROPHONE("Mikrofon"),
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
)
