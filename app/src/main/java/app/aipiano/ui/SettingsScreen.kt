package app.aipiano.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aipiano.audio.hasRecordAudioPermission
import app.aipiano.settings.InputSourceType
import app.aipiano.settings.PracticeHands
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micMessage by remember { mutableStateOf<String?>(null) }

    // Mikrofon-Berechtigung wird erst angefragt, wenn der Nutzer das Mikrofon wählt
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micMessage = null
            viewModel.update { it.copy(inputSource = InputSourceType.MICROPHONE) }
        } else {
            micMessage = "Ohne Mikrofon-Berechtigung kann die App nicht hören. Du kannst sie in den " +
                "Android-Einstellungen unter Apps → AI Piano → Berechtigungen erlauben."
        }
    }

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
                onSelect = {
                    if (type == InputSourceType.MICROPHONE && !context.hasRecordAudioPermission()) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        micMessage = null
                        viewModel.update { it.copy(inputSource = type) }
                    }
                },
            )
        }
        micMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(
            "USB-MIDI braucht ein USB-Host-fähiges Handy und einen OTG-Adapter. Für MIDI-Geräte " +
                "fragt Android keine Berechtigung ab; das Keyboard wird direkt verbunden.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Mikrofon-Empfindlichkeit: ${(settings.micSensitivity * 100).roundToInt()} %")
        Slider(
            value = settings.micSensitivity,
            onValueChange = { value -> viewModel.update { it.copy(micSensitivity = value) } },
            valueRange = 0f..1f,
        )
        Text(
            "Höher = auch leise Töne werden erkannt, aber auch mehr Hintergrundgeräusche. " +
                "Mit dem Mikrofon wird nur einstimmig erkannt, also Einzeltöne statt Akkorden.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Tempo: ${settings.tempoPercent} %")
        Slider(
            value = settings.tempoPercent.toFloat(),
            onValueChange = { value -> viewModel.update { it.copy(tempoPercent = value.roundToInt()) } },
            valueRange = 50f..150f,
            steps = 19,
        )

        SectionTitle("Modus")
        ChoiceRow(
            label = "Wartemodus: Das Stück hält an, bis die richtige Taste gespielt wird",
            selected = settings.waitMode,
            onSelect = { viewModel.update { it.copy(waitMode = true) } },
        )
        ChoiceRow(
            label = "Abspielmodus: Das Stück läuft im eingestellten Tempo weiter",
            selected = !settings.waitMode,
            onSelect = { viewModel.update { it.copy(waitMode = false) } },
        )

        SectionTitle("Ton und Akkorde")
        SwitchRow(
            label = "Töne abspielen",
            checked = settings.soundOn,
            onChange = { value -> viewModel.update { it.copy(soundOn = value) } },
        )
        SwitchRow(
            label = "Akkorde gleichzeitig spielen",
            checked = settings.chordsTogether,
            onChange = { value -> viewModel.update { it.copy(chordsTogether = value) } },
        )
        Text(
            "Aus: Akkordtöne dürfen nacheinander gespielt werden. Mit dem Mikrofon gilt das " +
                "automatisch, weil es nur einen Ton zur Zeit hört.",
            style = MaterialTheme.typography.bodySmall,
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
