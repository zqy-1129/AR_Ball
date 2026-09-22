package com.remy.guidesphere.gl

import android.opengl.GLES20
import android.util.Log

/**
 * 极简 GLSL 程序封装：编译、链接、uniform 定位缓存、错误上报。
 */
internal class GlProgram(vertexSrc: String, fragmentSrc: String, tag: String) {

    var id: Int = 0
        private set

    private val uniformCache = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc, "$tag.vert")
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc, "$tag.frag")
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("链接 $tag 程序失败: $log")
        }
        // 链接完成后即可释放 shader 对象
        GLES20.glDetachShader(program, vs)
        GLES20.glDetachShader(program, fs)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        id = program
    }

    fun use() = GLES20.glUseProgram(id)

    fun uniform(name: String): Int = uniformCache.getOrPut(name) {
        GLES20.glGetUniformLocation(id, name)
    }

    fun set1f(name: String, v: Float) = GLES20.glUniform1f(uniform(name), v)
    fun set3f(name: String, x: Float, y: Float, z: Float) = GLES20.glUniform3f(uniform(name), x, y, z)
    fun set4x4(name: String, m: FloatArray, offset: Int = 0) =
        GLES20.glUniformMatrix4fv(uniform(name), 1, false, m, offset)

    fun release() {
        if (id != 0) {
            GLES20.glDeleteProgram(id)
            id = 0
        }
        uniformCache.clear()
    }

    private fun compile(type: Int, src: String, tag: String): Int {
        // GLSL 要求 #version 必须是程序的第一个有效内容，
        // Kotlin 多行字符串开头的换行/缩进必须清掉，否则部分驱动会编译失败
        val source = src.trimStart()
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("编译 $tag 着色器失败: $log")
        }
        return shader
    }

    companion object {
        private const val TAG = "GlProgram"
        fun logGlError(where: String) {
            var err = GLES20.glGetError()
            while (err != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "GL error at $where: 0x${Integer.toHexString(err)}")
                err = GLES20.glGetError()
            }
        }
    }
}
