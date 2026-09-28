package dev.vibecloud.api.group

import dev.vibecloud.api.server.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupTest {
    @Test
    fun `desired running count is maximum of minimum and always running`() {
        assertEquals(3, group(min = 2, always = 3).desiredRunningServices)
        assertEquals(2, group(min = 2, always = 0).desiredRunningServices)
        assertEquals(0, group(min = 0, max = 0, always = 0).desiredRunningServices)
    }

    @Test
    fun `min services cannot exceed max`() {
        assertFailsWith<IllegalArgumentException> {
            group(min = 3, max = 2, always = 1)
        }
    }

    @Test
    fun `always running cannot exceed max`() {
        assertFailsWith<IllegalArgumentException> {
            group(min = 1, max = 2, always = 3)
        }
    }

    private fun group(min: Int, max: Int = 5, always: Int) = Group(
        name = "lobby",
        type = ServerType.PAPER,
        version = "26.3",
        minServices = min,
        maxServices = max,
        alwaysRunningServices = always,
    )
}
