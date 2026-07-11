package io.aequicor.hotkey.windows.jna

import io.aequicor.hotkey.GlobalHotkeyAction
import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.GlobalHotkeyGesture
import io.aequicor.hotkey.GlobalHotkeyKey
import io.aequicor.hotkey.GlobalHotkeyModifier
import io.aequicor.hotkey.GlobalHotkeyService
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WindowsGlobalHotkeyServiceFactory(
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    val isSupported: Boolean
        get() = osName.lowercase().startsWith("windows")

    fun create(
        bindings: List<GlobalHotkeyBinding> = defaultDesktopGlobalHotkeys,
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
                if (!nativeApi.registerHotkey(id, binding.gesture.toNativeModifiers(), binding.gesture.key.toVirtualKey())) {
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

private fun GlobalHotkeyKey.toVirtualKey(): Int = when (this) {
    GlobalHotkeyKey.F9 -> VK_F9
    GlobalHotkeyKey.F10 -> VK_F10
    GlobalHotkeyKey.F11 -> VK_F11
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
private const val VK_F9 = 0x78
private const val VK_F10 = 0x79
private const val VK_F11 = 0x7A
