package com.uvp.sim.ui

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 端调起系统播放器打开本地 mp4:
 * 优先 `UIDocumentInteractionController.presentPreviewAnimated`(iOS 原生预览 QuickLook 风格),
 * 失败 / 拒绝预览 时兜底 `UIActivityViewController` 分享面板让用户"用 xxx 打开"。
 *
 * NSFileManager 校验文件存在;不存在 or 无法拿到顶层 VC or 预览拒绝均记 SystemLogger。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun openVideoExternally(filePath: String) {
    if (!NSFileManager.defaultManager.fileExistsAtPath(filePath)) {
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "录像文件不存在: $filePath")
        return
    }

    dispatch_async(dispatch_get_main_queue()) {
        val presenter = topViewControllerForVideo()
        if (presenter == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "调起播放器失败:未找到顶层 UIViewController → $filePath"
            )
            return@dispatch_async
        }
        val url = NSURL(fileURLWithPath = filePath)
        val controller = UIDocumentInteractionController.interactionControllerWithURL(url).apply {
            UTI = "public.mpeg-4"
        }
        val delegate = VideoInteractionDelegate(presenter) {
            DocumentInteractionRetainer.release(it)
        }
        controller.delegate = delegate
        DocumentInteractionRetainer.retain(controller, delegate)

        val previewShown = runCatching {
            controller.presentPreviewAnimated(true)
        }.getOrDefault(false)

        if (previewShown) {
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "调起系统播放器 → $filePath")
        } else {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "预览不可用,回退到分享面板 → $filePath"
            )
            DocumentInteractionRetainer.release(controller)
            presentActivitySheet(presenter, url, filePath)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun presentActivitySheet(presenter: UIViewController, url: NSURL, filePath: String) {
    // iPad 弹窗需要 popoverPresentationController anchor,但当前 K/N binding 未导出该 property,
    // iPhone 上系统会自适应铺满,先按 iPhone 用法走;iPad 弹窗需要 anchor 时后续再补。
    runCatching {
        val activity = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null,
        )
        presenter.presentViewController(activity, animated = true, completion = null)
    }.onFailure {
        SystemLogger.emit(
            LogLevel.Error, LogTag.Media,
            "调起播放器失败(分享面板异常): ${it.message} → $filePath"
        )
    }
}

private fun topViewControllerForVideo(): UIViewController? {
    val root = UIApplication.sharedApplication.windows
        .filterIsInstance<UIWindow>()
        .firstOrNull()
        ?.rootViewController ?: return null
    var current = root
    while (true) {
        val next = current.presentedViewController ?: break
        current = next
    }
    return current
}

/**
 * `UIDocumentInteractionController` 是 __weak__ 引用 delegate 且不 retain 自己,
 * K/N 里如果不本地保住,K/N GC 会在 dispatch 返回后立即回收,导致 preview 无法弹出。
 * 用一个进程内静态集合弱强绑定,dismiss 时解绑释放。
 */
private object DocumentInteractionRetainer {
    private val retained = mutableListOf<Pair<UIDocumentInteractionController, NSObject>>()

    fun retain(controller: UIDocumentInteractionController, delegate: NSObject) {
        retained += controller to delegate
    }

    fun release(controller: UIDocumentInteractionController) {
        retained.removeAll { it.first === controller }
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
@kotlinx.cinterop.ExportObjCClass
private class VideoInteractionDelegate(
    private val presenter: UIViewController,
    private val onDismiss: (UIDocumentInteractionController) -> Unit,
) : NSObject(), UIDocumentInteractionControllerDelegateProtocol {

    override fun documentInteractionControllerViewControllerForPreview(
        controller: UIDocumentInteractionController,
    ): UIViewController = presenter

    override fun documentInteractionControllerDidEndPreview(
        controller: UIDocumentInteractionController,
    ) {
        onDismiss(controller)
    }

    override fun documentInteractionControllerDidDismissOpenInMenu(
        controller: UIDocumentInteractionController,
    ) {
        onDismiss(controller)
    }

    override fun documentInteractionControllerDidDismissOptionsMenu(
        controller: UIDocumentInteractionController,
    ) {
        onDismiss(controller)
    }
}
