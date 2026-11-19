# AI Piano

Native Android-App (Kotlin, Jetpack Compose, minSdk 26) zum Klavierlernen.

Eine MIDI-Datei wird geladen und als von oben fallende Balken über einer Klaviatur
dargestellt – wie in Piano-Tutorial-Videos. Linke und rechte Hand sind farblich getrennt.
Im Wartemodus läuft das Stück erst weiter, wenn der richtige Ton (bzw. alle Töne eines
Akkords) gespielt wird.

> **Status:** Schritt 1 von 5 – Grundgerüst, Gradle-Projekt und automatischer APK-Build.
> Die Funktionen folgen schrittweise (siehe Abschnitt „Fahrplan“).

## Fahrplan

| Schritt | Inhalt | Status |
|---|---|---|
| 1 | Projektgerüst, `.gitignore`, README, Beispiel-MIDI, GitHub-Actions-Build der Debug-APK | fertig |
| 2 | MIDI-Import (Noten, Startzeit, Dauer, Hand/Spur), Klaviatur (61+ Tasten, zoom- und scrollbar), fallende Balken, Abspielmodus mit einstellbarem Tempo | geplant |
| 3 | Eingabe über USB-MIDI (`MidiManager`), Einstellungen (Eingabequelle, Tempo, Hände) | geplant |
| 4 | Wartemodus (wartet auf richtigen Ton bzw. Akkord), grün/rot-Rückmeldung | geplant |
| 5 | Mikrofon-Tonerkennung (YIN), Mikrofon-Empfindlichkeit, Berechtigungsabfrage | geplant |

## Projektstruktur

Geplante Paketstruktur unter `app/src/main/java/app/aipiano/`:

| Paket | Aufgabe |
|---|---|
| `midi` | Parser für Standard-MIDI-Dateien (SMF) und Song-Modell |
| `input` | Schnittstelle `NoteInputSource` mit Flow aus `NoteEvent`s, Quellen für USB-MIDI und Mikrofon |
| `audio` | Tonhöhenerkennung (YIN) und Umrechnung Frequenz → MIDI-Notennummer |
| `player` | Wiedergabe- und Wartelogik (ohne Android-Abhängigkeiten, daher testbar) |
| `settings` | Einstellungen (DataStore) |
| `ui` | Compose-Oberfläche und ViewModels (MVVM) |

## Beispiel-MIDI zum Testen

Im Repository liegt `app/src/main/assets/samples/ode_an_die_freude.mid`
(„Ode an die Freude“, gemeinfreie Melodie, 120 BPM, rechte Hand = Melodie,
linke Hand = Akkorde). Die Datei kann auf das Handy kopiert und in der App geöffnet werden.
Neu erzeugen lässt sie sich mit:

```bash
python3 tools/make_sample_midi.py app/src/main/assets/samples/ode_an_die_freude.mid
```

## Bauen

### Variante A: Debug-APK aus GitHub Actions (ohne lokale Entwicklungsumgebung)

1. Im Repository auf den Tab **Actions** wechseln.
2. Den neuesten Lauf **„Debug-APK bauen“** öffnen (jeder Push startet einen Lauf).
3. Unter **Artifacts** das Paket **`AI_Piano_debug_apk`** herunterladen.
4. Die ZIP-Datei entpacken; darin liegt die `app-debug.apk`.

### Variante B: Lokal mit Android Studio

1. Repository klonen: `git clone https://github.com/posista/ai_piano_android`
2. Ordner in Android Studio öffnen (Ladbar: JDK 17 ist eingebaut in neueren Versionen).
3. Warten, bis die Gradle-Synchronisierung fertig ist.
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)** wählen.

### Variante C: Lokal per Kommandozeile

```bash
gradle testDebugUnitTest assembleDebug     # benötigt Gradle 8.10+, JDK 17 und das Android SDK
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk
```

## Installieren auf dem Android-Handy

1. APK-Datei auf das Handy übertragen (USB, Cloud, Mail …).
2. In der Dateiverwaltung auf die APK tippen.
3. Beim ersten Mal die Installation aus dieser Quelle erlauben
   (Einstellungen → Apps → Sonderzugriff → „Apps installieren“ bzw. „Unbekannte Apps installieren“).
4. Installieren und öffnen.

Für USB-MIDI-Keyboards (ab Schritt 3) wird ein USB-OTG-Adapter (USB-C auf USB-A bzw. USB-C auf
USB-C, je nach Keyboard) benötigt. Das Handy muss USB-Host/MIDI unterstützen.

## Lizenz

Noch nicht festgelegt.
