package app.unora.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.unora.webrtc.WebRtcFactory
import org.webrtc.CapturerObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.min

/**
 * Native screen capture path: MediaProjection -> ScreenCapturerAndroid -> VideoSource -> VideoTrack.
 * It must be started by [MediaProjectionService] after the user grants a fresh projection token.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val webRtcFactory: WebRtcFactory,
    private val onStateChanged: (State) -> Unit = {},
) : AutoCloseable {
    enum class State { IDLE, STARTING, SHARING, SHARING_WITHOUT_AUDIO, STOPPED, REVOKED, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var capturedFrameCount = 0L
    var videoTrack: VideoTrack? = null
        private set

    @Synchronized
    fun start(resultCode: Int, resultData: Intent): VideoTrack? {
        if (videoTrack != null) return videoTrack
        if (resultCode != Activity.RESULT_OK) {
            onStateChanged(State.ERROR)
            return null
        }
        onStateChanged(State.STARTING)
        return try {
            val source = webRtcFactory.factory.createVideoSource(true)
            val helper = SurfaceTextureHelper.create("unora-screen-capture", webRtcFactory.eglBase.eglBaseContext)
            val screenCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) {
                    mainHandler.post {
                        val (newWidth, newHeight) = captureSize(width, height)
                        applyCaptureFormat(newWidth, newHeight)
                    }
                }

                override fun onStop() {
                    mainHandler.post {
                        stopInternal(State.REVOKED)
                        MediaProjectionService.stop(context)
                    }
                }
            })
            // Publish ownership immediately so every partially initialized resource is released
            // by the common failure path below.
            videoSource = source
            textureHelper = helper
            capturer = screenCapturer
            val sourceObserver = source.capturerObserver
            screenCapturer.initialize(
                helper,
                context.applicationContext,
                object : CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) {
                        Log.i(TAG, "capturerStarted success=$success")
                        sourceObserver.onCapturerStarted(success)
                    }

                    override fun onCapturerStopped() {
                        Log.i(TAG, "capturerStopped frames=$capturedFrameCount")
                        sourceObserver.onCapturerStopped()
                    }

                    override fun onFrameCaptured(frame: VideoFrame) {
                        capturedFrameCount++
                        if (capturedFrameCount == 1L || capturedFrameCount % 240L == 0L) {
                            Log.i(
                                TAG,
                                "capturedFrame count=$capturedFrameCount " +
                                    "size=${frame.rotatedWidth}x${frame.rotatedHeight} " +
                                    "rotation=${frame.rotation}",
                            )
                        }
                        sourceObserver.onFrameCaptured(frame)
                    }
                },
            )
            val metrics = context.resources.displayMetrics
            val (initialWidth, initialHeight) = captureSize(metrics.widthPixels, metrics.heightPixels)
            captureWidth = initialWidth
            captureHeight = initialHeight
            screenCapturer.startCapture(
                initialWidth,
                initialHeight,
                TARGET_FPS,
            )
            val track = webRtcFactory.factory.createVideoTrack(VIDEO_TRACK_ID, source)
            track.setEnabled(true)
            videoTrack = track

            // ScreenCapturerAndroid owns the one-use MediaProjection token. Reusing its active
            // projection is valid; calling getMediaProjection a second time is not (Android 14+).
            val projection = screenCapturer.mediaProjection
            val audioStarted = projection?.let(webRtcFactory.playbackAudioInput::start) == true
            onStateChanged(if (audioStarted) State.SHARING else State.SHARING_WITHOUT_AUDIO)
            track
        } catch (error: Throwable) {
            Log.e(TAG, "screen capture failed", error)
            stopInternal(State.ERROR)
            null
        }
    }

    @Synchronized
    fun stop() = stopInternal(State.STOPPED)

    @Synchronized
    fun updateCaptureFormat() {
        val metrics = context.resources.displayMetrics
        val (width, height) = captureSize(metrics.widthPixels, metrics.heightPixels)
        applyCaptureFormat(width, height)
    }

    @Synchronized
    private fun stopInternal(state: State) {
        val hadResources = capturer != null || videoTrack != null || videoSource != null || textureHelper != null
        if (!hadResources && state == State.STOPPED) return
        webRtcFactory.playbackAudioInput.stop()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        textureHelper?.dispose()
        textureHelper = null
        captureWidth = 0
        captureHeight = 0
        capturedFrameCount = 0L
        onStateChanged(state)
    }

    /**
     * Samsung reports captured-content resize again after changeCaptureFormat. Calling it for an
     * identical size creates a tight VirtualDisplay resize loop, starving the renderer surface.
     */
    private fun applyCaptureFormat(width: Int, height: Int) {
        if (capturer == null || width == captureWidth && height == captureHeight) return
        Log.i(TAG, "captureFormat ${captureWidth}x$captureHeight -> ${width}x$height")
        captureWidth = width
        captureHeight = height
        runCatching { capturer?.changeCaptureFormat(width, height, TARGET_FPS) }
    }

    override fun close() = stop()

    private fun captureSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val width = sourceWidth.coerceAtLeast(2)
        val height = sourceHeight.coerceAtLeast(2)
        val landscape = width >= height
        // 720p/24 keeps software VP8 real-time on mid-range phones and avoids the encoder stalls
        // that appeared as an audio-only stream at 1080p/30 on some Samsung devices.
        val maxWidth = if (landscape) 1_280 else 720
        val maxHeight = if (landscape) 720 else 1_280
        val scale = min(1f, min(maxWidth.toFloat() / width, maxHeight.toFloat() / height))
        val outputWidth = (width * scale).toInt().coerceAtLeast(2) and -2
        val outputHeight = (height * scale).toInt().coerceAtLeast(2) and -2
        return outputWidth to outputHeight
    }

    private companion object {
        const val TAG = "UnoraVideoCapture"
        const val VIDEO_TRACK_ID = "unora-screen-video"
        const val TARGET_FPS = 24
    }
}
