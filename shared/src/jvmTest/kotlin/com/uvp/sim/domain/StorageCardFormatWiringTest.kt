package com.uvp.sim.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A.2.3.1.13 存储卡格式化 —— **生产装配**防回归（源码级,JVM-only）。
 *
 * 为什么需要一条"读源码"的测试:[ManscdpRouterImpl] 里那个 `DeviceControlActions` 匿名对象
 * 没有单测覆盖(构造它需要整套 transport / identity / outbox)。而它恰好是本功能**唯一**
 * 把"平台下发的格式化"接到"设备侧存储卡状态"上的那一跳。
 *
 * ⛔ 要防的具体写法是**在新实例上格式化**:
 * ```kotlin
 * override fun formatStorageCard(cardIndex: Int) {
 *     VirtualStorageCards().format(cardIndex)   // ← 看起来对,实际是另一批卡
 * }
 * ```
 * 这正是本仓反复吃过的"两处各掷各的骰子":格式化作用在一个**新建的、跟应答报文无关的**
 * 实例上。报文那侧读的还是原来那个实例 ⇒ 平台再查 `SDCardStatus`,看到的依旧是
 * "随机用了 60%":报文合法、日志正常、`format()` 也真的被调用了,**但闭环是断的**。
 * 这条只有靠对着看才发现,所以在这里用源码断言钉死。
 */
class StorageCardFormatWiringTest {

    private fun repoSource(relative: String): String {
        // Gradle 跑 jvmTest 时工作目录通常是模块目录(shared/),但别赌它 —— 逐个候选找。
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "../$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative;候选=${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /** 从 [marker] 起截 [lines] 行 —— 够覆盖那个 override 的实现体,又不至于吃掉隔壁方法。 */
    private fun windowAfter(source: String, marker: String, lines: Int = 24): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "源码里找不到标记:`$marker`" }
        return source.substring(start).lines().take(lines).joinToString("\n")
    }

    private val routerImpl = "shared/src/commonMain/kotlin/com/uvp/sim/domain/coord/ManscdpRouterImpl.kt"

    @Test
    fun router_formats_the_injected_storage_cards_instance() {
        val body = windowAfter(
            repoSource(routerImpl),
            "override fun formatStorageCard(cardIndex: Int) {",
        )
        assertTrue(
            body.contains("virtualStorageCards.format("),
            "formatStorageCard 必须作用在注入的 virtualStorageCards 上(与 SDCardStatus 应答同一实例),实际实现:\n$body",
        )
    }

    @Test
    fun router_does_not_format_a_freshly_constructed_instance() {
        val body = windowAfter(
            repoSource(routerImpl),
            "override fun formatStorageCard(cardIndex: Int) {",
        )
        assertFalse(
            body.contains("VirtualStorageCards("),
            "⛔ 在格式化路径里 new 一个 VirtualStorageCards 会让格式化落到另一批卡上:" +
                "平台再查 SDCardStatus 看不到任何变化,而日志里一切正常。实际实现:\n$body",
        )
    }

    /**
     * ⛔ 这条例外**反过来**锁住上面那条的边界:`ManscdpRouterImpl` 的构造参数默认值**可以**
     * 有 `VirtualStorageCards()`(单测/无装配场景的兜底),但只要 [router_formats_the_injected_storage_cards_instance]
     * 是绿的,就说明执行路径上用的仍是那个被注入的实例。
     *
     * 这条同时防止有人为了"让上一条过"而把默认值参数整个删掉 —— 那会把无装配场景弄挂。
     */
    @Test
    fun router_still_accepts_a_default_storage_cards_for_tests() {
        val source = repoSource(routerImpl)
        assertTrue(
            source.contains("private val virtualStorageCards: VirtualStorageCards = VirtualStorageCards()"),
            "构造参数默认值(单测/无装配兜底)不该被删掉",
        )
    }

    /**
     * ⛔ 反向:handler 侧**必须**把执行发出去 —— 只更新 `pendingEffect`(弹个提示)
     * 是这条 feature 最初的样子,也正是"平台侧什么都看不到"的根因。
     */
    @Test
    fun system_handler_actually_dispatches_the_format_action() {
        val source = repoSource(
            "shared/src/commonMain/kotlin/com/uvp/sim/domain/devicecontrol/SystemHandler.kt"
        )
        val body = windowAfter(source, "override fun handleFormatSDCard(xml: String) {", 34)
        assertTrue(
            body.contains("actions.formatStorageCard(card)"),
            "handleFormatSDCard 必须调 actions.formatStorageCard —— 只弹 snackbar 的话" +
                "平台观察窗里看不到 formatting / FormatProgress / 空盘,闭环在设备侧就断了。实际实现:\n$body",
        )
        assertFalse(
            body.contains("\"DiskNum\""),
            "⛔ `DiskNum` 是自造元素名(2022 全文 / 2022 附录 A / 2016 附录 A 三处 0 命中),不得再出现",
        )
    }
}
