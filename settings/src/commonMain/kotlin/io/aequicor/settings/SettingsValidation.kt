package io.aequicor.settings

import io.aequicor.capture.core.MAX_AUDIO_SOURCE_GAIN

object MissionRecorderSettingsValidator {
    fun validate(settings: MissionRecorderSettings): List<SettingsValidationIssue> = buildList {
        if (settings.schemaVersion != CURRENT_SETTINGS_SCHEMA_VERSION) {
            add(SettingsValidationIssue("schemaVersion", "Unsupported schema version: ${settings.schemaVersion}."))
        }
        if (settings.profiles.isEmpty()) {
            add(SettingsValidationIssue("profiles", "At least one recording profile is required."))
        }
        if (settings.defaultProfileId.isBlank()) {
            add(SettingsValidationIssue("defaultProfileId", "Default profile id must not be blank."))
        }
        if (settings.profiles.none { it.id == settings.defaultProfileId }) {
            add(SettingsValidationIssue("defaultProfileId", "Default profile id must reference an existing profile."))
        }
        settings.profiles
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { add(SettingsValidationIssue("profiles", "Duplicate profile id: $it.")) }
        settings.profiles.forEachIndexed { index, profile ->
            validateProfile(index, profile)
        }
        validateGlobalHotkeys(settings.desktopUi.globalHotkeys)
    }

    private fun MutableList<SettingsValidationIssue>.validateGlobalHotkeys(settings: GlobalHotkeySettings) {
        val gestures = listOf(
            settings.selectRegionAndStartRecording,
            settings.selectRegion,
            settings.toggleRecording,
            settings.togglePause,
            settings.saveReplay,
            settings.markImportantFrame,
        )
        if (gestures.distinct().size != gestures.size) {
            add(SettingsValidationIssue("desktopUi.globalHotkeys", "Global hotkey gestures must be unique."))
        }
    }

    private fun MutableList<SettingsValidationIssue>.validateProfile(index: Int, profile: RecordingProfileSettings) {
        val prefix = "profiles[$index]"
        if (profile.id.isBlank()) {
            add(SettingsValidationIssue("$prefix.id", "Profile id must not be blank."))
        }
        if (profile.name.isBlank()) {
            add(SettingsValidationIssue("$prefix.name", "Profile name must not be blank."))
        }
        validateSource("$prefix.source", profile.source)
        validateOutput("$prefix.output", profile.output)
        validateVideo("$prefix.video", profile.video)
        validateAudio("$prefix.audio", profile.audio)
        validateEncoder("$prefix.encoder", profile.encoder)
        validateReplay("$prefix.replay", profile.replay)
        validateExport("$prefix.export", profile.export)
    }

    private fun MutableList<SettingsValidationIssue>.validateSource(prefix: String, source: CaptureSourceSettings) {
        if (source.id.isBlank()) {
            add(SettingsValidationIssue("$prefix.id", "Capture source id must not be blank."))
        }
        if (source.displayName.isBlank()) {
            add(SettingsValidationIssue("$prefix.displayName", "Capture source display name must not be blank."))
        }
        if (source.type == CaptureSourceType.Monitor && source.monitorIndex == null) {
            add(SettingsValidationIssue("$prefix.monitorIndex", "Monitor source requires monitor index."))
        }
        if (source.type == CaptureSourceType.Region) {
            val region = source.region
            if (region == null) {
                add(SettingsValidationIssue("$prefix.region", "Region source requires region settings."))
            } else {
                if (region.width <= 0) {
                    add(SettingsValidationIssue("$prefix.region.width", "Region width must be positive."))
                }
                if (region.height <= 0) {
                    add(SettingsValidationIssue("$prefix.region.height", "Region height must be positive."))
                }
                if (region.scaleFactor <= 0.0) {
                    add(SettingsValidationIssue("$prefix.region.scaleFactor", "Region scale factor must be positive."))
                }
            }
        }
    }

    private fun MutableList<SettingsValidationIssue>.validateOutput(prefix: String, output: OutputSettings) {
        if (output.directory.isBlank()) {
            add(SettingsValidationIssue("$prefix.directory", "Output directory must not be blank."))
        }
        if (output.fileNamePattern.isBlank()) {
            add(SettingsValidationIssue("$prefix.fileNamePattern", "Output file name pattern must not be blank."))
        }
    }

    private fun MutableList<SettingsValidationIssue>.validateVideo(prefix: String, video: VideoSettings) {
        if (video.frameRate !in 1..240) {
            add(SettingsValidationIssue("$prefix.frameRate", "Frame rate must be between 1 and 240."))
        }
    }

    private fun MutableList<SettingsValidationIssue>.validateAudio(prefix: String, audio: AudioSettings) {
        audio.sources.forEachIndexed { index, source ->
            val sourcePrefix = "$prefix.sources[$index]"
            if (source.id.isBlank()) {
                add(SettingsValidationIssue("$sourcePrefix.id", "Audio source id must not be blank."))
            }
            if (source.displayName.isBlank()) {
                add(SettingsValidationIssue("$sourcePrefix.displayName", "Audio source display name must not be blank."))
            }
            if (source.sampleRate <= 0) {
                add(SettingsValidationIssue("$sourcePrefix.sampleRate", "Audio sample rate must be positive."))
            }
            if (source.channelCount <= 0) {
                add(SettingsValidationIssue("$sourcePrefix.channelCount", "Audio channel count must be positive."))
            }
            if (!source.gain.isFinite() || source.gain !in 0.0..MAX_AUDIO_SOURCE_GAIN) {
                add(SettingsValidationIssue("$sourcePrefix.gain", "Audio gain must be between 0% and 200%."))
            }
        }
    }

    private fun MutableList<SettingsValidationIssue>.validateEncoder(prefix: String, encoder: EncoderProfileSettings) {
        if (encoder.videoBitrateBitsPerSecond <= 0) {
            add(SettingsValidationIssue("$prefix.videoBitrateBitsPerSecond", "Video bitrate must be positive."))
        }
        if (encoder.audioBitrateBitsPerSecond <= 0) {
            add(SettingsValidationIssue("$prefix.audioBitrateBitsPerSecond", "Audio bitrate must be positive."))
        }
        if (encoder.keyframeIntervalSeconds <= 0) {
            add(SettingsValidationIssue("$prefix.keyframeIntervalSeconds", "Keyframe interval must be positive."))
        }
    }

    private fun MutableList<SettingsValidationIssue>.validateReplay(prefix: String, replay: ReplaySettings) {
        if (replay.enabled && replay.durationSeconds <= 0) {
            add(SettingsValidationIssue("$prefix.durationSeconds", "Replay duration must be positive when replay is enabled."))
        }
        if (replay.maxVideoFrames != null && replay.maxVideoFrames <= 0) {
            add(SettingsValidationIssue("$prefix.maxVideoFrames", "Replay max video frames must be positive."))
        }
        if (replay.maxAudioFrames != null && replay.maxAudioFrames <= 0) {
            add(SettingsValidationIssue("$prefix.maxAudioFrames", "Replay max audio frames must be positive."))
        }
    }

    private fun MutableList<SettingsValidationIssue>.validateExport(prefix: String, export: ExportProfileSettings) {
        if (export.targetFps != null && export.targetFps <= 0) {
            add(SettingsValidationIssue("$prefix.targetFps", "Export target fps must be positive."))
        }
    }
}

data class SettingsValidationIssue(
    val field: String,
    val message: String,
)

class SettingsValidationException(
    val issues: List<SettingsValidationIssue>,
) : IllegalArgumentException(issues.joinToString("; ") { "${it.field}: ${it.message}" })
