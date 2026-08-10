package app.unora.webrtc

import android.content.Context
import android.media.AudioAttributes
import app.unora.capture.PlaybackAudioInput
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** Owns native WebRTC resources for the duration of a party. */
class WebRtcFactory(context: Context) : AutoCloseable {
    val eglBase: EglBase = EglBase.create()
    val factory: PeerConnectionFactory
    /** Receives PCM solely from AudioPlaybackCapture, never a microphone. */
    val playbackAudioInput = PlaybackAudioInput(context.applicationContext)
    private val audioDeviceModule: JavaAudioDeviceModule
    private val audioSource: AudioSource
    val playbackAudioTrack: AudioTrack

    init {
        val appContext = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions(),
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioBufferCallback(playbackAudioInput)
            .setInputSampleRate(48_000)
            .setOutputSampleRate(48_000)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .createAudioDeviceModule()
        // The callback above becomes the sole driver; WebRTC never opens its microphone AudioRecord.
        audioDeviceModule.setAudioRecordEnabled(false)
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            // ScreenCapturerAndroid produces GPU texture frames. The default factories can pass
            // those frames through the Android MediaCodec path without a costly texture -> I420
            // conversion, while retaining WebRTC's software fallback when a codec is unavailable.
            // Forcing SoftwareVideoEncoderFactory at 720p/24 starved video on the affected Galaxy:
            // the audio sender stayed healthy, but no useful video frames reached the viewer.
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ true,
                ),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        audioSource = factory.createAudioSource(mediaAudioConstraints())
        playbackAudioTrack = factory.createAudioTrack("unora-playback-audio", audioSource)
    }

    override fun close() {
        playbackAudioInput.stop()
        playbackAudioTrack.dispose()
        audioSource.dispose()
        factory.dispose()
        audioDeviceModule.release()
        eglBase.release()
    }

    private fun mediaAudioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
    }
}
