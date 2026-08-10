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
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Native screen capture path: MediaProjection -> VirtualDisplay -> SurfaceTexture -> VideoSource.
 * It must be started by [MediaProjectionService] after the user grants a fresh projection token.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val webRtcFactory: WebRtcFactory,
    private val onStateChanged: (State) -> Unit = {},
) : AutoCloseable {
    enum class State { IDLE, STARTING, SHARING, SHARING_WITHOUT_AUDIO, STOPPED, REVOKED, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var capturer: StableScreenCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var capturedFrameCount = 0L
    private var capturedContentVisible: Boolean? = null
    private var pendingResize: Pair<Int, Int>? = null
    private var audioStarted = false
    private var sharingSignaled = false
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
            // Keep the MediaProjection surface at the device/window size. Scaling belongs in the
            // WebRTC source pipeline; resizing the VirtualDisplay down before its first frame is
            // a known source of OEM-specific SurfaceTexture stalls.
            source.adaptOutputFormat(OUTPUT_WIDTH, OUTPUT_HEIGHT, TARGET_FPS)

            val helper = checkNotNull(
                SurfaceTextureHelper.create("unora-screen-capture", webRtcFactory.eglContext),
            ) { "Falha ao criar SurfaceTextureHelper" }

            val metrics = context.resources.displayMetrics
            val screenCapturer = StableScreenCapturer(
                permissionData = resultData,
                densityDpi = metrics.densityDpi,
                mediaProjectionCallback = object : MediaProjection.Callback() {
                    override fun onCapturedContentResize(width: Int, height: Int) {
                        mainHandler.post {
                            val size = normalizeSurfaceSize(width, height)
                            // Android 14 can deliver this callback during VirtualDisplay startup.
                            // Do not mutate the producer surface until at least one frame proves the
                            // initial Surface/VirtualDisplay pair is functional.
                            if (capturedFrameCount == 0L) {
                                pendingResize = size
                                Log.i(TAG, "captureResize deferred ${size.first}x${size.second}")
                            } else {
                                applyCaptureFormat(size.first, size.second)
                            }
                        }
                    }

                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                        mainHandler.post {
                            capturedContentVisible = isVisible
                            Log.i(TAG, "capturedContentVisible=$isVisible frames=$capturedFrameCount")
                        }
                    }

                    override fun onStop() {
                        mainHandler.post {
                            stopInternal(State.REVOKED)
                            MediaProjectionService.stop(context)
                        }
                    }
                },
            )

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
                        if (!success) mainHandler.post { stopInternal(State.ERROR) }
                    }

                    override fun onCapturerStopped() {
                        Log.i(TAG, "capturerStopped frames=$capturedFrameCount")
                        sourceObserver.onCapturerStopped()
                    }

                    override fun onFrameCaptured(frame: VideoFrame) {
                        capturedFrameCount++
                        sourceObserver.onFrameCaptured(frame)
                        if (capturedFrameCount == 1L) {
                            Log.i(
                                TAG,
                                "firstFrame size=${frame.rotatedWidth}x${frame.rotatedHeight} rotation=${frame.rotation}",
                            )
                            mainHandler.post {
                                pendingResize?.also { pending ->
                                    pendingResize = null
                                    applyCaptureFormat(pending.first, pending.second)
                                }
                                if (!sharingSignaled && videoTrack != null) {
                                    sharingSignaled = true
                                    onStateChanged(if (audioStarted) State.SHARING else State.SHARING_WITHOUT_AUDIO)
                                }
                            }
                        } else if (capturedFrameCount % 240L == 0L) {
                            Log.i(TAG, "capturedFrame count=$capturedFrameCount size=${frame.rotatedWidth}x${frame.rotatedHeight}")
                        }
                    }
                },
            )

            val (initialWidth, initialHeight) = normalizeSurfaceSize(metrics.widthPixels, metrics.heightPixels)
            captureWidth = initialWidth
            captureHeight = initialHeight
            capturedFrameCount = 0L
            capturedContentVisible = null
            pendingResize = null
            audioStarted = false
            sharingSignaled = false

            // Create the WebRTC track before starting capture. The first-frame callback is posted
            // back to the main looper, so by the time SHARING is emitted this field is populated.
            val track = webRtcFactory.factory.createVideoTrack(VIDEO_TRACK_ID, source)
            track.setEnabled(true)
            videoTrack = track

            screenCapturer.startCapture(initialWidth, initialHeight, TARGET_FPS)

            // Screen audio uses the same active MediaProjection token. A video-only projection is
            // still valid if playback audio capture is unavailable on the device/source app.
            val projection = screenCapturer.mediaProjection
            audioStarted = projection?.let(webRtcFactory.playbackAudioInput::start) == true
            Log.i(
                TAG,
                "projectionStarted surface=${initialWidth}x$initialHeight density=${metrics.densityDpi} audio=$audioStarted",
            )
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
        val (width, height) = normalizeSurfaceSize(metrics.widthPixels, metrics.heightPixels)
        if (capturedFrameCount == 0L) pendingResize = width to height
        else applyCaptureFormat(width, height)
    }

    @Synchronized
    private fun stopInternal(state: State) {
        val hadResources = capturer != null || videoTrack != null || videoSource != null || textureHelper != null
        if (!hadResources && state == State.STOPPED) return
        webRtcFactory.playbackAudioInput.stop()
        runCatching { capturer?.stopCapture() }
            .onFailure { Log.w(TAG, "stopCapture failed", it) }
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
        capturedContentVisible = null
        pendingResize = null
        audioStarted = false
        sharingSignaled = false
        onStateChanged(state)
    }

    /** Resize only the already-created VirtualDisplay; never consumes another projection token. */
    private fun applyCaptureFormat(width: Int, height: Int) {
        if (capturer == null || width == captureWidth && height == captureHeight) return
        Log.i(TAG, "captureFormat ${captureWidth}x$captureHeight -> ${width}x$height")
        captureWidth = width
        captureHeight = height
        runCatching { capturer?.changeCaptureFormat(width, height, TARGET_FPS) }
            .onFailure { Log.w(TAG, "capture resize failed", it) }
    }

    override fun close() = stop()

    private fun normalizeSurfaceSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val width = (sourceWidth.coerceAtLeast(2) and -2).coerceAtLeast(2)
        val height = (sourceHeight.coerceAtLeast(2) and -2).coerceAtLeast(2)
        return width to height
    }

    private companion object {
        const val TAG = "UnoraVideoCapture"
        const val VIDEO_TRACK_ID = "unora-screen-video"
        const val TARGET_FPS = 24
        const val OUTPUT_WIDTH = 1_280
        const val OUTPUT_HEIGHT = 720
    }
}
