package dev.vibecloud.api.group

import dev.vibecloud.api.server.ServerType
import java.util.*

/**
 * A desired-state template for a family of services.
 *
 * The reconciler maintains [desiredRunningServices], i.e. max(minServices,
 * alwaysRunningServices), as a floor: it starts services while fewer are running, but never
 * stops the extras an operator started — those stay up until stopped explicitly.
 * maxServices caps all provisioned records, including stopped and crashed records.
 */
data class Group(
    val name: String,
    val type: ServerType,
    val version: String,
    val minServices: Int = 1,
    val maxServices: Int = 5,
    val alwaysRunningServices: Int = 0,
    /** Static services keep their files between restarts; non-static ones re-provision from the template. */
    val static: Boolean = true,
) {
    init {
        require(name == name.lowercase(Locale.ROOT)) { "Group name must be lowercase: '$name'" }
        require(NAME_PATTERN.matches(name)) {
            "Invalid group name '$name'. Use 1-32 lowercase letters, digits, '_' or '-', starting with a letter or digit."
        }
        require(version.isNotBlank() && version == version.trim()) { "Version must not be blank or padded with spaces" }
        require(VERSION_PATTERN.matches(version)) {
            "Invalid version '$version'. Use a safe template folder name (letters, digits, '.', '_', '+', '-')."
        }
        require(minServices >= 0) { "minServices must be at least 0" }
        require(maxServices >= 0) { "maxServices must be at least 0" }
        require(minServices <= maxServices) { "minServices ($minServices) must be <= maxServices ($maxServices)" }
        require(alwaysRunningServices in 0..maxServices) {
            "alwaysRunningServices ($alwaysRunningServices) must be between 0 and maxServices ($maxServices)"
        }
    }

    val desiredRunningServices: Int
        get() = maxOf(minServices, alwaysRunningServices)

    companion object {
        private val NAME_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
    }
}
