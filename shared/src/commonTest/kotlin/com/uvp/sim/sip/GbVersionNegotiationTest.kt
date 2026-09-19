package com.uvp.sim.sip

import com.uvp.sim.config.GbVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 附录 I「协议版本标识」的版本解析与协商规则。
 *
 * 表 I.1 版本号定义:1.0=2011、1.1=2011 修改补充文件、2.0=2016、3.0=2022。
 * 本仓只实现 2016/2022 两档,2011 的两个值按 2016 兼容处理(与平台侧 protocol.go 同口径)。
 */
class GbVersionNegotiationTest {

    @Test
    fun `表 I 1 的四个版本号都能落到本仓两档`() {
        assertEquals(GbVersion.V2016, GbVersionNegotiation.parse("1.0"))
        assertEquals(GbVersion.V2016, GbVersionNegotiation.parse("1.1"))
        assertEquals(GbVersion.V2016, GbVersionNegotiation.parse("2.0"))
        assertEquals(GbVersion.V2022, GbVersionNegotiation.parse("3.0"))
    }

    @Test
    fun `解析容忍首尾空白`() {
        assertEquals(GbVersion.V2022, GbVersionNegotiation.parse(" 3.0 "))
        assertEquals(GbVersion.V2016, GbVersionNegotiation.parse("\t2.0\r\n"))
    }

    @Test
    fun `不可识别的值一律返回 null 而不是猜一个版本`() {
        assertNull(GbVersionNegotiation.parse(null))
        assertNull(GbVersionNegotiation.parse(""))
        assertNull(GbVersionNegotiation.parse("   "))
        assertNull(GbVersionNegotiation.parse("3"))      // 缺小数点
        assertNull(GbVersionNegotiation.parse("3.0.1"))  // 多段
        assertNull(GbVersionNegotiation.parse("v3.0"))   // 非数字
        assertNull(GbVersionNegotiation.parse("4.0"))    // 表 I.1 之外
        assertNull(GbVersionNegotiation.parse("2.1"))    // 表 I.1 之外
    }

    @Test
    fun `有效版本取双方较低者`() {
        assertEquals(GbVersion.V2022, GbVersionNegotiation.effective(GbVersion.V2022, GbVersion.V2022))
        assertEquals(GbVersion.V2016, GbVersionNegotiation.effective(GbVersion.V2022, GbVersion.V2016))
        assertEquals(GbVersion.V2016, GbVersionNegotiation.effective(GbVersion.V2016, GbVersion.V2022))
        assertEquals(GbVersion.V2016, GbVersionNegotiation.effective(GbVersion.V2016, GbVersion.V2016))
    }

    @Test
    fun `对端未声明时保留本机版本而不是降级`() {
        assertEquals(GbVersion.V2022, GbVersionNegotiation.effective(GbVersion.V2022, null))
        assertEquals(GbVersion.V2016, GbVersionNegotiation.effective(GbVersion.V2016, null))
    }
}
