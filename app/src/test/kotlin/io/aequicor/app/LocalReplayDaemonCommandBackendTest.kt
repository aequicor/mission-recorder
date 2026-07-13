package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.RecordingControlAction
import io.aequicor.cli.RecordingControlCommandResult
import io.aequicor.cli.RecordingControlStatus
import io.aequicor.cli.RecordTarget
import io.aequicor.cli.ReplayDaemonCommandResult
import io.aequicor.cli.ReplayRunOptions
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LocalReplayDaemonCommandBackendTest {
    @Test
    fun launchesReplayRunAndWaitsForBufferingEndpoint() = runTest {
        val directory = Files.createTempDirectory("mission-recorder-daemon-test")
        val endpoint = directory.resolve("replay.control.json")
        val output = directory.resolve("final.mp4")
        val process = FakeReplayDaemonProcess(processId = 42, alive = true)
        val launcher = CapturingReplayDaemonLauncher(process) { arguments ->
            Files.writeString(Path.of(arguments.valueAfter("--control-endpoint")), "descriptor")
        }
        val backend = LocalReplayDaemonCommandBackend(
            processLauncher = launcher,
            readinessProbe = ReplayDaemonReadinessProbe { endpointPath ->
                assertEquals(endpoint.toString(), endpointPath)
                RecordingControlCommandResult.Completed(
                    action = RecordingControlAction.Status,
                    status = RecordingControlStatus("replay-buffering", output.toString(), 0, 0, 0),
                )
            },
            startupTimeout = 1.seconds,
            pollingInterval = 10.milliseconds,
        )

        val result = backend.start(
            CliCommand.ReplayStart(
                target = RecordTarget.Screen,
                options = ReplayRunOptions(
                    bufferDuration = "5m",
                    outputPath = output.toString(),
                    fps = 60,
                    captureCursor = false,
                    showInputOverlay = true,
                    microphone = "default",
                    microphoneGainPercent = 125,
                    systemAudio = true,
                    systemAudioEndpoint = "speakers",
                    systemAudioGainPercent = 80,
                    controlEndpointPath = endpoint.toString(),
                ),
            ),
        )

        val started = assertIs<ReplayDaemonCommandResult.Started>(result)
        assertEquals(42L, started.processId)
        assertEquals(endpoint.toString(), started.endpointPath)
        assertEquals(
            listOf(
                "replay", "run", "screen",
                "--buffer", "5m",
                "--output", output.toString(),
                "--fps", "60",
                "--no-cursor",
                "--show-input",
                "--mic", "default",
                "--mic-gain", "125",
                "--system-audio",
                "--system-audio-endpoint", "speakers",
                "--system-audio-gain", "80",
                "--control-endpoint", endpoint.toString(),
            ),
            launcher.arguments,
        )
        assertFalse(process.destroyed)
    }

    @Test
    fun rejectsExistingEndpointBeforeLaunchingProcess() = runTest {
        val directory = Files.createTempDirectory("mission-recorder-daemon-stale-test")
        val endpoint = directory.resolve("replay.control.json")
        Files.writeString(endpoint, "stale")
        val launcher = CapturingReplayDaemonLauncher(FakeReplayDaemonProcess(42, alive = true))
        val backend = LocalReplayDaemonCommandBackend(processLauncher = launcher)

        val result = backend.start(startCommand(endpoint, directory.resolve("final.mp4")))

        assertTrue(assertIs<ReplayDaemonCommandResult.Rejected>(result).message.contains("already exists"))
        assertTrue(launcher.arguments.isEmpty())
    }

    @Test
    fun reportsLogWhenChildExitsBeforeEndpointIsReady() = runTest {
        val directory = Files.createTempDirectory("mission-recorder-daemon-failure-test")
        val endpoint = directory.resolve("replay.control.json")
        val process = FakeReplayDaemonProcess(processId = 77, alive = false)
        val launcher = CapturingReplayDaemonLauncher(process) { _, logPath ->
            Files.writeString(logPath, "permission denied")
        }
        val backend = LocalReplayDaemonCommandBackend(
            processLauncher = launcher,
            startupTimeout = 1.seconds,
            pollingInterval = 10.milliseconds,
        )

        val result = backend.start(startCommand(endpoint, directory.resolve("final.mp4")))

        val failed = assertIs<ReplayDaemonCommandResult.Failed>(result)
        assertTrue(failed.message.contains("process 77"))
        assertTrue(failed.message.contains("permission denied"))
        assertTrue(process.destroyed)
    }

    @Test
    fun cancellationDestroysChildWaitingForReadiness() = runTest {
        val directory = Files.createTempDirectory("mission-recorder-daemon-cancel-test")
        val process = FakeReplayDaemonProcess(processId = 88, alive = true)
        val backend = LocalReplayDaemonCommandBackend(
            processLauncher = CapturingReplayDaemonLauncher(process),
            startupTimeout = 60.seconds,
            pollingInterval = 10.milliseconds,
        )

        val start = async {
            backend.start(startCommand(directory.resolve("replay.control.json"), directory.resolve("final.mp4")))
        }
        runCurrent()

        start.cancelAndJoin()

        assertTrue(process.destroyed)
    }

    @Test
    fun productionLauncherStartsCurrentCliWithoutCapture() = runBlocking {
        val logPath = Files.createTempDirectory("mission-recorder-daemon-launcher-test").resolve("help.log")
        val process = CurrentJvmReplayDaemonProcessLauncher().launch(listOf("--help"), logPath)

        withTimeout(10.seconds) {
            while (process.isAlive) delay(20.milliseconds)
        }

        assertTrue(Files.readString(logPath).contains("Mission Recorder"))
    }

    private fun startCommand(endpoint: Path, output: Path) = CliCommand.ReplayStart(
        target = RecordTarget.Screen,
        options = ReplayRunOptions(
            bufferDuration = "5m",
            outputPath = output.toString(),
            controlEndpointPath = endpoint.toString(),
        ),
    )
}

private class CapturingReplayDaemonLauncher(
    private val process: ReplayDaemonProcess,
    private val onLaunch: (List<String>, Path) -> Unit = { _, _ -> },
) : ReplayDaemonProcessLauncher {
    var arguments: List<String> = emptyList()
        private set

    constructor(
        process: ReplayDaemonProcess,
        onArguments: (List<String>) -> Unit,
    ) : this(process, { arguments, _ -> onArguments(arguments) })

    override fun launch(arguments: List<String>, logPath: Path): ReplayDaemonProcess {
        this.arguments = arguments
        onLaunch(arguments, logPath)
        return process
    }
}

private class FakeReplayDaemonProcess(
    override val processId: Long,
    alive: Boolean,
) : ReplayDaemonProcess {
    override var isAlive: Boolean = alive
        private set
    var destroyed: Boolean = false
        private set

    override fun destroy() {
        destroyed = true
        isAlive = false
    }
}

private fun List<String>.valueAfter(option: String): String = this[indexOf(option) + 1]
