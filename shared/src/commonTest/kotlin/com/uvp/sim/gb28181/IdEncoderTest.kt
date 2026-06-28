package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdEncoderTest {

    @Test
    fun `genChildId for VideoChannel produces 20 digits with type 132`() {
        val id = IdEncoder.genChildId("3402000000", CatalogNodeType.VideoChannel, 1)
        assertEquals("34020000001320000001", id)
        assertEquals(20, id.length)
    }

    @Test
    fun `genChildId for AlarmChannel produces type 134`() {
        val id = IdEncoder.genChildId("3402000000", CatalogNodeType.AlarmChannel, 1)
        assertEquals("34020000001340000001", id)
    }

    @Test
    fun `genChildId for BusinessGroup produces type 137`() {
        val id = IdEncoder.genChildId("3402000000", CatalogNodeType.BusinessGroup, 1)
        assertEquals("34020000001370000001", id)
    }

    @Test
    fun `genChildId for VirtualOrg produces type 138`() {
        val id = IdEncoder.genChildId("3402000000", CatalogNodeType.VirtualOrg, 1)
        assertEquals("34020000001380000001", id)
    }

    @Test
    fun `genChildId pads short domain to 10 digits prefix`() {
        val id = IdEncoder.genChildId("340", CatalogNodeType.VideoChannel, 1)
        assertEquals("34000000001320000001", id)
        assertEquals(20, id.length)
    }

    @Test
    fun `genChildId increments seq correctly`() {
        val id1 = IdEncoder.genChildId("3402000000", CatalogNodeType.VideoChannel, 5)
        assertEquals("34020000001320000005", id1)

        val id2 = IdEncoder.genChildId("3402000000", CatalogNodeType.VideoChannel, 1234567)
        assertEquals("34020000001321234567", id2)
    }

    @Test
    fun `parseTypeCode extracts type 3 digits`() {
        assertEquals("132", IdEncoder.parseTypeCode("34020000001320000001"))
        assertEquals("134", IdEncoder.parseTypeCode("34020000001340000005"))
        assertEquals("137", IdEncoder.parseTypeCode("34020000001370000001"))
        assertEquals("111", IdEncoder.parseTypeCode("34020000001110000001"))
    }

    @Test
    fun `parseTypeCode returns null for invalid length`() {
        assertNull(IdEncoder.parseTypeCode("123"))
        assertNull(IdEncoder.parseTypeCode(""))
    }

    @Test
    fun `isValidGbId accepts 20-digit numeric`() {
        assertEquals(true, IdEncoder.isValidGbId("34020000001320000001"))
        assertEquals(true, IdEncoder.isValidGbId("00000000000000000000"))
    }

    @Test
    fun `isValidGbId rejects wrong length or non-digit`() {
        assertEquals(false, IdEncoder.isValidGbId(""))
        assertEquals(false, IdEncoder.isValidGbId("12345678"))
        assertEquals(false, IdEncoder.isValidGbId("3402000000132000000")) // 19
        assertEquals(false, IdEncoder.isValidGbId("340200000013200000012")) // 21
        assertEquals(false, IdEncoder.isValidGbId("3402000000A320000001")) // letter
        assertEquals(false, IdEncoder.isValidGbId("3402000000 320000001")) // space
    }

    @Test
    fun `isValidGbDomain accepts 10-digit numeric`() {
        assertEquals(true, IdEncoder.isValidGbDomain("3402000000"))
        assertEquals(true, IdEncoder.isValidGbDomain("0000000000"))
    }

    @Test
    fun `isValidGbDomain rejects wrong length or non-digit`() {
        assertEquals(false, IdEncoder.isValidGbDomain(""))
        assertEquals(false, IdEncoder.isValidGbDomain("340200000")) // 9
        assertEquals(false, IdEncoder.isValidGbDomain("34020000000")) // 11
        assertEquals(false, IdEncoder.isValidGbDomain("3402a00000")) // letter
    }
}
