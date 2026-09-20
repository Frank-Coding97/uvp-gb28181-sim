package com.uvp.sim.ui.simulate.ptz

import com.uvp.sim.ui.model.MaskRectDto
import com.uvp.sim.ui.model.PictureMaskViewDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「图像」页那个**定高 284dp、不滚动**的内容区里，遮挡区块该显示成什么样。
 *
 * ⛔ 为什么这些用例值钱：HUD 内容区**超出直接裁掉最后一行**，
 * 而"裁掉"在截图里看不出来（页面上看起来只是少了一块区域）。
 * 所以行数上限、以及"什么状态下不列区域"这两件事必须是**纯函数说了算**、由测试守住，
 * 不能靠肉眼在真机上数。
 *
 * 2026-09-19 之前这里守的是「设备配置族」的 `selectConfigRows`；那一整块已按用户要求
 * 从「图像」页删除（只留遮挡区域列表），所以旧用例一并作废。
 */
class ImageTabContentStateTest {

    private fun rect(seq: Int) = MaskRectDto(
        seq = seq,
        left = 100 * seq,
        top = 200 * seq,
        right = 100 * seq + 320,
        bottom = 200 * seq + 240,
    )

    // ===== 四个状态各说各的话（合并了就等于设备替平台编答案） =====

    /**
     * ⛔⛔ 「平台从没配过」与「配过但关了」**必须分两态**：两者的 `on` 都是 false，
     * 合并了设备屏幕就会在平台配置**之前**先摆出一句"已停用" —— 那是设备替平台编答案。
     */
    @Test
    fun neverConfigured_saysSoAndListsNothing() {
        val state = maskSectionState(PictureMaskViewDto())

        assertEquals("画面遮挡 · 平台未配置", state.title)
        assertEquals(emptyList(), state.rows)
        assertEquals(0, state.hiddenCount)
    }

    /**
     * ⛔ **停用时不许罗列区域**（虽然协议允许 `On=0` 时保留 `RegionList`）：
     * 列出来会让操作员以为"这几块正挡着"—— 小标题写着"已停用"也救不回来，
     * 因为列表本身视觉上就是"生效的东西在列队"。
     */
    @Test
    fun configuredButOff_saysDisabledAndDoesNotListRegions() {
        val state = maskSectionState(
            PictureMaskViewDto(on = false, configured = true, rects = List(4) { rect(it + 1) }),
        )

        assertEquals("画面遮挡 · 已停用", state.title)
        assertEquals(emptyList(), state.rows, "停用态不该把旧坐标列成'正在生效'的样子")
        assertEquals(0, state.hiddenCount)
    }

    /** `On=1` 但一块都没画 —— 开关开着却什么都没挡，必须说出来，不能显示成"正常"。 */
    @Test
    fun enabledWithoutRegions_saysSo() {
        val state = maskSectionState(PictureMaskViewDto(on = true, configured = true))

        assertEquals("画面遮挡 · 已启用 · 无区域", state.title)
        assertEquals(emptyList(), state.rows)
    }

    // ===== 正常态：逐块罗列，坐标就是协议里那几个数 =====

    @Test
    fun enabledWithRegions_listsOneRowPerRegionWithProtocolPixels() {
        val state = maskSectionState(
            PictureMaskViewDto(on = true, configured = true, rects = listOf(rect(1), rect(2))),
        )

        assertEquals("画面遮挡 · 已启用 · 2 个区域", state.title)
        assertEquals(listOf("区域 1", "区域 2"), state.rows.map { it.label })
        // 用户明确要求：手机上要能看到 X、Y 的具体数值 —— 不是"已配 2 项"这种统计。
        assertEquals("100,200 → 420,440", state.rows[0].point)
        assertEquals("200,400 → 520,640", state.rows[1].point)
        assertEquals(0, state.hiddenCount)
    }

    /**
     * ⛔ 按 `Seq` 排序是为了和平台侧列表对得上；**但绝不重排编号本身** ——
     * 标准只规定 `Seq` 范围 1~4、**没要求连续**，重编等于给设备上的区域静默改名，
     * 平台回读对账反而可能"一致"，是最难查的一类偏差。
     */
    @Test
    fun rows_areOrderedBySeqButKeepSparseNumbers() {
        val state = maskSectionState(
            PictureMaskViewDto(on = true, configured = true, rects = listOf(rect(3), rect(1))),
        )

        assertEquals(listOf("区域 1", "区域 3"), state.rows.map { it.label }, "稀疏编号必须原样保留")
        assertEquals("100,200 → 420,440", state.rows[0].point, "排序后坐标要跟着自己那块走")
        assertEquals("300,600 → 620,840", state.rows[1].point)
    }

    // ===== 高度预算的守卫 =====

    /**
     * ⛔⛔ 这条守的是**硬高度预算**：`PtzHudPanel` 内容区定高 284dp 且不滚动，
     * 超出的行被直接裁掉，而裁掉的往往是最后一行。
     *
     * 正常报文到不了 5 块（`Item maxOccurs="4"`），所以这里喂的是一份**不合规的报文** ——
     * 要的正是"平台发错也不能把屏幕搞乱"：行数封顶 + 余数照实报。
     */
    @Test
    fun overProtocolLimit_capsRowsAndReportsRemainder() {
        val state = maskSectionState(
            PictureMaskViewDto(on = true, configured = true, rects = List(5) { rect(it + 1) }),
        )

        assertEquals(MAX_VISIBLE_MASK_REGIONS, state.rows.size, "行数必须由常量封顶")
        assertEquals(1, state.hiddenCount, "少摊的块数要报出来，别让屏幕看起来只有 4 块")
        assertTrue(state.title.contains("5 个区域"), "小标题报的是**总数**，与余数行互补")
    }

    /**
     * 「最坏能有多少行」——把三处常量放在一起算，谁改了预算测试先红。
     *
     * 遮挡区块：小标题 1 + 行(≤4) + 余数行(≤1)；与升级/最近命令互斥，故整页行数有上界。
     */
    @Test
    fun worstCaseRenderedRowsStayWithinHeightBudget() {
        val worst = maskSectionState(
            PictureMaskViewDto(on = true, configured = true, rects = List(8) { rect(it + 1) }),
        )

        val maskRows = 1 + worst.rows.size + if (worst.hiddenCount > 0) 1 else 0
        assertEquals(6, maskRows, "小标题 + 4 行区域 + 1 行余数 = 6 行，这是遮挡区块的上界")
    }

    // ===== 画面镜像（A.2.1.23）四卡片的展示规则 =====

    /**
     * ⛔⛔ 「平台从没配过」与「配了 0」**必须分两态**，与上面遮挡那条同源：
     * 未配置时四张卡**一个都不点亮** —— 设备不能替平台先认领一个"不启用"。
     *
     * 两者的画面表现完全一样（都不翻），但屏幕上的话不同：前者"不用管"，
     * 后者"有人刚把它关了，去平台确认为什么"。
     */
    @Test
    fun mirrorNeverConfigured_lightsNothingAndSaysSo() {
        val state = mirrorSectionState(null)

        assertEquals("画面镜像 · 平台未配置", state.title)
        assertEquals(4, state.cards.size)
        assertTrue(state.cards.none { it.selected }, "四张卡必须全灰，不能默认点亮『不启用』")
    }

    /** 平台配了 0 ⇒ 点亮「不启用」—— 与上一条的差别就是这一张卡。 */
    @Test
    fun mirrorConfiguredZero_lightsDisabledCardOnly() {
        val state = mirrorSectionState(0)

        assertEquals("画面镜像 · 平台已下发", state.title)
        assertEquals(listOf("不启用", "左右", "上下", "中心"), state.cards.map { it.label })
        assertEquals(listOf(true, false, false, false), state.cards.map { it.selected })
    }

    /**
     * ⛔⛔ 值 → 卡的对应**逐个钉死**：`1` 是水平（左右）、`2` 是上下。
     * 本仓前端 `MIRROR_OPTIONS` 曾按海康 ISP 口径把这两个写反，所以这里分四条断言，
     * 合并成"恰好点亮一张"就抓不出那个错。
     */
    @Test
    fun mirrorEachValueLightsItsOwnCard() {
        assertEquals(
            listOf(false, true, false, false),
            mirrorSectionState(1).cards.map { it.selected },
            "A.2.1.23 的 1 = 水平镜像（左右）",
        )
        assertEquals(
            listOf(false, false, true, false),
            mirrorSectionState(2).cards.map { it.selected },
            "A.2.1.23 的 2 = 上下镜像",
        )
        assertEquals(
            listOf(false, false, false, true),
            mirrorSectionState(3).cards.map { it.selected },
            "A.2.1.23 的 3 = 中心镜像（上下左右都翻）",
        )
    }

    /**
     * 越界值：协议层 `FrameMirrorConfig.parse` 本该拒掉（落不进库），真漏进来
     * 也**不许装作没看到** —— 报出来，且不点亮任何一张（不能替平台猜一个方向）。
     */
    @Test
    fun mirrorOutOfRange_lightsNothingAndReportsValue() {
        for (value in listOf(-1, 4, 7)) {
            val state = mirrorSectionState(value)
            assertEquals("画面镜像 · 取值非法（$value）", state.title)
            assertTrue(state.cards.none { it.selected }, "value=$value 不该点亮任何卡")
        }
    }

    /**
     * ⛔ 高度守卫：卡片数**恒为 4**、恒占**一行** —— 这条保证"镜像区块不吃预算弹性"，
     * 因为恒占一行，所以整页最坏高度那次加法（见 `ImageTabContent` 文件头）才有意义。
     */
    @Test
    fun mirrorSectionIsAlwaysExactlyOneRowOfFourCards() {
        for (value in listOf(null, 0, 1, 2, 3)) {
            assertEquals(4, mirrorSectionState(value).cards.size, "value=$value 也必须是四张")
        }
    }
}
