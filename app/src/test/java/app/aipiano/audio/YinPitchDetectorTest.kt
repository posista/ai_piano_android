package app.aipiano.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class YinPitchDetectorTest {

    private val sampleRate = 44_100
    private val frameSize = 2048

    private fun sine(frequency: Double): FloatArray =
        FloatArray(frameSize) { i -> (0.5 * sin(2 * PI * frequency * i / sampleRate)).toFloat() }

    @Test
    fun detectsA4As440HzAndMidi69() {
        val result = YinPitchDetector(sampleRate, frameSize).detect(sine(440.0))

        assertNotNull(result)
        assertEquals(440.0, result!!.frequencyHz, 2.0)
        assertEquals(69, PitchMath.nearestMidi(result.frequencyHz))
    }

    @Test
    fun detectsMiddleCAsMidi60() {
        val result = YinPitchDetector(sampleRate, frameSize).detect(sine(261.63))

        assertNotNull(result)
        assertEquals(60, PitchMath.nearestMidi(result!!.frequencyHz))
    }

    @Test
    fun returnsNullForSilence() {
        assertNull(YinPitchDetector(sampleRate, frameSize).detect(FloatArray(frameSize)))
    }

    @Test
    fun midiConversionMatchesStandardTuning() {
        assertEquals(69.0, PitchMath.frequencyToMidi(440.0), 1e-9)
        assertEquals(81.0, PitchMath.frequencyToMidi(880.0), 1e-9)
    }
}
