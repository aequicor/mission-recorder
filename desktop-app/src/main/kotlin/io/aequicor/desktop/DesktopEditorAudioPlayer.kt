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
    suspend fun play(audio: EditorPreviewAudio, startMicros: Long, playbackRate: Double)
    fun stop()
}

internal class JavaSoundDesktopEditorAudioPlayer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DesktopEditorAudioPlayer {
    private val lock = Any()
    private var activeLine: SourceDataLine? = null

    override suspend fun play(
        audio: EditorPreviewAudio,
        startMicros: Long,
        playbackRate: Double,
    ): Unit = withContext(dispatcher) {
        require(playbackRate.isFinite() && playbackRate > 0.0) { "Playback rate must be finite and positive." }
        require(audio.sampleRate > 0 && audio.channels > 0) { "Audio format must have a positive sample rate and channels." }
        require(audio.samples.size % audio.channels == 0) { "Audio samples must contain complete interleaved frames." }
        val format = AudioFormat(audio.sampleRate.toFloat(), 16, audio.channels, true, false)
        val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        synchronized(lock) {
            activeLine?.close()
            activeLine = line
        }
        try {
            line.open(format)
            line.start()
            val frameCount = audio.samples.size / audio.channels
            var sourceFrame = (startMicros.coerceAtLeast(0L).toDouble() * audio.sampleRate / MICROS_PER_SECOND)
                .coerceAtMost(frameCount.toDouble())
            val buffer = ByteBuffer.allocate(AUDIO_WRITE_FRAMES * audio.channels * Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            while (sourceFrame < frameCount && line.isOpen) {
                buffer.clear()
                var writtenFrames = 0
                while (writtenFrames < AUDIO_WRITE_FRAMES && sourceFrame < frameCount) {
                    val currentFrame = sourceFrame.toInt()
                    val nextFrame = (currentFrame + 1).coerceAtMost(frameCount - 1)
                    val fraction = (sourceFrame - currentFrame).toFloat()
                    repeat(audio.channels) { channel ->
                        val current = audio.samples[currentFrame * audio.channels + channel]
                        val next = audio.samples[nextFrame * audio.channels + channel]
                        val sample = current + (next - current) * fraction
                        buffer.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                    }
                    sourceFrame += playbackRate
                    writtenFrames += 1
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
private const val MICROS_PER_SECOND = 1_000_000.0
