package io.aequicor.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class EditorReducerTest {
    @Test
    fun trimsMovesAndSplitsMediaWithoutChangingItsSourceMapping() {
        val base = project()
        val trimmed = base.apply(
            EditorAction.TrimMediaClip(
                clipId = clipId,
                timelineStartMicros = 1_000_000,
                sourceStartMicros = 2_000_000,
                durationMicros = 6_000_000,
            ),
        )
        val split = trimmed.apply(
            EditorAction.SplitClip(
                clipId = clipId,
                timelineMicros = 4_000_000,
                rightClipId = EditorClipId("clip:right"),
            ),
        )
        val clips = split.tracks.single().clips.map { assertIs<EditorClip.Media>(it) }

        assertEquals(listOf(3_000_000L, 3_000_000L), clips.map(EditorClip::durationMicros))
        assertEquals(listOf(2_000_000L, 5_000_000L), clips.map(EditorClip.Media::sourceStartMicros))
        assertEquals(7_000_000L, split.durationMicros)
    }

    @Test
    fun rejectsOverlapWithoutCrossfadeAndAcceptsConfiguredCrossfade() {
        val second = mediaClip(
            id = EditorClipId("clip:second"),
            timelineStartMicros = 9_000_000,
            durationMicros = 1_000_000,
        )
        val rejected = EditorReducer.reduce(project(), EditorAction.AddClip(videoTrackId, second))
        assertIs<EditorReduceResult.Rejected>(rejected)

        val withTransition = project().apply(
            EditorAction.UpdateMediaClip(
                clipId = clipId,
                speed = 1f,
                transform = ClipTransform(),
                effects = ClipEffects(),
                transition = Transition.Crossfade(1_000_000),
            ),
        )
        val accepted = EditorReducer.reduce(withTransition, EditorAction.AddClip(videoTrackId, second))
        assertIs<EditorReduceResult.Applied>(accepted)
    }

    @Test
    fun importantFramesAreUniqueSortedAndRemainValidProjectDataPastDuration() {
        val first = ImportantFrameMarker(ImportantFrameId("frame:1"), 5_000_000)
        val second = ImportantFrameMarker(ImportantFrameId("frame:2"), 12_000_000)
        val marked = project()
            .apply(EditorAction.AddImportantFrame(second))
            .apply(EditorAction.AddImportantFrame(first))

        assertEquals(listOf(5_000_000L, 12_000_000L), marked.importantFrames.map { it.timelineMicros })
        assertEquals(10_000_000L, marked.durationMicros)
        assertIs<EditorReduceResult.Rejected>(
            EditorReducer.reduce(
                marked,
                EditorAction.AddImportantFrame(ImportantFrameMarker(ImportantFrameId("frame:3"), 5_000_000)),
            ),
        )
    }

    @Test
    fun undoAndRedoRestoreWholeProject() {
        val original = project()
        val marker = ImportantFrameMarker(ImportantFrameId("frame:1"), 1_000_000)
        val changed = EditorHistory(original).apply(EditorAction.AddImportantFrame(marker))

        assertEquals(listOf(marker), changed.project.importantFrames)
        assertSame(original, changed.undo().project)
        assertEquals(listOf(marker), changed.undo().redo().project.importantFrames)
    }

    @Test
    fun lockedTrackRejectsClipMutation() {
        val locked = project().apply(EditorAction.SetTrackLocked(videoTrackId, true))
        val result = EditorReducer.reduce(locked, EditorAction.DeleteClip(clipId))

        assertIs<EditorReduceResult.Rejected>(result)
    }

    private fun EditorProject.apply(action: EditorAction): EditorProject =
        assertIs<EditorReduceResult.Applied>(EditorReducer.reduce(this, action)).project

    private fun project(): EditorProject = EditorProject(
        name = "Editor test",
        primaryAssetId = assetId,
        canvasWidth = 1920,
        canvasHeight = 1080,
        frameRate = 30,
        assets = listOf(
            MediaAsset(
                id = assetId,
                path = "recording.mp4",
                displayName = "Recording",
                kind = MediaAssetKind.Video,
                durationMicros = 20_000_000,
                width = 1920,
                height = 1080,
                frameRate = 30.0,
                hasAudio = true,
            ),
        ),
        tracks = listOf(
            EditorTrack(
                id = videoTrackId,
                name = "Video 1",
                kind = EditorTrackKind.Video,
                clips = listOf(mediaClip()),
            ),
        ),
    )

    private fun mediaClip(
        id: EditorClipId = clipId,
        timelineStartMicros: Long = 0L,
        durationMicros: Long = 10_000_000,
    ): EditorClip.Media = EditorClip.Media(
        id = id,
        assetId = assetId,
        timelineStartMicros = timelineStartMicros,
        durationMicros = durationMicros,
    )

    private companion object {
        val assetId = MediaAssetId("asset:video")
        val videoTrackId = EditorTrackId("track:video")
        val clipId = EditorClipId("clip:video")
    }
}
