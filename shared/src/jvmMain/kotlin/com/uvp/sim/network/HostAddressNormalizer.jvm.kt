package com.uvp.sim.network

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * JVM 侧形式展开。
 *
 * ⚠️ 关键细节:`InetAddress.getByName("192.168.126.126")` 构造出来后 `hostName`
 * 已被设成输入的字面量,所以 `getHostName()` **不会**触发 PTR(原样返回
 * `"192.168.126.126"`);要强制反查必须用 `getCanonicalHostName()`。现场需要的
 * `"192.168.126.126"` → `"Mac"` 这条映射就是靠它拿到的。
 *
 * 解析失败一律忽略该形式(不回退成"放行"),由调用方按不匹配处理。
 */
internal actual fun hostComparableForms(host: String): Set<String> {
    val key = host.trim()
    if (key.isEmpty()) return emptySet()
    CACHE[key]?.let { return it }
    // 缓存未命中:只回"离线可得"的原文形式,**不做任何 DNS**
    // (本函数可能被主线程调用,阻塞/网络调用在这里都是不允许的)。
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
 * 专用单线程守护线程。PTR 是阻塞查询,现场 DNS 不可达时会长时间卡住,
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
