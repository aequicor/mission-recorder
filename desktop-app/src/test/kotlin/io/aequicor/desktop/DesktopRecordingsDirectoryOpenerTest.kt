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
    fun opensParentDirectoryWithRecordedFileSelected() = runTest {
        val root = Files.createTempDirectory("mission-recorder-select-file-test")
        val output = Files.createFile(root.resolve("recording.mp4"))
        var selected: Path? = null
        var opened: Path? = null
        val opener = AwtDesktopRecordingsDirectoryOpener(
            isHeadless = { false },
            isDesktopSupported = { true },
            isOpenSupported = { true },
            isBrowseFileDirectorySupported = { true },
            browseFileDirectory = { selected = it },
            openDirectory = { opened = it },
        )

        val result = opener.openForOutput(output.toString())

        val expectedDirectory = output.parent.toAbsolutePath().normalize()
        assertEquals(
            expectedDirectory.toString(),
            assertIs<DesktopDirectoryOpenResult.Opened>(result).directory,
        )
        assertEquals(output.toAbsolutePath().normalize(), selected)
        assertEquals(null, opened)
    }

    @Test
    fun selectsRecordedFileWithWindowsExplorerWhenDesktopActionIsUnavailable() = runTest {
        val root = Files.createTempDirectory("mission-recorder-windows-select-test")
        val output = Files.createFile(root.resolve("recording.mp4")).toAbsolutePath().normalize()
        var command: List<String>? = null
        val opener = AwtDesktopRecordingsDirectoryOpener(
            isHeadless = { false },
            isDesktopSupported = { true },
            isOpenSupported = { true },
            isBrowseFileDirectorySupported = { false },
            operatingSystemName = "Windows 11",
            startProcess = { command = it },
        )

        val result = opener.openForOutput(output.toString())

        assertIs<DesktopDirectoryOpenResult.Opened>(result)
        assertEquals(listOf("explorer.exe", "/select,$output"), command)
    }

    @Test
    fun createsAndOpensParentDirectoryWhenOutputFileDoesNotExist() = runTest {
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
