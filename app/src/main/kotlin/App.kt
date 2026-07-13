package io.aequicor.app

import io.aequicor.audio.core.AudioCaptureRoute
import io.aequicor.audio.core.AudioDriftCorrectionMonitor
import io.aequicor.audio.core.DriftCorrectingAudioCaptureAdapter
import io.aequicor.audio.core.MixingAudioCaptureAdapter
import io.aequicor.audio.desktop.javasound.JavaSoundAudioCaptureAdapter
import io.aequicor.audio.desktop.javasound.JavaSoundAudioSourceRepository
import io.aequicor.audio.linux.pulse.LinuxPulseAudioAdapterFactory
import io.aequicor.audio.windows.wasapi.WindowsWasapiAudioAdapterFactory
import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.desktop.awt.AwtCaptureSourceRepository
import io.aequicor.capture.desktop.awt.AwtVideoCaptureAdapter
import io.aequicor.capture.linux.x11.LinuxX11CaptureAdapterFactory
import io.aequicor.capture.macos.coregraphics.MacCaptureAdapterFactory
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.CompositeAudioSourceRepository
import io.aequicor.capture.platform.CompositeCaptureSourceRepository
import io.aequicor.capture.platform.JvmDesktopPermissionGateway
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.RoutingVideoCaptureAdapter
import io.aequicor.capture.platform.VideoCaptureRoute
import io.aequicor.capture.windows.jna.WindowsCaptureAdapterFactory
import io.aequicor.cli.MissionRecorderCli
import io.aequicor.export.FrameSequenceExporter
import io.aequicor.media.desktop.ffmpeg.FfmpegMediaEncoder
import io.aequicor.media.desktop.ffmpeg.FfmpegSegmentedReplayBuffer
import io.aequicor.media.desktop.ffmpeg.FfmpegStoryboardExporter
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val captureAdapters = createDesktopCaptureAdapters()
    val captureSourceRepository = captureAdapters.sourceRepository
    val audioAdapters = createDesktopAudioAdapters()
    val permissionGateway = captureAdapters.permissionGateway ?: JvmDesktopPermissionGateway(
        linuxSystemAudioAvailable = audioAdapters.systemAudioSupported,
    )
    val recordingBackend = DesktopRecordingCommandBackend(
        captureSourceRepository = captureSourceRepository,
        videoCaptureAdapter = captureAdapters.videoCaptureAdapter,
        audioSourceRepository = audioAdapters.sourceRepository,
        audioCaptureAdapter = audioAdapters.captureAdapter,
        recordingProfileLoader = LocalRecordingProfileLoader(),
        mediaEncoderFactory = { FfmpegMediaEncoder() },
        permissionGateway = permissionGateway,
    )
    val replayBackend = DesktopReplayCommandBackend(
        captureSourceRepository = captureSourceRepository,
        videoCaptureAdapter = captureAdapters.videoCaptureAdapter,
        audioSourceRepository = audioAdapters.sourceRepository,
        audioCaptureAdapter = audioAdapters.captureAdapter,
        mediaBufferFactory = {
            FfmpegSegmentedReplayBuffer(
                Path.of(System.getProperty("java.io.tmpdir"), "Mission Recorder", "replay-buffer"),
            )
        },
        permissionGateway = permissionGateway,
    )
    val shutdownRequested = AtomicBoolean(false)
    val shutdownHook = Thread(
        {
            shutdownRequested.set(true)
            runCatching {
                runBlocking {
                    recordingBackend.requestStop()
                    replayBackend.requestSaveAndStop()
                }
            }.onFailure { failure ->
                System.err.println("Failed to finalize the active recording during shutdown: ${failure.message}")
            }
            audioAdapters.close()
            captureAdapters.close()
        },
        "mission-recorder-shutdown",
    )
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    val exitCode = try {
        runBlocking {
            MissionRecorderCli(
                captureSourceRepository = captureSourceRepository,
                audioSourceRepository = audioAdapters.sourceRepository,
                recordingCommandBackend = recordingBackend,
                recordingControlCommandBackend = LocalRecordingControlCommandBackend(),
                replayCommandBackend = replayBackend,
                replayDaemonCommandBackend = LocalReplayDaemonCommandBackend(),
                exportFramesCommandBackend = DesktopExportFramesCommandBackend(
                    legacyFrameSequenceBackend = FrameSequenceExportFramesCommandBackend(
                        exporter = FrameSequenceExporter(),
                    ),
                    storyboardExporter = FfmpegStoryboardExporter(),
                ),
                settingsCommandBackend = LocalSettingsCommandBackend(),
            ).run(args = args, stdout = System.out, stderr = System.err)
        }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        audioAdapters.close()
        captureAdapters.close()
    }
    if (!shutdownRequested.get()) {
        exitProcess(exitCode)
    }
}

private fun createDesktopAudioAdapters(): DesktopAudioAdapters {
    val driftCorrectionMonitor = AudioDriftCorrectionMonitor()
    val microphoneRepository = JavaSoundAudioSourceRepository()
    val microphoneAdapter = DriftCorrectingAudioCaptureAdapter(
        delegate = JavaSoundAudioCaptureAdapter(),
        clock = SystemMediaClock,
        monitor = driftCorrectionMonitor,
    )
    val windowsAdapters = WindowsWasapiAudioAdapterFactory.createIfSupported()
    val linuxAdapters = LinuxPulseAudioAdapterFactory.createIfSupported()
    val repositories = listOfNotNull(
        microphoneRepository,
        windowsAdapters?.sourceRepository,
        linuxAdapters?.sourceRepository,
    )
    val routes = buildList {
        add(AudioCaptureRoute(matches = { source -> source is AudioSource.Microphone }, adapter = microphoneAdapter))
        windowsAdapters?.let { windows ->
            add(
                AudioCaptureRoute(
                    matches = { source -> source is AudioSource.SystemLoopback },
                    adapter = DriftCorrectingAudioCaptureAdapter(
                        delegate = windows.captureAdapter,
                        clock = SystemMediaClock,
                        monitor = driftCorrectionMonitor,
                    ),
                ),
            )
        }
        linuxAdapters?.let { linux ->
            add(
                AudioCaptureRoute(
                    matches = { source -> source is AudioSource.SystemLoopback },
                    adapter = DriftCorrectingAudioCaptureAdapter(
                        delegate = linux.captureAdapter,
                        clock = SystemMediaClock,
                        monitor = driftCorrectionMonitor,
                    ),
                ),
            )
        }
    }
    return DesktopAudioAdapters(
        sourceRepository = if (repositories.size == 1) repositories.single() else CompositeAudioSourceRepository(repositories),
        captureAdapter = MixingAudioCaptureAdapter(routes),
        driftCorrectionMonitor = driftCorrectionMonitor,
        systemAudioSupported = windowsAdapters != null || linuxAdapters != null,
        closeAction = {
            windowsAdapters?.close()
            linuxAdapters?.close()
        },
    )
}

private fun createDesktopCaptureAdapters(): DesktopCaptureAdapters {
    val awtRepository = AwtCaptureSourceRepository()
    val awtVideoAdapter = AwtVideoCaptureAdapter()
    val windowsAdapters = WindowsCaptureAdapterFactory.createIfSupported()
    val linuxAdapters = LinuxX11CaptureAdapterFactory.createIfSupported()
    val macAdapters = MacCaptureAdapterFactory.createIfSupported()
    val nativeRepository = windowsAdapters?.sourceRepository
        ?: linuxAdapters?.sourceRepository
        ?: macAdapters?.sourceRepository
        ?: return DesktopCaptureAdapters(awtRepository, awtVideoAdapter)
    val nativeVideoAdapter = windowsAdapters?.videoCaptureAdapter
        ?: linuxAdapters?.videoCaptureAdapter
        ?: requireNotNull(macAdapters).videoCaptureAdapter
    return DesktopCaptureAdapters(
        sourceRepository = CompositeCaptureSourceRepository(
            listOf(awtRepository, nativeRepository),
        ),
        videoCaptureAdapter = RoutingVideoCaptureAdapter(
            routes = listOf(
                VideoCaptureRoute(
                    matches = { source ->
                        windowsAdapters != null || source is CaptureSource.Window || source is CaptureSource.Application
                    },
                    adapter = nativeVideoAdapter,
                ),
            ),
            fallback = awtVideoAdapter,
        ),
        permissionGateway = macAdapters?.permissionGateway,
        closeAction = {
            linuxAdapters?.close()
            macAdapters?.close()
        },
    )
}

private data class DesktopCaptureAdapters(
    val sourceRepository: CaptureSourceRepository,
    val videoCaptureAdapter: VideoCaptureAdapter,
    val permissionGateway: PermissionGateway? = null,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }
}

private class DesktopAudioAdapters(
    val sourceRepository: AudioSourceRepository,
    val captureAdapter: AudioCaptureAdapter,
    val driftCorrectionMonitor: AudioDriftCorrectionMonitor,
    val systemAudioSupported: Boolean,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            driftCorrectionMonitor.clear()
            closeAction()
        }
    }
}
