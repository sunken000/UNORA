package app.unora.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Displays a WebRTC video track with one SurfaceViewRenderer for the lifetime of this composition.
 *
 * A SurfaceView owns a native Surface whose creation/destruction is asynchronous relative to
 * Compose. Recreating the renderer when a VideoTrack changes can therefore attach the new sink
 * before Android has attached the new Surface, producing repeated "Dropping frame - No surface"
 * and a permanently black player on affected devices. Keep the native view stable and move only
 * the VideoTrack sink when WebRTC reconnects or replaces a receiver track.
 */
@Composable
fun WebRtcStreamSurface(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    onFirstFrameRendered: () -> Unit = {},
    onFrameResolutionChanged: (width: Int, height: Int, rotation: Int) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val currentFirstFrameCallback = rememberUpdatedState(onFirstFrameRendered)
    val currentResolutionCallback = rememberUpdatedState(onFrameResolutionChanged)

    // Deliberately do not key this remember with track or eglContext. EglBase implementations may
    // return a fresh Context wrapper for the same native EGL context; the WebRtcFactory itself is
    // stable for the Activity lifetime, so recreating this view from wrapper identity is harmful.
    val renderer = remember(context) {
        SurfaceViewRenderer(context).apply {
            setSecure(false)
            setZOrderOnTop(false)
            setZOrderMediaOverlay(false)
            init(
                eglContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        Log.i(TAG, "rendererFirstFrame")
                    }

                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        Log.i(TAG, "frameResolution ${width}x$height rotation=$rotation")
                        post { currentResolutionCallback.value.invoke(width, height, rotation) }
                    }
                },
            )
            setMirror(false)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            // WebRTC documents fixed-size SurfaceView scaling as potentially buggy on some devices.
            // Layout-size surfaces are slightly less optimized but much safer across OEM compositors.
            setEnableHardwareScaler(false)
            disableFpsReduction()
        }
    }

    DisposableEffect(track, renderer) {
        if (track == null) {
            renderer.clearImage()
            return@DisposableEffect onDispose { }
        }

        // RendererEvents.onFirstFrameRendered is once per renderer, while the app needs a fresh
        // confirmation for every replacement VideoTrack. FrameListener is one-shot. In WebRTC's
        // EGL path it is dispatched after the renderer has verified that an EGL surface exists;
        // scale=0 avoids bitmap allocation/readback.
        val renderedFrameListener = EglRenderer.FrameListener {
            Log.i(TAG, "trackRendered track=${track.id()}")
            renderer.post { currentFirstFrameCallback.value.invoke() }
        }

        renderer.clearImage()
        renderer.addFrameListener(renderedFrameListener, 0f)
        track.addSink(renderer)
        Log.i(TAG, "sinkAttached track=${track.id()} renderer=${System.identityHashCode(renderer)}")

        onDispose {
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.removeFrameListener(renderedFrameListener) }
            renderer.clearImage()
            Log.i(TAG, "sinkDetached track=${track.id()} renderer=${System.identityHashCode(renderer)}")
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            renderer.release()
            Log.i(TAG, "rendererReleased renderer=${System.identityHashCode(renderer)}")
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier,
    )
}

private const val TAG = "UnoraVideoRenderer"
