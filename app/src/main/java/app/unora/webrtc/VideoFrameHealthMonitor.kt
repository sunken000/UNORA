package app.unora.webrtc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.max
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

enum class VideoFrameHealth { ACTIVE, BLACK_FRAMES, NO_FRAMES }

/**
 * Samples a tiny grid from occasional decoded/captured frames. MediaProjection deliberately
 * replaces FLAG_SECURE/DRM content with black pixels; detecting that condition lets the UI
 * distinguish it from WebRTC negotiation or network failure.
 */
class VideoFrameHealthMonitor(
    private val onHealthChanged: (VideoFrameHealth) -> Unit,
) : VideoSink, AutoCloseable {
    private val detector = BlackFrameSequenceDetector()
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var frameNumber = 0
    @Volatile private var lastFrameAtMs = SystemClock.elapsedRealtime()
    @Volatile private var currentHealth: VideoFrameHealth? = null

    @Volatile
    private var closed = false

    private val frameWatchdog = object : Runnable {
        override fun run() {
            if (closed) return
            if (SystemClock.elapsedRealtime() - lastFrameAtMs >= NO_FRAME_TIMEOUT_MS) {
                report(VideoFrameHealth.NO_FRAMES)
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    init {
        watchdogHandler.postDelayed(frameWatchdog, NO_FRAME_TIMEOUT_MS)
    }

    override fun onFrame(frame: VideoFrame) {
        if (closed) return
        lastFrameAtMs = SystemClock.elapsedRealtime()
        if (currentHealth == VideoFrameHealth.NO_FRAMES) report(VideoFrameHealth.ACTIVE)
        if (++frameNumber % SAMPLE_EVERY_N_FRAMES != 0) return
        val i420 = frame.buffer.toI420() ?: return
        try {
            val activityScore = centralActivityScore(
                data = i420.dataY,
                stride = i420.strideY,
                width = i420.width,
                height = i420.height,
            )
            detector.observe(activityScore)?.let(::report)
        } finally {
            i420.release()
        }
    }

    override fun close() {
        closed = true
        watchdogHandler.removeCallbacks(frameWatchdog)
    }

    /**
     * Uses the 90th percentile from the central 75% of the frame. Looking only for the single
     * brightest pixel missed the common protected-video shape: black video in the middle with a
     * bright toolbar around it. A percentile also ignores a subtitle or playback icon on an
     * otherwise capture-blocked surface.
     */
    private fun centralActivityScore(
        data: ByteBuffer,
        stride: Int,
        width: Int,
        height: Int,
    ): Int {
        if (width <= 0 || height <= 0 || stride <= 0) return 0
        val bytes = data.duplicate()
        val base = bytes.position()
        val startX = width / 8
        val endX = width - startX
        val startY = height / 8
        val endY = height - startY
        val xStep = max(1, (endX - startX) / SAMPLE_COLUMNS)
        val yStep = max(1, (endY - startY) / SAMPLE_ROWS)
        val samples = IntArray((SAMPLE_COLUMNS + 1) * (SAMPLE_ROWS + 1))
        var count = 0
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val index = base + y * stride + x
                if (index in base until bytes.limit()) {
                    samples[count++] = bytes.get(index).toInt() and 0xFF
                }
                x += xStep
            }
            y += yStep
        }
        if (count == 0) return 0
        samples.sort(0, count)
        return samples[((count - 1) * 9) / 10]
    }

    private fun report(health: VideoFrameHealth) {
        if (closed || currentHealth == health) return
        currentHealth = health
        Log.i(TAG, "health=$health observedFrames=$frameNumber")
        onHealthChanged(health)
    }

    private companion object {
        const val SAMPLE_EVERY_N_FRAMES = 6
        const val SAMPLE_COLUMNS = 20
        const val SAMPLE_ROWS = 12
        const val NO_FRAME_TIMEOUT_MS = 8_000L
        const val WATCHDOG_INTERVAL_MS = 2_000L
        const val TAG = "UnoraVideoHealth"
    }
}

/** Pure state machine kept separate so the black-frame policy can be unit-tested. */
internal class BlackFrameSequenceDetector(
    private val blackThreshold: Int = 30,
    private val samplesBeforeBlocked: Int = 24,
) {
    private var blackSamples = 0
    private var current: VideoFrameHealth? = null

    fun observe(brightestLuma: Int): VideoFrameHealth? {
        val next = if (brightestLuma > blackThreshold) {
            blackSamples = 0
            VideoFrameHealth.ACTIVE
        } else {
            blackSamples++
            if (blackSamples >= samplesBeforeBlocked) VideoFrameHealth.BLACK_FRAMES else current
        }
        if (next == null || next == current) return null
        current = next
        return next
    }
}
