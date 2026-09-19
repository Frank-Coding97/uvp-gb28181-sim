package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 A.2.5.7 图像抓拍传输完成通知（§9.14.3 i) 要求发这条）。
 *
 * 标准报文形态:
 * ```xml
 * <Notify>
 *   <CmdType>UploadSnapShotFinished</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <SessionID>...</SessionID>            <!-- 32~128 字节，与 SnapShotConfig 下发的一致 -->
 *   <SnapShotList>
 *     <SnapShotFileID>...</SnapShotFileID>  <!-- minOccurs=0 maxOccurs=10 -->
 *   </SnapShotList>
 * </Notify>
 * ```
 *
 * ⛔ **2026-09-19 按标准重写**（原先两种形态都不合标准）:
 *   - 原先主用形态发 `<CmdType>Notify</CmdType>` + `<SubCmd>SnapShot</SubCmd>`、
 *     兼容形态发 `<CmdType>SnapShot</CmdType>`。`SubCmd` 在 2022 全书 **0 命中**，
 *     `UploadSnapShotFinished` 在**整个仓库 0 命中** ⇒ 严格按标准匹配的平台
 *     **两条路径都匹配不上**（抓拍其实发生了，平台却认为没完成）。
 *   - 原先发的是 `SnapShotID` / `Time` / `StoragePath` 三个**标准里没有**的元素，
 *     而标准要求的 `SnapShotList/SnapShotFileID` 完全缺席 ⇒ 平台判"全部/部分失败"
 *     的唯一依据（文件标识个数）不存在。
 *
 * ⭐ **语义要点（标准原话）**：「无文件标识或文件标识个数**少于要求抓拍的文件个数**，
 * 表示全部或部分抓拍或上传操作异常失败」。所以这条通知是**一次序列一条**、
 * 携带**成功上传的**文件标识列表，不是每张图一条 —— 判断"部分失败"正是靠列表长度
 * 与 `SnapShotConfig.snapNum` 的差额。调用方见
 * [com.uvp.sim.snapshot.SnapshotUploadEngine]。
 *
 * ⚠️ `SessionID` 标准限 32~128 字节；本函数**不裁剪也不校验** —— 它由平台下发、
 * 用于关联抓拍请求，设备侧改一个字节都会让平台对不上。超长是平台侧的问题，
 * 这里原样透传（KDoc 留痕即可，不静默改值）。
 */
object SnapShotNotifyBuilder {

    /** A.2.5.7 的 `CmdType` 固定值。 */
    const val CMD_TYPE = "UploadSnapShotFinished"

    /**
     * 标准形态：一次抓拍序列**结束后**发一条。
     *
     * @param fileIds **成功上传**的抓拍图像标识，顺序即抓拍顺序。
     *   空列表是合法且有意义的报文（`<SnapShotList/>`）—— 表示整批全部失败，
     *   标准明确允许这样表达。超过 10 条时**截断到 10**：`maxOccurs="10"` 是 schema 硬约束，
     *   发第 11 条会让严格校验的对端判整条报文非法，比丢标记更糟。
     */
    fun build(
        deviceId: String,
        sn: String,
        sessionId: String,
        fileIds: List<String>,
    ): String {
        val capped = fileIds.take(MAX_FILE_IDS)
        val listBlock = if (capped.isEmpty()) {
            "<SnapShotList/>"
        } else {
            "<SnapShotList>\n" +
                capped.joinToString("\n") { "<SnapShotFileID>${escapeXmlText(it)}</SnapShotFileID>" } +
                "\n</SnapShotList>"
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>$CMD_TYPE</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<SessionID>$sessionId</SessionID>
$listBlock
</Notify>
""".replace("\n", "\r\n")
    }

    /**
     * 兼容形态（**自有扩展，标准里没有这两个 CmdType**，只为旧平台保留）:
     * 每张图一条，带 `SnapShotID` / `Time` / `StoragePath`。
     *
     * ⛔ 不要再往这条路径上加能力：它存在的唯一理由是"某些老客户端只看 `CmdType=SnapShot`"。
     * 新接入一律用 [build]。
     */
    fun buildLegacy(
        deviceId: String,
        sn: String,
        sessionId: String,
        snapShotId: String,
        timeIso: String,
        storagePath: String
    ): String = ("""<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>SnapShot</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<SessionID>$sessionId</SessionID>
<SnapShotID>$snapShotId</SnapShotID>
<Time>$timeIso</Time>
<StoragePath>$storagePath</StoragePath>
</Notify>
""").replace("\n", "\r\n")

    /** A.2.5.7 `SnapShotFileID` 的 `maxOccurs`。 */
    const val MAX_FILE_IDS = 10
}
