package app.aipiano.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aipiano.settings.InputSourceType
import app.aipiano.settings.PracticeHands
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Zurück") }
            Spacer(Modifier.width(12.dp))
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall)
        }

        SectionTitle("Eingabequelle")
        InputSourceType.entries.forEach { type ->
            ChoiceRow(
                label = type.label,
                selected = settings.inputSource == type,
                onSelect = { viewModel.update { it.copy(inputSource = type) } },
            )
        }
        Text(
            "USB-MIDI braucht ein USB-Host-fähiges Handy und einen OTG-Adapter. Für MIDI-Geräte " +
                "fragt Android keine Berechtigung ab; das Keyboard wird direkt verbunden.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Tempo: ${settings.tempoPercent} %")
        Slider(
            value = settings.tempoPercent.toFloat(),
            onValueChange = { value -> viewModel.update { it.copy(tempoPercent = value.roundToInt()) } },
            valueRange = 50f..150f,
            steps = 19,
        )

        SectionTitle("Zu übende Hände")
        PracticeHands.entries.forEach { hands ->
            ChoiceRow(
                label = hands.label,
                selected = settings.practiceHands == hands,
                onSelect = { viewModel.update { it.copy(practiceHands = hands) } },
            )
        }
        Text(
            "Die nicht geübte Hand läuft im Takt der Musik mit.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
