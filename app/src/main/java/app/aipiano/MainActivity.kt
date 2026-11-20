package app.aipiano

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aipiano.ui.LibraryScreen
import app.aipiano.ui.PlayerScreen
import app.aipiano.ui.PlayerViewModel
import app.aipiano.ui.SettingsScreen

/** Die drei Bildschirme der App. */
enum class AppScreen { PLAYER, SETTINGS, LIBRARY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf(AppScreen.PLAYER) }
                    val playerViewModel: PlayerViewModel = viewModel()
                    BackHandler(enabled = screen != AppScreen.PLAYER) { screen = AppScreen.PLAYER }
                    when (screen) {
                        AppScreen.PLAYER -> PlayerScreen(
                            onOpenSettings = { screen = AppScreen.SETTINGS },
                            onOpenLibrary = { screen = AppScreen.LIBRARY },
                            viewModel = playerViewModel,
                        )
                        AppScreen.SETTINGS -> SettingsScreen(onBack = { screen = AppScreen.PLAYER })
                        AppScreen.LIBRARY -> LibraryScreen(
                            onBack = { screen = AppScreen.PLAYER },
                            onPlay = { id ->
                                playerViewModel.loadLibraryEntry(id)
                                screen = AppScreen.PLAYER
                            },
                        )
                    }
                }
            }
        }
    }
}
