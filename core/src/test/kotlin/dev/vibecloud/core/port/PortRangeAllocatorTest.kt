package dev.vibecloud.core.port

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortRangeAllocatorTest {
    @Test
    fun `allocates unique ports and reuses a released port`() {
        val allocator = PortRangeAllocator(30000..30001, isBindable = { true })

        assertEquals(30000, allocator.allocate("lobby-1"))
        assertEquals(30000, allocator.allocate("lobby-1"))
        assertEquals(30001, allocator.allocate("lobby-2"))
        assertFailsWith<PortAllocationException> { allocator.allocate("lobby-3") }

        allocator.release("lobby-1")
        assertEquals(30000, allocator.allocate("lobby-3"))
    }

    @Test
    fun `persisted reservations reject collisions`() {
        val allocator = PortRangeAllocator(30000..30010, isBindable = { true })
        allocator.reserve("proxy-1", 30007)
        assertFailsWith<PortAllocationException> { allocator.reserve("lobby-1", 30007) }
        assertEquals(30000, allocator.allocate("lobby-1"))
    }
}
