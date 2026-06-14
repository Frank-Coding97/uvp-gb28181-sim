package com.uvp.sim.ui

import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 进程级桥 — Activity 持有的 streamer 跟 commonMain Compose UI 解耦。
 *
 * 屏幕预览改 SurfaceView(P0-PREVIEW,2026-06-14)以接 OsdRendererHolder.setScreenSurface,
 * 让屏幕看到的画面跟直播/录像同源(都带 OSD)。
 */
object CameraPreviewBinder {
    @Volatile
    private var binder: ((SurfaceView?) -> Unit)? = null

    fun setBinder(b: ((SurfaceView?) -> Unit)?) {
        binder = b
    }

    fun attach(view: SurfaceView) {
        binder?.invoke(view)
    }

    fun detach() {
        binder?.invoke(null)
    }
}
