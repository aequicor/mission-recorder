package io.aequicor.capture.core

object RecordingSettingsValidator {
    fun validate(settings: RecordingSettings): List<ValidationIssue> = buildList {
        if (settings.outputPath.isBlank()) {
            add(ValidationIssue("outputPath", "Output path must not be blank."))
        }
        if (settings.frameRate !in 1..240) {
            add(ValidationIssue("frameRate", "Frame rate must be between 1 and 240."))
        }
        if (settings.captureSource is CaptureSource.Region) {
            validateRegion(settings.captureSource.region).forEach(::add)
        }
        if (settings.replayDuration != null && !settings.replayDuration.isPositive()) {
            add(ValidationIssue("replayDuration", "Replay duration must be positive."))
        }
        if (settings.encoder.videoBitrateBitsPerSecond <= 0) {
            add(ValidationIssue("encoder.videoBitrateBitsPerSecond", "Video bitrate must be positive."))
        }
        if (settings.encoder.audioBitrateBitsPerSecond <= 0) {
            add(ValidationIssue("encoder.audioBitrateBitsPerSecond", "Audio bitrate must be positive."))
        }
        if (settings.encoder.keyframeIntervalSeconds <= 0) {
            add(ValidationIssue("encoder.keyframeIntervalSeconds", "Keyframe interval must be positive."))
        }
        settings.audioSources.forEachIndexed { index, source ->
            when (source) {
                is AudioSource.Microphone -> validateAudioSource(
                    index,
                    source.sampleRate,
                    source.channelCount,
                    source.gain,
                )
                is AudioSource.SystemLoopback -> validateAudioSource(
                    index,
                    source.sampleRate,
                    source.channelCount,
                    source.gain,
                )
            }
        }
    }

    private fun validateRegion(region: CaptureRegion): List<ValidationIssue> = buildList {
        if (region.width <= 0) {
            add(ValidationIssue("captureSource.region.width", "Region width must be positive."))
        }
        if (region.height <= 0) {
            add(ValidationIssue("captureSource.region.height", "Region height must be positive."))
        }
        if (region.scaleFactor <= 0.0) {
            add(ValidationIssue("captureSource.region.scaleFactor", "Region scale factor must be positive."))
        }
    }

    private fun MutableList<ValidationIssue>.validateAudioSource(
        index: Int,
        sampleRate: Int,
        channelCount: Int,
        gain: Double,
    ) {
        if (sampleRate <= 0) {
            add(ValidationIssue("audioSources[$index].sampleRate", "Audio sample rate must be positive."))
        }
        if (channelCount <= 0) {
            add(ValidationIssue("audioSources[$index].channelCount", "Audio channel count must be positive."))
        }
        if (!gain.isFinite() || gain !in 0.0..MAX_AUDIO_SOURCE_GAIN) {
            add(ValidationIssue("audioSources[$index].gain", "Audio gain must be between 0% and 200%."))
        }
    }
}
