package com.uvp.sim.network

/**
 * iOS 侧形式展开:**只做归一化(去空白 / 去结尾点 / 转小写),不做任何 DNS**。
 *
 * 为什么 iOS 可以这么简单(2026-09-12 核对 ktor-network 3.0.2 源码确认,非推测):
 *
 * 根因只存在于 JVM/Android —— 那边的 Ktor `InetSocketAddress.hostname` 映射到
 * `java.net.InetSocketAddress.getHostName()`,**会做反向 DNS**。Ktor Native 没有这条路径:
 *
 * ```
 * // ktor-network/posix/src/io/ktor/network/sockets/DatagramSocketNative.kt:109
 * val address = clientAddress.reinterpret<sockaddr>().toNativeSocketAddress()
 * ... address.toSocketAddress()
 *
 * // ktor-network/posix/src/io/ktor/network/util/NativeSocketAddress.kt
 * internal fun NativeSocketAddress.toSocketAddress(): SocketAddress = when (this) {
 *     is NativeInetSocketAddress -> InetSocketAddress(ipString, port)   // ← ipString
 *     is NativeUnixSocketAddress -> UnixSocketAddress(path)
 * }
 * ```
 *
 * `ipString` 由 `inet_ntop` 从原始 `in_addr`/`in6_addr` 生成,**恒为数字字面量**,
 * 全程不查 DNS。故 iOS 的 `envelope.sourceIp` 不可能变成主机名,
 * 不需要正向/反向解析兜底。
 *
 * 刻意不引 posix `getaddrinfo` / `getnameinfo`:iOS 目标无法在本机实机验证,
 * 引入 cinterop 调用纯属风险。若将来 iOS 侧观测到主机名形式的 `sourceIp`
 * (例如改用别的 transport 实现),再按 `IosLocalIpProvider` 的 posix 风格补
 * `getaddrinfo`,语义与 JVM/Android 版对齐即可。
 */
internal actual fun hostComparableForms(host: String): Set<String> {
    val normalized = normalizeHostForm(host)
    if (normalized.isEmpty()) return emptySet()
    return setOf(normalized)
}

/** iOS 侧不做 DNS(见文件头),预热是 no-op。 */
@Suppress("UNUSED_PARAMETER")
internal actual fun primeHostForms(host: String) {
    // intentionally no-op
}
