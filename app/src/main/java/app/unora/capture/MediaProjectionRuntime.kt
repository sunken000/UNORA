package app.unora.capture

/**
 * Process-local bridge between party lifecycle and the foreground service. The party manager must
 * bind before requesting consent and unbind/stop on leave. Projection permissions cannot survive
 * a process restart, so persisting this data would be both invalid and unsafe.
 */
object MediaProjectionRuntime {
    @Volatile private var captureManager: ScreenCaptureManager? = null

    fun bind(manager: ScreenCaptureManager) { captureManager = manager }
    fun start(resultCode: Int, resultData: android.content.Intent) = captureManager?.start(resultCode, resultData)
    fun stop() = captureManager?.stop()
    fun unbind(manager: ScreenCaptureManager) {
        if (captureManager === manager) captureManager = null
    }
}
