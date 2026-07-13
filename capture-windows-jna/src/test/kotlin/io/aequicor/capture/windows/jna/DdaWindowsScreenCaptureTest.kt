package io.aequicor.capture.windows.jna

import kotlin.test.Test
import kotlin.test.assertEquals

class DdaWindowsScreenCaptureTest {
    @Test
    fun buildsFullOutputFilterWithoutRedundantCrop() {
        val output = WindowsDesktopOutput(
            index = 1,
            bounds = WindowsWindowBounds(x = 1920, y = 0, width = 3440, height = 1440),
        )

        assertEquals(
            "ddagrab=framerate=60:output_idx=1:draw_mouse=0:dup_frames=1,hwdownload,format=bgra",
            ddaGrabFilter(output, output.bounds, frameRate = 60),
        )
    }

    @Test
    fun cropsRegionRelativeToSelectedOutput() {
        val output = WindowsDesktopOutput(
            index = 1,
            bounds = WindowsWindowBounds(x = 1920, y = -100, width = 3440, height = 1440),
        )
        val region = WindowsWindowBounds(x = 2020, y = 100, width = 1280, height = 720)

        assertEquals(
            "ddagrab=framerate=30:output_idx=1:draw_mouse=0:dup_frames=1,hwdownload,format=bgra,crop=1280:720:100:200",
            ddaGrabFilter(output, region, frameRate = 30),
        )
    }
}
