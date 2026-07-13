package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun RecordingThumbnail(
    filePath: String?,
    modifier: Modifier,
    onMissing: @Composable () -> Unit
) {
    val image = remember(filePath) {
        if (filePath.isNullOrBlank()) {
            null
        } else if (!NSFileManager.defaultManager.fileExistsAtPath(filePath)) {
            null
        } else {
            UIImage.imageWithContentsOfFile(filePath)
        }
    }

    if (image == null) {
        onMissing()
        return
    }

    UIKitView(
        factory = {
            UIImageView().apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                clipsToBounds = true
                this.image = image
            }
        },
        update = {
            it.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
            it.clipsToBounds = true
            it.image = image
        },
        modifier = modifier,
    )
}
