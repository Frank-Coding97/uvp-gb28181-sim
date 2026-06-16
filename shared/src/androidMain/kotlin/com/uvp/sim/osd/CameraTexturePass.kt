package com.uvp.sim.osd

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 摄像头 OES external texture 渲染 pass — 全屏四边形采样 SurfaceTexture。
 *
 * 用法(GL thread):
 * ```
 * val pass = CameraTexturePass()
 * pass.init()
 * val oesTexId = pass.cameraTextureId  // 给 SurfaceTexture(oesTexId) 用
 * // 每帧:
 * pass.draw(surfaceTexture.transformMatrix, viewportWidth, viewportHeight)
 * pass.release()
 * ```
 *
 * 这是 OSD 渲染管线的 Pass 1,后接 [OsdTextPass]。
 */
internal class CameraTexturePass {

    private var program: Int = 0
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var uTextureLoc: Int = 0
    private var uTexMatrixLoc: Int = 0

    private var vbo: Int = 0
    var cameraTextureId: Int = 0
        private set

    private val texMatrix = FloatArray(16)
    private var initialized = false

    fun init() {
        if (initialized) return

        program = GlUtil.createProgram(OsdShaders.CAMERA_VERTEX, OsdShaders.CAMERA_FRAGMENT_OES)
        aPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES30.glGetUniformLocation(program, "uTexture")
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")

        // 全屏四边形:NDC pos(xy)+ tex coord(xy),共 4 顶点 × 4 float = 16 float。
        // 顺序按 GL_TRIANGLE_STRIP 排列。SurfaceTexture 自带 Y 翻转,
        // tex coord 走标准方向,翻转由 transform matrix 处理。
        val verts = floatArrayOf(
            // x,    y,    u,   v
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(verts)
            .position(0) as FloatBuffer

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        vbo = vboArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)

        // OES external texture(SurfaceTexture 后续 attach)
        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        cameraTextureId = texArr[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        Matrix.setIdentityM(texMatrix, 0)
        initialized = true
    }

    /**
     * 渲染一帧到当前 framebuffer(可以是 fbo 也可以是 default framebuffer)。
     *
     * @param transformMatrix SurfaceTexture.getTransformMatrix() 拿到的矩阵
     * @param viewportWidth 输出区域宽
     * @param viewportHeight 输出区域高
     */
    fun draw(transformMatrix: FloatArray, viewportWidth: Int, viewportHeight: Int) {
        check(initialized) { "init() first" }

        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(uTextureLoc, 0)

        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, transformMatrix, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(aTexCoordLoc)
        GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aTexCoordLoc)
    }

    fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (cameraTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
        program = 0
        vbo = 0
        cameraTextureId = 0
        initialized = false
    }
}
