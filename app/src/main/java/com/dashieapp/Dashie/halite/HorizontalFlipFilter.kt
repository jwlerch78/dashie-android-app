package com.dashieapp.Dashie.halite

import android.content.Context
import android.opengl.GLES20
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Custom OpenGL filter to flip video horizontally.
 *
 * This filter un-mirrors the front camera output by flipping the texture horizontally.
 * Works with RootEncoder 2.6.1 which doesn't have built-in flip support in RotationFilterRender.
 */
class HorizontalFlipFilter : BaseFilterRender() {

    companion object {
        // Vertex shader (flips texture coordinates horizontally)
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                // Flip horizontally by inverting x coordinate: (1.0 - x, y)
                vTextureCoord = vec2(1.0 - aTextureCoord.x, aTextureCoord.y);
            }
        """

        // Fragment shader (standard passthrough)
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }
        """
    }

    // Vertex data for full-screen quad
    private val SQUARE_VERTEX = floatArrayOf(
        // X, Y
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        1.0f, 1.0f
    )

    // Texture coordinates (normal, before flip)
    private val TEXTURE_VERTEX = floatArrayOf(
        // U, V
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    private var program = 0
    private var aPositionHandle = 0
    private var aTextureHandle = 0
    private var uTextureHandle = 0

    private val squareVertexBuffer = ByteBuffer.allocateDirect(SQUARE_VERTEX.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(SQUARE_VERTEX)
            position(0)
        }

    private val textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTEX.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(TEXTURE_VERTEX)
            position(0)
        }

    override fun initGlFilter(context: Context?) {
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vertexShader, VERTEX_SHADER)
        GLES20.glCompileShader(vertexShader)

        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fragmentShader, FRAGMENT_SHADER)
        GLES20.glCompileShader(fragmentShader)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

        squareVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, squareVertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        textureVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer)
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        GLES20.glUniform1i(uTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun disableResources() {
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureHandle)
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
    }
}
