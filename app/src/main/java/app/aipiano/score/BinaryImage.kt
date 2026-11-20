package app.aipiano.score

/** Schwarz-Weiß-Bild: [dark] ist true für dunkle Bildpunkte (Noten, Linien). */
class BinaryImage(val width: Int, val height: Int, val dark: BooleanArray) {

    init {
        require(dark.size == width * height) { "Bildgröße passt nicht zu den Daten" }
    }

    fun isDark(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && dark[y * width + x]
}
