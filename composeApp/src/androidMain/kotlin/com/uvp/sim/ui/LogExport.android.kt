package com.uvp.sim.ui

import android.content.Intent

/**
 * Android: 推系统分享面板。filename 仅用于 EXTRA_TITLE,实际 P0 没真正写文件
 * (P1 才做 Storage Access Framework 落盘)。
 *
 * 调用方需在 Activity 上下文里调,所以我们走静态 holder 拿 Context — 简化方案。
 */
actual fun shareText(filename: String, content: String) {
    val ctx = ShareContextHolder.context ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, filename)
        putExtra(Intent.EXTRA_TEXT, content)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, "导出日志").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

object ShareContextHolder {
    @Volatile var context: android.content.Context? = null
}
