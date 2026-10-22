package com.uvp.sim.sip

/**
 * 平台在响应 Via 里回填的「它看到的我方端点」。
 *
 * 来源是 RFC 3581(NAT 场景下 SIP 的对称响应):客户端在 Via 里发一个**裸的** `;rport`,
 * 服务端回包时把它看到的源地址写回 `received=`、源端口写回 `rport=`。
 *
 * ## 工程用途
 * `rport` / `received` 属于 RFC 3581 的 SIP Via 参数,不是 GB/T 28181 的注册必选项。
 * 这里用它们观察平台看到的端点,并在信息完整时尝试更新 Contact。UDP/NAT 环境仍可继续
 * 使用 UDP;若平台未回填、映射不稳定或下行仍失败,再结合现场网络选择 TCP 或端口映射。
 *
 * [ip] / [port] 任一可能为 null:服务端可以只回填其中一个(甚至都不回填,那就整体为 null)。
 */
data class ObservedEndpoint(
    val ip: String?,
    val port: Int?,
) {
    /** 只有平台同时回填地址和端口时才允许用于 Contact。 */
    fun completeAddress(): String? {
        val host = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mappedPort = port?.takeIf { it in 1..65535 } ?: return null
        val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return "$formattedHost:$mappedPort"
    }
}

/**
 * 本机端点与平台观察端点的关系。
 *
 * 用来回答现场最常问的那个问题:「我明明填了 192.168.x.x,为什么平台连不上我?」
 * 平台若回填了 Via 映射参数,设备可以据此发现地址差异并尝试使用映射端点。
 */
enum class NatSituation {
    /** 平台没有回填(没开 rport 支持,或响应里没带 Via)。**不代表不在 NAT 后**。 */
    UNKNOWN,

    /** 平台看到的地址与本机声明的完全一致 —— 直连,或恰好做了 1:1 映射。 */
    DIRECT,

    /**
     * 平台看到的地址与本机声明的不一致 —— 中间存在地址转换。
     *
     * UDP 下先尝试把完整映射端点写入后续 Contact,让支持固定映射的网络继续工作。
     * 若映射不稳定或下行仍被 NAT 丢弃,再由现场选择 TCP 长连接或配置端口映射。
     */
    NAT,
}

/** 一次注册会话里观察到的事实:平台看到的我方端点 + 与本地端点的关系。 */
data class RportObservation(
    val observedIp: String?,
    val observedPort: Int?,
    val localIp: String,
    val localPort: Int,
    val situation: NatSituation,
)

/**
 * 解析响应 Via 的 `received` / `rport` 回填(纯函数,便于把边界钉死在单测里)。
 */
internal object ViaObservedEndpoint {

    /**
     * 从**首个** Via header 的值里取出 received / rport。
     *
     * 形如 `SIP/2.0/TCP 192.168.1.10:5060;rport=51234;received=203.0.113.5;branch=z9hG4bK...`。
     * 参数顺序不固定,也可能只有其中一个,还可能只有我们发出去的**裸** `;rport`(无 `=`)。
     */
    fun parse(viaHeaderValue: String?): ObservedEndpoint? {
        val raw = viaHeaderValue?.trim().orEmpty()
        if (raw.isEmpty()) return null

        var received: String? = null
        var rport: Int? = null

        // 按 ';' 切分后丢掉第 0 段(sent-protocol + sent-by),只看 via-params。
        // RFC 3581 把 rport 定义为 1*DIGIT、received 定义为 IPv4/IPv6 字面量,
        // 两者都不需要处理引号转义 —— 所以这里刻意不做通用的 quoted-string 拆解。
        for (part in raw.split(';').drop(1)) {
            val segment = part.trim()
            val separator = segment.indexOf('=')
            // 裸 ;rport(我们自己发出去的那个形态)没有 '=',落在这里被跳过,正确。
            if (separator <= 0) continue
            val name = segment.substring(0, separator).trim()
            val value = segment.substring(separator + 1).trim()
            when {
                name.equals("received", ignoreCase = true) && value.isNotEmpty() -> received = value
                name.equals("rport", ignoreCase = true) ->
                    rport = value.toIntOrNull()?.takeIf { it in 1..65535 }
            }
        }

        if (received == null && rport == null) return null
        return ObservedEndpoint(received, rport)
    }

    /**
     * 把「平台观察到的端点」与「本机声明的端点」对照,给出 NAT 判定。
     *
     * 注意 [NatSituation.UNKNOWN] 的语义是「**不知道**」而不是「没在 NAT 后」——
     * 平台不回填或设备没发 `;rport` 时都会落到这里,不能拿它当"网络正常"的证据。
     */
    fun assess(localIp: String, localPort: Int, observed: ObservedEndpoint?): RportObservation {
        val situation = when {
            observed == null || observed.ip.isNullOrBlank() -> NatSituation.UNKNOWN
            // 只对得上 IP、端口未知时也判 DIRECT:端口不同多半是 NAT 映射所致,
            // 但**只回填 received 不回填 rport** 说明对端没实现完整 RFC 3581,
            // 这时冒然判 NAT 会给出误导性的"请改用 TCP"提示(实际可能就在同一网段)。
            observed.ip == localIp && (observed.port == null || observed.port == localPort) -> NatSituation.DIRECT
            else -> NatSituation.NAT
        }
        return RportObservation(
            observedIp = observed?.ip,
            observedPort = observed?.port,
            localIp = localIp,
            localPort = localPort,
            situation = situation,
        )
    }
}
