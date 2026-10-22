[🏠 Home](Home.md) · [🚀 Installation](Installation.md) · [⌨️ Commands](Commands.md) · **🧩 Bridge API** · [📦 Versions](Versions.md) · [🧑 ServerMobs](ServerMobs.md)

# 🧩 Bridge API (Development)

There are three ways to build on VibeCloud, depending on where your code runs:

| Your code runs… | Use | Module |
|---|---|---|
| Inside a Minecraft server (a plugin) | **`VibeCloud` facade** — network-wide players, services, groups, commands | `bridge` |
| In your own application/JVM | **Embed the controller** — the full `Cloud` object with events | `api` + `core` |
| Anywhere (scripts, bots, tools) | **Redis** — read `vibecloud:status`, publish actions with the token | any |

The bridge is carried by **Redis**, not HTTP. The controller, the agent plugins and the SDK all speak the same small
key layout, so a status read is one local Redis round trip (well under a millisecond) and a player action is one
published message. There is no HTTP endpoint and no dashboard.

## 1. The `VibeCloud` facade (plugins)

### Setup

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("com.github.zpeso.vibecloud:bridge:2.0.0")  // dev.vibecloud.api.bridge.*
}
```

The published jar is self-contained: it bundles the Redis transport and client (relocated), so `implementation(...)`
needs no other dependencies. Alternatives: `./gradlew publishToMavenLocal` + `mavenLocal()`, or nothing at all — the
agent jar that the cloud injects into every backend server already contains these SDK classes, so on cloud-managed
servers the classes are simply present.

### Connect once at startup

```kotlin
class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        // inside a cloud-managed service: reads plugins/VibeCloud/agent.properties
        runCatching { VibeCloud.forService() }
            .onFailure { getLogger().warning("Cloud not reachable: ${it.message}") }
    }
}
```

Outside a managed service, connect manually:

```kotlin
VibeCloud.connect { builder ->
    builder.redisUrl("redis://127.0.0.1:6379")
           .token(Files.readString(Path.of("/opt/vibecloud/bridge.token")).trim())
           .timeout(java.time.Duration.ofSeconds(5))
}
val cloud = VibeCloud.instance()
```

The URL accepts credentials and a database (`redis://:password@host:6379/2`) and `rediss://` enables TLS.

### Ask "which service am I on?"

Inside a cloud-managed server, the facade knows the local service identity — it comes from the agent config,
no round trip, never throws:

```kotlin
val me = VibeCloud.currentServiceName()        // "citybuild-2", or null outside the cloud
val full = VibeCloud.instance.currentService() // ServiceIdentity(serviceId, serviceName, groupName)
myPlugin.onThisService("citybuild-2", ::registerEvents)  // e.g. only run game logic on one named service
```

`null` means this server is not managed by the cloud (or the facade connected without an identity).

### What you can do

Roster/lookup reads are synchronous Redis reads; message/kick/console commands publish one message and return
immediately. Only calls that need an answer block:

```kotlin
val cloud = VibeCloud.instance()

// players — network-wide
val players: List<CloudPlayer> = cloud.players().all()
val notch = cloud.players().findByName("Notch")
val byId = cloud.players().findByUniqueId("d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e")  // dashed or plain

notch?.let {
    cloud.players().sendMessage(it, "§aWelcome to the network!")   // returns immediately
    cloud.players().kick(it, "§cBye.")                             // returns immediately
}

val lobby = cloud.services().findByGroup("lobby").firstOrNull()
if (notch != null && lobby != null) cloud.players().connect(notch, lobby)  // waits for the controller

cloud.services().findByGroup("citybuild").forEach { service ->
    service.executeCommand("say Restarting in 10s")                 // returns immediately
}

// groups — what is configured
cloud.groups().all().forEach { group -> println("${group.name}: ${group.version}") }

// the same command surface as /cloud, resolved by the controller
val lines = cloud.executeCloudCommand(listOf("services"))
val suggestions = cloud.completeCloudCommand(listOf("group", ""))
```

| Call | Blocks? | Why |
|---|---|---|
| `status()`, `players().all()`, `findByName`, `services()`, `groups()` | yes, milliseconds | one local `GET` |
| `sendMessage`, `kick`, `CloudService.executeCommand` | no | one `PUBLISH`/`RPUSH` |
| `hasPermission(...)` | yes, until the player's server answers (≤ `timeout`) | the check runs on that server |
| `connect(...)` (transfer) | yes, until the controller wrote it to the proxy console | only the controller owns the proxy |
| `executeCloudCommand` / `completeCloudCommand` | yes, until the controller answers | the controller is the authority |

Every blocking call has a non-blocking variant that runs it on a background thread and returns a
`CompletableFuture`: `hasPermissionAsync`, `sendMessageAsync`, `kickAsync`, `connectAsync`,
`executeCommandAsync`, `executeCloudCommandAsync`. Prefer those on a server's main thread.

### Scoreboards and other frequent readers

Use `cloud.temporary()` for frequent reads: the full status document is cached in memory and refreshed in the
background every five seconds by default. Cached reads never touch Redis on the caller's thread, so they are safe
for scoreboards, events, or checks that run every second. Before the first refresh, cached collections are empty and
counts are zero; while Redis is unreachable, the last successful snapshot remains.

```kotlin
val cached = VibeCloud.instance().temporary()
val online = cached.players().playerCount()
val cloudPlayer = cached.players().findByName(player.name)
val group = cloudPlayer?.group             // cached, no Redis read
val services = cached.services().findByGroup("lobby")
```

`cloud.players().playerCount()` uses the same cache. Configure its cadence with
`temporaryRefreshInterval(Duration.ofSeconds(3))` on the builder. `cached.refreshAsync()` requests an immediate
background refresh and returns a `CompletableFuture<CloudStatus>`.

### API reference

| Call | Purpose |
|---|---|
| `VibeCloud.forService()` | Connect using the installed agent config (plugins inside managed services) |
| `VibeCloud.connect { … }` | Connect with an explicit Redis URL/token; stores the singleton |
| `VibeCloud.instance()` / `instanceOrNull()` | Access the singleton afterwards |
| `VibeCloud.currentServiceName()` / `instance.currentService()` | Local service identity from the agent config (no Redis; `null` outside the cloud) |
| `cloud.players().all() / findByName(name) / refresh(player)` | Fresh roster and lookups |
| `cloud.players().findByUniqueId(uuid)` | UUID lookup (dashes/case-insensitive, name fallback); `CloudPlayer.uniqueId` / `uuid` come from the agent roster |
| `cloud.players().hasPermission(player, node)` / `player.hasPermission(node)` | Effective permission check on the player's server (LuckPerms etc.); blocks until answered |
| `cloud.temporary().players().all() / findByName(name)` | Cached player roster and details; never touches Redis on the caller |
| `cloud.temporary().playerCount()` / `cloud.players().playerCount()` | Cached network player count (background refresh, default 5s) |
| `cloud.temporary().services() / groups()` | Cached service and group snapshots |
| `cloud.players().sendMessage(player, lines)` | Chat message (legacy `§` codes supported); returns immediately |
| `cloud.players().kick(player, reason)` | Kick with reason; returns immediately |
| `cloud.players().connect(player, targetService)` | Transfer via the proxy (`send`); needs a running proxy |
| `cloud.services().all() / findByName / findByGroup(group) / findOnline()` | Service snapshots |
| `CloudService.executeCommand(commandLine)` | Console command on that service (runs on its main thread); returns immediately |
| `cloud.groups().all() / findByName(name)` | Group configuration |
| `cloud.status()` | Full status document in one call |
| `cloud.executeCloudCommand(args) / completeCloudCommand(args)` | The `/cloud` surface from code |
| `…Async(...)` variants | The same call on a background thread (`CompletableFuture`) |

### Player ids and permission checks

`CloudPlayer.uniqueId` is the UUID the player's agent reports, and `CloudPlayer.uuid` is the same value parsed into a
`java.util.UUID` (null instead of throwing when the agent reports none). `findByUniqueId` accepts the UUID with or
without dashes in any letter case; if no online player has that id it falls back to an exact name match, so old
name-based callers keep working.

`hasPermission(player, node)` asks the **server the player is on** whether the node is granted, so the answer comes
from that backend's permission system — LuckPerms (or any other provider installed there) evaluates it with its own
group/luck data. Install LuckPerms on the backend servers (a shared MongoDB store keeps them in sync); a proxy-only
LuckPerms install does not answer Bukkit permission checks on the backends.

The request is published to every agent; the one that hosts the player evaluates the node on its main thread and
pushes the answer to a one-shot Redis list. The call returns `true`/`false`, and throws an `IOException` when the
player is offline, the node could not be evaluated, or no answer arrived within the configured timeout. Use
`hasPermissionAsync` on the main thread:

```kotlin
player.hasPermissionAsync("group.moderator").thenAccept { granted ->
    if (!granted) player.kick("§cStaff only")
}
```

A message or kick is broadcast to every backend agent, so the server that hosts the player acts on it — a player who
just switched servers still receives the message instead of being lost to stale routing state. A transfer is written
to the proxy's console right away and works with every client version. A player who left is reported by `null`
lookups, and `hasPermission` throws `CloudPlayerNotFoundException`.

## 2. Embedding the controller (your own app)

The whole cloud is one object — the CLI is just a consumer:

```kotlin
import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.core.cloud.CloudBootstrap

val cloud: Cloud = CloudBootstrap().create(Path.of("config.yml"))
cloud.start()      // repairs configs, syncs the proxy, starts reconciliation (suspend)
cloud.shutdown()   // stops all services, drains events
```

State and control:

```kotlin
cloud.groups.all() / get("lobby")            // GroupManager: create/update/delete too
cloud.services.all() / get("lobby-1")        // ServiceManager
runBlocking {
    cloud.services.create("lobby")
    cloud.services.start("lobby-1")          // also stop/restart/delete/updateVersion/stopAll
}
```

**Events** — subscribe with a lambda, keep the handle to unsubscribe:

```kotlin
val handle: AutoCloseable = cloud.events.subscribe { event ->
    when (event) {
        is ServiceStartedEvent -> notifyNetwork(event.service)
        is ServiceCrashedEvent -> alert(event.service.name, event.reason, event.exitCode)
        else -> Unit
    }
}
// handle.close() to unsubscribe
```

| Event | Fired when |
|---|---|
| `ServiceCreatedEvent` | a service record was provisioned |
| `ServiceStartingEvent` / `ServiceStartedEvent` | start accepted / process reported readiness |
| `ServiceStoppingEvent` / `ServiceStoppedEvent` | graceful stop began / process exited cleanly |
| `ServiceCrashedEvent` | unexpected exit (carries `exitCode`, `reason`) |
| `ServiceDeletedEvent` | record + directory removed |

Subscribers run on the cloud's dispatcher — hand long work to your own scope. Events are in-process only.

## 3. Raw Redis (scripts, bots, tools)

Everything lives under the `vibecloud:` prefix:

| Key / channel | Type | Contents |
|---|---|---|
| `vibecloud:status` | string | The cloud status document (JSON), written by the controller |
| `vibecloud:heartbeat` | channel | Agent heartbeats (JSON), consumed by the controller |
| `vibecloud:actions` | channel | Player actions (message/kick/log/permission) broadcast by the SDK |
| `vibecloud:commands:<service>` | list | Console commands for one service, popped by its agent |
| `vibecloud:cloud` | list | Requests only the controller can execute (transfers, `/cloud`) |
| `vibecloud:result:<id>` | list | One-shot answer to a permission check |
| `vibecloud:cloud:result:<id>` | list | The controller's answer to a request |

```bash
# The whole network state, exactly what the SDK's status() parses
redis-cli GET vibecloud:status | jq .
```

Publishing an action or a request requires the shared token (the contents of `bridge.token`) in the message's
`token` field; the controller and the agents reject anything else. Anyone with plain Redis access can read the
status — bind Redis to `127.0.0.1` (the default) or protect it with `requirepass`, and see
[docs/SECURITY.md](../SECURITY.md).

The full message shapes live in [docs/API.md](../API.md).

## Good to know

- The token grants command dispatch: distribute it like a password, and treat the Redis instance as a trusted
  component of the network.
- The facade throws `IOException` when Redis is unreachable, the token is rejected, no proxy is running, the target
  is unknown, or an answer timed out.
- `bridge.heartbeat-interval-seconds` (default 5) controls how fresh player/TPS/CPU data is.
- After upgrading the controller, restart the backend services: the cloud reinstalls its bundled agent jar on every
  start, and an older agent does not know the newer message types.

---

**Next:** [📦 Versions](Versions.md) — supported software and Java matrix.
