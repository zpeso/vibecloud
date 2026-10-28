# 🏠 VibeCloud Wiki

**VibeCloud** is a single-node Minecraft cloud controller written entirely in Kotlin. It turns one Linux or Windows
machine into a managed network: you define **groups** (Paper, Spigot, Velocity, BungeeCord) with exact pinned builds,
and VibeCloud provisions, launches, watches, and reconciles **services** — with a live Redis bridge and a plugin API
for your own Minecraft plugins.

## Why VibeCloud

- **One binary, one config** — download the zip, run it, done. Redis is the only companion service.
- **Guided setup** — `group create` is a wizard that queries the live PaperMC/Fill catalog, verifies checksums, and pins exact builds.
- **Desired-state reconciliation** — services that crash are restarted with backoff; stopped records are reused; ports are managed.
- **Live network state over Redis** — agents publish their rosters, the controller publishes one status document, and plugins read it synchronously.
- **Limbo fallback** — name a limbo service in `config.yml` and players land there whenever no other backend can take them
  (lobby down, everything still booting), instead of being refused.
- **Developer API** — talk to the cloud from your own plugins (`VibeCloud` facade) or embed the whole controller in your app.
- **No shell anywhere** — processes launch through structured argument lists; cloud commands are written to a server's stdin.

## Documentation tabs

| Tab | Contents |
|---|---|
| [🚀 Installation](Installation.md) | Requirements, first run, config reference, updating |
| [⌨️ Commands](Commands.md) | Every console and in-game command with examples |
| [🧩 Bridge API](Bridge-API.md) | For developers: use the cloud from your plugin or app |
| [📦 Versions](Versions.md) | Supported server software, Java matrix, version switching |
| [🧑 ServerMobs](ServerMobs.md) | Bundled NPC plugin: fake players with skins, holograms and click actions |

## How it fits together

```text
┌────────────────────────── one machine ──────────────────────────┐
│   VibeCloud controller (CLI + reconciler + bridge)              │
│        ▲                             │ process control          │
│        │ Redis (127.0.0.1:6379)      ▼                          │
│  ┌─────┴──────┐   ┌────────────┐   ┌───────────┐   ┌─────────┐  │
│  │  Velocity  │   │  lobby-1   │   │  lobby-2  │   │ cityb-1 │  │
│  │  (proxy)   │──▶│ + agent    │   │ + agent   │   │ + agent │  │
│  └────────────┘   └────────────┘   └───────────┘   └─────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

- The **controller** is the brain: groups, templates, ports, processes, and the bridge.
- Every backend server gets the **agent plugin** injected automatically — it publishes players, TPS, memory and CPU
  every few seconds, consumes its console commands, and executes player actions (messages, kicks, permission checks).
- **Your plugins** can join in: the bridge API gives them network-wide players, services, and groups in a few lines.
- Redis carries all of it: heartbeats and actions on channels, commands and answers on lists, and the cloud status on
  one key (`vibecloud:status`) that every reader parses.

## Module map

| Module | Purpose |
|---|---|
| `api` | Public immutable models, manager interfaces, lifecycle events |
| `common` | Safe YAML configuration, structured logging |
| `core` | Local implementation: groups, services, templates, catalogs, ports, reconciler, Redis bridge |
| `transport` | Redis gateway, bridge key layout and JSON shared by controller, agent and SDK |
| `bridge` | Paper agent plugin + the `VibeCloud` SDK for plugins |
| `servermobs` | Bundled NPC plugin for backend servers (PacketEvents-based fake players) |
| `launcher` | Composition root and the interactive CLI |

## Links

- Releases & downloads: <https://github.com/zpeso/vibecloud/releases>
- JitPack (Maven artifacts): <https://jitpack.io/#zpeso/vibecloud>
- Deep API reference: [docs/API.md](../API.md) · Security guide: [docs/SECURITY.md](../SECURITY.md)
