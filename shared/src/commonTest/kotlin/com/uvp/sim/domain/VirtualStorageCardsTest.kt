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

    // ===================== A.2.3.1.13 存储卡格式化(本类唯一的写操作) =====================

    /**
     * 受控时钟 —— 格式化进度是**时间的函数**,单测把时间推过去,不用 `delay`。
     *
     * ⛔ 不能用真实时钟跑这条:那需要 `delay(FORMAT_DURATION_MS)`(当前 35s),而慢测试最后一定会被谁调大
     *    `FORMAT_DURATION_MS` 或干脆删掉。注入 `nowMs` 就是为了让"到点收工"可被确定性验证。
     */
    private class FakeClock(var value: Long = 1_700_000_000_000L) {
        fun advance(ms: Long) { value += ms }
    }

    private fun cardsWithClock(seed: Int, clock: FakeClock) =
        VirtualStorageCards(Random(seed), nowMs = { clock.value })

    /** 找一个至少 [n] 张卡的 seed —— 多卡场景才测得出"只格式化点的那一张"。 */
    private fun seedWithAtLeastCards(n: Int): Int =
        (1..500).first { VirtualStorageCards(Random(it)).cards.size >= n }

    /** 找一个真的一张卡都没有的 seed(张数生成里 0 是合法且必可达的)。 */
    private fun seedWithoutCards(): Int =
        (1..500).first { VirtualStorageCards(Random(it)).cards.isEmpty() }

    /**
     * `cardIndex=0` → **全部卡**进入 `formatting`,且各自带 0-99 的进度。
     *
     * ⛔ 这条同时钉住"元素值是卡号、0 是全部卡"这条标准语义:若哪天有人把 0 改成
     *    "什么都不做",平台点「格式化全部卡」会静默变成空操作。
     */
    @Test
    fun format_zero_puts_every_card_into_formatting() {
        val clock = FakeClock()
        val src = cardsWithClock(seedWithAtLeastCards(1), clock)
        val allIds = src.cards.map { it.id }

        assertEquals(StorageCardFormatOutcome.Accepted(allIds), src.format(0))

        val readings = src.read()
        assertEquals(allIds, readings.map { it.cardId })
        readings.forEach { reading ->
            assertEquals(
                StorageCardStatus.Formatting, reading.status,
                "cardId=${reading.cardId} 刚被格式化却没进 formatting —— 平台观察窗会看到「什么都没发生」",
            )
            val progress = assertNotNull(reading.progress, "cardId=${reading.cardId} 格式化中必须带进度")
            assertTrue(progress in 0..99, "进度越界会让平台判 malformed: $progress")
        }
        assertTrue(src.isFormatting())
    }

    /** 指定卡号 → 只动那一张;别张不能被"顺手格式化"掉(那是不可逆的数据丢失)。 */
    @Test
    fun format_specific_card_does_not_touch_the_others() {
        val clock = FakeClock()
        val src = cardsWithClock(seedWithAtLeastCards(2), clock)
        val target = src.cards[1].id
        val capacityOf = src.cards.associate { it.id to it.capacityMb }

        assertEquals(StorageCardFormatOutcome.Accepted(listOf(target)), src.format(target))
        assertEquals(
            StorageCardStatus.Formatting,
            src.read().first { it.cardId == target }.status,
        )

        clock.advance(VirtualStorageCards.FORMAT_DURATION_MS)
        src.read().forEach { reading ->
            val capacity = capacityOf.getValue(reading.cardId)
            if (reading.cardId == target) {
                assertEquals(StorageCardStatus.Ok, reading.status)
                assertEquals(capacity, reading.freeMb, "格式化完应当是空盘")
            } else {
                // 随机读数分支里 freeMb 恒 < capacity(用率至少 5%),所以这条是确定性的。
                assertTrue(
                    reading.freeMb < capacity,
                    "cardId=${reading.cardId} 没被点却成了空盘 —— 误格式化了别的卡: $reading",
                )
            }
        }
    }

    /** 进度只前进、不倒退,且 `formatting` 期间**永不报 100**(100 意味着已完成,是自相矛盾的读数)。 */
    @Test
    fun format_progress_is_monotonic_and_stays_below_100() {
        val clock = FakeClock()
        val src = cardsWithClock(seedWithAtLeastCards(1), clock)
        val target = src.cards.first().id
        src.format(target)

        val step = VirtualStorageCards.FORMAT_DURATION_MS / 15
        val samples = buildList {
            repeat(15) {
                add(src.read().first { it.cardId == target })
                clock.advance(step) // 第 15 次采样后才会越界,采样点本身都在会话内
            }
        }
        assertTrue(samples.all { it.status == StorageCardStatus.Formatting })
        val progresses = samples.map { assertNotNull(it.progress, "格式化中必须带进度") }
        assertTrue(progresses.all { it in 0..99 }, "进度越界: $progresses")
        assertTrue(progresses.all { it < 100 }, "formatting 报 100 是自相矛盾的读数: $progresses")
        assertEquals(progresses.sorted(), progresses, "进度只能前进: $progresses")
        assertTrue(progresses.last() > progresses.first(), "15 次采样一点没涨,等于没在模拟进度")
    }

    /**
     * 到点后落到 `ok` + 剩余=容量,并且**此后一直是空盘**。
     *
     * ⛔ "一直"是重点:完成后若又落回随机分支,平台观察窗里剩余空间会从满值掉回去
     * —— 一次成功的格式化看起来反而像没生效([VirtualStorageCards] 里 `formattedCardIds` 的存在理由)。
     */
    @Test
    fun format_result_is_sticky_after_completion() {
        val clock = FakeClock()
        val src = cardsWithClock(seedWithAtLeastCards(1), clock)
        val target = src.cards.first()
        val capacity = target.capacityMb

        src.format(target.id)
        clock.advance(VirtualStorageCards.FORMAT_DURATION_MS)

        repeat(20) {
            clock.advance(3_600_000) // 每次隔一小时,确保不是"靠同一次调用侥幸"
            val reading = src.read().first { it.cardId == target.id }
            assertEquals(StorageCardStatus.Ok, reading.status, "第 $it 次复查")
            assertEquals(capacity, reading.freeMb, "第 $it 次复查:剩余空间掉回去了")
            assertNull(reading.progress, "不在格式化中就不该带进度")
        }
        assertTrue(!src.isFormatting(), "完成后不该还报在格式化")
    }

    /** 重新格式化一张已完成的卡 → 重新进入 `formatting`(而不是被"已完成"覆盖挡住)。 */
    @Test
    fun re_formatting_a_finished_card_starts_a_new_session() {
        val clock = FakeClock()
        val src = cardsWithClock(seedWithAtLeastCards(1), clock)
        val target = src.cards.first().id

        src.format(target)
        clock.advance(VirtualStorageCards.FORMAT_DURATION_MS)
        assertEquals(StorageCardStatus.Ok, src.read().first { it.cardId == target }.status)

        assertEquals(StorageCardFormatOutcome.Accepted(listOf(target)), src.format(target))
        val again = src.read().first { it.cardId == target }
        assertEquals(StorageCardStatus.Formatting, again.status, "第二次格式化必须重新进入格式化态")
        assertNotNull(again.progress)
    }

    /** 越界 / 负数一律拒绝,**且一张卡都不能被动到**。 */
    @Test
    fun format_rejects_invalid_index_without_touching_any_card() {
        val clock = FakeClock()
        val src = cardsWithClock(seedWithAtLeastCards(1), clock)
        val count = src.cards.size

        listOf(-1, Int.MIN_VALUE, count + 1, count + 99).forEach { bogus ->
            val outcome = src.format(bogus)
            assertTrue(
                outcome is StorageCardFormatOutcome.Rejected,
                "cardIndex=$bogus 越界就该拒绝,实际 $outcome —— 就近降级会格式化错卡",
            )
        }
        assertTrue(!src.isFormatting(), "被拒绝的请求不该开出格式化会话")

        clock.advance(VirtualStorageCards.FORMAT_DURATION_MS)
        src.read().forEach { reading ->
            val capacity = src.cards.first { it.id == reading.cardId }.capacityMb
            assertTrue(
                reading.freeMb < capacity,
                "cardId=${reading.cardId} 成了空盘 —— 越界请求不该有任何副作用: $reading",
            )
        }
    }

    /** 设备没插卡时格式化请求被拒绝 —— 而不是"受理一个没有目标的会话"。 */
    @Test
    fun format_is_rejected_when_device_has_no_card() {
        val src = VirtualStorageCards(Random(seedWithoutCards()))
        assertEquals(emptyList(), src.cards)

        val outcome = src.format(0)
        assertTrue(outcome is StorageCardFormatOutcome.Rejected, "没插卡却受理了: $outcome")
        assertTrue(!src.isFormatting())
        assertEquals(emptyList(), src.read())
    }
}
