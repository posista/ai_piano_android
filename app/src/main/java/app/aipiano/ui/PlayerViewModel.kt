package app.aipiano.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aipiano.midi.MidiParser
import app.aipiano.midi.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val SAMPLE_ASSET = "samples/ode_an_die_freude.mid"
private const val FRAME_MS = 16L

data class PlayerUiState(
    val song: Song? = null,
    /** Aktuelle Position im Stück in Millisekunden. */
    val positionMs: Double = 0.0,
    val playing: Boolean = false,
    val tempoPercent: Int = 100,
    val error: String? = null,
)

/** Hält das geladene Stück und die Wiedergabeposition (MVVM: die UI beobachtet [state]). */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var clockJob: Job? = null

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
                    _state.update { it.copy(song = song, positionMs = 0.0, error = null) }
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
        _state.update { it.copy(positionMs = 0.0) }
    }

    fun setTempo(percent: Int) {
        _state.update { it.copy(tempoPercent = percent.coerceIn(50, 150)) }
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
}
