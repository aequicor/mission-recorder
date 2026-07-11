package io.aequicor.cli

import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.CaptureSourceRepository

class MissionRecorderCli(
    private val captureSourceRepository: CaptureSourceRepository,
    private val audioSourceRepository: AudioSourceRepository,
    private val recordingCommandBackend: RecordingCommandBackend = UnsupportedRecordingCommandBackend,
    private val recordingControlCommandBackend: RecordingControlCommandBackend = UnsupportedRecordingControlCommandBackend,
    private val replayCommandBackend: ReplayCommandBackend = UnsupportedReplayCommandBackend,
    private val exportFramesCommandBackend: ExportFramesCommandBackend = UnsupportedExportFramesCommandBackend,
    private val settingsCommandBackend: SettingsCommandBackend = UnsupportedSettingsCommandBackend,
) {
    suspend fun run(
        args: Array<String>,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        return when (val parseResult = CliParser.parse(args)) {
            is CliParseResult.Invalid -> {
                stderr.appendLine(parseResult.message)
                stderr.appendLine("Run mission-recorder --help for usage.")
                CliExitCode.Usage.code
            }
            is CliParseResult.Parsed -> run(parseResult.command, stdout, stderr)
        }
    }

    private suspend fun run(command: CliCommand, stdout: Appendable, stderr: Appendable): Int =
        when (command) {
            CliCommand.Help -> {
                stdout.appendLine(CliRendering.helpText())
                CliExitCode.Success.code
            }
            is CliCommand.ListSources -> {
                val sources = captureSourceRepository.listSources()
                stdout.appendLine(if (command.json) CliRendering.sourcesJson(sources) else CliRendering.sourcesText(sources))
                CliExitCode.Success.code
            }
            is CliCommand.ListAudio -> {
                val sources = audioSourceRepository.listAudioSources()
                stdout.appendLine(if (command.json) CliRendering.audioJson(sources) else CliRendering.audioText(sources))
                CliExitCode.Success.code
            }
            is CliCommand.Record -> record(command, stdout, stderr)
            is CliCommand.Control -> control(command, stdout, stderr)
            is CliCommand.ReplayStart -> unsupported("replay start", command.json, stdout, stderr)
            is CliCommand.ReplaySave -> unsupported("replay save", command.json, stdout, stderr)
            is CliCommand.ReplayRun -> replay(command, stdout, stderr)
            is CliCommand.ExportFrames -> exportFrames(command, stdout, stderr)
            is CliCommand.Settings -> settings(command, stdout, stderr)
        }

    private suspend fun control(command: CliCommand.Control, stdout: Appendable, stderr: Appendable): Int =
        when (val result = recordingControlCommandBackend.control(command)) {
            is RecordingControlCommandResult.Completed -> {
                stdout.appendLine(
                    if (command.json) CliRendering.controlCompletedJson(result)
                    else CliRendering.controlCompletedText(result),
                )
                CliExitCode.Success.code
            }
            is RecordingControlCommandResult.Rejected -> {
                if (command.json) stdout.appendLine(CliRendering.errorJson("validation", result.message))
                else stderr.appendLine(result.message)
                CliExitCode.Validation.code
            }
            is RecordingControlCommandResult.Failed -> {
                if (command.json) stdout.appendLine(CliRendering.errorJson("failure", result.message))
                else stderr.appendLine(result.message)
                CliExitCode.Failure.code
            }
        }

    private suspend fun record(command: CliCommand.Record, stdout: Appendable, stderr: Appendable): Int {
        return when (val result = recordingCommandBackend.record(command)) {
            is RecordingCommandResult.Completed -> {
                stdout.appendLine(
                    if (command.options.json) {
                        CliRendering.recordCompletedJson(result)
                    } else {
                        CliRendering.recordCompletedText(result)
                    },
                )
                CliExitCode.Success.code
            }
            is RecordingCommandResult.Rejected -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("validation", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Validation.code
            }
            is RecordingCommandResult.Unsupported -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("unsupported", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Unsupported.code
            }
            is RecordingCommandResult.Failed -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("failure", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Failure.code
            }
        }
    }

    private fun unsupported(
        command: String,
        json: Boolean,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        if (json) {
            stdout.appendLine(CliRendering.unsupportedJson(command))
        } else {
            stderr.appendLine("$command is parsed, but no recording backend is wired yet.")
        }
        return CliExitCode.Unsupported.code
    }

    private suspend fun replay(command: CliCommand.ReplayRun, stdout: Appendable, stderr: Appendable): Int {
        return when (val result = replayCommandBackend.run(command)) {
            is ReplayCommandResult.Completed -> {
                stdout.appendLine(
                    if (command.options.json) {
                        CliRendering.replayCompletedJson(result)
                    } else {
                        CliRendering.replayCompletedText(result)
                    },
                )
                CliExitCode.Success.code
            }
            is ReplayCommandResult.Rejected -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("validation", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Validation.code
            }
            is ReplayCommandResult.Unsupported -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("unsupported", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Unsupported.code
            }
            is ReplayCommandResult.Failed -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("failure", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Failure.code
            }
        }
    }

    private suspend fun exportFrames(command: CliCommand.ExportFrames, stdout: Appendable, stderr: Appendable): Int {
        return when (val result = exportFramesCommandBackend.exportFrames(command)) {
            is ExportFramesCommandResult.Completed -> {
                stdout.appendLine(
                    if (command.options.json) {
                        CliRendering.exportFramesCompletedJson(result)
                    } else {
                        CliRendering.exportFramesCompletedText(result)
                    },
                )
                CliExitCode.Success.code
            }
            is ExportFramesCommandResult.Rejected -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("validation", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Validation.code
            }
            is ExportFramesCommandResult.Unsupported -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("unsupported", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Unsupported.code
            }
            is ExportFramesCommandResult.Failed -> {
                if (command.options.json) {
                    stdout.appendLine(CliRendering.errorJson("failure", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Failure.code
            }
        }
    }

    private suspend fun settings(command: CliCommand.Settings, stdout: Appendable, stderr: Appendable): Int {
        return when (val result = settingsCommandBackend.handle(command)) {
            is SettingsCommandResult.Initialized -> {
                stdout.appendLine(
                    if (command.action.json) {
                        CliRendering.settingsInitializedJson(result)
                    } else {
                        CliRendering.settingsInitializedText(result)
                    },
                )
                CliExitCode.Success.code
            }
            is SettingsCommandResult.Valid -> {
                stdout.appendLine(
                    if (command.action.json) {
                        CliRendering.settingsValidJson(result)
                    } else {
                        CliRendering.settingsValidText(result)
                    },
                )
                CliExitCode.Success.code
            }
            is SettingsCommandResult.Shown -> {
                stdout.appendLine(result.settingsJson)
                CliExitCode.Success.code
            }
            is SettingsCommandResult.Rejected -> {
                if (command.action.json) {
                    stdout.appendLine(CliRendering.errorJson("validation", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Validation.code
            }
            is SettingsCommandResult.Failed -> {
                if (command.action.json) {
                    stdout.appendLine(CliRendering.errorJson("failure", result.message))
                } else {
                    stderr.appendLine(result.message)
                }
                CliExitCode.Failure.code
            }
        }
    }
}
