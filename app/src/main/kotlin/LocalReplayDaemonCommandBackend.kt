package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.RecordingControlAction
import io.aequicor.cli.RecordingControlCommandResult
import io.aequicor.cli.ReplayDaemonCommandBackend
import io.aequicor.cli.ReplayDaemonCommandResult
import io.aequicor.cli.RecordTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class LocalReplayDaemonCommandBackend(
    private val processLauncher: ReplayDaemonProcessLauncher = CurrentJvmReplayDaemonProcessLauncher(),
    private val readinessProbe: ReplayDaemonReadinessProbe = LocalReplayDaemonReadinessProbe,
    private val startupTimeout: Duration = DEFAULT_STARTUP_TIMEOUT,
    private val pollingInterval: Duration = DEFAULT_POLLING_INTERVAL,
) : ReplayDaemonCommandBackend {
    override suspend fun start(command: CliCommand.ReplayStart): ReplayDaemonCommandResult {
        val endpoint = command.options.controlEndpointPath?.toAbsoluteNormalizedPath()
            ?: return ReplayDaemonCommandResult.Rejected("replay start requires a valid --control-endpoint path.")
        val output = command.options.outputPath.toAbsoluteNormalizedPath()
            ?: return ReplayDaemonCommandResult.Rejected("replay start requires a valid --output path.")
        if (!output.extension.equals("mp4", ignoreCase = true)) {
            return ReplayDaemonCommandResult.Rejected("Replay daemon output must use the .mp4 extension.")
        }
        if (endpoint == output) {
            return ReplayDaemonCommandResult.Rejected("Replay control endpoint must differ from the output path.")
        }
        if (Files.exists(endpoint)) {
            return ReplayDaemonCommandResult.Rejected(
                "Replay control endpoint already exists: $endpoint. Remove a stale endpoint or choose another path.",
            )
        }
        if (Files.exists(output)) {
            return ReplayDaemonCommandResult.Rejected(
                "Replay daemon output already exists: $output. Choose a new final output path.",
            )
        }
        runCatching {
            endpoint.parent?.let(Files::createDirectories)
            output.parent?.let(Files::createDirectories)
        }.getOrElse { failure ->
            return ReplayDaemonCommandResult.Failed(
                "Could not prepare replay daemon directories: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        val logPath = endpoint.resolveSibling("${endpoint.name}.daemon.log")
        val arguments = command.toRunArguments(endpoint, output)
        val process = runCatching { processLauncher.launch(arguments, logPath) }.getOrElse { failure ->
            return ReplayDaemonCommandResult.Failed(
                "Could not launch replay daemon: ${failure.message ?: failure::class.simpleName}.",
            )
        }

        val ready = try {
            withTimeoutOrNull(startupTimeout) {
                while (process.isAlive) {
                    if (Files.isRegularFile(endpoint)) {
                        val status = try {
                            readinessProbe.status(endpoint.toString())
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            null
                        }
                        when (status) {
                            is RecordingControlCommandResult.Completed -> {
                                if (status.status.state == "replay-buffering") return@withTimeoutOrNull true
                            }
                            is RecordingControlCommandResult.Failed,
                            is RecordingControlCommandResult.Rejected,
                            null,
                            -> Unit
                        }
                    }
                    delay(pollingInterval)
                }
                false
            } == true
        } catch (cancellation: CancellationException) {
            process.destroy()
            throw cancellation
        }

        if (!ready) {
            process.destroy()
            return ReplayDaemonCommandResult.Failed(
                buildString {
                    append("Replay daemon process ${process.processId} did not become ready")
                    append(if (process.isAlive) " before timeout." else " before exiting.")
                    readLogTail(logPath)?.let { log -> append(" Log: $log") }
                },
            )
        }
        return ReplayDaemonCommandResult.Started(
            processId = process.processId,
            endpointPath = endpoint.toString(),
            outputPath = output.toString(),
        )
    }
}

internal fun interface ReplayDaemonProcessLauncher {
    fun launch(arguments: List<String>, logPath: Path): ReplayDaemonProcess
}

internal interface ReplayDaemonProcess {
    val processId: Long
    val isAlive: Boolean
    fun destroy()
}

internal fun interface ReplayDaemonReadinessProbe {
    suspend fun status(endpointPath: String): RecordingControlCommandResult
}

private object LocalReplayDaemonReadinessProbe : ReplayDaemonReadinessProbe {
    private val backend = LocalRecordingControlCommandBackend()

    override suspend fun status(endpointPath: String): RecordingControlCommandResult = backend.control(
        CliCommand.Control(
            action = RecordingControlAction.Status,
            endpointPath = endpointPath,
            json = false,
        ),
    )
}

internal class CurrentJvmReplayDaemonProcessLauncher : ReplayDaemonProcessLauncher {
    override fun launch(arguments: List<String>, logPath: Path): ReplayDaemonProcess {
        logPath.parent?.let(Files::createDirectories)
        val command = currentApplicationCommand() + arguments
        val process = ProcessBuilder(command)
            .directory(Path.of(System.getProperty("user.dir")).toFile())
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
            .redirectErrorStream(true)
            .start()
        return JvmReplayDaemonProcess(process)
    }

    private fun currentApplicationCommand(): List<String> {
        val processCommand = ProcessHandle.current().info().command().orElse("")
        val executableName = runCatching { Path.of(processCommand).fileName.toString().lowercase() }.getOrDefault("")
        if (processCommand.isNotBlank() && executableName !in JAVA_EXECUTABLE_NAMES) {
            return listOf(processCommand)
        }
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (isWindows()) "java.exe" else "java",
        ).toString()
        val classpath = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .joinToString(File.pathSeparator) { entry ->
                runCatching { Path.of(entry).toAbsolutePath().normalize().toString() }.getOrDefault(entry)
            }
        return listOf(
            javaExecutable,
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            classpath,
            APP_MAIN_CLASS,
        )
    }

    private fun nullDevice(): File = File(if (isWindows()) "NUL" else "/dev/null")
}

private class JvmReplayDaemonProcess(
    private val process: Process,
) : ReplayDaemonProcess {
    override val processId: Long get() = process.pid()
    override val isAlive: Boolean get() = process.isAlive
    override fun destroy() {
        process.destroy()
    }
}

private fun CliCommand.ReplayStart.toRunArguments(endpoint: Path, output: Path): List<String> = buildList {
    add("replay")
    add("run")
    when (val selectedTarget = target) {
        RecordTarget.Screen -> add("screen")
        RecordTarget.Profile -> error("Replay daemon does not support profile as a direct capture target.")
        is RecordTarget.Monitor -> {
            add("monitor")
            add("--id")
            add(selectedTarget.id)
        }
        is RecordTarget.Region -> {
            add("region")
            addAll(
                listOf(
                    "--x", selectedTarget.x.toString(),
                    "--y", selectedTarget.y.toString(),
                    "--width", selectedTarget.width.toString(),
                    "--height", selectedTarget.height.toString(),
                ),
            )
        }
        is RecordTarget.Window -> {
            add("window")
            add("--id")
            add(selectedTarget.id)
        }
        is RecordTarget.Application -> {
            add("app")
            add("--id")
            add(selectedTarget.id)
        }
    }
    addAll(listOf("--buffer", options.bufferDuration, "--output", output.toString()))
    options.fps?.let { addAll(listOf("--fps", it.toString())) }
    options.captureCursor?.let { add(if (it) "--cursor" else "--no-cursor") }
    options.showInputOverlay?.let { add(if (it) "--show-input" else "--hide-input") }
    options.microphone?.let { addAll(listOf("--mic", it)) }
    options.microphoneGainPercent?.let { addAll(listOf("--mic-gain", it.toString())) }
    if (options.systemAudio) add("--system-audio")
    options.systemAudioEndpoint?.let { addAll(listOf("--system-audio-endpoint", it)) }
    options.systemAudioGainPercent?.let { addAll(listOf("--system-audio-gain", it.toString())) }
    addAll(listOf("--control-endpoint", endpoint.toString()))
}

private fun String.toAbsoluteNormalizedPath(): Path? =
    runCatching { Path.of(this).toAbsolutePath().normalize() }.getOrNull()

private fun readLogTail(path: Path): String? = runCatching {
    if (!Files.isRegularFile(path)) return@runCatching null
    Files.readAllLines(path).takeLast(LOG_TAIL_LINES).joinToString(" | ").take(MAX_LOG_TAIL_CHARS)
}.getOrNull()?.takeIf(String::isNotBlank)

private fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

private val DEFAULT_STARTUP_TIMEOUT = 60.seconds
private val DEFAULT_POLLING_INTERVAL = 100.milliseconds
private val JAVA_EXECUTABLE_NAMES = setOf("java", "java.exe", "javaw", "javaw.exe")
private const val APP_MAIN_CLASS = "io.aequicor.app.AppKt"
private const val LOG_TAIL_LINES = 8
private const val MAX_LOG_TAIL_CHARS = 1_500
