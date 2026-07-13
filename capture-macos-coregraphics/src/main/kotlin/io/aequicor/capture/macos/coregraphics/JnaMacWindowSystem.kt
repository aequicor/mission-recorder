package io.aequicor.capture.macos.coregraphics

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.mac.CoreGraphics
import com.sun.jna.platform.unix.LibCAPI.size_t
import java.awt.MouseInfo
import kotlin.math.ceil
import kotlin.math.floor

internal class JnaMacWindowSystem(
    private val coreFoundation: CoreFoundation = CoreFoundation.INSTANCE,
    private val coreGraphics: CoreGraphics = CoreGraphics.INSTANCE,
    private val imageApi: MacCoreGraphicsImageApi = MacCoreGraphicsImageApi.INSTANCE,
    private val inputApi: MacInputApi = MacInputApi.INSTANCE,
    private val excludedProcessId: Long = ProcessHandle.current().pid(),
) : MacWindowSystem {
    fun probe() {
        val displayId = coreGraphics.CGMainDisplayID()
        if (displayId == 0) throw MacCaptureFailure("CoreGraphics did not report a main display.")
    }

    override fun listWindows(): List<MacWindowDescriptor> = copyWindowInfo()

    override fun findWindow(windowId: Long): MacWindowDescriptor? =
        copyWindowInfo().firstOrNull { it.windowId == windowId }

    override fun captureWindow(windowId: Long): MacCapturedFrame {
        val descriptor = findWindow(windowId) ?: throw MacCaptureFailure("The selected macOS window no longer exists.")
        if (descriptor.minimized) throw MacCaptureFailure("The selected macOS window is not onscreen.")
        val rectangle = descriptor.bounds.toCGRect()
        val image = imageApi.CGWindowListCreateImage(
            rectangle,
            CoreGraphics.kCGWindowListOptionIncludingWindow,
            windowId.toInt(),
            CoreGraphics.kCGWindowImageBoundsIgnoreFraming or
                CoreGraphics.kCGWindowImageBestResolution or
                CoreGraphics.kCGWindowImageShouldBeOpaque,
        ) ?: throw MacCaptureFailure("CoreGraphics returned no image. Check Screen Recording permission.")
        try {
            val width = imageApi.CGImageGetWidth(image).toLong()
            val height = imageApi.CGImageGetHeight(image).toLong()
            val bytesPerRow = width * RGBA_CHANNELS
            val byteCount = bytesPerRow * height
            if (width <= 0 || height <= 0 || width > Int.MAX_VALUE || height > Int.MAX_VALUE || byteCount > Int.MAX_VALUE) {
                throw MacCaptureFailure("CoreGraphics returned invalid image dimensions: ${width}x$height.")
            }
            val memory = Memory(byteCount).apply { clear() }
            val colorSpace = imageApi.CGColorSpaceCreateDeviceRGB()
                ?: throw MacCaptureFailure("Could not create an RGB color space.")
            try {
                val context = imageApi.CGBitmapContextCreate(
                    memory,
                    size_t(width),
                    size_t(height),
                    BITS_PER_COMPONENT,
                    size_t(bytesPerRow),
                    colorSpace,
                    RGBA_BITMAP_INFO,
                ) ?: throw MacCaptureFailure("Could not create an RGBA bitmap context.")
                try {
                    imageApi.CGContextTranslateCTM(context, 0.0, height.toDouble())
                    imageApi.CGContextScaleCTM(context, 1.0, -1.0)
                    imageApi.CGContextDrawImage(context, pixelRect(width, height), image)
                    return MacCapturedFrame(
                        logicalBounds = descriptor.bounds,
                        pixelWidth = width.toInt(),
                        pixelHeight = height.toInt(),
                        rgbaPixels = memory.getByteArray(0, byteCount.toInt()),
                    )
                } finally {
                    imageApi.CGContextRelease(context)
                }
            } finally {
                imageApi.CGColorSpaceRelease(colorSpace)
            }
        } finally {
            imageApi.CGImageRelease(image)
        }
    }

    override fun cursorPosition(): MacPoint? = runCatching {
        MouseInfo.getPointerInfo()?.location?.let { MacPoint(it.x, it.y) }
    }.getOrNull()

    override fun pressedInputs(): List<String> = buildList {
        MAC_KEY_INPUTS
            .filter { input -> input.keyCodes.any { keyCode -> inputApi.CGEventSourceKeyState(SESSION_STATE, keyCode) } }
            .mapTo(this, MacKeyInput::label)
        if (inputApi.CGEventSourceButtonState(SESSION_STATE, 0)) add("LMB")
        if (inputApi.CGEventSourceButtonState(SESSION_STATE, 1)) add("RMB")
        if (inputApi.CGEventSourceButtonState(SESSION_STATE, 2)) add("MMB")
        if (inputApi.CGEventSourceButtonState(SESSION_STATE, 3)) add("Mouse 4")
        if (inputApi.CGEventSourceButtonState(SESSION_STATE, 4)) add("Mouse 5")
    }

    private fun copyWindowInfo(): List<MacWindowDescriptor> {
        val array = coreGraphics.CGWindowListCopyWindowInfo(
            CoreGraphics.kCGWindowListOptionAll or CoreGraphics.kCGWindowListExcludeDesktopElements,
            CoreGraphics.kCGNullWindowID,
        ) ?: return emptyList()
        return try {
            List(array.count) { index ->
                CoreFoundation.CFDictionaryRef(array.getValueAtIndex(index))
            }.mapNotNull(::descriptor).sortedBy { it.title.lowercase() }
        } finally {
            coreFoundation.CFRelease(array)
        }
    }

    private fun descriptor(dictionary: CoreFoundation.CFDictionaryRef): MacWindowDescriptor? {
        val windowId = dictionary.number(CoreGraphics.kCGWindowNumber)?.longValue() ?: return null
        val processId = dictionary.number(CoreGraphics.kCGWindowOwnerPID)?.longValue() ?: return null
        val layer = dictionary.number(CoreGraphics.kCGWindowLayer)?.intValue() ?: return null
        val sharing = dictionary.number(CoreGraphics.kCGWindowSharingState)?.intValue() ?: return null
        val alpha = dictionary.number(CoreGraphics.kCGWindowAlpha)?.doubleValue() ?: 1.0
        if (windowId <= 0 || processId <= 0 || processId == excludedProcessId || layer != 0 || sharing == 0 || alpha <= 0.0) {
            return null
        }
        val owner = dictionary.string(CoreGraphics.kCGWindowOwnerName)?.takeIf(String::isNotBlank) ?: return null
        val title = dictionary.string(CoreGraphics.kCGWindowName)?.takeIf(String::isNotBlank)
            ?: "$owner window $windowId"
        val boundsDictionary = dictionary.value(CoreGraphics.kCGWindowBounds)
            ?.let { pointer -> CoreFoundation.CFDictionaryRef(pointer) } ?: return null
        val rectangle = CoreGraphics.CGRect()
        if (coreGraphics.CGRectMakeWithDictionaryRepresentation(boundsDictionary, rectangle).toInt() == 0) return null
        rectangle.read()
        val bounds = rectangle.toBounds() ?: return null
        val onscreen = dictionary.boolean(CoreGraphics.kCGWindowIsOnscreen) ?: false
        return MacWindowDescriptor(windowId, processId, title, owner, bounds, minimized = !onscreen)
    }

    private fun CoreFoundation.CFDictionaryRef.value(key: String): Pointer? = withKey(key) { getValue(it) }
    private fun CoreFoundation.CFDictionaryRef.number(key: String): CoreFoundation.CFNumberRef? =
        value(key)?.let { pointer -> CoreFoundation.CFNumberRef(pointer) }
    private fun CoreFoundation.CFDictionaryRef.string(key: String): String? =
        value(key)?.let { pointer -> CoreFoundation.CFStringRef(pointer) }?.stringValue()
    private fun CoreFoundation.CFDictionaryRef.boolean(key: String): Boolean? =
        value(key)?.let { pointer -> CoreFoundation.CFBooleanRef(pointer) }
            ?.let { value -> coreFoundation.CFBooleanGetValue(value).toInt() != 0 }

    private inline fun <T> withKey(value: String, block: (CoreFoundation.CFStringRef) -> T): T {
        val key = CoreFoundation.CFStringRef.createCFString(value)
        return try {
            block(key)
        } finally {
            coreFoundation.CFRelease(key)
        }
    }
}

internal interface MacCoreGraphicsImageApi : Library {
    fun CGWindowListCreateImage(
        screenBounds: CoreGraphics.CGRect.ByValue,
        listOption: Int,
        windowId: Int,
        imageOption: Int,
    ): Pointer?

    fun CGImageGetWidth(image: Pointer): size_t
    fun CGImageGetHeight(image: Pointer): size_t
    fun CGImageRelease(image: Pointer)
    fun CGColorSpaceCreateDeviceRGB(): Pointer?
    fun CGColorSpaceRelease(colorSpace: Pointer)
    fun CGBitmapContextCreate(
        data: Pointer,
        width: size_t,
        height: size_t,
        bitsPerComponent: Int,
        bytesPerRow: size_t,
        colorSpace: Pointer,
        bitmapInfo: Int,
    ): Pointer?
    fun CGContextTranslateCTM(context: Pointer, tx: Double, ty: Double)
    fun CGContextScaleCTM(context: Pointer, sx: Double, sy: Double)
    fun CGContextDrawImage(context: Pointer, rectangle: CoreGraphics.CGRect.ByValue, image: Pointer)
    fun CGContextRelease(context: Pointer)
    fun CGPreflightScreenCaptureAccess(): Int
    fun CGRequestScreenCaptureAccess(): Int

    companion object {
        val INSTANCE: MacCoreGraphicsImageApi by lazy {
            Native.load("CoreGraphics", MacCoreGraphicsImageApi::class.java)
        }
    }
}

internal interface MacInputApi : Library {
    fun CGEventSourceKeyState(stateId: Int, keyCode: Short): Boolean
    fun CGEventSourceButtonState(stateId: Int, button: Int): Boolean

    companion object {
        val INSTANCE: MacInputApi by lazy { Native.load("CoreGraphics", MacInputApi::class.java) }
    }
}

private val CoreFoundation.CFArrayRef.count: Int get() = coreCount().toInt()
private fun CoreFoundation.CFArrayRef.coreCount() = CoreFoundation.INSTANCE.CFArrayGetCount(this).toLong()

private fun CoreGraphics.CGRect.toBounds(): MacWindowBounds? {
    val x = floor(origin.x).toInt()
    val y = floor(origin.y).toInt()
    val width = ceil(size.width).toInt()
    val height = ceil(size.height).toInt()
    return MacWindowBounds(x, y, width, height).takeIf { width > 1 && height > 1 }
}

private fun MacWindowBounds.toCGRect() = CoreGraphics.CGRect.ByValue().apply {
    origin.x = this@toCGRect.x.toDouble()
    origin.y = this@toCGRect.y.toDouble()
    size.width = this@toCGRect.width.toDouble()
    size.height = this@toCGRect.height.toDouble()
    write()
}

private fun pixelRect(width: Long, height: Long) = CoreGraphics.CGRect.ByValue().apply {
    origin.x = 0.0
    origin.y = 0.0
    size.width = width.toDouble()
    size.height = height.toDouble()
    write()
}

private data class MacKeyInput(
    val label: String,
    val keyCodes: ShortArray,
)

private val MAC_KEY_INPUTS: List<MacKeyInput> = listOf(
    MacKeyInput("Ctrl", shortArrayOf(59, 62)),
    MacKeyInput("Shift", shortArrayOf(56, 60)),
    MacKeyInput("Option", shortArrayOf(58, 61)),
    MacKeyInput("Cmd", shortArrayOf(54, 55)),
    MacKeyInput("A", shortArrayOf(0)),
    MacKeyInput("B", shortArrayOf(11)),
    MacKeyInput("C", shortArrayOf(8)),
    MacKeyInput("D", shortArrayOf(2)),
    MacKeyInput("E", shortArrayOf(14)),
    MacKeyInput("F", shortArrayOf(3)),
    MacKeyInput("G", shortArrayOf(5)),
    MacKeyInput("H", shortArrayOf(4)),
    MacKeyInput("I", shortArrayOf(34)),
    MacKeyInput("J", shortArrayOf(38)),
    MacKeyInput("K", shortArrayOf(40)),
    MacKeyInput("L", shortArrayOf(37)),
    MacKeyInput("M", shortArrayOf(46)),
    MacKeyInput("N", shortArrayOf(45)),
    MacKeyInput("O", shortArrayOf(31)),
    MacKeyInput("P", shortArrayOf(35)),
    MacKeyInput("Q", shortArrayOf(12)),
    MacKeyInput("R", shortArrayOf(15)),
    MacKeyInput("S", shortArrayOf(1)),
    MacKeyInput("T", shortArrayOf(17)),
    MacKeyInput("U", shortArrayOf(32)),
    MacKeyInput("V", shortArrayOf(9)),
    MacKeyInput("W", shortArrayOf(13)),
    MacKeyInput("X", shortArrayOf(7)),
    MacKeyInput("Y", shortArrayOf(16)),
    MacKeyInput("Z", shortArrayOf(6)),
    MacKeyInput("0", shortArrayOf(29)),
    MacKeyInput("1", shortArrayOf(18)),
    MacKeyInput("2", shortArrayOf(19)),
    MacKeyInput("3", shortArrayOf(20)),
    MacKeyInput("4", shortArrayOf(21)),
    MacKeyInput("5", shortArrayOf(23)),
    MacKeyInput("6", shortArrayOf(22)),
    MacKeyInput("7", shortArrayOf(26)),
    MacKeyInput("8", shortArrayOf(28)),
    MacKeyInput("9", shortArrayOf(25)),
    MacKeyInput("F1", shortArrayOf(122)),
    MacKeyInput("F2", shortArrayOf(120)),
    MacKeyInput("F3", shortArrayOf(99)),
    MacKeyInput("F4", shortArrayOf(118)),
    MacKeyInput("F5", shortArrayOf(96)),
    MacKeyInput("F6", shortArrayOf(97)),
    MacKeyInput("F7", shortArrayOf(98)),
    MacKeyInput("F8", shortArrayOf(100)),
    MacKeyInput("F9", shortArrayOf(101)),
    MacKeyInput("F10", shortArrayOf(109)),
    MacKeyInput("F11", shortArrayOf(103)),
    MacKeyInput("F12", shortArrayOf(111)),
    MacKeyInput("Esc", shortArrayOf(53)),
    MacKeyInput("Tab", shortArrayOf(48)),
    MacKeyInput("Enter", shortArrayOf(36, 76)),
    MacKeyInput("Space", shortArrayOf(49)),
    MacKeyInput("Backspace", shortArrayOf(51)),
    MacKeyInput("Delete", shortArrayOf(117)),
    MacKeyInput("Home", shortArrayOf(115)),
    MacKeyInput("End", shortArrayOf(119)),
    MacKeyInput("PgUp", shortArrayOf(116)),
    MacKeyInput("PgDn", shortArrayOf(121)),
    MacKeyInput("←", shortArrayOf(123)),
    MacKeyInput("↑", shortArrayOf(126)),
    MacKeyInput("→", shortArrayOf(124)),
    MacKeyInput("↓", shortArrayOf(125)),
    MacKeyInput("-", shortArrayOf(27)),
    MacKeyInput("=", shortArrayOf(24)),
    MacKeyInput("[", shortArrayOf(33)),
    MacKeyInput("]", shortArrayOf(30)),
    MacKeyInput("\\", shortArrayOf(42)),
    MacKeyInput(";", shortArrayOf(41)),
    MacKeyInput("'", shortArrayOf(39)),
    MacKeyInput(",", shortArrayOf(43)),
    MacKeyInput(".", shortArrayOf(47)),
    MacKeyInput("/", shortArrayOf(44)),
    MacKeyInput("`", shortArrayOf(50)),
)

private const val RGBA_CHANNELS = 4L
private const val BITS_PER_COMPONENT = 8
private const val RGBA_BITMAP_INFO = 0x4001
private const val SESSION_STATE = 0
