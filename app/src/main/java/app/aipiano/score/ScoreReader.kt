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
 * Notenköpfe als Blobs finden, Tonhöhe aus der Position im Notensystem, Zeit aus der Reihenfolge der
 * Notenköpfe. Zwei Notensysteme untereinander werden als Klavierakkolade gelesen: oben Violin-, unten
 * Bassschlüssel (Annahme, nicht erkannt).
 *
 * Nicht erkannt werden: Vorzeichen (es gilt immer der natürliche Ton), Pausen, Balken (Achtel
 * verbunden), Verzierungen, Text, Dynamik, Taktarten. Die Dauer ist grob: gefüllt = 1 Schlag, hohl mit
 * Hals = 2, hohl ohne Hals = 4. Ein Schlag = 0,5 s (120 BPM).
 */
object ScoreReader {

    private const val SECONDS_PER_BEAT = 0.5
    private const val VELOCITY = 90

    private class Staff(val lines: DoubleArray) {
        val top get() = lines[0]
        val bottom get() = lines[4]
        val spacing: Double = (lines[4] - lines[0]) / 4.0
    }

    private class Head(val x: Double, val y: Double, val beats: Double, val staffInSystem: Int, val system: Int)

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

        val dark = image.dark.copyOf()
        val cleaned = BinaryImage(image.width, image.height, dark)
        for (staff in staves) removeStaffLines(cleaned, staff)

        val heads = findHeads(cleaned, staves, systems)
        val notes = ArrayList<DetectedNote>()
        var beat = offsetBeats
        for (systemIndex in systems.indices) {
            val systemHeads = heads.filter { it.system == systemIndex }
            val systemStart = beat
            val columns = clusterColumns(systemHeads, systems[systemIndex])
            for (column in columns) {
                val duration = column.minOf { it.beats }
                for (head in column) {
                    val staff = systems[systemIndex][min(head.staffInSystem, systems[systemIndex].size - 1)]
                    val reference = if (head.staffInSystem == 0) StaffMath.TREBLE_BOTTOM_STEP else StaffMath.BASS_BOTTOM_STEP
                    val stepOffset = ((staff.bottom - head.y) / (staff.spacing / 2.0)).roundToInt()
                    val midi = StaffMath.midiOfStep(reference + stepOffset).coerceIn(21, 108)
                    val startSec = beat * SECONDS_PER_BEAT
                    notes += DetectedNote(midi, startSec, startSec + duration * SECONDS_PER_BEAT, VELOCITY)
                }
                beat += duration
            }
            if (columns.isEmpty()) beat = systemStart
        }
        return notes to (beat - offsetBeats)
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
        val maxRun = max(2, (staff.spacing * 0.5).roundToInt())
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

    /** Notenköpfe: zusammenhängende Flecken mit Kopfbreite, die innerhalb eines Notensystems liegen. */
    private fun findHeads(
        image: BinaryImage,
        staves: List<Staff>,
        systems: List<List<Staff>>,
    ): List<Head> {
        val visited = BooleanArray(image.dark.size)
        val heads = ArrayList<Head>()
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
                if (x > 0) visit(p - 1, image, visited, stack, { top = it }, top)
                if (x < image.width - 1) visit(p + 1, image, visited, stack, { top = it }, top)
                if (y > 0) visit(p - image.width, image, visited, stack, { top = it }, top)
                if (y < image.height - 1) visit(p + image.width, image, visited, stack, { top = it }, top)
            }
            headFromComponent(pixels, image.width, staves, systems)?.let { heads += it }
        }
        return heads
    }

    private inline fun visit(
        index: Int,
        image: BinaryImage,
        visited: BooleanArray,
        stack: IntArray,
        setTop: (Int) -> Unit,
        top: Int,
    ) {
        if (image.dark[index] && !visited[index]) {
            visited[index] = true
            stack[top] = index
            setTop(top + 1)
        }
    }

    private fun headFromComponent(
        pixels: List<Int>,
        width: Int,
        staves: List<Staff>,
        systems: List<List<Staff>>,
    ): Head? {
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

        // Der Kopf ist das Stück mit der größten Breite. Ein Hals ist nur wenige Pixel breit.
        val spacing = nearestStaff(staves, (minY + maxY) / 2.0)?.spacing ?: return null
        if (maxWidth < spacing * 0.8 || maxWidth > spacing * 2.0) return null
        val headRows = (0 until rows).filter { rowWidth[it] >= maxWidth * 0.7 }
        if (headRows.size < 3) return null

        val headPixels = pixels.filter { (it / width - minY) in headRows.first()..headRows.last() && rowWidth[it / width - minY] >= maxWidth * 0.7 }
        val centerY = headPixels.map { (it / width).toDouble() }.average()
        val centerX = headPixels.map { (it % width).toDouble() }.average()
        val filled = headPixels.size.toDouble() / (headRows.size * maxWidth) >= 0.6
        val hasStem = rows > spacing * 1.5

        val beats = when {
            filled -> 1.0
            hasStem -> 2.0
            else -> 4.0
        }
        val staffIndex = nearestStaffIndex(staves, centerY) ?: return null
        val systemIndex = systems.indexOfFirst { it.contains(staves[staffIndex]) }
        if (systemIndex < 0) return null
        val staffInSystem = systems[systemIndex].indexOf(staves[staffIndex])
        return Head(centerX, centerY, beats, staffInSystem, systemIndex)
    }

    private fun nearestStaff(staves: List<Staff>, y: Double): Staff? =
        nearestStaffIndex(staves, y)?.let { staves[it] }

    private fun nearestStaffIndex(staves: List<Staff>, y: Double): Int? {
        var best: Int? = null
        var bestDistance = Double.MAX_VALUE
        staves.forEachIndexed { index, staff ->
            val margin = staff.spacing * 4
            val distance = when {
                y < staff.top - margin -> staff.top - margin - y
                y > staff.bottom + margin -> y - staff.bottom - margin
                else -> 0.0
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return if (bestDistance <= (staves.firstOrNull()?.spacing ?: 0.0) * 6) best else null
    }

    /** Fasst Köpfe, die zur gleichen Zeit erklingen (gleiche x-Position), zu Spalten zusammen. */
    private fun clusterColumns(heads: List<Head>, system: List<Staff>): List<List<Head>> {
        if (heads.isEmpty()) return emptyList()
        val tolerance = system.first().spacing * 0.6
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
