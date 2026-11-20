package app.aipiano.library

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Holt nur den Titel eines Videos über die offizielle oEmbed-Schnittstelle von YouTube.
 * Es wird weder Video noch Tonspur geladen.
 */
object YouTubeTitle {

    @Throws(IOException::class)
    fun fetch(canonicalUrl: String): String {
        val endpoint = "https://www.youtube.com/oembed?format=json&url=" +
            URLEncoder.encode(canonicalUrl, "UTF-8")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Titel nicht abrufbar (HTTP ${connection.responseCode})")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).getString("title")
        } finally {
            connection.disconnect()
        }
    }
}
