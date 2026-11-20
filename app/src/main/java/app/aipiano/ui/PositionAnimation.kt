package app.aipiano.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlin.math.abs

/** Ab diesem Sprung (in Millisekunden) gilt eine Änderung als Notensprung und wird animiert. */
internal const val JUMP_THRESHOLD_MS = 120f
private const val JUMP_DURATION_MS = 300

/** Ist die Änderung von [fromMs] nach [toMs] ein Sprung und keine normale Fortbewegung? */
internal fun isJump(fromMs: Float, toMs: Float): Boolean = abs(toMs - fromMs) > JUMP_THRESHOLD_MS

/**
 * Liefert die angezeigte Position. Springt die Position (Schritt-Modus), gleitet die Anzeige in
 * [JUMP_DURATION_MS] zur neuen Stelle. Ohne [animate] oder bei normalem Abspielen folgt sie direkt.
 */
@Composable
fun animatedPositionMs(targetMs: Double, animate: Boolean): Double {
    val target = targetMs.toFloat()
    val display = remember { Animatable(target) }
    LaunchedEffect(target, animate) {
        if (animate && isJump(display.value, target)) {
            display.animateTo(target, tween(durationMillis = JUMP_DURATION_MS, easing = FastOutSlowInEasing))
        } else {
            display.snapTo(target)
        }
    }
    return display.value.toDouble()
}
