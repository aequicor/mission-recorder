package io.aequicor.desktop

import io.aequicor.media.desktop.ffmpeg.EditorPreviewAudio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

internal interface DesktopEditorAudioPlayer {
    suspend fun play(audio: EditorPreviewAudio, startMicros: Long)
    fun stop()
}

internal class JavaSoundDesktopEditorAudioPlayer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DesktopEditorAudioPlayer {
    private val lock = Any()
    private var activeLine: SourceDataLine? = null

    override suspend fun play(audio: EditorPreviewAudio, startMicros: Long): Unit = withContext(dispatcher) {
        val format = AudioFormat(audio.sampleRate.toFloat(), 16, audio.channels, true, false)
        val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        synchronized(lock) {
            activeLine?.close()
            activeLine = line
        }
        try {
            line.open(format)
            line.start()
            val startFrame = (startMicros * audio.sampleRate / 1_000_000L).coerceAtLeast(0L)
            val startValue = (startFrame * audio.channels).coerceAtMost(audio.samples.size.toLong()).toInt()
            val buffer = ByteBuffer.allocate(AUDIO_WRITE_FRAMES * audio.channels * Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            var offset = startValue
            while (offset < audio.samples.size && line.isOpen) {
                buffer.clear()
                val end = (offset + AUDIO_WRITE_FRAMES * audio.channels).coerceAtMost(audio.samples.size)
                while (offset < end) {
                    buffer.putShort((audio.samples[offset].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                    offset += 1
                }
                line.write(buffer.array(), 0, buffer.position())
            }
            line.drain()
        } finally {
            synchronized(lock) {
                if (activeLine === line) activeLine = null
            }
            line.stop()
            line.close()
        }
    }

    override fun stop() {
        synchronized(lock) {
            activeLine?.stop()
            activeLine?.close()
            activeLine = null
        }
    }
}

private const val AUDIO_WRITE_FRAMES = 2_048
