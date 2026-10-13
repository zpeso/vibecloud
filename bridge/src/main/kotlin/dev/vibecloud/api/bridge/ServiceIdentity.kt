package dev.vibecloud.api.bridge

/**
 * The identity of the service this server runs as, read from the agent config the cloud
 * installs into every backend server (`plugins/VibeCloud/agent.properties`).
 *
 * Plugins running inside a cloud-managed service reach it via
 * [VibeCloud.currentService] / [VibeCloud.currentServiceName]:
 *
 * ```kotlin
 * val name = VibeCloud.currentServiceName() ?: return  // not a cloud-managed server
 * VibeCloud.instance.players().sendMessage(...)
 * ```
 *
 * `null` in that model means "not managed by the cloud" — for example a server started
 * outside VibeCloud, or a plugin that connected itself with [VibeCloud.connect] without
 * providing an identity.
 */
data class ServiceIdentity(
    /** Unique service id, e.g. `lobby-2`. */
    val serviceId: String,
    /** Human-facing service name as shown in the cloud, e.g. `lobby-2`. */
    val serviceName: String,
    /** Group the service belongs to, or empty when unknown. */
    val groupName: String,
)
