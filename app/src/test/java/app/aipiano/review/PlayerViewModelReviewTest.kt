package app.aipiano.review

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aipiano.settings.AppSettings
import app.aipiano.settings.InputSourceType
import app.aipiano.settings.PracticeHands
import app.aipiano.settings.SettingsRepository
import app.aipiano.ui.PlayerViewModel
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Prüft Abspiel- und Wartemodus mit dem Beispielstück und simulierter Zeit.
 * Beispielstück: rechte Hand ab 0 ms alle 500 ms (E4 E4 F4 G4 | G4 F4 E4 D4 ...),
 * linke Hand je Takt ein Akkord (0 ms: C3 E3 G3, 2000 ms: G2 B2 D3, ...).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerViewModelReviewTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle(what: String, condition: () -> Boolean) {
        repeat(600) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Zustand nicht erreicht: $what")
    }

    /**
     * Lässt Zeit in 16-ms-Schritten vergehen. Der ViewModel misst mit System.nanoTime() (echte Uhr),
     * daher wird zusätzlich real gewartet; idleFor treibt die delay()-Aufrufe im Main-Looper an.
     */
    private fun advance(ms: Long) {
        var t = 0L
        while (t < ms) {
            Thread.sleep(16)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            t += 16
        }
    }

    private fun createVm(settings: AppSettings): PlayerViewModel {
        runBlocking { SettingsRepository(app).save(settings) }
        val vm = PlayerViewModel(app)
        settle("Einstellungen übernommen") {
            val s = vm.state.value
            s.inputSource == settings.inputSource && s.waitMode == settings.waitMode &&
                s.chordsTogether == settings.chordsTogether && s.practiceHands == settings.practiceHands &&
                s.soundOn == settings.soundOn
        }
        vm.loadSample()
        settle("Beispiel geladen") { vm.state.value.song != null }
        return vm
    }

    private fun PlayerViewModel.tap(midi: Int) {
        onVirtualKey(midi, true); idle()
        onVirtualKey(midi, false); idle()
    }

    private fun PlayerViewModel.advanceUntilWaiting(maxMs: Long = 5000) {
        var t = 0L
        while (state.value.waitingForIds.isEmpty() && t < maxMs) {
            advance(16); t += 16
        }
        assertTrue("Stück hätte anhalten müssen, Position=${state.value.positionMs}", state.value.waitingForIds.isNotEmpty())
    }

    private fun PlayerViewModel.waitingKeys(): Set<Int> {
        val s = state.value
        return s.song!!.notes.filter { it.id in s.waitingForIds }.map { it.midi }.toSet()
    }

    private val base = AppSettings(
        inputSource = InputSourceType.VIRTUAL,
        waitMode = true,
        soundOn = false,
        chordsTogether = false,
        practiceHands = PracticeHands.BOTH,
    )

    @Test
    fun clockAdvancesWithSimulatedTime() {
        val vm = createVm(base.copy(waitMode = false))
        vm.play(); advance(1000)
        val pos = vm.state.value.positionMs
        assertTrue("Position nach 1 s: $pos", pos in 900.0..1100.0)
    }

    @Test
    fun waitModeHoldsAtFirstNoteOfSong() {
        val vm = createVm(base)
        vm.play(); advance(300)
        val s = vm.state.value
        assertTrue(
            "Erster Anschlag bei 0 ms (E4 + C-Dur-Akkord) wurde übersprungen: Position=${s.positionMs}, " +
                "wartet auf=${vm.waitingKeys()}",
            s.positionMs == 0.0 && s.waitingForIds.isNotEmpty(),
        )
    }

    @Test
    fun waitModeContinuesAfterCorrectNote() {
        val vm = createVm(base)
        vm.play()
        vm.advanceUntilWaiting()
        val waitingAt = vm.state.value.positionMs
        val keys = vm.waitingKeys()
        keys.forEach { vm.tap(it) }
        assertTrue("nach richtigem Ton noch wartend: ${vm.waitingKeys()}", vm.state.value.waitingForIds.isEmpty())
        advance(200)
        assertTrue("läuft nicht weiter", vm.state.value.positionMs > waitingAt)
        assertEquals(keys.size, vm.state.value.correctCount)
    }

    @Test
    fun wrongKeyIsCountedAndDoesNotRelease() {
        val vm = createVm(base)
        vm.play()
        vm.advanceUntilWaiting()
        vm.tap(30) // A1, kommt im Stück nicht vor
        assertEquals(1, vm.state.value.wrongCount)
        assertTrue(vm.state.value.waitingForIds.isNotEmpty())
    }

    @Test
    fun strictChordNeedsAllKeysHeldTogether() {
        val vm = createVm(base.copy(chordsTogether = true))
        vm.play()
        // bis zum G-Dur-Akkord bei 2000 ms (G2 B2 D3 + G4) vorspielen
        while (true) {
            vm.advanceUntilWaiting()
            if (vm.state.value.positionMs >= 2000.0) break
            vm.waitingKeys().forEach { vm.onVirtualKey(it, true) }
            idle()
            vm.waitingKeys().ifEmpty { null }
            vm.state.value.pressedKeys.forEach { vm.onVirtualKey(it, false) }
            idle()
        }
        val chord = vm.waitingKeys()
        assertEquals(setOf(43, 47, 50, 67), chord)

        // nacheinander drücken und loslassen: darf nicht reichen
        chord.forEach { vm.tap(it) }
        assertTrue("Akkord wurde ohne gleichzeitiges Halten akzeptiert", vm.state.value.waitingForIds.isNotEmpty())

        // alle gleichzeitig halten
        chord.forEach { vm.onVirtualKey(it, true); idle() }
        assertTrue("Akkord trotz Halten nicht akzeptiert", vm.state.value.waitingForIds.isEmpty())
    }

    @Test
    fun switchingHandsWhileWaitingDoesNotDeadlock() {
        val vm = createVm(base)
        vm.play()
        vm.advanceUntilWaiting() // Anfangsakkord bei 0 ms (C3 E3 G3 + E4)
        vm.waitingKeys().forEach { vm.tap(it) }
        vm.advanceUntilWaiting() // wartet auf E4 (rechte Hand) bei 500 ms
        assertEquals(setOf(64), vm.waitingKeys())

        runBlocking { SettingsRepository(app).save(base.copy(practiceHands = PracticeHands.LEFT)) }
        settle("Hand gewechselt") { vm.state.value.practiceHands == PracticeHands.LEFT }
        vm.tap(64) // genau die Taste, auf die gewartet wird
        advance(500)
        assertTrue(
            "Nach Wechsel auf 'nur links' hängt das Stück bei ${vm.state.value.positionMs} ms und " +
                "wartet weiter auf rechte Hand ${vm.waitingKeys()}",
            vm.state.value.waitingForIds.isEmpty(),
        )
    }

    @Test
    fun playModeRunsToEndAndStops() {
        val vm = createVm(base.copy(waitMode = false))
        vm.play(); advance(17_000)
        val s = vm.state.value
        assertFalse(s.playing)
        assertEquals(s.song!!.durationMs.toDouble(), s.positionMs, 0.5)
    }

    @Test
    fun halfTempoHalvesSpeed() {
        val vm = createVm(base.copy(waitMode = false, tempoPercent = 50))
        vm.play(); advance(1000)
        val pos = vm.state.value.positionMs
        assertTrue("Position bei 50 % nach 1 s: $pos", pos in 400.0..600.0)
    }

    @Test
    fun playModeCountsHitsInTime() {
        val vm = createVm(base.copy(waitMode = false))
        vm.play(); advance(500) // E4 bei 500 ms
        vm.tap(64)
        assertEquals(1, vm.state.value.correctCount)
        assertEquals(0, vm.state.value.wrongCount)
    }

    /** Übungsmodus "nur links": das Stück wartet nur auf linke Töne, rechte Töne laufen mit. */
    @Test
    fun practiceLeftHandWaitsOnlyForLeftHand() {
        val vm = createVm(base.copy(practiceHands = PracticeHands.LEFT))
        vm.play()
        vm.advanceUntilWaiting() // Anfangsakkord C3 E3 G3, E4 der rechten Hand wird nicht verlangt
        assertEquals(setOf(48, 52, 55), vm.waitingKeys())
        vm.waitingKeys().forEach { vm.tap(it) }
        vm.advanceUntilWaiting() // nächster linker Akkord bei 2000 ms
        assertEquals(setOf(43, 47, 50), vm.waitingKeys())
    }

    /** Schritt-Modus: Die Zeit geht nur mit richtigen Anschlägen weiter, falsche Tasten bewegen nichts. */
    @Test
    fun stepModeAdvancesOnlyOnCorrectKeys() {
        val vm = createVm(base.copy(waitMode = true, stepMode = true, chordsTogether = false))
        vm.play()
        advance(200)
        assertEquals(0.0, vm.state.value.positionMs, 0.0)
        assertEquals(setOf(48, 52, 55, 64), vm.waitingKeys())

        vm.tap(30) // A1, kommt nicht vor
        advance(200)
        assertEquals("falsche Taste darf nichts bewegen", 0.0, vm.state.value.positionMs, 0.0)

        vm.waitingKeys().forEach { vm.tap(it) }
        advance(100)
        assertEquals("nach richtigen Tasten zur nächsten Note (500 ms)", 500.0, vm.state.value.positionMs, 0.0)
        assertEquals(setOf(64), vm.waitingKeys())
    }
}
