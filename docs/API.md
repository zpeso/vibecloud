# VibeCloud API guide

VibeCloud's public API lives in the **`api`** module under the `dev.vibecloud.api.*` packages. It is the same surface
the CLI uses, so anything the console can do, your code can do — plus lifecycle events you can react to.

This page covers:

1. [Embedding the controller](#embedding-the-controller)
2. [Reading cloud state](#reading-cloud-state)
3. [Controlling services from code](#controlling-services-from-code)
4. [Subscribing to events](#subscribing-to-events)
5. [Using the API from inside a Minecraft plugin (the bridge)](#using-the-api-from-inside-a-minecraft-plugin-the-bridge)
6. [What the API does not do (yet)](#what-the-api-does-not-do-yet)

## Embedding the controller

The whole cloud is a single object created from a `config.yml`:

```kotlin
import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.cloud.CloudState
import dev.vibecloud.core.cloud.CloudBootstrap
import java.nio.file.Path

val cloud: Cloud = CloudBootstrap().create(Path.of("config.yml"))

cloud.start()   // repairs configs, syncs the proxy, starts desired-state reconciliation
// ... cloud.state == CloudState.RUNNING ...
cloud.shutdown() // stops all services, drains events, releases the coroutine scope
```

- `create()` is blocking and loads/creates `config.yml`, `templates/`, `services/`, shared deployment folders
  (`templates/every_server/`, `templates/every_proxy/`), and per-group overlay folders (`templates/groups/<group>/`).
- `start()`, `reload()`, and `shutdown()` are `suspend` functions; call them from a coroutine or wrap them in
  `runBlocking { ... }` for simple embedding.
- A stopped cloud cannot be restarted — create a new `Cloud` instance instead.
- `cloud.reload()` re-reads `config.yml` and replaces group definitions; directory, port, and runtime settings only
  apply after a restart.

To depend on the API from another Gradle project, add this repository's published artifacts (or a composite build) and:

```kotlin
dependencies {
    implementation("com.github.zpeso.vibecloud:api:1.0.7")
}
```

Embedding the *implementation* (`dev.vibecloud:core` + `common`) gives you `CloudBootstrap`; depending only on `api`
keeps your code decoupled from the local implementation, which is the intended seam for future remote backends.

## Reading cloud state

```kotlin
// Groups are desired-state templates; services are the provisioned instances.
val groups: Collection<Group> = cloud.groups.all()
val group = cloud.groups.get("lobby")           // null if missing
val services: Collection<Service> = cloud.services.all()
val lobby1 = cloud.services.get("lobby-1")      // null if missing

group?.type                 // ServerType: PAPER, SPIGOT, VELOCITY, BUNGEECORD
group?.version              // pinned build key, e.g. "paper-26.2-129"
group?.desiredRunningServices // max(minServices, alwaysRunningServices) — the floor the reconciler maintains

service?.state              // CREATED, STARTING, RUNNING, STOPPING, STOPPED, CRASHED
service?.port               // allocated port
service?.static             // false → directory is wiped and re-provisioned from the template on every start
service?.restartCount
service?.lastError          // reason of the last crash, if any
```

`Service` and `Group` are immutable snapshots: calling `all()`/`get()` again gives you fresh values. There is no
subscription needed for state — poll it, or derive it from events (below).

### "How many players are connected?"

The controller supervises **processes**; players are reported by the **bridge** (see the next section): the agent
plugin inside each backend server publishes the live player roster to Redis, and the controller exposes it together
with service state in the status document. From an embedded `Cloud` you can read capacity and states directly;
live player counts come from the bridge:

```kotlin
val running = cloud.services.all().filter { it.state == ServiceState.RUNNING }
val status = VibeCloud.instance().status()          // players, per-service details, groups
```

What the `ServiceManager` alone gives you is capacity (ports, states); live player counts always come from the
bridge's status document.

## Controlling services from code

All mutating calls are `suspend`:

```kotlin
runBlocking {
    val service = cloud.services.create("lobby")   // provisions from the group's template + overlay
    cloud.services.start(service.name)             // or start(name, automatic = true) for reconciler-like starts
    cloud.services.restart(service.name)
    cloud.services.stop(service.name)
    cloud.services.delete(service.name)            // stops, deletes the directory, frees the port
    cloud.services.stopAll()                       // administrative stop-everything
}

// Group management is blocking (not suspend) and also persists to config.yml:
cloud.groups.create(Group(name = "event", type = ServerType.PAPER, version = "paper-26.2-129", minServices = 0, maxServices = 3))
cloud.groups.delete("event")                       // only when no service records remain
```

Errors are thrown as exceptions: `NoSuchElementException` for unknown names, `IllegalStateException` for invalid
transitions (e.g. starting while stopping), and `dev.vibecloud.core.server.ServiceStartException` when a launch fails
with the crash reason attached.

## Subscribing to events

`cloud.events` is an `EventBus` with one method — subscribe with a lambda and keep the returned handle to unsubscribe:

```kotlin
val handle: AutoCloseable = cloud.events.subscribe { event ->
    when (event) {
        is ServiceStartedEvent -> notifyNetwork(event.service)
        is ServiceCrashedEvent -> alert(event.service.name, event.reason, event.exitCode)
        else -> Unit
    }
}
// later: handle.close()
```

| Event | Fired when |
|---|---|
| `ServiceCreatedEvent` | a service record was provisioned from a group |
| `ServiceStartingEvent` | a start was accepted (after any non-static re-provisioning) |
| `ServiceStartedEvent` | the process reported readiness (`Done (…)` / `Listening on …`) |
| `ServiceStoppingEvent` | a graceful stop began |
| `ServiceStoppedEvent` | the process exited cleanly or a start was cancelled |
| `ServiceCrashedEvent` | an unexpected exit occurred (carries `exitCode` and `reason`) |
| `ServiceDeletedEvent` | the record and its directory were removed |

Notes:

- Subscribers run on the cloud's dispatcher; hand long work (HTTP calls, disk I/O) to your own scope so you never slow
  down lifecycle handling.
- Events are in-process only: they fire inside the controller, not inside your Minecraft servers.
- Publishing never throws into the cloud; a throwing listener is logged and skipped.

## Using the API from inside a Minecraft plugin (the bridge)

A plugin on a Paper or Velocity server runs in that **server's** JVM, while the `Cloud` object lives in the separate
controller process. VibeCloud ships a ready-made transport for this: the **bridge**, carried by Redis.

### How it works

1. The controller and the agents connect to the Redis server at `redis.url` (default `redis://127.0.0.1:6379`). The
   shared token lives in `bridge.token` next to `config.yml`.
2. On every backend service start the cloud injects the bundled agent plugin (`VibeCloud-Agent.jar`) and its
   `agent.properties` into the service. The agent publishes the exact online player roster every few seconds and
   consumes console commands from its own Redis list.
3. The controller publishes the cloud status document to `vibecloud:status` (on every reconcile pass and on every
   heartbeat), so the SDK reads players, services and groups with one local `GET`.
4. Your plugins use the SDK from the `bridge` module (package `dev.vibecloud.api.bridge`, published as
   `dev.vibecloud:bridge`). The agent jar also contains these SDK classes, so they are already on every backend
   server.

### Reading the roster in a lobby plugin

```kotlin
import dev.vibecloud.api.bridge.VibeCloud

class LobbyScoreboard(plugin: JavaPlugin) {
    private val cloud = VibeCloud.forService()   // reads plugins/VibeCloud/agent.properties

    fun networkPlayerCount(): Int = cloud.players().playerCount()          // cached, never blocks
    fun citybuildPlayers(): List<String> =
        cloud.services().findByGroup("citybuild").flatMap { it.players }   // one local Redis read
}
```

Notes:

- Roster/lookup reads are synchronous Redis reads (local, sub-millisecond); use `cloud.temporary()` for scoreboards
  that poll every tick — it reads an in-memory snapshot refreshed in the background every five seconds.
- The agent reports every 5 seconds by default (`bridge.heartbeat-interval-seconds`), which is also how often the
  status document's player lists are refreshed.
- `agentOnline` tells you whether the report comes from the authoritative agent or the console-line fallback.
- The status document also carries `totals.online`, per-service `state`/`port`/`players`, and the group definitions.
- `status().services` entries expose `playerDetails` (`PlayerDetail`: `name`, `uuid`, `pingMs`, `world`,
  `gamemode`) when the agent reports enriched metadata; `players` always carries the plain names.

### Dependency setup for your plugin

The `api`, `bridge` and `transport` modules publish Maven artifacts. For local development, install them into your
local Maven repository and depend on them from the plugin project:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// your-plugin/build.gradle.kts
repositories {
    mavenLocal()
}
dependencies {
    compileOnly("dev.vibecloud:bridge:<version>") // brings dev.vibecloud.api.bridge.*
}
```

For GitHub-based consumption without running a repository, tag a release and let **JitPack** build it on demand.
JitPack publishes this repo's tags under the plain `X.Y.Z` version:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { compileOnly("com.github.zpeso.vibecloud:bridge:<version>") }
```

(The zero-dependency alternative: the agent jar already shades the `dev.vibecloud.api.bridge` SDK classes and the
Redis client, so the classes are present on every cloud-managed server once the agent is installed.)

### The VibeCloud facade (recommended entry point)

`dev.vibecloud.api.bridge.VibeCloud` is the one object you interact with. Call `VibeCloud.forService()` once during
plugin startup (reads the agent config the cloud installs), then use `VibeCloud.instance` anywhere:

```kotlin
import dev.vibecloud.api.bridge.VibeCloud

class NetworkMessenger : Listener {
    private val cloud get() = VibeCloud.instance

    fun broadcastExcept(sender: String, message: String) {
        // Reads are one local Redis round trip; actions publish one message and return immediately.
        cloud.players().all()
            .filter { it.name != sender }
            .forEach { it.sendMessage("§b[Network] §f$message") }
    }

    fun sendToLobby(playerName: String) {
        val player = cloud.players().findByName(playerName) ?: return
        val lobby = cloud.services().findByGroup("lobby").firstOrNull() ?: return
        player.connect(lobby)   // blocks briefly: the controller writes it to the proxy console
    }

    fun kickCheater(name: String) {
        val player = cloud.players().findByName(name) ?: return
        player.kick("§cYou were removed from the network.")
    }

    fun requireStaff(name: String) {
        // Evaluated by the player's own server (LuckPerms or another provider installed there).
        val player = cloud.players().findByName(name) ?: return
        player.hasPermissionAsync("group.moderator").thenAccept { granted ->
            if (!granted) player.kick("§cStaff only")
        }
    }

    fun restartCitybuild() {
        cloud.services().findByGroup("citybuild").forEach { it.executeCommand("say Restarting in 10s") }
    }
}
```

`CloudPlayer.uniqueId` is the agent-reported UUID and `CloudPlayer.uuid` the same value parsed into a
`java.util.UUID` (null instead of throwing when no id was reported). `findByUniqueId` accepts a UUID with or without
dashes in any case and falls back to an exact name match, so name-based callers keep working.

`hasPermission(player, node)` asks the server the player is on, so the backend's permission system answers —
LuckPerms installed on the backends (a shared MongoDB store keeps them in sync) supplies the effective value. The
request is published to every agent; the one hosting the player evaluates it on its main thread and pushes the
answer. The call returns a `Boolean` and throws an `IOException` when the player left, the node could not be
evaluated, or no answer arrived within the configured timeout. `hasPermissionAsync` is the non-blocking variant for
a server's main thread.

Which calls block:

| Call | Blocks? |
|---|---|
| roster/service/group reads, `status()` | yes, one local Redis read (milliseconds) |
| `sendMessage`, `kick`, `executeCommand` | no — one message is published |
| `hasPermission` | yes, until the player's server answers |
| `connect` (transfer), `executeCloudCommand` | yes, until the controller answers |

Every blocking call has an `…Async` variant that runs on a background thread and returns a `CompletableFuture`.
Messages and kicks are delivered by the agent that hosts the player, which removes the old routing race: a player who
just changed servers still gets the message. A transfer is written to the proxy console immediately and works with
every client version.

### The /cloud command (in-game console)

Every server running the agent gets a `/cloud` command (permission `minetropia.cloud`, default: op). It executes
server-side — the cloud is the authority — so subcommands, lifecycle actions and completions behave exactly like the
interactive console. Output is branded with the cloud prefix (`ᴄʟᴏᴜᴅ »`) matching the agent's other messages:

```text
/cloud info                      Cloud overview: running/starting/crashed counts, players online
/cloud groups                    Group table (type, version, desired/max/provisioned)
/cloud group start <name>        Start another service of a group (reuses stopped records first)
/cloud group <name> restart      Restart every provisioned service in a group
/cloud group delete <name>       Delete a group together with all of its services
/cloud group version <n> <v>     Switch the group's version (same system only): downloads the build,
                                 re-pins every service record, and resyncs the proxy forwarding mode
/cloud service <name>            Details for one service ('ser' is a shortcut)
/cloud services                  Service table (group, state, type, port)
/cloud players                   Rosters per service
/cloud send <player> <target>    Transfer a player (service or group#) via the proxy console
/cloud msg <player> <text>       Send a chat message to a player
/cloud cmd <service> <command>   Run a console command on a service
/cloud start|stop|restart|delete <service>   Lifecycle
```

Tab completion is resolved by the cloud too (subcommands, service names, online players), so suggestions always
match the live state. Plugins can run the same surface programmatically:

```kotlin
val lines = VibeCloud.instance.executeCloudCommand("services")            // full response text
val subs = VibeCloud.instance.completeCloudCommand(listOf("start", ""))  // tab-completion suggestions
val later = VibeCloud.instance.executeCloudCommandAsync(listOf("info"))   // non-blocking
```

The controller executes the command and pushes the answer to a one-shot Redis list, so the call blocks at most for
the configured timeout.

### Raw Redis access

The status document is a single string, so scripts can read it without the SDK:

```bash
redis-cli GET vibecloud:status | jq '.totals'
```

Publishing an action or a controller request requires the shared token in the message's `token` field. The exact key
layout and message shapes are documented in [docs/wiki/Bridge-API.md](wiki/Bridge-API.md#3-raw-redis-scripts-bots-tools).

## What the API does not do (yet)

- **Proxy-less player transfers.** `connect` needs a running proxy service (it uses the proxy's `send` command);
- **Remote nodes.** `Cloud` manages local processes; multi-node scheduling is future work behind the same interfaces.
- **Persistence hooks.** Non-static services are wiped on start by design; store durable data outside
  `services/<name>/` (or use static groups).
