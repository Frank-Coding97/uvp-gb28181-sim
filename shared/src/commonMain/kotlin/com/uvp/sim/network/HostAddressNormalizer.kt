package com.uvp.sim.network

/**
 * 平台来源地址的"可比形式"工具 —— 2026-09-11/12 Catalog 查询全超时事故的修复。
 *
 * ## 背景
 * transport 层把 `InetSocketAddress.hostname` 直接当作 [SipEnvelope.sourceIp]
 * (见 `UdpSipTransport` / `TcpSipTransport`)。Ktor 的 `hostname` 在 JVM/Android 上
 * 映射到 `java.net.InetSocketAddress.getHostName()`,**会做反向 DNS**。路由器若持有
 * 平台 IP 的 PTR 记录(现场:`192.168.126.126 → Mac.`),设备观测到的 `sourceIp`
 * 就是主机名 `"Mac"`,而配置里 `config.server.ip` 是 `"192.168.126.126"` ——
 * [com.uvp.sim.sip.PlatformAuthorizer] 做精确字符串比对,必然不相等 → 授权门静默
 * 丢弃(不回 200 也不回 403)→ 平台侧干等 10s 报
 * `发送 MESSAGE 失败: context deadline exceeded`。
 *
 * ## 为什么拆成两个函数(⚠️ 这个坑踩了两次)
 * 最直觉的修法是"比对前做一次正向解析,把 `Mac` 解回 IP"。**这条路在 Android 上走不通**:
 * 入向 SIP 消息由 [com.uvp.sim.domain.SimulatorEngine] 在 `viewModelScope`
 * (= `Dispatchers.Main`)里处理,主线程上任何 DNS 都会被 BlockGuard 拦成
 * `NetworkOnMainThreadException`。现场 logcat 实证:
 *
 * ```
 * ERR handleIncoming: NetworkOnMainThreadException: null
 * ```
 *
 * 异常直接打断整条 MESSAGE 处理链路 —— 既没有 200,也没有"丢弃未授权"日志,
 * 比原始故障更难排查。所以职责必须**按线程边界拆开**:
 *
 *  - [hostComparableForms]:**纯读,绝不做 DNS、绝不阻塞**。返回"离线可得的形式"
 *    (归一化后的原文)+ 缓存里已有的形式。授权门(可能跑在主线程)只能用它。
 *  - [primeHostForms]:**填充缓存的唯一入口,内部会做 DNS**,且实现必须
 *    fire-and-forget。只由 transport 层在它自己的后台线程上调用
 *    (`Dispatchers.Default`,见两个 transport 的 `receiveJob`)。
 *
 * 于是"观测侧"的形式来自 transport 真正收到过包的来源地址,"配置侧"的形式来自
 * `connect()` 后对 `remote.host` + `allowList` 的预热。授权门本身零 DNS 开销,
 * 也不可能再把主线程拖进网络调用。
 *
 * ## 比对语义(刻意收窄,不放宽信任面)
 *  - 每一项形式都由 DNS 派生或取自输入原文,没有任何"兜底放行"。
 *  - 空串 / 无任何形式 → 空集 → 调用方按不匹配处理(fail-closed)。
 *  - 数字字面量依旧精确比对 → 非白名单 IP 仍然被拒。
 *  - 未授权仍然直接 drop、不回 403(reconnaissance 防御)不变。
 */
internal expect fun hostComparableForms(host: String): Set<String>

/**
 * 预热 [host] 的可比形式(内部会做 DNS),供授权门后续同步读取。
 *
 * ⚠️ 契约(实现必须遵守):
 *  - **fire-and-forget**:调用线程绝不会被 DNS 阻塞。现场排查中
 *    `getCanonicalHostName()` 在 DNS 不可达的环境下会**永不返回**
 *    (2026-09-12 本机复现),同步执行会把 transport 的接收循环一起拖死。
 *  - **幂等**:同一 host 重复调用只会真正解析一次。
 *  - 只应从中后台线程调用(transport 层已保证)。
 */
internal expect fun primeHostForms(host: String)

/**
 * 形式归一化:去空白、去结尾点(FQDN 绝对名形式)、转小写。
 *
 * `SipEnvelope.sourceIp` 来自 `getHostName()`,不同平台可能带回结尾点
 * (现场路由器 PTR 应答是 `Mac.`,Android 侧却拿到 `Mac`),不做这一步会漏匹配。
 */
internal fun normalizeHostForm(value: String?): String =
    value?.trim()?.trimEnd('.')?.lowercase().orEmpty()
