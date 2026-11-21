package app.aipiano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.aipiano.R
import app.aipiano.midi.Hand
import app.aipiano.midi.Song
import app.aipiano.midi.StaffMath
import app.aipiano.settings.PracticeHands
import kotlin.math.min

private val PaperColor = Color(0xFFF6F1E4)
private val InkColor = Color(0xFF1E1E1E)
private val MutedInkColor = Color(0xFFA6A6A6)
private val PlayheadColor = Color(0xFF1E88E5)

/** Zeichen der Musikschrift Bravura (SMuFL-Standard): Violinschlüssel, Bassschlüssel, Kreuz. */
private const val GLYPH_TREBLE_CLEF = "\uE050"
private const val GLYPH_BASS_CLEF = "\uE062"
private const val GLYPH_SHARP = "\uE262"

/** Pixel je Millisekunde, abhängig von der Zeilenhöhe. Ein Schlag (500 ms) ist etwa sechs Zeilenabstände breit. */
private const val LOOKBACK_MS = 300.0
private const val BEAT_MS = 500.0

/**
 * Notensystem mit zwei Systemen (Violinschlüssel oben für die rechte Hand, Bassschlüssel unten für
 * die linke). Die Noten laufen von rechts nach links an einer Abspiellinie vorbei, grün = richtig,
 * orange umrandet = wird gerade erwartet, blass = nicht geübte Hand.
 *
 * Hinweis: Die Schlüssel sind nur Buchstaben "G" und "F", keine gedruckten Schlüsselzeichen.
 */
@Composable
fun StaffView(
    song: Song?,
    positionMs: Double,
    hitNoteIds: Set<Int>,
    waitingForIds: Set<Int>,
    practiceHands: PracticeHands,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val music = FontFamily(Font(R.font.bravura))
    Canvas(modifier = modifier.background(PaperColor)) {
        drawStaff(song, positionMs, hitNoteIds, waitingForIds, practiceHands, textMeasurer, music)
    }
}

private fun DrawScope.drawStaff(
    song: Song?,
    positionMs: Double,
    hitNoteIds: Set<Int>,
    waitingForIds: Set<Int>,
    practiceHands: PracticeHands,
    textMeasurer: TextMeasurer,
    music: FontFamily,
) {
    // Zeilenabstand: so, dass beide Systeme sicher in die Höhe passen
    val gap = min(size.height / 17f, 14f * density)
    val trebleBottom = size.height * 0.42f
    val bassBottom = size.height * 0.90f
    val playheadX = size.width * 0.22f
    val pxPerMs = gap * 0.012f
    val lineWidth = 1.2f * density

    // Notenlinien beider Systeme
    for (bottom in listOf(trebleBottom, bassBottom)) {
        for (i in 0 until 5) {
            val y = bottom - i * gap
            drawLine(InkColor, Offset(0f, y), Offset(size.width, y), strokeWidth = lineWidth)
        }
    }

    // Schlüssel: In SMuFL ist eine Notenlinie ein Viertel der Schriftgröße (1 em = 4 Linien).
    // Der Violinschlüssel sitzt auf der zweiten Linie (G), der Bassschlüssel auf der vierten (F).
    val glyphStyle = TextStyle(color = InkColor, fontFamily = music, fontSize = (gap * 4f / density).sp)
    drawGlyph(textMeasurer, GLYPH_TREBLE_CLEF, glyphStyle, 6f * density, trebleBottom - gap)
    drawGlyph(textMeasurer, GLYPH_BASS_CLEF, glyphStyle, 6f * density, bassBottom - 3 * gap)

    if (song != null) {
        val visibleEnd = positionMs + (size.width - playheadX) / pxPerMs
        val visibleStart = positionMs - LOOKBACK_MS
        val headWidth = gap * 1.25f
        val headHeight = gap * 0.9f

        for (note in song.notes) {
            if (note.startMs > visibleEnd) break // Noten sind nach Startzeit sortiert
            if (note.endMs < visibleStart) continue

            val treble = note.hand == Hand.RIGHT
            val bottom = if (treble) trebleBottom else bassBottom
            val refStep = if (treble) StaffMath.TREBLE_BOTTOM_STEP else StaffMath.BASS_BOTTOM_STEP
            val step = StaffMath.step(note.midi)
            val x = playheadX + ((note.startMs - positionMs) * pxPerMs).toFloat()
            val y = bottom - (step - refStep) * gap / 2f

            drawLedgerLines(x, headWidth, bottom, gap, refStep, step, lineWidth)

            val color = when {
                note.id in hitNoteIds -> HitColor
                !practiceHands.includes(note.hand) -> MutedInkColor
                else -> InkColor
            }
            val beats = (note.endMs - note.startMs) / BEAT_MS
            val topLeft = Offset(x - headWidth / 2, y - headHeight / 2)
            if (beats >= 2.0) {
                drawOval(color, topLeft, Size(headWidth, headHeight), style = Stroke(width = gap * 0.18f))
            } else {
                drawOval(color, topLeft, Size(headWidth, headHeight))
            }

            // Hals: nach oben, unterhalb der Mittellinie nach unten
            if (beats < 4.0) {
                val stemUp = step < refStep + 4
                val stemX = if (stemUp) x + headWidth / 2 - lineWidth else x - headWidth / 2 + lineWidth
                val stemEnd = if (stemUp) y - 3.5f * gap else y + 3.5f * gap
                drawLine(color, Offset(stemX, y), Offset(stemX, stemEnd), strokeWidth = gap * 0.12f)
            }

            // Vorzeichen: schwarze Tasten als Kreuz links vom Kopf
            if (StaffMath.isBlack(note.midi)) {
                val sharpStyle = glyphStyle.copy(color = color)
                val layout = textMeasurer.measure(GLYPH_SHARP, sharpStyle)
                // Kreuz links neben dem Kopf, vertikal mittig zur Note
                drawText(
                    layout,
                    topLeft = Offset(x - headWidth * 0.75f - layout.size.width, y - layout.size.height / 2f),
                )
            }

            if (note.id in waitingForIds) {
                drawCircle(
                    color = WrongColor,
                    radius = headWidth * 0.85f,
                    center = Offset(x, y),
                    style = Stroke(width = gap * 0.18f),
                )
            }
        }
    }

    // Abspiellinie
    drawLine(PlayheadColor, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2f * density)
}

/** Zeichnet ein Schrift-Zeichen so, dass seine Grundlinie auf [baselineY] liegt. */
private fun DrawScope.drawGlyph(
    textMeasurer: TextMeasurer,
    glyph: String,
    style: TextStyle,
    x: Float,
    baselineY: Float,
) {
    val layout = textMeasurer.measure(glyph, style)
    drawText(layout, topLeft = Offset(x, baselineY - layout.firstBaseline))
}

/** Hilfslinien für Töne oberhalb oder unterhalb des Systems. */
private fun DrawScope.drawLedgerLines(
    x: Float,
    headWidth: Float,
    bottom: Float,
    gap: Float,
    refStep: Int,
    step: Int,
    lineWidth: Float,
) {
    val half = headWidth * 0.9f
    if (step <= refStep - 2) {
        var s = refStep - 2
        while (s >= step) {
            val y = bottom - (s - refStep) * gap / 2f
            drawLine(InkColor, Offset(x - half, y), Offset(x + half, y), strokeWidth = lineWidth)
            s -= 2
        }
    }
    val topStep = refStep + 8
    if (step >= topStep + 2) {
        var s = topStep + 2
        while (s <= step) {
            val y = bottom - (s - refStep) * gap / 2f
            drawLine(InkColor, Offset(x - half, y), Offset(x + half, y), strokeWidth = lineWidth)
            s += 2
        }
    }
}
