package io.aequicor.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CliParserTest {
    @Test
    fun parsesListSourcesJson() {
        val result = CliParser.parse(arrayOf("list-sources", "--json"))

        val parsed = assertIs<CliParseResult.Parsed>(result)
        assertEquals(CliCommand.ListSources(json = true), parsed.command)
    }

    @Test
    fun parsesLocalRecordingControlEndpointAndAction() {
        val recordResult = CliParser.parse(
            arrayOf("record", "screen", "--output", "out.mp4", "--control-endpoint", "control.json"),
        )
        val controlResult = CliParser.parse(
            arrayOf("control", "pause", "--endpoint", "control.json", "--json"),
        )

        val record = assertIs<CliCommand.Record>(assertIs<CliParseResult.Parsed>(recordResult).command)
        assertEquals("control.json", record.options.controlEndpointPath)
        assertEquals(
            CliCommand.Control(
                action = RecordingControlAction.Pause,
                endpointPath = "control.json",
                json = true,
            ),
            assertIs<CliParseResult.Parsed>(controlResult).command,
        )
    }

    @Test
    fun rejectsControlWithoutEndpoint() {
        val result = CliParser.parse(arrayOf("control", "status"))

        assertEquals(
            "control status requires --endpoint.",
            assertIs<CliParseResult.Invalid>(result).message,
        )
    }

    @Test
    fun parsesReplayControlEndpointAndSnapshotOutput() {
        val replayResult = CliParser.parse(
            arrayOf(
                "replay",
                "run",
                "screen",
                "--buffer",
                "5m",
                "--output",
                "final.mp4",
                "--control-endpoint",
                "replay-control.json",
            ),
        )
        val saveResult = CliParser.parse(
            arrayOf(
                "control",
                "save",
                "--endpoint",
                "replay-control.json",
                "--output",
                "snapshot.mp4",
                "--json",
            ),
        )

        val replay = assertIs<CliCommand.ReplayRun>(assertIs<CliParseResult.Parsed>(replayResult).command)
        assertEquals("replay-control.json", replay.options.controlEndpointPath)
        assertEquals(
            CliCommand.Control(
                action = RecordingControlAction.Save,
                endpointPath = "replay-control.json",
                outputPath = "snapshot.mp4",
                json = true,
            ),
            assertIs<CliParseResult.Parsed>(saveResult).command,
        )
    }

    @Test
    fun rejectsReplayControlSaveWithoutOutput() {
        val result = CliParser.parse(arrayOf("control", "save", "--endpoint", "control.json"))

        assertEquals(
            "control save requires --output.",
            assertIs<CliParseResult.Invalid>(result).message,
        )
    }

    @Test
    fun parsesRecordRegion() {
        val result = CliParser.parse(
            arrayOf(
                "record",
                "region",
                "--x",
                "10",
                "--y",
                "20",
                "--width",
                "1280",
                "--height",
                "720",
                "--output",
                "region.mp4",
                "--fps",
                "60",
                "--no-cursor",
                "--overwrite",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.Record>(parsed.command)
        assertEquals(RecordTarget.Region(x = 10, y = 20, width = 1280, height = 720), command.target)
        assertEquals("region.mp4", command.options.outputPath)
        assertEquals(60, command.options.fps)
        assertEquals(false, command.options.captureCursor)
        assertEquals(true, command.options.overwriteOutput)
    }

    @Test
    fun parsesRecordWindow() {
        val result = CliParser.parse(
            arrayOf(
                "record",
                "window",
                "--id",
                "window:win32:2a",
                "--output",
                "window.mp4",
                "--duration",
                "5s",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.Record>(parsed.command)
        assertEquals(RecordTarget.Window("window:win32:2a"), command.target)
        assertEquals("window.mp4", command.options.outputPath)
    }

    @Test
    fun systemAudioEndpointEnablesAndSelectsSystemAudio() {
        val result = CliParser.parse(
            arrayOf(
                "record",
                "screen",
                "--output",
                "screen.mp4",
                "--system-audio-endpoint",
                "wasapi:loopback:endpoint:headset",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.Record>(parsed.command)
        assertEquals(true, command.options.systemAudio)
        assertEquals("wasapi:loopback:endpoint:headset", command.options.systemAudioEndpoint)
    }

    @Test
    fun parsesIndependentAudioGainOverrides() {
        val result = CliParser.parse(
            arrayOf(
                "record",
                "screen",
                "--output",
                "screen.mp4",
                "--mic",
                "default",
                "--mic-gain",
                "75",
                "--system-audio-gain",
                "40",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.Record>(parsed.command)
        assertEquals(75, command.options.microphoneGainPercent)
        assertEquals(40, command.options.systemAudioGainPercent)
        assertEquals(true, command.options.systemAudio)
    }

    @Test
    fun rejectsOutOfRangeOrImplicitMicrophoneGain() {
        val outOfRange = CliParser.parse(
            arrayOf("record", "screen", "--output", "screen.mp4", "--mic", "default", "--mic-gain", "201"),
        )
        val implicitMicrophone = CliParser.parse(
            arrayOf("record", "screen", "--output", "screen.mp4", "--mic-gain", "50"),
        )

        assertEquals(
            "--mic-gain must be an integer from 0 to 200.",
            assertIs<CliParseResult.Invalid>(outOfRange).message,
        )
        assertEquals(
            "--mic-gain requires --mic or --settings.",
            assertIs<CliParseResult.Invalid>(implicitMicrophone).message,
        )
    }

    @Test
    fun rejectsSystemAudioEndpointWithoutValue() {
        val result = CliParser.parse(
            arrayOf("record", "screen", "--output", "screen.mp4", "--system-audio-endpoint"),
        )

        val invalid = assertIs<CliParseResult.Invalid>(result)
        assertEquals(
            "--system-audio-endpoint requires an endpoint id or display name.",
            invalid.message,
        )
    }

    @Test
    fun rejectsRecordWithoutOutput() {
        val result = CliParser.parse(arrayOf("record", "screen"))

        val invalid = assertIs<CliParseResult.Invalid>(result)
        assertEquals("record screen requires --output or --settings.", invalid.message)
    }

    @Test
    fun rejectsUnknownCommand() {
        val result = CliParser.parse(arrayOf("unknown"))

        val invalid = assertIs<CliParseResult.Invalid>(result)
        assertEquals("Unknown command: unknown", invalid.message)
    }

    @Test
    fun parsesSettingsInit() {
        val result = CliParser.parse(
            arrayOf("settings", "init", "--path", "local.settings.json", "--force", "--json"),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.Settings>(parsed.command)
        assertEquals(
            SettingsAction.Init(path = "local.settings.json", force = true, json = true),
            command.action,
        )
    }

    @Test
    fun parsesRecordProfileWithSettings() {
        val result = CliParser.parse(
            arrayOf(
                "record",
                "profile",
                "--settings",
                "mission-recorder.settings.json",
                "--profile",
                "default",
                "--duration",
                "1s",
                "--json",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.Record>(parsed.command)
        assertEquals(RecordTarget.Profile, command.target)
        assertEquals("mission-recorder.settings.json", command.options.settingsPath)
        assertEquals("default", command.options.profileId)
        assertEquals("1s", command.options.duration)
        assertEquals(true, command.options.json)
    }

    @Test
    fun parsesMicrophoneSelector() {
        val result = CliParser.parse(
            arrayOf(
                "record",
                "screen",
                "--output",
                "out.mrec",
                "--mic",
                "mic:test",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.Record>(parsed.command)
        assertEquals("mic:test", command.options.microphone)
    }

    @Test
    fun rejectsMicrophoneOptionWithoutSelector() {
        val result = CliParser.parse(
            arrayOf("record", "screen", "--output", "out.mrec", "--mic"),
        )

        val invalid = assertIs<CliParseResult.Invalid>(result)
        assertEquals("--mic requires a microphone id, display name, or 'default'.", invalid.message)
    }

    @Test
    fun parsesContactSheetFrameExport() {
        val result = CliParser.parse(
            arrayOf(
                "export-frames",
                "--input",
                "recording.mp4",
                "--output",
                "storyboard.png",
                "--interval",
                "2s",
                "--layout",
                "sheet",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.ExportFrames>(parsed.command)
        assertEquals(FrameExportLayout.ContactSheet, command.options.layout)
        assertEquals("2s", command.options.interval)
    }

    @Test
    fun parsesForegroundReplayRun() {
        val result = CliParser.parse(
            arrayOf(
                "replay",
                "run",
                "screen",
                "--buffer",
                "5m",
                "--run-for",
                "30m",
                "--output",
                "replay.mp4",
                "--fps",
                "60",
                "--mic",
                "default",
                "--json",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.ReplayRun>(parsed.command)
        assertEquals(RecordTarget.Screen, command.target)
        assertEquals("5m", command.options.bufferDuration)
        assertEquals("30m", command.options.runDuration)
        assertEquals("replay.mp4", command.options.outputPath)
        assertEquals(60, command.options.fps)
        assertEquals("default", command.options.microphone)
        assertEquals(true, command.options.json)
    }

    @Test
    fun parsesReplayRunWithoutDeadline() {
        val result = CliParser.parse(
            arrayOf(
                "replay",
                "run",
                "screen",
                "--buffer",
                "5m",
                "--output",
                "replay.mp4",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.ReplayRun>(parsed.command)
        assertEquals(null, command.options.runDuration)
    }

    @Test
    fun parsesReplaySystemAudioEndpoint() {
        val result = CliParser.parse(
            arrayOf(
                "replay",
                "run",
                "screen",
                "--buffer",
                "5m",
                "--output",
                "replay.mp4",
                "--system-audio-endpoint",
                "Headset",
                "--system-audio-gain",
                "35",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.ReplayRun>(parsed.command)
        assertEquals(true, command.options.systemAudio)
        assertEquals("Headset", command.options.systemAudioEndpoint)
        assertEquals(35, command.options.systemAudioGainPercent)
    }

    @Test
    fun rejectsReplayRunFlagWithoutDeadlineValue() {
        val result = CliParser.parse(
            arrayOf(
                "replay",
                "run",
                "screen",
                "--buffer",
                "5m",
                "--output",
                "replay.mp4",
                "--run-for",
            ),
        )

        val invalid = assertIs<CliParseResult.Invalid>(result)
        assertEquals("--run-for requires a positive duration such as 30m.", invalid.message)
    }

    @Test
    fun parsesWindowReplayRun() {
        val result = CliParser.parse(
            arrayOf(
                "replay",
                "run",
                "window",
                "--id",
                "window:win32:2a",
                "--buffer",
                "2m",
                "--run-for",
                "10m",
                "--output",
                "replay.mp4",
            ),
        )

        val parsed = assertIs<CliParseResult.Parsed>(result)
        val command = assertIs<CliCommand.ReplayRun>(parsed.command)
        assertEquals(RecordTarget.Window("window:win32:2a"), command.target)
    }
}
