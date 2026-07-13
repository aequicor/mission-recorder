package io.aequicor.hotkey.macos.carbon

import com.sun.jna.platform.mac.Carbon
import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.GlobalHotkeyGesture
import io.aequicor.hotkey.GlobalHotkeyKey
import io.aequicor.hotkey.GlobalHotkeyModifier
import io.aequicor.hotkey.GlobalHotkeyService
import io.aequicor.hotkey.GlobalHotkeyServiceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

class MacCarbonGlobalHotkeyServiceFactory(
    private val osName: String = System.getProperty("os.name").orEmpty(),
) : GlobalHotkeyServiceFactory {
    override val isSupported: Boolean
        get() = osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true)

    override fun create(bindings: List<GlobalHotkeyBinding>): GlobalHotkeyService {
        check(isSupported) { "Carbon global hotkeys are not supported on $osName." }
        return MacCarbonGlobalHotkeyService(bindings, JnaMacCarbonHotkeyNativeApi())
    }
}

class MacCarbonHotkeyRegistrationException(
    val binding: GlobalHotkeyBinding,
    val nativeStatus: Int,
) : IllegalStateException(
    "Could not register macOS global hotkey ${binding.gesture.displayName()} (OSStatus $nativeStatus).",
)

internal class MacCarbonGlobalHotkeyService(
    override val bindings: List<GlobalHotkeyBinding>,
    private val nativeApi: MacCarbonHotkeyNativeApi,
) : GlobalHotkeyService {
    private val mutableEvents = MutableSharedFlow<GlobalHotkeyEvent>(extraBufferCapacity = 16)
    private val closed = AtomicBoolean()
    private val lifecycleLock = Any()
    private val bindingById = bindings
        .also(::validateBindings)
        .mapIndexed { index, binding -> index + FIRST_HOTKEY_ID to binding }
        .toMap()
    private val pressedIds = mutableSetOf<Int>()
    private val registeredIds = mutableListOf<Int>()

    override val events: Flow<GlobalHotkeyEvent> = mutableEvents.asSharedFlow()

    init {
        synchronized(lifecycleLock) {
            val handlerStatus = nativeApi.installHandler(::onNativeMessage)
            check(handlerStatus == NO_ERROR) {
                "Could not install macOS global hotkey handler (OSStatus $handlerStatus)."
            }
            try {
                bindingById.forEach { (id, binding) ->
                    val status = nativeApi.registerHotkey(
                        id = id,
                        keycode = binding.gesture.key.toMacKeycode(),
                        modifiers = binding.gesture.toMacModifiers(),
                    )
                    if (status != NO_ERROR) {
                        throw MacCarbonHotkeyRegistrationException(binding, status)
                    }
                    registeredIds += id
                }
            } catch (failure: Throwable) {
                registeredIds.asReversed().forEach(nativeApi::unregisterHotkey)
                registeredIds.clear()
                nativeApi.removeHandler()
                throw failure
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            val failures = mutableListOf<String>()
            registeredIds.asReversed().forEach { id ->
                nativeApi.unregisterHotkey(id).takeIf { it != NO_ERROR }?.let { status ->
                    failures += "hotkey $id: OSStatus $status"
                }
            }
            registeredIds.clear()
            nativeApi.removeHandler().takeIf { it != NO_ERROR }?.let { status ->
                failures += "event handler: OSStatus $status"
            }
            pressedIds.clear()
            check(failures.isEmpty()) { "Could not release macOS global hotkeys (${failures.joinToString()})." }
        }
    }

    private fun onNativeMessage(message: MacCarbonHotkeyMessage) {
        synchronized(lifecycleLock) {
            if (closed.get()) return
            when (message) {
                is MacCarbonHotkeyMessage.Pressed -> {
                    if (pressedIds.add(message.id)) {
                        bindingById[message.id]?.let { binding ->
                            mutableEvents.tryEmit(GlobalHotkeyEvent.Triggered(binding.action))
                        }
                    }
                }
                is MacCarbonHotkeyMessage.Released -> pressedIds.remove(message.id)
            }
        }
    }
}

private fun validateBindings(bindings: List<GlobalHotkeyBinding>) {
    require(bindings.isNotEmpty()) { "At least one global hotkey binding is required." }
    require(bindings.map(GlobalHotkeyBinding::action).distinct().size == bindings.size) {
        "Global hotkey actions must be unique."
    }
    require(bindings.map(GlobalHotkeyBinding::gesture).distinct().size == bindings.size) {
        "Global hotkey gestures must be unique."
    }
}

private fun GlobalHotkeyGesture.toMacModifiers(): Int = modifiers.fold(0) { result, modifier ->
    result or when (modifier) {
        GlobalHotkeyModifier.Alt -> Carbon.optionKey
        GlobalHotkeyModifier.Control -> Carbon.controlKey
        GlobalHotkeyModifier.Shift -> Carbon.shiftKey
        GlobalHotkeyModifier.Meta -> Carbon.cmdKey
    }
}

internal fun GlobalHotkeyKey.toMacKeycode(): Int = when (this) {
    GlobalHotkeyKey.A -> 0
    GlobalHotkeyKey.B -> 11
    GlobalHotkeyKey.C -> 8
    GlobalHotkeyKey.D -> 2
    GlobalHotkeyKey.E -> 14
    GlobalHotkeyKey.F -> 3
    GlobalHotkeyKey.G -> 5
    GlobalHotkeyKey.H -> 4
    GlobalHotkeyKey.I -> 34
    GlobalHotkeyKey.J -> 38
    GlobalHotkeyKey.K -> 40
    GlobalHotkeyKey.L -> 37
    GlobalHotkeyKey.M -> 46
    GlobalHotkeyKey.N -> 45
    GlobalHotkeyKey.O -> 31
    GlobalHotkeyKey.P -> 35
    GlobalHotkeyKey.Q -> 12
    GlobalHotkeyKey.R -> 15
    GlobalHotkeyKey.S -> 1
    GlobalHotkeyKey.T -> 17
    GlobalHotkeyKey.U -> 32
    GlobalHotkeyKey.V -> 9
    GlobalHotkeyKey.W -> 13
    GlobalHotkeyKey.X -> 7
    GlobalHotkeyKey.Y -> 16
    GlobalHotkeyKey.Z -> 6
    GlobalHotkeyKey.Digit0 -> 29
    GlobalHotkeyKey.Digit1 -> 18
    GlobalHotkeyKey.Digit2 -> 19
    GlobalHotkeyKey.Digit3 -> 20
    GlobalHotkeyKey.Digit4 -> 21
    GlobalHotkeyKey.Digit5 -> 23
    GlobalHotkeyKey.Digit6 -> 22
    GlobalHotkeyKey.Digit7 -> 26
    GlobalHotkeyKey.Digit8 -> 28
    GlobalHotkeyKey.Digit9 -> 25
    GlobalHotkeyKey.F1 -> 122
    GlobalHotkeyKey.F2 -> 120
    GlobalHotkeyKey.F3 -> 99
    GlobalHotkeyKey.F4 -> 118
    GlobalHotkeyKey.F5 -> 96
    GlobalHotkeyKey.F6 -> 97
    GlobalHotkeyKey.F7 -> 98
    GlobalHotkeyKey.F8 -> 100
    GlobalHotkeyKey.F9 -> 101
    GlobalHotkeyKey.F10 -> 109
    GlobalHotkeyKey.F11 -> 103
    GlobalHotkeyKey.F12 -> 111
    GlobalHotkeyKey.Space -> 49
    GlobalHotkeyKey.Tab -> 48
    GlobalHotkeyKey.Enter -> 36
    GlobalHotkeyKey.Escape -> 53
    GlobalHotkeyKey.Backspace -> 51
    GlobalHotkeyKey.Insert -> 114
    GlobalHotkeyKey.Delete -> 117
    GlobalHotkeyKey.Home -> 115
    GlobalHotkeyKey.End -> 119
    GlobalHotkeyKey.PageUp -> 116
    GlobalHotkeyKey.PageDown -> 121
    GlobalHotkeyKey.ArrowUp -> 126
    GlobalHotkeyKey.ArrowDown -> 125
    GlobalHotkeyKey.ArrowLeft -> 123
    GlobalHotkeyKey.ArrowRight -> 124
    GlobalHotkeyKey.Minus -> 27
    GlobalHotkeyKey.Equal -> 24
    GlobalHotkeyKey.LeftBracket -> 33
    GlobalHotkeyKey.RightBracket -> 30
    GlobalHotkeyKey.Backslash -> 42
    GlobalHotkeyKey.Semicolon -> 41
    GlobalHotkeyKey.Apostrophe -> 39
    GlobalHotkeyKey.Comma -> 43
    GlobalHotkeyKey.Period -> 47
    GlobalHotkeyKey.Slash -> 44
    GlobalHotkeyKey.Grave -> 50
    GlobalHotkeyKey.Numpad0 -> 82
    GlobalHotkeyKey.Numpad1 -> 83
    GlobalHotkeyKey.Numpad2 -> 84
    GlobalHotkeyKey.Numpad3 -> 85
    GlobalHotkeyKey.Numpad4 -> 86
    GlobalHotkeyKey.Numpad5 -> 87
    GlobalHotkeyKey.Numpad6 -> 88
    GlobalHotkeyKey.Numpad7 -> 89
    GlobalHotkeyKey.Numpad8 -> 91
    GlobalHotkeyKey.Numpad9 -> 92
    GlobalHotkeyKey.NumpadAdd -> 69
    GlobalHotkeyKey.NumpadSubtract -> 78
    GlobalHotkeyKey.NumpadMultiply -> 67
    GlobalHotkeyKey.NumpadDivide -> 75
    GlobalHotkeyKey.NumpadDecimal -> 65
    GlobalHotkeyKey.NumpadEnter -> 76
}

private fun GlobalHotkeyGesture.displayName(): String =
    (modifiers.sortedBy(GlobalHotkeyModifier::ordinal).map(GlobalHotkeyModifier::name) + key.name)
        .joinToString("+")

private const val NO_ERROR = 0
private const val FIRST_HOTKEY_ID = 1
