# AI Piano

Native Android-App (Kotlin, Jetpack Compose, minSdk 26) zum Klavierlernen.

Eine MIDI-Datei wird geladen und als von oben fallende Balken über einer Klaviatur
dargestellt – wie in Piano-Tutorial-Videos. Linke und rechte Hand sind farblich getrennt.
Im Wartemodus läuft das Stück erst weiter, wenn der richtige Ton (bzw. alle Töne eines
Akkords) gespielt wird.

> **Status:** Schritt 5 von 5 – alle geplanten Funktionen sind umgesetzt: MIDI-Import, Darstellung, Abspielmodus, Wartemodus, USB-MIDI und Mikrofon-Erkennung.
> Die Funktionen folgen schrittweise (siehe Abschnitt „Fahrplan“).

## Fahrplan

| Schritt | Inhalt | Status |
|---|---|---|
| 1 | Projektgerüst, `.gitignore`, README, Beispiel-MIDI, GitHub-Actions-Build der Debug-APK | fertig |
| 2 | MIDI-Import (Noten, Startzeit, Dauer, Hand/Spur), Klaviatur (88 Tasten, zoom- und scrollbar), fallende Balken, Abspielmodus mit einstellbarem Tempo | fertig |
| 3 | Eingabe über USB-MIDI (`MidiManager`), Einstellungen (Eingabequelle, Tempo, Hände), Rückmeldung grün/rot | fertig |
| 4 | Wartemodus (wartet auf richtigen Ton bzw. Akkord), grün/rot-Rückmeldung | fertig |
| 5 | Mikrofon-Tonerkennung (YIN), Mikrofon-Empfindlichkeit, Berechtigungsabfrage | fertig |

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

## Bedienung (Stand Schritt 2)

- **MIDI öffnen:** lädt eine `.mid`-Datei aus dem Dateisystem des Handys.
- **Beispiel:** lädt die mitgelieferte Beispiel-Datei.
- **Abspielen / Pause / Neustart:** steuert die Wiedergabe.
- **Tempo:** 50 bis 150 Prozent der Originalgeschwindigkeit.
- **Zoom und Scroll:** zwei Finger zum Zoomen, mit einem Finger wischen zum Verschieben.
- Balken über der Klaviatur: orange = rechte Hand, blau = linke Hand. Eine Taste leuchtet,
  solange die zugehörige Note gespielt werden soll.

Die Hand-Zuordnung erfolgt automatisch: Hat die Datei mehrere Spuren, gilt eine Spur mit
mittlerer Tonhöhe unter C4 als linke Hand. Bei nur einer Spur entscheidet die Tonhöhe je Note.

## Eingabe und Einstellungen (Stand Schritt 3)

Über **Einstellungen** (Button oben im Hauptbildschirm):

- **Eingabequelle**
  - *USB-MIDI-Keyboard:* Note-On/Note-Off eines angeschlossenen Keyboards über `android.media.midi`.
    Das Keyboard wird automatisch übernommen, auch wenn es erst nach dem Start angesteckt wird.
    Berechtigung: Für USB-MIDI fragt Android keine Laufzeit-Berechtigung ab. Benötigt werden ein
    USB-Host-fähiges Handy und ein OTG-Adapter.
  - *Bildschirmtasten (zum Testen):* Die Klaviatur am unteren Rand kann direkt angetippt werden.
    So lässt sich die Übung auch ohne Keyboard ausprobieren.
  - *Mikrofon (nur einstimmig):* Die App erkennt die Tonhöhe per Mikrofon (YIN-Algorithmus).
    Beim ersten Auswählen fragt Android nach der Berechtigung **Mikrofon** (`RECORD_AUDIO`).
    Ohne Erlaubnis wird nichts aufgenommen. Mit dem Schieberegler „Mikrofon-Empfindlichkeit“
    stellst du ein, ab welcher Lautstärke ein Ton zählt.
    Einschränkung: Ein Mikrofon hört nur einen Ton zur Zeit. Akkorde lassen sich damit nicht
    erkennen, dafür ist ein USB-Keyboard besser geeignet.
- **Tempo** wird gespeichert und gilt auch im Hauptbildschirm.
- **Zu übende Hände:** beide, nur links oder nur rechts. Die andere Hand läuft im Takt mit.

Rückmeldung: Trifft die Eingabe eine Note im Zeitfenster von ±350 ms, wird der Balken **grün**
und die Taste leuchtet. Eine falsche Taste leuchtet **rot**. Der Zähler „Richtig / Falsch“ zeigt
das Ergebnis.

## Tonausgabe und Akkorde (Stand Nachtrag)

Zwei Schalter, jeweils im Hauptbildschirm (Button „Ton“) und in den Einstellungen:

- **Töne abspielen** (Standard: an). Eingebauter Klavier-Synthesizer ohne Samples: Gespielte
  Tasten klingen sofort. Im Wartemodus klingt auch die nicht geübte Hand. Im Abspielmodus klingt
  das ganze Stück.
- **Akkorde gleichzeitig spielen** (Standard: an). Bei an: Ein Akkord gilt erst, wenn alle geforderten
  Töne gleichzeitig gehalten werden. Bei aus: Die Akkordtöne dürfen nacheinander kommen.
  Mit dem Mikrofon ist die Gleichzeitigkeit automatisch aus, weil es nur einen Ton zur Zeit hört.

## Modi (Stand Schritt 4)

- **Wartemodus** (Standard): Bevor eine geübte Note beginnt, hält das Stück an. Es läuft erst
  weiter, wenn die richtige Taste gespielt wird. Bei einem Akkord müssen alle Töne gleichzeitig
  gedrückt werden, die Töne können nacheinander kommen, solange sie innerhalb des Zeitfensters liegen.
  Der Hinweis „Warte auf: C4 + E4 + G4“ zeigt, was gerade fehlt.
- **Abspielmodus:** Das Stück läuft im eingestellten Tempo weiter, unabhängig von der Eingabe.
  Richtige und falsche Töne werden trotzdem gezählt und farbig markiert.

Umschalten geht über den Button „Modus“ im Hauptbildschirm oder in den Einstellungen.
Die nicht geübte Hand läuft in beiden Modi im Takt mit.

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
2. Ordner in Android Studio öffnen (Android Studio bringt ein passendes JDK mit).
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
