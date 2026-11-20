package app.aipiano.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Liest die Tonspur einer Audio- oder Videodatei als Mono-Signal.
 * WAV-Dateien werden direkt gelesen, alle anderen (MP3, AAC/M4A, OGG, MP4, MKV …) über den
 * Android-Decoder [MediaCodec]. Das Ergebnis ist nicht auf einem Gerät geprüft, siehe README.
 */
object AudioDecoder {

    class Decoded(val samples: FloatArray, val sampleRate: Int)

    /** Wert von MediaFormat.ENCODING_PCM_FLOAT (API 31); hier als Zahl, damit minSdk 26 bleibt. */
    private const val PCM_FLOAT = 4
    private const val TIMEOUT_US = 10_000L

    fun decode(context: Context, uri: Uri): Decoded {
        val header = ByteArray(12)
        val read = context.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: -1
        if (read == header.size && String(header, 0, 4, Charsets.US_ASCII) == "RIFF") {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Datei nicht lesbar")
            val wav = WavReader.read(bytes)
            return Decoded(wav.samples, wav.sampleRate)
        }
        return decodeCompressed(context, uri)
    }

    private fun decodeCompressed(context: Context, uri: Uri): Decoded {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
            ?: error("Datei nicht lesbar")
        try {
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = candidate
                    break
                }
            }
            require(track >= 0 && format != null) { "Keine Tonspur in der Datei gefunden." }
            extractor.selectTrack(track)

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val sink = FloatSink()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = out.getInteger(MediaFormat.KEY_PCM_ENCODING, 2) == PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            mixToMono(buffer, channels, pcmFloat, sink)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()
            return Decoded(sink.toArray(), sampleRate)
        } finally {
            extractor.release()
        }
    }

    /** Mischt die Kanäle eines Ausgabe-Puffers zu Mono und hängt die Werte an [sink] an. */
    private fun mixToMono(buffer: java.nio.ByteBuffer, channels: Int, pcmFloat: Boolean, sink: FloatSink) {
        if (pcmFloat) {
            val floats = buffer.asFloatBuffer()
            while (floats.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += floats.get() }
                sink.add(sum / channels)
            }
        } else {
            val shorts = buffer.asShortBuffer()
            while (shorts.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += shorts.get() / 32768f }
                sink.add(sum / channels)
            }
        }
    }

    /** Wachsender Float-Puffer ohne Umweg über Listen. */
    private class FloatSink {
        private var data = FloatArray(1 shl 20)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
