package app.aipiano.transcribe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Einfache Radix-2-FFT (in-place) für Zweierpotenzen. */
internal class Fft(private val n: Int) {
    private val cosTable = DoubleArray(n / 2) { cos(-2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(-2 * PI * it / n) }
    private val reversed = IntArray(n)

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT-Größe muss eine Zweierpotenz sein" }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) reversed[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (j in 0 until half) {
                    val wr = cosTable[j * step]
                    val wi = sinTable[j * step]
                    val a = start + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }
}
