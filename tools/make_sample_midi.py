#!/usr/bin/env python3
"""Erzeugt die Beispiel-MIDI-Datei "Ode an die Freude" (gemeinfreie Melodie, Beethoven).

Aufbau (SMF Format 1, 480 Ticks pro Viertelnote, 120 BPM):
  Spur 0: Tempo, Taktart, Titel
  Spur 1: rechte Hand (Melodie)
  Spur 2: linke Hand (ein Dur-Akkord pro Takt, jeweils drei Töne gleichzeitig)

Aufruf:
  python3 tools/make_sample_midi.py app/src/main/assets/samples/ode_an_die_freude.mid
"""
import struct
import sys

PPQ = 480                 # Ticks pro Viertelnote
BAR = 4 * PPQ             # ein Takt im 4/4-Takt
ARTIC = 40                # kleine Lücke zwischen gleichen Tönen, damit sie getrennt klingen

# Melodie in Takten: Liste von (Tonhöhe, Dauer in Vierteln)
MELODY = [
    # Teil A
    [(64, 1), (64, 1), (65, 1), (67, 1)],
    [(67, 1), (65, 1), (64, 1), (62, 1)],
    [(60, 1), (60, 1), (62, 1), (64, 1)],
    [(64, 1), (62, 1), (62, 2)],
    # Teil B
    [(64, 1), (64, 1), (65, 1), (67, 1)],
    [(67, 1), (65, 1), (64, 1), (62, 1)],
    [(60, 1), (60, 1), (62, 1), (64, 1)],
    [(62, 1), (60, 1), (60, 2)],
]

# Akkorde der linken Hand, je Takt: C-Dur (C3, E3, G3) und G-Dur (G2, B2, D3)
C_MAJOR = [48, 52, 55]
G_MAJOR = [43, 47, 50]
CHORDS = [C_MAJOR, G_MAJOR, C_MAJOR, G_MAJOR, C_MAJOR, G_MAJOR, C_MAJOR, C_MAJOR]


def varlen(value: int) -> bytes:
    """MIDI-Variable-Length-Quantity (Delta-Zeiten)."""
    out = [value & 0x7F]
    value >>= 7
    while value:
        out.insert(0, (value & 0x7F) | 0x80)
        value >>= 7
    return bytes(out)


def meta(delta: int, mtype: int, data: bytes) -> bytes:
    return varlen(delta) + bytes([0xFF, mtype]) + varlen(len(data)) + data


def track_chunk(events: bytes) -> bytes:
    return b"MTrk" + struct.pack(">I", len(events)) + events


def notes_to_track(notes, channel: int, name: str) -> bytes:
    """notes: Liste von (Startzeit in Ticks, Dauer in Ticks, Tonhöhe, Anschlagstärke)."""
    # Note-Aus vor Note-An bei gleichem Zeitpunkt, sonst wird ein wiederholter Ton abgeschnitten.
    events = []
    for start, dur, key, vel in notes:
        events.append((start, 1, bytes([0x90 | channel, key, vel])))
        events.append((start + dur, 0, bytes([0x80 | channel, key, 0])))
    events.sort(key=lambda e: (e[0], e[1]))

    data = meta(0, 0x03, name.encode("ascii"))
    last = 0
    for tick, _, payload in events:
        data += varlen(tick - last) + payload
        last = tick
    data += meta(0, 0x2F, b"")
    return track_chunk(data)


def build() -> bytes:
    # Spur 0: Tempo (500000 µs pro Viertelnote = 120 BPM) und Taktart 4/4
    conductor = (
        meta(0, 0x03, b"Ode an die Freude")
        + meta(0, 0x51, (500000).to_bytes(3, "big"))
        + meta(0, 0x58, bytes([4, 2, 24, 8]))
        + meta(0, 0x2F, b"")
    )
    tracks = [track_chunk(conductor)]

    # Spur 1: rechte Hand
    right = []
    tick = 0
    for bar in MELODY:
        for key, quarters in bar:
            dur = quarters * PPQ
            right.append((tick, dur - ARTIC, key, 96))
            tick += dur
    tracks.append(notes_to_track(right, 0, "Rechte Hand"))

    # Spur 2: linke Hand, jeder Akkord hält einen ganzen Takt
    left = []
    for bar_index, chord in enumerate(CHORDS):
        start = bar_index * BAR
        for key in chord:
            left.append((start, BAR - ARTIC, key, 64))
    tracks.append(notes_to_track(left, 1, "Linke Hand"))

    header = b"MThd" + struct.pack(">IHHH", 6, 1, len(tracks), PPQ)
    return header + b"".join(tracks)


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "ode_an_die_freude.mid"
    with open(target, "wb") as f:
        f.write(build())
    print(f"geschrieben: {target}")
