package com.scantowatch.demo

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders one frame: camera feed, then the video quad locked to the tracked image.
 *
 * All callbacks fire on the GL thread — the listener is responsible for hopping
 * to the main thread before touching ExoPlayer or any UI.
 */
class ArRenderer(
    private val listener: Listener
) : GLSurfaceView.Renderer {

    interface Listener {
        /** The GL context is up and the video surface exists. */
        fun onVideoSurfaceReady(surfaceTexture: SurfaceTexture)
        /** Called when the tracked/untracked state flips, not every frame. */
        fun onTrackingChanged(tracking: Boolean)
    }

    private val background = BackgroundRenderer()
    private val videoQuad = VideoQuadRenderer()

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    @Volatile var session: Session? = null
    private var cameraTextureSet = false
    private var wasTracking = false

    companion object { private const val TAG = "ArRenderer" }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        videoQuad.createOnGlThread { st -> listener.onVideoSurfaceReady(st) }
        cameraTextureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // Orientation is locked to portrait in the manifest, so rotation is fixed.
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return

        // Must be bound before the first update() or ARCore has nowhere to write.
        if (!cameraTextureSet) {
            session.setCameraTextureName(background.textureId)
            cameraTextureSet = true
        }

        try {
            // Pull the newest decoded video frame while we're on the GL thread.
            videoQuad.updateTexture()

            val frame = session.update()
            background.draw(frame)

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                reportTracking(false)
                return
            }

            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
            camera.getViewMatrix(viewMatrix, 0)

            // getAllTrackables (not getUpdatedTrackables) so the video keeps
            // drawing on frames where the image produced no fresh update.
            val images = session.getAllTrackables(AugmentedImage::class.java)
            var drewAny = false

            for (image in images) {
                if (image.trackingState != TrackingState.TRACKING) continue
                // LAST_KNOWN_POSE means ARCore is extrapolating from memory rather
                // than actually seeing the image. Drawing then makes the video
                // hang in mid-air after the marker leaves frame.
                if (image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue

                image.centerPose.toMatrix(modelMatrix, 0)
                videoQuad.draw(
                    modelMatrix, viewMatrix, projectionMatrix,
                    image.extentX, image.extentZ
                )
                drewAny = true
                break // demo tracks a single marker
            }

            reportTracking(drewAny)

        } catch (t: Throwable) {
            // Never let an exception escape onDrawFrame — it kills the GL thread
            // and the screen freezes with no error shown.
            Log.e(TAG, "Error during rendering", t)
        }
    }

    private fun reportTracking(tracking: Boolean) {
        if (tracking != wasTracking) {
            wasTracking = tracking
            listener.onTrackingChanged(tracking)
        }
    }

    fun release() = videoQuad.release()
}
