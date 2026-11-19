package app.aipiano.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aipiano.ui.PianoRoll
import app.aipiano.ui.PlayerScreen
import app.aipiano.ui.PlayerViewModel
import app.aipiano.ui.SettingsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class UiReviewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capturePlayer(file: String, loadSample: Boolean) {
        lateinit var vm: PlayerViewModel
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    vm = viewModel()
                    PlayerScreen(onOpenSettings = {}, viewModel = vm)
                }
            }
        }
        if (loadSample) {
            compose.runOnIdle { vm.loadSample() }
            var tries = 0
            while (vm.state.value.song == null && tries++ < 500) {
                Thread.sleep(10)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            check(vm.state.value.song != null) { "Beispiel nicht geladen" }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/review/$file")
    }

    @Test
    fun playerScreenPhonePortrait() = capturePlayer("player_360dp.png", loadSample = true)

    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun playerScreenPhoneLandscape() = capturePlayer("player_landscape.png", loadSample = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun playerScreenLargePhone() = capturePlayer("player_411dp.png", loadSample = true)

    @Test
    fun settingsScreenPhone() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SettingsScreen(onBack = {}) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/review/settings_360dp.png")
    }

    /** Einzelner Finger auf der Bildschirmtastatur: Kontrolle, dass die Testmethode funktioniert. */
    @Test
    fun virtualKeyboardSingleFinger() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            Box(Modifier.size(400.dp, 300.dp).testTag("roll")) {
                PianoRoll(song = null, positionMs = 0.0, onKey = { m, d -> events += m to d }, modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("roll").performTouchInput {
            down(0, Offset(width * 0.3f, height * 0.95f))
            up(0)
        }
        compose.runOnIdle {
            assertEquals("Ereignisse: $events", 1, events.count { it.second })
        }
    }

    /** Zwei Finger gleichzeitig: für Akkorde auf der Bildschirmtastatur nötig. */
    @Test
    fun virtualKeyboardTwoFingersForChords() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            Box(Modifier.size(400.dp, 300.dp).testTag("roll")) {
                PianoRoll(song = null, positionMs = 0.0, onKey = { m, d -> events += m to d }, modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("roll").performTouchInput {
            down(0, Offset(width * 0.2f, height * 0.95f))
            down(1, Offset(width * 0.7f, height * 0.95f))
            up(1)
            up(0)
        }
        compose.runOnIdle {
            val downs = events.filter { it.second }.map { it.first }.distinct()
            assertEquals("Gleichzeitig gedrückte Tasten erkannt: $downs (alle Ereignisse $events)", 2, downs.size)
        }
    }
}
