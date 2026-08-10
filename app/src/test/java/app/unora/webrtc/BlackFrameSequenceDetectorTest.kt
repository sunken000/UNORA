package app.unora.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlackFrameSequenceDetectorTest {
    @Test
    fun `reports active immediately when a visible pixel is sampled`() {
        val detector = BlackFrameSequenceDetector(samplesBeforeBlocked = 3)

        assertEquals(VideoFrameHealth.ACTIVE, detector.observe(90))
        assertNull(detector.observe(120))
    }

    @Test
    fun `requires a sustained run of black frames before reporting blocked`() {
        val detector = BlackFrameSequenceDetector(samplesBeforeBlocked = 3)

        assertNull(detector.observe(16))
        assertNull(detector.observe(18))
        assertEquals(VideoFrameHealth.BLACK_FRAMES, detector.observe(20))
    }

    @Test
    fun `recovers as soon as visible content returns`() {
        val detector = BlackFrameSequenceDetector(samplesBeforeBlocked = 2)
        detector.observe(16)
        detector.observe(16)

        assertEquals(VideoFrameHealth.ACTIVE, detector.observe(80))
    }
}
