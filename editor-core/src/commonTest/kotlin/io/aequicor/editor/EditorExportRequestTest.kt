package io.aequicor.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditorExportRequestTest {
    private val project = EditorProject(
        name = "Export",
        primaryAssetId = null,
        canvasWidth = 1920,
        canvasHeight = 1080,
        frameRate = 30,
    )

    @Test
    fun framesUseFullResolutionAndMediumJpegCompressionByDefault() {
        val request = EditorExportRequest.Frames(project, "frames")

        assertEquals(FrameImageFormat.Png, request.outputFormat)
        assertEquals(100, request.resolutionPercent)
        assertEquals(JpegCompression.Medium, request.jpegCompression)
    }

    @Test
    fun rejectsFrameResolutionOutsideThePercentageRange() {
        assertFailsWith<IllegalArgumentException> {
            EditorExportRequest.Frames(project, "frames", resolutionPercent = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            EditorExportRequest.Frames(project, "frames", resolutionPercent = 101)
        }
    }
}
