package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GB/T 28181-2022 A.2.5.7 图像抓拍传输完成通知（§9.14.3 i)）。
 *
 * ⭐ 2026-09-19 本测试整体重写。原先钉的三件事**有一半是自造形态**：
 *  - 断言 `<CmdType>Notify</CmdType>` + `<SubCmd>SnapShot</SubCmd>` —— `SubCmd` 在 2022 全书 0 命中；
 *  - 断言 `SnapShotID` / `Time` / `StoragePath` 三个元素 —— 标准里一个都没有；
 *  - 完全没有断言 `SnapShotList` —— 而它恰是平台判"部分失败"的**唯一**依据。
 * 旧测试因此把"平台永远认为抓拍没完成"这个故障固化成了"预期"。
 *
 * 现在的锚点按 A.2.5.7 原文：`CmdType=UploadSnapShotFinished` +
 * `SessionID` + `SnapShotList/SnapShotFileID`（`minOccurs=0 maxOccurs=10`）。
 */
class SnapShotNotifyBuilderTest {

    private val deviceId = "34020000001320000001"

    private fun build(fileIds: List<String>) = SnapShotNotifyBuilder.build(
        deviceId = deviceId,
        sn = "42",
        sessionId = "S001",
        fileIds = fileIds,
    )

    private val sample = build(listOf("20260617T120000_0.jpg", "20260617T120000_1.jpg"))

    // ---- A.2.5.7 CmdType ----

    @Test
    fun cmdType_is_UploadSnapShotFinished() {
        assertEquals("UploadSnapShotFinished", SnapShotNotifyBuilder.CMD_TYPE)
        assertContains(sample, "<CmdType>UploadSnapShotFinished</CmdType>")
    }

    /**
     * ⛔ 自造形态必须彻底消失：`<CmdType>Notify</CmdType>` 与 `<SubCmd>` 都在 2022 全书 0 命中。
     * 只要它们还在，严格按标准匹配的平台就会**两条路径都匹配不上**。
     */
    @Test
    fun standardPath_hasNoSelfInventedElements() {
        assertFalse(sample.contains("<CmdType>Notify</CmdType>"), "CmdType 不得是 Notify: $sample")
        assertFalse(sample.contains("<SubCmd>"), "SubCmd 两版标准 0 命中: $sample")
        assertFalse(sample.contains("<SnapShotID>"), "SnapShotID 不是 A.2.5.7 的元素: $sample")
        assertFalse(sample.contains("<StoragePath>"), "StoragePath 不是 A.2.5.7 的元素: $sample")
    }

    /** `Time` 只出现在**自有扩展** legacy 形态里；标准形态不含。 */
    @Test
    fun standardPath_hasNoTimeElement() {
        assertFalse(sample.contains("<Time>"), "标准形态不带 Time: $sample")
    }

    @Test
    fun wrapper_is_notify() {
        assertContains(sample, "<Notify>")
        assertContains(sample, "</Notify>")
    }

    // ---- 必备字段与顺序 ----

    @Test
    fun contains_sn_deviceId_sessionId() {
        assertContains(sample, "<SN>42</SN>")
        assertContains(sample, "<DeviceID>$deviceId</DeviceID>")
        assertContains(sample, "<SessionID>S001</SessionID>")
    }

    @Test
    fun fieldOrder_matchesA2257() {
        // SN → DeviceID → SessionID → SnapShotList
        val sn = sample.indexOf("<SN>")
        val devId = sample.indexOf("<DeviceID>")
        val sess = sample.indexOf("<SessionID>")
        val list = sample.indexOf("<SnapShotList>")
        assertTrue(sn < devId, "SN before DeviceID")
        assertTrue(devId < sess, "DeviceID before SessionID")
        assertTrue(sess < list, "SessionID before SnapShotList")
    }

    // ---- SnapShotList / SnapShotFileID ----

    @Test
    fun snapShotList_carriesEveryUploadedFileIdInOrder() {
        assertContains(sample, "<SnapShotList>")
        assertContains(sample, "</SnapShotList>")
        assertContains(sample, "<SnapShotFileID>20260617T120000_0.jpg</SnapShotFileID>")
        assertContains(sample, "<SnapShotFileID>20260617T120000_1.jpg</SnapShotFileID>")
        assertTrue(
            sample.indexOf("_0.jpg") < sample.indexOf("_1.jpg"),
            "文件标识顺序即抓拍顺序，不能乱: $sample"
        )
        assertEquals(2, Regex("<SnapShotFileID>").findAll(sample).count())
    }

    /**
     * ⭐ 空列表是**合法且有语义**的报文（`<SnapShotList/>`）：
     * A.2.5.7 原话「无文件标识……表示全部或部分抓拍或上传操作异常失败」。
     * 不能因为"看起来像没内容"就跳过发送 —— 不发平台只会一直等。
     */
    @Test
    fun emptyFileIds_emitsSelfClosingList_stillSendsNotify() {
        val xml = build(emptyList())
        assertContains(xml, "<SnapShotList/>")
        assertFalse(xml.contains("<SnapShotFileID>"))
        assertContains(xml, "<CmdType>UploadSnapShotFinished</CmdType>")
    }

    /**
     * ⛔ `SnapShotFileID` 的 `maxOccurs="10"` 是 schema 硬约束。
     * 超发会让严格校验的对端判**整条报文非法**——比丢几个标识更糟。
     */
    @Test
    fun capsAtTenFileIds() {
        val eleven = (0 until 11).map { "shot_$it.jpg" }
        val xml = build(eleven)
        assertEquals(
            SnapShotNotifyBuilder.MAX_FILE_IDS,
            Regex("<SnapShotFileID>").findAll(xml).count(),
            "超过 maxOccurs=10 必须截断，实际: $xml"
        )
        assertContains(xml, "<SnapShotFileID>shot_0.jpg</SnapShotFileID>")
        assertFalse(xml.contains("shot_10.jpg"), "第 11 条（下标 10）应被截掉")
    }

    /** 文件标识含 XML 元字符时要转义，否则平台侧解析会炸。 */
    @Test
    fun fileIds_areXmlEscaped() {
        val xml = build(listOf("a&b<c>.jpg"))
        assertContains(xml, "<SnapShotFileID>a&amp;b&lt;c&gt;.jpg</SnapShotFileID>")
        assertFalse(xml.contains("a&b<c>"), "未转义的标识不得出现: $xml")
    }

    // ---- 线格式 ----

    @Test
    fun encoding_is_gb2312() {
        assertContains(sample, "encoding=\"GB2312\"")
    }

    @Test
    fun line_endings_are_crlf() {
        assertTrue(sample.contains("\r\n"), "must contain CRLF")
        val strippedCrlf = sample.replace("\r\n", "")
        assertFalse(strippedCrlf.contains('\n'), "no bare LF outside CRLF")
    }

    // ---- 自有扩展（legacy）：只保留给旧平台，不得反向污染标准路径 ----

    @Test
    fun buildLegacy_usesCmdTypeSnapShot_noSubCmdNoSnapShotList() {
        val legacy = SnapShotNotifyBuilder.buildLegacy(
            deviceId = deviceId,
            sn = "42",
            sessionId = "S001",
            snapShotId = "20260617T120000_0",
            timeIso = "2026-06-17T12:00:00.000Z",
            storagePath = "http://h/p.jpg"
        )
        assertContains(legacy, "<CmdType>SnapShot</CmdType>")
        assertFalse(legacy.contains("<SubCmd>"), "legacy must not include SubCmd")
        assertFalse(legacy.contains("<SnapShotList>"), "legacy 是每张一条的旧形态，不带列表")
        assertContains(legacy, "<SnapShotID>20260617T120000_0</SnapShotID>")
        assertContains(legacy, "<StoragePath>http://h/p.jpg</StoragePath>")
    }
}
