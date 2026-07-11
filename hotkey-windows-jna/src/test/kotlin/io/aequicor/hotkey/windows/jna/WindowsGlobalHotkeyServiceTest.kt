package io.aequicor.hotkey.windows.jna

import io.aequicor.hotkey.GlobalHotkeyAction
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WindowsGlobalHotkeyServiceTest {
    @Test
    fun registersDispatchesAndUnregistersBindingsOnMessageThread() = runBlocking {
        val nativeApi = FakeWindowsHotkeyNativeApi()
        val service = WindowsGlobalHotkeyService(defaultDesktopGlobalHotkeys, nativeApi)

        try {
            assertEquals(
                listOf(
                    NativeRegistration(id = 1, modifiers = 0x4006, virtualKey = 0x78),
                    NativeRegistration(id = 2, modifiers = 0x4006, virtualKey = 0x79),
                    NativeRegistration(id = 3, modifiers = 0x4006, virtualKey = 0x7A),
                ),
                nativeApi.registrations,
            )
            val event = async(start = CoroutineStart.UNDISPATCHED) { service.events.first() }

            nativeApi.emitHotkey(id = 2)

            assertEquals(
                GlobalHotkeyEvent.Triggered(GlobalHotkeyAction.TogglePause),
                withTimeout(1.seconds) { event.await() },
            )
        } finally {
            service.close()
        }

        assertEquals(listOf(3, 2, 1), nativeApi.unregisteredIds)
        assertEquals(nativeApi.messageThreadId, nativeApi.registrationThreadIds.toSet().single())
        assertEquals(nativeApi.messageThreadId, nativeApi.unregistrationThreadIds.toSet().single())
    }

    @Test
    fun rollsBackEarlierBindingsWhenRegistrationConflicts() {
        val nativeApi = FakeWindowsHotkeyNativeApi(failedRegistrationId = 2)

        val failure = assertFailsWith<GlobalHotkeyRegistrationException> {
            WindowsGlobalHotkeyService(defaultDesktopGlobalHotkeys, nativeApi)
        }

        assertEquals(defaultDesktopGlobalHotkeys[1], failure.binding)
        assertEquals(1409, failure.nativeErrorCode)
        assertEquals(listOf(1), nativeApi.unregisteredIds)
    }

    @Test
    fun rejectsDuplicateActionsBeforeStartingNativeRegistration() {
        val nativeApi = FakeWindowsHotkeyNativeApi()
        val duplicate = defaultDesktopGlobalHotkeys.first()

        assertFailsWith<IllegalArgumentException> {
            WindowsGlobalHotkeyService(listOf(duplicate, duplicate.copy()), nativeApi)
        }

        assertTrue(nativeApi.registrations.isEmpty())
    }

    @Test
    fun factoryReportsSupportedOperatingSystemWithoutLoadingUser32() {
        assertTrue(WindowsGlobalHotkeyServiceFactory("Windows 11").isSupported)
        assertFalse(WindowsGlobalHotkeyServiceFactory("Linux").isSupported)
        assertFalse(WindowsGlobalHotkeyServiceFactory("Darwin").isSupported)
    }
}

private class FakeWindowsHotkeyNativeApi(
    private val failedRegistrationId: Int? = null,
) : WindowsHotkeyNativeApi {
    private val messages = LinkedBlockingQueue<WindowsHotkeyMessage>()
    val registrations = CopyOnWriteArrayList<NativeRegistration>()
    val unregisteredIds = CopyOnWriteArrayList<Int>()
    val registrationThreadIds = CopyOnWriteArrayList<Long>()
    val unregistrationThreadIds = CopyOnWriteArrayList<Long>()

    @Volatile
    var messageThreadId: Long = 0
        private set

    override fun initializeMessageQueue() {
        messageThreadId = Thread.currentThread().threadId()
    }

    override fun currentThreadId(): Int = 42

    override fun registerHotkey(id: Int, modifiers: Int, virtualKey: Int): Boolean {
        registrationThreadIds += Thread.currentThread().threadId()
        if (id == failedRegistrationId) {
            return false
        }
        registrations += NativeRegistration(id, modifiers, virtualKey)
        return true
    }

    override fun unregisterHotkey(id: Int): Boolean {
        unregistrationThreadIds += Thread.currentThread().threadId()
        unregisteredIds += id
        return true
    }

    override fun readMessage(): WindowsHotkeyMessage = messages.take()

    override fun postQuit(threadId: Int): Boolean {
        messages.put(WindowsHotkeyMessage.Quit)
        return true
    }

    override fun lastErrorCode(): Int = 1409

    fun emitHotkey(id: Int) {
        messages.put(WindowsHotkeyMessage.Hotkey(id))
    }
}

private data class NativeRegistration(
    val id: Int,
    val modifiers: Int,
    val virtualKey: Int,
)
