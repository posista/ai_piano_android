package app.aipiano.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aipiano.audio.NoteSynth
import app.aipiano.audio.hasRecordAudioPermission
import app.aipiano.input.MicrophoneInputSource
import app.aipiano.input.MidiUsbInputSource
import app.aipiano.input.NoteEvent
import app.aipiano.input.NoteInputSource
import app.aipiano.input.VirtualKeyboardInputSource
import app.aipiano.midi.MidiParser
import app.aipiano.midi.Song
import app.aipiano.midi.SongNote
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
    val waitMode: Boolean = true,
    val micSensitivity: Float = 0.6f,
    val soundOn: Boolean = true,
    val chordsTogether: Boolean = true,
    val otherHandSound: Boolean = true,
    val inputSource: InputSourceType = InputSourceType.USB_MIDI,
    val inputStatus: String = "",
    /** Ids der Noten, die richtig gespielt wurden (werden grün). */
    val hitNoteIds: Set<Int> = emptySet(),
    /** Noten, auf die im Wartemodus gerade gewartet wird. Solange nicht leer, steht das Stück. */
    val waitingForIds: Set<Int> = emptySet(),
    /** Tasten, die gerade gedrückt sind. */
    val pressedKeys: Set<Int> = emptySet(),
    /** Tasten, die kurz rot markiert sind, weil sie falsch waren. */
    val wrongKeys: Set<Int> = emptySet(),
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val error: String? = null,
)

/**
 * Hält das geladene Stück, die Wiedergabeposition, den Wartemodus, die Tonausgabe und die
 * Auswertung der Eingabe. Die UI beobachtet nur [state] (MVVM).
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val usbSource = MidiUsbInputSource(application)
    private val virtualSource = VirtualKeyboardInputSource()
    private val micSource = MicrophoneInputSource { _state.value.micSensitivity }
    private val synth = NoteSynth()

    private var activeSource: NoteInputSource? = null
    private var activeType: InputSourceType? = null
    private val sourceJobs = mutableListOf<Job>()
    private var clockJob: Job? = null

    /** Zeitpunkte (ms), an denen geübte Noten beginnen. Der Wartemodus hält dort an. */
    private var checkpoints = LongArray(0)
    /** Letzter Haltepunkt, der bereits behandelt wurde (-1 = noch keiner). */
    private var consumedMs = -1L
    /** Index der nächsten Note des Stücks, die klingen kann (Noten sind nach Startzeit sortiert). */
    private var nextSoundIndex = 0
    /** Noten des Stücks, die gerade vom Synthesizer klingen. */
    private val songSounding = mutableListOf<SongNote>()

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
                waitMode = settings.waitMode,
                micSensitivity = settings.micSensitivity,
                soundOn = settings.soundOn,
                chordsTogether = settings.chordsTogether,
                otherHandSound = settings.otherHandSound,
                inputSource = settings.inputSource,
            )
        }
        refreshCheckpoints(_state.value.song, settings.practiceHands)

        // Wartet das Stück auf Noten, die nach einem Handwechsel nicht mehr geübt werden, gibt es sie frei.
        val song = _state.value.song
        val waiting = _state.value.waitingForIds
        if (song != null && waiting.isNotEmpty()) {
            val stillPracticed = song.notes
                .filter { it.id in waiting && settings.practiceHands.includes(it.hand) }
                .map { it.id }
                .toSet()
            if (stillPracticed.size != waiting.size) {
                _state.update { it.copy(waitingForIds = stillPracticed) }
            }
        }

        if (settings.inputSource != activeType) switchSource(settings.inputSource)
        updateSynth(settings.soundOn && settings.inputSource != InputSourceType.MICROPHONE)
    }

    /**
     * Schaltet den Synthesizer ein oder aus. Mit dem Mikrofon bleibt er aus, weil das Mikrofon
     * sonst die eigene Ausgabe der App hören und falsch auswerten würde.
     */
    private fun updateSynth(on: Boolean) {
        if (on) {
            synth.start()
        } else {
            stopSongSound()
            synth.stop()
        }
    }

    private fun refreshCheckpoints(song: Song?, hands: PracticeHands) {
        checkpoints = song?.notes
            ?.filter { hands.includes(it.hand) }
            ?.map { it.startMs }
            ?.distinct()
            ?.sorted()
            ?.toLongArray()
            ?: LongArray(0)
    }

    /** Erster Haltepunkt, der nach [afterMs] liegt. */
    private fun nextCheckpointAfter(afterMs: Long): Long? {
        var low = 0
        var high = checkpoints.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (checkpoints[mid] <= afterMs) low = mid + 1 else high = mid
        }
        return checkpoints.getOrNull(low)
    }

    private fun switchSource(type: InputSourceType) {
        activeSource?.stop()
        sourceJobs.forEach { it.cancel() }
        sourceJobs.clear()
        activeType = type

        val source = when (type) {
            InputSourceType.USB_MIDI -> usbSource
            InputSourceType.VIRTUAL -> virtualSource
            // Ohne Berechtigung wird nie aufgenommen
            InputSourceType.MICROPHONE ->
                if (getApplication<Application>().hasRecordAudioPermission()) micSource else null
        }
        activeSource = source
        if (source == null) {
            val message = if (type == InputSourceType.MICROPHONE) {
                "Mikrofon: keine Berechtigung. In den Einstellungen erlauben."
            } else {
                "Keine Eingabequelle aktiv"
            }
            _state.update { it.copy(inputStatus = message) }
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
        // Gespielte Taste sofort hörbar machen, unabhängig von der Auswertung
        val soundKey = "user-${event.midi}"
        if (event.isOn) synth.noteOn(soundKey, event.midi, event.velocity) else synth.noteOff(soundKey)

        val current = _state.value
        if (!event.isOn) {
            _state.update { it.copy(pressedKeys = it.pressedKeys - event.midi) }
            return
        }
        val heldAfter = current.pressedKeys + event.midi
        val song = current.song
        if (song == null) {
            _state.update { it.copy(pressedKeys = heldAfter) }
            return
        }

        // Akkordtöne gleichzeitig: nur mit USB-MIDI oder Bildschirmtasten (Mikrofon hört nur einen Ton)
        val strictChords = current.chordsTogether && current.inputSource != InputSourceType.MICROPHONE
        if (strictChords && current.waitingForIds.isNotEmpty()) {
            handleChordKey(song, current, event.midi, heldAfter)
            return
        }

        val match = findNearestNote(song, current.positionMs, event.midi, current.practiceHands, current.hitNoteIds)
        if (match != null) {
            _state.update {
                it.copy(
                    hitNoteIds = it.hitNoteIds + match,
                    waitingForIds = it.waitingForIds - match,
                    pressedKeys = heldAfter,
                    correctCount = it.correctCount + 1,
                )
            }
        } else {
            flashWrong(event.midi, heldAfter)
        }
    }

    /**
     * Wartet auf einen Akkord: Erst wenn alle geforderten Tasten gleichzeitig gehalten werden,
     * gilt der Anschlag als richtig. Einzelne Töne werden nicht gezählt.
     */
    private fun handleChordKey(song: Song, current: PlayerUiState, midi: Int, heldAfter: Set<Int>) {
        val pending = song.notes.filter { it.id in current.waitingForIds }
        val pendingKeys = pending.map { it.midi }.toSet()
        if (midi !in pendingKeys) {
            flashWrong(midi, heldAfter)
            return
        }
        if (heldAfter.containsAll(pendingKeys)) {
            _state.update {
                it.copy(
                    hitNoteIds = it.hitNoteIds + current.waitingForIds,
                    waitingForIds = emptySet(),
                    pressedKeys = heldAfter,
                    correctCount = it.correctCount + pending.size,
                )
            }
        } else {
            // Richtiger Ton, aber noch nicht alle gleichzeitig: nur als gedrückt anzeigen
            _state.update { it.copy(pressedKeys = heldAfter) }
        }
    }

    private fun flashWrong(midi: Int, pressedAfter: Set<Int>) {
        _state.update {
            it.copy(
                wrongKeys = it.wrongKeys + midi,
                pressedKeys = pressedAfter,
                wrongCount = it.wrongCount + 1,
            )
        }
        viewModelScope.launch {
            delay(WRONG_FLASH_MS)
            _state.update { it.copy(wrongKeys = it.wrongKeys - midi) }
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
                    refreshCheckpoints(song, _state.value.practiceHands)
                    consumedMs = -1L
                    resetSoundTo(song, 0.0)
                    _state.update {
                        it.copy(
                            song = song,
                            positionMs = 0.0,
                            error = null,
                            hitNoteIds = emptySet(),
                            waitingForIds = emptySet(),
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
            consumedMs = -1L
            resetSoundTo(song, 0.0)
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
        stopSongSound()
        _state.update { it.copy(playing = false) }
    }

    fun restart() {
        pause()
        val song = _state.value.song
        consumedMs = -1L
        resetSoundTo(song, 0.0)
        _state.update {
            it.copy(
                positionMs = 0.0,
                hitNoteIds = emptySet(),
                waitingForIds = emptySet(),
                correctCount = 0,
                wrongCount = 0,
            )
        }
    }

    /**
     * Tempo in Prozent (50 bis 150). Während des Ziehens nur im Zustand, gespeichert wird
     * erst beim Loslassen über [saveTempo]. So schreibt der Regler nicht bei jeder Bewegung.
     */
    fun setTempo(percent: Int) {
        _state.update { it.copy(tempoPercent = percent.coerceIn(50, 150)) }
    }

    fun saveTempo() {
        val value = _state.value.tempoPercent
        viewModelScope.launch {
            settingsRepository.save(settingsRepository.settings.first().copy(tempoPercent = value))
        }
    }

    /** Wechselt zwischen Wartemodus und Abspielmodus und speichert die Wahl. */
    fun toggleWaitMode() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.save(current.copy(waitMode = !current.waitMode))
        }
    }

    /** Übungsmodus wählen: beide Hände, nur links oder nur rechts. Wird gespeichert. */
    fun setPracticeHands(hands: PracticeHands) {
        viewModelScope.launch {
            settingsRepository.save(settingsRepository.settings.first().copy(practiceHands = hands))
        }
    }

    /** Schaltet die Hörbarkeit der nicht geübten Hand an oder aus und speichert die Wahl. */
    fun toggleOtherHandSound() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.save(current.copy(otherHandSound = !current.otherHandSound))
        }
    }

    /** Schaltet die Tonausgabe an oder aus und speichert die Wahl. */
    fun toggleSound() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.save(current.copy(soundOn = !current.soundOn))
        }
    }

    /**
     * App verlässt den Vordergrund: Wiedergabe anhalten, Ton aus, Mikrofon freigeben,
     * damit keine Aufnahme im Hintergrund weiterläuft.
     */
    fun onAppStopped() {
        pause()
        if (activeType == InputSourceType.MICROPHONE) micSource.stop()
        updateSynth(false)
    }

    /** App kommt zurück in den Vordergrund: Ton und Mikrofon wieder einschalten, falls gewählt. */
    fun onAppStarted() {
        val current = _state.value
        updateSynth(current.soundOn && current.inputSource != InputSourceType.MICROPHONE)
        if (activeType == InputSourceType.MICROPHONE && getApplication<Application>().hasRecordAudioPermission()) {
            micSource.start()
        }
    }

    private fun advance(deltaMs: Double) {
        val current = _state.value
        val song = current.song ?: return
        // Im Wartemodus steht das Stück, bis alle Töne des aktuellen Anschlags gespielt sind
        if (current.waitingForIds.isNotEmpty()) return

        val next = current.positionMs + deltaMs * current.tempoPercent / 100.0

        if (current.waitMode) {
            var checkpoint = nextCheckpointAfter(consumedMs)
            // Haltepunkte, die schon vorbei sind (z. B. nach Wechsel in den Wartemodus), nicht nachholen
            while (checkpoint != null && checkpoint < current.positionMs - 1.0) {
                consumedMs = checkpoint
                checkpoint = nextCheckpointAfter(consumedMs)
            }
            if (checkpoint != null && next >= checkpoint) {
                consumedMs = checkpoint
                val notes = song.notes.filter {
                    it.startMs == checkpoint && current.practiceHands.includes(it.hand) && it.id !in current.hitNoteIds
                }
                val pendingIds = notes.map { it.id }.toSet()
                // Strikter Akkord: Sind alle Töne schon gehalten, bevor das Stück hält, zählt das sofort
                val strict = current.chordsTogether && current.inputSource != InputSourceType.MICROPHONE
                val alreadyHeld = strict && notes.isNotEmpty() && notes.all { it.midi in current.pressedKeys }
                _state.update {
                    it.copy(
                        positionMs = checkpoint.toDouble(),
                        waitingForIds = if (alreadyHeld) emptySet() else pendingIds,
                        hitNoteIds = if (alreadyHeld) it.hitNoteIds + pendingIds else it.hitNoteIds,
                        correctCount = if (alreadyHeld) it.correctCount + pendingIds.size else it.correctCount,
                    )
                }
                updateSongSound(song, checkpoint.toDouble(), current)
                return
            }
        }

        if (next >= song.durationMs) {
            pause()
            _state.update { it.copy(positionMs = song.durationMs.toDouble()) }
        } else {
            _state.update { it.copy(positionMs = next) }
            updateSongSound(song, next, current)
        }
    }

    /**
     * Lässt die Noten des Stücks klingen, die das Programm selbst spielt: im Abspielmodus alle,
     * im Wartemodus nur die nicht geübte Hand.
     */
    private fun updateSongSound(song: Song, positionMs: Double, current: PlayerUiState) {
        while (nextSoundIndex < song.notes.size && song.notes[nextSoundIndex].startMs <= positionMs) {
            val note = song.notes[nextSoundIndex]
            nextSoundIndex++
            // Geübte Noten klingen nur im Abspielmodus, die andere Hand nur wenn "andere Hand hörbar" an ist
            val audible = if (current.practiceHands.includes(note.hand)) {
                !current.waitMode
            } else {
                current.otherHandSound
            }
            if (audible) {
                synth.noteOn("song-${note.id}", note.midi, note.velocity)
                songSounding += note
            }
        }
        val iterator = songSounding.iterator()
        while (iterator.hasNext()) {
            val note = iterator.next()
            if (note.endMs <= positionMs) {
                synth.noteOff("song-${note.id}")
                iterator.remove()
            }
        }
    }

    private fun stopSongSound() {
        songSounding.forEach { synth.noteOff("song-${it.id}") }
        songSounding.clear()
    }

    private fun resetSoundTo(song: Song?, positionMs: Double) {
        stopSongSound()
        nextSoundIndex = song?.notes?.indexOfFirst { it.startMs > positionMs }
            ?.takeIf { it >= 0 }
            ?: (song?.notes?.size ?: 0)
    }

    override fun onCleared() {
        activeSource?.stop()
        synth.stop()
        super.onCleared()
    }
}
