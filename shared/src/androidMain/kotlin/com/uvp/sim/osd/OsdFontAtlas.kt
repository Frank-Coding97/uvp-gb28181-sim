package com.uvp.sim.osd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 运行期 OSD 字体 atlas — 从 androidMain/assets 加载 PNG + JSON。
 *
 * 资产由 [:shared:bakeOsdFontAtlas] Gradle task 生成,详见 tools/font-baker/README.md。
 *
 * 使用流程:
 * ```
 * val atlas = OsdFontAtlas()
 * atlas.load(context)           // 在 GL thread 调,glGenTextures + 上传 PNG
 * val glyph = atlas.lookup('A') // GlyphInfo? — null 表示 atlas 没收
 * atlas.release()               // 释放 GL 纹理
 * ```
 *
 * 线程安全:[load] / [release] 必须在 GL thread,[lookup] 是只读纯函数,任意线程可调。
 */
internal class OsdFontAtlas {

    var glTexId: Int = 0
        private set
    var atlasWidth: Int = 0
        private set
    var atlasHeight: Int = 0
        private set
    var sdfSpread: Int = 0
        private set
    var ascent: Float = 0f
        private set

    private var charMap: Map<Char, GlyphInfo> = emptyMap()
    private var loaded = false

    /**
     * 从 assets 加载 atlas,返回成功与否。失败时 [glTexId] = 0,调用方可走 GL fallback。
     *
     * 失败原因可能:assets 文件缺失 / JSON 解析错 / GL 纹理创建错。
     */
    fun load(context: Context): Boolean {
        if (loaded) return true
        try {
            // 1. 读 JSON 元数据
            val jsonText = context.assets.open(ASSET_JSON).use { it.bufferedReader().readText() }
            val meta = Json.decodeFromString(AtlasMeta.serializer(), jsonText)
            require(meta.version == 1) { "unsupported atlas version: ${meta.version}" }
            atlasWidth = meta.atlasWidth
            atlasHeight = meta.atlasHeight
            sdfSpread = meta.sdfSpread
            ascent = meta.ascent
            charMap = meta.chars.entries.associate { (k, v) ->
                require(k.length == 1) { "atlas key must be single char, got '$k'" }
                k[0] to v
            }

            // 2. 读 PNG → Bitmap → GL 纹理
            val bitmap: Bitmap = context.assets.open(ASSET_PNG).use {
                BitmapFactory.decodeStream(it)
                    ?: throw IOException("BitmapFactory.decodeStream returned null")
            }
            require(bitmap.width == atlasWidth && bitmap.height == atlasHeight) {
                "atlas PNG size ${bitmap.width}x${bitmap.height} != JSON ${atlasWidth}x${atlasHeight}"
            }

            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            glTexId = tex[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            // PNG 是 grayscale,GL 上传成 R8(SDF 距离场存 R 通道)。
            // BitmapFactory 默认 ARGB_8888,我们提取 R 通道(grayscale 的 R == G == B == luma)。
            val pixels = IntArray(atlasWidth * atlasHeight)
            bitmap.getPixels(pixels, 0, atlasWidth, 0, 0, atlasWidth, atlasHeight)
            val r8 = ByteArray(atlasWidth * atlasHeight)
            for (i in pixels.indices) {
                r8[i] = (pixels[i] and 0xFF).toByte()
            }
            bitmap.recycle()

            val buffer = java.nio.ByteBuffer
                .allocateDirect(r8.size)
                .order(java.nio.ByteOrder.nativeOrder())
                .put(r8)
                .position(0)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
                atlasWidth, atlasHeight, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buffer
            )

            loaded = true
            return true
        } catch (t: Throwable) {
            release()
            return false
        }
    }

    /**
     * 查指定字符的 atlas 信息。
     *
     * - 返回 null:atlas 没这个字符,渲染层应跳过(典型场景:用户输入了 atlas 不含的字)。
     */
    fun lookup(char: Char): GlyphInfo? = charMap[char]

    /** 当前 atlas 收录字符总数。 */
    val charCount: Int get() = charMap.size

    fun release() {
        if (glTexId != 0) {
            val tex = intArrayOf(glTexId)
            GLES30.glDeleteTextures(1, tex, 0)
            glTexId = 0
        }
        charMap = emptyMap()
        loaded = false
    }

    companion object {
        const val ASSET_PNG = "osd-font-atlas.png"
        const val ASSET_JSON = "osd-font-atlas.json"
    }
}

/**
 * Atlas 元数据 — 跟 [com.uvp.sim.tools.fontbaker.AtlasMeta] schema 对齐。
 *
 * 这里独立声明而不共享:baker 是 host JVM 工具,运行期 androidMain 不依赖 baker 模块。
 */
@Serializable
internal data class AtlasMeta(
    val version: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val cellSize: Int,
    val sdfSpread: Int,
    val ascent: Float,
    val chars: Map<String, GlyphInfo>
)

/**
 * 单字符 atlas 信息。
 *
 * - [u]/[v]/[w]/[h]:UV 坐标(atlas 原子化 0..1),传给 fragment shader 采样
 * - [pixelW]/[pixelH]:实际像素尺寸,文本布局时定 quad 大小
 * - [advance]:水平推进距离(像素),下一字符 cursor += advance × scale
 * - [bearingX]/[bearingY]:相对 cursor 和 baseline 的偏移
 */
@Serializable
internal data class GlyphInfo(
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
