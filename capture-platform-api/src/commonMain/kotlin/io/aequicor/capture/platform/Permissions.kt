package io.aequicor.capture.platform

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.RecordingSettings

enum class CapturePermission {
    ScreenRecording,
    Microphone,
    SystemAudio,
}

interface PermissionGateway {
    suspend fun check(required: Set<CapturePermission>): PermissionReport
    suspend fun request(required: Set<CapturePermission>): PermissionReport
}

data object GrantedPermissionGateway : PermissionGateway {
    override suspend fun check(required: Set<CapturePermission>): PermissionReport =
        PermissionReport(required.associateWith { PermissionStatus.Granted })

    override suspend fun request(required: Set<CapturePermission>): PermissionReport = check(required)
}

data class PermissionReport(
    val statuses: Map<CapturePermission, PermissionStatus>,
) {
    val allGranted: Boolean
        get() = statuses.values.all { it is PermissionStatus.Granted }

    fun status(permission: CapturePermission): PermissionStatus =
        statuses[permission] ?: PermissionStatus.Denied("Permission status was not reported.")

    fun allGrantedFor(required: Set<CapturePermission>): Boolean =
        required.all { permission -> status(permission) is PermissionStatus.Granted }
}

sealed interface PermissionStatus {
    data object Granted : PermissionStatus
    data class Denied(val reason: String) : PermissionStatus
    data class RequiresUserAction(
        val instructions: String,
        val action: PermissionUserAction = PermissionUserAction.OpenSettings,
        val restartMayBeRequired: Boolean = false,
    ) : PermissionStatus
    data class Unsupported(val reason: String) : PermissionStatus
}

enum class PermissionUserAction {
    Request,
    OpenSettings,
}

sealed interface PermissionAuthorization {
    val required: Set<CapturePermission>
    val report: PermissionReport

    data class Granted(
        override val required: Set<CapturePermission>,
        override val report: PermissionReport,
    ) : PermissionAuthorization

    data class Rejected(
        override val required: Set<CapturePermission>,
        override val report: PermissionReport,
    ) : PermissionAuthorization
}

fun RecordingSettings.requiredPermissions(): Set<CapturePermission> = buildSet {
    add(CapturePermission.ScreenRecording)
    if (audioSources.any { source -> source is AudioSource.Microphone }) {
        add(CapturePermission.Microphone)
    }
    if (audioSources.any { source -> source is AudioSource.SystemLoopback }) {
        add(CapturePermission.SystemAudio)
    }
}

suspend fun PermissionGateway.authorize(settings: RecordingSettings): PermissionAuthorization {
    val preflight = preflight(settings)
    if (preflight is PermissionAuthorization.Granted) {
        return preflight
    }
    val required = preflight.required
    val requestable = required.firstOrNull { permission ->
        val status = preflight.report.status(permission)
        status is PermissionStatus.RequiresUserAction && status.action == PermissionUserAction.Request
    } ?: return preflight
    val requested = request(setOf(requestable))
    val finalReport = PermissionReport(
        statuses = required.associateWith { permission ->
            if (permission == requestable) requested.status(permission) else preflight.report.status(permission)
        },
    )
    return finalReport.toAuthorization(required)
}

suspend fun PermissionGateway.preflight(settings: RecordingSettings): PermissionAuthorization {
    val required = settings.requiredPermissions()
    return check(required).toAuthorization(required)
}

private fun PermissionReport.toAuthorization(required: Set<CapturePermission>): PermissionAuthorization =
    if (allGrantedFor(required)) {
        PermissionAuthorization.Granted(required, this)
    } else {
        PermissionAuthorization.Rejected(required, this)
    }

fun PermissionAuthorization.Rejected.message(): String = required
    .mapNotNull { permission ->
        when (val status = report.status(permission)) {
            PermissionStatus.Granted -> null
            is PermissionStatus.Denied -> "${permission.displayName()}: ${status.reason}"
            is PermissionStatus.RequiresUserAction -> "${permission.displayName()}: ${status.instructions}"
            is PermissionStatus.Unsupported -> "${permission.displayName()}: ${status.reason}"
        }
    }
    .joinToString(prefix = "Required permissions were not granted: ", separator = "; ")

private fun CapturePermission.displayName(): String = when (this) {
    CapturePermission.ScreenRecording -> "screen recording"
    CapturePermission.Microphone -> "microphone"
    CapturePermission.SystemAudio -> "system audio"
}
