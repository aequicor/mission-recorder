package io.aequicor.hotkey.windows.jna

import io.aequicor.hotkey.GlobalHotkeyAction
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WindowsGlobalHotkeyServiceFactory(
    private val osName: String = System.getProperty("os.name").orEmpty(),
) : GlobalHotkeyServiceFactory {
    override val isSupported: Boolean
        get() = osName.lowercase().startsWith("windows")

    override fun create(
        bindings: List<GlobalHotkeyBinding>,
    ): GlobalHotkeyService {
        check(isSupported) { "Global hotkeys are not supported on $osName." }
        return WindowsGlobalHotkeyService(
            bindings = bindings,
            nativeApi = JnaWindowsHotkeyNativeApi(),
        )
    }
}

class GlobalHotkeyRegistrationException(
    val binding: GlobalHotkeyBinding,
    val nativeErrorCode: Int,
) : IllegalStateException(
    "Could not register global hotkey ${binding.gesture.displayName()} (Win32 error $nativeErrorCode).",
)

internal class WindowsGlobalHotkeyService(
    override val bindings: List<GlobalHotkeyBinding>,
    private val nativeApi: WindowsHotkeyNativeApi,
    private val startupTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val shutdownTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : GlobalHotkeyService {
    private val mutableEvents = MutableSharedFlow<GlobalHotkeyEvent>(extraBufferCapacity = 16)
    private val startupLatch = CountDownLatch(1)
    private val threadId = AtomicInteger()
    private val closed = AtomicBoolean()
    private val bindingById = bindings
        .also(::validateBindings)
        .mapIndexed { index, binding -> index + FIRST_HOTKEY_ID to binding }
        .toMap()
    private val worker = Thread(::runMessageLoop, "mission-recorder-global-hotkeys").apply {
        isDaemon = true
    }

    @Volatile
    private var startupFailure: Throwable? = null

    override val events: Flow<GlobalHotkeyEvent> = mutableEvents.asSharedFlow()

    init {
        worker.start()
        if (!startupLatch.await(startupTimeoutMillis, TimeUnit.MILLISECONDS)) {
            close()
            error("Timed out while starting the Windows global hotkey message loop.")
        }
        startupFailure?.let { failure ->
            close()
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        if (worker.isAlive) {
            val registeredThreadId = threadId.get()
            if (registeredThreadId != 0 && !nativeApi.postQuit(registeredThreadId)) {
                mutableEvents.tryEmit(
                    GlobalHotkeyEvent.Failed(
                        "Could not stop the Windows global hotkey message loop " +
                            "(Win32 error ${nativeApi.lastErrorCode()}).",
                    ),
                )
            }
            worker.join(shutdownTimeoutMillis)
        }
        check(!worker.isAlive) { "Windows global hotkey message loop did not stop." }
    }

    private fun runMessageLoop() {
        val registeredIds = mutableListOf<Int>()
        try {
            nativeApi.initializeMessageQueue()
            threadId.set(nativeApi.currentThreadId())
            bindingById.forEach { (id, binding) ->
                val registered = nativeApi.registerHotkey(
                    id,
                    binding.gesture.toNativeModifiers(),
                    binding.gesture.key.toWindowsVirtualKey(),
                )
                if (!registered) {
                    throw GlobalHotkeyRegistrationException(binding, nativeApi.lastErrorCode())
                }
                registeredIds += id
            }
            startupLatch.countDown()
            while (!closed.get()) {
                when (val message = nativeApi.readMessage()) {
                    is WindowsHotkeyMessage.Hotkey -> bindingById[message.id]?.let { binding ->
                        mutableEvents.tryEmit(GlobalHotkeyEvent.Triggered(binding.action))
                    }
                    WindowsHotkeyMessage.Other -> Unit
                    WindowsHotkeyMessage.Quit -> return
                    is WindowsHotkeyMessage.Failed -> error(
                        "Windows global hotkey message loop failed with error ${message.nativeErrorCode}.",
                    )
                }
            }
        } catch (failure: Throwable) {
            if (startupLatch.count > 0) {
                startupFailure = failure
            } else {
                mutableEvents.tryEmit(
                    GlobalHotkeyEvent.Failed(failure.message ?: "Windows global hotkey message loop failed."),
                )
            }
        } finally {
            registeredIds.asReversed().forEach(nativeApi::unregisterHotkey)
            startupLatch.countDown()
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

private fun GlobalHotkeyGesture.toNativeModifiers(): Int =
    modifiers.fold(MOD_NOREPEAT) { nativeModifiers, modifier ->
        nativeModifiers or when (modifier) {
            GlobalHotkeyModifier.Alt -> MOD_ALT
            GlobalHotkeyModifier.Control -> MOD_CONTROL
            GlobalHotkeyModifier.Shift -> MOD_SHIFT
            GlobalHotkeyModifier.Meta -> MOD_WIN
        }
    }

internal fun GlobalHotkeyKey.toWindowsVirtualKey(): Int = when (this) {
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
    -> VK_A + ordinal - GlobalHotkeyKey.A.ordinal
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
    -> VK_0 + ordinal - GlobalHotkeyKey.Digit0.ordinal
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
    -> VK_F1 + ordinal - GlobalHotkeyKey.F1.ordinal
    GlobalHotkeyKey.Space -> 0x20
    GlobalHotkeyKey.Tab -> 0x09
    GlobalHotkeyKey.Enter -> 0x0D
    GlobalHotkeyKey.Escape -> 0x1B
    GlobalHotkeyKey.Backspace -> 0x08
    GlobalHotkeyKey.Insert -> 0x2D
    GlobalHotkeyKey.Delete -> 0x2E
    GlobalHotkeyKey.Home -> 0x24
    GlobalHotkeyKey.End -> 0x23
    GlobalHotkeyKey.PageUp -> 0x21
    GlobalHotkeyKey.PageDown -> 0x22
    GlobalHotkeyKey.ArrowUp -> 0x26
    GlobalHotkeyKey.ArrowDown -> 0x28
    GlobalHotkeyKey.ArrowLeft -> 0x25
    GlobalHotkeyKey.ArrowRight -> 0x27
    GlobalHotkeyKey.Minus -> 0xBD
    GlobalHotkeyKey.Equal -> 0xBB
    GlobalHotkeyKey.LeftBracket -> 0xDB
    GlobalHotkeyKey.RightBracket -> 0xDD
    GlobalHotkeyKey.Backslash -> 0xDC
    GlobalHotkeyKey.Semicolon -> 0xBA
    GlobalHotkeyKey.Apostrophe -> 0xDE
    GlobalHotkeyKey.Comma -> 0xBC
    GlobalHotkeyKey.Period -> 0xBE
    GlobalHotkeyKey.Slash -> 0xBF
    GlobalHotkeyKey.Grave -> 0xC0
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
    -> VK_NUMPAD0 + ordinal - GlobalHotkeyKey.Numpad0.ordinal
    GlobalHotkeyKey.NumpadAdd -> 0x6B
    GlobalHotkeyKey.NumpadSubtract -> 0x6D
    GlobalHotkeyKey.NumpadMultiply -> 0x6A
    GlobalHotkeyKey.NumpadDivide -> 0x6F
    GlobalHotkeyKey.NumpadDecimal -> 0x6E
    GlobalHotkeyKey.NumpadEnter -> 0x0D
}

private fun GlobalHotkeyGesture.displayName(): String =
    (modifiers.sortedBy(GlobalHotkeyModifier::ordinal).map(GlobalHotkeyModifier::name) + key.name)
        .joinToString("+")

private const val FIRST_HOTKEY_ID = 1
private const val DEFAULT_TIMEOUT_MILLIS = 5_000L
private const val MOD_ALT = 0x0001
private const val MOD_CONTROL = 0x0002
private const val MOD_SHIFT = 0x0004
private const val MOD_WIN = 0x0008
private const val MOD_NOREPEAT = 0x4000
private const val VK_0 = 0x30
private const val VK_A = 0x41
private const val VK_F1 = 0x70
private const val VK_NUMPAD0 = 0x60
