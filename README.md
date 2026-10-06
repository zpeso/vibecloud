# VibeCloud

[![Release CI](https://github.com/zpeso/vibecloud/actions/workflows/release.yml/badge.svg)](https://github.com/zpeso/vibecloud/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/zpeso/vibecloud)](https://github.com/zpeso/vibecloud/releases)
[![JitPack](https://jitpack.io/v/zpeso/vibecloud.svg)](https://jitpack.io/#zpeso/vibecloud)

**A single-node Minecraft cloud controller written entirely in Kotlin.** Define groups (Paper, Spigot, Velocity,
BungeeCord) with exact pinned builds — VibeCloud provisions, launches, watches, and reconciles your servers, with a
built-in web dashboard, a live HTTP bridge, and a plugin API. One zip, one config, zero dependencies.

**It supports 1.8 up to always the newest paper & velocity version!**
<img width="1864" height="911" alt="grafik" src="https://github.com/user-attachments/assets/c13d5c5d-452f-480e-a747-c960895ea6fd" />


## Highlights

- **Guided setup** — `group create` queries the live PaperMC catalog, shows build metadata, verifies checksums, and pins exact builds. The console is a polished TUI with Tab completion and colored output.
- **Desired-state reconciliation** — crashed services restart with bounded backoff, stopped records are reused, ports are managed. You start extras; the cloud never kills them.
- **Web dashboard** — overview charts, players with skin heads (transfer/kick), service meters (CPU/TPS/memory), live console, host health of your root server, activity feed. Served by the cloud, secured by token + session auth with a strict CSP.
- **Automatic proxy wiring** — shared forwarding secret, backend tables, `online-mode`, and the correct Velocity forwarding mode (modern/legacy) recomputed on every version switch.
- **Version switching** — `group version lobby 26.2` downloads, verifies, re-pins every service record, and resyncs the proxy. Same system only (paper → paper), legacy Java handled automatically for old builds.
- **Plugin API** — the `VibeCloud` facade gives your Minecraft plugins network-wide players, services, groups, and the full `/cloud` command surface in a few lines. Or embed the entire controller in your own app.

## Quick start

1. **Download** the latest `vibecloud-<version>.zip` from the [releases page](https://github.com/zpeso/vibecloud/releases) and extract it anywhere.
2. **Run it** — `bin/vibecloud` (Linux) or `bin\vibecloud.bat` (Windows). Java 25 on `PATH` is the only requirement. The cloud creates `config.yml`, tokens, and directories on first start.
3. **Create a network** — `group create lobby` walks you through type → version → build → counts (`exit` cancels at any prompt). Then `service create lobby` and `service lobby-1 start`. Repeat with a `VELOCITY` group for your proxy; wiring is automatic.
4. **Open the dashboard** — `http://127.0.0.1:25580/` and sign in with the contents of `bridge.token`.

## Supported software & versions

| Software | Source | Status |
|---|---|---|
| Paper | PaperMC Fill catalog | ✅ all versions, exact pinned builds, SHA-256 verified |
| Velocity | PaperMC Fill catalog | ✅ recommended proxy, fully auto-wired |
| BungeeCord | SpigotMC Jenkins | ✅ local checksum verification |
| Waterfall | PaperMC | ⚠️ end-of-life, selectable but labeled |
| Spigot | BuildTools | ✅ via local template (`templates/spigot/<version>/server.jar`) |

**Controller:** Java 25 · Kotlin 2.4.20 · Gradle 9.8. **Servers:** 26.x needs Java 25, 1.17–1.21.x needs 17–21, pre-1.17 uses `runtime.legacy-java-command` — picked automatically per version. Details in the [Versions tab](docs/wiki/Versions.md).

## Documentation

| | |
|---|---|
| [🚀 Installation](docs/wiki/Installation.md) | Requirements, first run, config reference, updating |
| [⌨️ Commands](docs/wiki/Commands.md) | Every console and in-game command |
| [🖥️ Dashboard](docs/wiki/Dashboard.md) | All pages, actions, and the security model |
| [🧩 Bridge API](docs/wiki/Bridge-API.md) | For developers: facade, embedding, events, HTTP |
| [📦 Versions](docs/wiki/Versions.md) | Supported software, Java matrix, forwarding, switching |
| [🔒 Security](docs/SECURITY.md) | Hardening guide, reverse-proxy setup, threat model |
| [📡 API reference](docs/API.md) | Exhaustive endpoint and embedding reference |

## Building from source

```bash
./gradlew test                        # run the test suite
./gradlew :launcher:releaseZip        # build/dist/vibecloud-<version>.zip
./gradlew release -Prelease=X.Y.Z     # full release: tests, zip, commit, tag, push
```

```
api/       Public immutable models, manager interfaces, lifecycle events
common/    Safe YAML configuration, structured logging
core/      Groups, services, templates, catalogs, ports, reconciler, bridge
bridge/    Paper agent plugin + the VibeCloud SDK for plugins
launcher/  Composition root and the interactive CLI
```

`TemplateManager`, `ServerCatalog`, `ProcessManager`, and `PortAllocator` are interfaces — distribution metadata,
artifact acquisition, and future remote-node implementations remain replaceable seams.

## Safety notes

The example config accepts the Minecraft EULA by default — read it and set `runtime.eula-accepted: false` if you do
not agree. The controller assigns ports but does not configure firewalls; review generated service configs and your
host firewall before going public. Run the cloud under a dedicated OS account and keep `bridge.token` secret — it
grants full dashboard and API access. See [docs/SECURITY.md](docs/SECURITY.md).
