package com.uvp.sim.ui

import com.uvp.sim.observability.SipHeaderRedactor
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun shareText(filename: String, content: String) {
    val redacted = SipHeaderRedactor.redact(content)
    dispatch_async(dispatch_get_main_queue()) {
        val presenter = topViewController() ?: return@dispatch_async
        val activity = UIActivityViewController(
            activityItems = listOf(redacted, filename),
            applicationActivities = null,
        )
        presenter.presentViewController(activity, animated = true, completion = null)
    }
}

private fun topViewController(): UIViewController? {
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
