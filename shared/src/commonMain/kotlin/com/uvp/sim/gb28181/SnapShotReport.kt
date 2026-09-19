package com.uvp.sim.gb28181

import kotlinx.serialization.Serializable

/**
 * GB/T 28181-2022 **A.2.1.24 图像抓拍配置类型**（`snapShotCfgType`）在设备侧的当前取值。
 *
 * 字段（抄自原文）：
 *   - [snapNum] `SnapNum` 连拍张数（必选，1~10，手动抓拍时为 1）
 *   - [intervalSeconds] `Interval` 单张抓拍间隔秒（可选，最短 1 秒）
 *   - [uploadUrl] `UploadURL` 抓拍图像上传路径（必选）
 *   - [sessionId] `SessionID` 平台生成的会话 ID，用于关联抓拍图像与平台请求（必选）
 *
 * ⛔⛔ **回读应答里的元素名是 `SnapShot`，不是 `SnapShotConfig`** ——
 * 这是本族里唯一「下发块名 ≠ 回读块名」的类型：
 *   - 下发命令（A.2.3.2.12）与查询请求的 `ConfigType` 取值 = `SnapShotConfig`
 *   - 回读应答（A.2.6.9）的元素名 = **`SnapShot`**（`type="tg:snapshotCfgType"`）
 * 回读时照下发块名写，对端**解不出来且不报错**，表现为"有应答无数据"。
 * 这个差异收在 [DeviceConfigBlock.responseElement] 一处，别在别处再写一遍字符串。
 *
 * ⭐ **口径（刻意的）**：回读报的是「**设备当前会按什么配置去抓拍**」，
 * 也就是与执行路径（`SnapshotUploadEngine`）**同一份值**。所以：
 *   - `SnapNum` 超出 1~10 时，执行侧会钳到边界，回读也报**钳位后的值** ——
 *     设备如实报"我实际会怎么做"，而不是"你让我做什么"。平台看到 20 → 回读 10
 *     正是"设备只支持到 10"的正确信号（判 mismatch 是应该的）。
 *   - [intervalSeconds] 为 null（= 平台没给 `Interval`）时回读**整个元素不出现**；
 *     不报 0 —— 标准的 `minInclusive=1`，报 0 是对端判协议非法的现成材料。
 */
@Serializable
data class SnapShotState(
    val snapNum: Int = 1,
    val intervalSeconds: Int? = null,
    val uploadUrl: String = "",
    val sessionId: String = "",
)

/**
 * `SnapShot` 的回读侧线格式与状态换算。
 *
 * ⛔ **从未被配置过时，不回这一块**（见 [snapshotOf] 返回 null 的分支）。
 * 理由：`UploadURL` 是**平台按会话下发的**，设备侧没有"出厂默认上传地址"这种东西；
 * 硬造一个（哪怕是空串）回给平台，比不回更危险 —— 那是一条设备根本没在执行的配置。
 * `SessionID` 同理，它是平台侧的会话凭据，不是设备的持久配置。
 * ⇒ 这是本族里唯一一个「设备可以诚实地什么都不回」的类型，别照其它类型的"回读永远有值"
 * 去硬凑一条（那条通则是为**有出厂默认**的类型立的）。
 */
object SnapShotReport {

    /** 从执行侧已解析的 [SnapShotConfig] 换算成可回读的状态。 */
    fun snapshotOf(config: SnapShotConfig?): SnapShotState? = config?.let {
        SnapShotState(
            snapNum = it.snapNum,
            intervalSeconds = (it.intervalMs / 1000L).takeIf { sec -> sec > 0L }?.toInt(),
            uploadUrl = it.uploadUrl,
            sessionId = it.sessionId,
        )
    }

    /**
     * 回读应答块。⛔ 块名用 [DeviceConfigBlock.SnapShot] 的 `responseElement`（`SnapShot`），
     * 元素顺序照 XSD：`SnapNum` → `Interval` → `UploadURL` → `SessionID`。
     */
    fun render(state: SnapShotState): String = buildString {
        append("<${DeviceConfigBlock.SnapShot.responseElement}>\n")
        append("<SnapNum>").append(state.snapNum).append("</SnapNum>\n")
        state.intervalSeconds?.let { append("<Interval>").append(it).append("</Interval>\n") }
        append("<UploadURL>").append(state.uploadUrl).append("</UploadURL>\n")
        append("<SessionID>").append(state.sessionId).append("</SessionID>\n")
        append("</${DeviceConfigBlock.SnapShot.responseElement}>\n")
    }
}
