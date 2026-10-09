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
    compileOnly("com.github.zpeso.vibecloud:bridge:v1.0.1")  // dev.vibecloud.api.bridge.*
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

### What you can do

Every call is a snapshot + an HTTP round-trip to the cloud — call from an **async thread**
(`Bukkit.getScheduler().runTaskTimerAsynchronously(...)`), never the main thread.

```kotlin
val cloud = VibeCloud.instance()

// players — network-wide
val players: List<CloudPlayer> = cloud.players().all()
val notch = cloud.players().findByName("Notch")
notch?.let {
    cloud.players().sendMessage(it, "§aWelcome to the network!")
    cloud.players().kick(it, "§cBye.")                       // optional reason
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
| `cloud.players().all() / findByName(name) / refresh(player)` | Fresh roster/lookups (HTTP; use asynchronously) |
| `cloud.temporary().players().all() / findByName(name)` | Cached player roster and details; never performs HTTP on the caller |
| `cloud.temporary().playerCount()` / `cloud.players().playerCount()` | Cached network player count (background refresh, default 5s) |
| `cloud.temporary().services() / groups()` | Cached service and group snapshots |
| `cloud.players().sendMessage(player, lines)` | Chat message (legacy `§` codes supported) |
| `cloud.players().kick(player, reason)` | Kick with reason |
| `cloud.players().connect(player, targetService)` | Transfer via the proxy (`send`); needs a running proxy |
| `cloud.services().all() / findByName / findByGroup(group) / findOnline()` | Service snapshots |
| `CloudService.executeCommand(commandLine)` | Console command on that service (runs on its main thread) |
| `cloud.groups().all() / findByName(name)` | Group configuration |
| `cloud.status()` | Full status document in one call |
| `cloud.executeCloudCommand(args) / completeCloudCommand(args)` | The `/cloud` surface from code |

Actions return immediately and are executed by the target service's agent within one heartbeat (default 5 s).
Transfers are dispatched through the proxy's console, so they work with every client version. A player who left in
the meantime yields `CloudPlayerNotFoundException` / graceful `null` lookups.

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
| `POST /bridge/players` | `action=message|kick|transfer` (form-encoded) |
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
