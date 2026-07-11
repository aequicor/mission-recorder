package io.aequicor.hotkey.windows.jna

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MSG

internal interface WindowsHotkeyNativeApi {
    fun initializeMessageQueue()
    fun currentThreadId(): Int
    fun registerHotkey(id: Int, modifiers: Int, virtualKey: Int): Boolean
    fun unregisterHotkey(id: Int): Boolean
    fun readMessage(): WindowsHotkeyMessage
    fun postQuit(threadId: Int): Boolean
    fun lastErrorCode(): Int
}

internal sealed interface WindowsHotkeyMessage {
    data class Hotkey(val id: Int) : WindowsHotkeyMessage
    data class Failed(val nativeErrorCode: Int) : WindowsHotkeyMessage
    data object Other : WindowsHotkeyMessage
    data object Quit : WindowsHotkeyMessage
}

internal class JnaWindowsHotkeyNativeApi(
    private val user32: User32 = User32.INSTANCE,
    private val kernel32: Kernel32 = Kernel32.INSTANCE,
) : WindowsHotkeyNativeApi {
    override fun initializeMessageQueue() {
        user32.PeekMessage(MSG(), null, 0, 0, PM_NOREMOVE)
    }

    override fun currentThreadId(): Int = kernel32.GetCurrentThreadId()

    override fun registerHotkey(id: Int, modifiers: Int, virtualKey: Int): Boolean =
        user32.RegisterHotKey(null, id, modifiers, virtualKey)

    override fun unregisterHotkey(id: Int): Boolean = user32.UnregisterHotKey(null, id)

    override fun readMessage(): WindowsHotkeyMessage {
        val message = MSG()
        return when (val result = user32.GetMessage(message, null, 0, 0)) {
            -1 -> WindowsHotkeyMessage.Failed(lastErrorCode())
            0 -> WindowsHotkeyMessage.Quit
            else -> if (message.message == WM_HOTKEY) {
                WindowsHotkeyMessage.Hotkey(message.wParam.toInt())
            } else {
                WindowsHotkeyMessage.Other
            }
        }
    }

    override fun postQuit(threadId: Int): Boolean =
        user32.PostThreadMessage(threadId, WM_QUIT, WPARAM(0), LPARAM(0)) != 0

    override fun lastErrorCode(): Int = kernel32.GetLastError()
}

private const val PM_NOREMOVE = 0x0000
private const val WM_HOTKEY = 0x0312
private const val WM_QUIT = 0x0012
