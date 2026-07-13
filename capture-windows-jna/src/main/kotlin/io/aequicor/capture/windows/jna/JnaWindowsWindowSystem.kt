package io.aequicor.capture.windows.jna

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HBITMAP
import com.sun.jna.platform.win32.WinDef.HDC
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Path

internal class JnaWindowsWindowSystem(
    private val excludedProcessId: Long = ProcessHandle.current().pid(),
    private val user32: User32 = User32.INSTANCE,
    private val gdi32: GDI32 = GDI32.INSTANCE,
    private val extendedUser32: ExtendedUser32 = ExtendedUser32.INSTANCE,
    private val dwmApi: DwmApi? = DwmApi.loadOrNull(),
) : WindowsWindowSystem {
    override fun listWindows(): List<WindowsWindowDescriptor> {
        val windows = mutableListOf<WindowsWindowDescriptor>()
        val callback = WNDENUMPROC { handle, _ ->
            describeUserWindow(handle)?.let(windows::add)
            true
        }
        if (!user32.EnumWindows(callback, null)) {
            throw WindowsCaptureFailure("EnumWindows failed with Win32 error ${Native.getLastError()}.")
        }
        return windows.sortedWith(
            compareBy<WindowsWindowDescriptor> { window -> window.processName.orEmpty().lowercase() }
                .thenBy { window -> window.title.lowercase() },
        )
    }

    override fun findWindow(handle: Long): WindowsWindowDescriptor? =
        describeUserWindow(HWND(Pointer(handle)))

    override fun captureWindow(handle: Long): WindowsCapturedFrame {
        val windowHandle = HWND(Pointer(handle))
        if (!user32.IsWindow(windowHandle)) {
            throw WindowsCaptureFailure("The window was closed.")
        }
        val bounds = readBounds(windowHandle)
            ?: throw WindowsCaptureFailure("The window bounds are unavailable.")
        val byteCount = checkedFrameByteCount(bounds.width, bounds.height)

        var sourceDc: HDC? = null
        var memoryDc: HDC? = null
        var bitmap: HBITMAP? = null
        var previousObject: HANDLE? = null
        try {
            sourceDc = extendedUser32.GetWindowDC(windowHandle)
                .takeUnless(PointerType?::isNullHandle)
                ?: throw nativeFailure("GetWindowDC")
            memoryDc = gdi32.CreateCompatibleDC(sourceDc)
                .takeUnless(PointerType?::isNullHandle)
                ?: throw nativeFailure("CreateCompatibleDC")

            val bitmapInfo = topDownBitmapInfo(bounds.width, bounds.height, byteCount)
            val pixelsReference = PointerByReference()
            bitmap = gdi32.CreateDIBSection(
                sourceDc,
                bitmapInfo,
                WinGDI.DIB_RGB_COLORS,
                pixelsReference,
                null,
                0,
            ).takeUnless(PointerType?::isNullHandle)
                ?: throw nativeFailure("CreateDIBSection")
            previousObject = gdi32.SelectObject(memoryDc, bitmap)
                .takeUnless(PointerType?::isInvalidGdiHandle)
                ?: throw nativeFailure("SelectObject")

            val pixels = pixelsReference.value
                ?: throw WindowsCaptureFailure("CreateDIBSection returned no pixel buffer.")
            val printed = user32.PrintWindow(windowHandle, memoryDc, PW_RENDERFULLCONTENT)
            var frameBytes = pixels.getByteArray(0, byteCount)
            var copied = false
            if (!printed || frameBytes.all(Byte::isZero)) {
                copied = gdi32.BitBlt(
                    memoryDc,
                    0,
                    0,
                    bounds.width,
                    bounds.height,
                    sourceDc,
                    0,
                    0,
                    GDI32.SRCCOPY or CAPTUREBLT,
                )
                if (copied) {
                    frameBytes = pixels.getByteArray(0, byteCount)
                }
            }
            if (!printed && !copied) {
                throw nativeFailure("PrintWindow and BitBlt")
            }
            return WindowsCapturedFrame(bounds = bounds, bgraPixels = frameBytes)
        } finally {
            if (!memoryDc.isNullHandle() && !previousObject.isInvalidGdiHandle()) {
                runCatching { gdi32.SelectObject(memoryDc, previousObject) }
            }
            if (!bitmap.isNullHandle()) {
                runCatching { gdi32.DeleteObject(bitmap) }
            }
            if (!memoryDc.isNullHandle()) {
                runCatching { gdi32.DeleteDC(memoryDc) }
            }
            if (!sourceDc.isNullHandle()) {
                runCatching { user32.ReleaseDC(windowHandle, sourceDc) }
            }
        }
    }

    override fun openScreenCapture(bounds: WindowsWindowBounds, frameRate: Int): WindowsScreenCapture {
        val fallback = { JnaWindowsScreenCapture(bounds, user32, gdi32) }
        val accelerated = DdaWindowsScreenCapture.openOrNull(bounds, frameRate) ?: return fallback()
        return FailoverWindowsScreenCapture(accelerated, fallback)
    }

    override fun cursorPosition(): WindowsPoint? {
        val point = POINT()
        return if (user32.GetCursorPos(point)) WindowsPoint(point.x, point.y) else null
    }

    private fun describeUserWindow(handle: HWND): WindowsWindowDescriptor? {
        if (!user32.IsWindow(handle) || !user32.IsWindowVisible(handle) || isCloaked(handle)) {
            return null
        }
        val extendedStyle = user32.GetWindowLong(handle, GWL_EXSTYLE)
        if (extendedStyle and WS_EX_TOOLWINDOW != 0 && extendedStyle and WS_EX_APPWINDOW == 0) {
            return null
        }
        val title = readWindowTitle(handle).takeIf(String::isNotBlank) ?: return null
        val processIdReference = IntByReference()
        user32.GetWindowThreadProcessId(handle, processIdReference)
        val processId = processIdReference.value.toLong() and UINT32_MASK
        if (processId == 0L || processId == excludedProcessId) {
            return null
        }
        val bounds = readBounds(handle) ?: return null
        return WindowsWindowDescriptor(
            handle = Pointer.nativeValue(handle.pointer),
            processId = processId,
            title = title,
            processName = processName(processId),
            bounds = bounds,
            minimized = extendedUser32.IsIconic(handle),
        )
    }

    private fun readWindowTitle(handle: HWND): String {
        val length = user32.GetWindowTextLength(handle)
        if (length <= 0) {
            return ""
        }
        val buffer = CharArray(length + 1)
        val copied = user32.GetWindowText(handle, buffer, buffer.size)
        return if (copied > 0) Native.toString(buffer).trim() else ""
    }

    private fun readBounds(handle: HWND): WindowsWindowBounds? {
        val rectangle = RECT()
        if (!user32.GetWindowRect(handle, rectangle)) {
            return null
        }
        val width = rectangle.right.toLong() - rectangle.left
        val height = rectangle.bottom.toLong() - rectangle.top
        if (width <= 0 || height <= 0 || width > MAX_FRAME_DIMENSION || height > MAX_FRAME_DIMENSION) {
            return null
        }
        if (width * height * BGRA_CHANNEL_COUNT > Int.MAX_VALUE) {
            return null
        }
        return WindowsWindowBounds(
            x = rectangle.left,
            y = rectangle.top,
            width = width.toInt(),
            height = height.toInt(),
        )
    }

    private fun isCloaked(handle: HWND): Boolean {
        val api = dwmApi ?: return false
        val cloaked = IntByReference()
        return runCatching {
            api.DwmGetWindowAttribute(handle, DWMWA_CLOAKED, cloaked, Int.SIZE_BYTES) == S_OK && cloaked.value != 0
        }.getOrDefault(false)
    }
}

private class FailoverWindowsScreenCapture(
    private var current: WindowsScreenCapture,
    private val fallbackFactory: () -> WindowsScreenCapture,
) : WindowsScreenCapture {
    private var usingFallback = false

    override fun capture(): WindowsCapturedFrame = try {
        current.capture()
    } catch (failure: WindowsCaptureFailure) {
        if (usingFallback) {
            throw failure
        }
        current.close()
        current = fallbackFactory()
        usingFallback = true
        current.capture()
    }

    override fun close() = current.close()
}

private class JnaWindowsScreenCapture(
    private val bounds: WindowsWindowBounds,
    private val user32: User32,
    private val gdi32: GDI32,
) : WindowsScreenCapture {
    private val byteCount = checkedFrameByteCount(bounds.width, bounds.height)
    private val sourceDc = user32.GetDC(null)
        .takeUnless(PointerType?::isNullHandle)
        ?: throw nativeFailure("GetDC")
    private val memoryDc: HDC
    private val bitmap: HBITMAP
    private val previousObject: HANDLE
    private val pixels: Pointer
    private var closed = false

    init {
        var createdMemoryDc: HDC? = null
        var createdBitmap: HBITMAP? = null
        var selectedObject: HANDLE? = null
        try {
            createdMemoryDc = gdi32.CreateCompatibleDC(sourceDc)
                .takeUnless(PointerType?::isNullHandle)
                ?: throw nativeFailure("CreateCompatibleDC")
            val pixelsReference = PointerByReference()
            createdBitmap = gdi32.CreateDIBSection(
                sourceDc,
                topDownBitmapInfo(bounds.width, bounds.height, byteCount),
                WinGDI.DIB_RGB_COLORS,
                pixelsReference,
                null,
                0,
            ).takeUnless(PointerType?::isNullHandle)
                ?: throw nativeFailure("CreateDIBSection")
            selectedObject = gdi32.SelectObject(createdMemoryDc, createdBitmap)
                .takeUnless(PointerType?::isInvalidGdiHandle)
                ?: throw nativeFailure("SelectObject")
            memoryDc = createdMemoryDc
            bitmap = createdBitmap
            previousObject = selectedObject
            pixels = pixelsReference.value
                ?: throw WindowsCaptureFailure("CreateDIBSection returned no pixel buffer.")
        } catch (failure: Throwable) {
            if (!createdMemoryDc.isNullHandle() && !selectedObject.isInvalidGdiHandle()) {
                runCatching { gdi32.SelectObject(createdMemoryDc, selectedObject) }
            }
            if (!createdBitmap.isNullHandle()) {
                runCatching { gdi32.DeleteObject(createdBitmap) }
            }
            if (!createdMemoryDc.isNullHandle()) {
                runCatching { gdi32.DeleteDC(createdMemoryDc) }
            }
            runCatching { user32.ReleaseDC(null, sourceDc) }
            throw failure
        }
    }

    override fun capture(): WindowsCapturedFrame {
        check(!closed) { "Screen capture is closed." }
        if (!gdi32.BitBlt(
                memoryDc,
                0,
                0,
                bounds.width,
                bounds.height,
                sourceDc,
                bounds.x,
                bounds.y,
                GDI32.SRCCOPY or CAPTUREBLT,
            )
        ) {
            throw nativeFailure("BitBlt")
        }
        return WindowsCapturedFrame(bounds, pixels.getByteArray(0, byteCount))
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        runCatching { gdi32.SelectObject(memoryDc, previousObject) }
        runCatching { gdi32.DeleteObject(bitmap) }
        runCatching { gdi32.DeleteDC(memoryDc) }
        runCatching { user32.ReleaseDC(null, sourceDc) }
    }
}

internal interface ExtendedUser32 : StdCallLibrary {
    fun GetWindowDC(window: HWND): HDC?

    fun IsIconic(window: HWND): Boolean

    companion object {
        val INSTANCE: ExtendedUser32 = Native.load("user32", ExtendedUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

internal interface DwmApi : StdCallLibrary {
    fun DwmGetWindowAttribute(
        window: HWND,
        attribute: Int,
        value: IntByReference,
        valueSize: Int,
    ): Int

    companion object {
        fun loadOrNull(): DwmApi? = runCatching {
            Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }
}

private fun processName(processId: Long): String? = runCatching {
    val command = ProcessHandle.of(processId)
        .flatMap { process -> process.info().command() }
        .orElse(null)
        ?: return@runCatching null
    Path.of(command).fileName?.toString()
}.getOrNull()

private fun topDownBitmapInfo(width: Int, height: Int, byteCount: Int): BITMAPINFO = BITMAPINFO().apply {
    bmiHeader.biSize = bmiHeader.size()
    bmiHeader.biWidth = width
    bmiHeader.biHeight = -height
    bmiHeader.biPlanes = 1
    bmiHeader.biBitCount = 32
    bmiHeader.biCompression = WinGDI.BI_RGB
    bmiHeader.biSizeImage = byteCount
}

private fun checkedFrameByteCount(width: Int, height: Int): Int {
    val count = width.toLong() * height * BGRA_CHANNEL_COUNT
    if (count <= 0 || count > Int.MAX_VALUE) {
        throw WindowsCaptureFailure("The window is too large to capture: ${width}x$height.")
    }
    return count.toInt()
}

private fun nativeFailure(operation: String): WindowsCaptureFailure =
    WindowsCaptureFailure("$operation failed with Win32 error ${Native.getLastError()}.")

private fun PointerType?.isNullHandle(): Boolean =
    this == null || pointer == null || Pointer.nativeValue(pointer) == 0L

private fun PointerType?.isInvalidGdiHandle(): Boolean =
    isNullHandle() || Pointer.nativeValue(requireNotNull(this).pointer) == -1L

private fun Byte.isZero(): Boolean = this == 0.toByte()

private const val GWL_EXSTYLE = -20
private const val WS_EX_TOOLWINDOW = 0x00000080
private const val WS_EX_APPWINDOW = 0x00040000
private const val PW_RENDERFULLCONTENT = 0x00000002
private const val CAPTUREBLT = 0x40000000
private const val DWMWA_CLOAKED = 14
private const val S_OK = 0
private const val BGRA_CHANNEL_COUNT = 4L
private const val MAX_FRAME_DIMENSION = 32_768L
private const val UINT32_MASK = 0xffff_ffffL
