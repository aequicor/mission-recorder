package io.aequicor.desktop

import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.GlobalHotkeyService
import io.aequicor.hotkey.GlobalHotkeyServiceFactory
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopGlobalHotkeyServiceFactoryTest {
    @Test
    fun delegatesToFirstSupportedPlatformWithoutOpeningOthers() {
        val unsupported = FakeGlobalHotkeyFactory(isSupported = false)
        val supported = FakeGlobalHotkeyFactory(isSupported = true)
        val later = FakeGlobalHotkeyFactory(isSupported = true)
        val factory = DesktopGlobalHotkeyServiceFactory(listOf(unsupported, supported, later))

        val service = factory.create()

        assertTrue(factory.isSupported)
        assertEquals(defaultDesktopGlobalHotkeys, service.bindings)
        assertEquals(0, unsupported.createCalls)
        assertEquals(1, supported.createCalls)
        assertEquals(0, later.createCalls)
    }

    @Test
    fun rejectsUnsupportedDesktopSessionWithoutCreatingService() {
        val platform = FakeGlobalHotkeyFactory(isSupported = false)
        val factory = DesktopGlobalHotkeyServiceFactory(listOf(platform))

        assertFalse(factory.isSupported)
        assertFailsWith<IllegalStateException> { factory.create() }
        assertEquals(0, platform.createCalls)
    }
}

private class FakeGlobalHotkeyFactory(
    override val isSupported: Boolean,
) : GlobalHotkeyServiceFactory {
    var createCalls: Int = 0
        private set

    override fun create(bindings: List<GlobalHotkeyBinding>): GlobalHotkeyService {
        createCalls += 1
        return FakeGlobalHotkeyService(bindings)
    }
}

private class FakeGlobalHotkeyService(
    override val bindings: List<GlobalHotkeyBinding>,
) : GlobalHotkeyService {
    override val events: Flow<GlobalHotkeyEvent> = emptyFlow()

    override fun close() = Unit
}
