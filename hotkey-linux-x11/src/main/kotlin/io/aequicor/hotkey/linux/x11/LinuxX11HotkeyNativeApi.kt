package io.aequicor.hotkey.linux.x11

import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import io.aequicor.hotkey.GlobalHotkeyKey

internal interface LinuxX11HotkeyNativeApi {
    fun open()
    fun keycode(key: GlobalHotkeyKey): Int
    fun grabKey(keycode: Int, modifiers: Int): Int?
    fun ungrabKey(keycode: Int, modifiers: Int)
    fun readEvent(): LinuxX11HotkeyMessage
    fun wake(): Boolean
    fun close()
}

internal sealed interface LinuxX11HotkeyMessage {
    data class KeyPressed(val keycode: Int, val modifiers: Int, val timestamp: Long) : LinuxX11HotkeyMessage
    data class KeyReleased(val keycode: Int, val modifiers: Int, val timestamp: Long) : LinuxX11HotkeyMessage
    data object Wake : LinuxX11HotkeyMessage
    data object Other : LinuxX11HotkeyMessage
}

internal class JnaLinuxX11HotkeyNativeApi(
    private val x11: X11 = X11.INSTANCE,
) : LinuxX11HotkeyNativeApi {
    private var display: X11.Display? = null
    private var rootWindow: X11.Window? = null
    private var wakeAtom: X11.Atom? = null

    override fun open() {
        check(display == null) { "X11 hotkey display is already open." }
        val openedDisplay = x11.XOpenDisplay(null)
            ?: error("Cannot open the current X11 display for global hotkeys.")
        display = openedDisplay
        rootWindow = x11.XDefaultRootWindow(openedDisplay)
        wakeAtom = x11.XInternAtom(openedDisplay, WAKE_ATOM_NAME, false)
        x11.XSelectInput(openedDisplay, requireNotNull(rootWindow), NativeLong(X11.StructureNotifyMask.toLong()))
        x11.XSync(openedDisplay, false)
    }

    override fun keycode(key: GlobalHotkeyKey): Int {
        val openedDisplay = requireNotNull(display)
        val keycode = x11.XKeysymToKeycode(openedDisplay, X11.KeySym(key.toX11Keysym())).toInt() and 0xff
        check(keycode != 0) { "X11 could not resolve ${key.name} to a keycode." }
        return keycode
    }

    override fun grabKey(keycode: Int, modifiers: Int): Int? = synchronized(X11_ERROR_TRAP_LOCK) {
        val openedDisplay = requireNotNull(display)
        var errorCode: Int? = null
        val handler = X11.XErrorHandler { errorDisplay, event ->
            if (errorDisplay == openedDisplay) {
                errorCode = event.error_code.toInt() and 0xff
            }
            0
        }
        val previousHandler = x11.XSetErrorHandler(handler)
        try {
            x11.XGrabKey(
                openedDisplay,
                keycode,
                modifiers,
                requireNotNull(rootWindow),
                0,
                X11.GrabModeAsync,
                X11.GrabModeAsync,
            )
            x11.XSync(openedDisplay, false)
            errorCode
        } finally {
            x11.XSetErrorHandler(previousHandler)
        }
    }

    override fun ungrabKey(keycode: Int, modifiers: Int) {
        val openedDisplay = requireNotNull(display)
        x11.XUngrabKey(openedDisplay, keycode, modifiers, requireNotNull(rootWindow))
        x11.XSync(openedDisplay, false)
    }

    override fun readEvent(): LinuxX11HotkeyMessage {
        val event = X11.XEvent()
        x11.XNextEvent(requireNotNull(display), event)
        event.read()
        return when (event.type) {
            X11.KeyPress -> event.keyMessage(pressed = true)
            X11.KeyRelease -> event.keyMessage(pressed = false)
            X11.ClientMessage -> event.clientMessage()
            else -> LinuxX11HotkeyMessage.Other
        }
    }

    override fun wake(): Boolean {
        val wakeDisplay = x11.XOpenDisplay(null) ?: return false
        return try {
            val root = x11.XDefaultRootWindow(wakeDisplay)
            val wakeMessageAtom = x11.XInternAtom(wakeDisplay, WAKE_ATOM_NAME, false)
            val clientMessage = X11.XClientMessageEvent().apply {
                type = X11.ClientMessage
                display = wakeDisplay
                window = root
                message_type = wakeMessageAtom
                format = 32
                write()
            }
            val event = X11.XEvent().apply {
                setType(X11.XClientMessageEvent::class.java)
                xclient = clientMessage
                write()
            }
            val sent = x11.XSendEvent(
                wakeDisplay,
                root,
                0,
                NativeLong(X11.StructureNotifyMask.toLong()),
                event,
            ) != 0
            x11.XFlush(wakeDisplay)
            sent
        } finally {
            x11.XCloseDisplay(wakeDisplay)
        }
    }

    override fun close() {
        display?.let(x11::XCloseDisplay)
        display = null
        rootWindow = null
        wakeAtom = null
    }

    private fun X11.XEvent.keyMessage(pressed: Boolean): LinuxX11HotkeyMessage {
        setType(X11.XKeyEvent::class.java)
        read()
        val message = xkey
        return if (pressed) {
            LinuxX11HotkeyMessage.KeyPressed(
                keycode = message.keycode,
                modifiers = message.state,
                timestamp = message.time.toLong(),
            )
        } else {
            LinuxX11HotkeyMessage.KeyReleased(
                keycode = message.keycode,
                modifiers = message.state,
                timestamp = message.time.toLong(),
            )
        }
    }

    private fun X11.XEvent.clientMessage(): LinuxX11HotkeyMessage {
        setType(X11.XClientMessageEvent::class.java)
        read()
        return if (xclient.message_type == wakeAtom) {
            LinuxX11HotkeyMessage.Wake
        } else {
            LinuxX11HotkeyMessage.Other
        }
    }
}

internal fun GlobalHotkeyKey.toX11Keysym(): Long = when (this) {
    GlobalHotkeyKey.A,
    GlobalHotkeyKey.B,
    GlobalHotkeyKey.C,
    GlobalHotkeyKey.D,
    GlobalHotkeyKey.E,
    GlobalHotkeyKey.F,
    GlobalHotkeyKey.G,
    GlobalHotkeyKey.H,
    GlobalHotkeyKey.I,
    GlobalHotkeyKey.J,
    GlobalHotkeyKey.K,
    GlobalHotkeyKey.L,
    GlobalHotkeyKey.M,
    GlobalHotkeyKey.N,
    GlobalHotkeyKey.O,
    GlobalHotkeyKey.P,
    GlobalHotkeyKey.Q,
    GlobalHotkeyKey.R,
    GlobalHotkeyKey.S,
    GlobalHotkeyKey.T,
    GlobalHotkeyKey.U,
    GlobalHotkeyKey.V,
    GlobalHotkeyKey.W,
    GlobalHotkeyKey.X,
    GlobalHotkeyKey.Y,
    GlobalHotkeyKey.Z
    -> XK_A + ordinal - GlobalHotkeyKey.A.ordinal
    GlobalHotkeyKey.Digit0,
    GlobalHotkeyKey.Digit1,
    GlobalHotkeyKey.Digit2,
    GlobalHotkeyKey.Digit3,
    GlobalHotkeyKey.Digit4,
    GlobalHotkeyKey.Digit5,
    GlobalHotkeyKey.Digit6,
    GlobalHotkeyKey.Digit7,
    GlobalHotkeyKey.Digit8,
    GlobalHotkeyKey.Digit9
    -> XK_0 + ordinal - GlobalHotkeyKey.Digit0.ordinal
    GlobalHotkeyKey.F1,
    GlobalHotkeyKey.F2,
    GlobalHotkeyKey.F3,
    GlobalHotkeyKey.F4,
    GlobalHotkeyKey.F5,
    GlobalHotkeyKey.F6,
    GlobalHotkeyKey.F7,
    GlobalHotkeyKey.F8,
    GlobalHotkeyKey.F9,
    GlobalHotkeyKey.F10,
    GlobalHotkeyKey.F11,
    GlobalHotkeyKey.F12
    -> XK_F1 + ordinal - GlobalHotkeyKey.F1.ordinal
    GlobalHotkeyKey.Space -> 0x20L
    GlobalHotkeyKey.Tab -> 0xFF09L
    GlobalHotkeyKey.Enter -> 0xFF0DL
    GlobalHotkeyKey.Escape -> 0xFF1BL
    GlobalHotkeyKey.Backspace -> 0xFF08L
    GlobalHotkeyKey.Insert -> 0xFF63L
    GlobalHotkeyKey.Delete -> 0xFFFFL
    GlobalHotkeyKey.Home -> 0xFF50L
    GlobalHotkeyKey.End -> 0xFF57L
    GlobalHotkeyKey.PageUp -> 0xFF55L
    GlobalHotkeyKey.PageDown -> 0xFF56L
    GlobalHotkeyKey.ArrowUp -> 0xFF52L
    GlobalHotkeyKey.ArrowDown -> 0xFF54L
    GlobalHotkeyKey.ArrowLeft -> 0xFF51L
    GlobalHotkeyKey.ArrowRight -> 0xFF53L
    GlobalHotkeyKey.Minus -> '-'.code.toLong()
    GlobalHotkeyKey.Equal -> '='.code.toLong()
    GlobalHotkeyKey.LeftBracket -> '['.code.toLong()
    GlobalHotkeyKey.RightBracket -> ']'.code.toLong()
    GlobalHotkeyKey.Backslash -> '\\'.code.toLong()
    GlobalHotkeyKey.Semicolon -> ';'.code.toLong()
    GlobalHotkeyKey.Apostrophe -> '\''.code.toLong()
    GlobalHotkeyKey.Comma -> ','.code.toLong()
    GlobalHotkeyKey.Period -> '.'.code.toLong()
    GlobalHotkeyKey.Slash -> '/'.code.toLong()
    GlobalHotkeyKey.Grave -> '`'.code.toLong()
    GlobalHotkeyKey.Numpad0,
    GlobalHotkeyKey.Numpad1,
    GlobalHotkeyKey.Numpad2,
    GlobalHotkeyKey.Numpad3,
    GlobalHotkeyKey.Numpad4,
    GlobalHotkeyKey.Numpad5,
    GlobalHotkeyKey.Numpad6,
    GlobalHotkeyKey.Numpad7,
    GlobalHotkeyKey.Numpad8,
    GlobalHotkeyKey.Numpad9
    -> XK_KP_0 + ordinal - GlobalHotkeyKey.Numpad0.ordinal
    GlobalHotkeyKey.NumpadAdd -> 0xFFABL
    GlobalHotkeyKey.NumpadSubtract -> 0xFFADL
    GlobalHotkeyKey.NumpadMultiply -> 0xFFAAL
    GlobalHotkeyKey.NumpadDivide -> 0xFFAFL
    GlobalHotkeyKey.NumpadDecimal -> 0xFFAEL
    GlobalHotkeyKey.NumpadEnter -> 0xFF8DL
}

private val X11_ERROR_TRAP_LOCK = Any()
private const val XK_0 = 0x30L
private const val XK_A = 0x61L
private const val XK_F1 = 0xFFBEL
private const val XK_KP_0 = 0xFFB0L
private const val WAKE_ATOM_NAME = "_MISSION_RECORDER_HOTKEY_WAKE"
