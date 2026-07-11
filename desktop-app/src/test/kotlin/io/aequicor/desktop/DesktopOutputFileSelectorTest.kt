package io.aequicor.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopOutputFileSelectorTest {
    @Test
    fun appendsMp4ExtensionAndNormalizesSelectedPath() {
        val directory = Files.createTempDirectory("mission-recorder-output-selector-test")

        val selected = normalizeMp4OutputPath(directory.toString(), "capture")

        assertEquals(directory.resolve("capture.mp4").toAbsolutePath().normalize().toString(), selected)
    }

    @Test
    fun preservesExistingMp4ExtensionCaseInsensitively() {
        val directory = Files.createTempDirectory("mission-recorder-output-selector-extension-test")

        val selected = normalizeMp4OutputPath(directory.toString(), "capture.MP4")

        assertEquals(directory.resolve("capture.MP4").toAbsolutePath().normalize().toString(), selected)
    }
}
