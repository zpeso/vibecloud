package dev.vibecloud.api.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibeCloudClientParserTest {
    @Test
    fun `status document is parsed into totals and services`() {
        val json = """
            {"totals":{"groups":2,"services":3,"online":2,"players-online":2},
             "groups":[{"name":"lobby","type":"PAPER"},{"name":"proxy","type":"VELOCITY"}],
             "services":[
               {"name":"lobby-1","group":"lobby","type":"PAPER","state":"RUNNING","port":25566,
                "static":true,"agent-online":true,"players-online":2,"players":["Steve","Alex"]},
               {"name":"lobby-2","group":"lobby","type":"PAPER","state":"STARTING","port":25567,
                "static":false,"agent-online":false,"players-online":null,"players":null},
               {"name":"proxy-1","group":"proxy","type":"VELOCITY","state":"RUNNING","port":25565,
                "static":true,"agent-online":false,"players-online":0,"players":[]}
             ]}
        """.trimIndent()

        val status = CloudStatusParser.parse(json)

        assertEquals(2, status.groupCount)
        assertEquals(3, status.serviceCount)
        assertEquals(2, status.onlineServices)
        assertEquals(2, status.totalPlayersOnline)
        assertEquals(3, status.services.size)

        val lobby1 = status.services[0]
        assertEquals("lobby-1", lobby1.name)
        assertEquals("RUNNING", lobby1.state)
        assertTrue(lobby1.agentOnline)
        assertEquals(2, lobby1.playersOnline)
        assertEquals(listOf("Steve", "Alex"), lobby1.players)

        val starting = status.services[1]
        assertNull(starting.playersOnline)
        assertTrue(starting.players.isEmpty())
    }

    /**
     * Regression: the status document contains `player-details` (an array of nested objects).
     * The old regex-based parser could not match service objects containing nested braces, so
     * running services were lost and `players().all()` returned an empty list even though
     * `status().totalPlayersOnline` was correct.
     */
    @Test
    fun `enriched player details carry vitals and connection metadata`() {
        val json = """
            {"totals":{"groups":1,"services":1,"online":1,"players-online":1},
             "groups":[],
             "services":[{"name":"lobby-1","group":"lobby","type":"PAPER","state":"RUNNING","port":25566,
                "agent-online":true,"players-online":1,"players":["Steve"],
                "player-details":[{"name":"Steve","uuid":"uuid-1","ping":42,"world":"world","gamemode":"SURVIVAL",
                    "health":14.5,"food":18,"level":33,"exp":0.5,"x":100.5,"y":64,"z":-200.25,
                    "client-brand":"vanilla","first-played":1600000000000,"address":"10.0.0.5",
                    "op":true,"flying":false,"saturation":7.2,"allowed-flight":false,"sneaking":true,
                    "sprinting":false,"gliding":false,"sleeping":false,"in-vehicle":"PIG"}]}]}
        """.trimIndent()

        val status = CloudStatusParser.parse(json)
        val steve = status.services.single().playerDetails.single()
        assertEquals(14.5, steve.health)
        assertEquals(18, steve.food)
        assertEquals(33, steve.level)
        assertEquals(0.5, steve.exp)
        assertEquals(100.5, steve.x)
        assertEquals(-200.25, steve.z)
        assertEquals("vanilla", steve.clientBrand)
        assertEquals(1600000000000, steve.firstPlayed)
        assertEquals("10.0.0.5", steve.address)
        assertEquals(true, steve.isOp)
        assertEquals(false, steve.isFlying)
        assertEquals(7.2, steve.saturation)
        assertEquals(false, steve.allowedFlight)
        assertEquals(true, steve.sneaking)
        assertEquals(false, steve.sprinting)
        assertEquals(false, steve.gliding)
        assertEquals(false, steve.sleeping)
        assertEquals("PIG", steve.inVehicle)
    }

    @Test
    fun `nested player-details do not break service parsing`() {
        val json = """
            {"totals":{"groups":1,"services":1,"online":1,"players-online":1,"agents-online":1},
             "groups":[{"name":"lobby","type":"PAPER","version":"paper-26.2-129","static":true,
                        "min-services":1,"max-services":3,"always-running-services":1}],
             "services":[{"name":"lobby-1","group":"lobby","type":"PAPER","state":"RUNNING","port":25566,
                "static":true,"agent-online":true,"players-online":1,
                "players":["Steve"],
                "player-details":[{"name":"Steve","uuid":"d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e",
                                   "ping":42,"world":"world","gamemode":"SURVIVAL"}],
                "tps":19.98,"heap-used-mb":512,"heap-max-mb":1024,"cpu":0.31,
                "agent-version":"0.7.5","restarts":0,"last-error":null}]}
        """.trimIndent()

        val status = CloudStatusParser.parse(json)

        assertEquals(1, status.services.size)
        val service = status.services.single()
        assertEquals("lobby-1", service.name)
        assertTrue(service.agentOnline)
        assertEquals(1, service.playersOnline)
        assertEquals(listOf("Steve"), service.players)
        assertEquals(1, service.playerDetails.size)
        val steve = service.playerDetails[0]
        assertEquals("Steve", steve.name)
        assertEquals("d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e", steve.uuid)
        assertEquals(42, steve.pingMs)
        assertEquals("world", steve.world)
        assertEquals("SURVIVAL", steve.gamemode)
    }

    @Test
    fun `players are aggregated across agent-online services`() {
        val json = """
            {"totals":{"groups":1,"services":2,"online":2,"players-online":3},
             "groups":[],
             "services":[
               {"name":"lobby-1","group":"lobby","type":"PAPER","state":"RUNNING","port":1,
                "agent-online":true,"players-online":2,"players":["Steve","Alex"],
                "player-details":[{"name":"Steve"},{"name":"Alex"}]},
               {"name":"lobby-2","group":"lobby","type":"PAPER","state":"RUNNING","port":2,
                "agent-online":true,"players-online":1,"players":["Notch"],
                "player-details":[{"name":"Notch"}]},
               {"name":"proxy-1","group":"proxy","type":"VELOCITY","state":"RUNNING","port":3,
                "agent-online":false,"players-online":null,"players":null,"player-details":null}
             ]}
        """.trimIndent()

        val status = CloudStatusParser.parse(json)
        assertEquals(3, status.totalPlayersOnline)
        assertEquals(
            listOf("Steve", "Alex", "Notch"),
            status.services.filter { it.agentOnline }.flatMap { it.players },
        )
    }

    @Test
    fun `malformed documents fail with a diagnostic instead of parsing garbage`() {
        assertFailsWith<IllegalArgumentException> { CloudStatusParser.parse("{not json") }
        assertFailsWith<IllegalStateException> { CloudStatusParser.parse("[]") }
        assertFailsWith<IllegalArgumentException> { CloudStatusParser.parse("{\"totals\":}") }
    }
}

class MiniJsonTest {
    @Test
    fun `scalars, structures and nesting round-trip`() {
        assertEquals(null, MiniJson.parse("null"))
        assertEquals(true, MiniJson.parse("true"))
        assertEquals(false, MiniJson.parse("false"))
        assertEquals(42L, MiniJson.parse("42"))
        assertEquals(-7L, MiniJson.parse(" -7 "))
        assertEquals(19.98, MiniJson.parse("19.98"))
        assertEquals(1.5e3, MiniJson.parse("1.5e3"))
        assertEquals("hi", MiniJson.parse("\"hi\""))
        assertEquals(listOf<Any?>(), MiniJson.parse("[]"))
        assertEquals(mapOf<Any?, Any?>(), MiniJson.parse("{}"))
        assertEquals(
            mapOf("a" to listOf(1L, mapOf("b" to null)), "c" to "d"),
            MiniJson.parse("""{"a": [1, {"b": null}], "c": "d"}"""),
        )
    }

    @Test
    fun `string escapes are decoded`() {
        // JSON source: "quote\" back\\slash line\nbreak tab\tnul-\u00e9"
        val json = "\"quote\\\" back\\\\slash line\\nbreak tab\\tnul-\\u00e9\""
        assertEquals("quote\" back\\slash line\nbreak tab\tnul-\u00e9", MiniJson.parse(json))
    }

    @Test
    fun `brackets and braces inside strings do not confuse the parser`() {
        val json = """{"lines":["services {ok} [2]","say \"hi\" {it}"]}"""
        assertEquals(listOf("""services {ok} [2]""", """say "hi" {it}"""), MiniJson.parse(json)?.let {
            (it as Map<*, *>)["lines"]
        })
    }

    @Test
    fun `malformed input throws with offset`() {
        val failure = assertFailsWith<IllegalArgumentException> { MiniJson.parse("{\"a\": }") }
        assertTrue(failure.message!!.contains("offset"))
    }
}
