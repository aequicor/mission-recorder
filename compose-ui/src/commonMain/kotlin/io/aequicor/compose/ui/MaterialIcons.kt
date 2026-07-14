package io.aequicor.compose.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.aequicor.compose.resources.Res
import io.aequicor.compose.resources.add
import io.aequicor.compose.resources.add_circle
import io.aequicor.compose.resources.apps
import io.aequicor.compose.resources.arrow_back
import io.aequicor.compose.resources.close
import io.aequicor.compose.resources.content_copy
import io.aequicor.compose.resources.content_cut
import io.aequicor.compose.resources.crop_free
import io.aequicor.compose.resources.download
import io.aequicor.compose.resources.edit
import io.aequicor.compose.resources.expand_more
import io.aequicor.compose.resources.fiber_manual_record
import io.aequicor.compose.resources.filter_none
import io.aequicor.compose.resources.folder
import io.aequicor.compose.resources.folder_open
import io.aequicor.compose.resources.forward_10
import io.aequicor.compose.resources.mic
import io.aequicor.compose.resources.mic_off
import io.aequicor.compose.resources.monitor
import io.aequicor.compose.resources.pause
import io.aequicor.compose.resources.play_arrow
import io.aequicor.compose.resources.redo
import io.aequicor.compose.resources.refresh
import io.aequicor.compose.resources.remove
import io.aequicor.compose.resources.replay_10
import io.aequicor.compose.resources.screenshot_monitor
import io.aequicor.compose.resources.skip_next
import io.aequicor.compose.resources.skip_previous
import io.aequicor.compose.resources.star
import io.aequicor.compose.resources.stop
import io.aequicor.compose.resources.undo
import io.aequicor.compose.resources.volume_off
import io.aequicor.compose.resources.volume_up
import io.aequicor.compose.resources.window
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun MaterialIcon(
    icon: DrawableResource,
    description: String?,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = description,
        modifier = modifier.size(size),
        tint = color,
    )
}

internal object MaterialIcons {
    val Add: DrawableResource = Res.drawable.add
    val AddCircle: DrawableResource = Res.drawable.add_circle
    val Apps: DrawableResource = Res.drawable.apps
    val ArrowBack: DrawableResource = Res.drawable.arrow_back
    val Capture: DrawableResource = Res.drawable.screenshot_monitor
    val Close: DrawableResource = Res.drawable.close
    val ContentCopy: DrawableResource = Res.drawable.content_copy
    val ContentCut: DrawableResource = Res.drawable.content_cut
    val Crop: DrawableResource = Res.drawable.crop_free
    val Download: DrawableResource = Res.drawable.download
    val Edit: DrawableResource = Res.drawable.edit
    val ExpandMore: DrawableResource = Res.drawable.expand_more
    val FilterNone: DrawableResource = Res.drawable.filter_none
    val Folder: DrawableResource = Res.drawable.folder
    val FolderOpen: DrawableResource = Res.drawable.folder_open
    val ForwardTen: DrawableResource = Res.drawable.forward_10
    val Mic: DrawableResource = Res.drawable.mic
    val MicOff: DrawableResource = Res.drawable.mic_off
    val Monitor: DrawableResource = Res.drawable.monitor
    val Pause: DrawableResource = Res.drawable.pause
    val Play: DrawableResource = Res.drawable.play_arrow
    val Record: DrawableResource = Res.drawable.fiber_manual_record
    val Redo: DrawableResource = Res.drawable.redo
    val Refresh: DrawableResource = Res.drawable.refresh
    val Remove: DrawableResource = Res.drawable.remove
    val ReplayTen: DrawableResource = Res.drawable.replay_10
    val SkipNext: DrawableResource = Res.drawable.skip_next
    val SkipPrevious: DrawableResource = Res.drawable.skip_previous
    val Star: DrawableResource = Res.drawable.star
    val Stop: DrawableResource = Res.drawable.stop
    val Undo: DrawableResource = Res.drawable.undo
    val VolumeOff: DrawableResource = Res.drawable.volume_off
    val VolumeUp: DrawableResource = Res.drawable.volume_up
    val Window: DrawableResource = Res.drawable.window
}
