package com.uvp.sim.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelIdGeneratorTest {

    /** 真实首启设备 ID：类型段 105（设备），哈希后缀随机。 */
    private val realDeviceId = "34020000001057079864"

    @Test
    fun `video channel uses 132 type segment`() {
        val result = ChannelIdGenerator.deriveVideoChannelId(realDeviceId)

        assertEquals(20, result.length, "通道编码必须是 20 位")
        assertEquals("132", result.substring(10, 13), "第 11-13 位必须是视频通道类型段 132")
        assertEquals("34020000001321079864", result)
    }

    @Test
    fun `front channel uses 132 type segment and channel number 2`() {
        val result = ChannelIdGenerator.deriveFrontChannelId(realDeviceId)

        assertEquals(20, result.length)
        assertEquals("132", result.substring(10, 13))
        assertEquals("34020000001322079864", result)
    }

    @Test
    fun `alarm channel uses 134 type segment`() {
        val result = ChannelIdGenerator.deriveAlarmChannelId(realDeviceId)

        assertEquals(20, result.length)
        assertEquals("134", result.substring(10, 13), "报警通道类型段必须是 134")
        assertEquals("34020000001341079864", result)
    }

    @Test
    fun `three channels of one device never collide`() {
        val video = ChannelIdGenerator.deriveVideoChannelId(realDeviceId)
        val front = ChannelIdGenerator.deriveFrontChannelId(realDeviceId)
        val alarm = ChannelIdGenerator.deriveAlarmChannelId(realDeviceId)

        assertEquals(3, setOf(video, front, alarm).size, "同设备三个通道编码必须互不相同")
        assertTrue(video != realDeviceId && front != realDeviceId && alarm != realDeviceId)
    }

    @Test
    fun `different devices produce different channel ids`() {
        val device1 = "30000000000010000001"
        val device2 = "31111111111110000001"

        assertTrue(
            ChannelIdGenerator.deriveVideoChannelId(device1) !=
                ChannelIdGenerator.deriveVideoChannelId(device2),
            "不同设备必须派生不同的通道编码",
        )
        // 中心+行业段保持一致，类型段固定 132
        assertTrue(ChannelIdGenerator.deriveVideoChannelId(device1).startsWith(device1.take(10)))
        assertTrue(ChannelIdGenerator.deriveVideoChannelId(device2).startsWith(device2.take(10)))
    }

    @Test
    fun `device id with 105 type segment no longer leaks into channel id`() {
        // 回归：历史实现是 deviceId.take(13) + 固定后缀，会把 105 类型段抄进通道编码，
        // 平台按类型段校验时认不出这是通道（EasyGBS 上表现为只显示设备自身一条）。
        val prefix13 = ChannelIdGenerator.deriveVideoChannelId(realDeviceId).take(13)
        assertEquals("3402000000132", prefix13, "通道编码前 13 位里的类型段必须是 132，不能是 105")
        assertFalse(prefix13.endsWith("105"))
    }

    @Test
    fun `isLegacyDerived recognises old derivation only`() {
        val oldVideo = realDeviceId.take(13) + "0000010"
        val oldFront = realDeviceId.take(13) + "0000020"
        val oldAlarm = realDeviceId.take(13) + "0000001"

        assertTrue(ChannelIdGenerator.isLegacyDerived(realDeviceId, oldVideo))
        assertTrue(ChannelIdGenerator.isLegacyDerived(realDeviceId, oldFront))
        assertTrue(ChannelIdGenerator.isLegacyDerived(realDeviceId, oldAlarm))

        // 用户手改过的 / 新规则产出的，都不算旧规则
        assertFalse(ChannelIdGenerator.isLegacyDerived(realDeviceId, "34020000001320000001"))
        assertFalse(
            ChannelIdGenerator.isLegacyDerived(
                realDeviceId, ChannelIdGenerator.deriveVideoChannelId(realDeviceId)
            )
        )
    }

    @Test
    fun `hasChannelTypeSegment accepts only 132 and 134`() {
        assertTrue(ChannelIdGenerator.hasChannelTypeSegment("34020000001320000001"))
        assertTrue(ChannelIdGenerator.hasChannelTypeSegment("34020000001340000001"))
        assertFalse(ChannelIdGenerator.hasChannelTypeSegment(realDeviceId), "设备自身 105 段不算通道")
        assertFalse(ChannelIdGenerator.hasChannelTypeSegment("123"))
    }

    @Test
    fun `invalid deviceId length throws`() {
        val exception = runCatching { ChannelIdGenerator.deriveVideoChannelId("123456") }
        assertTrue(exception.isFailure, "长度不对的 deviceId 必须抛错")
    }

    @Test
    fun `non digit deviceId throws`() {
        val exception = runCatching { ChannelIdGenerator.deriveVideoChannelId("3402000000AB057079864") }
        assertTrue(exception.isFailure, "含非数字的 deviceId 必须抛错")
    }
}
