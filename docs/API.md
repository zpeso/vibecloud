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
    implementation("com.github.zpeso.vibecloud:api:v0.7.5")
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
plugin inside each backend server POSTs the live player roster to the cloud, and the cloud exposes it together with
service state. From an embedded `Cloud` you can read the tracked values directly:

```kotlin
val online = cloud.services.all().filter { it.state == ServiceState.RUNNING }
// bridgeTracker is owned by the cloud internals; plugins should use the HTTP bridge instead (next section).
```

What the `ServiceManager` alone gives you is capacity (ports, states); live player counts always come from the bridge.

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
controller process. VibeCloud ships a ready-made transport for this: the **bridge**.

### How it works

1. The cloud runs a local HTTP endpoint (`bridge.bind-address:bridge.port`, default `127.0.0.1:25580`) protected by a
   shared token stored in `bridge.token` next to `config.yml`.
2. On every backend service start the cloud injects the bundled agent plugin (`VibeCloud-Agent.jar`) and its
   `agent.properties` into the service. The agent reports the exact online player roster every few seconds.
3. Your plugins read the status over HTTP using the SDK from the `bridge` module (package
   `dev.vibecloud.api.bridge`, published as `dev.vibecloud:bridge`). The agent jar itself also contains these SDK
   classes, so the classes are already on every backend server.

### Reading player counts in a lobby plugin

```kotlin
import dev.vibecloud.api.bridge.VibeCloudClient
import java.nio.file.Files
import java.nio.file.Path

class LobbyScoreboard(plugin: JavaPlugin) {
    private val client = VibeCloudClient.builder()
        .baseUrl("http://127.0.0.1:25580")
        // The cloud copies bridge.token next to config.yml; read it however you distribute secrets.
        .token(Files.readString(Path.of("/opt/vibecloud/bridge.token")).trim())
        .timeout(java.time.Duration.ofSeconds(2))
        .build()

    /** Call on your own scheduler thread (never the main thread). */
    fun networkPlayerCount(): Int = client.status().totalPlayersOnline

    fun citybuildPlayers(): List<String> =
        client.status().services.filter { it.group == "citybuild" }.flatMap { it.players }
}
```

Notes:

- Call the client from an async thread (`Bukkit.getScheduler().runTaskTimerAsynchronously(...)`); every call is a
  blocking HTTP request with a 2-3 second timeout.
- For frequently read state, use `VibeCloud.forService()` + `cloud.temporary()`. It exposes cached player/service/group
  snapshots refreshed asynchronously every five seconds by default; `temporary().players().findByName(name)?.group`
  is an in-memory lookup safe to poll on the main thread. `cloud.players().playerCount()` shares the same cache.
  Configure the cadence with the `temporaryRefreshInterval` builder option.
- The agent reports every 5 seconds by default (`bridge.heartbeat-interval-seconds`), so counts are near-real-time.
- `agentOnline` tells you whether the report comes from the authoritative agent or the console-line fallback.
- The status JSON also carries `totals.online`, per-service `state`/`port`/`players`, and the group definitions.
- `status().services` entries expose `playerDetails` (`PlayerDetail`: `name`, `uuid`, `pingMs`, `world`,
  `gamemode`) when the agent reports enriched metadata; `players` always carries the plain names.
- Inside a cloud-managed service you can skip the manual setup entirely: `VibeCloudClient.forService()` reads the
  cloud URL and token from the service's `plugins/VibeCloud/agent.properties`.

### Dependency setup for your plugin

Both `api` and `bridge` publish Maven artifacts. For local development, install them into your local Maven
repository and depend on them from the plugin project:

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

For GitHub-based consumption without running a repository, tag a release and let **JitPack** build it on demand:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { compileOnly("com.github.zpeso.vibecloud:bridge:v0.7.5") }
```

(The zero-dependency alternative: the agent jar already shades the `dev.vibecloud.api.bridge` SDK classes, so the
classes are present on every cloud-managed server once the agent is installed.)

### Authentication

Every request needs `Authorization: Bearer <token>` where the token is the contents of `bridge.token`. Treat that
file like a password: it grants read access to the whole cloud status. The endpoint binds to `127.0.0.1` by default;
only expose it through an authenticated TLS reverse proxy.

### The VibeCloud facade (recommended entry point)

`dev.vibecloud.api.bridge.VibeCloud` is the one object you interact with. Call `VibeCloud.forService()` once during
plugin startup (reads the agent config the cloud installs), then use `VibeCloud.instance` anywhere:

```kotlin
import dev.vibecloud.api.bridge.VibeCloud
import dev.vibecloud.api.bridge.CloudPlayerNotFoundException

class NetworkMessenger : Listener {
    private val cloud get() = VibeCloud.instance

    fun broadcastExcept(sender: String, message: String) {
        // Call from an async thread - every provider call is an HTTP request.
        cloud.players().all()
            .filter { it.name != sender }
            .forEach { it.sendMessage("§b[Network] §f$message") }
    }

    fun sendToLobby(playerName: String) {
        val player = cloud.players().findByName(playerName) ?: return
        val lobby = cloud.services().findByGroup("lobby").firstOrNull() ?: return
        player.connect(lobby)
    }

    fun kickCheater(name: String) {
        val player = cloud.players().findByName(name) ?: return
        player.kick("§cYou were removed from the network.")
    }

    fun restartCitybuild() {
        cloud.services().findByGroup("citybuild").forEach { it.executeCommand("say Restarting in 10s") }
    }
}
```

Actions run through the cloud: message/kick are queued for the target service's agent and executed on that server's
main thread within one heartbeat interval (default 5s). `player.connect(...)` is dispatched through the proxy's
console (`send <player> <server>`), so it works with every client version and needs no configuration - it just
requires a running proxy service.

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
val lines = VibeCloud.instance.executeCloudCommand("services")   // full response text
val subs = VibeCloud.instance.completeCloudCommand(listOf("start", ""))  // tab-completion suggestions
```

HTTP surface: `POST /bridge/cloud` with repeated `arg` fields, optional `player` (caller, for logs) and
`mode=execute` (default) or `mode=complete`; responds `{"lines":[...]}` or `{"suggestions":[...]}`.

### The web dashboard

The bridge serves a built-in dashboard at its root: `http://<cloud-host>:<bridge-port>/` (default
`http://127.0.0.1:25580/`). No extra service, no build step — the page, stylesheet and script ship
inside the controller (served from the core jar as classpath resources).

- **Login**: paste the bridge token (the contents of `bridge.token` next to `config.yml`). It is
  exchanged for an HttpOnly session cookie — the token itself never touches JavaScript or browser
  storage. Cookie-authenticated state-changing requests carry a CSRF header automatically.
- **Local + remote**: the bridge binds to `127.0.0.1` by default (open the dashboard locally or
  through an SSH tunnel). Set `bridge.bind-address: 0.0.0.0` in `config.yml` to reach it remotely —
  every data endpoint requires the token, and without it only the empty login page is served.
- **Live data**: totals, per-service TPS, heap and **CPU usage** (reported by the agent every
  heartbeat), players, groups, rolling charts (players / TPS / memory / running services) sampled
  once per reconciliation cycle, and the recent console output of any running service.
- **Pages**: the sidebar switches between Overview (stat cards, network activity chart, group
  distribution, crash alert banner), Players (skin heads, current server, one-click transfer via
  dropdown, kick), Services (state tabs with counts, CPU/TPS/memory meters, start/restart/stop),
  Groups (per-group cards with capacity bars plus the table, backend/proxy tabs, `Start another`),
  Console (per-service output with auto-refresh and command input), **Host & Health** (host CPU
  load chart, system memory chart, uptime, JVM heap, OS/Java details, per-process CPU), and
  **Activity** (recent service lifecycle events, newest first).
- **Actions**: start/stop/restart per service, `group start <name>`, `group <name> restart`, player transfers and kicks,
  and the full cloud command surface (with Tab completion) in the top command bar — the same
  authority as the interactive console.
- **Security hardening**: the shell, stylesheet and script are separate same-origin assets, so the
  Content-Security-Policy locks scripts to `'self'` — no more `'unsafe-inline'` scripts (styles keep
  inline attributes, which cannot execute script).

Bridge endpoints (all data endpoints require the token or a session):

```text
GET /                   → the dashboard HTML (public; no data)
GET /assets/app.css     → the dashboard stylesheet (public)
GET /assets/app.js      → the dashboard script (public)
GET /bridge/metrics     → {"history":{"points":[{t,players,running,services,tps,ram,cpu,sysram,jvmheap},...]},"services":[{name,tps,ram_usage,heap_used_mb,heap_max_mb,cpu}]}
GET /bridge/host        → host system metrics: {cpu, process-cpu, cores, memory-total-mb, memory-used-mb, swap-total-mb, swap-used-mb, jvm-used-mb, jvm-max-mb, uptime-seconds, load-average, os-name, os-version, os-arch, java-version, version, started-at, processes:{"<service>":cpu}}
GET /bridge/activity    → {"events":[{t,kind,message},...]} newest first (service lifecycle)
GET /bridge/console?service=<name> → {"lines":[...]}  (last 200 console lines)
POST /bridge/players  action=inventory → queue an inventory snapshot request (202 {"queued":true,"request-id":...})
GET /bridge/players/inventory?service=&player= → last snapshot {service,player,captured-at,items:[{slot,material,count,durability,name,lore[],enchantments[]}]}
```

Player inspection wire notes: `slot` is `helmet`, `chestplate`, `leggings`, `boots`, `offhand` or a
1–36 storage index (1–9 hotbar, 10–36 main). Inspection records in the heartbeat's `inspections`
field are `request-id\u0002payload` joined by `\u001e` (agents ≥0.9.0; older agents joined by
`\u0001`, which limited a snapshot to its first item — the cloud accepts both). The roster's
`player-meta` field order is append-only; 0.9.0 appended saturation, allowed-flight, sneaking,
sprinting, gliding, sleeping and in-vehicle after `flying` (index 16).

Agent heartbeats additionally carry `tps`, `heap-used-mb`, `heap-max-mb` and `process-cpu`; `GET
/bridge/status` includes the same values per service plus `agent-version`, `restarts`, `last-error`
and `totals.agents-online`.

`/bridge/host` values come from the controller JVM's `com.sun.management.OperatingSystemMXBean`:
`cpu` is the whole-host CPU load (0..1) — the number to watch for the root server's CPU — while
`process-cpu` is the controller process's own share. Per-service CPU lives in `processes` and in
each service's `cpu` field in status/metrics, reported by the agents themselves.

### The raw client (still available)

`VibeCloudClient` stays as the low-level transport. `VibeCloudClient.forService()` is deprecated in favor of
`VibeCloud.forService()`; the facade also exposes `status()` for the full document.

### Authentication

Every request needs `Authorization: Bearer <token>` where the token is the contents of `bridge.token`. Treat that
file like a password: it grants read access to the whole cloud status (and command dispatch on the write endpoints).
The endpoint binds to `127.0.0.1` by default; only expose it through an authenticated TLS reverse proxy.

## What the API does not do (yet)

- **Proxy-less player transfers.** `connect` needs a running proxy service (it uses the proxy's `send` command);
- **Remote nodes.** `Cloud` manages local processes; multi-node scheduling is future work behind the same interfaces.
- **Persistence hooks.** Non-static services are wiped on start by design; store durable data outside
  `services/<name>/` (or use static groups).
