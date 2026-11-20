package app.aipiano.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aipiano.library.LibraryEntry
import app.aipiano.library.LibraryNames
import app.aipiano.library.LibraryRepository
import app.aipiano.library.YouTubeTitle
import app.aipiano.transcribe.AudioDecoder
import app.aipiano.transcribe.MidiWriter
import app.aipiano.transcribe.SpectralTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Verwaltung der Bibliothek: Dateien hinzufügen, YouTube-Links anlegen, MIDI zuordnen, löschen. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LibraryRepository(application)

    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            _entries.value = withContext(Dispatchers.IO) { repository.list() }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val name = LibraryNames.cleanFileName(displayName(uri))
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: error("Datei nicht lesbar")
                    repository.addFile(name, bytes)
                }.isSuccess
            }
            _message.value = if (ok) "„$name“ zur Bibliothek hinzugefügt." else "Die Datei konnte nicht gelesen werden."
            refresh()
        }
    }

    /**
     * Wandelt eine Audio- oder Videodatei in eine MIDI-Datei um und legt sie in der Bibliothek ab.
     * Die Umwandlung läuft auf dem Hintergrund-Thread und kann bei langen Stücken dauern.
     */
    fun convertAudio(uri: Uri) {
        viewModelScope.launch {
            _message.value = "Umwandlung läuft … Bei langen Stücken dauert das eine Weile."
            val title = LibraryNames.stripAnyExtension(LibraryNames.cleanFileName(displayName(uri)))
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val decoded = AudioDecoder.decode(getApplication(), uri)
                    val notes = SpectralTranscriber().transcribe(decoded.samples, decoded.sampleRate)
                    check(notes.isNotEmpty()) { "Es wurden keine Töne erkannt." }
                    MidiWriter.write(notes, title) to notes.size
                }
            }
            result
                .onSuccess { (midi, count) ->
                    withContext(Dispatchers.IO) { repository.addConverted(title, midi) }
                    _message.value = "„$title“ umgewandelt: $count Noten. Das Ergebnis kann Fehler " +
                        "enthalten, besonders bei dichter Musik."
                }
                .onFailure {
                    _message.value = "Umwandlung fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}"
                }
            refresh()
        }
    }

    /** Legt einen YouTube-Link an. Der Titel kommt von YouTube (oEmbed). Die Tonspur wird nicht geladen. */
    fun addYouTubeLink(raw: String) {
        val id = LibraryNames.youtubeVideoId(raw)
        if (id == null) {
            _message.value = "Das ist kein gültiger YouTube-Link."
            return
        }
        val url = LibraryNames.canonicalYouTubeUrl(id)
        viewModelScope.launch {
            val title = withContext(Dispatchers.IO) {
                runCatching { YouTubeTitle.fetch(url) }.getOrNull()
            } ?: "YouTube-Video $id"
            withContext(Dispatchers.IO) { repository.addYouTube(title, url) }
            _message.value = "„$title“ gespeichert. Ordne jetzt eine MIDI-Datei zu, um es zu spielen."
            refresh()
        }
    }

    /** Ordnet einem Eintrag eine MIDI-Datei zu, z. B. einem YouTube-Link. */
    fun attachMidi(entryId: String, uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: error("Datei nicht lesbar")
                    repository.attachMidi(entryId, bytes)
                }.getOrDefault(false)
            }
            _message.value = if (ok) "MIDI-Datei zugeordnet." else "Die MIDI-Datei konnte nicht zugeordnet werden."
            refresh()
        }
    }

    fun delete(entryId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(entryId) }
            _message.value = "Eintrag gelöscht."
            refresh()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun displayName(uri: Uri): String? {
        val cursor = getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }
}
