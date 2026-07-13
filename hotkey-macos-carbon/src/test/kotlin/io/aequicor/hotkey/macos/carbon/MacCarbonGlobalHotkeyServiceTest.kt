package io.aequicor.hotkey.macos.carbon

import io.aequicor.hotkey.GlobalHotkeyAction
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.GlobalHotkeyKey
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MacCarbonGlobalHotkeyServiceTest {
    @Test
    fun mapsCapturedKeyboardKeysToMacKeycodes() {
        assertEquals(40, GlobalHotkeyKey.K.toMacKeycode())
        assertEquals(20, GlobalHotkeyKey.Digit3.toMacKeycode())
        assertEquals(69, GlobalHotkeyKey.NumpadAdd.toMacKeycode())
    }

    @Test
    fun registersDispatchesOncePerPressAndReleasesNativeResources() = runBlocking {
        val nativeApi = FakeMacCarbonHotkeyNativeApi()
        val service = MacCarbonGlobalHotkeyService(defaultDesktopGlobalHotkeys, nativeApi)

        try {
            assertEquals(
                defaultDesktopGlobalHotkeys.mapIndexed { index, binding ->
                    MacRegistration(
                        id = index + 1,
                        keycode = binding.gesture.key.toMacKeycode(),
                        modifiers = 4_608,
                    )
                },
                nativeApi.registrations,
            )
            val events = async(start = CoroutineStart.UNDISPATCHED) { service.events.take(2).toList() }

            nativeApi.emit(MacCarbonHotkeyMessage.Pressed(2))
            nativeApi.emit(MacCarbonHotkeyMessage.Pressed(2))
            nativeApi.emit(MacCarbonHotkeyMessage.Released(2))
            nativeApi.emit(MacCarbonHotkeyMessage.Pressed(2))

            assertEquals(
                listOf(
                    GlobalHotkeyEvent.Triggered(GlobalHotkeyAction.TogglePause),
                    GlobalHotkeyEvent.Triggered(GlobalHotkeyAction.TogglePause),
                ),
                withTimeout(1.seconds) { events.await() },
            )
        } finally {
            service.close()
        }

        assertEquals(defaultDesktopGlobalHotkeys.indices.map { index -> index + 1 }.reversed(), nativeApi.unregisteredIds)
        assertEquals(1, nativeApi.removeHandlerCalls)
    }

    @Test
    fun rollsBackHotkeysAndHandlerWhenRegistrationConflicts() {
        val nativeApi = FakeMacCarbonHotkeyNativeApi(failedRegistrationId = 2)

        val failure = assertFailsWith<MacCarbonHotkeyRegistrationException> {
            MacCarbonGlobalHotkeyService(defaultDesktopGlobalHotkeys, nativeApi)
        }

        assertEquals(defaultDesktopGlobalHotkeys[1], failure.binding)
        assertEquals(-9_878, failure.nativeStatus)
        assertEquals(listOf(1), nativeApi.unregisteredIds)
        assertEquals(1, nativeApi.removeHandlerCalls)
    }

    @Test
    fun factoryEnablesOnlyMacOperatingSystemsWithoutLoadingCarbon() {
        assertTrue(MacCarbonGlobalHotkeyServiceFactory("Mac OS X").isSupported)
        assertTrue(MacCarbonGlobalHotkeyServiceFactory("Darwin").isSupported)
        assertFalse(MacCarbonGlobalHotkeyServiceFactory("Windows 11").isSupported)
        assertFalse(MacCarbonGlobalHotkeyServiceFactory("Linux").isSupported)
    }
}

private class FakeMacCarbonHotkeyNativeApi(
    private val failedRegistrationId: Int? = null,
    private val handlerStatus: Int = 0,
) : MacCarbonHotkeyNativeApi {
    private var callback: ((MacCarbonHotkeyMessage) -> Unit)? = null
    val registrations = mutableListOf<MacRegistration>()
    val unregisteredIds = mutableListOf<Int>()
    var removeHandlerCalls: Int = 0
        private set

    override fun installHandler(callback: (MacCarbonHotkeyMessage) -> Unit): Int {
        if (handlerStatus == 0) this.callback = callback
        return handlerStatus
    }

    override fun registerHotkey(id: Int, keycode: Int, modifiers: Int): Int {
        if (id == failedRegistrationId) return -9_878
        registrations += MacRegistration(id, keycode, modifiers)
        return 0
    }

    override fun unregisterHotkey(id: Int): Int {
        unregisteredIds += id
        return 0
    }

    override fun removeHandler(): Int {
        removeHandlerCalls += 1
        callback = null
        return 0
    }

    fun emit(message: MacCarbonHotkeyMessage) {
        requireNotNull(callback).invoke(message)
    }
}

private data class MacRegistration(
    val id: Int,
    val keycode: Int,
    val modifiers: Int,
)
