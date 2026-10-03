package com.uvp.sim.domain

import kotlin.random.Random
import kotlin.time.Clock

/**
 * GB/T 28181-2022 附录 A.2.4.14 / A.2.6.16 —— 存储卡状态查询(设备侧的虚拟存储卡)。
 *
 * **手机上没有 SD 卡**,这里是纯 mock。但它不是"随手编个数"的 mock:
 *
 * - 报文口径由 [StorageCardStatus.wireValue] 钉死,取值域就是标准里那五个小写词。
 * - **单一真源**:同一个 [VirtualStorageCards] 实例同时喂两处 ——
 *   ① `DeviceControlSubRouter` 组装 A.2.6.16 应答报文;
 *   ② 模拟中心的「存储卡」卡片渲染。
 *   ⛔ 两处各掷各的骰子会造出最难查的那类问题:设备屏幕显示"2 张卡 32G",平台却收到
 *   "1 张卡 8G" —— 两边都"看起来正常",差异只在对着看的时候才暴露。
 *
 * 数据分层(与真实设备一致):
 *  - **物理属性**([StorageCard]:张数 / 盘名 / 容量)进程内只掷一次。真实设备的物理属性
 *    不会每次查询都变;每次重掷的话,平台连查两次会看到两张不同的卡,排障时无法区分
 *    "设备在变"与"模拟器在掷骰子"。
 *  - **读数**([StorageCardReading]:状态 / 格式化进度 / 剩余空间)每次读取重新抖,
 *    模拟真实的写入与格式化过程。
 *
 * ## 格式化(A.2.3.1.13)是本类**唯一的写操作**,也是"读数不是纯随机"的原因
 *
 * 平台下发 `<FormatSDCard>` 之后,设备必须**真的做点什么** —— 否则随后那次 `SDCardStatus`
 * 查询读回来的还是随机数:平台侧看不到 `formatting`、看不到 `FormatProgress`、剩余空间也
 * 不变,整条"下发 → 观察 → 确认"闭环在设备侧断掉。更糟的是它**看起来正常**(报文合法、
 * 数值合法),只能靠"卡明明是满格式化了却还显示用了 60%"这种对照才发现。
 *
 * 所以 [format] 会开一个**定时会话**:目标卡在 [FORMAT_DURATION_MS] 内报
 * `formatting` + 递增进度,到点后落到 `ok` + 剩余=容量。设备屏幕那张卡片读的是
 * 同一份读数,于是"平台看到的"与"屏幕上显示的"必然一致。
 */
class VirtualStorageCards(
    private val random: Random = Random.Default,
    /**
     * 墙上时间来源。注入而不是直接 `Clock.System.now()`:格式化进度是**时间的函数**,
     * 单测要能在不 `delay` 的情况下把时钟推到达标点(见 `VirtualStorageCardsTest`)。
     */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /**
     * 本机虚拟存储卡清单(**物理属性**)。首访时掷一次骰子后不再变。
     *
     * 用 `by lazy` 而不是构造期计算:构造发生在 Engine 装配阶段,那时掷骰子会把随机性
     * 提前暴露给"还没插卡就被读"的时序;而且 lazy 保证多次访问拿到的**是同一个 List 实例**,
     * `DeviceControlModel` 的 `equals` 也就不用每次比一堆内容。
     */
    val cards: List<StorageCard> by lazy { generateCards() }

    /** 当前正在跑的格式化会话。`null` = 没有卡在格式化。 */
    private var session: FormatSession? = null

    /**
     * 已经格式化完成的卡号 —— 一份**持久覆盖**。
     *
     * ⛔ 必须持久,不能"完成即忘":忘了的话下一次 [read] 又落回随机分支,平台观察窗里
     * 剩余空间会从满值掉回"用了 60%",一次成功的格式化看起来反而像没生效。
     * (真实设备重新录像也会慢慢占回去,但那是**另一条**业务路径,模拟器不为它造状态机。)
     */
    private val formattedCardIds = mutableSetOf<Int>()

    /** 一次格式化请求的进度来源:哪些卡 + 什么时候开始。 */
    private data class FormatSession(val targets: Set<Int>, val startedAtMs: Long)

    /**
     * 读一次盘 —— 每张卡重新抖出状态与剩余空间。
     *
     * 调用点只有两个(报文 + 卡片),它们必须**共用同一次调用的返回值**:见
     * `DeviceControlSubRouter.sendStorageCardStatusResponse` 里"先 read 一次,再分别喂报文和 UI"。
     *
     * ⛔ 本函数**有副作用**:它是格式化会话唯一的推进入口(到点收工、把目标卡转成"已格式化")。
     * 这不是"顺手做的",而是刻意的 —— 设备只有在**被读**的时候才有机会知道自己格式化完了,
     * 而这恰好与真实设备一致:格式化进度本来就是靠平台轮询 `SDCardStatus` 观察到的
     * (2022 全文里 SDCardStatus **只有**"查询 + 应答"这一对,没有主动上报那一半)。
     * 因此"平台不查就永远不推进"不是缺陷,是这条协议路径的固有形状。
     */
    fun read(): List<StorageCardReading> {
        val now = nowMs()
        val active = settleIfDone(now)
        return cards.map { card ->
            when {
                active != null && card.id in active.targets -> StorageCardReading(
                    cardId = card.id,
                    status = StorageCardStatus.Formatting,
                    progress = progressOf(now, active),
                    // 正在格式化时可用空间按 1/10 报(格式化会重建文件系统,剩余量本就不可信)。
                    freeMb = card.capacityMb / 10,
                )
                // 已格式化完的卡:固定报"空盘"。
                // ⛔ 不能落回 [readingOf] 的随机分支 —— 那样平台会看到剩余空间从满值**掉回去**,
                //    一次成功的格式化在观察窗里反而像"没生效"。
                card.id in formattedCardIds ->
                    StorageCardReading(card.id, StorageCardStatus.Ok, null, card.capacityMb)
                else -> readingOf(card)
            }
        }
    }

    /**
     * A.2.3.1.13 存储卡格式化 —— **本类唯一的写操作**。
     *
     * 标准(A.2.3.1.13,`<element name="FormatSDCard">` / `restriction base="integer"` /
     * `minInclusive=0`)规定:**元素值就是 SD 卡编号**(从 1 开始),**该值为 0 时对所有
     * 存储卡进行格式化**。所以 [cardIndex] 是"卡号",不是"要不要格式化"的开关。
     *
     * ⛔ **越界 / 非法一律拒绝,不做任何降级**:
     *  - 原本的调用方(`SystemHandler.handleFormatSDCard`)把"解析失败"回落成 `0`,
     *    而 `0` 在这里的语义是**格式化全部卡** —— 于是一条读不懂的报文会静默触发
     *    破坏性最大的一种操作。这条已在 handler 侧一并改正(解析失败不再转发到本函数)。
     *  - 卡号越界时**不能**"就近取一张":格式化错卡是不可逆的数据丢失。
     */
    fun format(cardIndex: Int): StorageCardFormatOutcome {
        val ids = cards.map { it.id }
        return when {
            ids.isEmpty() ->
                StorageCardFormatOutcome.Rejected("设备当前没有存储卡,忽略格式化请求")
            cardIndex < 0 ->
                StorageCardFormatOutcome.Rejected("卡号 $cardIndex 非法(标准 minInclusive=0,0 表示全部卡)")
            cardIndex == 0 -> {
                startSession(ids)
                StorageCardFormatOutcome.Accepted(ids)
            }
            cardIndex !in ids ->
                StorageCardFormatOutcome.Rejected("卡号 $cardIndex 越界(本机现有 ${ids.size} 张卡:$ids)")
            else -> {
                startSession(listOf(cardIndex))
                StorageCardFormatOutcome.Accepted(listOf(cardIndex))
            }
        }
    }

    /**
     * 当前是否有一张卡正在格式化 —— 供"格式化后要不要继续等"这类判断用。
     *
     * 注意它读的是**会话状态**,不推进、不产生副作用(与 [read] 相反),所以在 `read()` 之前
     * 调用不会把会话提前结算掉。
     */
    fun isFormatting(): Boolean = session?.let { nowMs() - it.startedAtMs < FORMAT_DURATION_MS } ?: false

    private fun startSession(targets: List<Int>) {
        // 重格式化一张已完成的卡:先撤掉"空盘"覆盖,否则它会与格式化态抢同一次 read。
        targets.forEach(formattedCardIds::remove)
        session = FormatSession(targets = targets.toSet(), startedAtMs = nowMs())
    }

    /**
     * 会话是否已达标 —— 达标则当场结算(目标卡转"已格式化")并返回 `null`。
     *
     * 结算点不在 `format()` 里定闹钟而在 `read()` 里算差值:本类是纯数据对象,没有协程作用域,
     * 而且**设备只有在被读时才知道自己格式化完了**这件事本身与协议一致(见 [read])。
     */
    private fun settleIfDone(now: Long): FormatSession? {
        val current = session ?: return null
        if (now - current.startedAtMs < FORMAT_DURATION_MS) return current
        formattedCardIds += current.targets
        session = null
        return null
    }

    /** 进度 = 已耗时占比,**上限 99**:`formatting` 却报 `100` 是自相矛盾的读数。 */
    private fun progressOf(now: Long, active: FormatSession): Int {
        val elapsed = (now - active.startedAtMs).coerceAtLeast(0L)
        return ((elapsed * 100) / FORMAT_DURATION_MS).toInt().coerceIn(0, 99)
    }

    /**
     * 张数随机 —— 手机模拟器"没插卡"是常见状态。
     *
     * ⛔ `0` 必须能出现:平台侧需要测到「`SumNum=0` 且**不带** `SDCardStatusInfo`」这条
     * **合法**分支(A.2.6.16 里 `SDCardStatusInfo` 是 `minOccurs=0`)。永远至少 1 张卡的话,
     * 那条分支在联调里就没有真数据能覆盖,只能靠单测。
     */
    private fun generateCards(): List<StorageCard> {
        val count = when (random.nextInt(6)) {
            0 -> 0
            1, 2, 3 -> 1
            else -> 2
        }
        return (1..count.coerceAtMost(MAX_CARDS)).map { index ->
            StorageCard(
                id = index,
                name = "SD Card $index",
                capacityMb = CAPACITIES_MB[random.nextInt(CAPACITIES_MB.size)],
            )
        }
    }

    private fun readingOf(card: StorageCard): StorageCardReading {
        // 真实设备绝大多数时间都是 ok,异常态只占少数(14%)。
        return when (random.nextInt(100)) {
            in 0..4 -> StorageCardReading(
                cardId = card.id,
                status = StorageCardStatus.Formatting,
                // 进度只在格式化过程中存在,且必须落在 0-100 —— 平台侧解析越界会判 malformed。
                progress = random.nextInt(0, 100),
                // 正在格式化时可用空间按 1/10 报(格式化会重建文件系统,剩余量本就不可信)。
                freeMb = card.capacityMb / 10,
            )
            in 5..7 -> StorageCardReading(card.id, StorageCardStatus.Unformatted, null, 0)
            in 8..10 -> StorageCardReading(card.id, StorageCardStatus.Idle, null, card.capacityMb / 2)
            in 11..13 -> StorageCardReading(card.id, StorageCardStatus.Error, null, 0)
            else -> {
                val usedPercent = random.nextInt(5, 96)
                StorageCardReading(
                    cardId = card.id,
                    status = StorageCardStatus.Ok,
                    progress = null,
                    freeMb = card.capacityMb * (100 - usedPercent) / 100,
                )
            }
        }
    }

    companion object {
        /**
         * A.2.6.16 的 `Item` 是 `maxOccurs="8"` —— 设备侧不得超发。
         * 当前生成逻辑最多 2 张,这条是**防御**:将来有人调大张数时不会静默超出标准上限
         * (超出后平台侧会判 `too_many_items` 整包拒收,表现为"存储卡查不出来"而看不出原因)。
         */
        const val MAX_CARDS: Int = 8

        /**
         * 一次格式化的"耗时"(ms)。
         *
         * 取值是两面对齐出来的:**先照真机实测**,再用平台观察节奏复核。
         *
         * **真机实测(海康,2026-09-20)**:17:13:45.251 下发 `<FormatSDCard>1</FormatSDCard>`,
         * 设备此后连回 9 条应答 —— `formatting` + `FormatProgress` 0→12→20→24→26→28→31→33,
         * 到 17:14:23.444 落 `ok` + `FreeSpace=28672`(28G 卡回满值)。
         * 全程 **≈38s**;最后一次报 `formatting` 是在 +25s。所以这里取 35s —— 落在真机区间内。
         *
         * 另一面是平台的观察窗(前端 `FORMAT_WATCH_ROUNDS=8` × `FORMAT_WATCH_DELAY_MS=6s`),
         * 它给出两个边界:
         *  - 太短(几秒):第一轮查询就直接看到"已完成 + 剩余空间满" —— 看不到 `formatting`
         *    与 `FormatProgress`,那条状态路径在联调里等于没被走过。
         *  - 太长(≥ 观察窗 ≈ 50s):窗口结束时卡还在 `formatting`,平台 UI 只能停在"仍在格式化中",
         *    而本可以给它一个明确的"已完成"。
         *
         * 35s 之下,6s 一轮的观察窗会依次看到:约 6 轮 `formatting`(进度 ≈17% → ≈85%),
         * 第 7 轮落到 `ok` + 剩余=容量 → 平台提前收工。
         *
         * ⛔ 这个值**不算协议的一部分**,标准里没有"格式化要多久"。它只影响联调时看到的
         * 状态序列像不像真机;改它不需要动任何断言(`VirtualStorageCardsTest` 用的是
         * 相对量:本常量本身 / `FORMAT_DURATION_MS / 15`)。
         */
        const val FORMAT_DURATION_MS: Long = 35_000

        /** 常见卡容量(MB):8G / 16G / 32G / 64G / 128G。 */
        private val CAPACITIES_MB = intArrayOf(8192, 16384, 32768, 65536, 131072)
    }
}

/**
 * [VirtualStorageCards.format] 的结果。
 *
 * 存在的理由是**日志要如实**:调用点必须能区分"真的开始格式化了"与"报文/卡号有问题所以什么都没做"。
 * 返回 `Unit` 的话,一条越界卡号会被静默吞掉 —— 平台那边看到的是"设备 200 OK 了但卡没变",
 * 只能靠对着卡看才发现,而设备侧日志里一条线索都没有。
 */
sealed interface StorageCardFormatOutcome {
    /** 已受理。`cardIds` = 本次真正进入格式化的卡号(`cardIndex=0` 时是全部卡)。 */
    data class Accepted(val cardIds: List<Int>) : StorageCardFormatOutcome

    /** 已拒绝,**未产生任何副作用**。[reason] 直接进日志。 */
    data class Rejected(val reason: String) : StorageCardFormatOutcome
}

/**
 * 一张虚拟存储卡的**物理属性**。
 *
 * 字段 1:1 对应 A.2.6.16 的 `Item/ID` / `Item/HddName` / `Item/Capacity`,单位 MB。
 *
 * ⛔ `name` 对应标准元素名 **`HddName`** —— 不是 `SDCardName`。按字段语义猜名字会全部解析不到,
 * 而且平台侧的表现是"有 Item 但读不到盘名",不报错。
 */
data class StorageCard(
    /** 卡编号 —— 标准要求**从 1 开始**(`Item/ID`,integer)。 */
    val id: Int,
    val name: String,
    val capacityMb: Int,
)

/**
 * 某张卡**在某一次读取**里的读数。
 *
 * [cardId] 而不是 `card`:读数与物理属性分开存,是为了让"卡还在、读数换了"这件事在类型上就成立
 * —— 剩余空间是会被写满的,卡不会凭空消失。
 */
data class StorageCardReading(
    val cardId: Int,
    val status: StorageCardStatus,
    /**
     * 格式化进度 0-100。**仅** [StorageCardStatus.Formatting] 有值,其余状态一律 `null`。
     *
     * ⛔ 用可空而不是给个 0:标准里 `FormatProgress` 是 `minOccurs="0"`,补 0 会被平台读成
     * "正在格式化且进度为 0" —— 那是一条**伪造的状态**。报文层与 UI 层都保持这个语义。
     */
    val progress: Int?,
    val freeMb: Int,
)

/**
 * A.2.6.16 `Item/Status` 的取值域 —— 标准里给定这五个词(`string` 类型,小写)。
 *
 * ⛔ 别复用平台侧那套"未知 → unknown":**设备侧只会产出这五个之一**,出现第六种只可能是
 * 有人写错了常量。未知值在这里没有合法来源,所以本 enum 不提供 `fromWire` 兜底
 * (兜底会让"写错常量"静默通过,而不是编译期就红)。
 */
enum class StorageCardStatus(val wireValue: String) {
    /** 正常可用。 */
    Ok("ok"),

    /** 正在格式化 —— 只有它带 [StorageCardReading.progress]。 */
    Formatting("formatting"),

    /** 未格式化(卡在,但没有可用文件系统)。 */
    Unformatted("unformatted"),

    /** 空闲(未承载业务)。 */
    Idle("idle"),

    /** 故障。 */
    Error("error"),
}
