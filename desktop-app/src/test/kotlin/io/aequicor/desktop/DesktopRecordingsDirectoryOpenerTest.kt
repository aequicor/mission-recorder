package io.aequicor.desktop

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopRecordingsDirectoryOpenerTest {
    @Test
    fun createsAndOpensParentDirectoryOfOutputFile() = runTest {
        val root = Files.createTempDirectory("mission-recorder-open-folder-test")
        val output = root.resolve("nested").resolve("recording.mp4")
        var opened: Path? = null
        val opener = AwtDesktopRecordingsDirectoryOpener(
            isHeadless = { false },
            isDesktopSupported = { true },
            isOpenSupported = { true },
            openDirectory = { opened = it },
        )

        val result = opener.openForOutput(output.toString())

        val expected = output.parent.toAbsolutePath().normalize()
        assertEquals(expected.toString(), assertIs<DesktopDirectoryOpenResult.Opened>(result).directory)
        assertEquals(expected, opened)
        assertTrue(expected.exists())
    }

    @Test
    fun rejectsHeadlessDesktopWithoutCreatingDirectory() = runTest {
        val root = Files.createTempDirectory("mission-recorder-open-folder-headless-test")
        val output = root.resolve("missing").resolve("recording.mp4")
        val opener = AwtDesktopRecordingsDirectoryOpener(isHeadless = { true })

        val result = opener.openForOutput(output.toString())

        assertIs<DesktopDirectoryOpenResult.Unavailable>(result)
        assertFalse(output.parent.exists())
    }
}
