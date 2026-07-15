package io.aequicor.capture.platform

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PermissionsTest {
    @Test
    fun derivesOnlyPermissionsUsedByTheRecording() {
        val settings = settings(
            audioSources = listOf(
                AudioSource.Microphone(AudioSourceId("mic"), "Mic", 48_000, 2),
                AudioSource.SystemLoopback(AudioSourceId("system"), "System", 48_000, 2),
            ),
        )

        assertEquals(
            setOf(
                CapturePermission.ScreenRecording,
                CapturePermission.Microphone,
                CapturePermission.SystemAudio,
            ),
            settings.requiredPermissions(),
        )
        assertEquals(
            setOf(CapturePermission.ScreenRecording),
            settings().requiredPermissions(),
        )
    }

    @Test
    fun requestsOnlyPermissionsThatAreNotAlreadyGranted() = runTest {
        val gateway = RecordingGateway(
            checked = mapOf(
                CapturePermission.ScreenRecording to PermissionStatus.Granted,
                CapturePermission.Microphone to PermissionStatus.RequiresUserAction(
                    instructions = "Allow microphone.",
                    action = PermissionUserAction.Request,
                ),
            ),
            requested = mapOf(CapturePermission.Microphone to PermissionStatus.Granted),
        )

        val result = gateway.authorize(
            settings(listOf(AudioSource.Microphone(AudioSourceId("mic"), "Mic", 48_000, 2))),
        )

        assertIs<PermissionAuthorization.Granted>(result)
        assertEquals(setOf(CapturePermission.Microphone), gateway.lastRequested)
    }

    @Test
    fun requestsAtMostOnePermissionPerAuthorizationAction() = runTest {
        val requestRequired = PermissionStatus.RequiresUserAction(
            instructions = "Continue.",
            action = PermissionUserAction.Request,
        )
        val gateway = RecordingGateway(
            checked = mapOf(
                CapturePermission.ScreenRecording to requestRequired,
                CapturePermission.Microphone to requestRequired,
            ),
            requested = mapOf(CapturePermission.ScreenRecording to PermissionStatus.Granted),
        )

        val result = gateway.authorize(
            settings(listOf(AudioSource.Microphone(AudioSourceId("mic"), "Mic", 48_000, 2))),
        )

        assertIs<PermissionAuthorization.Rejected>(result)
        assertEquals(setOf(CapturePermission.ScreenRecording), gateway.lastRequested)
    }

    @Test
    fun rejectsIncompleteGatewayReports() = runTest {
        val gateway = RecordingGateway(emptyMap(), emptyMap())

        val result = gateway.authorize(settings())

        val rejected = assertIs<PermissionAuthorization.Rejected>(result)
        assertTrue(rejected.message().contains("screen recording"))
        assertEquals(emptySet(), gateway.lastRequested)
    }
}

private class RecordingGateway(
    private val checked: Map<CapturePermission, PermissionStatus>,
    private val requested: Map<CapturePermission, PermissionStatus>,
) : PermissionGateway {
    var lastRequested: Set<CapturePermission> = emptySet()
        private set

    override suspend fun check(required: Set<CapturePermission>): PermissionReport = PermissionReport(checked)

    override suspend fun request(required: Set<CapturePermission>): PermissionReport {
        lastRequested = required
        return PermissionReport(requested)
    }
}

private fun settings(audioSources: List<AudioSource> = emptyList()) = RecordingSettings(
    captureSource = CaptureSource.Screen(CaptureSourceId("screen"), "Screen"),
    audioSources = audioSources,
    outputPath = "capture.mp4",
)
