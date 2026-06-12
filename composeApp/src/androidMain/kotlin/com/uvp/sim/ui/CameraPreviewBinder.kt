package com.uvp.sim.ui

import androidx.camera.view.PreviewView

/**
 * Process-wide bridge between the Activity-owned camera streamer and the
 * commonMain Compose UI. The Activity registers a binder once it has built
 * an [com.uvp.sim.camera.AndroidCameraStreamer]; the Compose layer calls
 * [attach] / [detach] from a `DisposableEffect` around the PreviewView.
 *
 * Single-Activity assumption holds for this app — there's exactly one
 * MainActivity and at most one streamer alive at a time.
 */
object CameraPreviewBinder {
    @Volatile
    private var binder: ((PreviewView?) -> Unit)? = null

    fun setBinder(b: ((PreviewView?) -> Unit)?) {
        binder = b
    }

    fun attach(view: PreviewView) {
        binder?.invoke(view)
    }

    fun detach() {
        binder?.invoke(null)
    }
}
