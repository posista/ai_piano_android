package app.aipiano.library

import java.net.URI

/** Reine Hilfsfunktionen für Dateinamen und YouTube-Links (ohne Android, daher testbar). */
object LibraryNames {

    private val midiExtension = Regex("\\.(mid|midi)$", RegexOption.IGNORE_CASE)
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    /**
     * Macht aus einem rohen Dateinamen einen Anzeigetitel:
     * Pfad und Speicher-Präfix entfernen, Endung .mid/.midi entfernen, Unterstriche durch Leerzeichen ersetzen.
     */
    fun cleanFileName(raw: String?): String {
        if (raw.isNullOrBlank()) return DEFAULT_TITLE
        val base = raw.substringAfterLast('/').substringAfterLast(':')
        val name = base.replace('_', ' ').replace(midiExtension, "").trim()
        return name.ifBlank { DEFAULT_TITLE }
    }

    /** Liefert die Video-ID eines YouTube-Links oder null, wenn es kein YouTube-Video ist. */
    fun youtubeVideoId(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty()
        val id = when (host) {
            "youtu.be" -> path.trim('/').substringBefore('/')
            "youtube.com", "music.youtube.com" -> when {
                path == "/watch" -> queryParam(uri.rawQuery, "v")
                path.startsWith("/shorts/") || path.startsWith("/embed/") ->
                    path.trim('/').split('/').getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { videoId.matches(it) }
    }

    /** Einheitliche Schreibweise eines YouTube-Links für die Speicherung. */
    fun canonicalYouTubeUrl(id: String): String = "https://www.youtube.com/watch?v=$id"

    private fun queryParam(query: String?, key: String): String? =
        query?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == key }
            ?.getOrNull(1)

    const val DEFAULT_TITLE = "MIDI-Datei"
}
