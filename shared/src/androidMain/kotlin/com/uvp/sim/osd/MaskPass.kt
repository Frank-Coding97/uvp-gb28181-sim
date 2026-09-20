package com.uvp.sim.osd

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 把「画面遮挡」画成**纯黑实心块**，直接烧进 GL FBO —— 也就是烧进推流/录像帧本身。
 *
 * ## 为什么必须有这个 pass（而不是继续在 Compose 画布上盖黑块）
 * 「模拟中心」那块 3D 画布与推给平台的摄像头画面是**两条互不相干的链路**：
 * 画布上盖黑块，平台点播到的画面一点遮挡都没有。本 pass 落在
 * `OsdRenderer.onFrameAvailable()` 的 FBO 段（与 `drawOsdLayers` 同级），
 * 而 FBO 是「一次渲染、多消费者共享」——编码器与屏幕各 blit 一次，
 * 所以**烧进去的黑块会同时进推流、进录像、进屏幕预览**，与工业 IPC 的
 * 硬件遮挡区域（OSD region）同构：屏幕看到什么 = 推出去什么。
 *
 * ## 坐标系（⛔ 改动前先把这段读完，Y 方向极易改错）
 * 输入是**归一化 0~1、原点在画面左上角**的矩形（见 [VideoMaskRect]）。
 *
 * fbo 里存的是**上下颠倒**的画面（`OsdRenderer.blitToConsumer` 的 quad 用
 * `DEFAULT_TEXTURE_COORDINATES = (0,1) (1,1) (0,0) (1,0)` 把 V 翻了一次，
 * 目标画面底部采样的是 fbo 顶行）。[OsdTextPass] 的 net 效果与之一致：
 * 屏幕 TOP 锚点落在 **GL 底边**（它 `anchorY` 那一段把 TOP 锚点算成
 * `viewportHeight - margin - glyphTopPx`，正是"自下而上坐标系里的顶"）。
 * 所以屏幕纵向归一化坐标 `t`（0 = 画面顶）到 NDC 的换算是：
 *
 * ```
 * ny = 2t - 1        // t=0(画面顶) → -1(GLB 底,blit 翻转后落到画面顶) ✓
 * ```
 *
 * ⛔⛔ **别照着 [OsdTextPass] 的 `pxToNdcY` 把这里"修正"成 `ny = 1 - 2t`** ——
 * 那个 helper 写的是 `1 - (py/H)*2`，但它的入参 `py` 是**自下而上**的坐标
 * （锚点反转已在 `anchorY` 里做过），与本文的 `t` **不是同一个基准**。
 * 直接套用会让遮挡块**上下镜像**：位置看起来"差不多对、但整体错位一整块"，
 * 正是最难当场发现的一类错。（2026-09-19 核过两个 pass 的 net 行为后才定下这一行。）
 * X 方向不翻转：blit 只翻 V，所以 `nx = 2l - 1` 直用。
 *
 * ## 边界
 *  - 不带任何纹理 / 混合：不透明纯黑，`glDisable(GL_BLEND)` 保证一定盖死底下的画面。
 *  - `rects` 为空时**立刻返回**，不做任何 GL 调用（[OsdRenderer] 也只在非空时调进来）。
 *  - 协议上限 4 块（`Item maxOccurs="4"`），[MAX_RECTS] 是第二道闸，防手造数据把缓冲区写穿。
 */
internal class MaskPass {

    private var program = 0
    private var posLoc = 0
    private var vbo = 0
    private var initialized = false

    /** 每块 6 个顶点（两个三角形）× 2 个 float。 */
    private val scratch: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_RECTS * 6 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    fun init() {
        if (initialized) return
        val vs = """#version 300 es
layout(location = 0) in vec2 aPosition;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""
        val fs = """#version 300 es
precision mediump float;
out vec4 fragColor;
void main() {
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
"""
        program = GlUtil.createProgram(vs, fs)
        posLoc = GLES30.glGetAttribLocation(program, "aPosition")

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        vbo = vboArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            MAX_RECTS * 6 * 2 * 4,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        initialized = true
    }

    /**
     * 在**当前已绑定到 fbo** 的状态下画遮挡块。
     *
     * ⛔ 调用前必须已经 `glBindFramebuffer(fboId)` —— 本 pass 不做任何绑定，
     * 它只负责"在当前目标上画几块黑"。
     */
    fun draw(rects: List<VideoMaskRect>) {
        if (!initialized || rects.isEmpty()) return
        val count = minOf(rects.size, MAX_RECTS)

        scratch.position(0)
        var vertexCount = 0
        for (i in 0 until count) {
            val r = rects[i]
            val nx0 = r.left * 2f - 1f
            val nx1 = r.right * 2f - 1f
            // 见类注释：屏幕纵向 t → ny = 2t - 1（fbo 存的是颠倒画面）。
            val nyTop = r.top * 2f - 1f
            val nyBottom = r.bottom * 2f - 1f

            // 两个三角形：左上 / 右上 / 左下，右上 / 右下 / 左下
            scratch.put(nx0).put(nyTop)
            scratch.put(nx1).put(nyTop)
            scratch.put(nx0).put(nyBottom)
            scratch.put(nx1).put(nyTop)
            scratch.put(nx1).put(nyBottom)
            scratch.put(nx0).put(nyBottom)
            vertexCount += 6
        }
        if (vertexCount == 0) return
        scratch.position(0)

        GLES30.glUseProgram(program)
        // 纯黑不透明：关混合，保证盖死底下的画面（OsdTextPass 会开 GL_BLEND，这里必须显式关）。
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexCount * 2 * 4,
            scratch,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glDisableVertexAttribArray(posLoc)
    }

    fun release() {
        if (!initialized) return
        if (vbo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vbo = 0
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        initialized = false
    }

    companion object {
        /** 标准 `Item maxOccurs="4"`；多出来的直接丢弃（协议上不可能出现）。 */
        const val MAX_RECTS = 4
    }
}
