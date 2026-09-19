package com.uvp.sim.gb28181

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.dataUsingEncoding

/**
 * iOS actual — 走 Foundation 的 NSString/NSData 编码。
 *
 * 三个 `NSStringEncoding` 取值都是 Foundation 公开常量(NSString.h「String Encodings」),
 * 直接写字面量而不引 `platform.CoreFoundation` 的 CF 符号:
 * CF 那边是 `kCFStringEncodingGBK_95`(0x0631)/`kCFStringEncodingGB_18030_2000`(0x0632),
 * 经 `CFStringConvertEncodingToNSStringEncoding` 映射后就是这里的 0x8000_06xx。
 * 写死映射值少一层对 K/N 暴露方式的依赖,值本身是稳定 ABI。
 */
private const val NS_ENCODING_UTF8: ULong = 4uL
private const val NS_ENCODING_GB2312: ULong = 0x80000631uL
private const val NS_ENCODING_GB18030: ULong = 0x80000632uL

private fun nsEncoding(charset: SignalingCharset): ULong = when (charset) {
    SignalingCharset.GB2312 -> NS_ENCODING_GB2312
    SignalingCharset.GB18030 -> NS_ENCODING_GB18030
    SignalingCharset.UTF8 -> NS_ENCODING_UTF8
}

/**
 * `allowLossyConversion = true` 是刻意的:编不出的字符落成 `?` 而不是让整条报文编码失败。
 * 与 Android/JVM 侧 `String.toByteArray(charset)` 的 REPLACE 语义对齐 —— 宁丢一个字,
 * 也不能退回"声明一个编码、字节另一个编码"。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun encodeSignalingText(text: String, charset: SignalingCharset): ByteArray {
    val data = NSString.create(string = text)
        .dataUsingEncoding(encoding = nsEncoding(charset), allowLossyConversion = true)
        ?: return text.encodeToByteArray()
    return data.toSignalingByteArray()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun decodeSignalingText(bytes: ByteArray, charset: SignalingCharset): String {
    if (bytes.isEmpty()) return ""
    val decoded = NSString.create(data = bytes.toNSData(), encoding = nsEncoding(charset))
    // 解码失败(字节根本不是该编码)时退回 UTF-8:与其抛异常把整条报文丢掉,
    // 不如给出可能带乱码的字符串,让上层日志还能看见报文轮廓。
    return decoded?.toString() ?: bytes.decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData.create(bytes = null, length = 0uL)
} else {
    usePinned { pinned -> NSData.dataWithBytes(bytes = pinned.addressOf(0), length = size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toSignalingByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val ptr = bytes ?: return ByteArray(0)
    return ptr.reinterpret<ByteVar>().readBytes(len)
}
