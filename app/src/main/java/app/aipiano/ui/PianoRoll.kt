package app.aipiano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import app.aipiano.midi.Hand
import app.aipiano.midi.Song
import kotlin.math.max
import kotlin.math.min

internal val LeftHandColor = Color(0xFF4FC3F7)
internal val RightHandColor = Color(0xFFFFB74D)
internal val HitColor = Color(0xFF66BB6A)
internal val WrongColor = Color(0xFFEF5350)

private val BackgroundColor = Color(0xFF101418)
private val WhiteKeyColor = Color(0xFFF5F5F5)
private val BlackKeyColor = Color(0xFF212121)
private val PressedKeyColor = Color(0xFFB3E5FC)

/** Wie weit im Voraus (in Songzeit) die Balken über der Klaviatur sichtbar sind. */
private const val LOOKAHEAD_MS = 2500.0
private const val MIN_WHITE_KEY_DP = 8f
private const val MAX_WHITE_KEY_DP = 60f

/**
 * Fallende Notenbalken über einer Klaviatur. Zoom per Pinch, Verschieben per Wischen.
 * Ein Balken berührt die Klaviatur genau dann, wenn die Note gespielt werden soll.
 *
 * @param hitNoteIds richtig gespielte Noten (grün)
 * @param pressedKeys gerade gedrückte Tasten
 * @param wrongKeys kurz rot markierte Tasten
 * @param onKey wenn gesetzt, lösen Berührungen auf der Klaviatur Tasten aus (Bildschirmtasten)
 */
@Composable
fun PianoRoll(
    song: Song?,
    positionMs: Double,
    modifier: Modifier = Modifier,
    hitNoteIds: Set<Int> = emptySet(),
    pressedKeys: Set<Int> = emptySet(),
    wrongKeys: Set<Int> = emptySet(),
    onKey: ((midi: Int, down: Boolean) -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier = modifier.background(BackgroundColor).clipToBounds()) {
        val viewWidthDp = maxWidth.value
        val viewHeightDp = maxHeight.value
        // Zoom und Scroll in dp, passend zum Tonumfang des Liedes eingestellt
        var zoom by remember(song, viewWidthDp) { mutableFloatStateOf(fitZoom(song, viewWidthDp)) }
        var scroll by remember(song, viewWidthDp) { mutableFloatStateOf(fitScroll(song, zoom)) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewWidthDp) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        val cx = centroid.x / density
                        val contentDp = scroll + cx
                        val newZoom = (zoom * zoomChange).coerceIn(MIN_WHITE_KEY_DP, MAX_WHITE_KEY_DP)
                        val contentUnits = contentDp / zoom
                        zoom = newZoom
                        val maxScroll = max(0f, KeyLayout.WHITE_KEY_COUNT * newZoom - viewWidthDp)
                        scroll = (contentUnits * newZoom - cx - pan.x / density).coerceIn(0f, maxScroll)
                    }
                }
                .then(
                    if (onKey == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(onKey, viewWidthDp, viewHeightDp) {
                            // Jeder Finger zählt einzeln: so funktionieren Akkorde auf der Bildschirmtastatur
                            val keyByPointer = HashMap<PointerId, Int>()
                            try {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        for (change in event.changes) {
                                            if (change.pressed && !change.previousPressed) {
                                                val midi = keyAt(
                                                    x = change.position.x,
                                                    y = change.position.y,
                                                    heightPx = viewHeightDp * density,
                                                    density = density,
                                                    zoomDp = zoom,
                                                    scrollDp = scroll,
                                                )
                                                if (midi != null) {
                                                    keyByPointer[change.id] = midi
                                                    onKey(midi, true)
                                                }
                                            } else if (!change.pressed && change.previousPressed) {
                                                keyByPointer.remove(change.id)?.let { onKey(it, false) }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                // Beim Verlassen alle noch gehaltenen Tasten loslassen
                                keyByPointer.values.forEach { onKey(it, false) }
                            }
                        }
                    },
                ),
        ) {
            drawRoll(
                song = song,
                positionMs = positionMs,
                zoomDp = zoom,
                scrollDp = scroll,
                density = density,
                hitNoteIds = hitNoteIds,
                pressedKeys = pressedKeys,
                wrongKeys = wrongKeys,
            )
        }
    }
}

private fun visibleRange(song: Song?): IntRange {
    val low = (song?.lowestKey ?: 60) - 3
    val high = (song?.highestKey ?: 72) + 3
    return max(KeyLayout.FIRST_KEY, low)..min(KeyLayout.LAST_KEY, high)
}

/** Zoom, bei dem der Tonumfang des Liedes in die Breite passt. */
private fun fitZoom(song: Song?, widthDp: Float): Float {
    val range = visibleRange(song)
    val units = KeyLayout.left(range.last) + KeyLayout.width(range.last) - KeyLayout.left(range.first)
    return (widthDp / units).coerceIn(MIN_WHITE_KEY_DP, MAX_WHITE_KEY_DP)
}

private fun fitScroll(song: Song?, zoom: Float): Float =
    KeyLayout.left(visibleRange(song).first) * zoom

/** Welche Taste liegt an dieser Bildschirmposition? Schwarze Tasten haben Vorrang. */
private fun keyAt(
    x: Float,
    y: Float,
    heightPx: Float,
    density: Float,
    zoomDp: Float,
    scrollDp: Float,
): Int? {
    val whiteWidth = zoomDp * density
    val keyboardHeight = min(heightPx * 0.28f, 190f * density)
    val keysTop = heightPx - keyboardHeight
    if (y < keysTop) return null
    val units = (x + scrollDp * density) / whiteWidth

    if (y < keysTop + keyboardHeight * 0.62f) {
        for (midi in KeyLayout.FIRST_KEY..KeyLayout.LAST_KEY) {
            if (!KeyLayout.isBlack(midi)) continue
            val left = KeyLayout.left(midi)
            if (units >= left && units <= left + KeyLayout.BLACK_KEY_WIDTH) return midi
        }
    }
    for (midi in KeyLayout.FIRST_KEY..KeyLayout.LAST_KEY) {
        if (KeyLayout.isBlack(midi)) continue
        val left = KeyLayout.left(midi)
        if (units >= left && units < left + 1f) return midi
    }
    return null
}

private fun handColor(hand: Hand): Color =
    if (hand == Hand.LEFT) LeftHandColor else RightHandColor

private fun DrawScope.drawRoll(
    song: Song?,
    positionMs: Double,
    zoomDp: Float,
    scrollDp: Float,
    density: Float,
    hitNoteIds: Set<Int>,
    pressedKeys: Set<Int>,
    wrongKeys: Set<Int>,
) {
    drawRect(BackgroundColor)

    val whiteWidth = zoomDp * density
    val scroll = scrollDp * density
    val keyboardHeight = min(size.height * 0.28f, 190f * density)
    val keysTop = size.height - keyboardHeight
    val pxPerMs = keysTop / LOOKAHEAD_MS.toFloat()

    fun xOf(midi: Int): Float = KeyLayout.left(midi) * whiteWidth - scroll
    fun widthOf(midi: Int): Float = KeyLayout.width(midi) * whiteWidth

    // Tasten, die gerade klingen sollen, werden in der Hand-Farbe eingefärbt
    val activeHand = HashMap<Int, Hand>()

    if (song != null) {
        val corner = CornerRadius(4f * density)
        for (note in song.notes) {
            if (note.startMs > positionMs + LOOKAHEAD_MS) break // Noten sind nach Startzeit sortiert
            if (note.endMs <= positionMs || !KeyLayout.contains(note.midi)) continue

            val x = xOf(note.midi)
            val w = widthOf(note.midi)
            if (x + w < 0f || x > size.width) continue

            val top = max(0f, keysTop - ((note.endMs - positionMs) * pxPerMs).toFloat())
            val bottom = keysTop - ((note.startMs - positionMs) * pxPerMs).toFloat()
            if (note.startMs <= positionMs) activeHand[note.midi] = note.hand

            drawRoundRect(
                color = if (note.id in hitNoteIds) HitColor else handColor(note.hand),
                topLeft = Offset(x + 2f * density, top),
                size = Size(w - 4f * density, max(4f * density, bottom - top)),
                cornerRadius = corner,
            )
        }
    }

    // Weiße Tasten zuerst, damit die schwarzen darüber liegen
    for (midi in KeyLayout.FIRST_KEY..KeyLayout.LAST_KEY) {
        if (KeyLayout.isBlack(midi)) continue
        val x = xOf(midi)
        if (x + whiteWidth < 0f || x > size.width) continue
        drawRect(
            color = keyColor(midi, black = false, activeHand, pressedKeys, wrongKeys),
            topLeft = Offset(x, keysTop),
            size = Size(whiteWidth - density, keyboardHeight),
        )
    }
    for (midi in KeyLayout.FIRST_KEY..KeyLayout.LAST_KEY) {
        if (!KeyLayout.isBlack(midi)) continue
        val x = xOf(midi)
        val w = widthOf(midi)
        if (x + w < 0f || x > size.width) continue
        drawRect(
            color = keyColor(midi, black = true, activeHand, pressedKeys, wrongKeys),
            topLeft = Offset(x, keysTop),
            size = Size(w, keyboardHeight * 0.62f),
        )
    }

    // Anschlagslinie: hier müssen die Noten gespielt werden
    drawLine(
        color = Color.White.copy(alpha = 0.7f),
        start = Offset(0f, keysTop),
        end = Offset(size.width, keysTop),
        strokeWidth = 2f * density,
    )
}

/** Rot für falsche Tasten hat Vorrang, dann gedrückte Tasten, dann klingende Noten. */
private fun keyColor(
    midi: Int,
    black: Boolean,
    activeHand: Map<Int, Hand>,
    pressedKeys: Set<Int>,
    wrongKeys: Set<Int>,
): Color = when {
    midi in wrongKeys -> WrongColor
    midi in pressedKeys -> PressedKeyColor
    activeHand[midi] != null -> handColor(activeHand.getValue(midi))
    black -> BlackKeyColor
    else -> WhiteKeyColor
}
