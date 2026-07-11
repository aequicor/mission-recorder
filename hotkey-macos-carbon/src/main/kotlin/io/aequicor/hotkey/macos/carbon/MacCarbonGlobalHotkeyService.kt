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

private fun GlobalHotkeyKey.toMacKeycode(): Int = when (this) {
    GlobalHotkeyKey.F9 -> MAC_KEYCODE_F9
    GlobalHotkeyKey.F10 -> MAC_KEYCODE_F10
    GlobalHotkeyKey.F11 -> MAC_KEYCODE_F11
}

private fun GlobalHotkeyGesture.displayName(): String =
    (modifiers.sortedBy(GlobalHotkeyModifier::ordinal).map(GlobalHotkeyModifier::name) + key.name)
        .joinToString("+")

private const val NO_ERROR = 0
private const val FIRST_HOTKEY_ID = 1
private const val MAC_KEYCODE_F9 = 101
private const val MAC_KEYCODE_F10 = 109
private const val MAC_KEYCODE_F11 = 103
