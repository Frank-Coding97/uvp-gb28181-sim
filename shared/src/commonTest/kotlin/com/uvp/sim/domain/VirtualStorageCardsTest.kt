package com.uvp.sim.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GB-2022 A.2.4.14 / A.2.6.16 设备侧虚拟存储卡 —— 不变量覆盖。
 *
 * 这些不变量是**报文的隐藏契约**:`SDCardStatus` 那几项在标准里是可选的/有值域的,
 * 违反了平台侧要么解析报错要么静默少读一条,而设备侧看不出来。放在这里锁住,比在
 * 报文用例里逐条断言好读 —— 报文用例只关心"字段名对不对"。
 */
class VirtualStorageCardsTest {

    /**
     * **物理属性**跨多次读取必须稳定。
     *
     * 现实含义:平台连点两次「刷新」不该看到两张不同的卡。每次重掷的话,排障时分不清
     * "设备真的换了卡"还是"模拟器又在掷骰子",而这两件事的处理方式完全相反。
     */
    @Test
    fun cards_are_stable_across_reads() {
        val source = VirtualStorageCards(Random(42))
        val first = source.cards
        repeat(20) { source.read() }
        assertEquals(first, source.cards, "读盘不该改变卡的物理属性")
        assertEquals(first.map { it.id }, source.cards.map { it.id })
    }

    /**
     * **读数**必须每次抖动 —— 否则"剩余空间"就是个常数,查询看起来生效了其实什么都没读。
     *
     * 用 20 次读取里出现多个不同的读数来判定。对 0 张卡的实例没有读数可抖,直接跳过
     * (那条分支由 [zero_cards_is_reachable] 覆盖)。
     */
    @Test
    fun readings_shake_between_calls() {
        val source = VirtualStorageCards(Random(7))
        if (source.cards.isEmpty()) return
        val readings = (1..20).map { source.read() }
        assertTrue(
            readings.distinct().size > 1,
            "剩余空间/状态应当每次重新抖,实际 20 次都是同一组: ${readings.first()}",
        )
    }

    /**
     * [StorageCardReading.progress] 只允许在 `formatting` 出现,且落在 0-100。
     *
     * ⛔ 两个方向都要断言:漏了"其余状态必须是 null"会让设备凭空报出"正在格式化 0%";
     * 漏了"formatting 必须有值"会让平台看不到进度(标准里它是可选的,平台不会报错)。
     */
    @Test
    fun format_progress_only_exists_while_formatting() {
        var sawFormatting = false
        for (seed in 1..60) {
            val source = VirtualStorageCards(Random(seed))
            source.read().forEach { reading ->
                if (reading.status == StorageCardStatus.Formatting) {
                    sawFormatting = true
                    val progress = assertNotNull(reading.progress, "seed=$seed 格式化中却没给进度")
                    assertTrue(progress in 0..100, "seed=$seed 进度越界: $progress")
                } else {
                    assertNull(
                        reading.progress,
                        "seed=$seed 状态 ${reading.status.wireValue} 不该带进度 —— 补 0 会被读成「正在格式化 0%」",
                    )
                }
            }
        }
        assertTrue(sawFormatting, "60 个 seed 都没掷出 formatting,这条用例就白跑了")
    }

    /** 剩余空间不得为负、也不得超过容量(平台侧越界会判 malformed,整包拒收)。 */
    @Test
    fun free_space_stays_within_capacity() {
        for (seed in 1..60) {
            val source = VirtualStorageCards(Random(seed))
            val capacityById = source.cards.associate { it.id to it.capacityMb }
            source.read().forEach { reading ->
                val capacity = capacityById.getValue(reading.cardId)
                assertTrue(reading.freeMb in 0..capacity, "seed=$seed read=${reading} capacity=$capacity")
            }
        }
    }

    /**
     * 卡号从 1 开始、连续不重复 —— 标准 `Item/ID` 是"SD 卡编号,从 1 开始"。
     *
     * ⛔ 平台侧按 ID 建唯一键(`gb_device_storage_card` 的 `(device_id, target_code, card_id)`
     * 三元组)。重号会让两张卡互相覆盖,现象是"查出来少一张卡",不是报错。
     */
    @Test
    fun card_ids_start_at_one_and_are_unique() {
        for (seed in 1..60) {
            val cards = VirtualStorageCards(Random(seed)).cards
            assertEquals(cards.map { it.id }, (1..cards.size).toList(), "seed=$seed")
        }
    }

    /**
     * 张数不得超过标准上限(`Item maxOccurs="8"`),且 **0 张必须可达**。
     *
     * 0 张是**合法结果**(`SumNum=0` 且不带 `SDCardStatusInfo`),平台侧那条分支只能靠
     * 真数据覆盖 —— 若哪天有人把生成逻辑改成"至少一张卡",这条会红,提醒他
     * "平台那条空分支从此没有真数据能测了"。
     */
    @Test
    fun card_count_within_standard_limit_and_zero_is_reachable() {
        val counts = (1..200).map { VirtualStorageCards(Random(it)).cards.size }
        assertTrue(counts.all { it <= VirtualStorageCards.MAX_CARDS }, "超发会让平台整包拒收: $counts")
        assertTrue(counts.any { it == 0 }, "0 张卡是合法结果,必须能掷出来")
        assertTrue(counts.any { it >= 2 }, "多卡场景也要能掷出来,否则多卡渲染没人覆盖")
    }
}
