package app.unora.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground-service owner for a visibly active MediaProjection session. */
class MediaProjectionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSharing()
            ACTION_START -> {
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        createNotification(),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        } else {
                            0
                        },
                    )
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    val resultData = intent.parcelableIntentExtra(EXTRA_RESULT_DATA)
                    if (resultData == null || MediaProjectionRuntime.start(resultCode, resultData) == null) {
                        Log.e(TAG, "MediaProjection failed to start after foreground promotion")
                        stopSharing()
                    }
                } catch (error: Throwable) {
                    // A service exception is process-fatal if it escapes onStartCommand. Keep a
                    // rejected projection/FGS transition contained and return the UI to idle.
                    Log.e(TAG, "MediaProjection foreground service failed", error)
                    stopSharing()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { MediaProjectionRuntime.stop() }
            .onFailure { Log.w(TAG, "projection cleanup failed", it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSharing() {
        runCatching { MediaProjectionRuntime.stop() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createNotification(): Notification {
        createChannel()
        val stopIntent = Intent(this, MediaProjectionService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Compartilhando no Unora")
            .setContentText("Sua tela está sendo transmitida para a party.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Parar", stopPendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun Intent.parcelableIntentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, Intent::class.java)
        else @Suppress("DEPRECATION") (getParcelableExtra(key) as? Intent)

    companion object {
        private const val TAG = "UnoraProjectionService"
        private const val CHANNEL_ID = "unora_screen_sharing"
        private const val NOTIFICATION_ID = 2201
        private const val ACTION_START = "app.unora.capture.START"
        private const val ACTION_STOP = "app.unora.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** Call immediately after Activity Result returns RESULT_OK; never cache that token. */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, MediaProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to launch MediaProjection service", error)
                MediaProjectionRuntime.stop()
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MediaProjectionService::class.java)) }
        }
    }
}
