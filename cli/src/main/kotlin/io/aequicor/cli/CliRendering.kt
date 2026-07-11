package io.aequicor.cli

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.CaptureSource

object CliRendering {
    fun helpText(): String =
        """
        Mission Recorder

        Usage:
          mission-recorder list-sources [--json]
          mission-recorder list-audio [--json]
          mission-recorder record screen --output out.mp4 [--duration 3s] [--fps 30] [--mic ID|default] [--mic-gain 0..200] [--system-audio] [--system-audio-endpoint ID] [--system-audio-gain 0..200] [--no-cursor] [--overwrite] [--control-endpoint PATH]
          mission-recorder record profile --settings mission-recorder.settings.json [--profile default] [--output out.mp4] [--duration 3s]
          mission-recorder record monitor --id ID --output out.mp4 [--duration 3s] [--fps 30] [--no-cursor]
          mission-recorder record region --x X --y Y --width W --height H --output out.mp4 [--duration 3s] [--no-cursor]
          mission-recorder record window --id ID --output out.mp4 [--duration 3s] [--no-cursor]
          mission-recorder record app --id ID --output out.mp4 [--duration 3s]
          mission-recorder replay run screen --buffer 5m --output replay.mp4 [--run-for 30m] [--mic ID|default] [--mic-gain 0..200] [--system-audio] [--system-audio-endpoint ID] [--system-audio-gain 0..200] [--control-endpoint PATH]
          mission-recorder replay run window --id ID --buffer 5m --output replay.mp4 [--run-for 30m]
          mission-recorder export-frames --input in.mp4 --output frames [--fps 1] [--layout separate|sheet]
          mission-recorder settings init [--path mission-recorder.settings.json] [--force]
          mission-recorder settings validate [--path mission-recorder.settings.json]
          mission-recorder settings show [--path mission-recorder.settings.json]
          mission-recorder control status|pause|resume|stop --endpoint PATH [--json]
          mission-recorder control save --endpoint PATH --output replay.mp4 [--json]

        Record runs until Ctrl+C when --duration is omitted.
        Existing recording targets are replaced only when --overwrite or profile output.overwrite is enabled.
        Replay run continues until Ctrl+C when --run-for is omitted, then saves the retained buffer.
        A control endpoint is local, opt-in, and exists only while its recording or replay process is active.
        """.trimIndent()

    fun sourcesText(sources: List<CaptureSource>): String =
        if (sources.isEmpty()) {
            "No capture sources are available because no platform adapter is wired yet."
        } else {
            buildString {
                appendLine("Capture sources:")
                sources.forEach { source ->
                    appendLine("  ${source.typeName()} ${source.id.value} ${source.displayName}")
                }
            }.trimEnd()
        }

    fun sourcesJson(sources: List<CaptureSource>): String =
        sources.joinToString(prefix = """{"sources":[""", separator = ",", postfix = "]}") { source ->
            """{"type":"${source.typeName()}","id":"${source.id.value.jsonEscape()}","displayName":"${source.displayName.jsonEscape()}"}"""
        }

    fun audioText(sources: List<AudioSource>): String =
        if (sources.isEmpty()) {
            "No audio sources are available."
        } else {
            buildString {
                appendLine("Audio sources:")
                sources.forEach { source ->
                    appendLine("  ${source.typeName()} ${source.id.value} ${source.displayName}")
                }
            }.trimEnd()
        }

    fun audioJson(sources: List<AudioSource>): String =
        sources.joinToString(prefix = """{"audioSources":[""", separator = ",", postfix = "]}") { source ->
            """{"type":"${source.typeName()}","id":"${source.id.value.jsonEscape()}","displayName":"${source.displayName.jsonEscape()}"}"""
        }

    fun unsupportedJson(command: String): String =
        """{"error":{"code":"unsupported","message":"${command.jsonEscape()} is parsed but no recording backend is wired yet."}}"""

    fun recordCompletedText(result: RecordingCommandResult.Completed): String =
        "Recording saved to ${result.outputPath} (${result.videoFrames} video frames, ${result.audioFrames} audio frames)."

    fun recordCompletedJson(result: RecordingCommandResult.Completed): String =
        """{"recording":{"outputPath":"${result.outputPath.jsonEscape()}","videoFrames":${result.videoFrames},"audioFrames":${result.audioFrames}}}"""

    fun controlCompletedText(result: RecordingControlCommandResult.Completed): String = buildString {
        append(result.action.name.lowercase())
        append(": ")
        append(result.status.state)
        append(" (${result.status.durationMilliseconds} ms, ${result.status.videoFrames} video frames, ")
        append("${result.status.audioFrames} audio frames, ${result.status.effectiveFramesPerSecond} effective FPS, ")
        append("${result.status.droppedFrames} dropped frames)")
        result.message?.let { append("; $it") }
    }

    fun controlCompletedJson(result: RecordingControlCommandResult.Completed): String =
        """{"control":{"action":"${result.action.name.lowercase()}","state":"${result.status.state.jsonEscape()}","outputPath":${result.status.outputPath?.let { "\"${it.jsonEscape()}\"" } ?: "null"},"durationMilliseconds":${result.status.durationMilliseconds},"videoFrames":${result.status.videoFrames},"audioFrames":${result.status.audioFrames},"droppedFrames":${result.status.droppedFrames},"effectiveFramesPerSecond":${result.status.effectiveFramesPerSecond},"message":${result.message?.let { "\"${it.jsonEscape()}\"" } ?: "null"}}}"""

    fun replayCompletedText(result: ReplayCommandResult.Completed): String =
        "Replay saved to ${result.outputPath} (${result.videoFrames} video frames, ${result.audioFrames} audio frames)."

    fun replayCompletedJson(result: ReplayCommandResult.Completed): String =
        """{"replay":{"outputPath":"${result.outputPath.jsonEscape()}","videoFrames":${result.videoFrames},"audioFrames":${result.audioFrames},"durationMilliseconds":${result.durationMilliseconds}}}"""

    fun exportFramesCompletedText(result: ExportFramesCommandResult.Completed): String =
        "Exported ${result.exportedFrames} of ${result.sourceFrames} frames to ${result.outputDirectory} as ${result.imageFormat}."

    fun exportFramesCompletedJson(result: ExportFramesCommandResult.Completed): String =
        """{"export":{"outputDirectory":"${result.outputDirectory.jsonEscape()}","sourceFrames":${result.sourceFrames},"exportedFrames":${result.exportedFrames},"imageFormat":"${result.imageFormat.jsonEscape()}"}}"""

    fun errorJson(code: String, message: String): String =
        """{"error":{"code":"${code.jsonEscape()}","message":"${message.jsonEscape()}"}}"""

    fun settingsInitializedText(result: SettingsCommandResult.Initialized): String =
        "Settings initialized at ${result.path} (${result.profileCount} profile(s))."

    fun settingsInitializedJson(result: SettingsCommandResult.Initialized): String =
        """{"settings":{"path":"${result.path.jsonEscape()}","initialized":true,"profileCount":${result.profileCount}}}"""

    fun settingsValidText(result: SettingsCommandResult.Valid): String =
        "Settings are valid at ${result.path} (${result.profileCount} profile(s))."

    fun settingsValidJson(result: SettingsCommandResult.Valid): String =
        """{"settings":{"path":"${result.path.jsonEscape()}","valid":true,"profileCount":${result.profileCount}}}"""
}

private fun CaptureSource.typeName(): String =
    when (this) {
        is CaptureSource.Application -> "application"
        is CaptureSource.Monitor -> "monitor"
        is CaptureSource.Region -> "region"
        is CaptureSource.Screen -> "screen"
        is CaptureSource.Window -> "window"
    }

private fun AudioSource.typeName(): String =
    when (this) {
        is AudioSource.Microphone -> "microphone"
        is AudioSource.SystemLoopback -> "system"
    }

private fun String.jsonEscape(): String =
    buildString {
        this@jsonEscape.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
