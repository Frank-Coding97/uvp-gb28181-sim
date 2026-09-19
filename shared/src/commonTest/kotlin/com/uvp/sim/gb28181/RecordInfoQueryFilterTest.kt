package com.uvp.sim.gb28181

import com.uvp.sim.recording.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * GB/T 28181-2022 A.2.4.5 RecordInfo 查询解析 —— 重点是 **2022 新增的三个过滤条件**：
 * `StreamNumber` / `AlarmMethod` / `AlarmType`。
 *
 * ⭐ 2026-09-19 新增。这三项此前**完全没解析**，于是平台的筛子被静默忽略 =
 * **返回超集**：平台按"视频报警录像 + 子码流"筛，设备把定时录像、主码流录像一起回了，
 * 而平台无法分辨哪条是超发的。
 *
 * ⛔ `AlarmMethod` / `AlarmType` 必须按**字符串**透传而不归一成 Int：两者取值域互相依赖
 * （方式=2 与方式=5 的 AlarmType 是两套完全不同的表），归一后设备侧就分不清
 * "方式 5 的类型 2"和"方式 2 的类型 2"了。
 */
class RecordInfoQueryFilterTest {

    private val tz = "Asia/Shanghai"
    private val channel = "34020000001320000001"

    private fun query(vararg extra: String) = buildString {
        append("<Query><CmdType>RecordInfo</CmdType><SN>42</SN>")
        append("<DeviceID>$channel</DeviceID>")
        append("<StartTime>2026-06-01T00:00:00</StartTime>")
        append("<EndTime>2026-06-12T23:59:59</EndTime>")
        for (e in extra) append(e)
        append("</Query>")
    }

    private fun parse(vararg extra: String) =
        RecordInfoQuery.parse(query(*extra), tz)

    // ---- 基础 ----

    @Test
    fun parse_baselineFields() {
        val q = assertNotNull(parse())
        assertEquals("42", q.sn)
        assertEquals(channel, q.channelId)
        assertNull(q.type, "没带 Type 即 all")
        assertEquals(0, q.secrecy)
    }

    // ---- G-7 三个 2022 新增过滤条件 ----

    @Test
    fun parse_streamNumber() {
        assertEquals(1, assertNotNull(parse("<StreamNumber>1</StreamNumber>")).streamNumber)
        assertEquals(0, assertNotNull(parse("<StreamNumber>0</StreamNumber>")).streamNumber)
    }

    /**
     * ⛔ 缺席（`null`）与"显式下发 0"**是两件事**：
     * 前者 = 平台没按码流筛（不过滤）；后者 = 平台点名主码流（要过滤）。
     * 合并成一个 Int 之后调用方就再也分不清，只能把两种都当 0 收下 —— 于是
     * "平台没提码流"和"平台点名主码流"落进同一分支。
     */
    @Test
    fun streamNumber_absentIsNull_notZero() {
        assertNull(assertNotNull(parse()).streamNumber)
    }

    @Test
    fun parse_alarmMethodAndType_asStrings() {
        val q = assertNotNull(parse("<AlarmMethod>5</AlarmMethod><AlarmType>2</AlarmType>"))
        assertEquals("5", q.alarmMethod)
        assertEquals("2", q.alarmType)
    }

    /** 空元素 / 纯空白视为缺席，不造一个空串让下游去判。 */
    @Test
    fun alarmFields_blankIsTreatedAsAbsent() {
        val q = assertNotNull(parse("<AlarmMethod>   </AlarmMethod><AlarmType></AlarmType>"))
        assertNull(q.alarmMethod)
        assertNull(q.alarmType)
    }

    /** 非数字的码流号解析失败即缺席（不补 0 —— 补 0 会变成"平台点名了主码流"）。 */
    @Test
    fun streamNumber_nonNumericBecomesAbsent() {
        assertNull(assertNotNull(parse("<StreamNumber>main</StreamNumber>")).streamNumber)
    }

    @Test
    fun parse_allThreeTogether() {
        val q = assertNotNull(parse(
            "<Type>alarm</Type>",
            "<StreamNumber>1</StreamNumber>",
            "<AlarmMethod>5</AlarmMethod>",
            "<AlarmType>2</AlarmType>",
        ))
        assertEquals(RecordType.Alarm, q.type)
        assertEquals(1, q.streamNumber)
        assertEquals("5", q.alarmMethod)
        assertEquals("2", q.alarmType)
    }

    // ---- 旧的四个高级字段仍然只解析透传 ----

    @Test
    fun parse_legacyAdvancedFields_stillPassThrough() {
        val q = assertNotNull(parse(
            "<IndistinctQuery>1</IndistinctQuery>",
            "<FilePath>/x/y</FilePath>",
            "<Address>10.0.0.1</Address>",
            "<RecorderID>34020000001110000001</RecorderID>",
        ))
        assertEquals(1, q.indistinctQuery)
        assertEquals("/x/y", q.filePath)
        assertEquals("10.0.0.1", q.address)
        assertEquals("34020000001110000001", q.recorderId)
    }

    // ---- 必选字段缺失即整体 null ----

    @Test
    fun parse_missingMandatoryFields_returnsNull() {
        assertNull(RecordInfoQuery.parse("<Query><CmdType>RecordInfo</CmdType></Query>", tz), "缺 SN/DeviceID/时间")
        assertNull(
            RecordInfoQuery.parse(
                "<Query><SN>1</SN><DeviceID>$channel</DeviceID><StartTime>2026-06-01T00:00:00</StartTime></Query>",
                tz
            ),
            "缺 EndTime"
        )
    }

    // ---- G-6：DeviceControl RecordCmd 里的 StreamNumber ----

    /**
     * A.2.3.1.4 录像控制命令里的 `<StreamNumber>`（2022 新增）。
     * 标准原文「码流类型：0-主码流，1-子码流1，2-子码流2，以此类推（可选），缺省 0」。
     */
    @Test
    fun recordStreamNumber_parsesFromDeviceControl() {
        val xml = "<Control><CmdType>DeviceControl</CmdType><RecordCmd>Record</RecordCmd>" +
            "<StreamNumber>1</StreamNumber></Control>"
        assertEquals(1, ManscdpParser.recordStreamNumber(xml))
    }

    @Test
    fun recordStreamNumber_absentIsNull_distinctFromExplicitZero() {
        val without = "<Control><RecordCmd>Record</RecordCmd></Control>"
        val withZero = "<Control><RecordCmd>Record</RecordCmd><StreamNumber>0</StreamNumber></Control>"
        assertNull(ManscdpParser.recordStreamNumber(without), "平台没提码流")
        assertEquals(0, ManscdpParser.recordStreamNumber(withZero), "平台点名主码流 —— 两者不同")
    }
}
