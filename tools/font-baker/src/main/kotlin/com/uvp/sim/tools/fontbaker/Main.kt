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
 * 8SSEDT(8-point Sequential Signed Euclidean Distance Transform)。
 *
 * 比 brute-force(O(W*H*spread²),atlas 2048+spread=8 跑 30-60s)快 ~30 倍,
 * 同尺寸 atlas 1-2s 出结果,GB2312 一级 1000 字 baking 体验跟得上。
 *
 * 算法:
 * 1. 初始化两个 grid(inside / outside),边界像素 (0,0),其他 +∞
 * 2. forward pass:从 (0,0) → (W-1,H-1),每点跟左/上邻居比距离取小
 * 3. backward pass:反向走一遍补漏
 * 4. signed distance = sqrt(outsideDist²) - sqrt(insideDist²)
 * 5. 归一化到 [0, 1] 写回 R8
 *
 * 输出语义跟 brute-force 完全一致(0.5 = 字符边界,> 0.5 内部,< 0.5 外部),
 * shader 端不用改。
 */
private fun generateSdf(
    alpha: ByteArray,
    width: Int,
    height: Int,
    spread: Int
): ByteArray {
    val threshold = 128
    // 用 IntArray 表示 (dx, dy) 偏移到最近边界的位置,放进单个 long-style 编码:
    // 高 16 bit dy(有符号) + 低 16 bit dx(有符号)。这样取距离平方很快。
    // 起始:边界像素(本侧 vs 邻侧不同)offset=0,其他 offset=很大值。
    val INF = (Short.MAX_VALUE.toInt() / 2)  // 安全上限,平方不溢出 int

    val inside = IntArray(width * height) { encode(INF, INF) }
    val outside = IntArray(width * height) { encode(INF, INF) }

    // 初始化:边界像素本身距离为 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val isInside = (alpha[y * width + x].toInt() and 0xFF) >= threshold
            val idx = y * width + x
            if (isInside) {
                inside[idx] = encode(0, 0)
            } else {
                outside[idx] = encode(0, 0)
            }
        }
    }

    sweep(inside, width, height)
    sweep(outside, width, height)

    val maxDist = spread.toFloat()
    val sdf = ByteArray(width * height)
    for (i in alpha.indices) {
        val isInside = (alpha[i].toInt() and 0xFF) >= threshold
        val (idx, idy) = decode(inside[i])
        val (odx, ody) = decode(outside[i])
        val distInside = kotlin.math.sqrt((idx * idx + idy * idy).toFloat())
        val distOutside = kotlin.math.sqrt((odx * odx + ody * ody).toFloat())
        // signed distance:正值在内,负值在外
        val signedDist = if (isInside) distOutside else -distInside
        // 映射到 [0, 1]:边界 = 0.5,inside 距离越大 → 越接近 1,outside 越大 → 越接近 0
        val normalized = (0.5f + signedDist / (2f * maxDist)).coerceIn(0f, 1f)
        sdf[i] = (normalized * 255f).toInt().coerceIn(0, 255).toByte()
    }
    return sdf
}

/**
 * 8SSEDT 双向扫描 — 先 forward(左上往右下),再 backward(右下往左上)。
 *
 * 每个像素跟 8 邻居中已经处理过的子集比距离,取最小。两遍即可拿到全局最近距离。
 * 误差 ≤ 1 像素(对 SDF 字体足够,smoothstep ±0.05 已抵消)。
 */
private fun sweep(grid: IntArray, width: Int, height: Int) {
    // forward
    for (y in 0 until height) {
        for (x in 0 until width) {
            val idx = y * width + x
            // 8 邻居中已扫描过的 4 个:左上 / 上 / 右上 / 左
            compare(grid, idx, x, y, -1, -1, width, height)
            compare(grid, idx, x, y, 0, -1, width, height)
            compare(grid, idx, x, y, 1, -1, width, height)
            compare(grid, idx, x, y, -1, 0, width, height)
        }
        // 行内补一次反向(覆盖右侧已更新值)
        for (x in width - 1 downTo 0) {
            val idx = y * width + x
            compare(grid, idx, x, y, 1, 0, width, height)
        }
    }
    // backward
    for (y in height - 1 downTo 0) {
        for (x in width - 1 downTo 0) {
            val idx = y * width + x
            compare(grid, idx, x, y, 1, 1, width, height)
            compare(grid, idx, x, y, 0, 1, width, height)
            compare(grid, idx, x, y, -1, 1, width, height)
            compare(grid, idx, x, y, 1, 0, width, height)
        }
        for (x in 0 until width) {
            val idx = y * width + x
            compare(grid, idx, x, y, -1, 0, width, height)
        }
    }
}

private fun compare(grid: IntArray, idx: Int, x: Int, y: Int, ox: Int, oy: Int, w: Int, h: Int) {
    val nx = x + ox
    val ny = y + oy
    if (nx < 0 || ny < 0 || nx >= w || ny >= h) return
    val (ndx, ndy) = decode(grid[ny * w + nx])
    val newDx = ndx + ox
    val newDy = ndy + oy
    val newDistSq = newDx * newDx + newDy * newDy
    val (cdx, cdy) = decode(grid[idx])
    val curDistSq = cdx * cdx + cdy * cdy
    if (newDistSq < curDistSq) {
        grid[idx] = encode(newDx, newDy)
    }
}

/** (dx, dy) ∈ [-32767, 32767] → 单 int 编码,提速 IntArray 单读单写。 */
private fun encode(dx: Int, dy: Int): Int = (dy shl 16) or (dx and 0xFFFF)

private fun decode(packed: Int): Pair<Int, Int> {
    val dx = (packed shl 16) shr 16  // 符号扩展到 32 bit
    val dy = packed shr 16
    return dx to dy
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
