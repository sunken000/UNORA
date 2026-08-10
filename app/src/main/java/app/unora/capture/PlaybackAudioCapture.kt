package app.unora.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.AudioPlaybackCaptureConfiguration
import androidx.core.content.ContextCompat
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures only playback permitted by Android's AudioPlaybackCapture policy.
 *
 * This class never selects a microphone, communication audio source, SCO device, or call mode.
 * [PlaybackAudioInput] is registered with the WebRTC AudioDeviceModule. WebRTC asks it for each
 * 10-ms PCM frame, and it reads directly from this AudioRecord into the native WebRTC buffer.
 */
class PlaybackAudioCapture(private val context: Context) : AutoCloseable {
    private var audioRecord: AudioRecord? = null
    private val scratch = ThreadLocal<ByteArray>()

    fun start(mediaProjection: MediaProjection): Boolean {
        if (audioRecord != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBuffer > 0) { "Playback capture is not supported on this device" }
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer.coerceAtLeast(FRAME_BYTES * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Playback AudioRecord was not initialized" }
            audioRecord = record
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback audio recording could not start"
            }
            true
        } catch (error: Throwable) {
            audioRecord?.release()
            audioRecord = null
            false
        }
    }

    /** Called on WebRTC's audio thread. Fills the complete destination buffer or silence. */
    fun readInto(destination: ByteBuffer, byteCapacity: Int = destination.capacity()): Long {
        val bytes = scratch.get()?.takeIf { it.size >= byteCapacity } ?: ByteArray(byteCapacity).also(scratch::set)
        val count = audioRecord?.read(bytes, 0, byteCapacity, AudioRecord.READ_BLOCKING) ?: 0
        destination.clear()
        if (count > 0) destination.put(bytes, 0, count)
        while (destination.position() < byteCapacity) destination.put(0)
        destination.flip()
        return System.nanoTime()
    }

    fun stop() {
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
    }

    override fun close() = stop()

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val FRAME_BYTES = (SAMPLE_RATE_HZ / 100) * 2 * 2
    }
}

/** WebRTC's external input hook. It has no Android microphone source behind it. */
class PlaybackAudioInput(context: Context) : JavaAudioDeviceModule.AudioBufferCallback {
    private val context = context.applicationContext
    private val activeCapture = AtomicReference<PlaybackAudioCapture?>(null)

    fun start(mediaProjection: MediaProjection): Boolean {
        stop()
        val capture = PlaybackAudioCapture(context)
        return if (capture.start(mediaProjection)) {
            activeCapture.set(capture)
            true
        } else {
            capture.close()
            false
        }
    }

    fun stop() {
        activeCapture.getAndSet(null)?.close()
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        // The factory locks this module to 48 kHz / PCM16. In the short period before playback
        // capture starts, return silence; this is preferable to ever opening a microphone.
        // With WebRTC recording disabled, some versions report bytesRead as 0. The direct
        // buffer still has the native 10-ms frame capacity and must always be filled in full.
        val byteCapacity = buffer.capacity()
        return activeCapture.get()?.readInto(buffer, byteCapacity) ?: run {
            buffer.clear()
            repeat(byteCapacity) { buffer.put(0) }
            buffer.flip()
            System.nanoTime()
        }
    }
}
