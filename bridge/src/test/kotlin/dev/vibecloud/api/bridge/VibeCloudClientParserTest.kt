package dev.vibecloud.api.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
