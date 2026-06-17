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
 * // 相机分辨率确定后(SurfaceRequest.resolution):
 * pass.setFrameSize(camW, camH, fboW, fboH)
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

        // 初始全屏四边形。v 坐标 Y 翻转(1→0):FBO 原点左下,补偿 blit 的 Y 翻转叠加。
        // setFrameSize() 调用后会按相机比例更新 VBO,默认 center-crop 铺满。
        uploadVerts(floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        ))

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
     * 按相机有效画面比例重算 NDC 坐标,避免 CameraX fallback 到非目标比例时被拉伸。
     * buffer 尺寸匹配 SurfaceRequest,这里的 camWidth/camHeight 表示显示比例。
     *
     * 默认 center-crop 铺满 FBO:不变形、不留黑边,多出的边缘被裁掉。
     */
    fun setFrameSize(
        camWidth: Int,
        camHeight: Int,
        fboWidth: Int,
        fboHeight: Int,
        cropToFill: Boolean = true
    ) {
        if (!initialized || vbo == 0) return
        if (camWidth <= 0 || camHeight <= 0 || fboWidth <= 0 || fboHeight <= 0) return

        val camAspect = camWidth.toFloat() / camHeight
        val fboAspect = fboWidth.toFloat() / fboHeight
        val fx: Float
        val fy: Float
        if (cropToFill) {
            if (camAspect > fboAspect) {
                fx = camAspect / fboAspect
                fy = 1f
            } else {
                fx = 1f
                fy = fboAspect / camAspect
            }
        } else {
            if (camAspect > fboAspect) {
                fx = 1f
                fy = fboAspect / camAspect
            } else {
                fx = camAspect / fboAspect
                fy = 1f
            }
        }
        uploadVerts(floatArrayOf(
            -fx, -fy, 0f, 1f,
             fx, -fy, 1f, 1f,
            -fx,  fy, 0f, 0f,
             fx,  fy, 1f, 0f,
        ), update = true)
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

    private fun uploadVerts(verts: FloatArray, update: Boolean = false) {
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(verts)
            .position(0) as FloatBuffer
        if (!update) {
            val vboArr = IntArray(1)
            GLES30.glGenBuffers(1, vboArr, 0)
            vbo = vboArr[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
        } else {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, verts.size * 4, buf)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }
}
