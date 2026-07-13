package io.aequicor.desktop

import io.aequicor.settings.AudioSettings
import io.aequicor.settings.DesktopWindowPositionSettings
import io.aequicor.settings.MissionRecorderSettings
import io.aequicor.settings.MissionRecorderSettingsStore
import io.aequicor.settings.RecordingProfileSettings
import io.aequicor.settings.StoryboardLayoutSetting
import io.aequicor.settings.toAudioSourceSettings
import io.aequicor.settings.toCaptureSourceSettings
import io.aequicor.settings.toEncoderProfileSettings
import io.aequicor.settings.toEncoderSettings
import io.aequicor.settings.toRecordingSettings
import io.aequicor.compose.ui.StoryboardMode
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class DesktopWindowPosition(
    val x: Int,
    val y: Int,
)

internal class DesktopUiSettingsRepository(
    private val store: MissionRecorderSettingsStore,
    private val outputTimestamp: () -> String = {
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
    },
) {
    private val lock = Any()

    fun loadStartupSettings(): DesktopStartupSettings = synchronized(lock) {
        val settings = store.loadOrDefault()
        val profile = requireNotNull(settings.profiles.firstOrNull { profile ->
            profile.id == settings.defaultProfileId
        }) { "Default recording profile is missing." }
        DesktopStartupSettings(
            recorderPreferences = DesktopRecorderPreferences(
                frameRate = profile.video.frameRate.takeIf { it in SUPPORTED_DESKTOP_FRAME_RATES }
                    ?: DEFAULT_DESKTOP_FRAME_RATE,
                captureCursor = profile.video.captureCursor,
                replayDurationMinutes = (profile.replay.durationSeconds / SECONDS_PER_MINUTE)
                    .coerceIn(MIN_REPLAY_MINUTES.toLong(), MAX_REPLAY_MINUTES.toLong())
                    .toInt(),
                storyboardMode = when (settings.desktopUi.storyboardLayout) {
                    StoryboardLayoutSetting.SeparatePngFiles -> StoryboardMode.SeparatePngFiles
                    StoryboardLayoutSetting.ContactSheet -> StoryboardMode.ContactSheet
                },
                encoderSettings = profile.encoder.toEncoderSettings(),
            ),
            globalHotkeysEnabled = settings.desktopUi.globalHotkeysEnabled,
            showApplicationInRecording = settings.desktopUi.showApplicationInRecording,
        )
    }

    fun loadProfileCatalog(): DesktopRecorderProfileCatalog = synchronized(lock) {
        store.loadOrDefault().toProfileCatalog()
    }

    fun nextOutputPath(profileId: String): String = synchronized(lock) {
        val profile = requireNotNull(store.loadOrDefault().profiles.firstOrNull { profile -> profile.id == profileId }) {
            "Recording profile does not exist: $profileId"
        }
        profile.resolveOutputPath()
    }

    fun previewOutputPath(profileId: String, outputPolicy: DesktopOutputPolicy): String = synchronized(lock) {
        validateOutputPolicy(outputPolicy)
        outputPolicy.resolveOutputPath(profileId)
    }

    fun selectProfile(profileId: String): DesktopRecorderProfileCatalog = synchronized(lock) {
        val settings = store.loadOrDefault()
        require(settings.profiles.any { profile -> profile.id == profileId }) {
            "Recording profile does not exist: $profileId"
        }
        settings.copy(defaultProfileId = profileId).also(store::save).toProfileCatalog()
    }

    fun createProfile(
        name: String,
        snapshot: DesktopRecorderProfileSnapshot,
    ): DesktopRecorderProfileCatalog = synchronized(lock) {
        validateOutputPolicy(snapshot.outputPolicy)
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Profile name must not be blank." }
        val settings = store.loadOrDefault()
        val sourceProfile = requireNotNull(settings.profiles.firstOrNull { profile ->
            profile.id == snapshot.profileId
        }) { "Recording profile does not exist: ${snapshot.profileId}" }
        val id = uniqueProfileId(normalizedName, settings.profiles.mapTo(mutableSetOf()) { it.id })
        val created = sourceProfile
            .copy(id = id, name = normalizedName)
            .withSnapshot(snapshot.copy(profileId = id))
        settings.copy(
            defaultProfileId = id,
            profiles = settings.profiles + created,
        ).also(store::save).toProfileCatalog()
    }

    fun deleteProfile(profileId: String): DesktopRecorderProfileCatalog = synchronized(lock) {
        val settings = store.loadOrDefault()
        require(settings.profiles.size > 1) { "At least one recording profile must remain." }
        require(settings.profiles.any { profile -> profile.id == profileId }) {
            "Recording profile does not exist: $profileId"
        }
        val remaining = settings.profiles.filterNot { profile -> profile.id == profileId }
        val selectedId = settings.defaultProfileId.takeUnless { it == profileId } ?: remaining.first().id
        settings.copy(defaultProfileId = selectedId, profiles = remaining)
            .also(store::save)
            .toProfileCatalog()
    }

    fun saveRecorderProfile(snapshot: DesktopRecorderProfileSnapshot) = synchronized(lock) {
        validatePreferences(snapshot.preferences)
        validateOutputPolicy(snapshot.outputPolicy)
        val settings = store.loadOrDefault()
        var updated = false
        val profiles = settings.profiles.map { profile ->
            if (profile.id == snapshot.profileId) {
                updated = true
                profile.withSnapshot(snapshot)
            } else {
                profile
            }
        }
        require(updated) { "Recording profile does not exist: ${snapshot.profileId}" }
        store.save(settings.copy(profiles = profiles, desktopUi = settings.desktopUi.with(snapshot.preferences)))
    }

    fun loadMiniControllerPosition(): DesktopWindowPosition? = synchronized(lock) {
        store.loadOrDefault()
            .desktopUi
            .miniController
            .position
            ?.let { position -> DesktopWindowPosition(x = position.x, y = position.y) }
    }

    fun saveMiniControllerPosition(position: DesktopWindowPosition) = synchronized(lock) {
        val settings = store.loadOrDefault()
        store.save(
            settings.copy(
                desktopUi = settings.desktopUi.copy(
                    miniController = settings.desktopUi.miniController.copy(
                        position = DesktopWindowPositionSettings(x = position.x, y = position.y),
                    ),
                ),
            ),
        )
    }

    fun saveRecorderPreferences(preferences: DesktopRecorderPreferences) = synchronized(lock) {
        validatePreferences(preferences)
        val settings = store.loadOrDefault()
        var updatedDefaultProfile = false
        val profiles = settings.profiles.map { profile ->
            if (profile.id == settings.defaultProfileId) {
                updatedDefaultProfile = true
                profile.copy(
                    video = profile.video.copy(
                        frameRate = preferences.frameRate,
                        captureCursor = preferences.captureCursor,
                    ),
                    replay = profile.replay.copy(
                        durationSeconds = preferences.replayDurationMinutes * SECONDS_PER_MINUTE,
                    ),
                    encoder = preferences.encoderSettings.toEncoderProfileSettings(),
                )
            } else {
                profile
            }
        }
        require(updatedDefaultProfile) { "Default recording profile is missing." }
        store.save(
            settings.copy(
                profiles = profiles,
                desktopUi = settings.desktopUi.copy(
                    storyboardLayout = when (preferences.storyboardMode) {
                        StoryboardMode.SeparatePngFiles -> StoryboardLayoutSetting.SeparatePngFiles
                        StoryboardMode.ContactSheet -> StoryboardLayoutSetting.ContactSheet
                    },
                ),
            ),
        )
    }

    fun saveGlobalHotkeysEnabled(enabled: Boolean) = synchronized(lock) {
        val settings = store.loadOrDefault()
        store.save(settings.copy(desktopUi = settings.desktopUi.copy(globalHotkeysEnabled = enabled)))
    }

    fun saveShowApplicationInRecording(enabled: Boolean) = synchronized(lock) {
        val settings = store.loadOrDefault()
        store.save(settings.copy(desktopUi = settings.desktopUi.copy(showApplicationInRecording = enabled)))
    }

    private fun MissionRecorderSettings.toProfileCatalog(): DesktopRecorderProfileCatalog {
        val selectedProfile = requireNotNull(profiles.firstOrNull { profile -> profile.id == defaultProfileId }) {
            "Default recording profile is missing."
        }
        return DesktopRecorderProfileCatalog(
            profiles = profiles.map { profile -> DesktopRecorderProfileSummary(profile.id, profile.name) },
            selected = selectedProfile.toDesktopConfiguration(desktopUi.storyboardLayout),
        )
    }

    private fun RecordingProfileSettings.toDesktopConfiguration(
        storyboardLayout: StoryboardLayoutSetting,
    ): DesktopRecorderProfileConfiguration {
        val recording = toRecordingSettings(resolveOutputPath())
        return DesktopRecorderProfileConfiguration(
            summary = DesktopRecorderProfileSummary(id, name),
            preferences = DesktopRecorderPreferences(
                frameRate = video.frameRate.takeIf { it in SUPPORTED_DESKTOP_FRAME_RATES }
                    ?: DEFAULT_DESKTOP_FRAME_RATE,
                captureCursor = video.captureCursor,
                replayDurationMinutes = (replay.durationSeconds / SECONDS_PER_MINUTE)
                    .coerceIn(MIN_REPLAY_MINUTES.toLong(), MAX_REPLAY_MINUTES.toLong())
                    .toInt(),
                storyboardMode = storyboardLayout.toStoryboardMode(),
                encoderSettings = encoder.toEncoderSettings(),
            ),
            captureSource = recording.captureSource,
            audioSources = recording.audioSources,
            outputPath = recording.outputPath,
            overwriteOutput = recording.overwriteOutput,
            outputPolicy = DesktopOutputPolicy(
                directory = output.directory,
                fileNamePattern = output.fileNamePattern,
            ),
        )
    }

    private fun RecordingProfileSettings.withSnapshot(
        snapshot: DesktopRecorderProfileSnapshot,
    ): RecordingProfileSettings = copy(
        source = snapshot.captureSource.toCaptureSourceSettings(),
        video = video.copy(
            frameRate = snapshot.preferences.frameRate,
            captureCursor = snapshot.preferences.captureCursor,
        ),
        audio = AudioSettings(snapshot.audioSources.map { source -> source.toAudioSourceSettings() }),
        output = output.copy(
            directory = snapshot.outputPolicy.directory,
            fileNamePattern = snapshot.outputPolicy.fileNamePattern,
            overwrite = snapshot.overwriteOutput,
        ),
        encoder = snapshot.preferences.encoderSettings.toEncoderProfileSettings(),
        replay = replay.copy(
            durationSeconds = snapshot.preferences.replayDurationMinutes * SECONDS_PER_MINUTE,
        ),
    )

    private fun RecordingProfileSettings.resolveOutputPath(): String {
        return DesktopOutputPolicy(
            directory = output.directory,
            fileNamePattern = output.fileNamePattern,
        ).resolveOutputPath(id)
    }

    private fun DesktopOutputPolicy.resolveOutputPath(profileId: String): String {
        val fileName = fileNamePattern
            .replace("{timestamp}", outputTimestamp())
            .replace("{profile}", profileId)
        return Path.of(directory).toAbsolutePath().normalize().resolve(fileName).toString()
    }
}

private fun validatePreferences(preferences: DesktopRecorderPreferences) {
    require(preferences.frameRate in SUPPORTED_DESKTOP_FRAME_RATES)
    require(preferences.replayDurationMinutes in MIN_REPLAY_MINUTES..MAX_REPLAY_MINUTES)
}

private fun validateOutputPolicy(outputPolicy: DesktopOutputPolicy) {
    require(outputPolicy.directory.isNotBlank()) { "Output directory must not be blank." }
    require(outputPolicy.fileNamePattern.isNotBlank()) { "Output file name pattern must not be blank." }
    require('/' !in outputPolicy.fileNamePattern && '\\' !in outputPolicy.fileNamePattern) {
        "Output file name pattern must not contain path separators."
    }
    require(outputPolicy.fileNamePattern.endsWith(".mp4", ignoreCase = true)) {
        "Output file name pattern must use the .mp4 extension."
    }
}

private fun io.aequicor.settings.DesktopUiSettings.with(
    preferences: DesktopRecorderPreferences,
) = copy(
    storyboardLayout = when (preferences.storyboardMode) {
        StoryboardMode.SeparatePngFiles -> StoryboardLayoutSetting.SeparatePngFiles
        StoryboardMode.ContactSheet -> StoryboardLayoutSetting.ContactSheet
    },
)

private fun StoryboardLayoutSetting.toStoryboardMode(): StoryboardMode = when (this) {
    StoryboardLayoutSetting.SeparatePngFiles -> StoryboardMode.SeparatePngFiles
    StoryboardLayoutSetting.ContactSheet -> StoryboardMode.ContactSheet
}

private fun uniqueProfileId(name: String, existingIds: Set<String>): String {
    val base = name
        .lowercase()
        .map { character -> if (character in 'a'..'z' || character in '0'..'9') character else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifEmpty { "profile" }
    if (base !in existingIds) {
        return base
    }
    var suffix = 2
    while ("$base-$suffix" in existingIds) {
        suffix++
    }
    return "$base-$suffix"
}

internal fun desktopSettingsPath(): Path = resolveDesktopSettingsPath(
    osName = System.getProperty("os.name").orEmpty(),
    userHome = Path.of(System.getProperty("user.home")),
    appData = environmentPath("APPDATA"),
    xdgConfigHome = environmentPath("XDG_CONFIG_HOME"),
)

internal fun resolveDesktopSettingsPath(
    osName: String,
    userHome: Path,
    appData: Path?,
    xdgConfigHome: Path?,
): Path {
    val normalizedOsName = osName.lowercase()
    val directory = when {
        normalizedOsName.startsWith("windows") ->
            (appData ?: userHome.resolve("AppData").resolve("Roaming"))
                .resolve("Mission Recorder")
        normalizedOsName.contains("mac") || normalizedOsName == "darwin" ->
            userHome.resolve("Library").resolve("Application Support").resolve("Mission Recorder")
        else ->
            (xdgConfigHome ?: userHome.resolve(".config")).resolve("mission-recorder")
    }
    return directory.resolve("settings.json")
}

private fun environmentPath(name: String): Path? =
    System.getenv(name)
        ?.takeIf(String::isNotBlank)
        ?.let { value -> Path.of(value) }

private const val SECONDS_PER_MINUTE = 60L
