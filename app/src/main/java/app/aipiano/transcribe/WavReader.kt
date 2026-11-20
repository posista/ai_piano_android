package app.aipiano.transcribe

/** Liest WAV-Dateien (16-Bit-PCM und 32-Bit-Float) und mischt sie auf Mono. */
object WavReader {

    class Wav(val samples: FloatArray, val sampleRate: Int)

    fun read(bytes: ByteArray): Wav {
        require(bytes.size >= 12 && ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WAVE") {
            "Keine WAV-Datei"
        }
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos)
            val length = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = le16(bytes, body)
                    channels = le16(bytes, body + 2)
                    rate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataStart = body
                    dataLength = minOf(length.toLong(), (bytes.size - body).toLong()).toInt()
                }
            }
            if (length < 0) break
            pos = body + length + (length and 1)
        }
        require(dataStart >= 0 && rate > 0 && channels > 0) { "Ungültige WAV-Datei" }
        val isPcm16 = (format == 1 || format == 0xFFFE) && bits == 16
        val isFloat32 = (format == 3 || format == 0xFFFE) && bits == 32
        require(isPcm16 || isFloat32) { "Nur 16-Bit-PCM- oder 32-Bit-Float-WAV wird unterstützt" }

        val bytesPerSample = bits / 8
        val frameBytes = bytesPerSample * channels
        val frames = dataLength / frameBytes
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val offset = dataStart + f * frameBytes + c * bytesPerSample
                sum += if (isPcm16) {
                    le16(bytes, offset).toShort() / 32768f
                } else {
                    Float.fromBits(le32(bytes, offset))
                }
            }
            mono[f] = sum / channels
        }
        return Wav(mono, rate)
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)
}
