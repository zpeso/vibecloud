[🏠 Home](Home.md) · [🚀 Installation](Installation.md) · [⌨️ Commands](Commands.md) · [🖥️ Dashboard](Dashboard.md) · **🧩 Bridge API** · [📦 Versions](Versions.md) · [🧑 ServerMobs](ServerMobs.md)

# 🧩 Bridge API (Development)

There are three ways to build on VibeCloud, depending on where your code runs:

| Your code runs… | Use | Module |
|---|---|---|
| Inside a Minecraft server (a plugin) | **`VibeCloud` facade** — network-wide players, services, groups, commands | `bridge` |
| In your own application/JVM | **Embed the controller** — the full `Cloud` object with events | `api` + `core` |
| Anywhere (scripts, bots, tools) | **Raw HTTP** against the bridge | any |

## 1. The `VibeCloud` facade (plugins)

### Setup

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("com.github.zpeso.vibecloud:bridge:1.0.7")  // dev.vibecloud.api.bridge.*
}
```

Alternatives: `./gradlew publishToMavenLocal` + `mavenLocal()`, or nothing at all — the agent jar that the cloud
injects into every backend server already contains these SDK classes, so on cloud-managed servers the classes are
simply present.

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
    builder.baseUrl("http://127.0.0.1:25580")
           .token(Files.readString(Path.of("/opt/vibecloud/bridge.token")).trim())
}
val cloud = VibeCloud.instance()
```

### Ask "which service am I on?"

Inside a cloud-managed server, the facade knows the local service identity — it comes from the agent config,
no HTTP round trip, never throws:

```kotlin
val me = VibeCloud.currentServiceName()        // "citybuild-2", or null outside the cloud
val full = VibeCloud.instance.currentService() // ServiceIdentity(serviceId, serviceName, groupName)
myPlugin.onThisService("citybuild-2", ::registerEvents)  // e.g. only run game logic on one named service
```

`null` means this server is not managed by the cloud (or the facade connected without an identity).

### What you can do

Every call is a snapshot + an HTTP round-trip to the cloud — call from an **async thread**
(`Bukkit.getScheduler().runTaskTimerAsynchronously(...)`), never the main thread.

```kotlin
val cloud = VibeCloud.instance()

// players — network-wide
val players: List<CloudPlayer> = cloud.players().all()
val notch = cloud.players().findByName("Notch")
val byId = cloud.players().findByUniqueId("d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e")  // dashed or plain
notch?.let {
    cloud.players().sendMessage(it, "§aWelcome to the network!")
    cloud.players().kick(it, "§cBye.")                       // optional reason
    it.hasPermission("group.moderator")                       // async, never blocks
}
val lobby = cloud.services().findByGroup("lobby").firstOrNull()
if (notch != null && lobby != null) cloud.players().connect(notch, lobby)  // via the proxy

// services — console commands from code
cloud.services().findByGroup("citybuild").forEach { service ->
    service.executeCommand("say Restarting in 10s")
}

// groups — what is configured
cloud.groups().all().forEach { group -> println("${group.name}: ${group.version}") }

// the same command surface as /cloud, resolved server-side
val lines = cloud.executeCloudCommand(listOf("services"))
val suggestions = cloud.completeCloudCommand(listOf("group", ""))
```

### Scoreboards and other frequent readers

Use `cloud.temporary()` for frequent reads: its player, service, and group data comes from one in-memory status
snapshot that refreshes asynchronously every five seconds by default. Cached reads never issue HTTP on the caller's
thread, so they are safe for scoreboards, events, or checks that run every second. Before the first refresh, cached
collections are empty and counts are zero; while the bridge is unreachable, the last successful snapshot remains.

```kotlin
val cached = VibeCloud.instance().temporary()
val online = cached.players().playerCount()
val cloudPlayer = cached.players().findByName(player.name)
val group = cloudPlayer?.group             // cached, no HTTP request
val services = cached.services().findByGroup("lobby")
```

The old `cloud.players().playerCount()` convenience call uses the same cache. Configure its cadence with
`temporaryRefreshInterval(Duration.ofSeconds(3))` on the `VibeCloud.connect` builder (the existing
`playerCountRefreshInterval(...)` builder method remains as an alias). Call `cached.refreshAsync()` if you need to
request an immediate background refresh; it returns a `CompletableFuture<CloudStatus>`.

### API reference

| Call | Purpose |
|---|---|
| `VibeCloud.forService()` | Connect using the installed agent config (plugins inside managed services) |
| `VibeCloud.connect { … }` | Connect with explicit base URL/token; stores the singleton |
| `VibeCloud.instance()` / `instanceOrNull()` | Access the singleton afterwards |
| `VibeCloud.currentServiceName()` / `instance.currentService()` | Local service identity from the agent config (no HTTP; `null` outside the cloud) |
| `cloud.players().all() / findByName(name) / refresh(player)` | Fresh roster/lookups (HTTP; use asynchronously) |
| `cloud.players().findByUniqueId(uuid)` | UUID lookup (dashes/case-insensitive, name fallback); `CloudPlayer.uniqueId` / `uuid` come from the agent roster |
| `cloud.players().hasPermission(player, node)` / `player.hasPermission(node)` | Effective permission check on the player's server (LuckPerms etc.); returns `CompletableFuture<Boolean>`, never blocks |
| `cloud.temporary().players().all() / findByName(name)` | Cached player roster and details; never performs HTTP on the caller |
| `cloud.temporary().playerCount()` / `cloud.players().playerCount()` | Cached network player count (background refresh, default 5s) |
| `cloud.temporary().services() / groups()` | Cached service and group snapshots |
| `cloud.players().sendMessage(player, lines)` | Chat message (legacy `§` codes supported); returns a `CompletableFuture`, never blocks |
| `cloud.players().kick(player, reason)` | Kick with reason; returns a `CompletableFuture`, never blocks |
| `cloud.players().connect(player, targetService)` | Transfer via the proxy (`send`); returns a `CompletableFuture`, never blocks, needs a running proxy |
| `cloud.services().all() / findByName / findByGroup(group) / findOnline()` | Service snapshots |
| `CloudService.executeCommand(commandLine)` | Console command on that service (runs on its main thread); returns a `CompletableFuture` |
| `cloud.groups().all() / findByName(name)` | Group configuration |
| `cloud.status()` | Full status document in one call |
| `cloud.executeCloudCommand(args) / completeCloudCommand(args)` | The `/cloud` surface from code |

### Player ids and permission checks

`CloudPlayer.uniqueId` is the UUID the player's agent reports, and `CloudPlayer.uuid` is the same value parsed into a
`java.util.UUID` (null instead of throwing when the agent reports none). `findByUniqueId` accepts the UUID with or
without dashes in any letter case; if no online player has that id it falls back to an exact name match, so old
name-based callers keep working.

`hasPermission(player, node)` asks the **server the player is on** whether the node is granted, so the answer comes
from that backend's permission system — LuckPerms (or any other provider installed there) evaluates it with its own
group/luck data. Install LuckPerms on the backend servers (a shared MongoDB store keeps them in sync); a proxy-only
LuckPerms install does not answer Bukkit permission checks on the backends.

The check is a round trip: the cloud queues the node for the player's agent, the agent answers on its next heartbeat
(executed on that server's main thread), and the SDK polls the cloud for the result. It is asynchronous like the
actions above and fails with an `IOException` when the player left, the node could not be evaluated, or the answer
took longer than ten seconds. Agents older than this feature ignore the request (the check then times out), so once
the controller is upgraded, restart the backend services — the cloud reinstalls its bundled agent on every start.

```kotlin
player.hasPermission("group.moderator").thenAccept { granted ->
    if (!granted) player.kick("§cStaff only")
}
```

Actions are asynchronous: they return immediately with a `CompletableFuture` that completes when the cloud accepted
the action, or fails with an `IOException` (bridge unreachable, rejected token, no running proxy, unknown target).
Message and kick are executed by the target service's agent within one heartbeat (default 5 s). A **transfer is
written to the proxy's console right away** — no heartbeat wait — and works with every client version. A player who
left in the meantime yields `CloudPlayerNotFoundException` / graceful `null` lookups.

The HTTP transport is tuned for the loopback bridge: HTTP/1.1 is pinned (no h2c upgrade) and the keep-alive
connection is opened during `connect` and kept warm by the background status refresh, so an action is a single
round trip. Actions run on a shared daemon dispatcher, which is why the calling thread is never blocked - a server
main thread can call `connect`/`sendMessage`/`kick` directly.

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

## 3. Raw HTTP (scripts, bots, tools)

Every data endpoint needs `Authorization: Bearer <bridge.token>`:

```bash
curl -H "Authorization: Bearer $(cat bridge.token)" http://127.0.0.1:25580/bridge/status
```

| Endpoint | Returns |
|---|---|
| `GET /bridge/status` | Totals, groups, per-service state/port/players (plus enriched `player-details`: UUID/ping/world/gamemode when the agent reports them)/TPS/heap/CPU/agent version |
| `GET /bridge/metrics` | Rolling history (players, TPS, RAM, host CPU…) + per-service metrics |
| `GET /bridge/host` | Root-server CPU/memory/swap/uptime/load/OS/Java + per-process CPU |
| `GET /bridge/activity` | Recent lifecycle events, newest first |
| `GET /bridge/console?service=` | Last 200 console lines |
| `POST /bridge/players` | `action=transfer&target=` → `202`, written to the proxy console off the request thread; `action=message|kick|inventory|permission` → queued for the player's agent (`permission` also needs `node=` and answers with a `request-id`) |
| `GET /bridge/players/permission?request-id=` | Answer of a queued permission check: `202` + `pending`, `200` + `granted`/`denied`, `422` when it could not be evaluated, `404` for an unknown/expired id |
| `POST /bridge/services/command` | Run a console command on a service |
| `POST /bridge/cloud` | The `/cloud` command surface (`arg` repeated, `mode=execute|complete`) |
| `POST /bridge/heartbeat` | Agent-only telemetry ingestion |

The full request/response shapes live in [docs/API.md](../API.md). Cookie/session auth (the dashboard's way) and
CSRF rules are documented in [docs/SECURITY.md](../SECURITY.md).

## Good to know

- The bridge binds to `127.0.0.1` by default — keep it that way and use a TLS reverse proxy for remote access.
- The token grants full read access *and* command dispatch: distribute it like a password.
- The facade and client throw `IOException` on transport errors, a rejected token (401), or unknown subcommands.
- `bridge.heartbeat-interval-seconds` (default 5) controls how fresh player/TPS/CPU data is.

---

**Next:** [📦 Versions](Versions.md) — supported software and Java matrix.
