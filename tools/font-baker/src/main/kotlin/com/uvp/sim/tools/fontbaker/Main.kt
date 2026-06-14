package com.uvp.sim.tools.fontbaker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * OSD 字体 atlas baker — 用 java.awt 渲染 alpha bitmap → 8-spread SDF。
 *
 * 用法:
 * ```
 * ./gradlew :tools:font-baker:run --args="<font-spec> <charset> <out-png> <out-json> [--cell=<px>] [--atlas=<px>]"
 * ```
 *
 * `font-spec` 可以是:
 * - 系统逻辑字体名(`Monospaced` / `SansSerif` / `Serif`)
 * - 已安装字体名(`PingFang SC` / `Helvetica`)
 * - .ttf/.otf 文件路径(以 `/` 开头或包含 `.ttf`/`.otf`)
 *
 * 默认 cell 64,atlas 2048(1024 cell,够 ASCII + 1000 中文)。
 *
 * 输出:
 * - PNG:8-bit grayscale,SDF 距离场存 R 通道
 * - JSON:每字符 UV(0..1)+ pixel 尺寸 + advance + bearing
 */
fun main(args: Array<String>) {
    require(args.size >= 4) {
        "用法: <font-spec> <charset-path> <out-png> <out-json> [--cell=<px>] [--atlas=<px>]"
    }
    val fontSpec = args[0]
    val charsetPath = args[1]
    val outPngPath = args[2]
    val outJsonPath = args[3]
    val cellSize = args.firstOrNull { it.startsWith("--cell=") }
        ?.substringAfter("=")?.toInt() ?: 64
    val atlasSize = args.firstOrNull { it.startsWith("--atlas=") }
        ?.substringAfter("=")?.toInt() ?: 2048
    val sdfSpread = args.firstOrNull { it.startsWith("--spread=") }
        ?.substringAfter("=")?.toInt() ?: 8

    val charset = File(charsetPath).readText(Charsets.UTF_8)
        .filter { it.code in 0x20..0xFFFF && it != '\n' && it != '\r' }
        .toSortedSet()
        .joinToString("")

    println("[baker] font='$fontSpec' chars=${charset.length} cell=$cellSize atlas=$atlasSize spread=$sdfSpread")

    // 加载字体
    val fontPx = (cellSize * 0.78f).toInt()  // 留 ~22% 边距给描边/SDF spread
    val baseFont = if (fontSpec.startsWith("/") || fontSpec.endsWith(".ttf") || fontSpec.endsWith(".otf")) {
        Font.createFont(Font.TRUETYPE_FONT, File(fontSpec))
    } else {
        Font(fontSpec, Font.PLAIN, fontPx)
    }
    val font = if (baseFont.size != fontPx) baseFont.deriveFont(fontPx.toFloat()) else baseFont
    println("[baker] font.family=${font.family} size=${font.size}")

    // 准备一张临时 BufferedImage 拿 FontMetrics
    val tmp = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
    tmp.font = font
    val metrics = tmp.fontMetrics
    val ascent = metrics.ascent.toFloat()
    tmp.dispose()

    // 渲染所有字符到 alpha atlas
    val alphaAtlas = ByteArray(atlasSize * atlasSize)
    val glyphMap = mutableMapOf<String, GlyphInfo>()
    var pen = 0
    val cellsPerRow = atlasSize / cellSize

    for (ch in charset) {
        val cellX = (pen % cellsPerRow) * cellSize
        val cellY = (pen / cellsPerRow) * cellSize
        if (cellY + cellSize > atlasSize) {
            println("[baker] atlas 满,丢弃从 '$ch' 起的 ${charset.length - pen} 字符")
            break
        }

        val (alphaCell, advance, bearingX, bearingY) = renderGlyph(font, ch, cellSize, ascent)
        // blit cell → atlas
        for (y in 0 until cellSize) {
            System.arraycopy(
                alphaCell, y * cellSize,
                alphaAtlas, (cellY + y) * atlasSize + cellX,
                cellSize
            )
        }

        glyphMap[ch.toString()] = GlyphInfo(
            u = cellX.toFloat() / atlasSize,
            v = cellY.toFloat() / atlasSize,
            w = cellSize.toFloat() / atlasSize,
            h = cellSize.toFloat() / atlasSize,
            pixelW = cellSize,
            pixelH = cellSize,
            advance = advance,
            bearingX = bearingX,
            bearingY = bearingY
        )
        pen++
    }

    println("[baker] alpha 渲染完成 ${glyphMap.size} 字符,SDF 后处理中...")

    val sdfAtlas = generateSdf(alphaAtlas, atlasSize, atlasSize, sdfSpread)

    // 写 PNG
    val img = BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_BYTE_GRAY)
    img.raster.setDataElements(0, 0, atlasSize, atlasSize, sdfAtlas)
    File(outPngPath).parentFile?.mkdirs()
    ImageIO.write(img, "png", File(outPngPath))
    println("[baker] PNG → $outPngPath (${File(outPngPath).length()} bytes)")

    // 写 JSON
    val atlasMeta = AtlasMeta(
        version = 1,
        atlasWidth = atlasSize,
        atlasHeight = atlasSize,
        cellSize = cellSize,
        sdfSpread = sdfSpread,
        ascent = ascent,
        chars = glyphMap
    )
    val json = Json { prettyPrint = false; encodeDefaults = true }
    File(outJsonPath).parentFile?.mkdirs()
    File(outJsonPath).writeText(json.encodeToString(AtlasMeta.serializer(), atlasMeta))
    println("[baker] JSON → $outJsonPath (${File(outJsonPath).length()} bytes,${glyphMap.size} chars)")
}

private fun renderGlyph(
    font: Font,
    ch: Char,
    cellSize: Int,
    ascent: Float
): GlyphRender {
    val img = BufferedImage(cellSize, cellSize, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP)
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0, 0, 0, 0)
    g.fillRect(0, 0, cellSize, cellSize)
    g.color = Color.WHITE
    g.font = font
    val metrics = g.fontMetrics
    val advance = metrics.charWidth(ch).toFloat()
    val padX = ((cellSize - advance.toInt()).coerceAtLeast(0)) / 2
    val padY = ((cellSize - metrics.ascent - metrics.descent).coerceAtLeast(0)) / 2
    val baselineY = padY + metrics.ascent
    g.drawString(ch.toString(), padX, baselineY)
    g.dispose()

    val cell = ByteArray(cellSize * cellSize)
    for (y in 0 until cellSize) {
        for (x in 0 until cellSize) {
            val argb = img.getRGB(x, y)
            val a = (argb ushr 24) and 0xFF
            cell[y * cellSize + x] = a.toByte()
        }
    }
    return GlyphRender(
        alpha = cell,
        advance = advance,
        bearingX = padX.toFloat(),
        bearingY = baselineY.toFloat() - ascent
    )
}

private data class GlyphRender(
    val alpha: ByteArray,
    val advance: Float,
    val bearingX: Float,
    val bearingY: Float
)

/**
 * 8-spread SDF — 对每个像素搜索 spread 邻域内最近的"对边"像素。
 *
 * 算法 O(W*H*spread²)。spread=8 atlas=2048 → 1.2 G ops,~30-60s,只跑一次可接受。
 */
private fun generateSdf(
    alpha: ByteArray,
    width: Int,
    height: Int,
    spread: Int
): ByteArray {
    val sdf = ByteArray(width * height)
    val threshold = 128
    val maxDist = spread.toFloat()

    for (y in 0 until height) {
        if (y % 128 == 0) print(".")
        for (x in 0 until width) {
            val center = (alpha[y * width + x].toInt() and 0xFF) >= threshold
            var minDistSq = Float.MAX_VALUE

            val xMin = (x - spread).coerceAtLeast(0)
            val xMax = (x + spread).coerceAtMost(width - 1)
            val yMin = (y - spread).coerceAtLeast(0)
            val yMax = (y + spread).coerceAtMost(height - 1)
            for (sy in yMin..yMax) {
                for (sx in xMin..xMax) {
                    val sample = (alpha[sy * width + sx].toInt() and 0xFF) >= threshold
                    if (sample != center) {
                        val dx = (sx - x).toFloat()
                        val dy = (sy - y).toFloat()
                        val dSq = dx * dx + dy * dy
                        if (dSq < minDistSq) minDistSq = dSq
                    }
                }
            }

            val dist = if (minDistSq == Float.MAX_VALUE) maxDist else kotlin.math.sqrt(minDistSq)
            val normalized = if (center) {
                0.5f + (dist / maxDist).coerceAtMost(1f) * 0.5f
            } else {
                0.5f - (dist / maxDist).coerceAtMost(1f) * 0.5f
            }
            sdf[y * width + x] = (normalized * 255f).toInt().coerceIn(0, 255).toByte()
        }
    }
    println()
    return sdf
}

@Serializable
data class AtlasMeta(
    val version: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val cellSize: Int,
    /** SDF 距离场 spread(像素) — shader 端 outline 阈值参考 */
    val sdfSpread: Int,
    /** 字体基线 ascent(像素) */
    val ascent: Float,
    /** char(单字符字符串) → GlyphInfo */
    val chars: Map<String, GlyphInfo>
)

@Serializable
data class GlyphInfo(
    val u: Float,
    val v: Float,
    val w: Float,
    val h: Float,
    val pixelW: Int,
    val pixelH: Int,
    val advance: Float,
    val bearingX: Float,
    val bearingY: Float
)
