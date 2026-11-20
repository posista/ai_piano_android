package app.aipiano.review

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aipiano.ui.PlayerScreen
import app.aipiano.ui.PlayerViewModel
import app.aipiano.ui.SettingsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper

/** Erzeugt die Bilder für die Funktionsübersicht (Wartemodus, komplette Einstellungen). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class FeatureShotsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun waitingStatePortrait() {
        lateinit var vm: PlayerViewModel
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    vm = viewModel()
                    PlayerScreen(onOpenSettings = {}, viewModel = vm)
                }
            }
        }
        compose.runOnIdle { vm.loadSample() }
        waitUntil { vm.state.value.song != null }
        compose.runOnIdle { vm.play() }
        waitUntil { vm.state.value.waitingForIds.isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/review/feature_wait.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h2400dp-xxhdpi")
    fun settingsFullPage() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SettingsScreen(onBack = {}) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/review/feature_settings_full.png")
    }

    private fun waitUntil(condition: () -> Boolean) {
        var tries = 0
        while (!condition() && tries++ < 1000) {
            Thread.sleep(16)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
        }
        check(condition()) { "Bedingung nicht erreicht" }
    }

    @Test
    fun compactAndFullscreenScreens() {
        lateinit var vm: PlayerViewModel
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    vm = viewModel()
                    PlayerScreen(onOpenSettings = {}, viewModel = vm)
                }
            }
        }
        compose.runOnIdle { vm.loadSample() }
        waitUntil { vm.state.value.song != null }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/review/compact_portrait.png")

        compose.onNodeWithText("Vollbild").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/review/fullscreen.png")
    }
}
