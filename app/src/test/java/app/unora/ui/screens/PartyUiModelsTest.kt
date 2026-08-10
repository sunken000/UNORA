package app.unora.ui.screens

import app.unora.model.PartyPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class PartyUiModelsTest {
    @Test
    fun connectedHostCanStartSharingAgainAfterStopping() {
        assertEquals(StreamUiStatus.WAITING_FOR_SHARE, streamStatusFor(PartyPhase.Connected, shareActive = false))
    }

    @Test
    fun activeShareIsLive() {
        assertEquals(StreamUiStatus.LIVE, streamStatusFor(PartyPhase.Sharing, shareActive = true))
    }

    @Test
    fun stoppedViewerReturnsToWaitingState() {
        assertEquals(StreamUiStatus.WAITING_FOR_SHARE, streamStatusFor(PartyPhase.WaitingForShare, shareActive = false))
    }
}
