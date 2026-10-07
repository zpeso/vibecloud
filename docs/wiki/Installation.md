[🏠 Home](Home.md) · **🚀 Installation** · [⌨️ Commands](Commands.md) · [🖥️ Dashboard](Dashboard.md) · [🧩 Bridge API](Bridge-API.md) · [📦 Versions](Versions.md) · [🧑 ServerMobs](ServerMobs.md)

# 🚀 Installation

## Requirements

| Component | Requirement |
|---|---|
| Operating system | Linux (recommended), Windows, or macOS |
| Controller JVM | **Java 25** — the `java` command must be on `PATH` |
| RAM for the controller | 512 MiB is plenty; Minecraft servers need their own (see [Versions](Versions.md)) |
| Disk | ~150 MiB for the install + whatever your servers and templates need |
| Network | Outbound HTTPS to papermc.io / spigotmc.org for the version catalog and downloads |

## 1. Download & extract

Grab the latest zip from the [releases page](https://github.com/zpeso/vibecloud/releases) and extract it anywhere —
the folder is self-contained and can be moved or copied between machines:

```text
vibecloud/
├── bin/vibecloud(.sh/.bat)   start scripts
├── lib/                      runtime jars (incl. the auto-injected agent)
├── plugins/ServerMobs.jar    bundled NPC plugin (install by hand — see ServerMobs tab)
├── config.yml                initial configuration (created on first run)
├── README.md
└── LICENSE
```

## 2. Start the controller

```bash
# Linux
cd vibecloud/bin && ./vibecloud          # or vibecloud.sh
# Windows
vibecloud\bin\vibecloud.bat
```

On first start the cloud creates everything it needs next to `config.yml`:

- `bridge.token` — the shared access token for the dashboard and API (**treat it like a password**)
- `forwarding.secret` — the shared proxy forwarding secret for your network
- `templates/` and `services/` directories, plus `templates/every_server/`, `templates/every_proxy/`, and per-group overlay folders
- An **empty** `config.yml` — a fresh install starts with zero groups and zero services

You are now looking at the interactive CLI. Type `help` to see everything.

## 3. Create your first network

```text
group create lobby                # wizard: type → version → build → service counts
service create lobby              # provisions lobby-1 from the pinned build
service lobby-1 start             # launches it (first start downloads + verifies the jar)

group create proxy                # same wizard, choose VELOCITY
service create proxy && service proxy-1 start
```

- The wizard shows live catalog versions/builds with metadata (channel, date, size, SHA-256, Java requirement).
- Answer prompts one by one — type `exit` at any question to cancel without changes.
- Prefer no prompts? Use flags: `group create lobby --type PAPER --version 26.2 --build 129 --min-services 1 --max-services 3`
- The cloud wires the proxy automatically: shared forwarding secret, `online-mode`, backend table, forwarding mode.

## 4. Open the dashboard

Browse to `http://<your-host>:25580/` (the `bridge.port` default, bound to `127.0.0.1`) and sign in with the contents
of `bridge.token`. Everything the CLI can do is in the dashboard too — see the [Dashboard tab](Dashboard.md).

## 5. Accept the EULA (or not)

`runtime.eula-accepted: true` is the default and is written to every Paper/Spigot service's `eula.txt`.
Read the [Minecraft EULA](https://www.minecraft.net/eula) and set it to `false` in `config.yml` if you do not agree.

## Config reference (`config.yml`)

```yaml
directories:
  templates: templates          # resolved relative to this file
  services: services

ports:
  start: 25565                  # inclusive range for service allocation
  end: 25664

runtime:
  java-command: java            # JVM used for modern servers
  legacy-java-command: ""       # optional: used automatically for pre-1.17 servers (e.g. /usr/bin/java8)
  min-memory-mb: 512
  max-memory-mb: 2048
  jvm-args: [ ]                 # extra flags for every service JVM
  startup-timeout-seconds: 180
  shutdown-timeout-seconds: 30
  eula-accepted: true

reconciliation:
  interval-seconds: 5           # desired-state check cycle

bridge:
  enabled: true
  port: 25580
  bind-address: "127.0.0.1"     # keep localhost; expose via TLS reverse proxy only
  heartbeat-interval-seconds: 5
  offline-timeout-seconds: 20

# groups are managed by `group create/delete` and persisted here automatically
```

`cloud reload` re-reads group definitions from disk. Directory, port, and runtime settings need a controller restart.

## Updating

The launcher self-updates from GitHub releases on boot. Restart it and you are current:

```bash
# typical Debian setup under screen — stop, then start again
screen -S cloud -X stuff $'exit\n'
sleep 3
cd /home/vibecloud/bin && screen -S cloud sh vibecloud.sh
```

Running servers keep running their current binaries until restarted; newly provisioned services always use the
pinned build. Never delete `bridge.token` while services run — agents re-read it on their next start.

## Where everything lives

| Path | Contents |
|---|---|
| `templates/<type>/<build-key>/` | Cached, checksum-verified server jars (shared between groups) |
| `templates/every_server/` | Shared files copied to all backend servers, not proxies |
| `templates/every_proxy/` | Shared files copied to all proxies, not backend servers |
| `templates/groups/<group>/` | Per-group overlay copied last, on top of the shared scope |
| `services/<name>/` | One provisioned service (worlds, plugins, configs live here) |
| `bridge.token` | Dashboard/API access token |
| `servermobs/` | NPC definitions, written by the bundled ServerMobs plugin (see [ServerMobs](ServerMobs.md)) |
| `forwarding.secret` | Network-wide Velocity forwarding secret |

---

**Next:** [⌨️ Commands](Commands.md) — everything the CLI can do.
