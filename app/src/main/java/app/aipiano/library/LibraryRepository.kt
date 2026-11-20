package app.aipiano.library

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Was in der Bibliothek liegt: eine MIDI-Datei oder nur ein YouTube-Link (mit optional zugeordneter MIDI). */
data class LibraryEntry(
    val id: String,
    val title: String,
    val source: Source,
    val youtubeUrl: String?,
    val hasMidi: Boolean,
    val addedAt: Long,
)

enum class Source { FILE, YOUTUBE, CONVERTED }

/**
 * Speichert Stücke dauerhaft im App-Speicher: eine Indexdatei (JSON) und je Stück eine .mid-Datei.
 * Es wird nur Material gespeichert, das der Nutzer selbst hinzufügt.
 */
class LibraryRepository(context: Context) {

    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val index = File(dir, "index.json")

    @Synchronized
    fun list(): List<LibraryEntry> = readAll().sortedByDescending { it.addedAt }

    @Synchronized
    fun addFile(title: String, midi: ByteArray): LibraryEntry =
        add(LibraryEntry(newId(), title, Source.FILE, null, hasMidi = true, addedAt = now()), midi)

    /** Ergebnis einer Umwandlung aus einer Audio- oder Videodatei. */
    @Synchronized
    fun addConverted(title: String, midi: ByteArray): LibraryEntry =
        add(LibraryEntry(newId(), title, Source.CONVERTED, null, hasMidi = true, addedAt = now()), midi)

    @Synchronized
    fun addYouTube(title: String, url: String): LibraryEntry =
        add(LibraryEntry(newId(), title, Source.YOUTUBE, url, hasMidi = false, addedAt = now()), null)

    /** Ordnet einem Eintrag (z. B. einem YouTube-Link) eine MIDI-Datei zu. */
    @Synchronized
    fun attachMidi(id: String, midi: ByteArray): Boolean {
        val all = readAll()
        val position = all.indexOfFirst { it.id == id }
        if (position < 0) return false
        File(dir, "$id.mid").writeBytes(midi)
        all[position] = all[position].copy(hasMidi = true)
        writeAll(all)
        return true
    }

    @Synchronized
    fun readMidi(id: String): ByteArray? = File(dir, "$id.mid").takeIf { it.exists() }?.readBytes()

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.mid").delete()
        writeAll(readAll().filterNot { it.id == id })
    }

    private fun add(entry: LibraryEntry, midi: ByteArray?): LibraryEntry {
        midi?.let { File(dir, "${entry.id}.mid").writeBytes(it) }
        writeAll(readAll() + entry)
        return entry
    }

    private fun readAll(): MutableList<LibraryEntry> {
        if (!index.exists()) return mutableListOf()
        val array = JSONArray(index.readText())
        return MutableList(array.length()) { i -> array.getJSONObject(i).toEntry() }
    }

    private fun writeAll(entries: List<LibraryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        index.writeText(array.toString())
    }

    private fun LibraryEntry.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("source", source.name)
        .put("url", youtubeUrl ?: "")
        .put("hasMidi", hasMidi)
        .put("addedAt", addedAt)

    private fun JSONObject.toEntry(): LibraryEntry = LibraryEntry(
        id = getString("id"),
        title = getString("title"),
        source = runCatching { Source.valueOf(getString("source")) }.getOrDefault(Source.FILE),
        youtubeUrl = getString("url").ifBlank { null },
        hasMidi = getBoolean("hasMidi"),
        addedAt = getLong("addedAt"),
    )

    private fun newId(): String = UUID.randomUUID().toString()

    private fun now(): Long = System.currentTimeMillis()
}
