package io.aequicor.capture.macos.coregraphics

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.aequicor.capture.platform.CapturePermission
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.PermissionReport
import io.aequicor.capture.platform.PermissionStatus
import io.aequicor.capture.platform.PermissionUserAction

internal interface MacScreenPermissionApi {
    fun isGranted(): Boolean
    fun request(): Boolean
}

internal object JnaMacScreenPermissionApi : MacScreenPermissionApi {
    override fun isGranted(): Boolean = MacCoreGraphicsImageApi.INSTANCE.CGPreflightScreenCaptureAccess() != 0
    override fun request(): Boolean = MacCoreGraphicsImageApi.INSTANCE.CGRequestScreenCaptureAccess() != 0
}

internal enum class MacMicrophoneAuthorization { NotDetermined, Restricted, Denied, Authorized, Unknown }

internal fun interface MacMicrophonePermissionApi {
    fun authorization(): MacMicrophoneAuthorization
}

internal object JnaMacMicrophonePermissionApi : MacMicrophonePermissionApi {
    override fun authorization(): MacMicrophoneAuthorization = runCatching {
        NativeLibrary.getInstance("AVFoundation")
        val stringClass = requireNotNull(ObjCStringLibrary.INSTANCE.objc_getClass("NSString"))
        val stringSelector = ObjCStringLibrary.INSTANCE.sel_registerName("stringWithUTF8String:")
        val audioMediaType = requireNotNull(
            ObjCStringLibrary.INSTANCE.objc_msgSend(stringClass, stringSelector, "soun"),
        )
        val captureDevice = requireNotNull(ObjCStringLibrary.INSTANCE.objc_getClass("AVCaptureDevice"))
        val statusSelector = ObjCStringLibrary.INSTANCE.sel_registerName("authorizationStatusForMediaType:")
        when (ObjCIntegerLibrary.INSTANCE.objc_msgSend(captureDevice, statusSelector, audioMediaType).toInt()) {
            0 -> MacMicrophoneAuthorization.NotDetermined
            1 -> MacMicrophoneAuthorization.Restricted
            2 -> MacMicrophoneAuthorization.Denied
            3 -> MacMicrophoneAuthorization.Authorized
            else -> MacMicrophoneAuthorization.Unknown
        }
    }.getOrDefault(MacMicrophoneAuthorization.Unknown)
}

internal class MacOsPermissionGateway(
    private val screen: MacScreenPermissionApi,
    private val microphone: MacMicrophonePermissionApi,
) : PermissionGateway {
    override suspend fun check(required: Set<CapturePermission>): PermissionReport =
        PermissionReport(required.associateWith { status(it, request = false) })

    override suspend fun request(required: Set<CapturePermission>): PermissionReport =
        PermissionReport(required.associateWith { status(it, request = true) })

    private fun status(permission: CapturePermission, request: Boolean): PermissionStatus = when (permission) {
        CapturePermission.ScreenRecording -> if (screen.isGranted() || request && screen.request()) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.RequiresUserAction(
                "Allow Mission Recorder in System Settings > Privacy & Security > Screen Recording, then retry.",
                action = if (request) PermissionUserAction.OpenSettings else PermissionUserAction.Request,
                restartMayBeRequired = request,
            )
        }
        CapturePermission.Microphone -> when (microphone.authorization()) {
            MacMicrophoneAuthorization.Authorized -> PermissionStatus.Granted
            MacMicrophoneAuthorization.NotDetermined -> if (request) {
                PermissionStatus.Granted
            } else {
                PermissionStatus.RequiresUserAction(
                    instructions = "Continue to let macOS ask for microphone access when recording starts.",
                    action = PermissionUserAction.Request,
                )
            }
            MacMicrophoneAuthorization.Restricted,
            MacMicrophoneAuthorization.Denied -> PermissionStatus.RequiresUserAction(
                "Allow Mission Recorder in System Settings > Privacy & Security > Microphone, then retry.",
            )
            MacMicrophoneAuthorization.Unknown -> PermissionStatus.Unsupported(
                "Could not determine macOS microphone authorization status.",
            )
        }
        CapturePermission.SystemAudio -> PermissionStatus.Unsupported(
            "The current macOS backend does not support system-audio capture.",
        )
    }
}

private interface ObjCStringLibrary : Library {
    fun objc_getClass(name: String): Pointer?
    fun sel_registerName(name: String): Pointer
    fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: String): Pointer?

    companion object {
        val INSTANCE: ObjCStringLibrary by lazy { Native.load("objc", ObjCStringLibrary::class.java) }
    }
}

private interface ObjCIntegerLibrary : Library {
    fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Pointer): Long

    companion object {
        val INSTANCE: ObjCIntegerLibrary by lazy { Native.load("objc", ObjCIntegerLibrary::class.java) }
    }
}
