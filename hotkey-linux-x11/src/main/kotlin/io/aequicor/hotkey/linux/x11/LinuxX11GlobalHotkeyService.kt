package io.aequicor.hotkey.linux.x11

import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyEvent
import io.aequicor.hotkey.GlobalHotkeyGesture
import io.aequicor.hotkey.GlobalHotkeyModifier
import io.aequicor.hotkey.GlobalHotkeyService
import io.aequicor.hotkey.GlobalHotkeyServiceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LinuxX11GlobalHotkeyServiceFactory(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val sessionType: String = System.getenv("XDG_SESSION_TYPE").orEmpty(),
    private val waylandDisplay: String = System.getenv("WAYLAND_DISPLAY").orEmpty(),
    private val x11Display: String = System.getenv("DISPLAY").orEmpty(),
) : GlobalHotkeyServiceFactory {
    override val isSupported: Boolean
        get() {
            val normalizedSession = sessionType.trim().lowercase()
            val isWayland = normalizedSession == "wayland" || waylandDisplay.isNotBlank()
            val isX11 = normalizedSession == "x11" || x11Display.isNotBlank()
            return osName.contains("linux", ignoreCase = true) && isX11 && !isWayland
        }

    override fun create(bindings: List<GlobalHotkeyBinding>): GlobalHotkeyService {
        check(isSupported) { "X11 global hotkeys are not supported in the current desktop session." }
        return LinuxX11GlobalHotkeyService(bindings, JnaLinuxX11HotkeyNativeApi())
    }
}

class LinuxX11HotkeyRegistrationException(
    val binding: GlobalHotkeyBinding,
    val nativeErrorCode: Int,
) : IllegalStateException(
    "Could not register X11 global hotkey ${binding.gesture.displayName()} (X11 error $nativeErrorCode).",
)

internal class LinuxX11GlobalHotkeyService(
    override val bindings: List<GlobalHotkeyBinding>,
    private val nativeApi: LinuxX11HotkeyNativeApi,
    private val startupTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val shutdownTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : GlobalHotkeyService {
    private val mutableEvents = MutableSharedFlow<GlobalHotkeyEvent>(extraBufferCapacity = 16)
    private val startupLatch = CountDownLatch(1)
    private val closed = AtomicBoolean()
    private val validatedBindings = bindings.also(::validateBindings)
    private val worker = Thread(::runEventLoop, "mission-recorder-x11-global-hotkeys").apply {
        isDaemon = true
    }

    @Volatile
    private var startupFailure: Throwable? = null

    override val events: Flow<GlobalHotkeyEvent> = mutableEvents.asSharedFlow()

    init {
        worker.start()
        if (!startupLatch.await(startupTimeoutMillis, TimeUnit.MILLISECONDS)) {
            close()
            error("Timed out while starting the X11 global hotkey event loop.")
        }
        startupFailure?.let { failure ->
            close()
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (worker.isAlive && !nativeApi.wake()) {
            mutableEvents.tryEmit(GlobalHotkeyEvent.Failed("Could not wake the X11 global hotkey event loop."))
        }
        worker.join(shutdownTimeoutMillis)
        check(!worker.isAlive) { "X11 global hotkey event loop did not stop." }
    }

    private fun runEventLoop() {
        val registeredGrabs = mutableListOf<NativeGrab>()
        try {
            nativeApi.open()
            if (closed.get()) return
            val bindingByGesture = buildMap {
                validatedBindings.forEach { binding ->
                    val keycode = nativeApi.keycode(binding.gesture.key)
                    val nativeModifiers = binding.gesture.toNativeModifiers()
                    put(NativeGesture(keycode, nativeModifiers), binding)
                    LOCK_VARIANTS.forEach { lockModifiers ->
                        val grab = NativeGrab(keycode, nativeModifiers or lockModifiers)
                        nativeApi.grabKey(grab.keycode, grab.modifiers)?.let { errorCode ->
                            throw LinuxX11HotkeyRegistrationException(binding, errorCode)
                        }
                        registeredGrabs += grab
                    }
                }
            }
            startupLatch.countDown()
            val keyStates = mutableMapOf<NativeGesture, Long?>()
            while (!closed.get()) {
                when (val message = nativeApi.readEvent()) {
                    is LinuxX11HotkeyMessage.KeyPressed -> {
                        val gesture = NativeGesture(
                            message.keycode,
                            message.modifiers and RELEVANT_MODIFIER_MASK,
                        )
                        val previousRelease = keyStates[gesture]
                        val isRepeat = keyStates.containsKey(gesture) &&
                            (previousRelease == null || previousRelease == message.timestamp)
                        keyStates[gesture] = null
                        if (!isRepeat) {
                            bindingByGesture[gesture]?.let { binding ->
                                mutableEvents.tryEmit(GlobalHotkeyEvent.Triggered(binding.action))
                            }
                        }
                    }
                    is LinuxX11HotkeyMessage.KeyReleased -> {
                        val gesture = NativeGesture(
                            message.keycode,
                            message.modifiers and RELEVANT_MODIFIER_MASK,
                        )
                        if (keyStates.containsKey(gesture)) {
                            keyStates[gesture] = message.timestamp
                        }
                    }
                    LinuxX11HotkeyMessage.Wake -> if (closed.get()) return
                    LinuxX11HotkeyMessage.Other -> Unit
                }
            }
        } catch (failure: Throwable) {
            if (startupLatch.count > 0) {
                startupFailure = failure
            } else {
                mutableEvents.tryEmit(
                    GlobalHotkeyEvent.Failed(failure.message ?: "X11 global hotkey event loop failed."),
                )
            }
        } finally {
            registeredGrabs.asReversed().forEach { grab ->
                runCatching { nativeApi.ungrabKey(grab.keycode, grab.modifiers) }
            }
            runCatching(nativeApi::close)
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

private fun GlobalHotkeyGesture.toNativeModifiers(): Int = modifiers.fold(0) { result, modifier ->
    result or when (modifier) {
        GlobalHotkeyModifier.Alt -> MOD1_MASK
        GlobalHotkeyModifier.Control -> CONTROL_MASK
        GlobalHotkeyModifier.Shift -> SHIFT_MASK
        GlobalHotkeyModifier.Meta -> MOD4_MASK
    }
}

private fun GlobalHotkeyGesture.displayName(): String =
    (modifiers.sortedBy(GlobalHotkeyModifier::ordinal).map(GlobalHotkeyModifier::name) + key.name)
        .joinToString("+")

private data class NativeGesture(val keycode: Int, val modifiers: Int)
private data class NativeGrab(val keycode: Int, val modifiers: Int)

private const val DEFAULT_TIMEOUT_MILLIS = 5_000L
private const val SHIFT_MASK = 1 shl 0
private const val LOCK_MASK = 1 shl 1
private const val CONTROL_MASK = 1 shl 2
private const val MOD1_MASK = 1 shl 3
private const val MOD2_MASK = 1 shl 4
private const val MOD4_MASK = 1 shl 6
private const val RELEVANT_MODIFIER_MASK = SHIFT_MASK or CONTROL_MASK or MOD1_MASK or MOD4_MASK
private val LOCK_VARIANTS = listOf(0, LOCK_MASK, MOD2_MASK, LOCK_MASK or MOD2_MASK)
