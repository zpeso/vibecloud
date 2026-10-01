package dev.vibecloud.core.bridge

/**
 * Renders [MetricsHistory] samples as the dashboard's `/bridge/metrics` JSON document. Kept
 * separate from the endpoint so tests can assert on the document without opening a port.
 */
internal object MetricsJson {
    fun document(history: MetricsHistory): String {
        val samples = history.all()
        val points = samples.joinToString(",") { sample ->
            JsonWriter.obj(
                "t" to JsonWriter.num(sample.timestamp.epochSecond),
                "players" to JsonWriter.num(sample.playersOnline),
                "running" to JsonWriter.num(sample.runningServices),
                "services" to JsonWriter.num(sample.totalServices),
                "tps" to (sample.worstTps?.let { JsonWriter.num(it) } ?: "null"),
                "ram" to (sample.averageRamUsage?.let { JsonWriter.num(it) } ?: "null"),
                "cpu" to (sample.hostCpu?.let { JsonWriter.num(it) } ?: "null"),
                "sysram" to (sample.hostSysRam?.let { JsonWriter.num(it) } ?: "null"),
                "jvmheap" to (sample.hostJvmHeap?.let { JsonWriter.num(it) } ?: "null"),
            )
        }
        return JsonWriter.obj(
            "samples" to samples.size.toString(),
            "points" to "[$points]",
        )
    }
}
