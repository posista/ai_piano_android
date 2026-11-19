package app.aipiano.input

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Liest Note-On/Note-Off von einem USB-MIDI-Keyboard über [MidiManager] (android.media.midi).
 *
 * Berechtigungen: Für USB-MIDI-Geräte über den MIDI-Dienst ist keine Laufzeit-Berechtigung nötig.
 * Android verbindet das Gerät direkt; benötigt wird nur ein USB-Host-fähiges Handy und ein
 * OTG-Adapter. Ist das Gerät beim Start nicht da, wird es automatisch übernommen, sobald es
 * angeschlossen wird.
 */
class MidiUsbInputSource(context: Context) : NoteInputSource {

    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())

    private val _events = MutableSharedFlow<NoteEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NoteEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow("USB-MIDI: nicht gestartet")
    override val status: StateFlow<String> = _status.asStateFlow()

    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var connectedId: Int? = null
    private var opening = false

    private val parser = MidiStreamParser { status, data1, data2 -> handleMessage(status, data1, data2) }

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parser.feed(data, offset, count)
        }
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (device == null) connectFirstAvailable()
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == connectedId) {
                disconnect()
                _status.value = "USB-MIDI: Gerät wurde getrennt"
                connectFirstAvailable()
            }
        }
    }

    override fun start() {
        val manager = midiManager
        if (manager == null) {
            _status.value = "USB-MIDI wird auf diesem Gerät nicht unterstützt"
            return
        }
        manager.registerDeviceCallback(deviceCallback, handler)
        connectFirstAvailable()
    }

    override fun stop() {
        midiManager?.unregisterDeviceCallback(deviceCallback)
        disconnect()
        _status.value = "USB-MIDI: gestoppt"
    }

    private fun connectFirstAvailable() {
        val manager = midiManager ?: return
        if (opening || device != null) return
        val info = manager.devices.firstOrNull { it.outputPortCount > 0 }
        if (info == null) {
            _status.value = "USB-MIDI: kein Keyboard angeschlossen"
            return
        }
        opening = true
        manager.openDevice(info, { opened -> onDeviceOpened(info, opened) }, handler)
    }

    private fun onDeviceOpened(info: MidiDeviceInfo, opened: MidiDevice?) {
        opening = false
        if (opened == null) {
            _status.value = "USB-MIDI: Gerät konnte nicht geöffnet werden"
            return
        }
        val outputPort = opened.openOutputPort(0)
        if (outputPort == null) {
            opened.close()
            _status.value = "USB-MIDI: Gerät bietet keinen Ausgang an"
            return
        }
        device = opened
        port = outputPort
        connectedId = info.id
        outputPort.connect(receiver)
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI-Gerät"
        _status.value = "USB-MIDI verbunden: $name"
    }

    private fun disconnect() {
        port?.close()
        device?.close()
        port = null
        device = null
        connectedId = null
    }

    private fun handleMessage(status: Int, data1: Int, data2: Int) {
        val now = System.currentTimeMillis()
        when (status and 0xF0) {
            0x90 -> _events.tryEmit(NoteEvent(data1, data2, isOn = data2 > 0, timestampMs = now))
            0x80 -> _events.tryEmit(NoteEvent(data1, data2, isOn = false, timestampMs = now))
        }
    }
}
