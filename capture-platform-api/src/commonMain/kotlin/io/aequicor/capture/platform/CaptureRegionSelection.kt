package io.aequicor.capture.platform

import io.aequicor.capture.core.CaptureRegion

fun interface CaptureRegionSelector {
    suspend fun selectRegion(): CaptureRegionSelection
}

sealed interface CaptureRegionSelection {
    data class Selected(val region: CaptureRegion) : CaptureRegionSelection
    data object Cancelled : CaptureRegionSelection
    data class Unavailable(val message: String) : CaptureRegionSelection
}

data object UnavailableCaptureRegionSelector : CaptureRegionSelector {
    override suspend fun selectRegion(): CaptureRegionSelection =
        CaptureRegionSelection.Unavailable("Interactive region selection is unavailable on this platform.")
}
