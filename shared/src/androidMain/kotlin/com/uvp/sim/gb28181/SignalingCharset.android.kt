package com.uvp.sim.gb28181

import java.nio.charset.Charset

/**
 * Android actual — 走 JVM 内置 charset。
 *
 * ⛔ `GB2312` 映射到 Java 的 **"GBK"** 而不是 "GB2312":JDK 的 "GB2312" 实为 EUC-CN 严格子集
 * (仅 6763 个汉字),编不出 GBK/GB18030 才有的字;平台侧对 `GB2312` 也是取 GBK 兼容超集
 * (见 `manscdp.encodingForLabel`),两侧必须取同一条,否则本端编得出、对面解不回。
 */
private fun jvmCharset(charset: SignalingCharset): Charset = Charset.forName(
    when (charset) {
        SignalingCharset.GB2312 -> "GBK"
        SignalingCharset.GB18030 -> "GB18030"
        SignalingCharset.UTF8 -> "UTF-8"
    }
)

/**
 * `String.toByteArray(charset)` 的默认编码器是 `CodingErrorAction.REPLACE`:编不出的字符落成
 * `?` 而不是抛异常 —— 这正是我们要的语义。宁可丢一个字,也不能"声明 GB18030、字节却是 UTF-8"
 * 让对面整段乱码。
 */
actual fun encodeSignalingText(text: String, charset: SignalingCharset): ByteArray =
    text.toByteArray(jvmCharset(charset))

actual fun decodeSignalingText(bytes: ByteArray, charset: SignalingCharset): String =
    String(bytes, jvmCharset(charset))
