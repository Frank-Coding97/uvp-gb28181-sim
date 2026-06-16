package com.uvp.sim.config

import kotlinx.serialization.Serializable

/**
 * 三层 OSD 视频叠加配置 — 跟海康 / 大华 IPC 行业惯例对齐。
 *
 * - [timestamp]:时间戳层(默认 ON,文本由运行期 OsdTickerSource 实时填)
 * - [channelName]:通道名层(默认 OFF,文本来自 [text])
 * - [watermark]:自定义水印层(默认 OFF,文本来自 [text])
 *
 * 序列化后挂在 [SimConfig.osd]。详见 specs/osd-overlay.md。
 */
@Serializable
data class OsdConfig(
    val timestamp: OsdLayer = OsdLayer(
        enabled = true,
        text = "",
        position = OsdPosition.TOP_RIGHT,
        size = OsdSize.MEDIUM,
        fillColor = "#FFFFFF",
        outlineColor = "#000000"
    ),
    val channelName: OsdLayer = OsdLayer(
        enabled = false,
        text = "",
        position = OsdPosition.BOTTOM_LEFT,
        size = OsdSize.MEDIUM,
        fillColor = "#FFFFFF",
        outlineColor = "#000000"
    ),
    val watermark: OsdLayer = OsdLayer(
        enabled = false,
        text = "",
        position = OsdPosition.BOTTOM_RIGHT,
        size = OsdSize.MEDIUM,
        fillColor = "#FFFFFF",
        outlineColor = "#000000"
    )
)

/**
 * 单层 OSD 配置。时间戳层的 [text] 字段不使用,文本由运行期 ticker 注入。
 *
 * [fillColor] / [outlineColor] 为 `#RRGGBB` 字符串,渲染层负责解析。
 */
@Serializable
data class OsdLayer(
    val enabled: Boolean,
    val text: String,
    val position: OsdPosition,
    val size: OsdSize,
    val fillColor: String,
    val outlineColor: String
)

/** 5 个锚点 — 行业惯例的四角 + 居中。 */
@Serializable
enum class OsdPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

/** 字号档位 — 实际像素值由渲染层根据分辨率换算。 */
@Serializable
enum class OsdSize { SMALL, MEDIUM, LARGE }
