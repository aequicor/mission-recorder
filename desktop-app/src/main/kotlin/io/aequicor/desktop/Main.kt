package io.aequicor.desktop

import io.aequicor.audio.core.AudioCaptureRoute
import io.aequicor.audio.core.AudioDriftCorrectionMonitor
import io.aequicor.audio.core.AudioLevelMonitor
import io.aequicor.audio.core.DriftCorrectingAudioCaptureAdapter
import io.aequicor.audio.core.MeteringAudioCaptureAdapter
import io.aequicor.audio.core.MixingAudioCaptureAdapter
import io.aequicor.audio.core.MutableAudioMuteController
import io.aequicor.audio.core.MutingAudioCaptureAdapter
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.aequicor.audio.desktop.javasound.JavaSoundAudioCaptureAdapter
import io.aequicor.audio.desktop.javasound.JavaSoundAudioSourceRepository
import io.aequicor.audio.linux.pulse.LinuxPulseAudioAdapterFactory
import io.aequicor.audio.windows.wasapi.WindowsWasapiAudioAdapterFactory
import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.MediaClock
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.RecordingController
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSessionIdFactory
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.desktop.awt.AwtCaptureSourceRepository
import io.aequicor.capture.desktop.awt.AwtCaptureRegionSelector
import io.aequicor.capture.desktop.awt.AwtVideoCaptureAdapter
import io.aequicor.capture.linux.x11.LinuxX11CaptureAdapterFactory
import io.aequicor.capture.macos.coregraphics.MacCaptureAdapterFactory
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.CompositeAudioSourceRepository
import io.aequicor.capture.platform.CompositeCaptureSourceRepository
import io.aequicor.capture.platform.JvmDesktopPermissionGateway
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.RoutingVideoCaptureAdapter
import io.aequicor.capture.platform.VideoCaptureRoute
import io.aequicor.capture.windows.jna.WindowsCaptureAdapterFactory
import io.aequicor.compose.ui.MissionRecorderScreen
import io.aequicor.compose.ui.MiniRecorderController
import io.aequicor.compose.ui.StoryboardMode
import io.aequicor.compose.resources.Res
import io.aequicor.compose.resources.mission_recorder
import io.aequicor.compose.resources.global_hotkeys
import io.aequicor.compose.resources.mini_controller_title
import io.aequicor.compose.resources.show_mini_controller
import io.aequicor.compose.resources.view_menu
import io.aequicor.media.desktop.ffmpeg.FfmpegMediaEncoder
import io.aequicor.media.desktop.ffmpeg.FfmpegSegmentedReplayBuffer
import io.aequicor.media.desktop.ffmpeg.FfmpegStoryboardExporter
import io.aequicor.media.desktop.ffmpeg.StoryboardExportSettings
import io.aequicor.media.desktop.ffmpeg.StoryboardExporter
import io.aequicor.media.desktop.ffmpeg.StoryboardLayout
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.windows.jna.WindowsGlobalHotkeyServiceFactory
import io.aequicor.replay.ReplayCaptureController
import io.aequicor.settings.MissionRecorderSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    val applicationIcon = painterResource(Res.drawable.mission_recorder)
    val recorderScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val audioAdapters = remember { createDesktopAudioAdapters() }
    val captureAdapters = remember { createDesktopCaptureAdapters() }
    val desktopUiSettings = remember {
        DesktopUiSettingsRepository(MissionRecorderSettingsStore(desktopSettingsPath()))
    }
    val startupSettingsResult = remember { runCatching(desktopUiSettings::loadStartupSettings) }
    val startupSettings = startupSettingsResult.getOrDefault(DesktopStartupSettings())
    val profileCatalogResult = remember { runCatching(desktopUiSettings::loadProfileCatalog) }
    val viewModel = remember {
        createDesktopRecorderViewModel(
            scope = recorderScope,
            audioAdapters = audioAdapters,
            captureAdapters = captureAdapters,
            initialPreferences = startupSettings.recorderPreferences,
            initialProfileCatalog = profileCatalogResult.getOrNull(),
            profileStore = RepositoryDesktopRecorderProfileStore(desktopUiSettings),
        )
    }
    val state by viewModel.state.collectAsState()
    val previewFrame by viewModel.previewFrame.collectAsState()
    val previewImage = remember(previewFrame) { previewFrame?.toImageBitmap() }
    val currentState by rememberUpdatedState(state)
    val hotkeyFactory = remember { WindowsGlobalHotkeyServiceFactory() }
    var closing by remember { mutableStateOf(false) }
    var showMiniController by remember { mutableStateOf(false) }
    var globalHotkeysEnabled by remember { mutableStateOf(startupSettings.globalHotkeysEnabled) }
    val windowState = rememberWindowState(width = 1180.dp, height = 760.dp)
    val miniWindowState = rememberWindowState(
        width = 488.dp,
        height = 92.dp,
        position = WindowPosition(Alignment.TopEnd),
    )
    val viewMenuLabel = stringResource(Res.string.view_menu)
    val showMiniControllerLabel = stringResource(Res.string.show_mini_controller)
    val globalHotkeysLabel = stringResource(Res.string.global_hotkeys)
    val miniControllerTitle = stringResource(Res.string.mini_controller_title)

    DisposableEffect(Unit) {
        onDispose {
            audioAdapters.close()
            captureAdapters.close()
            recorderScope.cancel()
        }
    }

    LaunchedEffect(Unit) {
        startupSettingsResult.exceptionOrNull()?.let { failure ->
            viewModel.reportPlatformError(failure.message ?: "Could not load desktop settings.")
        }
        profileCatalogResult.exceptionOrNull()?.let { failure ->
            viewModel.reportPlatformError(failure.message ?: "Could not load recording profiles.")
        }
    }

    LaunchedEffect(globalHotkeysEnabled) {
        runCatching {
            withContext(Dispatchers.IO) {
                desktopUiSettings.saveGlobalHotkeysEnabled(globalHotkeysEnabled)
            }
        }.onFailure { failure ->
            viewModel.reportPlatformError(failure.message ?: "Could not save global hotkey settings.")
        }
        if (!globalHotkeysEnabled) {
            return@LaunchedEffect
        }
        val service = try {
            withContext(Dispatchers.IO) { hotkeyFactory.create() }
        } catch (failure: Throwable) {
            globalHotkeysEnabled = false
            viewModel.reportPlatformError(failure.message ?: "Could not enable global hotkeys.")
            return@LaunchedEffect
        }
        try {
            service.events.collect { event ->
                when (event) {
                    is GlobalHotkeyEvent.Triggered ->
                        routeGlobalHotkey(event.action, currentState, viewModel::onAction)
                    is GlobalHotkeyEvent.Failed -> {
                        viewModel.reportPlatformError(event.message)
                        globalHotkeysEnabled = false
                    }
                }
            }
        } finally {
            runCatching {
                withContext(NonCancellable + Dispatchers.IO) { service.close() }
            }.onFailure { failure ->
                viewModel.reportPlatformError(failure.message ?: "Could not disable global hotkeys.")
            }
        }
    }

    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                viewModel.shutdown(::exitApplication)
            }
        },
        state = windowState,
        title = "Mission Recorder",
        icon = applicationIcon,
    ) {
        MenuBar {
            Menu(viewMenuLabel) {
                CheckboxItem(
                    text = showMiniControllerLabel,
                    checked = showMiniController,
                    onCheckedChange = { checked -> showMiniController = checked },
                )
                CheckboxItem(
                    text = globalHotkeysLabel,
                    checked = globalHotkeysEnabled,
                    enabled = hotkeyFactory.isSupported,
                    onCheckedChange = { checked -> globalHotkeysEnabled = checked },
                )
            }
        }
        LaunchedEffect(window) {
            window.minimumSize = Dimension(760, 620)
        }
        MissionRecorderScreen(
            state = state,
            onAction = viewModel::onAction,
            previewImage = previewImage,
        )
    }

    if (showMiniController) {
        Window(
            onCloseRequest = { showMiniController = false },
            state = miniWindowState,
            title = miniControllerTitle,
            icon = applicationIcon,
            resizable = false,
            alwaysOnTop = true,
        ) {
            LaunchedEffect(window) {
                restoreMiniControllerPosition(window, desktopUiSettings)
            }
            DisposableEffect(window) {
                val focusListener = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent) {
                        clampWindowToAvailableScreens(window)
                    }
                }
                window.addWindowFocusListener(focusListener)
                onDispose {
                    window.removeWindowFocusListener(focusListener)
                    saveMiniControllerPosition(window, desktopUiSettings)
                }
            }
            MiniRecorderController(state = state, onAction = viewModel::onAction)
        }
    }
}

private fun createDesktopRecorderViewModel(
    scope: CoroutineScope,
    audioAdapters: DesktopAudioAdapters,
    captureAdapters: DesktopCaptureAdapters,
    initialPreferences: DesktopRecorderPreferences,
    initialProfileCatalog: DesktopRecorderProfileCatalog?,
    profileStore: DesktopRecorderProfileStore,
): DesktopRecorderViewModel {
    val controller = RecordingController(
        videoCaptureAdapter = captureAdapters.videoCaptureAdapter,
        audioCaptureAdapter = audioAdapters.captureAdapter,
        mediaEncoder = FfmpegMediaEncoder(),
        scope = scope,
        clock = DesktopMediaClock,
        sessionIdFactory = DesktopSessionIdFactory,
    )
    val replayController = ReplayCaptureController(
        videoCaptureAdapter = captureAdapters.videoCaptureAdapter,
        audioCaptureAdapter = audioAdapters.captureAdapter,
        mediaBuffer = FfmpegSegmentedReplayBuffer(replayCachePath()),
        scope = scope,
        clock = DesktopMediaClock,
        sessionIdFactory = DesktopSessionIdFactory,
    )
    return DesktopRecorderViewModel(
        scope = scope,
        captureSourceRepository = captureAdapters.sourceRepository,
        audioSourceRepository = audioAdapters.sourceRepository,
        recordingEngine = RecordingControllerEngine(controller),
        replayEngine = ReplayCaptureControllerEngine(replayController),
        previewEngine = DesktopPreviewEngine(captureAdapters.videoCaptureAdapter::frames),
        storyboardExporter = DesktopFfmpegStoryboardExporter(FfmpegStoryboardExporter()),
        nextOutputPath = ::nextOutputPath,
        nextReplayOutputPath = ::nextReplayOutputPath,
        captureRegionSelector = AwtCaptureRegionSelector(),
        audioLevels = audioAdapters.levelMonitor.levels,
        outputFileSelector = AwtDesktopOutputFileSelector(),
        recordingsDirectoryOpener = AwtDesktopRecordingsDirectoryOpener(),
        audioMuteController = audioAdapters.muteController,
        permissionGateway = captureAdapters.permissionGateway ?: JvmDesktopPermissionGateway(
            linuxSystemAudioAvailable = audioAdapters.systemAudioSupported,
        ),
        initialPreferences = initialPreferences,
        initialProfileCatalog = initialProfileCatalog,
        profileStore = profileStore,
    )
}

private fun createDesktopAudioAdapters(): DesktopAudioAdapters {
    val levelMonitor = AudioLevelMonitor()
    val driftCorrectionMonitor = AudioDriftCorrectionMonitor()
    val muteController = MutableAudioMuteController()
    val microphoneRepository = JavaSoundAudioSourceRepository()
    val microphoneAdapter = MutingAudioCaptureAdapter(
        delegate = MeteringAudioCaptureAdapter(
            delegate = DriftCorrectingAudioCaptureAdapter(
                delegate = JavaSoundAudioCaptureAdapter(),
                clock = DesktopMediaClock,
                monitor = driftCorrectionMonitor,
            ),
            monitor = levelMonitor,
        ),
        muteController = muteController,
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
                    adapter = MutingAudioCaptureAdapter(
                        delegate = MeteringAudioCaptureAdapter(
                            delegate = DriftCorrectingAudioCaptureAdapter(
                                delegate = windows.captureAdapter,
                                clock = DesktopMediaClock,
                                monitor = driftCorrectionMonitor,
                            ),
                            monitor = levelMonitor,
                        ),
                        muteController = muteController,
                    ),
                ),
            )
        }
        linuxAdapters?.let { linux ->
            add(
                AudioCaptureRoute(
                    matches = { source -> source is AudioSource.SystemLoopback },
                    adapter = MutingAudioCaptureAdapter(
                        delegate = MeteringAudioCaptureAdapter(
                            delegate = DriftCorrectingAudioCaptureAdapter(
                                delegate = linux.captureAdapter,
                                clock = DesktopMediaClock,
                                monitor = driftCorrectionMonitor,
                            ),
                            monitor = levelMonitor,
                        ),
                        muteController = muteController,
                    ),
                ),
            )
        }
    }
    return DesktopAudioAdapters(
        sourceRepository = if (repositories.size == 1) repositories.single() else CompositeAudioSourceRepository(repositories),
        captureAdapter = MixingAudioCaptureAdapter(routes),
        levelMonitor = levelMonitor,
        driftCorrectionMonitor = driftCorrectionMonitor,
        muteController = muteController,
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
                    matches = { source -> source is CaptureSource.Window || source is CaptureSource.Application },
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
    val levelMonitor: AudioLevelMonitor,
    val driftCorrectionMonitor: AudioDriftCorrectionMonitor,
    val muteController: MutableAudioMuteController,
    val systemAudioSupported: Boolean,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    override fun close() {
        driftCorrectionMonitor.clear()
        closeAction()
    }
}

private data object DesktopMediaClock : MediaClock {
    private val origin = System.nanoTime()

    override fun nowNanoseconds(): Long = System.nanoTime() - origin
}

private data object DesktopSessionIdFactory : RecordingSessionIdFactory {
    override fun nextId(): RecordingSessionId = RecordingSessionId(UUID.randomUUID().toString())
}

private fun nextOutputPath(): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
    return Path.of(
        System.getProperty("user.home"),
        "Videos",
        "Mission Recorder",
        "mission-$timestamp.mp4",
    ).toString()
}

private fun nextReplayOutputPath(): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
    return Path.of(
        System.getProperty("user.home"),
        "Videos",
        "Mission Recorder",
        "replay-$timestamp.mp4",
    ).toString()
}

private fun replayCachePath(): Path = Path.of(
    System.getProperty("java.io.tmpdir"),
    "Mission Recorder",
    "replay-buffer",
)

private fun restoreMiniControllerPosition(
    window: java.awt.Window,
    settings: DesktopUiSettingsRepository,
) {
    val persisted = runCatching(settings::loadMiniControllerPosition)
        .onFailure { error -> reportDesktopUiSettingsFailure("load", error) }
        .getOrNull()
        ?: return
    val restored = clampWindowPosition(
        preferred = persisted,
        windowSize = window.size,
        screenBounds = availableScreenBounds(),
    )
    window.setLocation(restored.x, restored.y)
}

private fun clampWindowToAvailableScreens(window: java.awt.Window) {
    val current = DesktopWindowPosition(x = window.x, y = window.y)
    val clamped = clampWindowPosition(
        preferred = current,
        windowSize = window.size,
        screenBounds = availableScreenBounds(),
    )
    if (clamped != current) {
        window.setLocation(clamped.x, clamped.y)
    }
}

private fun saveMiniControllerPosition(
    window: java.awt.Window,
    settings: DesktopUiSettingsRepository,
) {
    runCatching {
        settings.saveMiniControllerPosition(DesktopWindowPosition(x = window.x, y = window.y))
    }.onFailure { error -> reportDesktopUiSettingsFailure("save", error) }
}

private fun availableScreenBounds() =
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .map { device -> device.defaultConfiguration.bounds }

private fun reportDesktopUiSettingsFailure(action: String, error: Throwable) {
    System.err.println("Unable to $action desktop UI settings: ${error.message}")
}

private class DesktopFfmpegStoryboardExporter(
    private val exporter: StoryboardExporter,
) : DesktopStoryboardExporter {
    override suspend fun export(request: DesktopStoryboardExportRequest): DesktopStoryboardExportResult {
        val result = exporter.export(
            StoryboardExportSettings(
                inputVideo = Path.of(request.inputVideoPath),
                outputPath = Path.of(request.outputPath),
                layout = when (request.mode) {
                    StoryboardMode.SeparatePngFiles -> StoryboardLayout.SeparatePngFiles
                    StoryboardMode.ContactSheet -> StoryboardLayout.ContactSheet
                },
            ),
        )
        return DesktopStoryboardExportResult(
            outputPath = request.outputPath,
            frameCount = result.frameCount,
        )
    }
}
