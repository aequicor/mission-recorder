package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.SettingsAction
import io.aequicor.cli.SettingsCommandResult
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSettingsCommandBackendTest {
    @Test
    fun initializesValidatesAndShowsSettingsFile() = runTest {
        val path = Files.createTempDirectory("mission-recorder-settings-cli").resolve("settings.json")
        val backend = LocalSettingsCommandBackend()

        val init = backend.handle(
            CliCommand.Settings(SettingsAction.Init(path = path.toString(), force = false, json = false)),
        )
        val validate = backend.handle(
            CliCommand.Settings(SettingsAction.Validate(path = path.toString(), json = false)),
        )
        val show = backend.handle(
            CliCommand.Settings(SettingsAction.Show(path = path.toString(), json = true)),
        )

        assertIs<SettingsCommandResult.Initialized>(init)
        val valid = assertIs<SettingsCommandResult.Valid>(validate)
        val shown = assertIs<SettingsCommandResult.Shown>(show)
        assertTrue(path.exists())
        assertEquals(1, valid.profileCount)
        assertTrue(shown.settingsJson.contains("schemaVersion"))
    }

    @Test
    fun rejectsInitWithoutForceWhenSettingsFileExists() = runTest {
        val path = Files.createTempDirectory("mission-recorder-settings-cli-existing").resolve("settings.json")
        Files.writeString(path, "{}")
        val backend = LocalSettingsCommandBackend()

        val result = backend.handle(
            CliCommand.Settings(SettingsAction.Init(path = path.toString(), force = false, json = false)),
        )

        assertIs<SettingsCommandResult.Rejected>(result)
    }
}
