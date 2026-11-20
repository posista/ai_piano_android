package app.aipiano.score

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testet die Notenerkennung an selbst gezeichneten Notenbildern (Linien, Köpfe, Hälse). */
class ScoreReaderTest {

    private val width = 800
    private val height = 320
    private val spacing = 10

    private class Canvas(val w: Int, val h: Int) {
        val dark = BooleanArray(w * h)
        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0..y1) for (x in x0..x1) if (x in 0 until w && y in 0 until h) dark[y * w + x] = true
        }
        fun fillEllipse(cx: Double, cy: Double, rx: Double, ry: Double) {
            for (y in (cy - ry).toInt()..(cy + ry).toInt()) for (x in (cx - rx).toInt()..(cx + rx).toInt()) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 1.0 && x in 0 until w && y in 0 until h) dark[y * w + x] = true
            }
        }
        fun image() = BinaryImage(w, h, dark)
    }

    /** Fünf Linien mit Abstand [spacing] ab der Oberkante [top]. */
    private fun drawStaff(c: Canvas, top: Int) {
        for (i in 0 until 5) {
            val y = top + i * spacing
            c.fillRect(40, y - 1, 760, y)
        }
    }

    /** Notenkopf (gefüllt) mit Hals nach oben. */
    private fun drawNote(c: Canvas, x: Int, y: Int, stem: Boolean) {
        c.fillEllipse(x.toDouble(), y.toDouble(), spacing * 0.6, spacing * 0.45)
        if (stem) c.fillRect(x + 4, y - 35, x + 5, y)
    }

    /** Ein Notensystem mit Violin- und Bassschlüssel-Linien: Oberes System oben, unteres darunter. */
    private fun grandStaffPage(): BinaryImage {
        val c = Canvas(width, height)
        val trebleTop = 60          // untere Linie bei 60 + 4*10 = 100 -> E4 (Stufe 30)
        val bassTop = 180           // untere Linie bei 220 -> G2
        drawStaff(c, trebleTop)
        drawStaff(c, bassTop)
        // Rechte Hand: E4, G4, C5 nacheinander (je 1 Schlag)
        drawNote(c, 150, trebleTop + 40, stem = true)  // E4: auf der unteren Linie
        drawNote(c, 300, trebleTop + 30, stem = true)  // G4: zweite Linie von unten
        drawNote(c, 450, trebleTop + 15, stem = true)  // C5: Zwischenraum über der zweiten Linie
        // Linke Hand: G2 und C3 gleichzeitig mit den ersten Noten
        drawNote(c, 150, bassTop + 40, stem = false)   // G2: untere Linie (hohl ohne Hals wäre 4 Schläge)
        drawNote(c, 300, bassTop + 25, stem = true)    // C3
        return c.image()
    }

    @Test
    fun readsPitchesAndOrderFromGrandStaff() {
        val notes = ScoreReader.read(listOf(grandStaffPage()))
        println("NOTEN: " + notes.joinToString { "${it.midi}@${"%.2f".format(it.startSec)}" })

        val byStart = notes.groupBy { Math.round(it.startSec * 2) }
        assertTrue("Mindestens fünf Noten erwartet, gefunden: $notes", notes.size >= 5)
        assertTrue("E4 bei 0 s fehlt: $notes", notes.any { it.midi == 64 && it.startSec < 0.1 })
        assertTrue("G4 bei 0,5 s fehlt: $notes", notes.any { it.midi == 67 && abs05(it.startSec) })
        assertTrue("C5 bei 1,0 s fehlt: $notes", notes.any { it.midi == 72 && abs(it.startSec - 1.0) < 0.1 })
        assertTrue("G2 bei 0 s fehlt: $notes", notes.any { it.midi == 43 && it.startSec < 0.1 })
        assertTrue("Spalten: $byStart", byStart.isNotEmpty())
    }

    private fun abs05(x: Double) = kotlin.math.abs(x - 0.5) < 0.1

    @Test
    fun emptyPageGivesNoNotes() {
        assertEquals(0, ScoreReader.read(listOf(BinaryImage(width, height, BooleanArray(width * height)))).size)
    }
}
