package com.uvp.sim.domain

import kotlin.random.Random

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
 */
class VirtualStorageCards(private val random: Random = Random.Default) {

    /**
     * 本机虚拟存储卡清单(**物理属性**)。首访时掷一次骰子后不再变。
     *
     * 用 `by lazy` 而不是构造期计算:构造发生在 Engine 装配阶段,那时掷骰子会把随机性
     * 提前暴露给"还没插卡就被读"的时序;而且 lazy 保证多次访问拿到的**是同一个 List 实例**,
     * `DeviceControlModel` 的 `equals` 也就不用每次比一堆内容。
     */
    val cards: List<StorageCard> by lazy { generateCards() }

    /**
     * 读一次盘 —— 每张卡重新抖出状态与剩余空间。
     *
     * 调用点只有两个(报文 + 卡片),它们必须**共用同一次调用的返回值**:见
     * `DeviceControlSubRouter.sendStorageCardStatusResponse` 里"先 read 一次,再分别喂报文和 UI"。
     */
    fun read(): List<StorageCardReading> = cards.map(::readingOf)

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

        /** 常见卡容量(MB):8G / 16G / 32G / 64G / 128G。 */
        private val CAPACITIES_MB = intArrayOf(8192, 16384, 32768, 65536, 131072)
    }
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
