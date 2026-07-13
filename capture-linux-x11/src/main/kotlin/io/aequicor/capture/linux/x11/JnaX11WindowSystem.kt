package io.aequicor.capture.linux.x11

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.atomic.AtomicInteger

internal class JnaX11WindowSystem(
    private val x11: X11 = X11.INSTANCE,
    private val imageLibrary: X11ImageLibrary = X11ImageLibrary.INSTANCE,
) : X11WindowSystem {
    private val keyCodeCache = mutableMapOf<Long, Int>()

    fun probe() {
        withDisplay { Unit }
    }

    override fun listWindows(): List<X11WindowDescriptor> = withDisplay { display ->
        withXErrorTrap(display) {
            val root = x11.XDefaultRootWindow(display)
            clientWindowIds(display, root)
                .mapNotNull { windowId -> descriptor(display, root, windowId, includeUnmapped = false) }
                .distinctBy(X11WindowDescriptor::windowId)
                .sortedBy { it.title.lowercase() }
        }
    }

    override fun findWindow(windowId: Long): X11WindowDescriptor? = withDisplay { display ->
        withXErrorTrap(display) {
            val root = x11.XDefaultRootWindow(display)
            descriptor(display, root, X11.Window(windowId), includeUnmapped = true)
        }
    }

    override fun captureWindow(windowId: Long): X11CapturedFrame = withDisplay { display ->
        withXErrorTrap(display) {
            val root = x11.XDefaultRootWindow(display)
            val window = X11.Window(windowId)
            val descriptor = descriptor(display, root, window, includeUnmapped = true)
                ?: throw X11CaptureFailure("The selected X11 window no longer exists.")
            if (descriptor.minimized) {
                throw X11CaptureFailure("The selected X11 window is not viewable.")
            }
            val imagePointer = imageLibrary.XGetImage(
                display = display,
                drawable = window,
                x = 0,
                y = 0,
                width = descriptor.bounds.width,
                height = descriptor.bounds.height,
                planeMask = NativeLong(-1L),
                format = Z_PIXMAP,
            ) ?: throw X11CaptureFailure("XGetImage returned no image for the selected window.")
            try {
                val image = NativeXImage(imagePointer).apply { read() }
                X11CapturedFrame(
                    bounds = descriptor.bounds.copy(width = image.width, height = image.height),
                    rgbaPixels = image.toRgba(),
                )
            } finally {
                x11.XDestroyImage(X11.XImage().apply { pointer = imagePointer })
            }
        }
    }

    override fun cursorPosition(): X11Point? = withDisplay { display ->
        withXErrorTrap(display) {
            val root = x11.XDefaultRootWindow(display)
            val rootReturn = X11.WindowByReference()
            val childReturn = X11.WindowByReference()
            val rootX = IntByReference()
            val rootY = IntByReference()
            val windowX = IntByReference()
            val windowY = IntByReference()
            val mask = IntByReference()
            if (
                x11.XQueryPointer(
                    display,
                    root,
                    rootReturn,
                    childReturn,
                    rootX,
                    rootY,
                    windowX,
                    windowY,
                    mask,
                )
            ) {
                X11Point(rootX.value, rootY.value)
            } else {
                null
            }
        }
    }

    override fun pressedInputs(): List<String> = withDisplay { display ->
        withXErrorTrap(display) {
            val keymap = ByteArray(32)
            x11.XQueryKeymap(display, keymap)
            buildList<String> {
                X11_INPUTS
                    .filter { input -> input.keySymbols.any { keySymbol -> isKeyPressed(display, keymap, keySymbol) } }
                    .mapTo(this, X11Input::label)
                val pointerMask = pointerMask(display)
                if (pointerMask and X11.Button1Mask != 0) add("LMB")
                if (pointerMask and X11.Button3Mask != 0) add("RMB")
                if (pointerMask and X11.Button2Mask != 0) add("MMB")
                if (pointerMask and X11.Button4Mask != 0) add("Mouse 4")
                if (pointerMask and X11.Button5Mask != 0) add("Mouse 5")
            }
        }
    }

    private fun isKeyPressed(display: X11.Display, keymap: ByteArray, keySymbol: Long): Boolean {
        val keyCode = keyCodeCache.getOrPut(keySymbol) {
            x11.XKeysymToKeycode(display, X11.KeySym(keySymbol)).toInt() and 0xff
        }
        if (keyCode == 0) return false
        val byteIndex = keyCode ushr 3
        val bitMask = 1 shl (keyCode and 7)
        return keymap[byteIndex].toInt() and bitMask != 0
    }

    private fun pointerMask(display: X11.Display): Int {
        val root = x11.XDefaultRootWindow(display)
        val mask = IntByReference()
        x11.XQueryPointer(
            display,
            root,
            X11.WindowByReference(),
            X11.WindowByReference(),
            IntByReference(),
            IntByReference(),
            IntByReference(),
            IntByReference(),
            mask,
        )
        return mask.value
    }

    private fun descriptor(
        display: X11.Display,
        root: X11.Window,
        window: X11.Window,
        includeUnmapped: Boolean,
    ): X11WindowDescriptor? {
        val attributes = X11.XWindowAttributes()
        if (x11.XGetWindowAttributes(display, window, attributes) == 0) return null
        attributes.read()
        val minimized = attributes.map_state != X11.IsViewable
        if ((!includeUnmapped && minimized) || attributes.override_redirect || attributes.width <= 1 || attributes.height <= 1) {
            return null
        }
        val title = windowTitle(display, window)?.trim()?.takeIf(String::isNotBlank) ?: return null
        val coordinates = translatedCoordinates(display, window, root) ?: return null
        return X11WindowDescriptor(
            windowId = window.toLong(),
            processId = propertyLongs(display, window, "_NET_WM_PID").firstOrNull()?.takeIf { it > 0 },
            title = title,
            processName = windowClass(display, window),
            bounds = X11WindowBounds(
                x = coordinates.x,
                y = coordinates.y,
                width = attributes.width,
                height = attributes.height,
            ),
            minimized = minimized,
        )
    }

    private fun clientWindowIds(display: X11.Display, root: X11.Window): List<X11.Window> {
        val ewmhIds = propertyLongs(display, root, "_NET_CLIENT_LIST")
            .map { windowId -> X11.Window(windowId) }
        return ewmhIds.ifEmpty { queryChildren(display, root) }
    }

    private fun queryChildren(display: X11.Display, root: X11.Window): List<X11.Window> {
        val rootReturn = X11.WindowByReference()
        val parentReturn = X11.WindowByReference()
        val childrenReturn = PointerByReference()
        val childCount = IntByReference()
        if (x11.XQueryTree(display, root, rootReturn, parentReturn, childrenReturn, childCount) == 0) {
            return emptyList()
        }
        val pointer = childrenReturn.value ?: return emptyList()
        return try {
            List(childCount.value.coerceAtLeast(0)) { index ->
                X11.Window(pointer.getNativeLong(index.toLong() * NativeLong.SIZE).toLong())
            }
        } finally {
            x11.XFree(pointer)
        }
    }

    private fun translatedCoordinates(display: X11.Display, window: X11.Window, root: X11.Window): X11Point? {
        val x = IntByReference()
        val y = IntByReference()
        return if (x11.XTranslateCoordinates(display, window, root, 0, 0, x, y, X11.WindowByReference())) {
            X11Point(x.value, y.value)
        } else {
            null
        }
    }

    private fun windowTitle(display: X11.Display, window: X11.Window): String? =
        propertyBytes(display, window, "_NET_WM_NAME")?.decodeToString()
            ?: run {
                val name = PointerByReference()
                if (x11.XFetchName(display, window, name) == 0) return@run null
                val pointer = name.value ?: return@run null
                try {
                    pointer.getString(0)
                } finally {
                    x11.XFree(pointer)
                }
            }

    private fun windowClass(display: X11.Display, window: X11.Window): String? =
        propertyBytes(display, window, "WM_CLASS")
            ?.decodeToString()
            ?.split('\u0000')
            ?.filter(String::isNotBlank)
            ?.lastOrNull()

    private fun propertyBytes(display: X11.Display, window: X11.Window, propertyName: String): ByteArray? =
        readProperty(display, window, propertyName) { pointer, format, itemCount ->
            if (format != 8 || itemCount <= 0) null else pointer.getByteArray(0, itemCount.toInt())
        }

    private fun propertyLongs(display: X11.Display, window: X11.Window, propertyName: String): List<Long> =
        readProperty(display, window, propertyName) { pointer, format, itemCount ->
            if (format != 32 || itemCount <= 0) {
                emptyList()
            } else {
                List(itemCount.toInt().coerceAtMost(MAX_PROPERTY_ITEMS)) { index ->
                    pointer.getNativeLong(index.toLong() * NativeLong.SIZE).toLong() and 0xffff_ffffL
                }
            }
        } ?: emptyList()

    private fun <T> readProperty(
        display: X11.Display,
        window: X11.Window,
        propertyName: String,
        consume: (Pointer, format: Int, itemCount: Long) -> T?,
    ): T? {
        val property = x11.XInternAtom(display, propertyName, true)
        if (property == X11.Atom.None) return null
        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val itemCount = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val value = PointerByReference()
        val status = x11.XGetWindowProperty(
            display,
            window,
            property,
            NativeLong(0),
            NativeLong(MAX_PROPERTY_ITEMS.toLong()),
            false,
            X11.Atom(X11.AnyPropertyType.toLong()),
            actualType,
            actualFormat,
            itemCount,
            bytesAfter,
            value,
        )
        if (status != X11.Success) return null
        val pointer = value.value ?: return null
        return try {
            consume(pointer, actualFormat.value, itemCount.value.toLong())
        } finally {
            x11.XFree(pointer)
        }
    }

    private fun <T> withDisplay(block: (X11.Display) -> T): T {
        val display = x11.XOpenDisplay(null) ?: throw X11CaptureFailure("Cannot open the current X11 display.")
        return try {
            block(display)
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    private fun <T> withXErrorTrap(display: X11.Display, block: () -> T): T = synchronized(X11_ERROR_LOCK) {
        x11.XSync(display, false)
        val errorCode = AtomicInteger(0)
        val handler = X11.XErrorHandler { _, event ->
            errorCode.compareAndSet(0, event.error_code.toInt() and 0xff)
            0
        }
        val previous = x11.XSetErrorHandler(handler)
        val result = try {
            runCatching(block)
        } finally {
            x11.XSync(display, false)
            x11.XSetErrorHandler(previous)
        }
        if (errorCode.get() != 0) {
            throw X11CaptureFailure("X11 request failed with error code ${errorCode.get()}.")
        }
        result.getOrThrow()
    }
}

@Structure.FieldOrder(
    "width",
    "height",
    "xoffset",
    "format",
    "data",
    "byteOrder",
    "bitmapUnit",
    "bitmapBitOrder",
    "bitmapPad",
    "depth",
    "bytesPerLine",
    "bitsPerPixel",
    "redMask",
    "greenMask",
    "blueMask",
    "obdata",
    "functions",
)
internal class NativeXImage(pointer: Pointer) : Structure(pointer) {
    @JvmField var width: Int = 0
    @JvmField var height: Int = 0
    @JvmField var xoffset: Int = 0
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
    @JvmField var byteOrder: Int = 0
    @JvmField var bitmapUnit: Int = 0
    @JvmField var bitmapBitOrder: Int = 0
    @JvmField var bitmapPad: Int = 0
    @JvmField var depth: Int = 0
    @JvmField var bytesPerLine: Int = 0
    @JvmField var bitsPerPixel: Int = 0
    @JvmField var redMask: NativeLong = NativeLong(0)
    @JvmField var greenMask: NativeLong = NativeLong(0)
    @JvmField var blueMask: NativeLong = NativeLong(0)
    @JvmField var obdata: Pointer? = null
    @JvmField var functions: NativeXImageFunctions = NativeXImageFunctions()

    fun toRgba(): ByteArray {
        val bytesPerPixel = bitsPerPixel / Byte.SIZE_BITS
        val byteCount = bytesPerLine.toLong() * height
        val outputByteCount = width.toLong() * height * RGBA_CHANNELS
        val imageData = data ?: throw X11CaptureFailure("XImage contains no pixel data.")
        if (
            width <= 0 || height <= 0 || bytesPerPixel !in 2..4 ||
            bytesPerLine.toLong() < width.toLong() * bytesPerPixel ||
            byteCount <= 0 || byteCount > Int.MAX_VALUE || outputByteCount > Int.MAX_VALUE
        ) {
            throw X11CaptureFailure("Unsupported XImage layout: ${width}x$height, $bitsPerPixel bpp.")
        }
        val source = imageData.getByteArray(0, byteCount.toInt())
        val masks = listOf(redMask.toLong(), greenMask.toLong(), blueMask.toLong())
        if (masks.any { it == 0L }) throw X11CaptureFailure("XImage does not expose RGB colour masks.")
        return packedXImageToRgba(
            source = source,
            width = width,
            height = height,
            bytesPerLine = bytesPerLine,
            bitsPerPixel = bitsPerPixel,
            mostSignificantByteFirst = byteOrder == X11.MSBFirst,
            redMask = redMask.toLong(),
            greenMask = greenMask.toLong(),
            blueMask = blueMask.toLong(),
        )
    }
}

@Structure.FieldOrder("createImage", "destroyImage", "getPixel", "putPixel", "subImage", "addPixel")
internal class NativeXImageFunctions : Structure() {
    @JvmField var createImage: Pointer? = null
    @JvmField var destroyImage: Pointer? = null
    @JvmField var getPixel: Pointer? = null
    @JvmField var putPixel: Pointer? = null
    @JvmField var subImage: Pointer? = null
    @JvmField var addPixel: Pointer? = null
}

internal interface X11ImageLibrary : Library {
    fun XGetImage(
        display: X11.Display,
        drawable: X11.Drawable,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        planeMask: NativeLong,
        format: Int,
    ): Pointer?

    companion object {
        val INSTANCE: X11ImageLibrary by lazy { Native.load("X11", X11ImageLibrary::class.java) }
    }
}

internal fun packedXImageToRgba(
    source: ByteArray,
    width: Int,
    height: Int,
    bytesPerLine: Int,
    bitsPerPixel: Int,
    mostSignificantByteFirst: Boolean,
    redMask: Long,
    greenMask: Long,
    blueMask: Long,
): ByteArray {
    val bytesPerPixel = bitsPerPixel / Byte.SIZE_BITS
    val requiredSourceBytes = bytesPerLine.toLong() * height
    val outputBytes = width.toLong() * height * RGBA_CHANNELS
    require(width > 0 && height > 0 && bytesPerPixel in 2..4)
    require(bytesPerLine.toLong() >= width.toLong() * bytesPerPixel)
    require(requiredSourceBytes <= source.size && outputBytes <= Int.MAX_VALUE)
    require(redMask != 0L && greenMask != 0L && blueMask != 0L)
    val output = ByteArray(outputBytes.toInt())
    for (y in 0 until height) {
        for (x in 0 until width) {
            val sourceOffset = y * bytesPerLine + x * bytesPerPixel
            val pixel = source.readPixel(sourceOffset, bytesPerPixel, mostSignificantByteFirst)
            val targetOffset = (y * width + x) * RGBA_CHANNELS
            output[targetOffset] = pixel.channel(redMask).toByte()
            output[targetOffset + 1] = pixel.channel(greenMask).toByte()
            output[targetOffset + 2] = pixel.channel(blueMask).toByte()
            output[targetOffset + 3] = 0xff.toByte()
        }
    }
    return output
}

private fun ByteArray.readPixel(offset: Int, byteCount: Int, mostSignificantByteFirst: Boolean): Long {
    var result = 0L
    repeat(byteCount) { index ->
        val sourceIndex = if (mostSignificantByteFirst) offset + index else offset + byteCount - index - 1
        result = result shl Byte.SIZE_BITS or (this[sourceIndex].toLong() and 0xff)
    }
    return result
}

private fun Long.channel(mask: Long): Int {
    val shift = java.lang.Long.numberOfTrailingZeros(mask)
    val maximum = mask ushr shift
    return (((this and mask) ushr shift) * 255L / maximum).toInt().coerceIn(0, 255)
}

private data class X11Input(
    val label: String,
    val keySymbols: LongArray,
)

private val X11_INPUTS: List<X11Input> = buildList {
    add(X11Input("Ctrl", longArrayOf(0xffe3, 0xffe4)))
    add(X11Input("Shift", longArrayOf(0xffe1, 0xffe2)))
    add(X11Input("Alt", longArrayOf(0xffe9, 0xffea)))
    add(X11Input("Super", longArrayOf(0xffeb, 0xffec)))
    addAll(('a'..'z').map { key -> X11Input(key.uppercase(), longArrayOf(key.code.toLong())) })
    addAll(('0'..'9').map { key -> X11Input(key.toString(), longArrayOf(key.code.toLong())) })
    addAll((0xffbeL..0xffc9L).mapIndexed { index, key -> X11Input("F${index + 1}", longArrayOf(key)) })
    add(X11Input("Esc", longArrayOf(0xff1b)))
    add(X11Input("Tab", longArrayOf(0xff09)))
    add(X11Input("Enter", longArrayOf(0xff0d)))
    add(X11Input("Space", longArrayOf(0x20)))
    add(X11Input("Backspace", longArrayOf(0xff08)))
    add(X11Input("Delete", longArrayOf(0xffff)))
    add(X11Input("Insert", longArrayOf(0xff63)))
    add(X11Input("Home", longArrayOf(0xff50)))
    add(X11Input("End", longArrayOf(0xff57)))
    add(X11Input("PgUp", longArrayOf(0xff55)))
    add(X11Input("PgDn", longArrayOf(0xff56)))
    add(X11Input("←", longArrayOf(0xff51)))
    add(X11Input("↑", longArrayOf(0xff52)))
    add(X11Input("→", longArrayOf(0xff53)))
    add(X11Input("↓", longArrayOf(0xff54)))
    "-=[]\\;',./`".forEach { key -> add(X11Input(key.toString(), longArrayOf(key.code.toLong()))) }
}

private val X11_ERROR_LOCK = Any()
private const val Z_PIXMAP = 2
private const val MAX_PROPERTY_ITEMS = 16_384
private const val RGBA_CHANNELS = 4
