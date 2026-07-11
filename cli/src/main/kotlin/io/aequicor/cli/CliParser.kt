package io.aequicor.cli

object CliParser {
    fun parse(args: Array<String>): CliParseResult = parse(args.toList())

    fun parse(args: List<String>): CliParseResult {
        if (args.isEmpty() || args.singleOrNull() in setOf("help", "--help", "-h")) {
            return CliParseResult.Parsed(CliCommand.Help)
        }

        return when (args.first()) {
            "list-sources" -> parseListCommand(args.drop(1), ::ListSourcesCommand)
            "list-audio" -> parseListCommand(args.drop(1), ::ListAudioCommand)
            "record" -> parseRecord(args.drop(1))
            "control" -> parseControl(args.drop(1))
            "replay" -> parseReplay(args.drop(1))
            "export-frames" -> parseExportFrames(args.drop(1))
            "settings" -> parseSettings(args.drop(1))
            else -> CliParseResult.Invalid("Unknown command: ${args.first()}")
        }
    }

    private fun parseListCommand(
        args: List<String>,
        factory: (Boolean) -> CliCommand,
    ): CliParseResult {
        var json = false
        for (arg in args) {
            when (arg) {
                "--json" -> json = true
                "--help", "-h" -> return CliParseResult.Parsed(CliCommand.Help)
                else -> return CliParseResult.Invalid("Unknown option for list command: $arg")
            }
        }
        return CliParseResult.Parsed(factory(json))
    }

    private fun parseRecord(args: List<String>): CliParseResult {
        val targetName = args.firstOrNull()
            ?: return CliParseResult.Invalid("Missing record target. Expected screen, monitor, region, window, or app.")
        val options = OptionCursor(args.drop(1))

        val target = when (targetName) {
            "screen" -> RecordTarget.Screen
            "profile" -> RecordTarget.Profile
            "monitor" -> {
                val id = options.requireValue("--id")
                    ?: return CliParseResult.Invalid("record monitor requires --id.")
                RecordTarget.Monitor(id)
            }
            "region" -> {
                val x = options.requireInt("--x") ?: return CliParseResult.Invalid("record region requires integer --x.")
                val y = options.requireInt("--y") ?: return CliParseResult.Invalid("record region requires integer --y.")
                val width = options.requireInt("--width")
                    ?: return CliParseResult.Invalid("record region requires integer --width.")
                val height = options.requireInt("--height")
                    ?: return CliParseResult.Invalid("record region requires integer --height.")
                RecordTarget.Region(x = x, y = y, width = width, height = height)
            }
            "window" -> {
                val id = options.requireValue("--id")
                    ?: return CliParseResult.Invalid("record window requires --id.")
                RecordTarget.Window(id)
            }
            "app", "application" -> {
                val id = options.requireValue("--id") ?: options.requireValue("--pid")
                    ?: return CliParseResult.Invalid("record app requires --id or --pid.")
                RecordTarget.Application(id)
            }
            else -> return CliParseResult.Invalid("Unknown record target: $targetName")
        }

        val settingsPath = options.optionalValue("--settings")
        val profileId = options.optionalValue("--profile")
        val outputPath = options.optionalValue("--output")
        if (target == RecordTarget.Profile && settingsPath == null) {
            return CliParseResult.Invalid("record profile requires --settings.")
        }
        if (outputPath == null && settingsPath == null) {
            return CliParseResult.Invalid("record $targetName requires --output or --settings.")
        }
        val fps = if (options.contains("--fps")) {
            options.requireInt("--fps") ?: return CliParseResult.Invalid("--fps must be an integer.")
        } else {
            null
        }
        val captureCursor = when {
            options.has("--cursor") && options.has("--no-cursor") -> {
                return CliParseResult.Invalid("Use either --cursor or --no-cursor, not both.")
            }
            options.has("--cursor") -> true
            options.has("--no-cursor") -> false
            else -> null
        }
        val microphone = if (options.contains("--mic")) {
            options.requireValue("--mic")
                ?: return CliParseResult.Invalid("--mic requires a microphone id, display name, or 'default'.")
        } else {
            null
        }
        val microphoneGainPercent = options.readGainPercent("--mic-gain")
            ?: if (options.contains("--mic-gain")) {
                return CliParseResult.Invalid("--mic-gain must be an integer from 0 to 200.")
            } else {
                null
            }
        if (microphoneGainPercent != null && microphone == null && settingsPath == null) {
            return CliParseResult.Invalid("--mic-gain requires --mic or --settings.")
        }
        val systemAudioEndpoint = if (options.contains("--system-audio-endpoint")) {
            options.requireValue("--system-audio-endpoint")
                ?: return CliParseResult.Invalid(
                    "--system-audio-endpoint requires an endpoint id or display name.",
                )
        } else {
            null
        }
        val systemAudioGainPercent = options.readGainPercent("--system-audio-gain")
            ?: if (options.contains("--system-audio-gain")) {
                return CliParseResult.Invalid("--system-audio-gain must be an integer from 0 to 200.")
            } else {
                null
            }
        val systemAudio = options.has("--system-audio") ||
            systemAudioEndpoint != null ||
            systemAudioGainPercent != null
        val duration = options.optionalValue("--duration")
        val overwriteOutput = options.has("--overwrite")
        val controlEndpointPath = if (options.contains("--control-endpoint")) {
            options.requireValue("--control-endpoint")
                ?: return CliParseResult.Invalid("--control-endpoint requires a local descriptor path.")
        } else {
            null
        }
        val json = options.has("--json")

        return options.unconsumedOption()?.let {
            CliParseResult.Invalid("Unknown option for record $targetName: $it")
        } ?: CliParseResult.Parsed(
            CliCommand.Record(
                target = target,
                options = RecordOptions(
                    outputPath = outputPath,
                    fps = fps,
                    captureCursor = captureCursor,
                    microphone = microphone,
                    microphoneGainPercent = microphoneGainPercent,
                    systemAudio = systemAudio,
                    systemAudioEndpoint = systemAudioEndpoint,
                    systemAudioGainPercent = systemAudioGainPercent,
                    duration = duration,
                    settingsPath = settingsPath,
                    profileId = profileId,
                    overwriteOutput = overwriteOutput,
                    controlEndpointPath = controlEndpointPath,
                    json = json,
                ),
            ),
        )
    }

    private fun parseControl(args: List<String>): CliParseResult {
        val actionName = args.firstOrNull()
            ?: return CliParseResult.Invalid("Missing control action. Expected status, pause, resume, save, or stop.")
        val action = when (actionName) {
            "status" -> RecordingControlAction.Status
            "pause" -> RecordingControlAction.Pause
            "resume" -> RecordingControlAction.Resume
            "save" -> RecordingControlAction.Save
            "stop" -> RecordingControlAction.Stop
            else -> return CliParseResult.Invalid("Unknown control action: $actionName")
        }
        val options = OptionCursor(args.drop(1))
        val endpointPath = options.requireValue("--endpoint")
            ?: return CliParseResult.Invalid("control $actionName requires --endpoint.")
        val outputPath = if (action == RecordingControlAction.Save) {
            options.requireValue("--output")
                ?: return CliParseResult.Invalid("control save requires --output.")
        } else {
            null
        }
        val json = options.has("--json")
        return options.unconsumedOption()?.let {
            CliParseResult.Invalid("Unknown option for control $actionName: $it")
        } ?: CliParseResult.Parsed(
            CliCommand.Control(
                action = action,
                endpointPath = endpointPath,
                outputPath = outputPath,
                json = json,
            ),
        )
    }

    private fun parseReplay(args: List<String>): CliParseResult {
        val subcommand = args.firstOrNull()
            ?: return CliParseResult.Invalid("Missing replay command. Expected run, start, or save.")
        return when (subcommand) {
            "start" -> parseReplayCapture(args.drop(1), commandName = "start").toDaemonStart()
            "save" -> {
                val options = OptionCursor(args.drop(1))
                val endpointPath = options.requireValue("--endpoint")
                    ?: return CliParseResult.Invalid("replay save requires --endpoint.")
                val outputPath = options.requireValue("--output")
                    ?: return CliParseResult.Invalid("replay save requires --output.")
                val json = options.has("--json")
                options.unconsumedOption()?.let {
                    CliParseResult.Invalid("Unknown option for replay save: $it")
                } ?: CliParseResult.Parsed(
                    CliCommand.ReplaySave(endpointPath = endpointPath, outputPath = outputPath, json = json),
                )
            }
            "run" -> parseReplayCapture(args.drop(1), commandName = "run")
            else -> CliParseResult.Invalid("Unknown replay command: $subcommand")
        }
    }

    private fun parseReplayCapture(args: List<String>, commandName: String): CliParseResult {
        val targetName = args.firstOrNull()
            ?: return CliParseResult.Invalid(
                "replay $commandName requires a source: screen, monitor, region, window, or app.",
            )
        val options = OptionCursor(args.drop(1))
        val target = when (targetName) {
            "screen" -> RecordTarget.Screen
            "monitor" -> {
                val id = options.requireValue("--id")
                    ?: return CliParseResult.Invalid("replay $commandName monitor requires --id.")
                RecordTarget.Monitor(id)
            }
            "region" -> {
                val x = options.requireInt("--x")
                    ?: return CliParseResult.Invalid("replay $commandName region requires integer --x.")
                val y = options.requireInt("--y")
                    ?: return CliParseResult.Invalid("replay $commandName region requires integer --y.")
                val width = options.requireInt("--width")
                    ?: return CliParseResult.Invalid("replay $commandName region requires integer --width.")
                val height = options.requireInt("--height")
                    ?: return CliParseResult.Invalid("replay $commandName region requires integer --height.")
                RecordTarget.Region(x = x, y = y, width = width, height = height)
            }
            "window" -> {
                val id = options.requireValue("--id")
                    ?: return CliParseResult.Invalid("replay $commandName window requires --id.")
                RecordTarget.Window(id)
            }
            "app", "application" -> {
                val id = options.requireValue("--id") ?: options.requireValue("--pid")
                    ?: return CliParseResult.Invalid("replay $commandName app requires --id or --pid.")
                RecordTarget.Application(id)
            }
            else -> return CliParseResult.Invalid("Unknown replay source: $targetName")
        }
        val bufferDuration = options.requireValue("--buffer")
            ?: return CliParseResult.Invalid("replay $commandName requires --buffer, for example 5m.")
        val runDuration = if (options.contains("--run-for")) {
            options.requireValue("--run-for")
                ?: return CliParseResult.Invalid("--run-for requires a positive duration such as 30m.")
        } else {
            null
        }
        val outputPath = options.requireValue("--output")
            ?: return CliParseResult.Invalid("replay $commandName requires --output.")
        val fps = if (options.contains("--fps")) {
            options.requireInt("--fps") ?: return CliParseResult.Invalid("--fps must be an integer.")
        } else {
            null
        }
        val captureCursor = when {
            options.has("--cursor") && options.has("--no-cursor") -> {
                return CliParseResult.Invalid("Use either --cursor or --no-cursor, not both.")
            }
            options.has("--cursor") -> true
            options.has("--no-cursor") -> false
            else -> null
        }
        val microphone = if (options.contains("--mic")) {
            options.requireValue("--mic")
                ?: return CliParseResult.Invalid("--mic requires a microphone id, display name, or 'default'.")
        } else {
            null
        }
        val microphoneGainPercent = options.readGainPercent("--mic-gain")
            ?: if (options.contains("--mic-gain")) {
                return CliParseResult.Invalid("--mic-gain must be an integer from 0 to 200.")
            } else {
                null
            }
        if (microphoneGainPercent != null && microphone == null) {
                return CliParseResult.Invalid("--mic-gain requires --mic for replay $commandName.")
        }
        val systemAudioEndpoint = if (options.contains("--system-audio-endpoint")) {
            options.requireValue("--system-audio-endpoint")
                ?: return CliParseResult.Invalid(
                    "--system-audio-endpoint requires an endpoint id or display name.",
                )
        } else {
            null
        }
        val systemAudioGainPercent = options.readGainPercent("--system-audio-gain")
            ?: if (options.contains("--system-audio-gain")) {
                return CliParseResult.Invalid("--system-audio-gain must be an integer from 0 to 200.")
            } else {
                null
            }
        val systemAudio = options.has("--system-audio") ||
            systemAudioEndpoint != null ||
            systemAudioGainPercent != null
        val controlEndpointPath = if (options.contains("--control-endpoint")) {
            options.requireValue("--control-endpoint")
                ?: return CliParseResult.Invalid("--control-endpoint requires a local descriptor path.")
        } else {
            null
        }
        val json = options.has("--json")

        return options.unconsumedOption()?.let {
            CliParseResult.Invalid("Unknown option for replay $commandName $targetName: $it")
        } ?: CliParseResult.Parsed(
            CliCommand.ReplayRun(
                target = target,
                options = ReplayRunOptions(
                    bufferDuration = bufferDuration,
                    runDuration = runDuration,
                    outputPath = outputPath,
                    fps = fps,
                    captureCursor = captureCursor,
                    microphone = microphone,
                    microphoneGainPercent = microphoneGainPercent,
                    systemAudio = systemAudio,
                    systemAudioEndpoint = systemAudioEndpoint,
                    systemAudioGainPercent = systemAudioGainPercent,
                    controlEndpointPath = controlEndpointPath,
                    json = json,
                ),
            ),
        )
    }

    private fun CliParseResult.toDaemonStart(): CliParseResult = when (this) {
        is CliParseResult.Invalid -> this
        is CliParseResult.Parsed -> {
            val run = command as? CliCommand.ReplayRun ?: return this
            when {
                run.options.runDuration != null ->
                    CliParseResult.Invalid("replay start does not accept --run-for; stop it through the control endpoint.")
                run.options.controlEndpointPath == null ->
                    CliParseResult.Invalid("replay start requires --control-endpoint.")
                else -> CliParseResult.Parsed(CliCommand.ReplayStart(run.target, run.options))
            }
        }
    }

    private fun parseExportFrames(args: List<String>): CliParseResult {
        val options = OptionCursor(args)
        val inputPath = options.requireValue("--input")
            ?: return CliParseResult.Invalid("export-frames requires --input.")
        val outputDirectory = options.requireValue("--output")
            ?: return CliParseResult.Invalid("export-frames requires --output.")
        val fps = if (options.contains("--fps")) {
            options.requireInt("--fps") ?: return CliParseResult.Invalid("--fps must be an integer.")
        } else {
            null
        }
        val interval = options.optionalValue("--interval")
        if (fps != null && interval != null) {
            return CliParseResult.Invalid("Use either --fps or --interval, not both.")
        }
        val imageFormat = options.optionalValue("--format") ?: "png"
        val layout = when (val value = options.optionalValue("--layout")?.lowercase()) {
            null, "separate", "frames" -> FrameExportLayout.SeparatePngFiles
            "sheet", "contact-sheet", "single" -> FrameExportLayout.ContactSheet
            else -> return CliParseResult.Invalid(
                "Unsupported export layout: $value. Use separate or sheet.",
            )
        }
        val overwrite = options.has("--overwrite")
        val json = options.has("--json")

        return options.unconsumedOption()?.let {
            CliParseResult.Invalid("Unknown option for export-frames: $it")
        } ?: CliParseResult.Parsed(
            CliCommand.ExportFrames(
                ExportFramesOptions(
                    inputPath = inputPath,
                    outputDirectory = outputDirectory,
                    fps = fps,
                    interval = interval,
                    imageFormat = imageFormat,
                    layout = layout,
                    overwrite = overwrite,
                    json = json,
                ),
            ),
        )
    }

    private fun parseSettings(args: List<String>): CliParseResult {
        val subcommand = args.firstOrNull()
            ?: return CliParseResult.Invalid("Missing settings command. Expected init, validate, or show.")
        val options = OptionCursor(args.drop(1))
        val path = options.optionalValue("--path") ?: DEFAULT_SETTINGS_PATH
        val json = options.has("--json")
        return when (subcommand) {
            "init" -> {
                val force = options.has("--force")
                options.unconsumedOption()?.let {
                    CliParseResult.Invalid("Unknown option for settings init: $it")
                } ?: CliParseResult.Parsed(
                    CliCommand.Settings(
                        SettingsAction.Init(path = path, force = force, json = json),
                    ),
                )
            }
            "validate" -> options.unconsumedOption()?.let {
                CliParseResult.Invalid("Unknown option for settings validate: $it")
            } ?: CliParseResult.Parsed(
                CliCommand.Settings(SettingsAction.Validate(path = path, json = json)),
            )
            "show" -> options.unconsumedOption()?.let {
                CliParseResult.Invalid("Unknown option for settings show: $it")
            } ?: CliParseResult.Parsed(
                CliCommand.Settings(SettingsAction.Show(path = path, json = json)),
            )
            else -> CliParseResult.Invalid("Unknown settings command: $subcommand")
        }
    }
}

const val DEFAULT_SETTINGS_PATH: String = "mission-recorder.settings.json"

private class OptionCursor(args: List<String>) {
    private val tokens = args
    private val consumed = mutableSetOf<Int>()

    fun has(name: String): Boolean {
        val index = tokens.indexOf(name).takeIf { it >= 0 } ?: return false
        consumed += index
        return true
    }

    fun requireValue(name: String): String? {
        val index = tokens.indexOf(name).takeIf { it >= 0 } ?: return null
        consumed += index
        val valueIndex = index + 1
        val value = tokens.getOrNull(valueIndex) ?: return null
        if (value.startsWith("--")) {
            return null
        }
        consumed += valueIndex
        return value
    }

    fun optionalValue(name: String): String? = requireValue(name)

    fun requireInt(name: String): Int? = requireValue(name)?.toIntOrNull()

    fun readGainPercent(name: String): Int? = requireInt(name)?.takeIf { value -> value in 0..200 }

    fun unconsumedOption(): String? =
        tokens.withIndex()
            .filter { it.index !in consumed }
            .firstOrNull { it.value.startsWith("--") }
            ?.value

    fun contains(name: String): Boolean = tokens.contains(name)
}

private fun ListSourcesCommand(json: Boolean): CliCommand = CliCommand.ListSources(json)

private fun ListAudioCommand(json: Boolean): CliCommand = CliCommand.ListAudio(json)
