package app.aipiano.input

/**
 * Zerlegt einen Byte-Strom (z. B. von USB-MIDI) in Kanalnachrichten.
 * Berücksichtigt Running Status und ignoriert Echtzeit-Nachrichten, die mitten in einer
 * Nachricht stehen dürfen. System-Exklusiv- und andere Systemnachrichten werden übersprungen.
 */
class MidiStreamParser(private val onMessage: (status: Int, data1: Int, data2: Int) -> Unit) {

    private var status = 0
    private val args = IntArray(2)
    private var argCount = 0

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> Unit // Echtzeit-Nachricht: ignorieren, laufender Status bleibt erhalten
                b >= 0xF0 -> {
                    status = 0 // System-Nachricht oder SysEx: Status zurücksetzen
                    argCount = 0
                }
                b >= 0x80 -> {
                    status = b
                    argCount = 0
                }
                status == 0 -> Unit // Datenbyte ohne Status: verwerfen
                else -> {
                    args[argCount++] = b
                    if (argCount == dataLength(status)) {
                        onMessage(status, args[0], if (argCount == 2) args[1] else 0)
                        argCount = 0
                    }
                }
            }
        }
    }

    private fun dataLength(status: Int): Int {
        val kind = status and 0xF0
        return if (kind == 0xC0 || kind == 0xD0) 1 else 2
    }
}
