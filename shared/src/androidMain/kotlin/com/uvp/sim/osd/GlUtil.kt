package com.uvp.sim.osd

import android.opengl.GLES30

/**
 * GL 工具 — shader 编译 / program link / 错误检查。
 *
 * 编译 / link 失败抛 [GlException],调用方可 catch 后走 fallback。
 */
internal object GlUtil {

    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES30.glCreateProgram()
        if (program == 0) throw GlException("glCreateProgram returned 0")
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw GlException("program link failed: $log")
        }

        // shader 已 link 进 program,detach + delete 即可
        GLES30.glDetachShader(program, vs)
        GLES30.glDetachShader(program, fs)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) throw GlException("glCreateShader($type) returned 0")
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            val typeStr = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
            throw GlException("$typeStr shader compile failed: $log")
        }
        return shader
    }

    /** 跑完 GL 调用后检查是否有错,有错抛 [GlException]。debug 用,正式路径不要每帧调(开销)。 */
    fun checkError(tag: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            throw GlException("$tag: glGetError 0x${err.toString(16)}")
        }
    }
}

internal class GlException(message: String) : RuntimeException(message)

/**
 * "#RRGGBB" 或 "#AARRGGBB" → ARGB int。
 *
 * 解析失败默认返回不透明白色,渲染层不会因为颜色字段崩。
 */
internal fun parseColor(hex: String): Int {
    val s = hex.trim().removePrefix("#")
    return try {
        when (s.length) {
            6 -> 0xFF000000.toInt() or s.toInt(16)
            8 -> s.toLong(16).toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    } catch (_: NumberFormatException) {
        0xFFFFFFFF.toInt()
    }
}
