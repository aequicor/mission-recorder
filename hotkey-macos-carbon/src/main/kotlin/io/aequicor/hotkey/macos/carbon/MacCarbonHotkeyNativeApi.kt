package io.aequicor.hotkey.macos.carbon

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.Carbon
import com.sun.jna.ptr.PointerByReference

internal interface MacCarbonHotkeyNativeApi {
    fun installHandler(callback: (MacCarbonHotkeyMessage) -> Unit): Int
    fun registerHotkey(id: Int, keycode: Int, modifiers: Int): Int
    fun unregisterHotkey(id: Int): Int
    fun removeHandler(): Int
}

internal sealed interface MacCarbonHotkeyMessage {
    data class Pressed(val id: Int) : MacCarbonHotkeyMessage
    data class Released(val id: Int) : MacCarbonHotkeyMessage
}

internal class JnaMacCarbonHotkeyNativeApi(
    private val carbon: Carbon = Carbon.INSTANCE,
    private val eventApi: MacCarbonEventApi = MacCarbonEventApi.INSTANCE,
) : MacCarbonHotkeyNativeApi {
    private val hotkeyReferences = mutableMapOf<Int, Pointer>()
    private var eventHandlerReference: Pointer? = null
    private var eventHandlerCallback: Carbon.EventHandlerProcPtr? = null

    override fun installHandler(callback: (MacCarbonHotkeyMessage) -> Unit): Int {
        check(eventHandlerReference == null) { "Carbon hotkey handler is already installed." }
        val nativeCallback = Carbon.EventHandlerProcPtr { _, event, _ ->
            val id = readHotkeyId(event) ?: return@EventHandlerProcPtr EVENT_NOT_HANDLED
            when (eventApi.GetEventKind(event)) {
                EVENT_HOTKEY_PRESSED -> callback(MacCarbonHotkeyMessage.Pressed(id))
                EVENT_HOTKEY_RELEASED -> callback(MacCarbonHotkeyMessage.Released(id))
                else -> return@EventHandlerProcPtr EVENT_NOT_HANDLED
            }
            NO_ERROR
        }
        val eventTypes = arrayOf(
            eventType(EVENT_HOTKEY_PRESSED),
            eventType(EVENT_HOTKEY_RELEASED),
        )
        val handlerReference = PointerByReference()
        val status = carbon.InstallEventHandler(
            carbon.GetEventDispatcherTarget(),
            nativeCallback,
            eventTypes.size,
            eventTypes,
            null,
            handlerReference,
        )
        if (status == NO_ERROR) {
            eventHandlerCallback = nativeCallback
            eventHandlerReference = handlerReference.value
        }
        return status
    }

    override fun registerHotkey(id: Int, keycode: Int, modifiers: Int): Int {
        val reference = PointerByReference()
        val hotkeyId = Carbon.EventHotKeyID.ByValue().apply {
            signature = HOTKEY_SIGNATURE
            this.id = id
            write()
        }
        val status = carbon.RegisterEventHotKey(
            keycode,
            modifiers,
            hotkeyId,
            carbon.GetEventDispatcherTarget(),
            0,
            reference,
        )
        if (status == NO_ERROR) {
            hotkeyReferences[id] = requireNotNull(reference.value) {
                "Carbon registered hotkey $id without returning a reference."
            }
        }
        return status
    }

    override fun unregisterHotkey(id: Int): Int {
        val reference = hotkeyReferences.remove(id) ?: return NO_ERROR
        return carbon.UnregisterEventHotKey(reference)
    }

    override fun removeHandler(): Int {
        val reference = eventHandlerReference ?: return NO_ERROR
        val status = carbon.RemoveEventHandler(reference)
        if (status == NO_ERROR) {
            eventHandlerReference = null
            eventHandlerCallback = null
        }
        return status
    }

    private fun readHotkeyId(event: Pointer): Int? {
        val hotkeyId = Carbon.EventHotKeyID()
        val status = carbon.GetEventParameter(
            event,
            EVENT_PARAM_DIRECT_OBJECT,
            TYPE_EVENT_HOTKEY_ID,
            null,
            hotkeyId.size(),
            null,
            hotkeyId,
        )
        if (status != NO_ERROR) return null
        hotkeyId.read()
        return hotkeyId.id.takeIf { hotkeyId.signature == HOTKEY_SIGNATURE }
    }

    private fun eventType(kind: Int): Carbon.EventTypeSpec = Carbon.EventTypeSpec().apply {
        eventClass = EVENT_CLASS_KEYBOARD
        eventKind = kind
        write()
    }
}

internal interface MacCarbonEventApi : Library {
    fun GetEventKind(event: Pointer): Int

    companion object {
        val INSTANCE: MacCarbonEventApi by lazy { Native.load("Carbon", MacCarbonEventApi::class.java) }
    }
}

private fun fourCharCode(value: String): Int {
    require(value.length == 4)
    return value.fold(0) { result, character -> (result shl 8) or character.code }
}

private const val NO_ERROR = 0
private const val EVENT_NOT_HANDLED = -9874
private const val EVENT_HOTKEY_PRESSED = 5
private const val EVENT_HOTKEY_RELEASED = 6
private val EVENT_CLASS_KEYBOARD = fourCharCode("keyb")
private val EVENT_PARAM_DIRECT_OBJECT = fourCharCode("----")
private val TYPE_EVENT_HOTKEY_ID = fourCharCode("hkid")
private val HOTKEY_SIGNATURE = fourCharCode("MRec")
