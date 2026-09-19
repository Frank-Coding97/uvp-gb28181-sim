package com.uvp.sim.ui.simulate

import com.uvp.sim.ui.model.StorageCardDto
import com.uvp.sim.ui.model.StorageCardReadingDto
import com.uvp.sim.ui.model.StorageCardStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「存储卡」OSD 卡片的纯函数部分。
 *
 * 这里锁的是**语言口径**:同一次查询的读数该怎么翻译成人看得懂的话,以及
 * 「没查过 / 查了但没卡 / 有卡」三态不能混。渲染(颜色、动效)不在这里测。
 */
class StorageCardPanelUiTest {

    private fun card(id: Int, name: String = "卡$id", capacityMb: Int = 65_536) =
        StorageCardDto(id = id, name = name, capacityMb = capacityMb)

    private fun reading(
        cardId: Int,
        status: StorageCardStatusDto,
        progress: Int? = null,
        freeMb: Int = 0,
    ) = StorageCardReadingDto(cardId = cardId, status = status, progress = progress, freeMb = freeMb)

    @Test
    fun not_queried_shows_no_capacity_before_the_platform_asks() {
        val ui = storageCardPanelUi(cards = listOf(card(1)), readings = emptyMap(), queryCount = 0)

        // 平台还没问过 —— 设备屏幕上一个数字都不许摆出来。
        assertEquals(StorageCardPanelUi.NotQueried, ui)
    }

    @Test
    fun queried_without_cards_is_no_card_not_not_queried() {
        val ui = storageCardPanelUi(cards = emptyList(), readings = emptyMap(), queryCount = 1)

        // 这两态在 cards 上都表现为空,但设备要说的话完全不同。
        assertEquals(StorageCardPanelUi.NoCard, ui)
    }

    @Test
    fun ok_row_reports_capacity_and_free_space() {
        val ui = storageCardPanelUi(
            cards = listOf(card(1, name = "卡1", capacityMb = 65_536)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Ok, freeMb = 32_768)),
            queryCount = 1,
        ) as StorageCardPanelUi.Cards

        assertEquals(1, ui.rows.size)
        assertEquals(0, ui.overflowCount)
        val row = ui.rows.single()
        assertEquals("卡1", row.name)
        assertEquals(StorageCardStatusDto.Ok, row.status)
        assertEquals("64.0GB", row.capacityText)
        assertEquals("剩余 32.0GB", row.detailText)
        assertEquals("正常", row.statusText)
    }

    @Test
    fun formatting_row_only_prints_progress_when_the_report_carried_one() {
        val withProgress = storageCardPanelUi(
            cards = listOf(card(1)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Formatting, progress = 45)),
            queryCount = 1,
        ) as StorageCardPanelUi.Cards
        assertEquals("格式化中 45%", withProgress.rows.single().detailText)

        val withoutProgress = storageCardPanelUi(
            cards = listOf(card(1)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Formatting, progress = null)),
            queryCount = 1,
        ) as StorageCardPanelUi.Cards
        // FormatProgress 协议上是 minOccurs=0,缺了不能补一个 0%(那看着像格式化没启动)。
        assertEquals("格式化中", withoutProgress.rows.single().detailText)
    }

    @Test
    fun every_status_has_its_own_wording() {
        val expected = mapOf(
            StorageCardStatusDto.Ok to "正常",
            StorageCardStatusDto.Formatting to "格式化中",
            StorageCardStatusDto.Unformatted to "未格式化",
            StorageCardStatusDto.Idle to "空闲",
            StorageCardStatusDto.Error to "读写异常",
        )

        expected.forEach { (status, text) ->
            val ui = storageCardPanelUi(
                cards = listOf(card(1)),
                readings = mapOf(1 to reading(1, status, freeMb = 1_024)),
                queryCount = 1,
            ) as StorageCardPanelUi.Cards
            assertEquals(text, ui.rows.single().statusText, status.name)
        }
    }

    @Test
    fun error_row_does_not_dress_up_zero_free_space_as_remaining_capacity() {
        val ui = storageCardPanelUi(
            cards = listOf(card(1)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Error, freeMb = 0)),
            queryCount = 1,
        ) as StorageCardPanelUi.Cards

        assertEquals("读写异常", ui.rows.single().detailText)
    }

    @Test
    fun a_card_without_a_reading_degrades_to_no_reading() {
        val ui = storageCardPanelUi(
            cards = listOf(card(1)),
            readings = emptyMap(),
            queryCount = 2,
        ) as StorageCardPanelUi.Cards

        // 理论上不该发生(清单与读数同一次写入),但发生了要说「无读数」而不是假装正常。
        val row = ui.rows.single()
        assertNull(row.status)
        assertEquals("无读数", row.statusText)
        assertEquals("—", row.detailText)
    }

    @Test
    fun extra_cards_collapse_into_an_overflow_hint() {
        val cards = (1..5).map { card(it) }
        val readings = cards.associate { it.id to reading(it.id, StorageCardStatusDto.Ok, freeMb = 1_024) }

        val ui = storageCardPanelUi(cards = cards, readings = readings, queryCount = 1) as StorageCardPanelUi.Cards

        // 协议允许 8 张,全平铺会把 3D 画面糊掉 —— 只摊前两张,余量走提示。
        assertEquals(MAX_VISIBLE_STORAGE_CARDS, ui.rows.size)
        assertEquals(3, ui.overflowCount)
    }

    @Test
    fun capacity_switches_from_mb_to_gb_at_one_gib() {
        assertEquals("0MB", formatCapacityMb(0))
        assertEquals("512MB", formatCapacityMb(512))
        assertEquals("1023MB", formatCapacityMb(1_023))
        assertEquals("1.0GB", formatCapacityMb(1_024))
        assertEquals("8.0GB", formatCapacityMb(8_192))
        assertEquals("64.0GB", formatCapacityMb(65_536))
        assertEquals("128.0GB", formatCapacityMb(131_072))
    }
}
