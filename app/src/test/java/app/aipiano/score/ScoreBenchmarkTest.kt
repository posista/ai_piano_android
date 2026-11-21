package app.aipiano.score

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aipiano.midi.StaffMath
import java.io.File
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Prüfstand für die Notenerkennung: Zufällige Melodien auf zwei Systemen werden mit echten
 * Notenköpfen (Bravura) gezeichnet. Die richtige Lösung ist bekannt, daraus folgen Trefferquoten.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScoreBenchmarkTest {

    data class Truth(val midi: Int, val startBeat: Double, val beats: Double, val staff: Int)

    private val staffSpace = 20.0
    private val pxPerBeat = 60.0
    private val leftMargin = 200.0
    private val beatsPerStaff = 16

    private val typeface: Typeface by lazy {
        Typeface.createFromFile(File("src/test/resources/bravura_full.otf"))
    }

    /** Zufällige Melodie für beide Systeme, mit gelegentlichen Akkorden in einer Stimme. */
    private fun randomScore(seed: Long): List<Truth> {
        val rnd = java.util.Random(seed)
        val truth = ArrayList<Truth>()
        for (staff in 0..1) {
            val range = if (staff == 0) 62..79 else 40..57
            var start = 0.0
            while (start < beatsPerStaff) {
                val remaining = beatsPerStaff - start
                val options = listOf(1.0, 1.0, 1.0, 2.0, 4.0).filter { it <= remaining }
                val beats = options[rnd.nextInt(options.size)]
                val midi = range.first + rnd.nextInt(range.last - range.first + 1)
                truth += Truth(midi, start, beats, staff)
                if (rnd.nextDouble() < 0.2) {
                    val partner = (midi + if (rnd.nextBoolean()) 4 else -3).coerceIn(range.first, range.last)
                    if (partner != midi) truth += Truth(partner, start, beats, staff)
                }
                start += beats
            }
        }
        return truth
    }

    /** Zeichnet die Noten als Schwarz-Weiß-Bild mit zwei Systemen (Violin- oben, Bass unten). */
    private fun render(truth: List<Truth>): BinaryImage {
        val width = 2400
        val height = 760
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        val trebleBottom = 200.0 + 4 * staffSpace
        val bassBottom = trebleBottom + 6 * staffSpace + 4 * staffSpace
        val lineThickness = 2f
        for (bottom in listOf(trebleBottom, bassBottom)) {
            for (i in 0..4) {
                val y = (bottom - i * staffSpace).toFloat()
                canvas.drawRect(60f, y - lineThickness / 2, width - 60f, y + lineThickness / 2, paint)
            }
        }

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = this@ScoreBenchmarkTest.typeface
            textSize = (4 * staffSpace).toFloat()
            textAlign = Paint.Align.CENTER
        }
        val stroke = 0.12 * staffSpace
        val sharpPaint = Paint(glyphPaint)
        val sharpBounds = Rect()
        val bounds = Rect()
        for (note in truth) {
            val treble = note.staff == 0
            val bottom = if (treble) trebleBottom else bassBottom
            val refStep = if (treble) StaffMath.TREBLE_BOTTOM_STEP else StaffMath.BASS_BOTTOM_STEP
            val step = StaffMath.step(note.midi)
            val x = leftMargin + note.startBeat * pxPerBeat
            val y = bottom - (step - refStep) * staffSpace / 2

            val glyph = when {
                note.beats >= 4 -> "\uE0A2"
                note.beats >= 2 -> "\uE0A3"
                else -> "\uE0A4"
            }
            glyphPaint.getTextBounds(glyph, 0, glyph.length, bounds)
            if (StaffMath.isBlack(note.midi)) {
                // Kreuz links vom Kopf, mittig zur Notenlinie
                val halfHead = bounds.width() / 2.0
                val sharpWidth = glyphPaint.measureText("\uE262")
                sharpPaint.getTextBounds("\uE262", 0, 1, sharpBounds)
                val sharpX = x - halfHead - 0.3 * staffSpace - sharpWidth / 2
                val sharpBaseline = (y - (sharpBounds.top + sharpBounds.bottom) / 2.0).toFloat()
                canvas.drawText("\uE262", sharpX.toFloat(), sharpBaseline, sharpPaint)
            }
            // Glyph so setzen, dass seine Mitte auf (x, y) liegt
            val baseline = (y - (bounds.top + bounds.bottom) / 2.0).toFloat()
            canvas.drawText(glyph, x.toFloat(), baseline, glyphPaint)

            if (note.beats < 4) {
                val stemUp = step < refStep + 4
                val halfWidth = (bounds.width() / 2.0)
                val stemX = if (stemUp) x + halfWidth - stroke / 2 else x - halfWidth + stroke / 2
                val length = 3.5 * staffSpace
                val top = if (stemUp) y - length else y
                canvas.drawRect((stemX - stroke / 2).toFloat(), top.toFloat(), (stemX + stroke / 2).toFloat(),
                    (top + length).toFloat(), paint)
            }
        }

        File("build/review").mkdirs()
        File("build/review/benchmark_last.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val dark = BooleanArray(width * height) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 0.3 + gg * 0.59 + b * 0.11) < 128
        }
        bitmap.recycle()
        return BinaryImage(width, height, dark)
    }

    /** Vergleicht die erkannten Noten mit der Lösung und zählt Treffer. */
    private fun score(truth: List<Truth>, predicted: List<app.aipiano.transcribe.DetectedNote>): Triple<Int, Int, Int> {
        val remaining = truth.toMutableList()
        var hits = 0
        for (p in predicted) {
            val startBeat = p.startSec / 0.5
            val beats = (p.endSec - p.startSec) / 0.5
            val match = remaining.firstOrNull {
                it.midi == p.midi && abs(it.startBeat - startBeat) <= 0.3 && abs(it.beats - beats) <= 0.6
            }
            if (match != null) {
                hits++
                remaining.remove(match)
            }
        }
        return Triple(hits, predicted.size, truth.size)
    }

    @Test(timeout = 600_000)
    fun benchmarkOnRenderedScores() {
        var hits = 0
        var predicted = 0
        var expected = 0
        for (seed in 1L..12L) {
            val truth = randomScore(seed)
            val image = render(truth)
            val log = ArrayList<String>()
            val notes = if (seed == 1L) {
                ScoreReader.debug = { log += it }
                try { ScoreReader.read(listOf(image)) } finally { ScoreReader.debug = null }
            } else ScoreReader.read(listOf(image))
            val (h, p, e) = score(truth, notes)
            hits += h
            predicted += p
            expected += e
            println("PRUEFSTAND seed=$seed: richtig $h, erkannt $p, erwartet $e")
            if (seed == 1L) {
                println("LOESUNG: " + truth.sortedWith(compareBy({ it.staff }, { it.startBeat })).joinToString { "${it.staff}:${it.midi}@${it.startBeat}/${it.beats}" })
                println("ERKANNT: " + notes.joinToString { "${it.midi}@${it.startSec / 0.5}/${(it.endSec - it.startSec) / 0.5}" })
                println("KOEPFE: " + log.joinToString(" | "))
            }
        }
        val precision = hits.toDouble() / predicted.coerceAtLeast(1)
        val recall = hits.toDouble() / expected
        println("PRUEFSTAND GESAMT: Trefferquote (Recall) ${"%.1f".format(recall * 100)} %, " +
            "Genauigkeit (Precision) ${"%.1f".format(precision * 100)} %")
        // Untergrenzen unter dem aktuellen Stand, damit eine Verschlechterung auffällt
        org.junit.Assert.assertTrue("Trefferquote zu niedrig: ${recall}", recall >= 0.65)
        org.junit.Assert.assertTrue("Genauigkeit zu niedrig: ${precision}", precision >= 0.75)
    }
}
