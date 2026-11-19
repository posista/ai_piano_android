package app.aipiano.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aipiano.input.MidiUsbInputSource
import app.aipiano.input.NoteEvent
import app.aipiano.input.NoteInputSource
import app.aipiano.input.VirtualKeyboardInputSource
import app.aipiano.midi.MidiParser
import app.aipiano.midi.Song
import app.aipiano.settings.AppSettings
import app.aipiano.settings.InputSourceType
import app.aipiano.settings.PracticeHands
import app.aipiano.settings.SettingsRepository
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE_ASSET = "samples/ode_an_die_freude.mid"
private const val FRAME_MS = 16L
/** Eine Note gilt als getroffen, wenn sie höchstens so weit vom Zeitpunkt entfernt gespielt wird. */
private const val HIT_WINDOW_MS = 350.0
/** Dauer, wie lange eine falsche Taste rot markiert bleibt. */
private const val WRONG_FLASH_MS = 400L

data class PlayerUiState(
    val song: Song? = null,
    /** Aktuelle Position im Stück in Millisekunden. */
    val positionMs: Double = 0.0,
    val playing: Boolean = false,
    val tempoPercent: Int = 100,
    val practiceHands: PracticeHands = PracticeHands.BOTH,
    val inputSource: InputSourceType = InputSourceType.USB_MIDI,
    val inputStatus: String = "",
    /** Ids der Noten, die richtig gespielt wurden (werden grün). */
    val hitNoteIds: Set<Int> = emptySet(),
    /** Tasten, die gerade gedrückt sind. */
    val pressedKeys: Set<Int> = emptySet(),
    /** Tasten, die kurz rot markiert sind, weil sie falsch waren. */
    val wrongKeys: Set<Int> = emptySet(),
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val error: String? = null,
)

/**
 * Hält das geladene Stück, die Wiedergabeposition und die Eingabe-Auswertung.
 * Die UI beobachtet nur [state] (MVVM).
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val usbSource = MidiUsbInputSource(application)
    private val virtualSource = VirtualKeyboardInputSource()

    private var activeSource: NoteInputSource? = null
    private var activeType: InputSourceType? = null
    private val sourceJobs = mutableListOf<Job>()
    private var clockJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { applySettings(it) }
        }
    }

    private fun applySettings(settings: AppSettings) {
        _state.update {
            it.copy(
                tempoPercent = settings.tempoPercent,
                practiceHands = settings.practiceHands,
                inputSource = settings.inputSource,
            )
        }
        if (settings.inputSource != activeType) switchSource(settings.inputSource)
    }

    private fun switchSource(type: InputSourceType) {
        activeSource?.stop()
        sourceJobs.forEach { it.cancel() }
        sourceJobs.clear()
        activeType = type

        val source = when (type) {
            InputSourceType.USB_MIDI -> usbSource
            InputSourceType.VIRTUAL -> virtualSource
            // Mikrofon-Erkennung folgt in Schritt 5
            InputSourceType.MICROPHONE -> null
        }
        activeSource = source
        if (source == null) {
            _state.update { it.copy(inputStatus = "Mikrofon-Erkennung folgt in einem späteren Schritt") }
            return
        }

        source.start()
        sourceJobs += viewModelScope.launch {
            source.status.collect { text -> _state.update { it.copy(inputStatus = text) } }
        }
        sourceJobs += viewModelScope.launch {
            source.events.collect { handleNote(it) }
        }
    }

    /** Wird von der Bildschirmtastatur aufgerufen (nur wirksam, wenn diese Quelle aktiv ist). */
    fun onVirtualKey(midi: Int, down: Boolean) {
        if (activeType == InputSourceType.VIRTUAL) virtualSource.press(midi, down)
    }

    private fun handleNote(event: NoteEvent) {
        val current = _state.value
        val song = current.song
        if (!event.isOn) {
            _state.update { it.copy(pressedKeys = it.pressedKeys - event.midi) }
            return
        }
        if (song == null) {
            _state.update { it.copy(pressedKeys = it.pressedKeys + event.midi) }
            return
        }

        val match = findNearestNote(song, current.positionMs, event.midi, current.practiceHands, current.hitNoteIds)
        if (match != null) {
            _state.update {
                it.copy(
                    hitNoteIds = it.hitNoteIds + match,
                    pressedKeys = it.pressedKeys + event.midi,
                    correctCount = it.correctCount + 1,
                )
            }
        } else {
            _state.update {
                it.copy(
                    wrongKeys = it.wrongKeys + event.midi,
                    pressedKeys = it.pressedKeys + event.midi,
                    wrongCount = it.wrongCount + 1,
                )
            }
            viewModelScope.launch {
                delay(WRONG_FLASH_MS)
                _state.update { it.copy(wrongKeys = it.wrongKeys - event.midi) }
            }
        }
    }

    private fun findNearestNote(
        song: Song,
        positionMs: Double,
        midi: Int,
        hands: PracticeHands,
        alreadyHit: Set<Int>,
    ): Int? {
        var best: Int? = null
        var bestDistance = HIT_WINDOW_MS
        for (note in song.notes) {
            if (note.midi != midi || note.id in alreadyHit || !hands.includes(note.hand)) continue
            val distance = abs(note.startMs - positionMs)
            if (distance <= bestDistance) {
                best = note.id
                bestDistance = distance
            }
        }
        return best
    }

    fun loadSong(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "MIDI-Datei"
        loadFrom(name) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Die Datei konnte nicht geöffnet werden.")
        }
    }

    fun loadSample() {
        loadFrom("Ode an die Freude") {
            getApplication<Application>().assets.open(SAMPLE_ASSET).use { it.readBytes() }
        }
    }

    private fun loadFrom(name: String, read: () -> ByteArray) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MidiParser.parse(read(), name) }
            }
            result
                .onSuccess { song ->
                    pause()
                    _state.update {
                        it.copy(
                            song = song,
                            positionMs = 0.0,
                            error = null,
                            hitNoteIds = emptySet(),
                            wrongKeys = emptySet(),
                            correctCount = 0,
                            wrongCount = 0,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Die Datei konnte nicht geladen werden.") }
                }
        }
    }

    fun togglePlay() {
        if (_state.value.playing) pause() else play()
    }

    fun play() {
        val song = _state.value.song ?: return
        if (_state.value.positionMs >= song.durationMs) {
            _state.update { it.copy(positionMs = 0.0) }
        }
        _state.update { it.copy(playing = true) }
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(FRAME_MS)
                val now = System.nanoTime()
                val deltaMs = (now - last) / 1_000_000.0
                last = now
                advance(deltaMs)
            }
        }
    }

    fun pause() {
        clockJob?.cancel()
        clockJob = null
        _state.update { it.copy(playing = false) }
    }

    fun restart() {
        pause()
        _state.update {
            it.copy(positionMs = 0.0, hitNoteIds = emptySet(), correctCount = 0, wrongCount = 0)
        }
    }

    /** Tempo in Prozent (50 bis 150); wird dauerhaft in den Einstellungen gespeichert. */
    fun setTempo(percent: Int) {
        val value = percent.coerceIn(50, 150)
        _state.update { it.copy(tempoPercent = value) }
        viewModelScope.launch {
            settingsRepository.save(settingsRepository.settings.first().copy(tempoPercent = value))
        }
    }

    private fun advance(deltaMs: Double) {
        val current = _state.value
        val song = current.song ?: return
        val next = current.positionMs + deltaMs * current.tempoPercent / 100.0
        if (next >= song.durationMs) {
            pause()
            _state.update { it.copy(positionMs = song.durationMs.toDouble()) }
        } else {
            _state.update { it.copy(positionMs = next) }
        }
    }

    override fun onCleared() {
        activeSource?.stop()
        super.onCleared()
    }
}
