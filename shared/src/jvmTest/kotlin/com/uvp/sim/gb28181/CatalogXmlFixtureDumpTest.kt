package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.GbVersion
import java.io.File
import kotlin.test.Test

/**
 * **跨仓契约夹具生成器**(不是常规单测,没有断言,只落文件)。
 *
 * 平台仓 `UVP-GB28181` 的 `server/app/gb28181/manscdp/testdata/sim-catalog-*.xml`
 * 逐字节取自这里 —— 那份夹具喂给平台的 MANSCDP 解析器,用来验证"模拟器发出的目录报文
 * 平台吃得下、能取到 `<Longitude>`/`<Latitude>`"。
 *
 * 两个人分别在两个仓里各改一半时,这条契约最容易悄悄断。分工是:
 * - **报文长什么样**由 [CatalogInstallPositionTest] 逐条钉住(元素位置/两版差异/0 值不发);
 * - **平台能不能解出来**由平台仓那条用例钉住。
 *
 * ## 重新生成夹具
 *
 * ```bash
 * cd ~/code/uvp/uvp-gb28181-sim
 * JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:jvmTest \
 *   --tests "com.uvp.sim.gb28181.CatalogXmlFixtureDumpTest"
 * cp shared/build/catalog-fixtures/sim-catalog-V2016.xml \
 *    shared/build/catalog-fixtures/sim-catalog-V2022.xml \
 *    shared/build/catalog-fixtures/sim-catalog-response-V2016.xml \
 *    shared/build/catalog-fixtures/sim-catalog-response-V2022.xml \
 *    ~/code/uvp/UVP-GB28181/server/app/gb28181/manscdp/testdata/
 * ```
 *
 * ⛔ 输出目录的覆盖走 `-Duvp.catalogFixtureDir=...`,但 **Gradle 的 `-D` 不会传给测试 JVM**;
 *    要改路径得在 `build.gradle.kts` 里 `systemProperty(...)` 转发。默认落在
 *    `shared/build/catalog-fixtures/`,日常用默认值即可。
 *
 * ⛔ 改了 Item 的元素集合 / 元素顺序 / 坐标写法之后**必须重跑这一步**,
 *    否则平台仓的夹具会停在旧形态上,那条契约用例就只在验历史。
 */
class CatalogXmlFixtureDumpTest {

    private val deviceId = "34020000002000000001"

    @Test
    fun dumpFourFixtureFiles() {
        val outDir = File(System.getProperty("uvp.catalogFixtureDir") ?: "build/catalog-fixtures")
        outDir.mkdirs()

        // 刻意用最小树:设备根 + 一个视频通道。夹具只需要覆盖"通道 Item 带不带坐标,
        // 以及带在哪个位置",节点越多越难在 diff 里看出形态变化。
        val tree = listOf(
            CatalogNode(deviceId, CatalogNodeType.Device, "UVP-Sim", deviceId),
            CatalogNode("34020000001320000010", CatalogNodeType.VideoChannel, "前置摄像头", deviceId),
        )

        for (version in listOf(GbVersion.V2016, GbVersion.V2022)) {
            // NOTIFY 形态 —— 平台走 ParseCatalogNotify(订阅通道)
            outDir.resolve("sim-catalog-$version.xml").writeText(
                CatalogNotifyBuilder.build(deviceId, 1, tree, version = version, channel = ChannelProfile())
            )
            // Response 形态 —— 平台走 ParseCatalogResponse(主动查询),另一条解析入口
            outDir.resolve("sim-catalog-response-$version.xml").writeText(
                CatalogNotifyBuilder.renderResponse(deviceId, "1", tree, version = version, channel = ChannelProfile())
            )
        }
    }
}
