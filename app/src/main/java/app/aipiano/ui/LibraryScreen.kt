package app.aipiano.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aipiano.library.LibraryEntry
import app.aipiano.library.Source

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var attachTarget by remember { mutableStateOf<String?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.convertAudio(uri)
    }
    val pickScore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importScore(uri)
    }
    val pickMidiForEntry = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = attachTarget
        if (uri != null && target != null) viewModel.attachMidi(target, uri)
        attachTarget = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Zurück") }
            Spacer(Modifier.width(12.dp))
            Text("Bibliothek", style = MaterialTheme.typography.headlineSmall)
        }

        Button(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("MIDI-Datei hinzufügen") }
        Button(onClick = { pickMedia.launch(arrayOf("audio/*", "video/*")) }) {
            Text("Audio oder Video umwandeln")
        }
        Button(onClick = { pickScore.launch(arrayOf("application/pdf", "image/*")) }) {
            Text("Noten als PDF oder Bild erkennen")
        }
        Text(
            "Notenerkennung: funktioniert nur bei sauber gedruckten, einfachen Noten (eine Stimme, " +
                "Violin- und Bassschlüssel, ohne Vorzeichen-Erkennung). Das Ergebnis immer prüfen.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Umwandlung: Die Tonspur wird analysiert und als MIDI gespeichert. Das Ergebnis ist eine " +
                "Näherung und muss nicht perfekt sein. Nutze nur Aufnahmen, für die du die Rechte hast.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("YouTube-Link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.addYouTubeLink(link)
                link = ""
            },
            enabled = link.isNotBlank(),
        ) { Text("Link speichern") }

        Text(
            "Ein YouTube-Link wird nur als Eintrag mit Titel gespeichert. Die Tonspur wird nicht geladen. " +
                "Lege dafür eine MIDI-Datei an und ordne sie dem Eintrag zu.",
            style = MaterialTheme.typography.bodySmall,
        )

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }

        if (entries.isEmpty()) {
            Text("Noch keine Stücke gespeichert.", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    onPlay = { onPlay(entry.id) },
                    onAttach = {
                        attachTarget = entry.id
                        pickMidiForEntry.launch(arrayOf("*/*"))
                    },
                    onOpenLink = {
                        entry.youtubeUrl?.let {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                        }
                    },
                    onDelete = { viewModel.delete(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: LibraryEntry,
    onPlay: () -> Unit,
    onAttach: () -> Unit,
    onOpenLink: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            val kind = when (entry.source) {
                Source.FILE -> "MIDI-Datei"
                Source.YOUTUBE -> "YouTube-Link"
                Source.CONVERTED -> "aus Audio/Video umgewandelt"
                Source.SCORE -> "aus Noten (PDF/Bild) erkannt"
            }
            val midiState = if (entry.hasMidi) "MIDI vorhanden" else "keine MIDI-Datei"
            Text("$kind · $midiState", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.hasMidi) {
                    Button(onClick = onPlay) { Text("Spielen") }
                } else {
                    OutlinedButton(onClick = onAttach) { Text("MIDI zuordnen") }
                }
                if (entry.youtubeUrl != null) {
                    OutlinedButton(onClick = onOpenLink) { Text("Link öffnen") }
                }
                OutlinedButton(onClick = onDelete) { Text("Löschen") }
            }
        }
    }
}
