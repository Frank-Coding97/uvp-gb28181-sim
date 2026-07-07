package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVKit.AVPlayerViewController
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun RecordingPlayerDialog(filePath: String?, onDismiss: () -> Unit) {
    if (filePath.isNullOrBlank()) return

    val player: AVPlayer = remember(filePath) {
        AVPlayer(uRL = NSURL(fileURLWithPath = filePath))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            UIKitView(
                factory = {
                    RecordingPlayerContainerView().apply {
                        setPlayer(player)
                    }
                },
                update = {
                    it.setPlayer(player)
                },
                onRelease = {
                    it.setPlayer(null)
                },
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fileNameWithoutExtension(filePath),
                    fontSize = 14.sp,
                    color = Color.White
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class RecordingPlayerContainerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val playerController = AVPlayerViewController().apply {
        showsPlaybackControls = true
        view.backgroundColor = UIColor.blackColor
    }

    init {
        backgroundColor = UIColor.blackColor
        addSubview(playerController.view)
    }

    fun setPlayer(player: AVPlayer?) {
        playerController.player = player
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerController.view.setFrame(bounds)
    }
}

private fun fileNameWithoutExtension(path: String): String {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}
