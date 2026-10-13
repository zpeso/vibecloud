package dev.vibecloud.api.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract tests for the local-service-identity plumbing: plugins running inside a cloud-managed
 * server must be able to ask for their own service name without any HTTP round trip.
 */
class ServiceIdentityTest {

    @Test
    fun `builder-provided identity is exposed on the instance and companion`() {
        val cloud = VibeCloud.connect { builder ->
            builder.token("test-token")
                .currentService(ServiceIdentity("citybuild-3", "citybuild-3", "citybuild"))
        }
        try {
            val identity = cloud.currentService()
            assertEquals("citybuild-3", identity?.serviceId)
            assertEquals("citybuild-3", identity?.serviceName)
            assertEquals("citybuild", identity?.groupName)
            // Companion convenience reads the stored singleton — never throws.
            assertEquals("citybuild-3", VibeCloud.currentServiceName())
        } finally {
            cleanupConnected()
        }
    }

    @Test
    fun `identity is null when the facade connects without one`() {
        val cloud = VibeCloud.connect { builder -> builder.token("test-token") }
        try {
            assertNull(cloud.currentService())
            // Not-connected / no-identity must return null, never throw (usable from scoreboards).
            assertNull(VibeCloud.currentServiceName())
        } finally {
            cleanupConnected()
        }
    }

    @Test
    fun `companion lookup stays null without any connection`() {
        cleanupConnected()
        assertNull(VibeCloud.currentServiceName())
    }

    /** The facade singleton is process-global; reset it so tests stay independent. */
    @Suppress("UNCHECKED_CAST")
    private fun cleanupConnected() {
        try {
            val field = VibeCloud::class.java.getDeclaredField("connected")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: NoSuchFieldException) {
            // Fallback for compilers that keep the companion backing field on the Companion class.
            val companion = VibeCloud.Companion
            val field = companion.javaClass.getDeclaredField("connected")
            field.isAccessible = true
            field.set(companion, null)
        }
    }
}
