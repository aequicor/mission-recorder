package io.aequicor.desktop

import io.aequicor.editor.ClipEffects
import io.aequicor.editor.ClipTransform
import io.aequicor.editor.CURRENT_EDITOR_PROJECT_SCHEMA_VERSION
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorClipId
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTextAlignment
import io.aequicor.editor.EditorTrack
import io.aequicor.editor.EditorTrackId
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameMarker
import io.aequicor.editor.MediaAsset
import io.aequicor.editor.MediaAssetId
import io.aequicor.editor.MediaAssetKind
import io.aequicor.editor.NormalizedCrop
import io.aequicor.editor.Transition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

internal class DesktopEditorProjectException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal interface DesktopEditorProjectStore {
    suspend fun load(primaryMediaPath: String): EditorProject?
    suspend fun save(primaryMediaPath: String, project: EditorProject): String
}

internal class JsonDesktopEditorProjectStore(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = editorProjectJson,
) : DesktopEditorProjectStore {
    override suspend fun load(primaryMediaPath: String): EditorProject? = withContext(dispatcher) {
        val sidecar = editorProjectSidecarPath(primaryMediaPath)
        if (!sidecar.exists()) return@withContext null
        try {
            val document = json.decodeFromString<EditorProjectDocument>(Files.readString(sidecar))
            require(document.schemaVersion == CURRENT_EDITOR_PROJECT_SCHEMA_VERSION) {
                "Unsupported editor project schema ${document.schemaVersion}."
            }
            document.toDomain(sidecar.parent)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: DesktopEditorProjectException) {
            throw failure
        } catch (failure: Exception) {
            throw DesktopEditorProjectException("Unable to load editor project: $sidecar", failure)
        }
    }

    override suspend fun save(primaryMediaPath: String, project: EditorProject): String = withContext(dispatcher) {
        val sidecar = editorProjectSidecarPath(primaryMediaPath)
        sidecar.parent?.let(Files::createDirectories)
        val temporary = sidecar.resolveSibling(".${sidecar.fileName}.tmp-${System.nanoTime()}")
        try {
            Files.writeString(temporary, json.encodeToString(project.toDocument(sidecar.parent)))
            try {
                Files.move(
                    temporary,
                    sidecar,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, sidecar, StandardCopyOption.REPLACE_EXISTING)
            }
            sidecar.toString()
        } catch (failure: CancellationException) {
            Files.deleteIfExists(temporary)
            throw failure
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary)
            throw DesktopEditorProjectException("Unable to save editor project: $sidecar", failure)
        }
    }
}

internal fun editorProjectSidecarPath(primaryMediaPath: String): Path {
    val primary = runCatching { Path.of(primaryMediaPath).toAbsolutePath().normalize() }
        .getOrElse { throw DesktopEditorProjectException("Invalid primary media path: $primaryMediaPath", it) }
    return primary.resolveSibling("${primary.fileName}$EDITOR_PROJECT_SUFFIX")
}

@Serializable
private data class EditorProjectDocument(
    val schemaVersion: Int,
    val name: String,
    val primaryAssetId: String?,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val frameRate: Int,
    val assets: List<MediaAssetDocument>,
    val tracks: List<EditorTrackDocument>,
    val importantFrames: List<ImportantFrameDocument>,
)

@Serializable
private data class MediaAssetDocument(
    val id: String,
    val path: String,
    val relativePath: Boolean,
    val displayName: String,
    val kind: MediaAssetKind,
    val durationMicros: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val hasAudio: Boolean,
)

@Serializable
private data class EditorTrackDocument(
    val id: String,
    val name: String,
    val kind: EditorTrackKind,
    val clips: List<EditorClipDocument>,
    val visible: Boolean,
    val locked: Boolean,
    val muted: Boolean,
)

@Serializable
private sealed interface EditorClipDocument {
    val id: String
    val timelineStartMicros: Long
    val durationMicros: Long

    @Serializable
    @SerialName("media")
    data class Media(
        override val id: String,
        val assetId: String,
        override val timelineStartMicros: Long,
        override val durationMicros: Long,
        val sourceStartMicros: Long,
        val speed: Float,
        val transform: ClipTransformDocument,
        val effects: ClipEffectsDocument,
        val crossfadeMicros: Long?,
    ) : EditorClipDocument

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        override val timelineStartMicros: Long,
        override val durationMicros: Long,
        val text: String,
        val positionX: Float,
        val positionY: Float,
        val fontSize: Float,
        val colorArgb: Long,
        val alignment: EditorTextAlignment,
        val opacity: Float,
    ) : EditorClipDocument
}

@Serializable
private data class ClipTransformDocument(
    val cropLeft: Float,
    val cropTop: Float,
    val cropRight: Float,
    val cropBottom: Float,
    val positionX: Float,
    val positionY: Float,
    val scale: Float,
    val opacity: Float,
)

@Serializable
private data class ClipEffectsDocument(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val fadeInMicros: Long,
    val fadeOutMicros: Long,
    val volume: Float,
    val muted: Boolean,
)

@Serializable
private data class ImportantFrameDocument(
    val id: String,
    val timelineMicros: Long,
    val included: Boolean,
)

private fun EditorProject.toDocument(baseDirectory: Path?): EditorProjectDocument = EditorProjectDocument(
    schemaVersion = schemaVersion,
    name = name,
    primaryAssetId = primaryAssetId?.value,
    canvasWidth = canvasWidth,
    canvasHeight = canvasHeight,
    frameRate = frameRate,
    assets = assets.map { it.toDocument(baseDirectory) },
    tracks = tracks.map(EditorTrack::toDocument),
    importantFrames = importantFrames.map { marker ->
        ImportantFrameDocument(marker.id.value, marker.timelineMicros, marker.included)
    },
)

private fun MediaAsset.toDocument(baseDirectory: Path?): MediaAssetDocument {
    val absolute = Path.of(path).toAbsolutePath().normalize()
    val relative = baseDirectory?.relativePathToOrNull(absolute)
    return MediaAssetDocument(
        id = id.value,
        path = (relative ?: absolute).toString().replace('\\', '/'),
        relativePath = relative != null,
        displayName = displayName,
        kind = kind,
        durationMicros = durationMicros,
        width = width,
        height = height,
        frameRate = frameRate,
        hasAudio = hasAudio,
    )
}

private fun EditorTrack.toDocument(): EditorTrackDocument = EditorTrackDocument(
    id = id.value,
    name = name,
    kind = kind,
    clips = clips.map(EditorClip::toDocument),
    visible = visible,
    locked = locked,
    muted = muted,
)

private fun EditorClip.toDocument(): EditorClipDocument = when (this) {
    is EditorClip.Media -> EditorClipDocument.Media(
        id = id.value,
        assetId = assetId.value,
        timelineStartMicros = timelineStartMicros,
        durationMicros = durationMicros,
        sourceStartMicros = sourceStartMicros,
        speed = speed,
        transform = ClipTransformDocument(
            cropLeft = transform.crop.left,
            cropTop = transform.crop.top,
            cropRight = transform.crop.right,
            cropBottom = transform.crop.bottom,
            positionX = transform.positionX,
            positionY = transform.positionY,
            scale = transform.scale,
            opacity = transform.opacity,
        ),
        effects = ClipEffectsDocument(
            brightness = effects.brightness,
            contrast = effects.contrast,
            saturation = effects.saturation,
            fadeInMicros = effects.fadeInMicros,
            fadeOutMicros = effects.fadeOutMicros,
            volume = effects.volume,
            muted = effects.muted,
        ),
        crossfadeMicros = (transition as? Transition.Crossfade)?.durationMicros,
    )
    is EditorClip.Text -> EditorClipDocument.Text(
        id = id.value,
        timelineStartMicros = timelineStartMicros,
        durationMicros = durationMicros,
        text = text,
        positionX = positionX,
        positionY = positionY,
        fontSize = fontSize,
        colorArgb = colorArgb,
        alignment = alignment,
        opacity = opacity,
    )
}

private fun EditorProjectDocument.toDomain(baseDirectory: Path?): EditorProject = EditorProject(
    schemaVersion = schemaVersion,
    name = name,
    primaryAssetId = primaryAssetId?.let(::MediaAssetId),
    canvasWidth = canvasWidth,
    canvasHeight = canvasHeight,
    frameRate = frameRate,
    assets = assets.map { it.toDomain(baseDirectory) },
    tracks = tracks.map(EditorTrackDocument::toDomain),
    importantFrames = importantFrames.map { marker ->
        ImportantFrameMarker(ImportantFrameId(marker.id), marker.timelineMicros, marker.included)
    },
)

private fun MediaAssetDocument.toDomain(baseDirectory: Path?): MediaAsset {
    val stored = Path.of(path)
    val resolved = if (relativePath && baseDirectory != null) baseDirectory.resolve(stored) else stored
    val absolute = resolved.toAbsolutePath().normalize()
    return MediaAsset(
        id = MediaAssetId(id),
        path = absolute.toString(),
        displayName = displayName,
        kind = kind,
        durationMicros = durationMicros,
        width = width,
        height = height,
        frameRate = frameRate,
        hasAudio = hasAudio,
        available = absolute.exists(),
    )
}

private fun EditorTrackDocument.toDomain(): EditorTrack = EditorTrack(
    id = EditorTrackId(id),
    name = name,
    kind = kind,
    clips = clips.map(EditorClipDocument::toDomain),
    visible = visible,
    locked = locked,
    muted = muted,
)

private fun EditorClipDocument.toDomain(): EditorClip = when (this) {
    is EditorClipDocument.Media -> EditorClip.Media(
        id = EditorClipId(id),
        assetId = MediaAssetId(assetId),
        timelineStartMicros = timelineStartMicros,
        durationMicros = durationMicros,
        sourceStartMicros = sourceStartMicros,
        speed = speed,
        transform = ClipTransform(
            crop = NormalizedCrop(
                left = transform.cropLeft,
                top = transform.cropTop,
                right = transform.cropRight,
                bottom = transform.cropBottom,
            ),
            positionX = transform.positionX,
            positionY = transform.positionY,
            scale = transform.scale,
            opacity = transform.opacity,
        ),
        effects = ClipEffects(
            brightness = effects.brightness,
            contrast = effects.contrast,
            saturation = effects.saturation,
            fadeInMicros = effects.fadeInMicros,
            fadeOutMicros = effects.fadeOutMicros,
            volume = effects.volume,
            muted = effects.muted,
        ),
        transition = crossfadeMicros?.let(Transition::Crossfade) ?: Transition.None,
    )
    is EditorClipDocument.Text -> EditorClip.Text(
        id = EditorClipId(id),
        timelineStartMicros = timelineStartMicros,
        durationMicros = durationMicros,
        text = text,
        positionX = positionX,
        positionY = positionY,
        fontSize = fontSize,
        colorArgb = colorArgb,
        alignment = alignment,
        opacity = opacity,
    )
}

private fun Path.relativePathToOrNull(target: Path): Path? = runCatching {
    val relative = toAbsolutePath().normalize().relativize(target)
    relative.takeUnless { it.startsWith("..") }
}.getOrNull()

private val editorProjectJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

private const val EDITOR_PROJECT_SUFFIX = ".mission-recorder-edit.json"
