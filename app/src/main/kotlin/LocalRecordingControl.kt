package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.RecordingControlAction
import io.aequicor.cli.RecordingControlCommandBackend
import io.aequicor.cli.RecordingControlCommandResult
import io.aequicor.cli.RecordingControlRequest
import io.aequicor.cli.RecordingControlStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalRecordingControlServer private constructor(
    private val endpointPath: Path,
    private val descriptor: ControlEndpointDescriptor,
    private val serverSocket: ServerSocket,
    private val handler: suspend (RecordingControlRequest) -> RecordingControlCommandResult,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val worker = Thread(::acceptRequests, "mission-recorder-cli-control").apply {
        isDaemon = true
    }

    fun start() {
        worker.start()
        try {
            writeEndpointDescriptor(endpointPath, descriptor)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        runCatching { serverSocket.close() }
        if (Thread.currentThread() !== worker) {
            worker.join(SHUTDOWN_TIMEOUT_MILLIS)
        }
        deleteOwnedDescriptor()
    }

    private fun acceptRequests() {
        while (!closed.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Throwable) {
                if (closed.get()) return
                continue
            }
            try {
                socket.use(::handleRequest)
            } catch (_: IOException) {
                if (closed.get()) return
            }
        }
    }

    private fun handleRequest(socket: Socket) {
        socket.soTimeout = REQUEST_TIMEOUT_MILLIS
        val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
        val request = runCatching { CONTROL_JSON.decodeFromString<ControlWireRequest>(requestLine) }.getOrNull()
        val response = when {
            request == null -> ControlWireResponse(errorKind = "validation", error = "Invalid control request.")
            request.token != descriptor.token ->
                ControlWireResponse(errorKind = "validation", error = "Invalid control token.")
            else -> request.action.toControlAction()?.let { action ->
                runCatching {
                    runBlocking { handler(RecordingControlRequest(action, request.outputPath)) }
                }.fold(::toWireResponse) { failure ->
                    ControlWireResponse(
                        errorKind = "failure",
                        error = failure.message ?: "Control action failed.",
                    )
                }
            } ?: ControlWireResponse(
                errorKind = "validation",
                error = "Unknown control action: ${request.action}",
            )
        }
        socket.getOutputStream().bufferedWriter().use { writer ->
            writer.appendLine(CONTROL_JSON.encodeToString(response))
        }
    }

    private fun deleteOwnedDescriptor() {
        val current = runCatching {
            CONTROL_JSON.decodeFromString<ControlEndpointDescriptor>(Files.readString(endpointPath))
        }.getOrNull()
        if (current?.token == descriptor.token) {
            runCatching { Files.deleteIfExists(endpointPath) }
        }
    }

    companion object {
        fun open(
            endpointPath: String,
            handler: suspend (RecordingControlRequest) -> RecordingControlCommandResult,
        ): LocalRecordingControlServer {
            val path = Path.of(endpointPath).toAbsolutePath().normalize()
            require(!Files.exists(path)) {
                "Control endpoint already exists: $path. Remove a stale endpoint or choose another path."
            }
            path.parent?.let(Files::createDirectories)
            val serverSocket = ServerSocket(0, CONTROL_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
            val descriptor = ControlEndpointDescriptor(
                host = LOOPBACK_HOST,
                port = serverSocket.localPort,
                token = UUID.randomUUID().toString(),
                processId = ProcessHandle.current().pid(),
            )
            return LocalRecordingControlServer(path, descriptor, serverSocket, handler).also { it.start() }
        }
    }
}

internal class LocalRecordingControlCommandBackend : RecordingControlCommandBackend {
    override suspend fun control(command: CliCommand.Control): RecordingControlCommandResult {
        val endpointPath = runCatching { Path.of(command.endpointPath).toAbsolutePath().normalize() }
            .getOrElse { return RecordingControlCommandResult.Rejected("Invalid control endpoint path: ${it.message}") }
        if (!Files.isRegularFile(endpointPath)) {
            return RecordingControlCommandResult.Rejected("Control endpoint does not exist: $endpointPath")
        }
        val descriptor = runCatching {
            CONTROL_JSON.decodeFromString<ControlEndpointDescriptor>(Files.readString(endpointPath))
        }.getOrElse {
            return RecordingControlCommandResult.Rejected("Invalid control endpoint: ${it.message}")
        }
        if (
            descriptor.version != CONTROL_PROTOCOL_VERSION ||
            descriptor.host != LOOPBACK_HOST ||
            descriptor.port !in 1..65535 ||
            descriptor.token.isBlank()
        ) {
            return RecordingControlCommandResult.Rejected("Control endpoint contains invalid connection data.")
        }
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), descriptor.port),
                    CONNECT_TIMEOUT_MILLIS,
                )
                socket.soTimeout = REQUEST_TIMEOUT_MILLIS
                socket.getOutputStream().bufferedWriter().apply {
                    appendLine(
                        CONTROL_JSON.encodeToString(
                            ControlWireRequest(
                                token = descriptor.token,
                                action = command.action.wireName(),
                                outputPath = command.outputPath,
                            ),
                        ),
                    )
                    flush()
                }
                val responseLine = socket.getInputStream().bufferedReader().readLine()
                    ?: error("Control process closed the connection without a response.")
                CONTROL_JSON.decodeFromString<ControlWireResponse>(responseLine).toCommandResult(command.action)
            }
        }.getOrElse { failure ->
            RecordingControlCommandResult.Failed(
                "Could not contact recording process ${descriptor.processId}: " +
                    (failure.message ?: failure::class.simpleName ?: "control connection failed"),
            )
        }
    }
}

@Serializable
private data class ControlEndpointDescriptor(
    val version: Int = CONTROL_PROTOCOL_VERSION,
    val host: String,
    val port: Int,
    val token: String,
    val processId: Long,
)

@Serializable
private data class ControlWireRequest(
    val token: String,
    val action: String,
    val outputPath: String? = null,
)

@Serializable
private data class ControlWireResponse(
    val state: String? = null,
    val outputPath: String? = null,
    val durationMilliseconds: Long = 0,
    val videoFrames: Long = 0,
    val audioFrames: Long = 0,
    val droppedFrames: Long = 0,
    val message: String? = null,
    val errorKind: String? = null,
    val error: String? = null,
)

private fun toWireResponse(result: RecordingControlCommandResult): ControlWireResponse = when (result) {
    is RecordingControlCommandResult.Completed -> ControlWireResponse(
        state = result.status.state,
        outputPath = result.status.outputPath,
        durationMilliseconds = result.status.durationMilliseconds,
        videoFrames = result.status.videoFrames,
        audioFrames = result.status.audioFrames,
        droppedFrames = result.status.droppedFrames,
        message = result.message,
    )
    is RecordingControlCommandResult.Rejected ->
        ControlWireResponse(errorKind = "validation", error = result.message)
    is RecordingControlCommandResult.Failed ->
        ControlWireResponse(errorKind = "failure", error = result.message)
}

private fun ControlWireResponse.toCommandResult(action: RecordingControlAction): RecordingControlCommandResult =
    error?.let { message ->
        if (errorKind == "failure") {
            RecordingControlCommandResult.Failed(message)
        } else {
            RecordingControlCommandResult.Rejected(message)
        }
    } ?: RecordingControlCommandResult.Completed(
        action = action,
        status = RecordingControlStatus(
            state = state ?: "unknown",
            outputPath = outputPath,
            durationMilliseconds = durationMilliseconds,
            videoFrames = videoFrames,
            audioFrames = audioFrames,
            droppedFrames = droppedFrames,
        ),
        message = message,
    )

private fun RecordingControlAction.wireName(): String = name.lowercase()

private fun String.toControlAction(): RecordingControlAction? =
    RecordingControlAction.entries.firstOrNull { it.wireName() == this }

private fun writeEndpointDescriptor(path: Path, descriptor: ControlEndpointDescriptor) {
    val temporary = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, CONTROL_JSON.encodeToString(descriptor))
            runCatching {
                Files.setPosixFilePermissions(
                    temporary,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private val CONTROL_JSON = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}
private const val CONTROL_PROTOCOL_VERSION = 3
private const val LOOPBACK_HOST = "127.0.0.1"
private const val CONTROL_BACKLOG = 4
private const val CONNECT_TIMEOUT_MILLIS = 3_000
private const val REQUEST_TIMEOUT_MILLIS = 120_000
private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L
