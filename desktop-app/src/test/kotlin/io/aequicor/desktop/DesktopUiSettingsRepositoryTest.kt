package io.aequicor.desktop

import io.aequicor.capture.core.EncoderSettings
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.compose.ui.StoryboardMode
import io.aequicor.hotkey.GlobalHotkeyAction
import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyGesture
import io.aequicor.hotkey.GlobalHotkeyKey
import io.aequicor.hotkey.GlobalHotkeyModifier
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys
import io.aequicor.settings.MissionRecorderSettingsStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUiSettingsRepositoryTest {
    @Test
    fun resolvesRelativeOutputDirectoriesFromUserHomeInsteadOfProcessDirectory() {
        val directory = Files.createTempDirectory("mission-recorder-relative-output")
        val repository = DesktopUiSettingsRepository(
            store = MissionRecorderSettingsStore(directory.resolve("settings.json")),
            outputTimestamp = { "20260715-120000-000" },
            relativeOutputRoot = directory.resolve("home"),
        )

        assertEquals(
            directory.resolve("home/recordings/mission-20260715-120000-000.mp4").toString(),
            repository.nextOutputPath("default"),
        )
    }

    @Test
    fun keepsAbsoluteOutputDirectoriesUnchanged() {
        val directory = Files.createTempDirectory("mission-recorder-absolute-output")
        val repository = DesktopUiSettingsRepository(
            store = MissionRecorderSettingsStore(directory.resolve("settings.json")),
            outputTimestamp = { "20260715-120000-000" },
            relativeOutputRoot = directory.resolve("unused-home"),
        )
        val outputDirectory = directory.resolve("captures").toAbsolutePath()

        assertEquals(
            outputDirectory.resolve("mission-20260715-120000-000.mp4").toString(),
            repository.previewOutputPath(
                profileId = "default",
                outputPolicy = DesktopOutputPolicy(
                    directory = outputDirectory.toString(),
                    fileNamePattern = "mission-{timestamp}.mp4",
                ),
            ),
        )
    }

    @Test
    fun createsSelectsSavesAndDeletesNamedProfilesAtomically() {
        val path = Files.createTempDirectory("mission-recorder-profiles").resolve("settings.json")
        val store = MissionRecorderSettingsStore(path)
        val repository = DesktopUiSettingsRepository(store, outputTimestamp = { "20260711-120000-000" })
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val microphone = AudioSource.Microphone(AudioSourceId("mic:test"), "Test mic", 48_000, 2)
        val snapshot = DesktopRecorderProfileSnapshot(
            profileId = "default",
            preferences = DesktopRecorderPreferences(
                frameRate = 60,
                captureCursor = false,
                showInputOverlay = true,
                showMouseTrail = true,
                recordMouseTrail = true,
                replayDurationMinutes = 9,
                encoderSettings = EncoderSettings(videoBitrateBitsPerSecond = 16_000_000),
            ),
            captureSource = screen,
            audioSources = listOf(microphone),
            overwriteOutput = true,
            outputPolicy = DesktopOutputPolicy(
                directory = "captures",
                fileNamePattern = "game-{profile}-{timestamp}.mp4",
            ),
        )

        val firstCreated = repository.createProfile("Gaming Profile", snapshot)

        assertEquals("gaming-profile", firstCreated.selected.summary.id)
        assertEquals("Gaming Profile", firstCreated.selected.summary.name)
        assertEquals(screen, firstCreated.selected.captureSource)
        assertEquals(listOf(microphone), firstCreated.selected.audioSources)
        assertEquals(60, firstCreated.selected.preferences.frameRate)
        assertEquals(16_000_000, firstCreated.selected.preferences.encoderSettings.videoBitrateBitsPerSecond)
        assertTrue(firstCreated.selected.overwriteOutput)
        assertEquals(snapshot.outputPolicy, firstCreated.selected.outputPolicy)
        assertTrue(store.loadOrDefault().profiles.last().output.overwrite)
        assertEquals("captures", store.loadOrDefault().profiles.last().output.directory)
        assertEquals("game-{profile}-{timestamp}.mp4", store.loadOrDefault().profiles.last().output.fileNamePattern)
        assertEquals("gaming-profile", store.loadOrDefault().defaultProfileId)
        assertTrue(repository.nextOutputPath("gaming-profile").contains("20260711-120000-000"))
        assertTrue(repository.nextOutputPath("gaming-profile").endsWith("game-gaming-profile-20260711-120000-000.mp4"))

        val secondCreated = repository.createProfile(
            "Gaming Profile",
            snapshot.copy(profileId = "gaming-profile", audioSources = emptyList()),
        )
        assertEquals("gaming-profile-2", secondCreated.selected.summary.id)

        val selected = repository.selectProfile("gaming-profile")
        repository.saveRecorderProfile(
            snapshot.copy(
                profileId = selected.selected.summary.id,
                preferences = selected.selected.preferences.copy(frameRate = 15),
            ),
        )
        assertEquals(15, repository.loadProfileCatalog().selected.preferences.frameRate)

        val afterDelete = repository.deleteProfile("gaming-profile")
        assertEquals("default", afterDelete.selected.summary.id)
        assertEquals(listOf("default", "gaming-profile-2"), afterDelete.profiles.map { it.id })

        repository.deleteProfile("gaming-profile-2")
        assertFailsWith<IllegalArgumentException> {
            repository.deleteProfile("default")
        }
    }

    @Test
    fun savesPositionWithoutChangingRecordingProfiles() {
        val path = Files.createTempDirectory("mission-recorder-desktop-ui").resolve("settings.json")
        val store = MissionRecorderSettingsStore(path)
        val repository = DesktopUiSettingsRepository(store)

        assertNull(repository.loadMiniControllerPosition())
        assertEquals(DesktopStartupSettings(), repository.loadStartupSettings())

        repository.saveMiniControllerPosition(DesktopWindowPosition(x = -420, y = 72))

        assertEquals(
            DesktopWindowPosition(x = -420, y = 72),
            repository.loadMiniControllerPosition(),
        )
        assertEquals("default", store.loadOrDefault().profiles.single().id)
    }

    @Test
    fun persistsRecentEditorMediaNewestFirstAcrossReloads() {
        val path = Files.createTempDirectory("mission-recorder-editor-history").resolve("settings.json")
        val repository = DesktopUiSettingsRepository(MissionRecorderSettingsStore(path))
        val first = Path.of("recordings", "first.mp4").toAbsolutePath().normalize().toString()
        val second = Path.of("recordings", "second.mp4").toAbsolutePath().normalize().toString()

        repository.saveRecentEditorMediaPath(first)
        repository.saveRecentEditorMediaPath(second)
        repository.saveRecentEditorMediaPath(first)

        assertEquals(listOf(first, second), repository.loadStartupSettings().recentEditorMediaPaths)
    }

    @Test
    fun persistsRecorderAndHotkeyPreferencesWithoutLosingWindowPosition() {
        val path = Files.createTempDirectory("mission-recorder-desktop-preferences").resolve("settings.json")
        val store = MissionRecorderSettingsStore(path)
        val repository = DesktopUiSettingsRepository(store)
        val position = DesktopWindowPosition(x = -640, y = 96)
        val preferences = DesktopRecorderPreferences(
            frameRate = 60,
            captureCursor = false,
            showInputOverlay = true,
            showMouseTrail = true,
            recordMouseTrail = true,
            replayDurationMinutes = 12,
            storyboardMode = StoryboardMode.ContactSheet,
            encoderSettings = EncoderSettings(
                videoBitrateBitsPerSecond = 18_000_000,
                audioBitrateBitsPerSecond = 160_000,
                keyframeIntervalSeconds = 3,
            ),
        )

        repository.saveMiniControllerPosition(position)
        repository.saveRecorderPreferences(preferences)
        repository.saveGlobalHotkeySettings(true, defaultDesktopGlobalHotkeys)
        repository.saveShowApplicationInRecording(true)
        repository.saveShowCaptureBorder(false)

        assertEquals(
            DesktopStartupSettings(
                recorderPreferences = preferences,
                globalHotkeysEnabled = true,
                showApplicationInRecording = true,
                showCaptureBorder = false,
            ),
            repository.loadStartupSettings(),
        )
        assertEquals(position, repository.loadMiniControllerPosition())
        val persistedProfile = store.loadOrDefault().profiles.single()
        assertEquals(60, persistedProfile.video.frameRate)
        assertEquals(false, persistedProfile.video.captureCursor)
        assertTrue(persistedProfile.video.showInputOverlay)
        assertTrue(persistedProfile.video.showMouseTrail)
        assertTrue(persistedProfile.video.recordMouseTrail)
        assertEquals(12 * 60L, persistedProfile.replay.durationSeconds)
        assertEquals(18_000_000, persistedProfile.encoder.videoBitrateBitsPerSecond)
        assertEquals(160_000, persistedProfile.encoder.audioBitrateBitsPerSecond)
        assertEquals(3, persistedProfile.encoder.keyframeIntervalSeconds)
    }

    @Test
    fun persistsCustomGlobalHotkeys() {
        val path = Files.createTempDirectory("mission-recorder-hotkeys").resolve("settings.json")
        val repository = DesktopUiSettingsRepository(MissionRecorderSettingsStore(path))
        val bindings = listOf(
            GlobalHotkeyBinding(
                action = GlobalHotkeyAction.ToggleRecording,
                gesture = GlobalHotkeyGesture(setOf(GlobalHotkeyModifier.Alt), GlobalHotkeyKey.K),
            ),
            GlobalHotkeyBinding(
                action = GlobalHotkeyAction.TogglePause,
                gesture = GlobalHotkeyGesture(setOf(GlobalHotkeyModifier.Control), GlobalHotkeyKey.F9),
            ),
            GlobalHotkeyBinding(
                action = GlobalHotkeyAction.SaveReplay,
                gesture = GlobalHotkeyGesture(setOf(GlobalHotkeyModifier.Meta), GlobalHotkeyKey.F11),
            ),
            GlobalHotkeyBinding(
                action = GlobalHotkeyAction.SelectRegion,
                gesture = GlobalHotkeyGesture(setOf(GlobalHotkeyModifier.Shift), GlobalHotkeyKey.F8),
            ),
            GlobalHotkeyBinding(
                action = GlobalHotkeyAction.MarkImportantFrame,
                gesture = GlobalHotkeyGesture(emptySet(), GlobalHotkeyKey.Numpad1),
            ),
            GlobalHotkeyBinding(
                action = GlobalHotkeyAction.SelectRegionAndStartRecording,
                gesture = GlobalHotkeyGesture(setOf(GlobalHotkeyModifier.Alt), GlobalHotkeyKey.F7),
            ),
        )

        repository.saveGlobalHotkeySettings(enabled = true, bindings = bindings)

        assertEquals(true, repository.loadStartupSettings().globalHotkeysEnabled)
        assertEquals(bindings, repository.loadStartupSettings().globalHotkeyBindings)
    }

    @Test
    fun resolvesPlatformSpecificLocalSettingsPaths() {
        val home = Path.of("home")
        val appData = Path.of("roaming")
        val xdg = Path.of("xdg")

        assertEquals(
            appData.resolve("Mission Recorder").resolve("settings.json"),
            resolveDesktopSettingsPath("Windows 11", home, appData, xdg),
        )
        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("Mission Recorder").resolve("settings.json"),
            resolveDesktopSettingsPath("Mac OS X", home, appData, xdg),
        )
        assertEquals(
            xdg.resolve("mission-recorder").resolve("settings.json"),
            resolveDesktopSettingsPath("Linux", home, appData, xdg),
        )
        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("Mission Recorder").resolve("settings.json"),
            resolveDesktopSettingsPath("Darwin", home, appData, xdg),
        )
    }

    @Test
    fun fallsBackToConventionalSettingsDirectories() {
        val home = Path.of("home")

        assertEquals(
            home.resolve("AppData").resolve("Roaming").resolve("Mission Recorder").resolve("settings.json"),
            resolveDesktopSettingsPath("Windows 10", home, appData = null, xdgConfigHome = null),
        )
        assertEquals(
            home.resolve(".config").resolve("mission-recorder").resolve("settings.json"),
            resolveDesktopSettingsPath("FreeBSD", home, appData = null, xdgConfigHome = null),
        )
    }
}
