package app.unora.webrtc

import org.webrtc.StatsObserver
import org.webrtc.StatsReport

enum class ConnectionQuality { EXCELLENT, GOOD, UNSTABLE, UNKNOWN }

data class ConnectionStats(
    val bitrateKbps: Int = 0,
    val rttMs: Int? = null,
    val packetLossPercent: Int? = null,
    val fps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val quality: ConnectionQuality = ConnectionQuality.UNKNOWN,
)

internal object ConnectionStatsMapper {
    fun from(reports: Array<StatsReport>): ConnectionStats {
        val values = reports.flatMap { report -> report.values.asList() }
            .associate { it.name to it.value }
        val bitrate = values["bytesSent"]?.toLongOrNull()?.let { it / 1024 }?.toInt() ?: 0
        val rtt = values["googRtt"]?.toIntOrNull()
        val lost = values["packetsLost"]?.toDoubleOrNull()
        val received = values["packetsReceived"]?.toDoubleOrNull()
        val loss = if (lost != null && received != null && lost + received > 0) {
            ((lost * 100.0) / (lost + received)).toInt()
        } else null
        val fps = values["googFrameRateSent"]?.toIntOrNull()
        val width = values["googFrameWidthSent"]?.toIntOrNull()
        val height = values["googFrameHeightSent"]?.toIntOrNull()
        val quality = when {
            rtt != null && rtt > 450 || loss != null && loss > 8 -> ConnectionQuality.UNSTABLE
            rtt != null && rtt < 150 && (loss == null || loss < 2) -> ConnectionQuality.EXCELLENT
            rtt != null || loss != null -> ConnectionQuality.GOOD
            else -> ConnectionQuality.UNKNOWN
        }
        return ConnectionStats(bitrate, rtt, loss, fps, width, height, quality)
    }
}

internal fun statsObserver(onStats: (ConnectionStats) -> Unit) = StatsObserver { reports ->
    onStats(ConnectionStatsMapper.from(reports))
}
