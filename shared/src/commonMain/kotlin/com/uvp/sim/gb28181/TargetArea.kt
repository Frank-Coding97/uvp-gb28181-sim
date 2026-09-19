package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 A.2.3.1.14 目标跟踪控制命令的 `TargetArea` —— 全景图片大小 + 框选区域坐标。
 *
 * ```xml
 * <TargetTrack>Auto|Manual|Stop</TargetTrack>
 * <DeviceID2>34020000001320000001</DeviceID2>          <!-- 全景通道ID，可选 -->
 * <TargetArea>                                          <!-- 手动跟踪时需要，可选 -->
 *   <Length>1920</Length>        <!-- 全景播放窗口长度像素值（必选） -->
 *   <Width>1080</Width>          <!-- 全景播放窗口宽度像素值（必选） -->
 *   <MidPointX>960</MidPointX>   <!-- 跟踪框中心横轴坐标（必选） -->
 *   <MidPointY>540</MidPointY>   <!-- 跟踪框中心纵轴坐标（必选） -->
 *   <LengthX>200</LengthX>       <!-- 跟踪框长度像素值（必选） -->
 *   <LengthY>100</LengthY>       <!-- 跟踪框宽度像素值（必选） -->
 * </TargetArea>
 * ```
 *
 * 标准原文（A.2.3.1.14 正文）：「由于平台与设备画面比例大小不同，需要进行比例关系转化。
 * 因此，平台应提供画面大小：播放窗口长度像素值和播放窗口宽度像素值。」
 * ⇒ 六个值必须**整组**收下：只拿到中心点而没有窗口尺寸，**做不了比例换算**。
 *
 * ⛔ 这六个元素与 `DragZoomIn`/`DragZoomOut` 的
 * `Length`/`Width`/`MidPointX`/`MidPointY`/`LengthX`/`LengthY` **元素名完全相同**，
 * 所以解析**必须先取 `<TargetArea>` 块、再在块内找** ——
 * 直接在整篇报文里 `tagValue("Length")` 会在两种命令同时出现时静默串值。
 *
 * ⭐ 2026-09-19 新增。此前 `handleTargetTrack` 解析的是 `<ObjectID>` + `<Speed>`，
 * 这两个名字在 2022 全书与 2016 附录 A **都是 0 命中**（`ObjectID` 实为把
 * `TargetTrack` 的取值 `Auto/Manual/Stop` 误读成"对象标识"）——
 * 于是平台按标准下发的框选坐标**全部被丢弃**，手动跟踪等于"收到了指令但没有目标"。
 */
data class TargetArea(
    val length: Int,
    val width: Int,
    val midPointX: Int,
    val midPointY: Int,
    val lengthX: Int,
    val lengthY: Int,
) {
    /** 日志用的一行描述（顺序与标准字段序一致）。 */
    fun describe(): String = "${length}x$width @($midPointX,$midPointY) ${lengthX}x$lengthY"

    companion object {
        /**
         * 从 `DeviceControl` 报文里解析 `<TargetArea>`。
         *
         * 六个子元素在 schema 里**全部必选**，所以缺一个就**整体返回 null**，
         * 绝不返回"半份坐标"：半份坐标会让上层以为框选生效了，比没有更糟
         * —— 现象会变成"跟踪框位置不对"而不是"没收到框选"，排查方向完全不同。
         */
        fun parse(xml: String): TargetArea? {
            val body = configBlockBody(xml, "TargetArea") ?: return null
            fun int(tag: String): Int? = ManscdpParser.tagValue(body, tag)?.toIntOrNull()
            return TargetArea(
                length = int("Length") ?: return null,
                width = int("Width") ?: return null,
                midPointX = int("MidPointX") ?: return null,
                midPointY = int("MidPointY") ?: return null,
                lengthX = int("LengthX") ?: return null,
                lengthY = int("LengthY") ?: return null,
            )
        }
    }
}
