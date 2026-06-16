package com.uvp.sim.config

import com.uvp.sim.camera.CameraFacing
import com.uvp.sim.domain.CatalogTreeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 双真实通道(前置/后置)— DeviceConfig 字段 + facing 映射 + defaultTree。
 * spec/plan/tasks: dual-camera-channel。
 */
class DualChannelConfigTest {

    private fun device(frontId: String = "35020000001320000002") = DeviceConfig(
        deviceId = "35020000001310000001",
        videoChannelId = "35020000001320000001",
        alarmChannelId = "35020000001340000001",
        username = "35020000001310000001",
        password = "p",
        frontChannelId = frontId
    )

    private fun cfg(frontId: String = "35020000001320000002") = SimConfig(
        server = ServerConfig(ip = "1.1.1.1", serverId = "s", domain = "3502000000"),
        device = device(frontId)
    )

    @Test
    fun deviceConfigHasDualChannelDefaults() {
        val d = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = "u",
            password = "p"
        )
        assertEquals("", d.frontChannelId, "前置通道 ID 默认空(运行期注入)")
        assertEquals("前置摄像头", d.frontChannelName)
        assertEquals("后置摄像头", d.videoChannelName)
    }

    // ---- T2: facingForChannel ----

    @Test
    fun frontChannelMapsToFront() {
        assertEquals(CameraFacing.FRONT, device().facingForChannel("35020000001320000002"))
    }

    @Test
    fun backChannelMapsToBack() {
        assertEquals(CameraFacing.BACK, device().facingForChannel("35020000001320000001"))
    }

    @Test
    fun unknownChannelMapsToBack() {
        assertEquals(CameraFacing.BACK, device().facingForChannel("99999999991329999999"))
    }

    @Test
    fun emptyFrontIdNeverMatchesEmptyChannel() {
        // frontChannelId 为空 + 传空 channelId,绝不能误命中 FRONT
        assertEquals(CameraFacing.BACK, device(frontId = "").facingForChannel(""))
    }

    // ---- T3: defaultTree ----

    @Test
    fun defaultTreeHasFourNodesWhenFrontIdPresent() {
        val tree = CatalogTreeStore.defaultTree(cfg())
        assertEquals(4, tree.size, "根 + 前置 + 后置 + 报警")
        val videos = tree.filter { it.type == CatalogNodeType.VideoChannel }
        assertEquals(2, videos.size)
        assertTrue(videos.any { it.id == "35020000001320000002" && it.name == "前置摄像头" })
        assertTrue(videos.any { it.id == "35020000001320000001" && it.name == "后置摄像头" })
        assertTrue(tree.all { it.parentId == "35020000001310000001" || it.id == it.parentId })
    }

    @Test
    fun defaultTreeFallsBackToSingleVideoWhenFrontIdBlank() {
        val tree = CatalogTreeStore.defaultTree(cfg(frontId = ""))
        assertEquals(3, tree.size, "根 + 后置 + 报警(无空 ID 节点)")
        assertTrue(tree.none { it.id.isBlank() }, "绝不生成空 ID 节点")
        assertEquals(1, tree.count { it.type == CatalogNodeType.VideoChannel })
    }

    @Test
    fun defaultDualTreeIsValid() {
        assertTrue(CatalogTreeStore.validate(CatalogTreeStore.defaultTree(cfg())).isOk)
    }
}

