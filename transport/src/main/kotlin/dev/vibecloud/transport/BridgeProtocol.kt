package dev.vibecloud.transport

/**
 * Key and channel layout of the VibeCloud bridge on Redis. The cloud, the agent plugins and the
 * SDK all use these constants, so the wire layout is defined exactly once.
 *
 * Everything lives under `vibecloud:` so a shared Redis instance stays tidy:
 *  - [STATUS_KEY]      string  — the cloud status document (JSON), written by the controller
 *  - [HEARTBEAT_CHANNEL] channel — agent heartbeats (JSON), read by the controller
 *  - [ACTIONS_CHANNEL]   channel — player actions (JSON) broadcast to every backend agent
 *  - [CLOUD_REQUESTS_KEY] list   — requests the controller must execute (transfers, /cloud)
 *  - [commandsKey]       list    — console commands for one service, popped by its agent
 *  - [resultKey]         list    — one answer to one request (permission check, inventory)
 *  - [cloudResultKey]    list    — the controller's answer to a cloud request
 */
object BridgeProtocol {
    const val PREFIX = "vibecloud:"

    /** Cloud status document, read by the SDK (`cloud.status()`). */
    const val STATUS_KEY = PREFIX + "status"

    /** Agent heartbeats: every backend publishes here, the controller subscribes. */
    const val HEARTBEAT_CHANNEL = PREFIX + "heartbeat"

    /** Player actions (message/kick/log/permission/inventory) broadcast to every agent. */
    const val ACTIONS_CHANNEL = PREFIX + "actions"

    /** Queue the controller consumes: player transfers and in-game `/cloud` commands. */
    const val CLOUD_REQUESTS_KEY = PREFIX + "cloud"

    /** Console commands for one service (keyed by service name, which is unique in the cloud). */
    fun commandsKey(serviceName: String): String = PREFIX + "commands:" + serviceName

    /** A one-shot answer to [requestId] (permission check, inventory snapshot). */
    fun resultKey(requestId: String): String = PREFIX + "result:" + requestId

    /** The controller's answer to a cloud request (transfer, `/cloud` execution). */
    fun cloudResultKey(requestId: String): String = PREFIX + "cloud:result:" + requestId

    /** How long an unanswered result list survives before Redis drops it. */
    const val RESULT_TTL_SECONDS = 60L

    /** Message types used on [ACTIONS_CHANNEL]. */
    const val ACTION_MESSAGE = "message"
    const val ACTION_KICK = "kick"
    const val ACTION_LOG = "log"
    const val ACTION_PERMISSION = "permission"
    const val ACTION_INVENTORY = "inventory"

    /** Message types used on [commandsKey] lists. */
    const val COMMAND_CONSOLE = "command"

    /** Message types used on [CLOUD_REQUESTS_KEY]. */
    const val REQUEST_TRANSFER = "transfer"
    const val REQUEST_CLOUD_COMMAND = "cloud-command"

    /** Answers pushed to a [resultKey] list. */
    const val RESULT_GRANTED = "granted"
    const val RESULT_DENIED = "denied"
    const val RESULT_OFFLINE = "offline"
    const val RESULT_ERROR_PREFIX = "error:"
}
