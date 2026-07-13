package io.aequicor.editor

/** Project mutation accepted by [EditorReducer]. */
public sealed interface EditorAction {
    public data class AddAsset(public val asset: MediaAsset) : EditorAction
    public data class RelinkAsset(public val assetId: MediaAssetId, public val path: String) : EditorAction
    public data class RemoveAsset(public val assetId: MediaAssetId) : EditorAction
    public data class AddTrack(public val track: EditorTrack) : EditorAction
    public data class RemoveTrack(public val trackId: EditorTrackId) : EditorAction
    public data class SetTrackVisibility(public val trackId: EditorTrackId, public val visible: Boolean) : EditorAction
    public data class SetTrackLocked(public val trackId: EditorTrackId, public val locked: Boolean) : EditorAction
    public data class SetTrackMuted(public val trackId: EditorTrackId, public val muted: Boolean) : EditorAction
    public data class AddClip(public val trackId: EditorTrackId, public val clip: EditorClip) : EditorAction
    public data class DeleteClip(public val clipId: EditorClipId) : EditorAction
    public data class MoveClip(
        public val clipId: EditorClipId,
        public val targetTrackId: EditorTrackId,
        public val timelineStartMicros: Long,
    ) : EditorAction
    public data class TrimMediaClip(
        public val clipId: EditorClipId,
        public val timelineStartMicros: Long,
        public val sourceStartMicros: Long,
        public val durationMicros: Long,
    ) : EditorAction
    public data class SplitClip(
        public val clipId: EditorClipId,
        public val timelineMicros: Long,
        public val rightClipId: EditorClipId,
    ) : EditorAction
    public data class UpdateMediaClip(
        public val clipId: EditorClipId,
        public val speed: Float,
        public val transform: ClipTransform,
        public val effects: ClipEffects,
        public val transition: Transition,
    ) : EditorAction
    public data class UpdateTextClip(public val clip: EditorClip.Text) : EditorAction
    public data class AddImportantFrame(public val marker: ImportantFrameMarker) : EditorAction
    public data class SetImportantFrameIncluded(
        public val markerId: ImportantFrameId,
        public val included: Boolean,
    ) : EditorAction
    public data class RemoveImportantFrame(public val markerId: ImportantFrameId) : EditorAction
}

/** Result of applying one editor mutation. */
public sealed interface EditorReduceResult {
    public data class Applied(public val project: EditorProject) : EditorReduceResult
    public data class Rejected(public val message: String) : EditorReduceResult
}

/** Deterministic validator and reducer for timeline mutations. */
public object EditorReducer {
    /** Applies [action] without mutating [project]. */
    public fun reduce(project: EditorProject, action: EditorAction): EditorReduceResult = runCatching {
        when (action) {
            is EditorAction.AddAsset -> project.addAsset(action.asset)
            is EditorAction.RelinkAsset -> project.updateAsset(action.assetId) { asset ->
                require(action.path.isNotBlank()) { "Media asset path must not be blank." }
                asset.copy(path = action.path, available = true)
            }
            is EditorAction.RemoveAsset -> project.removeAsset(action.assetId)
            is EditorAction.AddTrack -> project.addTrack(action.track)
            is EditorAction.RemoveTrack -> project.removeTrack(action.trackId)
            is EditorAction.SetTrackVisibility -> project.updateTrack(action.trackId) { it.copy(visible = action.visible) }
            is EditorAction.SetTrackLocked -> project.updateTrack(action.trackId) { it.copy(locked = action.locked) }
            is EditorAction.SetTrackMuted -> project.updateTrack(action.trackId) { it.copy(muted = action.muted) }
            is EditorAction.AddClip -> project.updateTrack(action.trackId) { track ->
                require(!track.locked) { "Track is locked." }
                require(project.findClip(action.clip.id) == null) { "Clip id already exists: ${action.clip.id.value}" }
                require(track.accepts(action.clip)) { "Clip is incompatible with ${track.kind} track." }
                track.copy(clips = (track.clips + action.clip).sortedBy(EditorClip::timelineStartMicros))
            }.validated()
            is EditorAction.DeleteClip -> project.updateClipTrack(action.clipId) { track, _ ->
                require(!track.locked) { "Track is locked." }
                track.copy(clips = track.clips.filterNot { it.id == action.clipId })
            }
            is EditorAction.MoveClip -> project.moveClip(action)
            is EditorAction.TrimMediaClip -> project.updateClip(action.clipId) { track, clip ->
                require(!track.locked) { "Track is locked." }
                val media = clip as? EditorClip.Media ?: error("Only media clips can be trimmed.")
                media.copy(
                    timelineStartMicros = action.timelineStartMicros,
                    sourceStartMicros = action.sourceStartMicros,
                    durationMicros = action.durationMicros,
                ).also { updated -> project.requireWithinAsset(updated) }
            }.validated()
            is EditorAction.SplitClip -> project.splitClip(action)
            is EditorAction.UpdateMediaClip -> project.updateClip(action.clipId) { track, clip ->
                require(!track.locked) { "Track is locked." }
                val media = clip as? EditorClip.Media ?: error("Only media clips have media effects.")
                media.copy(
                    speed = action.speed,
                    transform = action.transform,
                    effects = action.effects,
                    transition = action.transition,
                ).also { updated -> project.requireWithinAsset(updated) }
            }.validated()
            is EditorAction.UpdateTextClip -> project.updateClip(action.clip.id) { track, current ->
                require(!track.locked) { "Track is locked." }
                require(current is EditorClip.Text) { "Only text clips can be replaced with text." }
                action.clip
            }.validated()
            is EditorAction.AddImportantFrame -> {
                require(project.importantFrames.none { it.id == action.marker.id }) { "Marker id already exists." }
                require(project.importantFrames.none { it.timelineMicros == action.marker.timelineMicros }) {
                    "An important frame already exists at this timestamp."
                }
                project.copy(
                    importantFrames = (project.importantFrames + action.marker).sortedBy(ImportantFrameMarker::timelineMicros),
                )
            }
            is EditorAction.SetImportantFrameIncluded -> project.updateMarker(action.markerId) {
                it.copy(included = action.included)
            }
            is EditorAction.RemoveImportantFrame -> {
                require(project.importantFrames.any { it.id == action.markerId }) { "Important frame was not found." }
                project.copy(importantFrames = project.importantFrames.filterNot { it.id == action.markerId })
            }
        }
    }.fold(
        onSuccess = EditorReduceResult::Applied,
        onFailure = { failure -> EditorReduceResult.Rejected(failure.message ?: "Editor action was rejected.") },
    )
}

/** Undo/redo state around the pure [EditorReducer]. */
public data class EditorHistory(
    public val project: EditorProject,
    public val undoStack: List<EditorProject> = emptyList(),
    public val redoStack: List<EditorProject> = emptyList(),
) {
    /** Applies a mutation and records the previous project for undo. */
    public fun apply(action: EditorAction): EditorHistory = when (val result = EditorReducer.reduce(project, action)) {
        is EditorReduceResult.Applied -> copy(
            project = result.project,
            undoStack = (undoStack + project).takeLast(MAX_EDITOR_UNDO_STEPS),
            redoStack = emptyList(),
        )
        is EditorReduceResult.Rejected -> this
    }

    /** Restores the previous project when available. */
    public fun undo(): EditorHistory {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(
            project = previous,
            undoStack = undoStack.dropLast(1),
            redoStack = (redoStack + project).takeLast(MAX_EDITOR_UNDO_STEPS),
        )
    }

    /** Restores the most recently undone project when available. */
    public fun redo(): EditorHistory {
        val next = redoStack.lastOrNull() ?: return this
        return copy(
            project = next,
            undoStack = (undoStack + project).takeLast(MAX_EDITOR_UNDO_STEPS),
            redoStack = redoStack.dropLast(1),
        )
    }
}

private fun EditorProject.addAsset(asset: MediaAsset): EditorProject {
    require(assets.none { it.id == asset.id }) { "Media asset id already exists: ${asset.id.value}" }
    return copy(assets = assets + asset, primaryAssetId = primaryAssetId ?: asset.id)
}

private fun EditorProject.removeAsset(assetId: MediaAssetId): EditorProject {
    require(assets.any { it.id == assetId }) { "Media asset was not found." }
    require(tracks.none { track -> track.clips.any { it is EditorClip.Media && it.assetId == assetId } }) {
        "Remove clips that use this asset before removing the asset."
    }
    val remaining = assets.filterNot { it.id == assetId }
    return copy(assets = remaining, primaryAssetId = primaryAssetId.takeUnless { it == assetId } ?: remaining.firstOrNull()?.id)
}

private fun EditorProject.addTrack(track: EditorTrack): EditorProject {
    require(tracks.none { it.id == track.id }) { "Track id already exists: ${track.id.value}" }
    require(track.clips.all(track::accepts)) { "Track contains incompatible clips." }
    return copy(tracks = tracks + track).validated()
}

private fun EditorProject.removeTrack(trackId: EditorTrackId): EditorProject {
    require(tracks.any { it.id == trackId }) { "Track was not found." }
    return copy(tracks = tracks.filterNot { it.id == trackId })
}

private fun EditorProject.updateAsset(id: MediaAssetId, transform: (MediaAsset) -> MediaAsset): EditorProject {
    var found = false
    val updated = assets.map { asset ->
        if (asset.id == id) {
            found = true
            transform(asset)
        } else {
            asset
        }
    }
    require(found) { "Media asset was not found." }
    return copy(assets = updated)
}

private fun EditorProject.updateTrack(id: EditorTrackId, transform: (EditorTrack) -> EditorTrack): EditorProject {
    var found = false
    val updated = tracks.map { track ->
        if (track.id == id) {
            found = true
            transform(track)
        } else {
            track
        }
    }
    require(found) { "Track was not found." }
    return copy(tracks = updated)
}

private fun EditorProject.updateClipTrack(
    clipId: EditorClipId,
    transform: (EditorTrack, EditorClip) -> EditorTrack,
): EditorProject {
    var found = false
    val updated = tracks.map { track ->
        val clip = track.clips.firstOrNull { it.id == clipId }
        if (clip != null) {
            found = true
            transform(track, clip)
        } else {
            track
        }
    }
    require(found) { "Clip was not found." }
    return copy(tracks = updated)
}

private fun EditorProject.updateClip(
    clipId: EditorClipId,
    transform: (EditorTrack, EditorClip) -> EditorClip,
): EditorProject = updateClipTrack(clipId) { track, clip ->
    track.copy(clips = track.clips.map { current ->
        if (current.id == clipId) transform(track, current) else current
    }.sortedBy(EditorClip::timelineStartMicros))
}

private fun EditorProject.moveClip(action: EditorAction.MoveClip): EditorProject {
    require(action.timelineStartMicros >= 0L) { "Clip start must not be negative." }
    val sourceTrack = tracks.firstOrNull { track -> track.clips.any { it.id == action.clipId } }
        ?: error("Clip was not found.")
    val clip = sourceTrack.clips.first { it.id == action.clipId }
    val targetTrack = tracks.firstOrNull { it.id == action.targetTrackId } ?: error("Target track was not found.")
    require(!sourceTrack.locked && !targetTrack.locked) { "Track is locked." }
    require(targetTrack.accepts(clip)) { "Clip is incompatible with target track." }
    val moved = when (clip) {
        is EditorClip.Media -> clip.copy(timelineStartMicros = action.timelineStartMicros)
        is EditorClip.Text -> clip.copy(timelineStartMicros = action.timelineStartMicros)
    }
    return copy(tracks = tracks.map { track ->
        val without = track.clips.filterNot { it.id == clip.id }
        if (track.id == targetTrack.id) {
            track.copy(clips = (without + moved).sortedBy(EditorClip::timelineStartMicros))
        } else {
            track.copy(clips = without)
        }
    }).validated()
}

private fun EditorProject.splitClip(action: EditorAction.SplitClip): EditorProject = updateClipTrack(action.clipId) { track, clip ->
    require(!track.locked) { "Track is locked." }
    require(findClip(action.rightClipId) == null) { "Right clip id already exists." }
    val offset = action.timelineMicros - clip.timelineStartMicros
    require(offset in 1 until clip.durationMicros) { "Split position must be inside the clip." }
    val pair = when (clip) {
        is EditorClip.Media -> {
            val sourceOffset = (offset * clip.speed).toLong()
            clip.copy(durationMicros = offset, transition = Transition.None) to clip.copy(
                id = action.rightClipId,
                timelineStartMicros = action.timelineMicros,
                durationMicros = clip.durationMicros - offset,
                sourceStartMicros = clip.sourceStartMicros + sourceOffset,
            )
        }
        is EditorClip.Text -> clip.copy(durationMicros = offset) to clip.copy(
            id = action.rightClipId,
            timelineStartMicros = action.timelineMicros,
            durationMicros = clip.durationMicros - offset,
        )
    }
    track.copy(clips = (track.clips.filterNot { it.id == clip.id } + listOf(pair.first, pair.second))
        .sortedBy(EditorClip::timelineStartMicros))
}.validated()

private fun EditorProject.updateMarker(
    id: ImportantFrameId,
    transform: (ImportantFrameMarker) -> ImportantFrameMarker,
): EditorProject {
    var found = false
    val updated = importantFrames.map { marker ->
        if (marker.id == id) {
            found = true
            transform(marker)
        } else {
            marker
        }
    }
    require(found) { "Important frame was not found." }
    return copy(importantFrames = updated)
}

private fun EditorProject.findClip(id: EditorClipId): EditorClip? =
    tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == id }

private fun EditorTrack.accepts(clip: EditorClip): Boolean = when (kind) {
    EditorTrackKind.Video -> clip is EditorClip.Media
    EditorTrackKind.Audio -> clip is EditorClip.Media
    EditorTrackKind.Text -> clip is EditorClip.Text
}

private fun EditorProject.requireWithinAsset(clip: EditorClip.Media) {
    val asset = assets.firstOrNull { it.id == clip.assetId } ?: error("Clip media asset was not found.")
    val usedSourceMicros = (clip.durationMicros * clip.speed).toLong()
    require(clip.sourceStartMicros + usedSourceMicros <= asset.durationMicros || asset.kind == MediaAssetKind.Image) {
        "Clip exceeds the source media duration."
    }
}

private fun EditorProject.validated(): EditorProject {
    val clipIds = mutableSetOf<EditorClipId>()
    tracks.forEach { track ->
        track.clips.forEach { clip ->
            require(clipIds.add(clip.id)) { "Clip ids must be unique." }
            require(track.accepts(clip)) { "Clip is incompatible with ${track.kind} track." }
            if (clip is EditorClip.Media) {
                requireWithinAsset(clip)
            }
        }
        val ordered = track.clips.sortedBy(EditorClip::timelineStartMicros)
        ordered.zipWithNext().forEach { (left, right) ->
            val overlap = left.timelineEndMicros - right.timelineStartMicros
            if (overlap > 0L) {
                require(track.kind == EditorTrackKind.Video && left is EditorClip.Media) {
                    "Overlapping clips require separate tracks."
                }
                val transition = left.transition as? Transition.Crossfade
                    ?: error("Overlapping clips on one video track require a crossfade.")
                require(overlap <= transition.durationMicros) { "Clip overlap exceeds crossfade duration." }
            }
        }
    }
    return this
}

private const val MAX_EDITOR_UNDO_STEPS = 100
