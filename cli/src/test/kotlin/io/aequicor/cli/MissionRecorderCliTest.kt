package io.aequicor.cli

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissionRecorderCliTest {
    @Test
    fun listsSourcesAsText() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(
                    CaptureSource.Screen(
                        id = CaptureSourceId("screen:primary"),
                        displayName = "Primary screen",
                    ),
                ),
            ),
            audioSourceRepository = EmptyTestAudioSourceRepository,
        )

        val code = cli.run(arrayOf("list-sources"), stdout, stderr)

        assertEquals(CliExitCode.Success.code, code)
        assertTrue(stdout.toString().contains("screen screen:primary Primary screen"))
        assertEquals("", stderr.toString())
    }

    @Test
    fun listsSourcesAsJsonWithoutDiagnostics() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(
                    CaptureSource.Monitor(
                        id = CaptureSourceId("monitor:1"),
                        displayName = "Display 1",
                        index = 1,
                    ),
                ),
            ),
            audioSourceRepository = EmptyTestAudioSourceRepository,
        )

        val code = cli.run(arrayOf("list-sources", "--json"), stdout, stderr)

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            """{"sources":[{"type":"monitor","id":"monitor:1","displayName":"Display 1"}]}""" + "\n",
            stdout.toString(),
        )
        assertEquals("", stderr.toString())
    }

    @Test
    fun returnsUsageForInvalidCommand() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
        )

        val code = cli.run(arrayOf("record", "screen"), stdout, stderr)

        assertEquals(CliExitCode.Usage.code, code)
        assertEquals("", stdout.toString())
        assertTrue(stderr.toString().contains("record screen requires --output or --settings."))
    }

    @Test
    fun returnsUnsupportedForParsedRecordCommand() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
        )

        val code = cli.run(arrayOf("record", "screen", "--output", "out.mp4"), stdout, stderr)

        assertEquals(CliExitCode.Unsupported.code, code)
        assertEquals("", stdout.toString())
        assertTrue(stderr.toString().contains("record is parsed"))
    }

    @Test
    fun runsInjectedRecordingBackend() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
            recordingCommandBackend = object : RecordingCommandBackend {
                override suspend fun record(command: CliCommand.Record): RecordingCommandResult =
                    RecordingCommandResult.Completed(
                        outputPath = command.options.outputPath ?: "out.mrec",
                        videoFrames = 3,
                        audioFrames = 0,
                    )
            },
        )

        val code = cli.run(
            arrayOf("record", "screen", "--output", "out.mrec", "--duration", "1s", "--json"),
            stdout,
            stderr,
        )

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            """{"recording":{"outputPath":"out.mrec","videoFrames":3,"audioFrames":0}}""" + "\n",
            stdout.toString(),
        )
        assertEquals("", stderr.toString())
    }

    @Test
    fun runsInjectedRecordingControlBackendAsJson() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
            recordingControlCommandBackend = object : RecordingControlCommandBackend {
                override suspend fun control(command: CliCommand.Control): RecordingControlCommandResult =
                    RecordingControlCommandResult.Completed(
                        action = command.action,
                        status = RecordingControlStatus(
                            state = "paused",
                            outputPath = "out.mp4",
                            durationMilliseconds = 1_250,
                            videoFrames = 30,
                            audioFrames = 10,
                            droppedFrames = 2,
                        ),
                    )
            },
        )

        val code = cli.run(
            arrayOf("control", "status", "--endpoint", "control.json", "--json"),
            stdout,
            stderr,
        )

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            """{"control":{"action":"status","state":"paused","outputPath":"out.mp4","durationMilliseconds":1250,"videoFrames":30,"audioFrames":10,"droppedFrames":2,"effectiveFramesPerSecond":24.0,"message":null}}""" + "\n",
            stdout.toString(),
        )
        assertEquals("", stderr.toString())
    }

    @Test
    fun startsInjectedReplayDaemonAsJson() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
            replayDaemonCommandBackend = object : ReplayDaemonCommandBackend {
                override suspend fun start(command: CliCommand.ReplayStart): ReplayDaemonCommandResult =
                    ReplayDaemonCommandResult.Started(
                        processId = 42,
                        endpointPath = "replay.control.json",
                        outputPath = "final.mp4",
                    )
            },
        )

        val code = cli.run(
            arrayOf(
                "replay", "start", "screen", "--buffer", "5m", "--output", "final.mp4",
                "--control-endpoint", "replay.control.json", "--json",
            ),
            stdout,
            stderr,
        )

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            """{"replayDaemon":{"state":"started","processId":42,"endpointPath":"replay.control.json","outputPath":"final.mp4"}}""" + "\n",
            stdout.toString(),
        )
        assertEquals("", stderr.toString())
    }

    @Test
    fun replaySaveUsesLocalControlBackend() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var received: CliCommand.Control? = null
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
            recordingControlCommandBackend = object : RecordingControlCommandBackend {
                override suspend fun control(command: CliCommand.Control): RecordingControlCommandResult {
                    received = command
                    return RecordingControlCommandResult.Completed(
                        action = command.action,
                        status = RecordingControlStatus("replay-buffering", "snapshot.mp4", 1_000, 30, 0),
                        message = "Replay snapshot saved.",
                    )
                }
            },
        )

        val code = cli.run(
            arrayOf(
                "replay", "save", "--endpoint", "replay.control.json", "--output", "snapshot.mp4",
            ),
            stdout,
            stderr,
        )

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            CliCommand.Control(
                RecordingControlAction.Save,
                endpointPath = "replay.control.json",
                outputPath = "snapshot.mp4",
                json = false,
            ),
            received,
        )
        assertTrue(stdout.toString().contains("Replay snapshot saved."))
        assertEquals("", stderr.toString())
    }

    @Test
    fun runsInjectedExportFramesBackend() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
            exportFramesCommandBackend = object : ExportFramesCommandBackend {
                override suspend fun exportFrames(command: CliCommand.ExportFrames): ExportFramesCommandResult =
                    ExportFramesCommandResult.Completed(
                        outputDirectory = command.options.outputDirectory,
                        sourceFrames = 4,
                        exportedFrames = 2,
                        imageFormat = command.options.imageFormat,
                    )
            },
        )

        val code = cli.run(
            arrayOf(
                "export-frames",
                "--input",
                "recording.mrec",
                "--output",
                "frames",
                "--fps",
                "2",
                "--json",
            ),
            stdout,
            stderr,
        )

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            """{"export":{"outputDirectory":"frames","sourceFrames":4,"exportedFrames":2,"imageFormat":"png"}}""" + "\n",
            stdout.toString(),
        )
        assertEquals("", stderr.toString())
    }

    @Test
    fun runsInjectedReplayBackend() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
            replayCommandBackend = object : ReplayCommandBackend {
                override suspend fun run(command: CliCommand.ReplayRun): ReplayCommandResult =
                    ReplayCommandResult.Completed(
                        outputPath = command.options.outputPath,
                        videoFrames = 120,
                        audioFrames = 60,
                        durationMilliseconds = 5_000,
                    )
            },
        )

        val code = cli.run(
            arrayOf(
                "replay",
                "run",
                "screen",
                "--buffer",
                "5s",
                "--output",
                "replay.mp4",
                "--json",
            ),
            stdout,
            stderr,
        )

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            """{"replay":{"outputPath":"replay.mp4","videoFrames":120,"audioFrames":60,"durationMilliseconds":5000}}""" + "\n",
            stdout.toString(),
        )
        assertEquals("", stderr.toString())
    }

    @Test
    fun runsInjectedSettingsBackend() = runTest {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = MissionRecorderCli(
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyTestAudioSourceRepository,
            settingsCommandBackend = object : SettingsCommandBackend {
                override suspend fun handle(command: CliCommand.Settings): SettingsCommandResult =
                    SettingsCommandResult.Initialized(path = command.action.path, profileCount = 1)
            },
        )

        val code = cli.run(
            arrayOf("settings", "init", "--path", "settings.json", "--json"),
            stdout,
            stderr,
        )

        assertEquals(CliExitCode.Success.code, code)
        assertEquals(
            """{"settings":{"path":"settings.json","initialized":true,"profileCount":1}}""" + "\n",
            stdout.toString(),
        )
        assertEquals("", stderr.toString())
    }
}

private class StaticCaptureSourceRepository(
    private val sources: List<CaptureSource>,
) : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> = sources
}

private data object EmptyTestAudioSourceRepository : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest) = emptyList<io.aequicor.capture.core.AudioSource>()
}
