package app.aipiano.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionAnimationTest {

    @Test
    fun smallStepsAreNotJumps() {
        assertFalse(isJump(1000f, 1016f)) // normales Abspielen pro Frame
        assertFalse(isJump(1000f, 1100f))
    }

    @Test
    fun largeChangesAreJumps() {
        assertTrue(isJump(1000f, 1500f)) // nächste Note im Schritt-Modus
        assertTrue(isJump(1500f, 0f))    // Neustart
    }
}
