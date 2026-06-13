package com.uvp.sim.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun RecordingThumbnail(
    filePath: String?,
    modifier: Modifier,
    onMissing: @Composable () -> Unit
) {
    var bitmap by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(filePath) { mutableStateOf(true) }

    LaunchedEffect(filePath) {
        bitmap = null
        loading = true
        if (filePath.isNullOrEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(filePath)
                if (!f.exists() || f.length() == 0L) return@runCatching null
                BitmapFactory.decodeFile(filePath)?.asImageBitmap()
            }.getOrNull()
        }
        bitmap = loaded
        loading = false
    }

    val bm = bitmap
    if (bm != null) {
        Image(
            bitmap = bm,
            contentDescription = "录像缩略图",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else if (!loading) {
        onMissing()
    } else {
        // 加载中 — 显示占位避免闪烁
        onMissing()
    }
}
