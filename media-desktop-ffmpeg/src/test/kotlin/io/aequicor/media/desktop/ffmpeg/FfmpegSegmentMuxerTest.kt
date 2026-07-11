package io.aequicor.media.desktop.ffmpeg

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FfmpegSegmentMuxerTest {
    @Test
    fun writesBoundedDecodableTransportStreamSegments() {
        val directory = Files.createTempDirectory("mission-recorder-segments")
        val pattern = directory.resolve("segment-%06d.ts")
        val converter = Java2DFrameConverter()
        val recorder = FFmpegFrameRecorder(pattern.toString(), WIDTH, HEIGHT, 0).apply {
            format = "segment"
            setOption("segment_format", "mpegts")
            setOption("segment_time", "1")
            setOption("segment_wrap", MAX_SEGMENTS.toString())
            setOption("reset_timestamps", "1")
            videoCodec = AV_CODEC_ID_H264
            pixelFormat = AV_PIX_FMT_YUV420P
            frameRate = FRAME_RATE.toDouble()
            gopSize = FRAME_RATE
            videoBitrate = 1_000_000
        }

        try {
            recorder.start()
            repeat(FRAME_RATE * 5) { index ->
                recorder.timestamp = index * 1_000_000L / FRAME_RATE
                recorder.record(converter.convert(image(index)))
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
            runCatching { converter.close() }
        }

        val segments = Files.list(directory).use { paths ->
            paths.filter { it.extension == "ts" }.toList()
        }
        assertTrue(segments.size in 2..MAX_SEGMENTS)
        segments.forEach { segment ->
            val grabber = FFmpegFrameGrabber(segment.toFile())
            try {
                grabber.start()
                assertNotNull(grabber.grabImage())
            } finally {
                runCatching { grabber.stop() }
                runCatching { grabber.release() }
            }
        }
    }

    private fun image(index: Int): BufferedImage =
        BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = Color((index * 17) and 0xff, 80, 160)
                graphics.fillRect(0, 0, WIDTH, HEIGHT)
            } finally {
                graphics.dispose()
            }
        }
}

private const val WIDTH = 64
private const val HEIGHT = 48
private const val FRAME_RATE = 10
private const val MAX_SEGMENTS = 3
