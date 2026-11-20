package app.aipiano.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aipiano.settings.InputSourceType
import app.aipiano.settings.PracticeHands
import kotlin.math.roundToInt

/**
 * Hauptbildschirm zum Spielen. Oben eine kompakte Leiste (Abspielen, Tempo, Üben, Modus),
 * darunter Notensystem und Klaviatur. Über "Mehr" sind die seltener gebrauchten Funktionen,
 * "Vollbild" blendet alles außer Notensystem und Klaviatur aus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadSong(uri)
    }
    val song = state.song
    var showStaff by rememberSaveable { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }

    // Beim Verlassen der App anhalten, Mikrofon und Ton freigeben
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppStarted()
                Lifecycle.Event.ON_STOP -> viewModel.onAppStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    FullscreenEffect(enabled = fullscreen)
    // Zurück-Taste verlässt zuerst den Vollbildmodus
    BackHandler(enabled = fullscreen) { fullscreen = false }

    val onKey = if (state.inputSource == InputSourceType.VIRTUAL) viewModel::onVirtualKey else null
    val notes: @Composable (Modifier) -> Unit = { modifier ->
        if (showStaff) {
            StaffView(
                song = song,
                positionMs = state.positionMs,
                hitNoteIds = state.hitNoteIds,
                waitingForIds = state.waitingForIds,
                practiceHands = state.practiceHands,
                modifier = modifier.fillMaxWidth(),
            )
        }
    }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                notes(Modifier.weight(if (showStaff) 0.4f else 0.01f))
                PianoRoll(
                    song = song,
                    positionMs = state.positionMs,
                    hitNoteIds = state.hitNoteIds,
                    pressedKeys = state.pressedKeys,
                    wrongKeys = state.wrongKeys,
                    onKey = onKey,
                    practiceHands = state.practiceHands,
                    modifier = Modifier.weight(if (showStaff) 0.6f else 0.99f).fillMaxWidth(),
                )
            }
            // Schwebende Bedienung oben rechts, damit das Bild frei bleibt
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactButton(
                    text = if (state.playing) "Pause" else "Abspielen",
                    primary = true,
                    onClick = viewModel::togglePlay,
                    enabled = song != null,
                )
                CompactButton(text = "Verlassen", onClick = { fullscreen = false })
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Zeile 1: Transport und Hauptfunktionen
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactButton(
                text = if (state.playing) "Pause" else "Abspielen",
                primary = true,
                onClick = viewModel::togglePlay,
                enabled = song != null,
            )
            CompactButton(text = "Neustart", onClick = viewModel::restart, enabled = song != null)
            CompactButton(text = "Vollbild", onClick = { fullscreen = true })
            Box {
                CompactButton(text = "Mehr ▾", onClick = { moreOpen = true })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    MenuItem("MIDI öffnen") { openFile.launch(arrayOf("*/*")) }
                    MenuItem("Beispiel laden") { viewModel.loadSample() }
                    MenuItem("Bibliothek") { onOpenLibrary() }
                    MenuItem("Einstellungen") { onOpenSettings() }
                    MenuItem(if (state.soundOn) "Ton: an → aus" else "Ton: aus → an") { viewModel.toggleSound() }
                    MenuItem(if (state.otherHandSound) "Andere Hand: Ton aus" else "Andere Hand: Ton an") {
                        viewModel.toggleOtherHandSound()
                    }
                    MenuItem(if (showStaff) "Notensystem ausblenden" else "Notensystem einblenden") {
                        showStaff = !showStaff
                    }
                }
            }
        }

        // Zeile 2: Tempo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tempo ${state.tempoPercent} %",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(96.dp),
            )
            Slider(
                value = state.tempoPercent.toFloat(),
                onValueChange = { viewModel.setTempo(it.roundToInt()) },
                onValueChangeFinished = viewModel::saveTempo,
                valueRange = 50f..150f,
                steps = 19,
                modifier = Modifier.weight(1f),
            )
        }

        // Zeile 3: Üben und Modus
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PracticeHands.entries.forEach { hands ->
                FilterChip(
                    selected = state.practiceHands == hands,
                    onClick = { viewModel.setPracticeHands(hands) },
                    label = { Text(hands.shortLabel, style = MaterialTheme.typography.labelLarge) },
                )
            }
            CompactButton(
                text = if (state.waitMode) "Modus: Warten" else "Modus: Abspielen",
                onClick = viewModel::toggleWaitMode,
            )
        }

        // Zeile 4: Status in einer Zeile
        val waitingNames = song?.let { s ->
            s.notes.filter { it.id in state.waitingForIds }
                .map { noteName(it.midi) }
                .distinct()
                .joinToString(" + ")
        }.orEmpty()
        val status = when {
            state.error != null -> state.error!!
            waitingNames.isNotEmpty() -> "Warte auf: $waitingNames"
            song != null -> "${song.name} · ${song.notes.size} Noten · ${formatTime(song.durationMs)} · " +
                "Richtig ${state.correctCount} · Falsch ${state.wrongCount}"
            else -> "Noch kein Stück. „Mehr“ → MIDI öffnen, Bibliothek oder Beispiel."
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                state.error != null -> MaterialTheme.colorScheme.error
                waitingNames.isNotEmpty() -> WrongColor
                else -> MaterialTheme.colorScheme.onBackground
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Eingabe: ${state.inputStatus}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            "■ Links  ■ Rechts  ■ Richtig  ■ Falsch",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
        )

        notes(Modifier.weight(if (showStaff) 0.4f else 0.01f))
        PianoRoll(
            song = song,
            positionMs = state.positionMs,
            hitNoteIds = state.hitNoteIds,
            pressedKeys = state.pressedKeys,
            wrongKeys = state.wrongKeys,
            onKey = onKey,
            practiceHands = state.practiceHands,
            modifier = Modifier.weight(if (showStaff) 0.6f else 0.99f).fillMaxWidth(),
        )
    }
}

/** Kompakter Button mit kleiner Innenabstand, damit viele Funktionen in eine Zeile passen. */
@Composable
private fun CompactButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val modifier = Modifier.heightIn(min = 34.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, contentPadding = padding, modifier = modifier) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = padding, modifier = modifier) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
    )
}

/**
 * Schaltet die Systemleisten (Statusleiste, Navigation) für den Vollbildmodus aus und stellt sie
 * beim Verlassen wieder her. Die Leisten erscheinen bei einer Wischgeste nur kurz.
 */
@Composable
private fun FullscreenEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (enabled) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var context: android.content.Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Kurzbezeichnung für die Übungsauswahl im Hauptbildschirm. */
private val PracticeHands.shortLabel: String
    get() = when (this) {
        PracticeHands.BOTH -> "Beide"
        PracticeHands.LEFT -> "Nur links"
        PracticeHands.RIGHT -> "Nur rechts"
    }

private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Notenname mit Oktave nach wissenschaftlicher Notation, z. B. 60 = C4. */
private fun noteName(midi: Int): String = "${NoteNames[midi % 12]}${midi / 12 - 1}"

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
