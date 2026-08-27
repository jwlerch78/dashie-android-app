package com.dashieapp.Dashie.halite

import android.content.Context
import android.opengl.GLES20
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Custom OpenGL filter that rotates video pixels by 180°.
 *
 * Used to correct upside-down camera streams when the device's camera sensor
 * is mounted rotated 180° relative to the display, AND the consumer (e.g. HA's
 * native stream integration, Fully Kiosk) ignores H.264 rotation metadata.
 *
 * Pixel rotation happens in the GPU pre-encode pipeline, so the encoded stream
 * is upright for ALL viewers (VLC, WebRTC, HA, Fully, ExoPlayer) without
 * relying on rotation metadata tags.
 *
 * Note: 90° and 270° rotation are intentionally NOT supported here. Those would
 * require swapping the encoder's width/height (portrait mode) which is a
 * separate work item — see camera RTSP portrait support.
 *
 * Implementation pattern matches HorizontalFlipFilter — same vertex/texture
 * buffer setup, same shader compile flow, single uniform texture sample.
 */
class RotateVideoFilter : BaseFilterRender() {

    companion object {
        // Vertex shader: rotate texture coordinates 180° around (0.5, 0.5)
        // Mapping: (u, v) → (1-u, 1-v)
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = vec2(1.0 - aTextureCoord.x, 1.0 - aTextureCoord.y);
            }
        """

        // Fragment shader: standard texture passthrough
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }
        """
    }

    private val SQUARE_VERTEX = floatArrayOf(
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        1.0f, 1.0f
    )

    private val TEXTURE_VERTEX = floatArrayOf(
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
