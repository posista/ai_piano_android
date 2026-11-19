package app.aipiano.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aipiano.settings.InputSourceType
import kotlin.math.roundToInt

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("MIDI öffnen") }
            OutlinedButton(onClick = viewModel::loadSample) { Text("Beispiel") }
            OutlinedButton(onClick = onOpenSettings) { Text("Einstellungen") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::togglePlay, enabled = song != null) {
                Text(if (state.playing) "Pause" else "Abspielen")
            }
            OutlinedButton(onClick = viewModel::restart, enabled = song != null) { Text("Neustart") }
        }

        Text("Tempo: ${state.tempoPercent} %", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = state.tempoPercent.toFloat(),
            onValueChange = { viewModel.setTempo(it.roundToInt()) },
            valueRange = 50f..150f,
            steps = 19,
        )

        Text(
            "Eingabe: ${state.inputStatus}",
            style = MaterialTheme.typography.bodySmall,
        )
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

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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

        PianoRoll(
            song = song,
            positionMs = state.positionMs,
            hitNoteIds = state.hitNoteIds,
            pressedKeys = state.pressedKeys,
            wrongKeys = state.wrongKeys,
            onKey = if (state.inputSource == InputSourceType.VIRTUAL) viewModel::onVirtualKey else null,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
