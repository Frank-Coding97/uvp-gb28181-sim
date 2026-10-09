package com.uvp.sim.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotConfigTest {
    private fun config(snapshot: SnapshotConfig = SnapshotConfig()) = SimConfig(
        server = ServerConfig(
            ip = "192.168.10.106",
            serverId = "34020000002000000002",
            domain = "3402000000",
        ),
        device = DeviceConfig(
            deviceId = "37010301021180000007",
            videoChannelId = "37010301021320000007",
            alarmChannelId = "37010301021400000007",
            username = "37010301021180000007",
            password = "secret",
        ),
        snapshot = snapshot,
    )

    @Test
    fun empty_snapshot_allow_list_falls_back_to_sip_server_host() {
        assertEquals(listOf("192.168.10.106"), config().effectiveSnapshotUploadAllowList())
    }

    @Test
    fun explicit_snapshot_allow_list_is_preserved() {
        assertEquals(
            listOf("upload.example.com"),
            config(SnapshotConfig(listOf("upload.example.com"))).effectiveSnapshotUploadAllowList(),
        )
    }
}
