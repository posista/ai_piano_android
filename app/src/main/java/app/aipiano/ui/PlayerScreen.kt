package app.aipiano.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aipiano.settings.InputSourceType
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    onOpenSettings: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadSong(uri)
    }
    val song = state.song

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

    val controls: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("MIDI öffnen") }
            OutlinedButton(onClick = viewModel::loadSample) { Text("Beispiel") }
            OutlinedButton(onClick = onOpenSettings) { Text("Einstellungen") }
            Button(onClick = viewModel::togglePlay, enabled = song != null) {
                Text(if (state.playing) "Pause" else "Abspielen")
            }
            OutlinedButton(onClick = viewModel::restart, enabled = song != null) { Text("Neustart") }
            OutlinedButton(onClick = viewModel::toggleWaitMode) {
                Text(if (state.waitMode) "Modus: Warten" else "Modus: Abspielen")
            }
            OutlinedButton(onClick = viewModel::toggleSound) {
                Text(if (state.soundOn) "Ton: an" else "Ton: aus")
            }
        }

        Text("Tempo: ${state.tempoPercent} %", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = state.tempoPercent.toFloat(),
            onValueChange = { viewModel.setTempo(it.roundToInt()) },
            onValueChangeFinished = viewModel::saveTempo,
            valueRange = 50f..150f,
            steps = 19,
        )

        Text("Eingabe: ${state.inputStatus}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Richtig: ${state.correctCount} · Falsch: ${state.wrongCount}",
            style = MaterialTheme.typography.bodySmall,
        )

        if (song != null) {
            Text(
                "${song.name} · ${song.notes.size} Noten · ${formatTime(song.durationMs)} · " +
                    "${song.tracks.size} Spuren",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Noch kein Stück geladen. Tippe auf „MIDI öffnen“ oder „Beispiel“.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.waitingForIds.isNotEmpty() && song != null) {
            val waitingNames = song.notes
                .filter { it.id in state.waitingForIds }
                .map { noteName(it.midi) }
                .distinct()
                .joinToString(" + ")
            Text(
                "Warte auf: $waitingNames",
                color = WrongColor,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("■ Linke Hand", color = LeftHandColor, style = MaterialTheme.typography.labelLarge)
            Text("■ Rechte Hand", color = RightHandColor, style = MaterialTheme.typography.labelLarge)
            Text("■ Richtig", color = HitColor, style = MaterialTheme.typography.labelLarge)
            Text("■ Falsch", color = WrongColor, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "Zoom: Pinch · Verschieben: Wischen",
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    val onKey = if (state.inputSource == InputSourceType.VIRTUAL) viewModel::onVirtualKey else null

    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            // Querformat: Bedienung links (scrollbar), Notenfläche rechts über die volle Höhe
            Row(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    controls()
                }
                PianoRoll(
                    song = song,
                    positionMs = state.positionMs,
                    hitNoteIds = state.hitNoteIds,
                    pressedKeys = state.pressedKeys,
                    wrongKeys = state.wrongKeys,
                    onKey = onKey,
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                controls()
                PianoRoll(
                    song = song,
                    positionMs = state.positionMs,
                    hitNoteIds = state.hitNoteIds,
                    pressedKeys = state.pressedKeys,
                    wrongKeys = state.wrongKeys,
                    onKey = onKey,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Notenname mit Oktave nach wissenschaftlicher Notation, z. B. 60 = C4. */
private fun noteName(midi: Int): String = "${NoteNames[midi % 12]}${midi / 12 - 1}"

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
