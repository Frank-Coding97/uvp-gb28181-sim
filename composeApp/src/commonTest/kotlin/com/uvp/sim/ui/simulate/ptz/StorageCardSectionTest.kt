package com.uvp.sim.ui.simulate.ptz

import com.uvp.sim.domain.VirtualStorageCards
import com.uvp.sim.ui.model.StorageCardDto
import com.uvp.sim.ui.model.StorageCardReadingDto
import com.uvp.sim.ui.model.StorageCardStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「存储卡」区块的纯函数部分。
 *
 * 这里锁两件事:
 *  1. **语言口径** —— 同一次查询的读数该怎么翻译成人看得懂的话,以及
 *     「没查过 / 查了但没卡 / 有卡」三态不能混;
 *  2. **高度预算** —— 本区块挂在 HUD 状态页的最下面,而那一页是定高 284dp、**不滚动**的,
 *     超出来的行是被**静默裁掉**的(见 [StorageCardSection] 文件头那道加法)。
 *     渲染(颜色、动效)不在这里测。
 */
class StorageCardSectionTest {

    private fun card(id: Int, name: String = "卡$id", capacityMb: Int = 65_536) =
        StorageCardDto(id = id, name = name, capacityMb = capacityMb)

    private fun reading(
        cardId: Int,
        status: StorageCardStatusDto,
        progress: Int? = null,
        freeMb: Int = 0,
    ) = StorageCardReadingDto(cardId = cardId, status = status, progress = progress, freeMb = freeMb)

    private fun uiOf(cards: List<StorageCardDto>, readings: Map<Int, StorageCardReadingDto>, queryCount: Int = 1) =
        storageCardSectionUi(cards = cards, readings = readings, queryCount = queryCount)

    @Test
    fun not_queried_shows_no_capacity_before_the_platform_asks() {
        val ui = uiOf(cards = listOf(card(1)), readings = emptyMap(), queryCount = 0)

        // 平台还没问过 —— 设备屏幕上一个数字都不许摆出来。
        assertEquals(StorageCardSectionUi.NotQueried, ui)
    }

    @Test
    fun queried_without_cards_is_no_card_not_not_queried() {
        val ui = uiOf(cards = emptyList(), readings = emptyMap(), queryCount = 1)

        // 这两态在 cards 上都表现为空,但设备要说的话完全不同。
        assertEquals(StorageCardSectionUi.NoCard, ui)
    }

    @Test
    fun ok_row_reports_capacity_and_free_space() {
        val ui = uiOf(
            cards = listOf(card(1, name = "卡1", capacityMb = 65_536)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Ok, freeMb = 32_768)),
        ) as StorageCardSectionUi.Cards

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
        val withProgress = uiOf(
            cards = listOf(card(1)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Formatting, progress = 45)),
        ) as StorageCardSectionUi.Cards
        assertEquals("进度 45%", withProgress.rows.single().detailText)

        val withoutProgress = uiOf(
            cards = listOf(card(1)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Formatting, progress = null)),
        ) as StorageCardSectionUi.Cards
        // FormatProgress 协议上是 minOccurs=0,缺了不能补一个 0%(那看着像格式化没启动)。
        assertEquals("—", withoutProgress.rows.single().detailText)
    }

    @Test
    fun detail_column_never_repeats_the_status_word() {
        // ⛔ 单行表格里"读数"与"状态"是并排两列,两列说同一句话会读成
        // `未格式化 未格式化` / `读写异常 读写异常` —— 卡片时代那是上下两行,看不出来。
        val statuses = listOf(
            StorageCardStatusDto.Ok, StorageCardStatusDto.Formatting,
            StorageCardStatusDto.Unformatted, StorageCardStatusDto.Idle,
            StorageCardStatusDto.Error,
        )
        statuses.forEach { status ->
            val row = (uiOf(
                cards = listOf(card(1)),
                readings = mapOf(1 to reading(1, status, progress = 45, freeMb = 1_024)),
            ) as StorageCardSectionUi.Cards).rows.single()
            assertFalse(
                row.detailText.contains(row.statusText),
                "$status 的读数列(\"${row.detailText}\")把状态列(\"${row.statusText}\")又说了一遍",
            )
        }
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
            val ui = uiOf(
                cards = listOf(card(1)),
                readings = mapOf(1 to reading(1, status, freeMb = 1_024)),
            ) as StorageCardSectionUi.Cards
            assertEquals(text, ui.rows.single().statusText, status.name)
        }
    }

    @Test
    fun error_row_does_not_dress_up_zero_free_space_as_remaining_capacity() {
        val ui = uiOf(
            cards = listOf(card(1)),
            readings = mapOf(1 to reading(1, StorageCardStatusDto.Error, freeMb = 0)),
        ) as StorageCardSectionUi.Cards

        // ⛔ 异常态没有可信的"剩余"可言:不许把 0 剩余写成「剩余 0MB」(那像"卡是空的"),
        // 状态由右边那一列说。
        val row = ui.rows.single()
        assertEquals("—", row.detailText)
        assertFalse(row.detailText.contains("剩余"), "异常态不该报剩余空间")
        assertEquals("读写异常", row.statusText)
    }

    @Test
    fun a_card_without_a_reading_degrades_to_no_reading() {
        val ui = uiOf(cards = listOf(card(1)), readings = emptyMap(), queryCount = 2)
            as StorageCardSectionUi.Cards

        // 理论上不该发生(清单与读数同一次写入),但发生了要说「无读数」而不是假装正常。
        val row = ui.rows.single()
        assertNull(row.status)
        assertEquals("无读数", row.statusText)
        assertEquals("—", row.detailText)
    }

    @Test
    fun extra_cards_collapse_into_the_header_not_into_another_line() {
        val cards = (1..5).map { card(it) }
        val readings = cards.associate { it.id to reading(it.id, StorageCardStatusDto.Ok, freeMb = 1_024) }

        val ui = uiOf(cards = cards, readings = readings) as StorageCardSectionUi.Cards

        // 协议允许 8 张,而状态页只放得下 MAX_VISIBLE_STORAGE_CARDS 行 —— 余量走**标题行右侧**。
        // ⛔ 余量用常量算,别写死 3:写死就是把 MAX_VISIBLE_STORAGE_CARDS 的取值抄了第二遍,
        // 改上限时这条会以"跟它无关"的理由变红(变异自检抓过一次)。
        assertEquals(MAX_VISIBLE_STORAGE_CARDS, ui.rows.size)
        assertEquals(5 - MAX_VISIBLE_STORAGE_CARDS, ui.overflowCount)
        // ⛔ 关键:余量**不新开一行**。它要是偷偷加了第 3 行,第 3 行会被 HUD 裁掉。
        assertEquals(MAX_VISIBLE_STORAGE_CARDS, storageCardSectionLineCount(ui))
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

    @Test
    fun worst_case_card_count_still_fits_the_hud_line_budget() {
        // 最坏情况:协议允许的 8 张卡全在(A.2.6.16 的 Item 是 maxOccurs=8)。
        val cards = (1..VirtualStorageCards.MAX_CARDS).map { card(it) }
        val readings = cards.associate { it.id to reading(it.id, StorageCardStatusDto.Ok, freeMb = 1_024) }

        val ui = uiOf(cards = cards, readings = readings)

        assertTrue(
            storageCardSectionLineCount(ui) <= STORAGE_CARD_SECTION_MAX_LINES,
            "本区块要占 ${storageCardSectionLineCount(ui)} 行,超过 HUD 状态页的 " +
                "$STORAGE_CARD_SECTION_MAX_LINES 行预算 —— 多出来的行会被静默裁掉" +
                "(见 StorageCardSection 文件头那道加法)",
        )
    }

    @Test
    fun line_budget_constant_matches_the_visible_card_cap() {
        // ⛔ 这两者必须相等:摊开的卡一行一张,余量提示不占行。
        // 有人调大 MAX_VISIBLE_STORAGE_CARDS 却没想到高度预算时,这条会红。
        assertEquals(
            MAX_VISIBLE_STORAGE_CARDS,
            STORAGE_CARD_SECTION_MAX_LINES,
            "行数上限与可摊开卡数脱钩了 —— 先回去算 StorageCardSection 文件头那道加法",
        )
    }
}
