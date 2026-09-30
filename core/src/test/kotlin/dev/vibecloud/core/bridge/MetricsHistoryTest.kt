package dev.vibecloud.core.bridge

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsHistoryTest {
    @Test
    fun `samples are kept oldest first and trimmed to capacity`() {
        val history = MetricsHistory(capacity = 3)
        repeat(5) { index ->
            history.record(
                MetricsHistory.Sample(
                    timestamp = Instant.ofEpochSecond(index.toLong()),
                    playersOnline = index,
                    runningServices = 1,
                    totalServices = 2,
                    worstTps = 20.0,
                    averageRamUsage = 0.5,
                ),
            )
        }
        val samples = history.all()
        assertEquals(3, samples.size)
        assertEquals(listOf(2, 3, 4), samples.map { it.playersOnline })
        assertTrue(samples.zipWithNext().all { (first, second) -> first.timestamp < second.timestamp })
    }

    @Test
    fun `json document renders points and null metrics`() {
        val history = MetricsHistory()
        history.record(
            MetricsHistory.Sample(
                timestamp = Instant.ofEpochSecond(1_000),
                playersOnline = 4,
                runningServices = 2,
                totalServices = 3,
                worstTps = 19.5,
                averageRamUsage = null,
            ),
        )
        val document = MetricsJson.document(history)
        assertTrue(document.contains("\"players\":4"), document)
        assertTrue(document.contains("\"tps\":19.5"), document)
        assertTrue(document.contains("\"ram\":null"), document)
        assertTrue(document.contains("\"samples\":1"), document)
    }

    @Test
    fun `no samples render as an empty document`() {
        val document = MetricsJson.document(MetricsHistory())
        assertTrue(document.contains("\"points\":[]"), document)
        assertNull(null)
    }
}
