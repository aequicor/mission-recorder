package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.SettingsAction
import io.aequicor.cli.SettingsCommandBackend
import io.aequicor.cli.SettingsCommandResult
import io.aequicor.settings.MissionRecorderSettings
import io.aequicor.settings.MissionRecorderSettingsFactory
import io.aequicor.settings.MissionRecorderSettingsStore
import io.aequicor.settings.MissionRecorderSettingsValidator
import io.aequicor.settings.SettingsValidationException
import io.aequicor.settings.defaultSettingsJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class LocalSettingsCommandBackend : SettingsCommandBackend {
    override suspend fun handle(command: CliCommand.Settings): SettingsCommandResult =
        when (val action = command.action) {
            is SettingsAction.Init -> init(action)
            is SettingsAction.Validate -> validate(action)
            is SettingsAction.Show -> show(action)
        }

    private fun init(action: SettingsAction.Init): SettingsCommandResult {
        val path = action.path.toPath()
        if (path.exists() && !action.force) {
            return SettingsCommandResult.Rejected("Settings file already exists: $path. Use --force to overwrite.")
        }
        return runCatching {
            val settings = MissionRecorderSettingsFactory.defaultLocal()
            MissionRecorderSettingsStore(path).save(settings)
            SettingsCommandResult.Initialized(path = path.toString(), profileCount = settings.profiles.size)
        }.getOrElse { it.toSettingsFailure() }
    }

    private fun validate(action: SettingsAction.Validate): SettingsCommandResult {
        val path = action.path.toPath()
        if (!path.exists()) {
            return SettingsCommandResult.Rejected("Settings file does not exist: $path")
        }
        return runCatching {
            val settings = MissionRecorderSettingsStore(path).loadOrDefault()
            val issues = MissionRecorderSettingsValidator.validate(settings)
            if (issues.isEmpty()) {
                SettingsCommandResult.Valid(path = path.toString(), profileCount = settings.profiles.size)
            } else {
                SettingsCommandResult.Rejected(issues.joinToString("; ") { "${it.field}: ${it.message}" })
            }
        }.getOrElse { it.toSettingsFailure() }
    }

    private fun show(action: SettingsAction.Show): SettingsCommandResult {
        val path = action.path.toPath()
        if (!path.exists()) {
            return SettingsCommandResult.Rejected("Settings file does not exist: $path")
        }
        return runCatching {
            val settings = MissionRecorderSettingsStore(path).loadOrDefault()
            SettingsCommandResult.Shown(
                path = path.toString(),
                settingsJson = defaultSettingsJson.encodeToString(MissionRecorderSettings.serializer(), settings),
            )
        }.getOrElse { it.toSettingsFailure() }
    }
}

private fun String.toPath(): Path = Path.of(this).toAbsolutePath().normalize()

private fun Throwable.toSettingsFailure(): SettingsCommandResult =
    when (this) {
        is SettingsValidationException -> SettingsCommandResult.Rejected(
            issues.joinToString("; ") { "${it.field}: ${it.message}" },
        )
        is java.nio.file.NoSuchFileException -> SettingsCommandResult.Rejected("Settings file does not exist: ${file}")
        else -> SettingsCommandResult.Failed(message ?: this::class.simpleName ?: "Settings command failed.")
    }
