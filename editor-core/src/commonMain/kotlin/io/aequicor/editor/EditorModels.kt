package io.aequicor.editor

/** Stable identifier of a media asset in an editor project. */
@JvmInline
public value class MediaAssetId(public val value: String)

/** Stable identifier of a timeline track. */
@JvmInline
public value class EditorTrackId(public val value: String)

/** Stable identifier of a timeline clip. */
@JvmInline
public value class EditorClipId(public val value: String)

/** Stable identifier of an important-frame marker. */
@JvmInline
public value class ImportantFrameId(public val value: String)

/** Media type detected while probing an imported file. */
public enum class MediaAssetKind {
    Video,
    Audio,
    Image,
}

/** Immutable metadata for a file used by a project. */
public data class MediaAsset(
    public val id: MediaAssetId,
    public val path: String,
    public val displayName: String,
    public val kind: MediaAssetKind,
    public val durationMicros: Long,
    public val width: Int = 0,
    public val height: Int = 0,
    public val frameRate: Double = 0.0,
    public val hasAudio: Boolean = false,
    public val available: Boolean = true,
) {
    init {
        require(path.isNotBlank()) { "Media asset path must not be blank." }
        require(displayName.isNotBlank()) { "Media asset name must not be blank." }
        require(durationMicros >= 0L) { "Media duration must not be negative." }
        require(width >= 0 && height >= 0) { "Media dimensions must not be negative." }
        require(frameRate >= 0.0) { "Media frame rate must not be negative." }
    }
}

/** Type and compositing behavior of a timeline track. */
public enum class EditorTrackKind {
    Video,
    Audio,
    Text,
}

/** Normalized crop rectangle within a source frame. */
public data class NormalizedCrop(
    public val left: Float = 0f,
    public val top: Float = 0f,
    public val right: Float = 1f,
    public val bottom: Float = 1f,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Crop edges must be normalized."
        }
        require(left < right && top < bottom) { "Crop rectangle must have a positive size." }
    }
}

/** Spatial transformation applied after a media clip is decoded. */
public data class ClipTransform(
    public val crop: NormalizedCrop = NormalizedCrop(),
    public val positionX: Float = 0.5f,
    public val positionY: Float = 0.5f,
    public val scale: Float = 1f,
    public val opacity: Float = 1f,
) {
    init {
        require(positionX in 0f..1f && positionY in 0f..1f) { "Clip position must be normalized." }
        require(scale in 0.05f..8f) { "Clip scale must be between 0.05 and 8." }
        require(opacity in 0f..1f) { "Clip opacity must be normalized." }
    }
}

/** Basic non-destructive effects applied to a media clip. */
public data class ClipEffects(
    public val brightness: Float = 0f,
    public val contrast: Float = 1f,
    public val saturation: Float = 1f,
    public val fadeInMicros: Long = 0L,
    public val fadeOutMicros: Long = 0L,
    public val volume: Float = 1f,
    public val muted: Boolean = false,
) {
    init {
        require(brightness in -1f..1f) { "Brightness must be between -1 and 1." }
        require(contrast in 0f..2f) { "Contrast must be between 0 and 2." }
        require(saturation in 0f..2f) { "Saturation must be between 0 and 2." }
        require(fadeInMicros >= 0L && fadeOutMicros >= 0L) { "Fade duration must not be negative." }
        require(volume in 0f..2f) { "Volume must be between 0 and 2." }
    }
}

/** Transition from this clip into the following clip on the same video track. */
public sealed interface Transition {
    /** No transition is applied. */
    public data object None : Transition

    /** Cross-fades through the overlap with the following clip. */
    public data class Crossfade(public val durationMicros: Long) : Transition {
        init {
            require(durationMicros > 0L) { "Crossfade duration must be positive." }
        }
    }
}

/** Horizontal alignment for a text overlay. */
public enum class EditorTextAlignment {
    Start,
    Center,
    End,
}

/** Item placed on an editor timeline. */
public sealed interface EditorClip {
    public val id: EditorClipId
    public val timelineStartMicros: Long
    public val durationMicros: Long

    /** Exclusive end timestamp of the clip on the project timeline. */
    public val timelineEndMicros: Long
        get() = timelineStartMicros + durationMicros

    /** Video, image, or audio media placed on a track. */
    public data class Media(
        override val id: EditorClipId,
        public val assetId: MediaAssetId,
        override val timelineStartMicros: Long,
        override val durationMicros: Long,
        public val sourceStartMicros: Long = 0L,
        public val speed: Float = 1f,
        public val transform: ClipTransform = ClipTransform(),
        public val effects: ClipEffects = ClipEffects(),
        public val transition: Transition = Transition.None,
    ) : EditorClip {
        init {
            require(timelineStartMicros >= 0L) { "Clip start must not be negative." }
            require(durationMicros > 0L) { "Clip duration must be positive." }
            require(sourceStartMicros >= 0L) { "Source start must not be negative." }
            require(speed in 0.5f..2f) { "Clip speed must be between 0.5 and 2." }
        }
    }

    /** Text overlay placed on a text track. */
    public data class Text(
        override val id: EditorClipId,
        override val timelineStartMicros: Long,
        override val durationMicros: Long,
        public val text: String,
        public val positionX: Float = 0.5f,
        public val positionY: Float = 0.85f,
        public val fontSize: Float = 42f,
        public val colorArgb: Long = 0xFFFFFFFFL,
        public val alignment: EditorTextAlignment = EditorTextAlignment.Center,
        public val opacity: Float = 1f,
    ) : EditorClip {
        init {
            require(timelineStartMicros >= 0L) { "Text start must not be negative." }
            require(durationMicros > 0L) { "Text duration must be positive." }
            require(text.isNotBlank()) { "Text overlay must not be blank." }
            require(positionX in 0f..1f && positionY in 0f..1f) { "Text position must be normalized." }
            require(fontSize in 8f..256f) { "Text size must be between 8 and 256." }
            require(opacity in 0f..1f) { "Text opacity must be normalized." }
        }
    }
}

/** One ordered editor track; later video tracks are composited above earlier tracks. */
public data class EditorTrack(
    public val id: EditorTrackId,
    public val name: String,
    public val kind: EditorTrackKind,
    public val clips: List<EditorClip> = emptyList(),
    public val visible: Boolean = true,
    public val locked: Boolean = false,
    public val muted: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Track name must not be blank." }
    }
}

/** A user-selected timestamp that can be included in frame export. */
public data class ImportantFrameMarker(
    public val id: ImportantFrameId,
    public val timelineMicros: Long,
    public val included: Boolean = true,
) {
    init {
        require(timelineMicros >= 0L) { "Important frame timestamp must not be negative." }
    }
}

/** Immutable, autosaved video-editing project. */
public data class EditorProject(
    public val schemaVersion: Int = CURRENT_EDITOR_PROJECT_SCHEMA_VERSION,
    public val name: String,
    public val primaryAssetId: MediaAssetId?,
    public val canvasWidth: Int,
    public val canvasHeight: Int,
    public val frameRate: Int,
    public val assets: List<MediaAsset> = emptyList(),
    public val tracks: List<EditorTrack> = emptyList(),
    public val importantFrames: List<ImportantFrameMarker> = emptyList(),
) {
    init {
        require(schemaVersion > 0) { "Project schema version must be positive." }
        require(name.isNotBlank()) { "Project name must not be blank." }
        require(canvasWidth > 0 && canvasHeight > 0) { "Project canvas must have a positive size." }
        require(frameRate in SUPPORTED_EDITOR_FRAME_RATES) { "Unsupported editor frame rate: $frameRate" }
    }

    /** Duration of all visible timeline content. */
    public val durationMicros: Long
        get() = tracks.asSequence().flatMap { it.clips.asSequence() }
            .maxOfOrNull(EditorClip::timelineEndMicros)
            ?: 0L
}

/** Output frame layout selected by the editor. */
public enum class ImportantFrameLayout {
    SeparatePngFiles,
    ContactSheet,
}

/** Request to render either the full project or selected timeline frames. */
public sealed interface EditorExportRequest {
    public val project: EditorProject
    public val outputPath: String
    public val overwrite: Boolean

    /** Renders the complete timeline into an MP4 file. */
    public data class Video(
        override val project: EditorProject,
        override val outputPath: String,
        override val overwrite: Boolean = false,
        public val width: Int = project.canvasWidth,
        public val height: Int = project.canvasHeight,
        public val frameRate: Int = project.frameRate,
    ) : EditorExportRequest

    /** Renders selected timeline timestamps from the final composition. */
    public data class Frames(
        override val project: EditorProject,
        override val outputPath: String,
        override val overwrite: Boolean = false,
        public val layout: ImportantFrameLayout = ImportantFrameLayout.SeparatePngFiles,
        /** Sorted during export; duplicate and out-of-range positions are ignored. */
        public val timestampsMicros: List<Long> = project.importantFrames
            .filter(ImportantFrameMarker::included)
            .map(ImportantFrameMarker::timelineMicros),
    ) : EditorExportRequest
}

/** Current sidecar project format. */
public const val CURRENT_EDITOR_PROJECT_SCHEMA_VERSION: Int = 1

/** Frame rates exposed by the editor UI. */
public val SUPPORTED_EDITOR_FRAME_RATES: Set<Int> = setOf(24, 30, 60)
