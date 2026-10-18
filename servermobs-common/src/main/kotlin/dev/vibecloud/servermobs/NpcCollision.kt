package dev.vibecloud.servermobs

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The missing hitbox of a fake-player NPC.
 *
 * NPCs are client-side player entities: the server has no body for them, so the client happily
 * lets a player walk into — and stand inside — the NPC. The manager calls [push] from a per-tick
 * task, which means standing still (or only moving the mouse) is corrected exactly like walking
 * into the NPC is. Kept free of Bukkit types so the geometry stays unit-testable.
 */
internal object NpcCollision {
    /**
     * Horizontal distance from the NPC centre at which the push starts. Two 0.6-block-wide
     * player bodies touch at 0.6, so a little more gives a natural "bumping into them" feel
     * instead of the old 2-block bubble that shoved players who merely walked past.
     */
    const val RADIUS: Double = 0.7

    /** Vertical span (relative to the NPC's feet) in which a player overlaps the NPC. */
    const val MIN_HEIGHT: Double = -0.4
    const val MAX_HEIGHT: Double = 1.8

    /**
     * Push speed at the very edge of the hitbox (blocks per tick). Slightly above a walking player
     * (0.216) and just below a sprinting one (0.28), so walking in is stopped and sprinting in can
     * only reach the rim of the NPC's body before the push wins.
     */
    const val BASE_SPEED: Double = 0.24

    /** Extra speed added at the centre, so being inside the NPC ejects quickly. */
    const val DEPTH_BOOST: Double = 0.16

    private const val EPSILON: Double = 1.0e-4

    /** An already-scaled horizontal velocity plus its magnitude. */
    data class Push(val x: Double, val z: Double) {
        val speed: Double get() = sqrt(x * x + z * z)
    }

    /**
     * Returns the horizontal push that moves a player at ([dx], [dz]) relative to the NPC's centre
     * out of it, or null when the player does not overlap the NPC. [yaw] is the NPC's facing, used
     * to pick a direction when the player is exactly on the NPC's centre (where "away" is
     * undefined) so they are always shoved towards the NPC's front.
     */
    fun push(
        dx: Double,
        dz: Double,
        playerY: Double,
        npcY: Double,
        yaw: Float,
    ): Push? {
        if (playerY < npcY + MIN_HEIGHT || playerY > npcY + MAX_HEIGHT) return null
        val distanceSq = dx * dx + dz * dz
        if (distanceSq >= RADIUS * RADIUS) return null

        val distance = sqrt(distanceSq)
        val (dirX, dirZ) = if (distance > EPSILON) {
            dx / distance to dz / distance
        } else {
            // Minecraft yaw 0 faces +Z; the inverse of the NPC's look direction points out of its
            // face, which is where a player standing on its exact centre should land.
            val radians = Math.toRadians(yaw.toDouble())
            -sin(radians) to cos(radians)
        }

        // 0 at the edge of the hitbox, 1 dead centre: the deeper the overlap, the faster the exit.
        val depth = 1.0 - distance / RADIUS
        val speed = BASE_SPEED + depth * DEPTH_BOOST
        return Push(dirX * speed, dirZ * speed)
    }
}
