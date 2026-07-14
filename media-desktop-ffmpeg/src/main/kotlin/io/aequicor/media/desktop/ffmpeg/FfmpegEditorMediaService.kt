package io.aequicor.media.desktop.ffmpeg

import io.aequicor.editor.ClipEffects
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorClipId
import io.aequicor.editor.EditorExportRequest
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTextAlignment
import io.aequicor.editor.EditorTrack
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.FrameImageFormat
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.editor.JpegCompression
import io.aequicor.editor.MediaAsset
import io.aequicor.editor.MediaAssetKind
import io.aequicor.editor.Transition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Metadata returned by probing one editor media file. */
data class EditorMediaProbe(
    val kind: MediaAssetKind,
    val durationMicros: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val hasAudio: Boolean,
)

/** BGRA preview frame rendered from the final editor composition. */
data class EditorPreviewFrame(
    val width: Int,
    val height: Int,
    val bgraPixels: ByteArray,
)

/** Interleaved floating-point PCM used for synchronized desktop preview. */
data class EditorPreviewAudio(
    val sampleRate: Int,
    val channels: Int,
    val samples: FloatArray,
)

/** Supplies ordered preview frames and allows a media backend to reuse decoder state. */
interface EditorPreviewSession {
    suspend fun render(timelineMicros: Long): EditorPreviewFrame

    suspend fun close()
}

/** Progress of a long-running editor export. */
data class EditorExportProgress(
    val completedUnits: Long,
    val totalUnits: Long,
) {
    val fraction: Float
        get() = if (totalUnits <= 0L) 0f else (completedUnits.toDouble() / totalUnits).toFloat().coerceIn(0f, 1f)
}

/** One separately rendered editor frame and its position on the project timeline. */
data class EditorExportedFrame(
    val timelineMicros: Long,
    val outputPath: String,
)

/** Completed editor export. */
data class EditorExportResult(
    val outputPath: String,
    val renderedFrames: Int,
    /** Files produced by the export; a video or contact sheet contains one entry. */
    val outputPaths: List<String> = listOf(outputPath),
    /** Timeline-aware outputs for a separate-files frame export. */
    val exportedFrames: List<EditorExportedFrame> = emptyList(),
)

/** Failure raised for invalid media or editor export settings. */
class EditorMediaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Desktop media operations required by the video editor. */
interface EditorMediaService {
    suspend fun probe(path: String): EditorMediaProbe

    /** Finds timestamps embedded by important-frame actions during recording. */
    suspend fun findImportantFrameTimestamps(path: String): List<Long> = emptyList()

    /** Selects regular storyboard timestamps from the final edited composition. */
    suspend fun createStoryboardFrameTimestamps(
        project: EditorProject,
        intervalMicros: Long = 1_000_000L,
        maxFrames: Int = 120,
    ): List<Long> {
        require(intervalMicros > 0L) { "Storyboard interval must be positive." }
        require(maxFrames > 0) { "Storyboard frame limit must be positive." }
        val result = mutableListOf<Long>()
        var timestamp = 0L
        while (timestamp < project.durationMicros && result.size < maxFrames) {
            result += timestamp
            timestamp += intervalMicros
        }
        return result
    }

    suspend fun renderPreview(
        project: EditorProject,
        timelineMicros: Long,
        maxWidth: Int = 1920,
        maxHeight: Int = 1080,
    ): EditorPreviewFrame

    /** Opens an ordered preview session. Call [EditorPreviewSession.close] after playback stops. */
    suspend fun createPreviewSession(
        project: EditorProject,
        maxWidth: Int = 1920,
        maxHeight: Int = 1080,
    ): EditorPreviewSession {
        require(maxWidth > 0 && maxHeight > 0) { "Preview bounds must be positive." }
        val service = this
        return object : EditorPreviewSession {
            override suspend fun render(timelineMicros: Long): EditorPreviewFrame =
                service.renderPreview(project, timelineMicros, maxWidth, maxHeight)

            override suspend fun close() = Unit
        }
    }

    suspend fun renderPreviewAudio(project: EditorProject): EditorPreviewAudio

    suspend fun export(
        request: EditorExportRequest,
        onProgress: (EditorExportProgress) -> Unit = {},
    ): EditorExportResult
}

/** JavaCV/FFmpeg implementation used by the desktop editor. */
class FfmpegEditorMediaService(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EditorMediaService {
    override suspend fun probe(path: String): EditorMediaProbe = withContext(dispatcher) {
        val input = normalizedExistingFile(path)
        if (input.extension.lowercase() in IMAGE_EXTENSIONS) {
            val image = ImageIO.read(input.toFile())
                ?: throw EditorMediaException("Unsupported image: $input")
            return@withContext EditorMediaProbe(
                kind = MediaAssetKind.Image,
                durationMicros = 0L,
                width = image.width,
                height = image.height,
                frameRate = 0.0,
                hasAudio = false,
            )
        }
        val grabber = FFmpegFrameGrabber(input.toFile())
        try {
            grabber.start()
            val hasVideo = grabber.imageWidth > 0 && grabber.imageHeight > 0
            val hasAudio = grabber.audioChannels > 0
            if (!hasVideo && !hasAudio) {
                throw EditorMediaException("The file does not contain decodable video or audio: $input")
            }
            EditorMediaProbe(
                kind = if (hasVideo) MediaAssetKind.Video else MediaAssetKind.Audio,
                durationMicros = grabber.lengthInTime.coerceAtLeast(0L),
                width = grabber.imageWidth.coerceAtLeast(0),
                height = grabber.imageHeight.coerceAtLeast(0),
                frameRate = grabber.frameRate.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                hasAudio = hasAudio,
            )
        } catch (failure: EditorMediaException) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw EditorMediaException("FFmpeg could not read $input", failure)
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    override suspend fun findImportantFrameTimestamps(path: String): List<Long> = withContext(dispatcher) {
        val input = normalizedExistingFile(path)
        if (input.extension.lowercase() in IMAGE_EXTENSIONS) return@withContext emptyList()
        val grabber = FFmpegFrameGrabber(input.toFile()).apply {
            pixelFormat = AV_PIX_FMT_BGRA
        }
        val timestamps = linkedSetOf<Long>()
        try {
            grabber.start()
            while (true) {
                coroutineContext.ensureActive()
                val frame = grabber.grabImage() ?: break
                if (frame.hasInputEventMarker()) {
                    timestamps += grabber.timestamp.coerceAtLeast(0L)
                }
            }
            timestamps.toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw EditorMediaException("FFmpeg could not inspect important frames in $input", failure)
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    override suspend fun createStoryboardFrameTimestamps(
        project: EditorProject,
        intervalMicros: Long,
        maxFrames: Int,
    ): List<Long> = withContext(dispatcher) {
        require(intervalMicros > 0L) { "Storyboard interval must be positive." }
        require(maxFrames > 0) { "Storyboard frame limit must be positive." }
        val renderer = ProjectFrameRenderer(project)
        val deduplicator = StoryboardFrameDeduplicator()
        val timestamps = mutableListOf<Long>()
        var timestamp = 0L
        var inspectedFrames = 0
        try {
            while (timestamp < project.durationMicros && inspectedFrames < maxFrames) {
                coroutineContext.ensureActive()
                if (deduplicator.shouldRetain(renderer.render(timestamp))) {
                    timestamps += timestamp
                }
                inspectedFrames += 1
                timestamp += intervalMicros
            }
            timestamps
        } finally {
            renderer.close()
        }
    }

    override suspend fun renderPreview(
        project: EditorProject,
        timelineMicros: Long,
        maxWidth: Int,
        maxHeight: Int,
    ): EditorPreviewFrame = withContext(dispatcher) {
        require(maxWidth > 0 && maxHeight > 0) { "Preview bounds must be positive." }
        val renderer = ProjectFrameRenderer(project)
        try {
            renderer.renderPreviewFrame(timelineMicros, maxWidth, maxHeight)
        } finally {
            renderer.close()
        }
    }

    override suspend fun createPreviewSession(
        project: EditorProject,
        maxWidth: Int,
        maxHeight: Int,
    ): EditorPreviewSession {
        require(maxWidth > 0 && maxHeight > 0) { "Preview bounds must be positive." }
        return FfmpegEditorPreviewSession(project, maxWidth, maxHeight, dispatcher)
    }

    override suspend fun renderPreviewAudio(project: EditorProject): EditorPreviewAudio = withContext(dispatcher) {
        val clips = decodeAudioClips(project)
        val totalFrames = (project.durationMicros * EDITOR_AUDIO_SAMPLE_RATE / MICROS_PER_SECOND)
            .coerceAtMost(Int.MAX_VALUE.toLong() / EDITOR_AUDIO_CHANNELS)
        EditorPreviewAudio(
            sampleRate = EDITOR_AUDIO_SAMPLE_RATE,
            channels = EDITOR_AUDIO_CHANNELS,
            samples = mixAudio(clips, 0L, totalFrames),
        )
    }

    override suspend fun export(
        request: EditorExportRequest,
        onProgress: (EditorExportProgress) -> Unit,
    ): EditorExportResult = withContext(dispatcher) {
        require(request.project.durationMicros > 0L) { "Editor project has no timeline content." }
        when (request) {
            is EditorExportRequest.Video -> exportVideo(request, onProgress)
            is EditorExportRequest.Frames -> exportImportantFrames(request, onProgress)
        }
    }

    private suspend fun exportVideo(
        request: EditorExportRequest.Video,
        onProgress: (EditorExportProgress) -> Unit,
    ): EditorExportResult {
        require(request.width > 0 && request.height > 0) { "Export dimensions must be positive." }
        require(request.width % 2 == 0 && request.height % 2 == 0) { "H.264 export dimensions must be even." }
        require(request.frameRate > 0) { "Export frame rate must be positive." }
        val target = normalizedTarget(request.outputPath)
        validateTarget(target, request.overwrite)
        target.parent?.createDirectories()
        val temporary = temporarySibling(target)
        val project = request.project.copy(
            canvasWidth = request.width,
            canvasHeight = request.height,
            frameRate = request.frameRate,
        )
        val renderer = ProjectFrameRenderer(project)
        val audioClips = decodeAudioClips(project)
        val hasAudio = audioClips.isNotEmpty()
        val recorder = FFmpegFrameRecorder(temporary.toFile(), request.width, request.height, if (hasAudio) 2 else 0).apply {
            format = "mp4"
            videoCodec = AV_CODEC_ID_H264
            pixelFormat = AV_PIX_FMT_YUV420P
            frameRate = request.frameRate.toDouble()
            videoBitrate = DEFAULT_EDITOR_VIDEO_BITRATE
            if (hasAudio) {
                audioCodec = AV_CODEC_ID_AAC
                audioBitrate = DEFAULT_EDITOR_AUDIO_BITRATE
                sampleRate = EDITOR_AUDIO_SAMPLE_RATE
                audioChannels = EDITOR_AUDIO_CHANNELS
            }
        }
        val converter = Java2DFrameConverter()
        val totalFrames = max(1L, project.durationMicros * request.frameRate / MICROS_PER_SECOND)
        var renderedFrames = 0
        var writtenAudioSamples = 0L
        try {
            recorder.start()
            repeat(totalFrames.toInt()) { index ->
                coroutineContext.ensureActive()
                val timestampMicros = index * MICROS_PER_SECOND / request.frameRate
                recorder.timestamp = timestampMicros
                recorder.record(converter.convert(renderer.render(timestampMicros)))
                renderedFrames += 1
                if (hasAudio) {
                    val audioEndSample = min(
                        project.durationMicros * EDITOR_AUDIO_SAMPLE_RATE / MICROS_PER_SECOND,
                        (index + 1L) * EDITOR_AUDIO_SAMPLE_RATE / request.frameRate,
                    )
                    if (audioEndSample > writtenAudioSamples) {
                        val mixed = mixAudio(audioClips, writtenAudioSamples, audioEndSample)
                        recorder.recordSamples(
                            EDITOR_AUDIO_SAMPLE_RATE,
                            EDITOR_AUDIO_CHANNELS,
                            FloatBuffer.wrap(mixed),
                        )
                        writtenAudioSamples = audioEndSample
                    }
                }
                onProgress(EditorExportProgress(renderedFrames.toLong(), totalFrames))
            }
            recorder.stop()
            commitOutput(temporary, target, request.overwrite)
            return EditorExportResult(target.toString(), renderedFrames)
        } catch (cancelled: CancellationException) {
            runCatching { recorder.stop() }
            Files.deleteIfExists(temporary)
            throw cancelled
        } catch (failure: Exception) {
            runCatching { recorder.stop() }
            Files.deleteIfExists(temporary)
            throw failure.asEditorMediaException("Video export failed.")
        } finally {
            runCatching { recorder.release() }
            converter.close()
            renderer.close()
        }
    }

    private suspend fun exportImportantFrames(
        request: EditorExportRequest.Frames,
        onProgress: (EditorExportProgress) -> Unit,
    ): EditorExportResult {
        val timestamps = request.timestampsMicros
            .filter { it in 0L..request.project.durationMicros }
            .distinct()
            .sorted()
        require(timestamps.isNotEmpty()) { "Select at least one available frame." }
        val target = normalizedTarget(request.outputPath)
        validateTarget(target, request.overwrite)
        target.parent?.createDirectories()
        val temporary = temporarySibling(target)
        val renderer = ProjectFrameRenderer(request.project)
        val outputWidth = (request.project.canvasWidth.toDouble() * request.resolutionPercent / 100.0)
            .roundToInt()
            .coerceAtLeast(1)
        val outputHeight = (request.project.canvasHeight.toDouble() * request.resolutionPercent / 100.0)
            .roundToInt()
            .coerceAtLeast(1)
        val extension = request.outputFormat.fileExtension
        try {
            val outputs = when (request.layout) {
                ImportantFrameLayout.SeparatePngFiles -> {
                    Files.createDirectory(temporary)
                    val exportedFrames = timestamps.mapIndexed { index, timestamp ->
                        coroutineContext.ensureActive()
                        val image = renderer.render(timestamp, outputWidth, outputHeight).withTimecode(timestamp)
                        val name = "frame-${(index + 1).toString().padStart(6, '0')}.$extension"
                        writeFrameImage(
                            image = image,
                            output = temporary.resolve(name),
                            format = request.outputFormat,
                            jpegCompression = request.jpegCompression,
                        )
                        onProgress(EditorExportProgress(index + 1L, timestamps.size.toLong()))
                        EditorExportedFrame(
                            timelineMicros = timestamp,
                            outputPath = target.resolve(name).toString(),
                        )
                    }
                    FrameExportOutputs(
                        outputPaths = exportedFrames.map(EditorExportedFrame::outputPath),
                        exportedFrames = exportedFrames,
                    )
                }
                ImportantFrameLayout.ContactSheet -> {
                    val frames = timestamps.mapIndexed { index, timestamp ->
                        coroutineContext.ensureActive()
                        renderer.render(timestamp, outputWidth, outputHeight).withTimecode(timestamp).also {
                            onProgress(EditorExportProgress(index + 1L, timestamps.size.toLong()))
                        }
                    }
                    writeContactSheet(
                        frames = frames,
                        output = temporary,
                        format = request.outputFormat,
                        jpegCompression = request.jpegCompression,
                    )
                    FrameExportOutputs(outputPaths = listOf(target.toString()))
                }
            }
            commitOutput(temporary, target, request.overwrite)
            return EditorExportResult(
                outputPath = target.toString(),
                renderedFrames = timestamps.size,
                outputPaths = outputs.outputPaths,
                exportedFrames = outputs.exportedFrames,
            )
        } catch (cancelled: CancellationException) {
            deleteRecursively(temporary)
            throw cancelled
        } catch (failure: Exception) {
            deleteRecursively(temporary)
            throw failure.asEditorMediaException("Important-frame export failed.")
        } finally {
            renderer.close()
        }
    }
}

private data class FrameExportOutputs(
    val outputPaths: List<String>,
    val exportedFrames: List<EditorExportedFrame> = emptyList(),
)

private class FfmpegEditorPreviewSession(
    project: EditorProject,
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val dispatcher: CoroutineDispatcher,
) : EditorPreviewSession {
    private val renderer = ProjectFrameRenderer(project)

    override suspend fun render(timelineMicros: Long): EditorPreviewFrame = withContext(dispatcher) {
        renderer.renderPreviewFrame(timelineMicros, maxWidth, maxHeight)
    }

    override suspend fun close(): Unit = withContext(NonCancellable + dispatcher) {
        renderer.close()
    }
}

private class ProjectFrameRenderer(private val project: EditorProject) : AutoCloseable {
    private val videoSources = mutableMapOf<EditorClipId, VideoFrameCursor>()
    private val images = mutableMapOf<String, BufferedImage>()
    private val mouseTrails = mutableMapOf<String, MouseTrail>()
    private val assetsWithoutMouseTrail = mutableSetOf<String>()
    private var previewCanvas: BufferedImage? = null

    fun render(
        timelineMicros: Long,
        outputWidth: Int = project.canvasWidth,
        outputHeight: Int = project.canvasHeight,
    ): BufferedImage = renderInto(
        canvas = BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB),
        timelineMicros = timelineMicros,
    )

    private fun renderInto(canvas: BufferedImage, timelineMicros: Long): BufferedImage {
        val outputWidth = canvas.width
        val outputHeight = canvas.height
        val graphics = canvas.createGraphics()
        try {
            graphics.scale(
                outputWidth.toDouble() / project.canvasWidth,
                outputHeight.toDouble() / project.canvasHeight,
            )
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, project.canvasWidth, project.canvasHeight)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            project.tracks.forEach { track ->
                if (!track.visible) return@forEach
                when (track.kind) {
                    EditorTrackKind.Video -> renderVideoTrack(graphics, track, timelineMicros)
                    EditorTrackKind.Text -> renderTextTrack(graphics, track, timelineMicros)
                    EditorTrackKind.Audio -> Unit
                }
            }
        } finally {
            graphics.dispose()
        }
        return canvas
    }

    fun renderPreviewFrame(
        timelineMicros: Long,
        maxWidth: Int,
        maxHeight: Int,
    ): EditorPreviewFrame {
        val scale = min(
            maxWidth.toDouble() / project.canvasWidth,
            maxHeight.toDouble() / project.canvasHeight,
        ).coerceAtMost(1.0)
        val outputWidth = (project.canvasWidth * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (project.canvasHeight * scale).roundToInt().coerceAtLeast(1)
        val canvas = previewCanvas
            ?.takeIf { it.width == outputWidth && it.height == outputHeight }
            ?: BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB).also {
                previewCanvas = it
            }
        return renderInto(canvas, timelineMicros.coerceIn(0L, project.durationMicros)).toBgraFrame()
    }

    private fun renderVideoTrack(graphics: Graphics2D, track: EditorTrack, timelineMicros: Long) {
        val active = track.clips.filter { timelineMicros in it.timelineStartMicros until it.timelineEndMicros }
        active.forEach { clip ->
            val media = clip as? EditorClip.Media ?: return@forEach
            val asset = project.assets.firstOrNull { it.id == media.assetId && it.available } ?: return@forEach
            val sourceMicros = media.sourceStartMicros +
                ((timelineMicros - media.timelineStartMicros) * media.speed).toLong()
            val source = sourceImage(media.id, asset, sourceMicros) ?: return@forEach
            val sourceWithMouseTrail = source.withMouseTrail(
                points = mouseTrailPointsFor(media, asset, timelineMicros, sourceMicros),
            )
            val transformed = sourceWithMouseTrail.applyEffects(media.effects).crop(media.transform.crop)
            val targetWidth = max(1, (transformed.width * media.transform.scale).roundToInt())
            val targetHeight = max(1, (transformed.height * media.transform.scale).roundToInt())
            val x = (media.transform.positionX * project.canvasWidth - targetWidth / 2f).roundToInt()
            val y = (media.transform.positionY * project.canvasHeight - targetHeight / 2f).roundToInt()
            val alpha = (media.transform.opacity * clipOpacity(track, media, timelineMicros)).coerceIn(0f, 1f)
            val previousComposite = graphics.composite
            graphics.composite = AlphaComposite.SrcOver.derive(alpha)
            graphics.drawImage(transformed, x, y, targetWidth, targetHeight, null)
            graphics.composite = previousComposite
        }
    }

    private fun mouseTrailPointsFor(
        clip: EditorClip.Media,
        asset: MediaAsset,
        timelineMicros: Long,
        sourceMicros: Long,
    ): List<RecordedMousePoint> {
        if (project.importantFrames.none { marker -> marker.timelineMicros == timelineMicros }) return emptyList()
        val previousMarkerMicros = project.importantFrames
            .lastOrNull { marker -> marker.timelineMicros < timelineMicros }
            ?.timelineMicros
            ?: clip.timelineStartMicros
        val segmentStartMicros = max(previousMarkerMicros, clip.timelineStartMicros)
        val segmentStartSourceMicros = clip.sourceStartMicros +
            ((segmentStartMicros - clip.timelineStartMicros) * clip.speed).toLong()
        return mouseTrailFor(asset)?.pointsBetween(segmentStartSourceMicros, sourceMicros).orEmpty()
    }

    private fun mouseTrailFor(asset: MediaAsset): MouseTrail? {
        mouseTrails[asset.path]?.let { return it }
        if (asset.path in assetsWithoutMouseTrail) return null
        return MouseTrail.load(asset.path)?.also { trail ->
            mouseTrails[asset.path] = trail
        } ?: run {
            assetsWithoutMouseTrail += asset.path
            null
        }
    }

    private fun renderTextTrack(graphics: Graphics2D, track: EditorTrack, timelineMicros: Long) {
        track.clips.filterIsInstance<EditorClip.Text>()
            .filter { timelineMicros in it.timelineStartMicros until it.timelineEndMicros }
            .forEach { clip ->
                val alpha = ((clip.colorArgb ushr 24 and 0xff) / 255f * clip.opacity).coerceIn(0f, 1f)
                val previousComposite = graphics.composite
                graphics.composite = AlphaComposite.SrcOver.derive(alpha)
                graphics.color = Color((clip.colorArgb and 0x00FFFFFF).toInt())
                graphics.font = Font(Font.SANS_SERIF, Font.BOLD, clip.fontSize.roundToInt())
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val width = graphics.fontMetrics.stringWidth(clip.text)
                val anchorX = (clip.positionX * project.canvasWidth).roundToInt()
                val x = when (clip.alignment) {
                    EditorTextAlignment.Start -> anchorX
                    EditorTextAlignment.Center -> anchorX - width / 2
                    EditorTextAlignment.End -> anchorX - width
                }
                val y = (clip.positionY * project.canvasHeight).roundToInt()
                graphics.drawString(clip.text, x, y)
                graphics.composite = previousComposite
            }
    }

    private fun sourceImage(clipId: EditorClipId, asset: MediaAsset, sourceMicros: Long): BufferedImage? {
        if (asset.kind == MediaAssetKind.Image) {
            return images.getOrPut(asset.path) {
                ImageIO.read(Path.of(asset.path).toFile()) ?: error("Unsupported image: ${asset.path}")
            }
        }
        val source = videoSources.getOrPut(clipId) {
            VideoFrameCursor(asset.path)
        }
        return source.imageAt(sourceMicros.coerceAtMost(asset.durationMicros))
    }

    override fun close() {
        videoSources.values.forEach(VideoFrameCursor::close)
    }
}

private class VideoFrameCursor(
    path: String,
) : AutoCloseable {
    private val converter = Java2DFrameConverter()
    private val grabber = FFmpegFrameGrabber(Path.of(path).toFile()).apply { start() }
    private var lastRequestedMicros: Long? = null
    private var frameTimestampMicros = Long.MIN_VALUE
    private var frame: BufferedImage? = null

    fun imageAt(timestampMicros: Long): BufferedImage? {
        val target = timestampMicros.coerceAtLeast(0L)
        val previousRequest = lastRequestedMicros
        if (
            previousRequest == null ||
            target < previousRequest ||
            target - previousRequest > MAX_SEQUENTIAL_PREVIEW_GAP_MICROS
        ) {
            grabber.timestamp = target
            frameTimestampMicros = Long.MIN_VALUE
            frame = null
        }
        lastRequestedMicros = target
        if (frame == null) grabNextFrame()
        while (frameTimestampMicros < target) {
            val previousTimestamp = frameTimestampMicros
            if (grabNextFrame() == null || frameTimestampMicros <= previousTimestamp) break
        }
        return frame
    }

    private fun grabNextFrame(): BufferedImage? {
        val decoded = grabber.grabImage() ?: return null
        val image = converter.convert(decoded) ?: return null
        frameTimestampMicros = max(decoded.timestamp, grabber.timestamp).coerceAtLeast(0L)
        frame = image
        return image
    }

    override fun close() {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
        converter.close()
    }
}

private data class DecodedAudioClip(
    val timelineStartSample: Long,
    val samples: FloatArray,
    val fadeInSamples: Int,
    val fadeOutSamples: Int,
)

private suspend fun decodeAudioClips(project: EditorProject): List<DecodedAudioClip> {
    val clips = mutableListOf<DecodedAudioClip>()
    project.tracks.filterNot { it.muted }.forEach { track ->
        if (track.kind == EditorTrackKind.Text) return@forEach
        track.clips.filterIsInstance<EditorClip.Media>().forEach { clip ->
            coroutineContext.ensureActive()
            val asset = project.assets.firstOrNull { it.id == clip.assetId && it.available && it.hasAudio }
                ?: return@forEach
            if (clip.effects.muted || clip.effects.volume <= 0f) return@forEach
            clips += decodeAudioClip(asset, clip)
        }
    }
    return clips
}

private fun decodeAudioClip(asset: MediaAsset, clip: EditorClip.Media): DecodedAudioClip {
    val grabber = FFmpegFrameGrabber(Path.of(asset.path).toFile()).apply {
        audioChannels = EDITOR_AUDIO_CHANNELS
        sampleRate = EDITOR_AUDIO_SAMPLE_RATE
        sampleFormat = AV_SAMPLE_FMT_FLT
    }
    val tempo = clip.speed.toString()
    val filter = FFmpegFrameFilter(
        "atempo=$tempo,volume=${clip.effects.volume},aformat=sample_fmts=flt:sample_rates=$EDITOR_AUDIO_SAMPLE_RATE:channel_layouts=stereo",
        EDITOR_AUDIO_CHANNELS,
    ).apply {
        sampleRate = EDITOR_AUDIO_SAMPLE_RATE
        sampleFormat = AV_SAMPLE_FMT_FLT
    }
    val samples = FloatArrayBuilder()
    val sourceEnd = clip.sourceStartMicros + (clip.durationMicros * clip.speed).toLong()
    try {
        grabber.start()
        filter.start()
        grabber.timestamp = clip.sourceStartMicros
        while (grabber.timestamp <= sourceEnd) {
            val frame = grabber.grabSamples() ?: break
            if (frame.timestamp > sourceEnd) break
            frame.timestamp = (frame.timestamp - clip.sourceStartMicros).coerceAtLeast(0L)
            filter.push(frame)
            pullFilteredSamples(filter, samples)
        }
        filter.push(null)
        pullFilteredSamples(filter, samples)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw failure.asEditorMediaException("Audio decode failed for ${asset.path}.")
    } finally {
        runCatching { filter.stop() }
        runCatching { filter.release() }
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }
    val expectedValues = (clip.durationMicros * EDITOR_AUDIO_SAMPLE_RATE / MICROS_PER_SECOND *
        EDITOR_AUDIO_CHANNELS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return DecodedAudioClip(
        timelineStartSample = clip.timelineStartMicros * EDITOR_AUDIO_SAMPLE_RATE / MICROS_PER_SECOND,
        samples = samples.toArray(expectedValues),
        fadeInSamples = (clip.effects.fadeInMicros * EDITOR_AUDIO_SAMPLE_RATE / MICROS_PER_SECOND).toInt(),
        fadeOutSamples = (clip.effects.fadeOutMicros * EDITOR_AUDIO_SAMPLE_RATE / MICROS_PER_SECOND).toInt(),
    )
}

private fun pullFilteredSamples(filter: FFmpegFrameFilter, output: FloatArrayBuilder) {
    while (true) {
        val filtered = filter.pullSamples() ?: return
        output.appendSamples(filtered.samples ?: return, filtered.audioChannels)
    }
}

private fun FloatArrayBuilder.appendSamples(buffers: Array<Buffer>, channels: Int) {
    if (buffers.size == 1) {
        val source = buffers[0] as? FloatBuffer ?: return
        val copy = source.duplicate()
        while (copy.hasRemaining()) append(copy.get())
        return
    }
    val planes = buffers.mapNotNull { (it as? FloatBuffer)?.duplicate() }
    if (planes.size != channels) return
    val count = planes.minOf(FloatBuffer::remaining)
    repeat(count) { planes.forEach { append(it.get()) } }
}

private fun mixAudio(clips: List<DecodedAudioClip>, startSample: Long, endSample: Long): FloatArray {
    val sampleFrames = (endSample - startSample).coerceAtLeast(0L).toInt()
    val mixed = FloatArray(sampleFrames * EDITOR_AUDIO_CHANNELS)
    clips.forEach { clip ->
        val localStart = startSample - clip.timelineStartSample
        repeat(sampleFrames) { frameIndex ->
            val clipFrame = localStart + frameIndex
            if (clipFrame < 0L) return@repeat
            val sourceIndex = clipFrame * EDITOR_AUDIO_CHANNELS
            if (sourceIndex + 1 >= clip.samples.size) return@repeat
            val totalFrames = clip.samples.size / EDITOR_AUDIO_CHANNELS
            val gain = fadeGain(clipFrame.toInt(), totalFrames, clip.fadeInSamples, clip.fadeOutSamples)
            repeat(EDITOR_AUDIO_CHANNELS) { channel ->
                val targetIndex = frameIndex * EDITOR_AUDIO_CHANNELS + channel
                mixed[targetIndex] = (mixed[targetIndex] + clip.samples[sourceIndex.toInt() + channel] * gain)
                    .coerceIn(-1f, 1f)
            }
        }
    }
    return mixed
}

private fun fadeGain(frame: Int, total: Int, fadeIn: Int, fadeOut: Int): Float {
    val fadeInGain = if (fadeIn > 0) (frame.toFloat() / fadeIn).coerceIn(0f, 1f) else 1f
    val remaining = total - frame
    val fadeOutGain = if (fadeOut > 0) (remaining.toFloat() / fadeOut).coerceIn(0f, 1f) else 1f
    return min(fadeInGain, fadeOutGain)
}

private class FloatArrayBuilder(initialCapacity: Int = 16_384) {
    private var values = FloatArray(initialCapacity)
    private var size = 0

    fun append(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun toArray(limit: Int): FloatArray = values.copyOf(min(size, limit))
}

private fun clipOpacity(track: EditorTrack, clip: EditorClip.Media, timelineMicros: Long): Float {
    val local = timelineMicros - clip.timelineStartMicros
    val fadeIn = if (clip.effects.fadeInMicros > 0L) {
        (local.toDouble() / clip.effects.fadeInMicros).toFloat().coerceIn(0f, 1f)
    } else 1f
    val remaining = clip.timelineEndMicros - timelineMicros
    val fadeOut = if (clip.effects.fadeOutMicros > 0L) {
        (remaining.toDouble() / clip.effects.fadeOutMicros).toFloat().coerceIn(0f, 1f)
    } else 1f
    val ordered = track.clips.filterIsInstance<EditorClip.Media>().sortedBy(EditorClip::timelineStartMicros)
    val index = ordered.indexOfFirst { it.id == clip.id }
    val transitionOut = (clip.transition as? Transition.Crossfade)?.let { transition ->
        val next = ordered.getOrNull(index + 1)
        if (next != null && timelineMicros >= next.timelineStartMicros) {
            ((clip.timelineEndMicros - timelineMicros).toDouble() /
                (clip.timelineEndMicros - next.timelineStartMicros).coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
        } else 1f
    } ?: 1f
    val previous = ordered.getOrNull(index - 1)
    val transitionIn = if (previous?.transition is Transition.Crossfade && timelineMicros < previous.timelineEndMicros) {
        ((timelineMicros - clip.timelineStartMicros).toDouble() /
            (previous.timelineEndMicros - clip.timelineStartMicros).coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
    } else 1f
    return min(min(fadeIn, fadeOut), min(transitionIn, transitionOut))
}

private fun BufferedImage.applyEffects(effects: ClipEffects): BufferedImage {
    if (effects.brightness == 0f && effects.contrast == 1f && effects.saturation == 1f) return this
    val output = deepCopy(BufferedImage.TYPE_INT_ARGB)
    val pixels = (output.raster.dataBuffer as? DataBufferInt)?.data ?: return output
    pixels.indices.forEach { index ->
        val value = pixels[index]
        val alpha = value ushr 24 and 0xff
        var red = value ushr 16 and 0xff
        var green = value ushr 8 and 0xff
        var blue = value and 0xff
        val luminance = (red * 0.2126f + green * 0.7152f + blue * 0.0722f)
        red = ((luminance + (red - luminance) * effects.saturation - 128f) * effects.contrast + 128f +
            effects.brightness * 255f).roundToInt().coerceIn(0, 255)
        green = ((luminance + (green - luminance) * effects.saturation - 128f) * effects.contrast + 128f +
            effects.brightness * 255f).roundToInt().coerceIn(0, 255)
        blue = ((luminance + (blue - luminance) * effects.saturation - 128f) * effects.contrast + 128f +
            effects.brightness * 255f).roundToInt().coerceIn(0, 255)
        pixels[index] = alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }
    return output
}

private fun BufferedImage.crop(crop: io.aequicor.editor.NormalizedCrop): BufferedImage {
    if (crop.left == 0f && crop.top == 0f && crop.right == 1f && crop.bottom == 1f) return this
    val x = (crop.left * width).roundToInt().coerceIn(0, width - 1)
    val y = (crop.top * height).roundToInt().coerceIn(0, height - 1)
    val right = (crop.right * width).roundToInt().coerceIn(x + 1, width)
    val bottom = (crop.bottom * height).roundToInt().coerceIn(y + 1, height)
    return getSubimage(x, y, right - x, bottom - y).deepCopy()
}

private fun BufferedImage.deepCopy(type: Int = BufferedImage.TYPE_INT_ARGB): BufferedImage {
    val output = BufferedImage(width, height, type)
    val graphics = output.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return output
}

private fun BufferedImage.withMouseTrail(points: List<RecordedMousePoint>): BufferedImage {
    if (points.size < 2) return this
    return deepCopy().also { image ->
        val graphics = image.createGraphics()
        try {
            graphics.drawMouseTrail(points)
        } finally {
            graphics.dispose()
        }
    }
}

private fun BufferedImage.toBgraFrame(): EditorPreviewFrame {
    val pixels = requireNotNull((raster.dataBuffer as? DataBufferInt)?.data) {
        "Editor preview requires an integer ARGB canvas."
    }
    val pixelCount = width * height
    val bgra = ByteArray(pixelCount * 4)
    ByteBuffer.wrap(bgra)
        .order(ByteOrder.LITTLE_ENDIAN)
        .asIntBuffer()
        .put(pixels, 0, pixelCount)
    return EditorPreviewFrame(width, height, bgra)
}

private fun BufferedImage.withTimecode(timestampMicros: Long): BufferedImage {
    val fontSize = (height * 0.04).roundToInt().coerceIn(12, 48)
    val padding = max(4, fontSize / 3)
    val font = Font(Font.MONOSPACED, Font.BOLD, fontSize)
    val metrics = createGraphics().let { graphics ->
        try { graphics.getFontMetrics(font) } finally { graphics.dispose() }
    }
    val stripHeight = metrics.height + padding * 2
    val output = BufferedImage(width, height + stripHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
        graphics.color = Color(24, 27, 31)
        graphics.fillRect(0, height, width, stripHeight)
        graphics.font = font
        graphics.color = Color.WHITE
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val text = formatTimecode(timestampMicros)
        graphics.drawString(text, ((width - metrics.stringWidth(text)) / 2).coerceAtLeast(0), height + padding + metrics.ascent)
    } finally {
        graphics.dispose()
    }
    return output
}

private fun writeContactSheet(
    frames: List<BufferedImage>,
    output: Path,
    format: FrameImageFormat,
    jpegCompression: JpegCompression,
) {
    val width = frames.maxOf(BufferedImage::getWidth)
    val height = frames.sumOf(BufferedImage::getHeight) + (frames.size - 1) * CONTACT_SHEET_GAP
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color(24, 27, 31)
        graphics.fillRect(0, 0, width, height)
        var y = 0
        frames.forEach { frame ->
            graphics.drawImage(frame, 0, y, null)
            y += frame.height + CONTACT_SHEET_GAP
        }
    } finally {
        graphics.dispose()
    }
    writeFrameImage(image, output, format, jpegCompression)
}

private fun writeFrameImage(
    image: BufferedImage,
    output: Path,
    format: FrameImageFormat,
    jpegCompression: JpegCompression,
) {
    when (format) {
        FrameImageFormat.Png -> require(ImageIO.write(image, "png", output.toFile())) {
            "PNG writer is not available."
        }
        FrameImageFormat.Jpeg -> writeJpeg(image, output, jpegCompression)
    }
}

private fun writeJpeg(image: BufferedImage, output: Path, compression: JpegCompression) {
    val writers = ImageIO.getImageWritersByFormatName("jpeg")
    require(writers.hasNext()) { "JPEG writer is not available." }
    val writer = writers.next()
    try {
        ImageIO.createImageOutputStream(output.toFile()).use { stream ->
            requireNotNull(stream) { "JPEG output stream could not be created." }
            writer.output = stream
            val parameters = writer.defaultWriteParam
            if (parameters.canWriteCompressed()) {
                parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameters.compressionQuality = compression.imageIoQuality
            }
            writer.write(null, IIOImage(image, null, null), parameters)
        }
    } finally {
        writer.dispose()
    }
}

private val JpegCompression.imageIoQuality: Float
    get() = when (this) {
        JpegCompression.None -> 1f
        JpegCompression.Low -> 0.9f
        JpegCompression.Medium -> 0.75f
        JpegCompression.High -> 0.5f
    }

private val FrameImageFormat.fileExtension: String
    get() = when (this) {
        FrameImageFormat.Png -> "png"
        FrameImageFormat.Jpeg -> "jpg"
    }

private fun formatTimecode(timestampMicros: Long): String {
    val totalMilliseconds = timestampMicros.coerceAtLeast(0L) / 1_000L
    val milliseconds = totalMilliseconds % 1_000L
    val totalSeconds = totalMilliseconds / 1_000L
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    val minutes = totalMinutes % 60L
    val hours = totalMinutes / 60L
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:" +
        "${seconds.toString().padStart(2, '0')}.${milliseconds.toString().padStart(3, '0')}"
}

private fun normalizedExistingFile(value: String): Path {
    val path = runCatching { Path.of(value).toAbsolutePath().normalize() }
        .getOrElse { throw EditorMediaException("Invalid media path: $value", it) }
    if (!path.exists() || path.isDirectory()) throw EditorMediaException("Media file does not exist: $path")
    return path
}

private fun normalizedTarget(value: String): Path = runCatching { Path.of(value).toAbsolutePath().normalize() }
    .getOrElse { throw EditorMediaException("Invalid export path: $value", it) }

private fun validateTarget(path: Path, overwrite: Boolean) {
    if (path.exists() && !overwrite) throw EditorMediaException("Export output already exists: $path")
    require(path.fileName != null) { "Export output must have a file or directory name." }
}

private fun temporarySibling(target: Path): Path = target.resolveSibling(".${target.name}.tmp-${System.nanoTime()}")

private fun backupSibling(target: Path): Path = target.resolveSibling(".${target.name}.backup-${System.nanoTime()}")

private fun commitOutput(source: Path, target: Path, overwrite: Boolean) {
    val backup = if (target.exists() && overwrite) backupSibling(target) else null
    try {
        if (backup != null) moveOutput(target, backup)
        moveOutput(source, target)
        backup?.let(::deleteRecursively)
    } catch (failure: Exception) {
        if (backup != null && backup.exists() && !target.exists()) runCatching { moveOutput(backup, target) }
        throw failure
    }
}

private fun moveOutput(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

private fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    if (!path.isDirectory()) {
        Files.deleteIfExists(path)
        return
    }
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

private fun Throwable.asEditorMediaException(fallback: String): EditorMediaException =
    this as? EditorMediaException ?: EditorMediaException(message ?: fallback, this)

private const val MICROS_PER_SECOND = 1_000_000L
private const val EDITOR_AUDIO_SAMPLE_RATE = 48_000
private const val EDITOR_AUDIO_CHANNELS = 2
private const val DEFAULT_EDITOR_VIDEO_BITRATE = 24_000_000
private const val DEFAULT_EDITOR_AUDIO_BITRATE = 192_000
private const val CONTACT_SHEET_GAP = 8
private const val MAX_SEQUENTIAL_PREVIEW_GAP_MICROS = 250_000L
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif")
