package io.aequicor.desktop

import io.aequicor.editor.ClipEffects
import io.aequicor.editor.ClipTransform
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorClipId
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTrack
import io.aequicor.editor.EditorTrackId
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameMarker
import io.aequicor.editor.MediaAsset
import io.aequicor.editor.MediaAssetId
import io.aequicor.editor.MediaAssetKind
import io.aequicor.editor.Transition
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopEditorProjectStoreTest {
    @Test
    fun savesVersionedSidecarAtomicallyAndRestoresRelativeMediaPath() = runTest {
        val directory = Files.createTempDirectory("mission-editor-project")
        val media = directory.resolve("recording.mp4")
        Files.write(media, byteArrayOf(1))
        val store = JsonDesktopEditorProjectStore(StandardTestDispatcher(testScheduler))
        val project = project(media.toString())

        val sidecar = store.save(media.toString(), project)
        val restored = assertNotNull(store.load(media.toString()))

        assertTrue(editorProjectSidecarPath(media.toString()).exists())
        assertEquals(editorProjectSidecarPath(media.toString()).toString(), sidecar)
        assertEquals(project, restored)
        assertTrue(Files.readString(Path.of(sidecar)).contains("\"schemaVersion\": 1"))
    }

    @Test
    fun keepsProjectAndMarksMissingAssetsUnavailable() = runTest {
        val directory = Files.createTempDirectory("mission-editor-missing")
        val media = directory.resolve("recording.mp4")
        Files.write(media, byteArrayOf(1))
        val store = JsonDesktopEditorProjectStore(StandardTestDispatcher(testScheduler))
        store.save(media.toString(), project(media.toString()))
        Files.delete(media)

        val restored = assertNotNull(store.load(media.toString()))

        assertFalse(restored.assets.single().available)
        assertEquals(1, restored.tracks.single().clips.size)
        assertEquals(1, restored.importantFrames.size)
    }

    @Test
    fun rejectsCorruptedAndUnsupportedSidecars() = runTest {
        val directory = Files.createTempDirectory("mission-editor-corrupt")
        val media = directory.resolve("recording.mp4")
        Files.write(media, byteArrayOf(1))
        val store = JsonDesktopEditorProjectStore(StandardTestDispatcher(testScheduler))
        val sidecar = editorProjectSidecarPath(media.toString())
        Files.writeString(sidecar, "not-json")

        assertFailsWith<DesktopEditorProjectException> { store.load(media.toString()) }

        store.save(media.toString(), project(media.toString()))
        Files.writeString(sidecar, Files.readString(sidecar).replace("\"schemaVersion\": 1", "\"schemaVersion\": 999"))
        assertTrue(Files.readString(sidecar).contains("\"schemaVersion\": 999"))
        assertFailsWith<DesktopEditorProjectException> { store.load(media.toString()) }
    }

    private fun project(path: String): EditorProject {
        val assetId = MediaAssetId("asset:primary")
        return EditorProject(
            name = "Recording edit",
            primaryAssetId = assetId,
            canvasWidth = 1920,
            canvasHeight = 1080,
            frameRate = 30,
            assets = listOf(
                MediaAsset(
                    id = assetId,
                    path = path,
                    displayName = "recording.mp4",
                    kind = MediaAssetKind.Video,
                    durationMicros = 10_000_000,
                    width = 1920,
                    height = 1080,
                    frameRate = 30.0,
                    hasAudio = true,
                ),
            ),
            tracks = listOf(
                EditorTrack(
                    id = EditorTrackId("track:video"),
                    name = "Video 1",
                    kind = EditorTrackKind.Video,
                    clips = listOf(
                        EditorClip.Media(
                            id = EditorClipId("clip:video"),
                            assetId = assetId,
                            timelineStartMicros = 0,
                            durationMicros = 10_000_000,
                            transform = ClipTransform(scale = 0.8f, opacity = 0.9f),
                            effects = ClipEffects(brightness = 0.1f, volume = 0.7f),
                            transition = Transition.Crossfade(500_000),
                        ),
                    ),
                ),
            ),
            importantFrames = listOf(ImportantFrameMarker(ImportantFrameId("frame:1"), 1_000_000)),
        )
    }
}
