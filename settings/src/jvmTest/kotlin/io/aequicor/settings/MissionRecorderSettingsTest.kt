package io.aequicor.settings

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.ContainerFormat
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MissionRecorderSettingsTest {
    @Test
    fun defaultSettingsAreValidAndSerializable() {
        val settings = MissionRecorderSettingsFactory.defaultLocal()

        assertEquals(emptyList(), MissionRecorderSettingsValidator.validate(settings))
        val defaultProfile = settings.profiles.single()
        assertTrue(defaultProfile.video.captureCursor)
        assertEquals(false, defaultProfile.video.showInputOverlay)
        assertTrue(defaultProfile.output.fileNamePattern.endsWith(".mp4"))

        val encoded = defaultSettingsJson.encodeToString(MissionRecorderSettings.serializer(), settings)
        val decoded = defaultSettingsJson.decodeFromString(MissionRecorderSettings.serializer(), encoded)

        assertEquals(settings, decoded)
    }

    @Test
    fun validatesProfileFields() {
        val settings = MissionRecorderSettings(
            defaultProfileId = "missing",
            profiles = listOf(
                MissionRecorderSettingsFactory.defaultLocal().profiles.single().copy(
                    id = "",
                    video = VideoSettings(frameRate = 0),
                    replay = ReplaySettings(enabled = true, durationSeconds = 0),
                    audio = AudioSettings(
                        sources = listOf(
                            AudioSourceSettings(
                                type = AudioSourceType.Microphone,
                                id = "",
                                displayName = "",
                                sampleRate = 0,
                                channelCount = 0,
                                gain = -1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val fields = MissionRecorderSettingsValidator.validate(settings).map { it.field }.toSet()

        assertTrue("defaultProfileId" in fields)
        assertTrue("profiles[0].id" in fields)
        assertTrue("profiles[0].video.frameRate" in fields)
        assertTrue("profiles[0].replay.durationSeconds" in fields)
        assertTrue("profiles[0].audio.sources[0].gain" in fields)
    }

    @Test
    fun convertsProfileToRecordingSettings() {
        val profile = MissionRecorderSettingsFactory.defaultLocal().profiles.single().copy(
            source = CaptureSourceSettings(
                type = CaptureSourceType.Region,
                id = "region:test",
                displayName = "Test region",
                region = CaptureRegionSettings(x = 10, y = 20, width = 640, height = 480),
            ),
            audio = AudioSettings(
                sources = listOf(
                    AudioSourceSettings(
                        type = AudioSourceType.SystemLoopback,
                        id = "system",
                        displayName = "System audio",
                        sampleRate = 48_000,
                        channelCount = 2,
                    ),
                ),
            ),
            encoder = EncoderProfileSettings(container = ContainerFormatSetting.Matroska),
            video = VideoSettings(showInputOverlay = true),
            output = OutputSettings(overwrite = true),
        )

        val recordingSettings = profile.toRecordingSettings(outputPath = "out.mrec")

        val source = assertIs<CaptureSource.Region>(recordingSettings.captureSource)
        assertEquals(640, source.region.width)
        assertEquals(1, recordingSettings.audioSources.size)
        assertEquals(ContainerFormat.Matroska, recordingSettings.encoder.container)
        assertTrue(recordingSettings.showInputOverlay)
        assertTrue(recordingSettings.overwriteOutput)
    }

    @Test
    fun roundTripsEncoderProfileThroughCommonDomainSettings() {
        val profile = EncoderProfileSettings(
            container = ContainerFormatSetting.Matroska,
            videoCodec = VideoCodecSetting.H265,
            audioCodec = AudioCodecSetting.Opus,
            videoBitrateBitsPerSecond = 14_000_000,
            audioBitrateBitsPerSecond = 160_000,
            keyframeIntervalSeconds = 4,
            hardwareAcceleration = HardwareAccelerationSetting.Disabled,
            pixelFormat = PixelFormatSetting.Nv12,
        )

        assertEquals(profile, profile.toEncoderSettings().toEncoderProfileSettings())
    }

    @Test
    fun roundTripsCaptureAndAudioSourcesThroughProfileSettings() {
        val region = CaptureSource.Region(
            id = CaptureSourceId("region:test"),
            displayName = "Test region",
            region = CaptureRegion(
                x = -120,
                y = 40,
                width = 1280,
                height = 720,
                monitorId = CaptureSourceId("monitor:2"),
                scaleFactor = 1.5,
            ),
        )
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = 44_100,
            channelCount = 1,
            gain = 0.65,
        )
        val profile = MissionRecorderSettingsFactory.defaultLocal().profiles.single().copy(
            source = region.toCaptureSourceSettings(),
            audio = AudioSettings(listOf(microphone.toAudioSourceSettings())),
        )

        val recording = profile.toRecordingSettings("capture.mp4")

        assertEquals(region, recording.captureSource)
        assertEquals(listOf(microphone), recording.audioSources)
    }

    @Test
    fun storeSavesAndLoadsAtomically() {
        val directory = Files.createTempDirectory("mission-recorder-settings")
        val path = directory.resolve("settings.json")
        val store = MissionRecorderSettingsStore(path)
        val settings = MissionRecorderSettingsFactory.defaultLocal().copy(
            desktopUi = DesktopUiSettings(
                miniController = MiniControllerSettings(
                    position = DesktopWindowPositionSettings(x = -420, y = 80),
                ),
            ),
        )

        store.save(settings)
        val loaded = store.loadOrDefault()

        assertTrue(path.exists())
        assertEquals(settings, loaded)
    }

    @Test
    fun readsSchemaOneDocumentWithoutDesktopUiState() {
        val raw = """
            {
              "schemaVersion": 1,
              "defaultProfileId": "default",
              "profiles": [
                {
                  "id": "default",
                  "name": "Default",
                  "source": { "type": "Screen", "id": "screen:all", "displayName": "All screens" }
                }
              ]
            }
        """.trimIndent()

        val settings = defaultSettingsJson.decodeFromString(MissionRecorderSettings.serializer(), raw)

        assertEquals(DesktopUiSettings(), settings.desktopUi)
    }

    @Test
    fun storeRejectsInvalidSettings() {
        val directory = Files.createTempDirectory("mission-recorder-settings-invalid")
        val store = MissionRecorderSettingsStore(directory.resolve("settings.json"))
        val invalid = MissionRecorderSettings(defaultProfileId = "", profiles = emptyList())

        assertFailsWith<SettingsValidationException> {
            store.save(invalid)
        }
    }

    @Test
    fun migratesLegacyDocumentWithoutSchemaVersion() {
        val raw = """
            {
              "defaultProfileId": "default",
              "profiles": [
                {
                  "id": "default",
                  "name": "Default",
                  "source": { "type": "Screen", "id": "screen:all", "displayName": "All screens" }
                }
              ]
            }
        """.trimIndent()

        val migrated = SettingsMigrator.migrate(raw)
        val settings = defaultSettingsJson.decodeFromString(MissionRecorderSettings.serializer(), migrated)

        assertEquals(CURRENT_SETTINGS_SCHEMA_VERSION, settings.schemaVersion)
        assertEquals("default", settings.defaultProfileId)
    }

    @Test
    fun migratesSchemaOneDefaultVideoBitrateWithoutOverwritingCustomQuality() {
        val raw = """
            {
              "schemaVersion": 1,
              "defaultProfileId": "default",
              "profiles": [
                {
                  "id": "default",
                  "name": "Default",
                  "source": { "type": "Screen", "id": "screen:all", "displayName": "All screens" },
                  "encoder": { "videoBitrateBitsPerSecond": 8000000 }
                },
                {
                  "id": "custom",
                  "name": "Custom",
                  "source": { "type": "Screen", "id": "screen:all", "displayName": "All screens" },
                  "encoder": { "videoBitrateBitsPerSecond": 42000000 }
                }
              ]
            }
        """.trimIndent()

        val migrated = SettingsMigrator.migrate(raw)
        val settings = defaultSettingsJson.decodeFromString(MissionRecorderSettings.serializer(), migrated)

        assertEquals(CURRENT_SETTINGS_SCHEMA_VERSION, settings.schemaVersion)
        assertEquals(24_000_000, settings.profiles[0].encoder.videoBitrateBitsPerSecond)
        assertEquals(42_000_000, settings.profiles[1].encoder.videoBitrateBitsPerSecond)
    }

    @Test
    fun rejectsDuplicateGlobalHotkeyGestures() {
        val defaults = MissionRecorderSettingsFactory.defaultLocal()
        val globalHotkeys = defaults.desktopUi.globalHotkeys
        val invalid = defaults.copy(
            desktopUi = defaults.desktopUi.copy(
                globalHotkeys = globalHotkeys.copy(togglePause = globalHotkeys.toggleRecording),
            ),
        )

        assertEquals(
            listOf("desktopUi.globalHotkeys"),
            MissionRecorderSettingsValidator.validate(invalid).map(SettingsValidationIssue::field),
        )
    }

    @Test
    fun addsRegionSelectionShortcutToExistingHotkeySettings() {
        val settings = defaultSettingsJson.decodeFromString(
            GlobalHotkeySettings.serializer(),
            """
                {
                  "toggleRecording": { "modifiers": ["Control", "Shift"], "key": "F9" },
                  "togglePause": { "modifiers": ["Control", "Shift"], "key": "F10" },
                  "saveReplay": { "modifiers": ["Control", "Shift"], "key": "F11" }
                }
            """.trimIndent(),
        )

        assertEquals(GlobalHotkeyKeySetting.F8, settings.selectRegion.key)
        assertEquals(
            setOf(GlobalHotkeyModifierSetting.Control, GlobalHotkeyModifierSetting.Shift),
            settings.selectRegion.modifiers,
        )
    }
}
