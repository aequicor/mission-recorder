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

private const val RGBA_CHANNELS = 4L
private const val BITS_PER_COMPONENT = 8
private const val RGBA_BITMAP_INFO = 0x4001
