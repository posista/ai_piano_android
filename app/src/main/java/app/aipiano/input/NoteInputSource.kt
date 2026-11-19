package app.aipiano.input

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ein Noten-Ereignis einer beliebigen Eingabequelle.
 * @param midi MIDI-Notennummer (60 = C4)
 * @param velocity Anschlagstärke 0..127 (bei Mikrofon geschätzt)
 * @param isOn true = Taste gedrückt, false = losgelassen
 */
data class NoteEvent(
    val midi: Int,
    val velocity: Int,
    val isOn: Boolean,
    val timestampMs: Long,
)

/**
 * Gemeinsame Schnittstelle für alle Eingabequellen (USB-MIDI, Mikrofon, Bildschirmtasten).
 * Der Rest der App kennt nur diesen Flow und ist damit unabhängig von der Quelle.
 */
interface NoteInputSource {
    /** Alle Noten-Ereignisse, solange die Quelle aktiv ist. */
    val events: SharedFlow<NoteEvent>

    /** Kurze Statusmeldung für die Anzeige (z. B. "USB-MIDI verbunden: ..."). */
    val status: StateFlow<String>

    fun start()

    fun stop()
}
