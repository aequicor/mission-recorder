package io.aequicor.media.desktop.ffmpeg

import io.aequicor.editor.ClipTransform
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorClipId
import io.aequicor.editor.EditorExportRequest
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTrack
import io.aequicor.editor.EditorTrackId
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.FrameImageFormat
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.editor.ImportantFrameMarker
import io.aequicor.editor.JpegCompression
import io.aequicor.editor.MediaAsset
import io.aequicor.editor.MediaAssetId
import io.aequicor.editor.MediaAssetKind
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FfmpegEditorMediaServiceTest {
    @Test
    fun createsDefaultStoryboardAndRemovesVisuallyIdenticalFrames() = runTest {
        val directory = Files.createTempDirectory("mission-editor-storyboard")
        val image = directory.resolve("source.png").also { writeColor(it.toFile(), Color.GREEN) }
        val project = layeredProject(image.toString(), image.toString())
        val service = FfmpegEditorMediaService(StandardTestDispatcher(testScheduler))

        val timestamps = service.createStoryboardFrameTimestamps(
            project = project,
            intervalMicros = 250_000L,
            maxFrames = 10,
        )

        assertEquals(listOf(0L), timestamps)
    }

    @Test
    fun compositesHigherVideoTrackAndExportsExactImportantFrames() = runTest {
        val directory = Files.createTempDirectory("mission-editor-frames")
        val red = directory.resolve("red.png").also { writeColor(it.toFile(), Color.RED) }
        val blue = directory.resolve("blue.png").also { writeColor(it.toFile(), Color.BLUE) }
        val project = layeredProject(red.toString(), blue.toString())
        val service = FfmpegEditorMediaService(StandardTestDispatcher(testScheduler))

        val preview = service.renderPreview(project, 500_000, maxWidth = 64, maxHeight = 64)
        val center = (preview.height / 2 * preview.width + preview.width / 2) * 4
        assertTrue((preview.bgraPixels[center].toInt() and 0xff) > 200)
        assertTrue((preview.bgraPixels[center + 2].toInt() and 0xff) < 50)

        val output = directory.resolve("important")
        val result = service.export(
            EditorExportRequest.Frames(
                project = project,
                outputPath = output.toString(),
                layout = ImportantFrameLayout.SeparatePngFiles,
            ),
        )
        assertEquals(2, result.renderedFrames)
        assertEquals(2, Files.list(output).use { it.count() })
    }

    @Test
    fun exportsSeparateJpegFramesAtSelectedResolution() = runTest {
        val directory = Files.createTempDirectory("mission-editor-jpeg-frames")
        val image = directory.resolve("source.png").also { writeColor(it.toFile(), Color.GREEN) }
        val project = layeredProject(image.toString(), image.toString())
        val output = directory.resolve("jpeg-frames")
        val service = FfmpegEditorMediaService(StandardTestDispatcher(testScheduler))

        val result = service.export(
            EditorExportRequest.Frames(
                project = project,
                outputPath = output.toString(),
                layout = ImportantFrameLayout.SeparatePngFiles,
                outputFormat = FrameImageFormat.Jpeg,
                resolutionPercent = 50,
                timestampsMicros = listOf(0L),
            ),
        )

        val exported = output.resolve("frame-000001.jpg")
        assertEquals(listOf(exported.toString()), result.outputPaths)
        assertEquals(
            listOf(EditorExportedFrame(timelineMicros = 0L, outputPath = exported.toString())),
            result.exportedFrames,
        )
        assertEquals(32, assertNotNull(ImageIO.read(exported.toFile())).width)
    }

    @Test
    fun writesDecodableJpegForEveryCompressionLevel() = runTest {
        val directory = Files.createTempDirectory("mission-editor-jpeg-compression")
        val image = directory.resolve("source.png").also { writeColor(it.toFile(), Color.GREEN) }
        val project = layeredProject(image.toString(), image.toString())
        val service = FfmpegEditorMediaService(StandardTestDispatcher(testScheduler))
        val outputSizes = mutableMapOf<JpegCompression, Long>()

        JpegCompression.entries.forEach { compression ->
            val output = directory.resolve(compression.name.lowercase())
            service.export(
                EditorExportRequest.Frames(
                    project = project,
                    outputPath = output.toString(),
                    layout = ImportantFrameLayout.SeparatePngFiles,
                    outputFormat = FrameImageFormat.Jpeg,
                    jpegCompression = compression,
                    timestampsMicros = listOf(0L),
                ),
            )
            val exported = output.resolve("frame-000001.jpg")
            assertNotNull(ImageIO.read(exported.toFile()))
            outputSizes[compression] = Files.size(exported)
        }
        assertTrue(
            requireNotNull(outputSizes[JpegCompression.High]) < requireNotNull(outputSizes[JpegCompression.None]),
        )
    }

    @Test
    fun exportsPlayableAtomicMp4AndRequiresExplicitOverwrite() = runTest {
        val directory = Files.createTempDirectory("mission-editor-video")
        val image = directory.resolve("source.png").also { writeColor(it.toFile(), Color.GREEN) }
        val project = layeredProject(image.toString(), image.toString()).copy(
            tracks = layeredProject(image.toString(), image.toString()).tracks.take(1).map { track ->
                track.copy(clips = track.clips.map { clip ->
                    (clip as EditorClip.Media).copy(durationMicros = 250_000)
                })
            },
            importantFrames = emptyList(),
        )
        val output = directory.resolve("edited.mp4")
        val service = FfmpegEditorMediaService(StandardTestDispatcher(testScheduler))

        val result = service.export(EditorExportRequest.Video(project, output.toString(), width = 64, height = 64, frameRate = 24))
        assertTrue(output.exists())
        assertTrue(result.renderedFrames >= 1)
        val grabber = FFmpegFrameGrabber(output.toFile())
        try {
            grabber.start()
            assertNotNull(grabber.grabImage())
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }

        val videoAssetId = assertNotNull(project.primaryAssetId)
        val previewProject = project.copy(
            assets = listOf(
                MediaAsset(
                    id = videoAssetId,
                    path = output.toString(),
                    displayName = output.fileName.toString(),
                    kind = MediaAssetKind.Video,
                    durationMicros = project.durationMicros,
                    width = 64,
                    height = 64,
                    frameRate = 24.0,
                ),
            ),
        )
        val previewSession = service.createPreviewSession(previewProject, maxWidth = 64, maxHeight = 64)
        try {
            val first = previewSession.render(0L)
            val later = previewSession.render(100_000L)
            assertEquals(64 * 64 * 4, first.bgraPixels.size)
            assertEquals(64 * 64 * 4, later.bgraPixels.size)
        } finally {
            previewSession.close()
        }

        assertFailsWith<EditorMediaException> {
            service.export(EditorExportRequest.Video(project, output.toString(), overwrite = false))
        }
    }

    private fun layeredProject(firstPath: String, secondPath: String): EditorProject {
        val firstId = MediaAssetId("asset:first")
        val secondId = MediaAssetId("asset:second")
        return EditorProject(
            name = "Layered",
            primaryAssetId = firstId,
            canvasWidth = 64,
            canvasHeight = 64,
            frameRate = 24,
            assets = listOf(
                imageAsset(firstId, firstPath),
                imageAsset(secondId, secondPath),
            ),
            tracks = listOf(
                EditorTrack(
                    EditorTrackId("track:first"),
                    "Video 1",
                    EditorTrackKind.Video,
                    clips = listOf(EditorClip.Media(EditorClipId("clip:first"), firstId, 0L, 1_000_000L)),
                ),
                EditorTrack(
                    EditorTrackId("track:second"),
                    "Video 2",
                    EditorTrackKind.Video,
                    clips = listOf(
                        EditorClip.Media(
                            EditorClipId("clip:second"),
                            secondId,
                            0L,
                            1_000_000L,
                            transform = ClipTransform(scale = 0.5f),
                        ),
                    ),
                ),
            ),
            importantFrames = listOf(
                ImportantFrameMarker(ImportantFrameId("frame:one"), 100_000),
                ImportantFrameMarker(ImportantFrameId("frame:two"), 900_000),
                ImportantFrameMarker(ImportantFrameId("frame:excluded"), 500_000, included = false),
            ),
        )
    }

    private fun imageAsset(id: MediaAssetId, path: String): MediaAsset = MediaAsset(
        id = id,
        path = path,
        displayName = path.substringAfterLast('\\').substringAfterLast('/'),
        kind = MediaAssetKind.Image,
        durationMicros = 0,
        width = 64,
        height = 64,
    )

    private fun writeColor(file: java.io.File, color: Color) {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        assertTrue(ImageIO.write(image, "png", file))
    }
}
