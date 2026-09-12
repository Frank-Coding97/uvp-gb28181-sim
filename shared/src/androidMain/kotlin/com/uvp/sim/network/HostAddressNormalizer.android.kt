package com.uvp.sim.network

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Android 侧形式展开,与 jvmMain 实现同款
 * (`java.net.InetAddress` 在 Android 上同样可用)。
 *
 * ⚠️ 与 JVM 的唯一区别是**必须**守住这条线:App 的入向 SIP 处理跑在
 * `viewModelScope`(= 主线程),主线程上任何 DNS 都会被 BlockGuard 抛
 * `NetworkOnMainThreadException`(2026-09-12 现场 logcat 实证),异常会打断
 * 整条 MESSAGE 处理链路。因此 [hostComparableForms] 在主线程只读缓存,
 * 真正的解析只能由 [primeHostForms] 在专用后台线程完成。
 *
 * 刻意**同时**收集正向解析(`getByName().hostAddress`)与 PTR 名
 * (`getCanonicalHostName()`):现场 App 进程内正向解析单标签名(`"Mac"`)
 * 未必可用,而 PTR 方向一定可用 —— transport 上报的 `sourceIp` 本身就是
 * PTR 的产物。两条路径都留着才不至于再次静默失效。
 */
internal actual fun hostComparableForms(host: String): Set<String> {
    val key = host.trim()
    if (key.isEmpty()) return emptySet()
    CACHE[key]?.let { return it }
    val normalized = normalizeHostForm(key)
    return if (normalized.isEmpty()) emptySet() else setOf(normalized)
}

/** 见 commonMain 契约:fire-and-forget,绝不在调用线程做 DNS。 */
internal actual fun primeHostForms(host: String) {
    val key = host.trim()
    if (key.isEmpty() || CACHE.containsKey(key)) return
    if (!PENDING.add(key)) return
    RESOLVER.execute {
        try {
            if (CACHE.size < CACHE_MAX_ENTRIES) CACHE[key] = computeForms(key)
        } finally {
            PENDING.remove(key)
        }
    }
}

/** 形式缓存。授权门在入向消息路径上,每条消息都重查一次 DNS 不可接受。 */
private val CACHE = ConcurrentHashMap<String, Set<String>>()

/** 正在解析中的 host,避免同一 host 重复排队。 */
private val PENDING = ConcurrentHashMap.newKeySet<String>()

/** 入口地址来自局域网主机,正常规模极小;设上限只是防止异常来源把内存撑爆。 */
private const val CACHE_MAX_ENTRIES = 256

/**
 * 专用单线程守护线程。PTR 是阻塞查询,DNS 不可达时会长时间卡住,
 * 不能占用 `Dispatchers.Default` 的公共线程池,更不能阻塞 transport 接收循环。
 */
private val RESOLVER = Executors.newSingleThreadExecutor { r ->
    Thread(r, "uvp-host-forms").apply { isDaemon = true }
}

private fun computeForms(trimmed: String): Set<String> {
    val forms = linkedSetOf(normalizeHostForm(trimmed))
    val address = runCatching { InetAddress.getByName(trimmed) }.getOrNull() ?: return forms
    forms.addForm(address.hostAddress)
    forms.addForm(address.hostName)
    forms.addForm(address.canonicalHostName)
    return forms
}

private fun MutableSet<String>.addForm(value: String?) {
    val normalized = normalizeHostForm(value)
    if (normalized.isNotEmpty()) add(normalized)
}
