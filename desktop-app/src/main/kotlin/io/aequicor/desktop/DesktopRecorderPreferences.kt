package io.aequicor.desktop

import io.aequicor.capture.core.EncoderSettings
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.CaptureSource
import io.aequicor.compose.ui.StoryboardMode
import io.aequicor.compose.ui.MAX_VIDEO_BITRATE_MBPS
import io.aequicor.compose.ui.MIN_VIDEO_BITRATE_MBPS
import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys

internal data class DesktopRecorderPreferences(
    val frameRate: Int = DEFAULT_DESKTOP_FRAME_RATE,
    val captureCursor: Boolean = true,
    val showInputOverlay: Boolean = false,
    val recordMouseTrail: Boolean = false,
    val replayDurationMinutes: Int = DEFAULT_REPLAY_MINUTES,
    val storyboardMode: StoryboardMode = StoryboardMode.SeparatePngFiles,
    val encoderSettings: EncoderSettings = EncoderSettings(),
) {
    val videoBitrateMbps: Int
        get() = (
            (encoderSettings.videoBitrateBitsPerSecond.toLong() + BITS_PER_HALF_MEGABIT) /
                BITS_PER_MEGABIT
            ).toInt()
            .coerceIn(MIN_VIDEO_BITRATE_MBPS, MAX_VIDEO_BITRATE_MBPS)
}

internal data class DesktopStartupSettings(
    val recorderPreferences: DesktopRecorderPreferences = DesktopRecorderPreferences(),
    val recentEditorMediaPaths: List<String> = emptyList(),
    val globalHotkeysEnabled: Boolean = true,
    val globalHotkeyBindings: List<GlobalHotkeyBinding> = defaultDesktopGlobalHotkeys,
    val showApplicationInRecording: Boolean = false,
    val showCaptureBorder: Boolean = true,
)

internal data class DesktopRecorderProfileSummary(
    val id: String,
    val name: String,
)

internal data class DesktopOutputPolicy(
    val directory: String = "recordings",
    val fileNamePattern: String = "mission-{timestamp}.mp4",
)

internal data class DesktopRecorderProfileConfiguration(
    val summary: DesktopRecorderProfileSummary,
    val preferences: DesktopRecorderPreferences,
    val captureSource: CaptureSource,
    val audioSources: List<AudioSource>,
    val outputPath: String,
    val overwriteOutput: Boolean = false,
    val outputPolicy: DesktopOutputPolicy = DesktopOutputPolicy(),
)

internal data class DesktopRecorderProfileCatalog(
    val profiles: List<DesktopRecorderProfileSummary>,
    val selected: DesktopRecorderProfileConfiguration,
)

internal data class DesktopRecorderProfileSnapshot(
    val profileId: String,
    val preferences: DesktopRecorderPreferences,
    val captureSource: CaptureSource,
    val audioSources: List<AudioSource>,
    val overwriteOutput: Boolean = false,
    val outputPolicy: DesktopOutputPolicy = DesktopOutputPolicy(),
)

internal interface DesktopRecorderProfileStore {
    suspend fun save(snapshot: DesktopRecorderProfileSnapshot)
    suspend fun select(profileId: String): DesktopRecorderProfileCatalog
    suspend fun create(name: String, snapshot: DesktopRecorderProfileSnapshot): DesktopRecorderProfileCatalog
    suspend fun delete(profileId: String): DesktopRecorderProfileCatalog
    fun nextOutputPath(profileId: String): String?
    fun previewOutputPath(profileId: String, outputPolicy: DesktopOutputPolicy): String? = null
}

internal class RepositoryDesktopRecorderProfileStore(
    private val repository: DesktopUiSettingsRepository,
) : DesktopRecorderProfileStore {
    override suspend fun save(snapshot: DesktopRecorderProfileSnapshot) = repository.saveRecorderProfile(snapshot)

    override suspend fun select(profileId: String): DesktopRecorderProfileCatalog = repository.selectProfile(profileId)

    override suspend fun create(
        name: String,
        snapshot: DesktopRecorderProfileSnapshot,
    ): DesktopRecorderProfileCatalog = repository.createProfile(name, snapshot)

    override suspend fun delete(profileId: String): DesktopRecorderProfileCatalog = repository.deleteProfile(profileId)

    override fun nextOutputPath(profileId: String): String = repository.nextOutputPath(profileId)

    override fun previewOutputPath(profileId: String, outputPolicy: DesktopOutputPolicy): String =
        repository.previewOutputPath(profileId, outputPolicy)
}

internal data object NoopDesktopRecorderProfileStore : DesktopRecorderProfileStore {
    override suspend fun save(snapshot: DesktopRecorderProfileSnapshot) = Unit

    override suspend fun select(profileId: String): DesktopRecorderProfileCatalog =
        error("Profile selection is unavailable.")

    override suspend fun create(
        name: String,
        snapshot: DesktopRecorderProfileSnapshot,
    ): DesktopRecorderProfileCatalog = error("Profile creation is unavailable.")

    override suspend fun delete(profileId: String): DesktopRecorderProfileCatalog =
        error("Profile deletion is unavailable.")

    override fun nextOutputPath(profileId: String): String? = null

    override fun previewOutputPath(profileId: String, outputPolicy: DesktopOutputPolicy): String? = null
}

internal fun interface DesktopRecorderPreferencesWriter {
    suspend fun save(preferences: DesktopRecorderPreferences)
}

internal data object NoopDesktopRecorderPreferencesWriter : DesktopRecorderPreferencesWriter {
    override suspend fun save(preferences: DesktopRecorderPreferences) = Unit
}

internal val SUPPORTED_DESKTOP_FRAME_RATES: Set<Int> = setOf(15, 30, 60)
internal const val DEFAULT_DESKTOP_FRAME_RATE: Int = 30
internal const val MIN_REPLAY_MINUTES: Int = 1
internal const val MAX_REPLAY_MINUTES: Int = 60
internal const val DEFAULT_REPLAY_MINUTES: Int = 5

internal const val BITS_PER_MEGABIT: Long = 1_000_000L
private const val BITS_PER_HALF_MEGABIT: Long = BITS_PER_MEGABIT / 2
