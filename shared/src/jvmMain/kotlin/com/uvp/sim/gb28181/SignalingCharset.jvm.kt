package com.uvp.sim.gb28181

import java.nio.charset.Charset

/**
 * JVM actual(供 `:shared:jvmTest` 跑 commonTest)—— 与 [SignalingCharset] 的 Android 侧同一套实现。
 *
 * ⛔ `GB2312` → Java **"GBK"**:JDK 的 "GB2312" 是 EUC-CN 严格子集,编不出 GBK 才有的字;
 * 平台侧对 `GB2312` 也取 GBK 兼容超集,两侧必须一致。
 */
private fun jvmCharset(charset: SignalingCharset): Charset = Charset.forName(
    when (charset) {
        SignalingCharset.GB2312 -> "GBK"
        SignalingCharset.GB18030 -> "GB18030"
        SignalingCharset.UTF8 -> "UTF-8"
    }
)

actual fun encodeSignalingText(text: String, charset: SignalingCharset): ByteArray =
    text.toByteArray(jvmCharset(charset))

actual fun decodeSignalingText(bytes: ByteArray, charset: SignalingCharset): String =
    String(bytes, jvmCharset(charset))
