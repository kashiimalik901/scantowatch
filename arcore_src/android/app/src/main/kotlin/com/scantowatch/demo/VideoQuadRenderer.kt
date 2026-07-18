package com.scantowatch.demo

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the video as a quad locked to a tracked image.
 *
 * ExoPlayer decodes into a SurfaceTexture, which we sample as an external OES
 * texture — the video never touches the CPU, so this stays cheap even at 1080p.
 */
class VideoQuadRenderer {

    var textureId = -1
        private set

    /** Created on the GL thread; ExoPlayer attaches to it from the main thread. */
    var surfaceTexture: SurfaceTexture? = null
        private set

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var mvpUniform = 0
    private var texTransformUniform = 0
    private var textureUniform = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    private val modelViewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val texTransform = FloatArray(16)

    private val frameLock = Object()
    private var frameAvailable = false

    companion object {
        /**
         * Unit quad in the X-Z plane. ARCore's augmented image pose puts +Y out of
         * the image surface, +X along its width and +Z down its height — so the
         * quad lies flat in X-Z and the model matrix scales it to the real extent.
         */
        private val QUAD_VERTICES = floatArrayOf(
            -0.5f, 0f, -0.5f,
            +0.5f, 0f, -0.5f,
            -0.5f, 0f, +0.5f,
            +0.5f, 0f, +0.5f
        )

        /** v = 0 is the top of the video, matching the image's -Z (top) edge. */
        private val QUAD_TEXCOORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            uniform mat4 u_ModelViewProjection;
            uniform mat4 u_TexTransform;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
                v_TexCoord = (u_TexTransform * vec4(a_TexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 v_TexCoord;\n" +
            "uniform samplerExternalOES u_Texture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(u_Texture, v_TexCoord);\n" +
            "}\n"
    }

    /**
     * @param onSurfaceReady invoked on the GL thread once the SurfaceTexture exists.
     *        The caller must hop to the main thread before touching ExoPlayer.
     */
    fun createOnGlThread(onSurfaceReady: (SurfaceTexture) -> Unit) {
        textureId = GlUtil.createExternalTexture()

        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener {
            synchronized(frameLock) { frameAvailable = true }
        }
        surfaceTexture = st

        vertexBuffer = ByteBuffer
            .allocateDirect(QUAD_VERTICES.size * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(QUAD_VERTICES); vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer
            .allocateDirect(QUAD_TEXCOORDS.size * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        texCoordBuffer.put(QUAD_TEXCOORDS); texCoordBuffer.position(0)

        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib      = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib      = GLES20.glGetAttribLocation(program, "a_TexCoord")
        mvpUniform          = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        texTransformUniform = GLES20.glGetUniformLocation(program, "u_TexTransform")
        textureUniform      = GLES20.glGetUniformLocation(program, "u_Texture")

        GlUtil.checkGlError("VideoQuadRenderer.createOnGlThread")
        onSurfaceReady(st)
    }

    /** Must be called on the GL thread, before draw(). */
    fun updateTexture() {
        synchronized(frameLock) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                frameAvailable = false
            }
        }
    }

    /**
     * @param modelMatrix the tracked image's centre pose
     * @param extentX     image width in metres
     * @param extentZ     image height in metres
     *
     * The quad is scaled to the image's exact extent, so the video fills the
     * marker precisely. Author the video at the marker's aspect ratio or it
     * will appear stretched.
     */
    fun draw(
        modelMatrix: FloatArray,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        extentX: Float,
        extentZ: Float
    ) {
        val st = surfaceTexture ?: return
        st.getTransformMatrix(texTransform)

        // Scale the unit quad up to the physical size of the tracked image.
        val scaled = modelMatrix.copyOf()
        Matrix.scaleM(scaled, 0, extentX, 1f, extentZ)

        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, scaled, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texTransformUniform, 1, false, texTransform, 0)

        vertexBuffer.position(0)
        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(
            positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(
            texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)

        GlUtil.checkGlError("VideoQuadRenderer.draw")
    }

    fun release() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
