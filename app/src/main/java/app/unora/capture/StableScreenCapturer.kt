package app.unora.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * MediaProjection capturer with an explicit lifecycle tailored for Android 14+ app/window sharing.
 *
 * Differences from the generic ScreenCapturerAndroid path:
 * - the SurfaceTexture listener is attached before VirtualDisplay creation so the initial frame
 *   cannot be lost between producer startup and listener registration;
 * - AUTO_MIRROR matches the Android MediaProjection reference implementation;
 * - resize reuses the one allowed VirtualDisplay instead of creating another projection session;
 * - density comes from the real device rather than a fixed synthetic value.
 */
internal class StableScreenCapturer(
    private val permissionData: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback,
    private val densityDpi: Int,
) : VideoCapturer, VideoSink {
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var projectionManager: MediaProjectionManager? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputSurface: Surface? = null
    private var disposed = false
    private var width = 0
    private var height = 0

    var mediaProjection: MediaProjection? = null
        private set

    var numCapturedFrames: Long = 0L
        private set

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver,
    ) {
        check(!disposed) { "Capturer already disposed" }
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        projectionManager = applicationContext.getSystemService(MediaProjectionManager::class.java)
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        check(!disposed) { "Capturer already disposed" }
        check(virtualDisplay == null) { "Capture already started" }
        val helper = checkNotNull(surfaceTextureHelper) { "Capturer not initialized" }
        val observer = checkNotNull(capturerObserver) { "Capturer not initialized" }
        val manager = checkNotNull(projectionManager) { "MediaProjectionManager unavailable" }

        this.width = width.coerceAtLeast(2)
        this.height = height.coerceAtLeast(2)

        val projection = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, permissionData)) {
            "MediaProjection permission token rejected"
        }
        mediaProjection = projection
        projection.registerCallback(mediaProjectionCallback, helper.handler)

        // Register the consumer first. SurfaceTextureHelper deliberately discards a pending frame
        // when a listener is attached; attaching before VirtualDisplay creation guarantees the
        // first producer frame belongs to this capture session.
        helper.setTextureSize(this.width, this.height)
        helper.startListening(this)

        val surface = Surface(helper.surfaceTexture)
        outputSurface = surface
        val display = projection.createVirtualDisplay(
            "UNORA_ScreenCapture",
            this.width,
            this.height,
            densityDpi.coerceAtLeast(1),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            helper.handler,
        )
        if (display == null) {
            helper.stopListening()
            outputSurface?.release()
            outputSurface = null
            projection.unregisterCallback(mediaProjectionCallback)
            projection.stop()
            mediaProjection = null
            observer.onCapturerStarted(false)
            error("MediaProjection could not create VirtualDisplay")
        }
        virtualDisplay = display
        observer.onCapturerStarted(true)
    }

    @Synchronized
    override fun stopCapture() {
        val helper = surfaceTextureHelper ?: return
        val observer = capturerObserver
        val display = virtualDisplay
        val surface = outputSurface
        val projection = mediaProjection

        virtualDisplay = null
        outputSurface = null
        mediaProjection = null

        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            runCatching { helper.stopListening() }
            runCatching { display?.release() }
            runCatching { surface?.release() }
            if (projection != null) {
                runCatching { projection.unregisterCallback(mediaProjectionCallback) }
                runCatching { projection.stop() }
            }
            observer?.onCapturerStopped()
        }
    }

    /** Resize the existing capture session; never creates a second VirtualDisplay/token use. */
    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        val helper = surfaceTextureHelper ?: return
        val display = virtualDisplay ?: return
        val newWidth = width.coerceAtLeast(2)
        val newHeight = height.coerceAtLeast(2)
        if (newWidth == this.width && newHeight == this.height) return
        this.width = newWidth
        this.height = newHeight

        // setTextureSize posts its bookkeeping update to the helper handler. Posting the display
        // resize afterwards preserves ordering and avoids blocking the main thread on EGL work.
        helper.setTextureSize(newWidth, newHeight)
        helper.handler.post {
            if (virtualDisplay !== display) return@post
            runCatching {
                display.resize(newWidth, newHeight, densityDpi.coerceAtLeast(1))
                val replacement = Surface(helper.surfaceTexture)
                display.surface = replacement
                outputSurface?.release()
                outputSurface = replacement
            }
        }
    }

    override fun onFrame(frame: VideoFrame) {
        numCapturedFrames++
        capturerObserver?.onFrameCaptured(frame)
    }

    override fun dispose() {
        disposed = true
    }

    override fun isScreencast(): Boolean = true
}
