package app.aipiano.score

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Probelauf an einer echten, gemeinfreien Partitur (Beethoven, "Ode an die Freude", Wikimedia Commons,
 * Lizenz: Public domain). Es gibt keine Lösung zum Vergleich, die Ausgabe wird nur zur Sichtprüfung protokolliert.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RealScoreProbeTest {

    @Test
    fun readPublicDomainScore() {
        val bitmap: Bitmap = BitmapFactory.decodeFile(File("src/test/resources/ode_joy_public_domain.png").path)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val dark = BooleanArray(pixels.size) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 0.3 + g * 0.59 + b * 0.11) < 140
        }
        val log = ArrayList<String>()
        ScoreReader.debug = { log += it }
        val notes = try { ScoreReader.read(listOf(BinaryImage(bitmap.width, bitmap.height, dark))) } finally { ScoreReader.debug = null }
        println("PROTOKOLL_KREUZE: " + log.filter { it.startsWith("KREUZ") || it.startsWith("VORZEICHNUNG") }.joinToString(" | "))
        println("ECHTE_PARTITUR: ${notes.size} Noten, " + notes.joinToString(" ") {
            "${it.midi}@${"%.1f".format(it.startSec / 0.5)}"
        })
    }
}
