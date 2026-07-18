package com.scantowatch.demo

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView

/**
 * The AR screen, embedded into Flutter as a platform view.
 *
 * Owns the ARCore session, the GL surface and the ExoPlayer instance, and keeps
 * them in step with the host activity's lifecycle.
 */
class ArVideoView(
    private val activity: Activity,
    private val channel: MethodChannel
) : PlatformView, ArRenderer.Listener {

    private val glView = GLSurfaceView(activity)
    private val renderer = ArRenderer(this)
    private val main = Handler(Looper.getMainLooper())

    private var session: Session? = null
    private var player: ExoPlayer? = null
    private var videoSurface: Surface? = null
    private var installRequested = false
    private var pendingSurfaceTexture: SurfaceTexture? = null

    // resume() arrives from two places — the view factory on creation and the
    // activity's onResume — so it has to be idempotent.
    private var resumed = false

    companion object {
        private const val TAG = "ArVideoView"

        /** Marker file in android/app/src/main/assets/. */
        private const val MARKER_ASSET = "marker.png"

        /** Video in android/app/src/main/assets/. */
        private const val VIDEO_ASSET = "asset:///demo_video.mp4"

        /**
         * Physical width of the printed marker, in METRES. ARCore uses this to
         * scale the overlay and to lock on faster. Measure your actual print and
         * set it — a wrong value here makes the video the wrong size.
         */
        private const val MARKER_WIDTH_METERS = 0.20f
    }

    init {
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun getView(): View = glView

    // ---------------------------------------------------------------- lifecycle

    fun resume() {
        if (resumed) return
        if (session == null && !createSession()) return

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            report("error", "Camera unavailable. Close other camera apps and retry.")
            session = null
            return
        }

        glView.onResume()
        renderer.session = session
        resumed = true
    }

    fun pause() {
        if (!resumed) return
        resumed = false
        // Order matters: pausing the GL view first guarantees no in-flight
        // onDrawFrame is still calling into a session we are about to pause.
        glView.onPause()
        session?.pause()
        player?.pause()
    }

    private fun createSession(): Boolean {
        try {
            when (com.google.ar.core.ArCoreApk.getInstance()
                .requestInstall(activity, !installRequested)) {
                com.google.ar.core.ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false // resume() runs again once the user returns
                }
                com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val s = Session(activity)
            val config = Config(s).apply {
                focusMode = Config.FocusMode.AUTO
                // Required for a continuously-rendering GLSurfaceView, otherwise
                // update() blocks waiting for a new camera frame.
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                augmentedImageDatabase = buildImageDatabase(s) ?: return false
            }
            s.configure(config)
            session = s
            return true

        } catch (e: UnavailableArcoreNotInstalledException) {
            report("error", "Google Play Services for AR is not installed.")
        } catch (e: UnavailableUserDeclinedInstallationException) {
            report("error", "Google Play Services for AR is required to run this demo.")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            report("error", "This device does not support ARCore.")
        } catch (e: UnavailableApkTooOldException) {
            report("error", "Please update Google Play Services for AR.")
        } catch (e: UnavailableSdkTooOldException) {
            report("error", "This app is out of date and needs rebuilding.")
        } catch (e: Exception) {
            Log.e(TAG, "Session creation failed", e)
            report("error", "Could not start AR: ${e.message}")
        }
        return false
    }

    private fun buildImageDatabase(session: Session): AugmentedImageDatabase? {
        return try {
            val bitmap = activity.assets.open(MARKER_ASSET).use {
                BitmapFactory.decodeStream(it)
            } ?: run {
                report("error", "Could not decode $MARKER_ASSET.")
                return null
            }

            AugmentedImageDatabase(session).apply {
                // Throws if ARCore scores the image below its quality threshold —
                // which is the single most useful signal about whether the
                // artwork will track well. Surface it rather than swallowing it.
                addImage("marker", bitmap, MARKER_WIDTH_METERS)
            }
        } catch (e: ImageInsufficientQualityException) {
            report("error",
                "Marker image quality is too low for ARCore to track.\n\n" +
                "Needs more high-contrast, asymmetric detail. Check it with " +
                "the arcoreimg tool — aim for a score of 75+.")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Image database failed", e)
            report("error", "Could not load marker image: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------- renderer callbacks

    /** GL thread. */
    override fun onVideoSurfaceReady(surfaceTexture: SurfaceTexture) {
        pendingSurfaceTexture = surfaceTexture
        main.post { setupPlayer(surfaceTexture) }
    }

    /** GL thread. */
    override fun onTrackingChanged(tracking: Boolean) {
        main.post {
            if (tracking) player?.play() else player?.pause()
            report("tracking", tracking)
        }
    }

    // ------------------------------------------------------------------ player

    private fun setupPlayer(surfaceTexture: SurfaceTexture) {
        if (player != null) return
        try {
            videoSurface = Surface(surfaceTexture)
            player = ExoPlayer.Builder(activity).build().apply {
                setMediaItem(MediaItem.fromUri(VIDEO_ASSET))
                repeatMode = Player.REPEAT_MODE_ALL
                setVideoSurface(videoSurface)
                playWhenReady = false // starts only once the marker is tracked
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Player setup failed", e)
            report("error", "Could not load the video: ${e.message}")
        }
    }

    private fun report(method: String, arg: Any) {
        main.post {
            try { channel.invokeMethod(method, arg) }
            catch (e: Exception) { Log.e(TAG, "channel send failed", e) }
        }
    }

    // ----------------------------------------------------------------- dispose

    override fun dispose() {
        player?.release(); player = null
        videoSurface?.release(); videoSurface = null
        renderer.release()
        renderer.session = null
        session?.close(); session = null
        pendingSurfaceTexture = null
    }
}
