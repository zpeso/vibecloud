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
