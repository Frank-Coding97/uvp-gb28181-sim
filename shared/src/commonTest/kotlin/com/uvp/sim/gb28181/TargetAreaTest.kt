package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GB/T 28181-2022 A.2.3.1.14 目标跟踪控制命令的 `TargetArea` 解析。
 *
 * ⭐ 2026-09-19 新增。此前 `handleTargetTrack` 解析 `<ObjectID>` + `<Speed>`，
 * 这两个名字在 2022 全书与 2016 附录 A 都是 0 命中 —— 平台按标准下发的框选坐标
 * **全部被丢弃**，手动跟踪等于"收到指令但没有目标"。
 */
class TargetAreaTest {

    private val full = """
        <Control>
        <CmdType>DeviceControl</CmdType>
        <SN>1</SN>
        <DeviceID>34020000001320000001</DeviceID>
        <TargetTrack>Manual</TargetTrack>
        <TargetArea>
        <Length>1920</Length>
        <Width>1080</Width>
        <MidPointX>960</MidPointX>
        <MidPointY>540</MidPointY>
        <LengthX>200</LengthX>
        <LengthY>100</LengthY>
        </TargetArea>
        </Control>
    """.trimIndent()

    @Test
    fun parse_readsAllSixFields() {
        val a = TargetArea.parse(full)!!
        assertEquals(1920, a.length)
        assertEquals(1080, a.width)
        assertEquals(960, a.midPointX)
        assertEquals(540, a.midPointY)
        assertEquals(200, a.lengthX)
        assertEquals(100, a.lengthY)
    }

    @Test
    fun describe_ordersFieldsLikeTheStandard() {
        assertEquals("1920x1080 @(960,540) 200x100", TargetArea.parse(full)!!.describe())
    }

    @Test
    fun parse_absentBlock_returnsNull() {
        assertNull(TargetArea.parse("<Control><TargetTrack>Auto</TargetTrack></Control>"))
    }

    /**
     * 六个子元素在 schema 里**全部必选** ⇒ 缺一个就整体 null。
     *
     * ⛔ 绝不返回"半份坐标"：半个框选会让上层以为框选生效了，现象会变成
     * "跟踪框位置不对"而不是"没收到框选"，排查方向完全不同。
     */
    @Test
    fun parse_missingAnyField_returnsNullNeverHalfCoordinates() {
        val fields = listOf("Length", "Width", "MidPointX", "MidPointY", "LengthX", "LengthY")
        for (missing in fields) {
            val body = buildString {
                append("<Control><TargetArea>")
                for (f in fields) {
                    if (f == missing) continue
                    append("<$f>1</$f>")
                }
                append("</TargetArea></Control>")
            }
            assertNull(TargetArea.parse(body), "缺 $missing 时必须整体 null")
        }
    }

    @Test
    fun parse_nonIntegerField_returnsNull() {
        val body = full.replace("<LengthX>200</LengthX>", "<LengthX>abc</LengthX>")
        assertNull(TargetArea.parse(body))
    }

    /**
     * ⛔ 核心坑：`Length`/`Width`/`MidPointX`/`MidPointY`/`LengthX`/`LengthY`
     * 与 `DragZoomIn`/`DragZoomOut` 的**元素名完全相同**。
     *
     * 所以解析必须**先取 `<TargetArea>` 块、再在块内找**。
     * 这条用例构造了「两条命令同时出现」的报文：若实现直接在整篇里 `tagValue("Length")`，
     * 读到的会是 DragZoom 那份 640x480，而标准要求的 TargetArea 尺寸被静默串值。
     */
    @Test
    fun parse_isBlockScoped_doesNotBleedFromDragZoom() {
        val xml = """
            <Control>
            <DragZoomIn>
            <Length>640</Length>
            <Width>480</Width>
            <MidPointX>11</MidPointX>
            <MidPointY>22</MidPointY>
            <LengthX>33</LengthX>
            <LengthY>44</LengthY>
            </DragZoomIn>
            <TargetArea>
            <Length>1920</Length>
            <Width>1080</Width>
            <MidPointX>960</MidPointX>
            <MidPointY>540</MidPointY>
            <LengthX>200</LengthX>
            <LengthY>100</LengthY>
            </TargetArea>
            </Control>
        """.trimIndent()
        val a = TargetArea.parse(xml)!!
        assertEquals(1920, a.length, "必须是 TargetArea 块里的尺寸，不能被 DragZoomIn 串值")
        assertEquals(1080, a.width)
        assertEquals(960, a.midPointX, "中心点也必须来自 TargetArea 块（DragZoom 里的是 11）")
        assertEquals(200, a.lengthX)
        assertEquals(100, a.lengthY)
        assertEquals("1920x1080 @(960,540) 200x100", a.describe())
    }

    /**
     * `TargetArea` 是 `TargetTrack` 的**前缀兄弟**关系：`openTagEnd` 要求标签名后
     * 紧跟 `>`/`/`/空白，所以 `<TargetTrack>` 不会被误当成 `<TargetArea>`。
     */
    @Test
    fun parse_doesNotConfuseSiblingTagNames() {
        val xml = "<Control><TargetTrack>Auto</TargetTrack>${
            "<TargetArea><Length>1</Length><Width>2</Width><MidPointX>3</MidPointX>" +
                "<MidPointY>4</MidPointY><LengthX>5</LengthX><LengthY>6</LengthY></TargetArea>"
        }</Control>"
        assertEquals("1x2 @(3,4) 5x6", TargetArea.parse(xml)!!.describe())
    }

    /** 自闭 `<TargetArea/>` 结构上不成立（六个必选子元素全缺）⇒ null，与"没这个元素"同为 null 但都安全。 */
    @Test
    fun parse_selfClosedBlock_returnsNull() {
        assertNull(TargetArea.parse("<Control><TargetArea/></Control>"))
    }
}
