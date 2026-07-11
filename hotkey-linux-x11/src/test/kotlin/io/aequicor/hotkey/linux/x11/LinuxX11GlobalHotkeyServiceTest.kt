package io.aequicor.hotkey.linux.x11

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LinuxX11GlobalHotkeyServiceTest {
    @Test
    fun registersLockVariantsSuppressesRepeatAndUnregistersOnOwningThread() = runBlocking {
        val nativeApi = FakeLinuxX11HotkeyNativeApi()
        val service = LinuxX11GlobalHotkeyService(defaultDesktopGlobalHotkeys, nativeApi)

        try {
            assertEquals(12, nativeApi.grabs.size)
            assertEquals(
                listOf(5, 7, 21, 23),
                nativeApi.grabs.take(4).map(NativeGrab::modifiers),
            )
            val events = async(start = CoroutineStart.UNDISPATCHED) { service.events.take(2).toList() }

            nativeApi.emit(LinuxX11HotkeyMessage.KeyPressed(keycode = 76, modifiers = 23, timestamp = 100))
            nativeApi.emit(LinuxX11HotkeyMessage.KeyReleased(keycode = 76, modifiers = 23, timestamp = 200))
            nativeApi.emit(LinuxX11HotkeyMessage.KeyPressed(keycode = 76, modifiers = 23, timestamp = 200))
            nativeApi.emit(LinuxX11HotkeyMessage.KeyReleased(keycode = 76, modifiers = 23, timestamp = 300))
            nativeApi.emit(LinuxX11HotkeyMessage.KeyPressed(keycode = 76, modifiers = 23, timestamp = 400))

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

        assertEquals(nativeApi.grabs.asReversed(), nativeApi.ungrabs)
        assertEquals(nativeApi.eventThreadId, nativeApi.grabThreadIds.toSet().single())
        assertEquals(nativeApi.eventThreadId, nativeApi.ungrabThreadIds.toSet().single())
        assertTrue(nativeApi.closed)
    }

    @Test
    fun rollsBackRegisteredVariantsWhenGrabConflicts() {
        val failedGrab = NativeGrab(keycode = 76, modifiers = 5)
        val nativeApi = FakeLinuxX11HotkeyNativeApi(failedGrab = failedGrab)

        val failure = assertFailsWith<LinuxX11HotkeyRegistrationException> {
            LinuxX11GlobalHotkeyService(defaultDesktopGlobalHotkeys, nativeApi)
        }

        assertEquals(defaultDesktopGlobalHotkeys[1], failure.binding)
        assertEquals(10, failure.nativeErrorCode)
        assertEquals(nativeApi.grabs.take(4).asReversed(), nativeApi.ungrabs)
        assertTrue(nativeApi.closed)
    }

    @Test
    fun factoryEnablesOnlyConfirmedLinuxX11Sessions() {
        assertTrue(
            LinuxX11GlobalHotkeyServiceFactory(
                osName = "Linux",
                sessionType = "x11",
                waylandDisplay = "",
                x11Display = ":0",
            ).isSupported,
        )
        assertFalse(
            LinuxX11GlobalHotkeyServiceFactory(
                osName = "Linux",
                sessionType = "wayland",
                waylandDisplay = "wayland-0",
                x11Display = ":0",
            ).isSupported,
        )
        assertFalse(
            LinuxX11GlobalHotkeyServiceFactory(
                osName = "Windows 11",
                sessionType = "",
                waylandDisplay = "",
                x11Display = "",
            ).isSupported,
        )
    }
}

private class FakeLinuxX11HotkeyNativeApi(
    private val failedGrab: NativeGrab? = null,
) : LinuxX11HotkeyNativeApi {
    private val messages = LinkedBlockingQueue<LinuxX11HotkeyMessage>()
    val grabs = CopyOnWriteArrayList<NativeGrab>()
    val ungrabs = CopyOnWriteArrayList<NativeGrab>()
    val grabThreadIds = CopyOnWriteArrayList<Long>()
    val ungrabThreadIds = CopyOnWriteArrayList<Long>()

    @Volatile
    var eventThreadId: Long = 0
        private set

    @Volatile
    var closed: Boolean = false
        private set

    override fun open() {
        eventThreadId = Thread.currentThread().threadId()
    }

    override fun keycode(key: GlobalHotkeyKey): Int = when (key) {
        GlobalHotkeyKey.F9 -> 75
        GlobalHotkeyKey.F10 -> 76
        GlobalHotkeyKey.F11 -> 95
    }

    override fun grabKey(keycode: Int, modifiers: Int): Int? {
        grabThreadIds += Thread.currentThread().threadId()
        val grab = NativeGrab(keycode, modifiers)
        if (grab == failedGrab) return 10
        grabs += grab
        return null
    }

    override fun ungrabKey(keycode: Int, modifiers: Int) {
        ungrabThreadIds += Thread.currentThread().threadId()
        ungrabs += NativeGrab(keycode, modifiers)
    }

    override fun readEvent(): LinuxX11HotkeyMessage = messages.take()

    override fun wake(): Boolean {
        messages.put(LinuxX11HotkeyMessage.Wake)
        return true
    }

    override fun close() {
        closed = true
    }

    fun emit(message: LinuxX11HotkeyMessage) {
        messages.put(message)
    }
}

private data class NativeGrab(val keycode: Int, val modifiers: Int)
