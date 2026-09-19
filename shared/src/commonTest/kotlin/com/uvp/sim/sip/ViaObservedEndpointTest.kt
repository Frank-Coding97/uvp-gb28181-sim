package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ViaObservedEndpoint] 的边界。
 *
 * 这里钉的都是**真实报文里出现过**的形态:平台回填完整、只回填 received、
 * 干脆不回填(收到我们发出去的裸 `;rport`)。最后一种是最容易写错的 ——
 * 裸参数没有 `=` 号,字符串处理若不判就是一次 NPE 或把 `rport` 自身当值。
 */
class ViaObservedEndpointTest {

    // ---- parse ----

    @Test
    fun `平台完整回填 received 与 rport 时两者都取到`() {
        val observed = ViaObservedEndpoint.parse(
            "SIP/2.0/TCP 192.168.1.10:5060;rport=51234;received=203.0.113.5;branch=z9hG4bK123",
        )
        assertEquals("203.0.113.5", observed?.ip)
        assertEquals(51234, observed?.port)
    }

    @Test
    fun `参数顺序不影响解析`() {
        val observed = ViaObservedEndpoint.parse(
            "SIP/2.0/UDP 10.0.0.7:5060;received=198.51.100.9;branch=z9hG4bKabc;rport=62000",
        )
        assertEquals("198.51.100.9", observed?.ip)
        assertEquals(62000, observed?.port)
    }

    /**
     * 这条是**最关键的回归点**:设备自己发出去的 Via 里带的是**裸** `;rport`(无等号)。
     * 平台若不支持 RFC 3581、原样回显该头,解析结果必须是 null(「未知」),
     * 而不是把 "rport" 这个词当成地址。
     */
    @Test
    fun `裸 rport 参数不产生观察值`() {
        assertNull(
            ViaObservedEndpoint.parse(
                "SIP/2.0/UDP 10.0.0.7:5060;rport;branch=z9hG4bKabc",
            ),
        )
    }

    @Test
    fun `只回填 received 时端口为 null`() {
        val observed = ViaObservedEndpoint.parse(
            "SIP/2.0/TCP 192.168.1.10:5060;received=203.0.113.5;branch=z9hG4bK123",
        )
        assertEquals("203.0.113.5", observed?.ip)
        assertNull(observed?.port)
    }

    @Test
    fun `只回填 rport 时 IP 为 null`() {
        val observed = ViaObservedEndpoint.parse(
            "SIP/2.0/TCP 192.168.1.10:5060;rport=51234;branch=z9hG4bK123",
        )
        assertNull(observed?.ip)
        assertEquals(51234, observed?.port)
    }

    @Test
    fun `没有 Via 或空值时返回 null`() {
        assertNull(ViaObservedEndpoint.parse(null))
        assertNull(ViaObservedEndpoint.parse(""))
        assertNull(ViaObservedEndpoint.parse("   "))
        // 只有协议与 sent-by、一个参数都没有
        assertNull(ViaObservedEndpoint.parse("SIP/2.0/TCP 192.168.1.10:5060"))
    }

    @Test
    fun `rport 越界或非数字时按未知处理`() {
        assertNull(ViaObservedEndpoint.parse("SIP/2.0/TCP 10.0.0.1:5060;rport=0")?.port)
        assertNull(ViaObservedEndpoint.parse("SIP/2.0/TCP 10.0.0.1:5060;rport=65536")?.port)
        assertNull(ViaObservedEndpoint.parse("SIP/2.0/TCP 10.0.0.1:5060;rport=abc")?.port)
        // 端口无效但 received 有效 → 仍然保留 IP
        assertEquals(
            "203.0.113.5",
            ViaObservedEndpoint.parse("SIP/2.0/TCP 10.0.0.1:5060;rport=abc;received=203.0.113.5")?.ip,
        )
    }

    @Test
    fun `参数名大小写不敏感`() {
        val observed = ViaObservedEndpoint.parse(
            "SIP/2.0/TCP 10.0.0.1:5060;RPORT=51234;Received=203.0.113.5",
        )
        assertEquals("203.0.113.5", observed?.ip)
        assertEquals(51234, observed?.port)
    }

    // ---- assess ----

    @Test
    fun `无观察值时判定为未知而不是直连`() {
        // ⛔ 这条特别重要:平台不回填**不能**当成"没在 NAT 后"。它只说明我们不知道。
        val observation = ViaObservedEndpoint.assess("192.168.1.10", 5060, null)
        assertEquals(NatSituation.UNKNOWN, observation.situation)
    }

    @Test
    fun `端点完全一致时判定为无地址转换`() {
        val observation = ViaObservedEndpoint.assess(
            "192.168.1.10", 5060, ObservedEndpoint("192.168.1.10", 5060),
        )
        assertEquals(NatSituation.DIRECT, observation.situation)
        assertEquals("192.168.1.10", observation.observedIp)
        assertEquals(5060, observation.observedPort)
    }

    @Test
    fun `IP 相同但端口不同时判定为有地址转换`() {
        // 典型:NAPT 只换端口(源端口 5060 → 映射端口 51234)
        val observation = ViaObservedEndpoint.assess(
            "192.168.1.10", 5060, ObservedEndpoint("192.168.1.10", 51234),
        )
        assertEquals(NatSituation.NAT, observation.situation)
    }

    @Test
    fun `IP 不同时判定为有地址转换`() {
        val observation = ViaObservedEndpoint.assess(
            "192.168.1.10", 5060, ObservedEndpoint("203.0.113.5", 51234),
        )
        assertEquals(NatSituation.NAT, observation.situation)
        // 观察值原样带出来,UI 要显示"平台看到的是谁"
        assertEquals("203.0.113.5", observation.observedIp)
        assertEquals(51234, observation.observedPort)
        // 本机端点也带出来,UI 用来做对照展示
        assertEquals("192.168.1.10", observation.localIp)
        assertEquals(5060, observation.localPort)
    }

    @Test
    fun `IP 相同且端口未知时不判为地址转换`() {
        // 对端只实现了半个 RFC 3581(回填 received 不回填 rport)。此时冒然判 NAT
        // 会给出误导性的"请改用 TCP"——实际很可能就在同一网段。
        val observation = ViaObservedEndpoint.assess(
            "192.168.1.10", 5060, ObservedEndpoint("192.168.1.10", null),
        )
        assertEquals(NatSituation.DIRECT, observation.situation)
    }

    @Test
    fun `观察到的 IP 为空串时按未知处理`() {
        val observation = ViaObservedEndpoint.assess(
            "192.168.1.10", 5060, ObservedEndpoint("   ", 51234),
        )
        assertEquals(NatSituation.UNKNOWN, observation.situation)
    }
}
