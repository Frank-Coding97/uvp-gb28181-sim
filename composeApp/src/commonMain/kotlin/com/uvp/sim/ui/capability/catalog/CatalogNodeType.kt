package com.uvp.sim.ui.capability.catalog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.ui.UvpColor

/**
 * `CatalogNodeType` 的 UI 展示扩展(图标/配色/短标签/全称)。
 * 拆出来给 `capability/catalog/` 子包内多个文件复用。
 */
internal fun CatalogNodeType.shortLabel(): String = when (this) {
    CatalogNodeType.Device -> "设备"
    CatalogNodeType.BusinessGroup -> "分组"
    CatalogNodeType.VirtualOrg -> "区划"
    CatalogNodeType.VideoChannel -> "视频"
    CatalogNodeType.AlarmChannel -> "报警"
}

internal fun CatalogNodeType.icon(): ImageVector = when (this) {
    CatalogNodeType.Device -> Icons.Outlined.AccountTree
    CatalogNodeType.BusinessGroup -> Icons.Outlined.Folder
    CatalogNodeType.VirtualOrg -> Icons.Outlined.Folder
    CatalogNodeType.VideoChannel -> Icons.Outlined.Videocam
    CatalogNodeType.AlarmChannel -> Icons.Outlined.NotificationsActive
}

internal fun CatalogNodeType.color(): Color = when (this) {
    CatalogNodeType.Device -> UvpColor.Primary
    CatalogNodeType.BusinessGroup -> UvpColor.Info
    CatalogNodeType.VirtualOrg -> UvpColor.Info
    CatalogNodeType.VideoChannel -> UvpColor.Success
    CatalogNodeType.AlarmChannel -> UvpColor.Warning
}

internal fun CatalogNodeType.displayName(): String = when (this) {
    CatalogNodeType.Device -> "设备根"
    CatalogNodeType.BusinessGroup -> "业务分组(137)"
    CatalogNodeType.VirtualOrg -> "虚拟组织(138)"
    CatalogNodeType.VideoChannel -> "视频通道(132)"
    CatalogNodeType.AlarmChannel -> "报警通道(134)"
}
