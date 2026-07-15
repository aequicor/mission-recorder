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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
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
import io.aequicor.compose.ui.MissionRecorderTheme
import io.aequicor.compose.ui.MiniRecorderController
import io.aequicor.compose.ui.PreviewUiStatus
import io.aequicor.compose.ui.RecorderShortcutLabels
import io.aequicor.compose.ui.RecorderStatus
import io.aequicor.compose.ui.RecorderUiAction
import io.aequicor.compose.ui.StoryboardMode
import io.aequicor.compose.ui.VideoEditorAction
import io.aequicor.compose.ui.VideoEditorScreen
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.editor.ImportantFrameId
import io.aequicor.compose.resources.Res
import io.aequicor.compose.resources.mission_recorder
import io.aequicor.compose.resources.mini_controller_title
import io.aequicor.compose.resources.app_name
import io.aequicor.compose.resources.tray_exit
import io.aequicor.compose.resources.tray_open
import io.aequicor.compose.resources.video_editor
import io.aequicor.media.desktop.ffmpeg.FfmpegMediaEncoder
import io.aequicor.media.desktop.ffmpeg.FfmpegEditorMediaService
import io.aequicor.media.desktop.ffmpeg.FfmpegSegmentedReplayBuffer
import io.aequicor.media.desktop.ffmpeg.FfmpegStoryboardExporter
import io.aequicor.media.desktop.ffmpeg.StoryboardExportSettings
import io.aequicor.media.desktop.ffmpeg.StoryboardExporter
import io.aequicor.media.desktop.ffmpeg.StoryboardLayout
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.GlobalHotkeyAction
import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.replay.ReplayCaptureController
import io.aequicor.settings.MissionRecorderSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource

fun main() {
    installDesktopFatalErrorHandler()
    desktopApplication()
}

private fun desktopApplication() = application(exitProcessOnExit = true) {
    val applicationIcon = painterResource(Res.drawable.mission_recorder)
    val recorderScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val audioAdapters = remember { createDesktopAudioAdapters() }
    val captureAdapters = remember { createDesktopCaptureAdapters() }
    val capturePresentation = remember { DesktopCapturePresentation() }
    val desktopUiSettings = remember {
        DesktopUiSettingsRepository(MissionRecorderSettingsStore(desktopSettingsPath()))
    }
    val startupSettingsResult = remember { runCatching(desktopUiSettings::loadStartupSettings) }
    val startupSettings = startupSettingsResult.getOrDefault(DesktopStartupSettings())
    val profileCatalogResult = remember { runCatching(desktopUiSettings::loadProfileCatalog) }
    val editorFileSelector = remember { AwtDesktopEditorFileSelector() }
    val viewModel = remember {
        createDesktopRecorderViewModel(
            scope = recorderScope,
            audioAdapters = audioAdapters,
            captureAdapters = captureAdapters,
            initialPreferences = startupSettings.recorderPreferences,
            initialStoryboardInputPath = startupSettings.recentEditorMediaPaths.firstOrNull().orEmpty(),
            initialShowApplicationInRecording = startupSettings.showApplicationInRecording,
            initialShowCaptureBorder = startupSettings.showCaptureBorder,
            initialProfileCatalog = profileCatalogResult.getOrNull(),
            profileStore = RepositoryDesktopRecorderProfileStore(desktopUiSettings),
        )
    }
    val editorViewModel = remember {
        VideoEditorViewModel(
            scope = recorderScope,
            mediaService = FfmpegEditorMediaService(),
            projectStore = JsonDesktopEditorProjectStore(),
            fileSelector = editorFileSelector,
            audioPlayer = JavaSoundDesktopEditorAudioPlayer(),
            imageClipboard = AwtDesktopImageClipboard(),
            initialFrameLayout = when (startupSettings.recorderPreferences.storyboardMode) {
                StoryboardMode.SeparatePngFiles -> ImportantFrameLayout.SeparatePngFiles
                StoryboardMode.ContactSheet -> ImportantFrameLayout.ContactSheet
            },
            initialRecentMediaPaths = startupSettings.recentEditorMediaPaths,
            onRecentMediaPath = { path ->
                runCatching { desktopUiSettings.saveRecentEditorMediaPath(path) }
            },
        )
    }
    val state by viewModel.state.collectAsState()
    val editorState by editorViewModel.state.collectAsState()
    val editorPlayhead = editorViewModel.playheadMicros.collectAsState()
    val previewImageResource = remember { mutableStateOf<DesktopPreviewImage?>(null) }
    val previewImage = remember { derivedStateOf { previewImageResource.value?.bitmap } }
    val editorPreviewImageResource = remember { mutableStateOf<DesktopPreviewImage?>(null) }
    val editorPreviewImage = remember { derivedStateOf { editorPreviewImageResource.value?.bitmap } }
    val editorImportantFrameImageResources = remember {
        mutableStateOf<Map<ImportantFrameId, DesktopPreviewImage>>(emptyMap())
    }
    val editorImportantFrameImages = remember {
        derivedStateOf { editorImportantFrameImageResources.value.mapValues { it.value.bitmap } }
    }
    val currentState by rememberUpdatedState(state)
    val hotkeyFactory = remember { DesktopGlobalHotkeyServiceFactory() }
    var closing by remember { mutableStateOf(false) }
    var showMainWindow by remember { mutableStateOf(true) }
    var hiddenToTray by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var openEditorAfterRecording by remember { mutableStateOf(false) }
    var mainWindowMinimized by remember { mutableStateOf(false) }
    var showMiniController by remember { mutableStateOf(false) }
    var compactedForRecording by remember { mutableStateOf(false) }
    var mainWindow by remember { mutableStateOf<java.awt.Window?>(null) }
    var editorWindow by remember { mutableStateOf<java.awt.Window?>(null) }
    var globalHotkeysEnabled by remember {
        mutableStateOf(startupSettings.globalHotkeysEnabled && hotkeyFactory.isSupported)
    }
    var globalHotkeyBindings by remember { mutableStateOf(startupSettings.globalHotkeyBindings) }
    var showHotkeySettingsDialog by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(width = 1180.dp, height = 760.dp)
    val editorWindowState = rememberWindowState(width = 1440.dp, height = 900.dp)
    val miniWindowState = rememberWindowState(
        width = 70.dp,
        height = 220.dp,
        position = WindowPosition(Alignment.TopEnd),
    )
    val miniControllerTitle = stringResource(Res.string.mini_controller_title)
    val applicationName = stringResource(Res.string.app_name)
    val trayOpenLabel = stringResource(Res.string.tray_open)
    val trayExitLabel = stringResource(Res.string.tray_exit)
    val editorWindowTitle = stringResource(Res.string.video_editor)

    val requestExit = {
        if (!closing) {
            closing = true
            showMainWindow = false
            showMiniController = false
            scheduleForcedDesktopExit()
            viewModel.shutdown(::exitApplication)
            editorViewModel.shutdown()
        }
    }
    val showFromTray = {
        hiddenToTray = false
        compactedForRecording = false
        mainWindowMinimized = false
        showMiniController = false
        showMainWindow = true
        restoreDesktopWindow(mainWindow)
    }
    val hideToTray = {
        hiddenToTray = true
        compactedForRecording = false
        showMainWindow = false
        showMiniController = false
    }
    val openEditor = { inputPath: String? ->
        if (showEditor) {
            restoreDesktopWindow(editorWindow)
        } else {
            showEditor = true
            editorViewModel.open(inputPath)
        }
    }
    val hideEditor = {
        if (showEditor) {
            showEditor = false
            editorViewModel.requestClose { }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorderScope.cancel()
            previewImageResource.value?.close()
            previewImageResource.value = null
            editorPreviewImageResource.value?.close()
            editorPreviewImageResource.value = null
            editorImportantFrameImageResources.value.values.forEach(DesktopPreviewImage::close)
            editorImportantFrameImageResources.value = emptyMap()
            editorViewModel.shutdown()
            audioAdapters.close()
            captureAdapters.close()
            capturePresentation.close()
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

    LaunchedEffect(state.permissionPrompt != null) {
        if (state.permissionPrompt != null) {
            showFromTray()
        }
    }

    LaunchedEffect(viewModel) {
        var pendingImage: DesktopPreviewImage? = null
        var retiredImage: DesktopPreviewImage? = null
        try {
            viewModel.previewFrame.collect { frame ->
                withContext(Dispatchers.IO) {
                    pendingImage = frame?.toDesktopPreviewImage()
                }
                retiredImage = previewImageResource.value
                previewImageResource.value = pendingImage
                pendingImage = null
                // The first frame publishes the replacement; the second cannot draw the retired image anymore.
                repeat(PREVIEW_IMAGE_RETIREMENT_FRAMES) { withFrameNanos { } }
                retiredImage?.close()
                retiredImage = null
            }
        } finally {
            pendingImage?.close()
            retiredImage?.close()
        }
    }

    LaunchedEffect(editorViewModel) {
        var pendingImage: DesktopPreviewImage? = null
        try {
            editorViewModel.previewFrame.collect { frame ->
                withContext(Dispatchers.IO) {
                    pendingImage = frame?.toDesktopPreviewImage()
                }
                val retiredImage = editorPreviewImageResource.value
                editorPreviewImageResource.value = pendingImage
                pendingImage = null
                if (retiredImage != null) {
                    launch {
                        try {
                            repeat(PREVIEW_IMAGE_RETIREMENT_FRAMES) { withFrameNanos { } }
                        } finally {
                            retiredImage.close()
                        }
                    }
                }
            }
        } finally {
            pendingImage?.close()
        }
    }

    LaunchedEffect(editorViewModel) {
        var pendingImages = emptyMap<ImportantFrameId, DesktopPreviewImage>()
        var retiredImages = emptyMap<ImportantFrameId, DesktopPreviewImage>()
        try {
            editorViewModel.importantFramePreviews.collect { previews ->
                pendingImages = withContext(Dispatchers.IO) {
                    previews.mapValues { it.value.toDesktopPreviewImage() }
                }
                retiredImages = editorImportantFrameImageResources.value
                editorImportantFrameImageResources.value = pendingImages
                pendingImages = emptyMap()
                repeat(PREVIEW_IMAGE_RETIREMENT_FRAMES) { withFrameNanos { } }
                retiredImages.values.forEach(DesktopPreviewImage::close)
                retiredImages = emptyMap()
            }
        } finally {
            pendingImages.values.forEach(DesktopPreviewImage::close)
            retiredImages.values.forEach(DesktopPreviewImage::close)
        }
    }

    LaunchedEffect(state.selectedSourceId, state.frameRate) {
        if (state.previewStatus != PreviewUiStatus.Idle) {
            viewModel.onAction(RecorderUiAction.StopPreview)
        }
    }

    LaunchedEffect(showMainWindow, mainWindowMinimized, showEditor, state.canStartPreview) {
        if (!showEditor && shouldStartPreview(showMainWindow, mainWindowMinimized, state.canStartPreview)) {
            viewModel.onAction(RecorderUiAction.StartPreview)
        }
    }

    LaunchedEffect(showMainWindow, mainWindowMinimized, showEditor, state.previewStatus) {
        if ((showEditor && state.isPreviewRunning) ||
            shouldStopPreview(showMainWindow, mainWindowMinimized, state.isPreviewRunning)
        ) {
            viewModel.onAction(RecorderUiAction.StopPreview)
        }
    }

    LaunchedEffect(globalHotkeysEnabled, globalHotkeyBindings) {
        runCatching {
            withContext(Dispatchers.IO) {
                desktopUiSettings.saveGlobalHotkeySettings(globalHotkeysEnabled, globalHotkeyBindings)
            }
        }.onFailure { failure ->
            viewModel.reportPlatformError(failure.message ?: "Could not save global hotkey settings.")
        }
        if (!globalHotkeysEnabled) {
            return@LaunchedEffect
        }
        val service = try {
            withContext(Dispatchers.IO) { hotkeyFactory.create(globalHotkeyBindings) }
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

    LaunchedEffect(state.showApplicationInRecording) {
        runCatching {
            withContext(Dispatchers.IO) {
                desktopUiSettings.saveShowApplicationInRecording(state.showApplicationInRecording)
            }
        }.onFailure { failure ->
            viewModel.reportPlatformError(failure.message ?: "Could not save capture visibility setting.")
        }
    }

    LaunchedEffect(state.showCaptureBorder) {
        runCatching {
            withContext(Dispatchers.IO) {
                desktopUiSettings.saveShowCaptureBorder(state.showCaptureBorder)
            }
        }.onFailure { failure ->
            viewModel.reportPlatformError(failure.message ?: "Could not save capture border setting.")
        }
    }

    val recordingPresentationActive = state.status == RecorderStatus.Recording ||
        state.status == RecorderStatus.Paused ||
        state.status == RecorderStatus.Stopping
    LaunchedEffect(recordingPresentationActive, state.selectedSourceId) {
        if (!recordingPresentationActive) {
            compactedForRecording = false
            return@LaunchedEffect
        }
        val captureSource = viewModel.captureSource(state.selectedSourceId) ?: return@LaunchedEffect
        val frame = mainWindow as? Frame
        if (
            frame != null &&
            frame.extendedState and Frame.ICONIFIED == 0 &&
            shouldShowMiniController(applicationHiddenToTray = hiddenToTray)
        ) {
            compactedForRecording = true
            showMiniController = true
            frame.extendedState = frame.extendedState or Frame.ICONIFIED
        }
        withContext(Dispatchers.IO) {
            capturePresentation.activate(captureSource)
        }
    }

    LaunchedEffect(openEditorAfterRecording, state.status, state.lastOutputPath) {
        if (!openEditorAfterRecording) {
            return@LaunchedEffect
        }
        when (state.status) {
            RecorderStatus.Completed -> {
                val outputPath = state.lastOutputPath ?: return@LaunchedEffect
                openEditorAfterRecording = false
                openEditor(outputPath)
            }
            RecorderStatus.Failed,
            RecorderStatus.Idle,
            -> openEditorAfterRecording = false
            RecorderStatus.Preparing,
            RecorderStatus.Recording,
            RecorderStatus.Paused,
            RecorderStatus.Stopping,
            -> Unit
        }
    }

    LaunchedEffect(state.status, state.lastOutputPath) {
        if (state.status == RecorderStatus.Completed) {
            state.lastOutputPath?.let(editorViewModel::registerRecentMediaPath)
        }
    }

    LaunchedEffect(recordingPresentationActive, state.selectedSourceId, state.showCaptureBorder) {
        if (!recordingPresentationActive || !state.showCaptureBorder) {
            capturePresentation.hide()
            return@LaunchedEffect
        }
        val captureSource = viewModel.captureSource(state.selectedSourceId) ?: return@LaunchedEffect
        try {
            while (true) {
                val bounds = withContext(Dispatchers.IO) {
                    capturePresentation.bounds(captureSource)
                }
                capturePresentation.show(bounds)
                delay(CAPTURE_INDICATOR_REFRESH_MILLIS)
            }
        } finally {
            capturePresentation.hide()
        }
    }

    if (isTraySupported) {
        Tray(
            icon = applicationIcon,
            tooltip = applicationName,
            onAction = showFromTray,
        ) {
            Item(trayOpenLabel, onClick = showFromTray)
            Separator()
            Item(trayExitLabel, onClick = requestExit)
        }
    }

    Window(
        onCloseRequest = {
            when (desktopCloseAction(isTraySupported)) {
                DesktopCloseAction.HideToTray -> hideToTray()
                DesktopCloseAction.MinimizeToTaskbar -> {
                    (mainWindow as? Frame)?.let { frame ->
                        frame.extendedState = frame.extendedState or Frame.ICONIFIED
                    }
                }
            }
        },
        visible = !closing && showMainWindow,
        state = windowState,
        title = "Mission Recorder",
        icon = applicationIcon,
    ) {
        if (showHotkeySettingsDialog) {
            MissionRecorderTheme {
                DesktopHotkeySettingsDialog(
                    enabled = globalHotkeysEnabled,
                    hotkeysSupported = hotkeyFactory.isSupported,
                    bindings = globalHotkeyBindings,
                    onDismissRequest = { showHotkeySettingsDialog = false },
                    onApply = { enabled, bindings ->
                        globalHotkeysEnabled = enabled
                        globalHotkeyBindings = bindings
                        showHotkeySettingsDialog = false
                    },
                )
            }
        }
        LaunchedEffect(window, state.showApplicationInRecording) {
            mainWindow = window
            window.minimumSize = Dimension(760, 620)
            setWindowVisibleInCapture(window, state.showApplicationInRecording)
        }
        DisposableEffect(window, showEditor) {
            val windowListener = object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) {
                    if (showEditor) return
                    if (
                        !shouldCompactOnFocusLoss(
                            focusMovedWithinApplication = event.oppositeWindow != null,
                            applicationHiddenToTray = hiddenToTray,
                        )
                    ) {
                        return
                    }
                    val frame = event.window as? Frame ?: return
                    if (frame.extendedState and Frame.ICONIFIED != 0) {
                        return
                    }
                    showMiniController = true
                    frame.extendedState = frame.extendedState or Frame.ICONIFIED
                }

                override fun windowIconified(event: WindowEvent) {
                    mainWindowMinimized = true
                    if (shouldShowMiniController(applicationHiddenToTray = hiddenToTray)) {
                        showMiniController = true
                    }
                }

                override fun windowDeiconified(event: WindowEvent) {
                    mainWindowMinimized = false
                    showMiniController = false
                }
            }
            window.addWindowListener(windowListener)
            window.addWindowFocusListener(windowListener)
            onDispose {
                window.removeWindowListener(windowListener)
                window.removeWindowFocusListener(windowListener)
                if (mainWindow === window) {
                    mainWindow = null
                }
            }
        }
        MissionRecorderScreen(
            state = state,
            onAction = { action ->
                when (action) {
                    RecorderUiAction.ChooseStoryboardInputFile -> recorderScope.launch {
                        editorFileSelector.chooseVideoFile(state.storyboardInputPath)?.let { path ->
                            viewModel.onAction(RecorderUiAction.SetStoryboardInputPath(path))
                            editorViewModel.registerRecentMediaPath(path)
                        }
                    }
                    RecorderUiAction.OpenEditor -> openEditor(
                        state.storyboardInputPath.trim().ifEmpty {
                            state.lastOutputPath ?: editorState.recentMediaPaths.firstOrNull().orEmpty()
                        },
                    )
                    else -> {
                        if (shouldHideEditorWhenStartingRecording(action)) {
                            hideEditor()
                        }
                        viewModel.onAction(action)
                    }
                }
            },
            previewImage = previewImage,
            onConfigureShortcuts = { showHotkeySettingsDialog = true },
            shortcutLabels = globalHotkeyBindings.toShortcutLabels(),
        )
    }

    if (showEditor) {
        Window(
            onCloseRequest = { editorViewModel.requestClose { showEditor = false } },
            state = editorWindowState,
            title = editorWindowTitle,
            icon = applicationIcon,
        ) {
            DisposableEffect(window) {
                editorWindow = window
                window.minimumSize = Dimension(1_180, 720)
                restoreDesktopWindow(window)
                onDispose {
                    if (editorWindow === window) {
                        editorWindow = null
                    }
                }
            }
            VideoEditorScreen(
                state = editorState,
                playheadMicros = editorPlayhead,
                previewImage = editorPreviewImage,
                importantFrameImages = editorImportantFrameImages.value,
                onAction = { action ->
                    if (action == VideoEditorAction.BackToRecorder) {
                        editorViewModel.requestClose { showEditor = false }
                    } else {
                        editorViewModel.onAction(action)
                    }
                },
            )
        }
    }

    if (showMiniController) {
        Window(
            onCloseRequest = hideToTray,
            state = miniWindowState,
            title = miniControllerTitle,
            icon = applicationIcon,
            resizable = false,
            undecorated = true,
            transparent = true,
            focusable = false,
            alwaysOnTop = true,
        ) {
            LaunchedEffect(window) {
                restoreMiniControllerPosition(window, desktopUiSettings)
            }
            LaunchedEffect(window, state.showApplicationInRecording) {
                setWindowVisibleInCapture(window, state.showApplicationInRecording)
            }
            DisposableEffect(window) {
                val focusListener = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent) {
                        clampWindowToAvailableScreens(window)
                    }

                    override fun windowStateChanged(event: WindowEvent) {
                        if (!shouldRestoreMainWindowFromMiniController(event.newState)) {
                            return
                        }
                        window.extendedState = Frame.NORMAL
                        miniWindowState.placement = WindowPlacement.Floating
                        showFromTray()
                    }
                }
                window.addWindowFocusListener(focusListener)
                window.addWindowStateListener(focusListener)
                onDispose {
                    window.removeWindowFocusListener(focusListener)
                    window.removeWindowStateListener(focusListener)
                    saveMiniControllerPosition(window, desktopUiSettings)
                }
            }
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(window) {
                    var pointerOrigin: Point? = null
                    var windowOrigin: Point? = null
                    detectDragGestures(
                        onDragStart = {
                            pointerOrigin = MouseInfo.getPointerInfo()?.location
                            windowOrigin = window.location
                        },
                        onDragEnd = {
                            pointerOrigin = null
                            windowOrigin = null
                        },
                        onDragCancel = {
                            pointerOrigin = null
                            windowOrigin = null
                        },
                        onDrag = onDrag@{ change, _ ->
                            val pointerStart = pointerOrigin ?: return@onDrag
                            val windowStart = windowOrigin ?: return@onDrag
                            val pointer = MouseInfo.getPointerInfo()?.location ?: return@onDrag
                            change.consume()
                            window.setLocation(
                                windowStart.x + pointer.x - pointerStart.x,
                                windowStart.y + pointer.y - pointerStart.y,
                            )
                        },
                    )
                },
            ) {
                MiniRecorderController(
                    state = state,
                    onAction = { action ->
                        if (shouldHideEditorWhenStartingRecording(action)) {
                            hideEditor()
                        }
                        viewModel.onAction(action)
                    },
                    previewImage = previewImage,
                    shortcutLabels = globalHotkeyBindings.toShortcutLabels(),
                    onExpand = showFromTray,
                    onHide = { showMiniController = false },
                    onOpenEditor = {
                        if (state.hasActiveRecording) {
                            openEditorAfterRecording = true
                            viewModel.onAction(RecorderUiAction.StopRecording)
                        } else if (state.canOpenEditor) {
                            openEditor(state.lastOutputPath ?: state.storyboardInputPath)
                        }
                    },
                )
            }
        }
    }
}

private fun List<GlobalHotkeyBinding>.toShortcutLabels(): RecorderShortcutLabels {
    val bindingsByAction = associateBy(GlobalHotkeyBinding::action)
    return RecorderShortcutLabels(
        recording = requireNotNull(bindingsByAction[GlobalHotkeyAction.ToggleRecording]).gesture
            .let(::formatGlobalHotkeyGesture),
        pause = requireNotNull(bindingsByAction[GlobalHotkeyAction.TogglePause]).gesture
            .let(::formatGlobalHotkeyGesture),
    )
}

private fun restoreDesktopWindow(window: java.awt.Window?) {
    window ?: return
    if (window is Frame) {
        window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    }
    window.isVisible = true
    window.toFront()
    window.requestFocus()
}

internal enum class DesktopCloseAction {
    HideToTray,
    MinimizeToTaskbar,
}

internal fun desktopCloseAction(traySupported: Boolean): DesktopCloseAction =
    if (traySupported) DesktopCloseAction.HideToTray else DesktopCloseAction.MinimizeToTaskbar

internal fun shouldCompactOnFocusLoss(
    focusMovedWithinApplication: Boolean,
    applicationHiddenToTray: Boolean,
): Boolean = !applicationHiddenToTray && !focusMovedWithinApplication

internal fun shouldShowMiniController(applicationHiddenToTray: Boolean): Boolean =
    !applicationHiddenToTray

internal fun shouldRestoreMainWindowFromMiniController(windowState: Int): Boolean =
    windowState and Frame.MAXIMIZED_BOTH != 0

internal fun shouldHideEditorWhenStartingRecording(action: RecorderUiAction): Boolean =
    action == RecorderUiAction.StartRecording || action == RecorderUiAction.SelectRegionAndStartRecording

internal fun shouldStartPreview(
    mainWindowVisible: Boolean,
    mainWindowMinimized: Boolean,
    canStartPreview: Boolean,
): Boolean = mainWindowVisible && !mainWindowMinimized && canStartPreview

internal fun shouldStopPreview(
    mainWindowVisible: Boolean,
    mainWindowMinimized: Boolean,
    previewRunning: Boolean,
): Boolean = (!mainWindowVisible || mainWindowMinimized) && previewRunning

private fun createDesktopRecorderViewModel(
    scope: CoroutineScope,
    audioAdapters: DesktopAudioAdapters,
    captureAdapters: DesktopCaptureAdapters,
    initialPreferences: DesktopRecorderPreferences,
    initialStoryboardInputPath: String,
    initialShowApplicationInRecording: Boolean,
    initialShowCaptureBorder: Boolean,
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
        screenshotSaver = PngDesktopScreenshotSaver(),
        nextScreenshotOutputPath = ::nextScreenshotOutputPath,
        captureRegionSelector = AwtCaptureRegionSelector(),
        audioLevels = audioAdapters.levelMonitor.levels,
        outputFileSelector = AwtDesktopOutputFileSelector(),
        recordingsDirectoryOpener = AwtDesktopRecordingsDirectoryOpener(),
        audioMuteController = audioAdapters.muteController,
        permissionGateway = captureAdapters.permissionGateway ?: JvmDesktopPermissionGateway(
            linuxSystemAudioAvailable = audioAdapters.systemAudioSupported,
        ),
        permissionSettingsOpener = MacOsPermissionSettingsOpener(),
        initialPreferences = initialPreferences,
        initialStoryboardInputPath = initialStoryboardInputPath,
        initialShowApplicationInRecording = initialShowApplicationInRecording,
        initialShowCaptureBorder = initialShowCaptureBorder,
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
            windowsAdapters?.close()
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

private fun nextScreenshotOutputPath(recordingOutputPath: String): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
    val outputDirectory = if (recordingOutputPath.isBlank()) {
        Path.of(System.getProperty("user.home"), "Videos", "Mission Recorder")
    } else {
        Path.of(recordingOutputPath).toAbsolutePath().normalize().parent
            ?: Path.of(System.getProperty("user.home"), "Videos", "Mission Recorder")
    }
    return outputDirectory.resolve("screenshot-$timestamp.png").toString()
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

internal fun availableScreenBounds() =
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .map { device -> device.defaultConfiguration.bounds }

private fun reportDesktopUiSettingsFailure(action: String, error: Throwable) {
    System.err.println("Unable to $action desktop UI settings: ${error.message}")
}

private const val PREVIEW_IMAGE_RETIREMENT_FRAMES = 2
private const val CAPTURE_INDICATOR_REFRESH_MILLIS = 250L

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
