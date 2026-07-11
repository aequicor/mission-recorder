package io.aequicor.app

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import io.aequicor.capture.platform.EmptyAudioSourceRepository
import io.aequicor.cli.RecordTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCaptureSelectionResolverTest {
    @Test
    fun resolvesWindowByOpaqueSourceId() = runTest {
        val window = CaptureSource.Window(
            id = CaptureSourceId("window:win32:2a"),
            displayName = "Editor",
        )
        val resolver = resolver(listOf(window))

        assertEquals(window, resolver.resolveSource(RecordTarget.Window(window.id.value)))
    }

    @Test
    fun resolvesWindowsApplicationByPidSelector() = runTest {
        val application = CaptureSource.Application(
            id = CaptureSourceId("application:win32:4242"),
            displayName = "Editor",
        )
        val resolver = resolver(listOf(application))

        assertEquals(application, resolver.resolveSource(RecordTarget.Application("4242")))
    }

    @Test
    fun resolvesRequestedSystemLoopback() = runTest {
        val loopback = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:default"),
            displayName = "System",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val resolver = DesktopCaptureSelectionResolver(
            captureSourceRepository = TestCaptureSourceRepository(emptyList()),
            audioSourceRepository = object : AudioSourceRepository {
                override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> = listOf(loopback)
            },
        )

        val result = resolver.resolveAudioSources(
            microphoneSelector = null,
            includeSystemAudio = true,
        )

        assertEquals(listOf(loopback), (result as DesktopAudioSourceResolution.Resolved).sources)
    }

    @Test
    fun resolvesExplicitSystemLoopbackInsteadOfDefault() = runTest {
        val speakers = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:endpoint:speakers"),
            displayName = "Speakers",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val headset = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:endpoint:headset"),
            displayName = "Headset",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val resolver = DesktopCaptureSelectionResolver(
            captureSourceRepository = TestCaptureSourceRepository(emptyList()),
            audioSourceRepository = object : AudioSourceRepository {
                override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> =
                    listOf(speakers, headset)
            },
        )

        val result = resolver.resolveAudioSources(
            microphoneSelector = null,
            includeSystemAudio = true,
            systemAudioSelector = headset.id.value,
        )

        assertEquals(listOf(headset), (result as DesktopAudioSourceResolution.Resolved).sources)
    }

    @Test
    fun preservesProfileGainWhenResolvingPhysicalAudioSource() = runTest {
        val available = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:endpoint:speakers"),
            displayName = "Speakers",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val configured = available.copy(gain = 0.35)
        val resolver = DesktopCaptureSelectionResolver(
            captureSourceRepository = TestCaptureSourceRepository(emptyList()),
            audioSourceRepository = object : AudioSourceRepository {
                override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> = listOf(available)
            },
        )

        val result = resolver.resolveAudioSources(
            microphoneSelector = null,
            includeSystemAudio = false,
            profileSources = listOf(configured),
        )

        assertEquals(listOf(configured), (result as DesktopAudioSourceResolution.Resolved).sources)
    }

    @Test
    fun rejectsGainOverrideWhenProfileHasNoMicrophone() = runTest {
        val resolver = DesktopCaptureSelectionResolver(
            captureSourceRepository = TestCaptureSourceRepository(emptyList()),
            audioSourceRepository = EmptyAudioSourceRepository,
        )

        val result = resolver.resolveAudioSources(
            microphoneSelector = null,
            includeSystemAudio = false,
            microphoneGain = 0.5,
            profileSources = emptyList(),
        )

        assertEquals(
            DesktopAudioSourceResolution.Rejected(
                "Microphone gain was provided, but the selected profile has no microphone. " +
                    "Pass --mic or enable a microphone in the profile.",
            ),
            result,
        )
    }

    private fun resolver(sources: List<CaptureSource>) = DesktopCaptureSelectionResolver(
        captureSourceRepository = TestCaptureSourceRepository(sources),
        audioSourceRepository = EmptyAudioSourceRepository,
    )
}

private class TestCaptureSourceRepository(
    private val sources: List<CaptureSource>,
) : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> = sources
}
