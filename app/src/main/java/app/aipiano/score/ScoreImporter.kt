package app.aipiano.score

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Lädt Noten aus einer PDF- oder Bilddatei als Liste von Schwarz-Weiß-Seiten.
 * PDF-Seiten werden mit [PdfRenderer] gerendert. Bilder werden über [BitmapFactory] geladen.
 */
object ScoreImporter {

    /** Breite, auf die jede Seite gerendert wird. Höhere Werte sind genauer, aber langsamer. */
    private const val TARGET_WIDTH = 2000
    private const val MAX_PAGES = 12
    private const val DARK_LEVEL = 140

    fun load(context: Context, uri: Uri, mime: String?, name: String): List<BinaryImage> {
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
        return if (isPdf) renderPdf(context, uri) else listOf(loadImage(context, uri))
    }

    private fun renderPdf(context: Context, uri: Uri): List<BinaryImage> {
        val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("PDF nicht lesbar")
        descriptor.use {
            val renderer = PdfRenderer(it)
            try {
                val pages = ArrayList<BinaryImage>()
                for (index in 0 until minOf(renderer.pageCount, MAX_PAGES)) {
                    val page = renderer.openPage(index)
                    try {
                        val scale = TARGET_WIDTH.toFloat() / page.width
                        val width = TARGET_WIDTH
                        val height = (page.height * scale).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(0xFFFFFFFF.toInt())
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages += toBinary(bitmap)
                        bitmap.recycle()
                    } finally {
                        page.close()
                    }
                }
                return pages
            } finally {
                renderer.close()
            }
        }
    }

    private fun loadImage(context: Context, uri: Uri): BinaryImage {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Bild nicht lesbar")
        val binary = toBinary(bitmap)
        bitmap.recycle()
        return binary
    }

    private fun toBinary(bitmap: Bitmap): BinaryImage {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val dark = BooleanArray(pixels.size) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 0.3 + g * 0.59 + b * 0.11) < DARK_LEVEL
        }
        return BinaryImage(width, height, dark)
    }
}
