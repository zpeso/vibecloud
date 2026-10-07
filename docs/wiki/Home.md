# 🏠 VibeCloud Wiki

**VibeCloud** is a single-node Minecraft cloud controller written entirely in Kotlin. It turns one Linux or Windows
machine into a managed network: you define **groups** (Paper, Spigot, Velocity, BungeeCord) with exact pinned builds,
and VibeCloud provisions, launches, watches, and reconciles **services** — with a built-in web dashboard, a live HTTP
bridge, and a plugin API for your own Minecraft plugins.

## Why VibeCloud

- **One binary, one config, zero dependencies** — download the zip, run it, done. No Docker, no databases, no agents to install by hand.
- **Guided setup** — `group create` is a wizard that queries the live PaperMC/Fill catalog, verifies checksums, and pins exact builds.
- **Desired-state reconciliation** — services that crash are restarted with backoff; stopped records are reused; ports are managed.
- **Web dashboard** — charts, players, console, per-service CPU/TPS/memory, host health, activity feed. Served by the cloud itself.
- **Developer API** — talk to the cloud from your own plugins (`VibeCloud` facade) or embed the whole controller in your app.
- **Security-first bridge** — token or session auth, CSRF protection, strict CSP, rate limiting, no shell anywhere.

## Documentation tabs

| Tab | Contents |
|---|---|
| [🚀 Installation](Installation.md) | Requirements, first run, config reference, updating |
| [⌨️ Commands](Commands.md) | Every console and in-game command with examples |
| [🖥️ Dashboard](Dashboard.md) | All dashboard pages, actions, and login/security model |
| [🧩 Bridge API](Bridge-API.md) | For developers: use the cloud from your plugin or app |
| [📦 Versions](Versions.md) | Supported server software, Java matrix, version switching |
| [🧑 ServerMobs](ServerMobs.md) | Bundled NPC plugin: fake players with skins, holograms and click actions |

## How it fits together

```text
┌────────────────────────── one machine ──────────────────────────┐
│  VibeCloud controller (CLI + dashboard + reconciler + bridge)   │
│        ▲ HTTP bridge (token)              │ process control     │
│        │                                  ▼                     │
│  ┌─────┴──────┐   ┌────────────┐   ┌───────────┐   ┌─────────┐  │
│  │  Velocity  │   │  lobby-1   │   │  lobby-2  │   │ cityb-1 │  │
│  │  (proxy)   │──▶│ + agent    │   │ + agent   │   │ + agent │  │
│  └────────────┘   └────────────┘   └───────────┘   └─────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

- The **controller** is the brain: groups, templates, ports, processes, metrics, dashboard.
- Every backend server gets the **agent plugin** injected automatically — it reports players, TPS, memory and CPU
  every few seconds and executes cloud commands (messages, kicks, transfers, console commands).
- **Your plugins** can join in: the bridge API gives them network-wide players, services, and groups in a few lines.

## Module map

| Module | Purpose |
|---|---|
| `api` | Public immutable models, manager interfaces, lifecycle events |
| `common` | Safe YAML configuration, structured logging |
| `core` | Local implementation: groups, services, templates, catalogs, ports, reconciler, bridge |
| `bridge` | Paper agent plugin + the `VibeCloud`/`VibeCloudClient` SDK for plugins |
| `servermobs` | Bundled NPC plugin for backend servers (PacketEvents-based fake players) |
| `launcher` | Composition root and the interactive CLI |

## Links

- Releases & downloads: <https://github.com/zpeso/vibecloud/releases>
- JitPack (Maven artifacts): <https://jitpack.io/#zpeso/vibecloud>
- Deep API reference: [docs/API.md](../API.md) · Security guide: [docs/SECURITY.md](../SECURITY.md)
