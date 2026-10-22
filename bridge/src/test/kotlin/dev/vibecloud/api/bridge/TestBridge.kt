package dev.vibecloud.api.bridge

import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.BridgeProtocol
import dev.vibecloud.transport.InMemoryRedisGateway
import dev.vibecloud.transport.RedisSubscription
import dev.vibecloud.transport.text
import java.time.Duration
import kotlin.test.fail

internal const val TEST_UUID = "d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e"

/** A facade on an in-memory gateway, with short timeouts so failures surface fast in tests. */
internal fun testCloud(
    redis: InMemoryRedisGateway,
    refreshInterval: Duration = Duration.ofSeconds(30),
    timeout: Duration = Duration.ofSeconds(2),
): VibeCloud = VibeCloud.connect { builder ->
    builder
        .gateway(redis)
        .token("test-token")
        .timeout(timeout)
        .temporaryRefreshInterval(refreshInterval)
}

/** A status document shaped exactly like the controller publishes it. */
internal fun statusJson(
    playerNames: List<String> = listOf("Steve"),
    totalPlayersOnline: Int = playerNames.size,
    group: String = "lobby",
    uuid: String = TEST_UUID,
    world: String = "world",
    ping: Int = 42,
): String {
    val players = playerNames.joinToString(",") { "\"$it\"" }
    val details = playerNames.joinToString(",") { name ->
        """{"name":"$name","uuid":"$uuid","world":"$world","ping":$ping,"health":20.0}"""
    }
    return """
        {"totals":{"groups":1,"services":1,"online":1,"players-online":$totalPlayersOnline,"agents-online":1},
         "groups":[{"name":"$group","type":"PAPER","version":"paper-test","static":true,
                    "min-services":1,"max-services":3,"always-running-services":1}],
         "services":[{"name":"lobby-1","group":"$group","type":"PAPER","state":"RUNNING","port":25566,
                      "agent-online":true,"players-online":${playerNames.size},"players":[$players],
                      "player-details":[$details]}]}
    """.trimIndent()
}

/** Publishes a status document as the controller would. */
internal fun InMemoryRedisGateway.publishStatus(json: String) = set(BridgeProtocol.STATUS_KEY, json)

/**
 * A fake backend agent: answers permission checks with [answer] (returning null leaves the request
 * for another agent, i.e. "not my player"). The answer is pushed on a separate thread, mirroring
 * how a real agent reacts asynchronously to the published action.
 */
internal fun startPermissionAgent(
    redis: InMemoryRedisGateway,
    delayMillis: Long = 0,
    answer: (player: String, node: String) -> String,
): RedisSubscription = redis.subscribe(BridgeProtocol.ACTIONS_CHANNEL) { message ->
    val action = BridgeJson.parseObject(message) ?: return@subscribe
    if (action.text("type") != BridgeProtocol.ACTION_PERMISSION) return@subscribe
    val requestId = action.text("request-id") ?: return@subscribe
    val player = action.text("player") ?: return@subscribe
    val node = action.text("node") ?: return@subscribe
    val value = answer(player, node)
    Thread {
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val key = BridgeProtocol.resultKey(requestId)
        redis.rpush(key, value)
        redis.expire(key, BridgeProtocol.RESULT_TTL_SECONDS)
    }.apply { isDaemon = true }.start()
}

/** Fails after [timeoutMillis] unless [condition] becomes true. */
internal fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    fail("timed out waiting for: $what")
}
