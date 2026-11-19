package app.aipiano.input

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Eingabe über die Tasten auf dem Bildschirm. Praktisch zum Testen ohne USB-Keyboard.
 * Die UI ruft [press] auf, wenn eine Taste berührt bzw. losgelassen wird.
 */
class VirtualKeyboardInputSource : NoteInputSource {

    private val _events = MutableSharedFlow<NoteEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NoteEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow("Bildschirmtasten: inaktiv")
    override val status: StateFlow<String> = _status.asStateFlow()

    fun press(midi: Int, down: Boolean) {
        _events.tryEmit(
            NoteEvent(
                midi = midi,
                velocity = if (down) 100 else 0,
                isOn = down,
                timestampMs = System.currentTimeMillis(),
            ),
        )
    }

    override fun start() {
        _status.value = "Bildschirmtasten: aktiv"
    }

    override fun stop() {
        _status.value = "Bildschirmtasten: inaktiv"
    }
}
