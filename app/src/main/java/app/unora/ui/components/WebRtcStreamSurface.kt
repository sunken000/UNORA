package app.unora.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Displays a WebRTC video track with WebRTC's supported Android renderer.
 *
 * The previous hand-written TextureView wrapper acknowledged a frame as soon as it was submitted
 * to EGL, even when Android had not displayed it. That made a permanently black surface look
 * "live" to the rest of the UI. SurfaceViewRenderer owns the surface lifecycle, reports the first
 * frame only after EGL renders it, and is the path exercised by upstream WebRTC on Android.
 */
@Composable
fun WebRtcStreamSurface(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    onFirstFrameRendered: () -> Unit = {},
    onFrameResolutionChanged: (width: Int, height: Int, rotation: Int) -> Unit = { _, _, _ -> },
) {
    // AndroidView keeps the View returned by its first factory call while the composition node
    // remains at the same position. Merely remembering a new renderer for a new track therefore
    // routes frames into an object that has no Android Surface ("Dropping frame - No surface").
    // Key the complete subtree so the old AndroidView is disposed and the replacement renderer is
    // actually attached to the window whenever WebRTC publishes a different track.
    key(track) {
        TrackSurface(
            track = track,
            eglContext = eglContext,
            modifier = modifier,
            onFirstFrameRendered = onFirstFrameRendered,
            onFrameResolutionChanged = onFrameResolutionChanged,
        )
    }
}

@Composable
private fun TrackSurface(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
    onFrameResolutionChanged: (width: Int, height: Int, rotation: Int) -> Unit,
) {
    val context = LocalContext.current
    val currentFirstFrameCallback = rememberUpdatedState(onFirstFrameRendered)
    val currentResolutionCallback = rememberUpdatedState(onFrameResolutionChanged)
    // A SurfaceViewRenderer reports its first frame once per instance. Recreate it with the track
    // so reconnecting cannot reuse a stale "first frame" state from the previous receiver.
    // EglBase.eglBaseContext may return a fresh wrapper object for the same native EGL context.
    // It must not be a remember key: the first-frame state update would otherwise replace this
    // renderer immediately after it successfully drew, leaving the replacement without a surface.
    // The enclosing track key is the renderer's intentional lifecycle boundary.
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            setSecure(false)
            setZOrderOnTop(false)
            setZOrderMediaOverlay(false)
            init(
                eglContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        Log.i(TAG, "firstFrameRendered track=${track?.id()}")
                        post { currentFirstFrameCallback.value.invoke() }
                    }

                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        Log.i(TAG, "frameResolution ${width}x$height rotation=$rotation track=${track?.id()}")
                        post { currentResolutionCallback.value.invoke(width, height, rotation) }
                    }
                },
            )
            setMirror(false)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            disableFpsReduction()
        }
    }

    DisposableEffect(track, renderer) {
        renderer.clearImage()
        track?.addSink(renderer)
        onDispose {
            runCatching { track?.removeSink(renderer) }
            renderer.clearImage()
        }
    }
    DisposableEffect(renderer) {
        onDispose(renderer::release)
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier,
    )
}

private const val TAG = "UnoraVideoRenderer"
