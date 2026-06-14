package com.uvp.sim.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTreeTest {

    @Test
    fun `CatalogNodeType typeCode and parental are correct`() {
        assertEquals("111", CatalogNodeType.Device.typeCode)
        assertEquals(1, CatalogNodeType.Device.parental)

        assertEquals("137", CatalogNodeType.BusinessGroup.typeCode)
        assertEquals(1, CatalogNodeType.BusinessGroup.parental)

        assertEquals("138", CatalogNodeType.VirtualOrg.typeCode)
        assertEquals(1, CatalogNodeType.VirtualOrg.parental)

        assertEquals("132", CatalogNodeType.VideoChannel.typeCode)
        assertEquals(0, CatalogNodeType.VideoChannel.parental)

        assertEquals("134", CatalogNodeType.AlarmChannel.typeCode)
        assertEquals(0, CatalogNodeType.AlarmChannel.parental)
    }

    @Test
    fun `CatalogNode serialization round-trip preserves all fields`() {
        val original = CatalogNode(
            id = "34020000001320000001",
            type = CatalogNodeType.VideoChannel,
            name = "门口摄像头",
            parentId = "34020000000111000001",
            fields = mapOf(
                "Manufacturer" to "Hikvision",
                "Model" to "DS-2CD",
                "Status" to "ON"
            )
        )
        val json = Json.encodeToString(CatalogNode.serializer(), original)
        val restored = Json.decodeFromString(CatalogNode.serializer(), json)
        assertEquals(original, restored)
    }

    @Test
    fun `CatalogNode copy preserves immutability`() {
        val n = CatalogNode(
            id = "id1",
            type = CatalogNodeType.VideoChannel,
            name = "before",
            parentId = "parent1"
        )
        val copy = n.copy(name = "after")
        assertEquals("before", n.name)
        assertEquals("after", copy.name)
        assertEquals("id1", copy.id)
    }

    @Test
    fun `default fields is empty map`() {
        val n = CatalogNode("id", CatalogNodeType.Device, "n", "id")
        assertTrue(n.fields.isEmpty())
    }
}
