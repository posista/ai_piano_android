package app.aipiano.score

import app.aipiano.midi.StaffMath
import app.aipiano.transcribe.DetectedNote
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Klassische Notenerkennung ohne KI-Modell für gedruckte, gerade Noten.
 *
 * Ablauf: Notenlinien finden (waagerechte Linien mit gleichem Abstand), Linien aus dem Bild entfernen,
 * Notenköpfe als Flecken finden, Tonhöhe aus der Position im Notensystem, Dauer aus Füllung und Hals.
 * Jedes Notensystem wird für sich gelesen: Die Zeit wird aus der Reihenfolge der Notenköpfe der
 * Stimme abgeleitet. Zwei Systeme untereinander gelten als Klavierakkolade (oben Violin-, unten
 * Bassschlüssel, angenommen, nicht erkannt).
 *
 * Nicht erkannt werden: Vorzeichen (es gilt immer der natürliche Ton), Pausen, Balken (Achtel
 * verbunden), Verzierungen, Text, Taktarten. Dauer: gefüllt = 1 Schlag, hohl mit Hals = 2,
 * hohl ohne Hals = 4. Ein Schlag = 0,5 s (120 BPM). Pausen zwischen den Noten sind nicht erkennbar.
 */
object ScoreReader {

    /** Diagnose: Wird bei Tests gesetzt, um verworfene Flecken zu protokollieren. */
    internal var debug: ((String) -> Unit)? = null

    private const val SECONDS_PER_BEAT = 0.5
    private const val VELOCITY = 90

    private class Staff(val lines: DoubleArray) {
        val top get() = lines[0]
        val bottom get() = lines[4]
        val spacing: Double = (lines[4] - lines[0]) / 4.0
    }

    private class Head(val x: Double, val y: Double, val beats: Double, val staff: Staff, val sharp: Boolean = false)

    /** Ein Kreuz (Vorzeichen) links neben einem Kopf. */
    private class Accidental(val x: Double, val y: Double, val rightEdge: Double, val staff: Staff)

    /** Liest alle Seiten nacheinander. Gibt Noten in Sekunden zurück, sortiert nach Startzeit. */
    fun read(pages: List<BinaryImage>): List<DetectedNote> {
        val notes = ArrayList<DetectedNote>()
        var offsetBeats = 0.0
        for (page in pages) {
            val (pageNotes, pageBeats) = readPage(page, offsetBeats)
            notes += pageNotes
            offsetBeats += pageBeats
        }
        return notes.sortedWith(compareBy<DetectedNote> { it.startSec }.thenBy { it.midi })
    }

    private fun readPage(image: BinaryImage, offsetBeats: Double): Pair<List<DetectedNote>, Double> {
        val staves = findStaves(image)
        if (staves.isEmpty()) return emptyList<DetectedNote>() to 0.0
        val systems = groupIntoSystems(staves)

        val cleaned = BinaryImage(image.width, image.height, image.dark.copyOf())
        for (staff in staves) removeStaffLines(cleaned, staff)

        val (rawHeads, accidentals) = findHeads(cleaned, staves)
        val heads = attachAccidentals(rawHeads, accidentals)
        val notes = ArrayList<DetectedNote>()
        var systemStart = offsetBeats
        for (system in systems) {
            var systemEnd = systemStart
            for (staffIndex in system.indices) {
                val staff = system[staffIndex]
                val rawStaff = heads.filter { it.staff === staff }
                val staffHeads = mergeSplitHeads(rawStaff, staff.spacing)
                debug?.invoke("STIMME ${staffIndex} roh=${rawStaff.map { "(${"%.0f".format(it.x)},${"%.0f".format(it.y)},${it.beats})" }} gemerged=${staffHeads.map { "(${"%.0f".format(it.x)},${"%.0f".format(it.y)},${it.beats})" }}")
                // Zeit je Stimme: Noten in Reihenfolge der x-Position, Akkorde gleichzeitig
                var beat = systemStart
                for (column in clusterColumns(staffHeads, staff.spacing)) {
                    val duration = column.minOf { it.beats }
                    val reference = if (staffIndex == 0) StaffMath.TREBLE_BOTTOM_STEP else StaffMath.BASS_BOTTOM_STEP
                    for (head in column) {
                        val stepOffset = ((staff.bottom - head.y) / (staff.spacing / 2.0)).roundToInt()
                        val natural = StaffMath.midiOfStep(reference + stepOffset)
                        val midi = (natural + if (head.sharp) 1 else 0).coerceIn(21, 108)
                        notes += DetectedNote(
                            midi,
                            beat * SECONDS_PER_BEAT,
                            (beat + duration) * SECONDS_PER_BEAT,
                            VELOCITY,
                        )
                    }
                    beat += duration
                }
                systemEnd = max(systemEnd, beat)
            }
            systemStart = systemEnd
        }
        return notes to (systemStart - offsetBeats)
    }

    /** Sucht Gruppen von fünf Linien mit gleichem Abstand. */
    private fun findStaves(image: BinaryImage): List<Staff> {
        var minX = image.width
        var maxX = -1
        val rowCount = IntArray(image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.dark[y * image.width + x]) {
                    rowCount[y]++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        if (maxX < 0) return emptyList()
        val span = maxX - minX + 1
        val lineRows = (0 until image.height).filter { rowCount[it] >= span * 0.5 }

        // Zusammenhängende Zeilen zu Linienmitten zusammenfassen
        val centers = ArrayList<Double>()
        var i = 0
        while (i < lineRows.size) {
            var j = i
            while (j + 1 < lineRows.size && lineRows[j + 1] == lineRows[j] + 1) j++
            centers += (lineRows[i] + lineRows[j]) / 2.0
            i = j + 1
        }

        val staves = ArrayList<Staff>()
        var k = 0
        while (k + 4 < centers.size) {
            val group = centers.subList(k, k + 5)
            val gap = (group[4] - group[0]) / 4.0
            val regular = (0 until 4).all { abs((group[it + 1] - group[it]) - gap) <= gap * 0.3 }
            if (regular && gap in 4.0..80.0) {
                staves += Staff(group.toDoubleArray())
                k += 5
            } else {
                k++
            }
        }
        return staves
    }

    /** Zwei Notensysteme dicht untereinander bilden eine Klavierakkolade. */
    private fun groupIntoSystems(staves: List<Staff>): List<List<Staff>> {
        val systems = ArrayList<List<Staff>>()
        var i = 0
        while (i < staves.size) {
            val first = staves[i]
            val second = staves.getOrNull(i + 1)
            if (second != null && second.top - first.bottom < first.spacing * 10) {
                systems += listOf(first, second)
                i += 2
            } else {
                systems += listOf(first)
                i++
            }
        }
        return systems
    }

    /**
     * Entfernt Notenlinien, aber nur Pixel mit kurzem senkrechten Lauf. Hälse und Notenköpfe haben
     * einen langen senkrechten Lauf und bleiben erhalten.
     */
    private fun removeStaffLines(image: BinaryImage, staff: Staff) {
        // Nur Pixel mit einem Lauf etwa von der Stärke einer Linie entfernen. Ein Kopf auf der Linie
        // hat einen längeren senkrechten Lauf und bleibt erhalten.
        val maxRun = max(3, (staff.spacing * 0.25).roundToInt())
        for (line in staff.lines) {
            val centerRow = line.roundToInt()
            for (y in centerRow - 2..centerRow + 2) {
                for (x in 0 until image.width) {
                    if (!image.isDark(x, y)) continue
                    var run = 1
                    var up = y - 1
                    while (image.isDark(x, up) && run <= maxRun) { run++; up-- }
                    var down = y + 1
                    while (image.isDark(x, down) && run <= maxRun) { run++; down++ }
                    if (run <= maxRun) image.dark[y * image.width + x] = false
                }
            }
        }
    }

    /** Notenköpfe: zusammenhängende Flecken mit Kopfbreite, die im Bereich eines Notensystems liegen. */
    private fun findHeads(image: BinaryImage, staves: List<Staff>): Pair<List<Head>, List<Accidental>> {
        val visited = BooleanArray(image.dark.size)
        val heads = ArrayList<Head>()
        val accidentals = ArrayList<Accidental>()
        val stack = IntArray(image.dark.size)
        for (start in image.dark.indices) {
            if (!image.dark[start] || visited[start]) continue
            // Flood-Fill (4er-Nachbarschaft), iterativ
            var top = 0
            stack[top++] = start
            visited[start] = true
            val pixels = ArrayList<Int>()
            while (top > 0) {
                val p = stack[--top]
                pixels += p
                val x = p % image.width
                val y = p / image.width
                val neighbours = IntArray(4)
                var count = 0
                if (x > 0) neighbours[count++] = p - 1
                if (x < image.width - 1) neighbours[count++] = p + 1
                if (y > 0) neighbours[count++] = p - image.width
                if (y < image.height - 1) neighbours[count++] = p + image.width
                for (n in 0 until count) {
                    val q = neighbours[n]
                    if (image.dark[q] && !visited[q]) {
                        visited[q] = true
                        stack[top++] = q
                    }
                }
            }
            val shape = shapeOf(pixels, image.width)
            val staff = nearestStaff(staves, (shape.minY + shape.maxY) / 2.0)
            if (staff != null && isAccidental(shape, staff.spacing)) {
                accidentals += Accidental(shape.centerX, shape.centerY, shape.maxX.toDouble(), staff)
                debug?.invoke("KREUZ x=${"%.0f".format(shape.centerX)} y=${"%.0f".format(shape.centerY)} rechts=${shape.maxX} hoehe=${shape.maxY - shape.minY + 1} breite=${shape.maxX - shape.minX + 1} zeile=${shape.maxRow}")
            } else {
                heads += headsFromComponent(pixels, image, staves)
            }
        }
        return heads to accidentals
    }

    /** Umriss eines Flecks: Ausdehnung und breiteste Zeile. */
    private class Shape(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val maxRow: Int) {
        val centerX get() = (minX + maxX) / 2.0
        val centerY get() = (minY + maxY) / 2.0
    }

    private fun shapeOf(pixels: List<Int>, width: Int): Shape {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (p in pixels) {
            val x = p % width
            val y = p / width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val rowWidth = IntArray(maxY - minY + 1)
        for (p in pixels) rowWidth[p / width - minY]++
        return Shape(minX, maxX, minY, maxY, rowWidth.max())
    }

    /**
     * Ein Kreuz ist hoch und schmal, hat aber keine breiten Zeilen wie ein Notenkopf. Köpfe, Hälse
     * und Balken werden dadurch ausgeschlossen.
     */
    private fun isAccidental(shape: Shape, spacing: Double): Boolean {
        val height = shape.maxY - shape.minY + 1
        val width = shape.maxX - shape.minX + 1
        return height in (spacing * 1.6).roundToInt()..(spacing * 3.6).roundToInt() &&
            width <= spacing * 1.3 &&
            shape.maxRow in (spacing * 0.25).roundToInt()..(spacing * 1.0).roundToInt()
    }

    /** Ordnet jedem Kreuz den Kopf rechts daneben auf gleicher Höhe zu. */
    private fun attachAccidentals(heads: List<Head>, accidentals: List<Accidental>): List<Head> {
        val sharpened = BooleanArray(heads.size)
        for (acc in accidentals) {
            val spacing = acc.staff.spacing
            var best = -1
            var bestDistance = Double.MAX_VALUE
            heads.forEachIndexed { i, h ->
                val gap = h.x - acc.rightEdge
                if (h.staff === acc.staff && gap in spacing * 0.1..spacing * 2.0 && abs(h.y - acc.y) <= spacing * 1.2) {
                    val d = abs(h.y - acc.y) + gap
                    if (d < bestDistance) {
                        bestDistance = d
                        best = i
                    }
                }
            }
            if (best >= 0) sharpened[best] = true
        }
        return heads.mapIndexed { i, h -> if (sharpened[i]) Head(h.x, h.y, h.beats, h.staff, sharp = true) else h }
    }

    /**
     * Zerlegt einen zusammenhängenden Fleck in Notenköpfe. Ein Kopf ist ein zusammenhängender Bereich
     * breiter Zeilen. Zwei Köpfe eines Akkords sind dadurch getrennt, weil zwischen ihnen nur der
     * schmale Hals liegt. Ein Ring (hohle Note) bleibt ein Kopf, weil seine Seiten noch breit sind.
     */
    private fun headsFromComponent(pixels: List<Int>, image: BinaryImage, staves: List<Staff>): List<Head> {
        val width = image.width
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (p in pixels) {
            val y = p / width
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val rows = maxY - minY + 1
        val rowWidth = IntArray(rows)
        for (p in pixels) rowWidth[p / width - minY]++
        val maxWidth = rowWidth.max()

        val staff = nearestStaff(staves, (minY + maxY) / 2.0)
        if (staff == null) {
            debug?.invoke("verworfen: kein Notensystem y=$minY..$maxY")
            return emptyList()
        }
        val spacing = staff.spacing
        // Ein Kopf ist etwa eine Notenlinie breit. Balken und Zeichen sind breiter oder schmaler.
        if (maxWidth < spacing * 0.5 || maxWidth > spacing * 2.6) {
            debug?.invoke("verworfen: Breite $maxWidth bei Abstand ${"%.1f".format(spacing)} y=$minY..$maxY")
            return emptyList()
        }

        // Körperzeilen: breit genug für einen Kopf. Der Hals ist schmaler und trennt Köpfe voneinander.
        val body = BooleanArray(rows) { rowWidth[it] >= maxWidth * 0.22 }
        val segments = ArrayList<IntRange>()
        var r = 0
        while (r < rows) {
            if (body[r]) {
                var e = r
                while (e + 1 < rows && body[e + 1]) e++
                segments += r..e
                r = e + 1
            } else {
                r++
            }
        }

        val heads = ArrayList<Head>()
        for (seg in segments) {
            val segHeight = seg.last - seg.first + 1
            if (segHeight < spacing * 0.5) continue
            val segMaxWidth = seg.maxOf { rowWidth[it] }
            val centerY = minY + (seg.first + seg.last) / 2.0
            // Hals vorhanden, wenn der Fleck über oder unter dem Kopf deutlich weiter reicht
            val reachAbove = seg.first
            val reachBelow = rows - 1 - seg.last
            val hasStem = max(reachAbove, reachBelow) > spacing * 0.8
            // Waagerechte Mitte aus den Pixeln des Kopfes (ohne Hals)
            var sumX = 0.0
            var countX = 0
            for (p in pixels) {
                val rr = p / width - minY
                if (rr in seg && rowWidth[rr] >= segMaxWidth * 0.7) {
                    sumX += (p % width).toDouble()
                    countX++
                }
            }
            if (countX == 0) continue
            val centerX = sumX / countX
            // Gefüllt: der Kopfkasten ist fast ganz schwarz (nur die Ecken des Ovals sind weiß).
            // Hohl: zusätzlich ein weißes Loch in der Mitte, daher mehr weiße Pixel.
            val half = segMaxWidth / 2
            var white = 0
            var total = 0
            for (yy in seg) for (xx in (centerX - half).roundToInt()..(centerX + half).roundToInt()) {
                total++
                if (!image.isDark(xx, minY + yy)) white++
            }
            val filled = total > 0 && white.toDouble() / total < 0.35
            val beats = when {
                filled -> 1.0
                hasStem -> 2.0
                else -> 4.0
            }
            debug?.invoke("KOPF x=${"%.0f".format(centerX)} y=${"%.0f".format(centerY)} schläge=$beats filled=$filled stem=$hasStem")
            heads += Head(centerX, centerY, beats, staff)
        }
        return heads
    }

    private fun p2row(p: Int, width: Int): Int = p / width

    private fun nearestStaff(staves: List<Staff>, y: Double): Staff? {
        var best: Staff? = null
        var bestDistance = Double.MAX_VALUE
        for (staff in staves) {
            val margin = staff.spacing * 4
            val distance = when {
                y < staff.top - margin -> staff.top - margin - y
                y > staff.bottom + margin -> y - staff.bottom - margin
                else -> 0.0
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = staff
            }
        }
        return if (bestDistance <= (best?.spacing ?: 0.0) * 6) best else null
    }

    /**
     * Ein Kopf, der genau auf einer Notenlinie liegt, kann in zwei Hälften zerfallen. Zwei Köpfe
     * auf gleicher Höhe und nur eine Notenbreite auseinander sind daher ein Kopf. Die längere Dauer
     * gilt, weil eine ganze Note in ihren Hälften gefüllt aussehen kann.
     */
    private fun mergeSplitHeads(heads: List<Head>, spacing: Double): List<Head> {
        val sorted = heads.sortedBy { it.x }
        val result = ArrayList<Head>()
        for (head in sorted) {
            // Partner suchen: gleiche Höhe, nur eine Notenbreite entfernt
            val partner = result.indexOfFirst {
                abs(head.x - it.x) <= spacing * 1.3 && abs(head.y - it.y) <= spacing * 0.2
            }
            if (partner >= 0) {
                val last = result[partner]
                result[partner] = Head(
                    (last.x + head.x) / 2,
                    (last.y + head.y) / 2,
                    max(last.beats, head.beats),
                    last.staff,
                    last.sharp || head.sharp,
                )
            } else {
                result += head
            }
        }
        return result
    }

    /** Fasst Köpfe einer Stimme, die zur gleichen Zeit erklingen (gleiche x-Position), zu Spalten zusammen. */
    private fun clusterColumns(heads: List<Head>, spacing: Double): List<List<Head>> {
        if (heads.isEmpty()) return emptyList()
        // Akkordtöne liegen genau übereinander; schon ein Viertel Linienabstand trennt zwei Noten
        val tolerance = spacing * 0.25
        val sorted = heads.sortedBy { it.x }
        val columns = ArrayList<MutableList<Head>>()
        for (head in sorted) {
            val current = columns.lastOrNull()
            if (current != null && abs(head.x - current.first().x) <= tolerance) {
                current += head
            } else {
                columns += mutableListOf(head)
            }
        }
        return columns
    }
}
