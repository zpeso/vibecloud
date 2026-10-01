# VibeCloud

A small, single-node Minecraft cloud controller written entirely in Kotlin. It manages local server processes as
**Services**, provisioned from versioned **Group** templates. It is intentionally much smaller than a multi-node
platform: the public API, process boundary, template provider registry, and port allocator are replaceable seams for
future growth, while the default implementation remains easy to operate.

## Compatibility

- Kotlin **2.4.20** (stable)
- Gradle **9.8.0** with Kotlin DSL
- Java **25** for this build and for current Paper releases
- Example target: Minecraft Java **26.3** (stable release on September 15, 2026)
- Coroutines **1.11.0**, SnakeYAML **2.7**

Minecraft 26.1+ / Paper currently requires Java 25. Older templates may have different requirements; the controller uses
the configured Java executable for every service.## Build and run

Install a JDK 25, then from this directory:

```bash
./gradlew clean testClasses :launcher:installDist
./gradlew test
./launcher/build/install/vibecloud/bin/vibecloud
```

Compile/package and test in separate Gradle invocations on memory-constrained hosts; this keeps the Kotlin compiler and test worker from peaking together.

On Windows, use `launcher\build\install\vibecloud\bin\vibecloud.bat`. You can also run during development with `./gradlew :launcher:run`.

### Production release

One command does everything — tests, zip, version bump, commit, tag, push:

```bash
./gradlew release -Prelease=0.2.0
```

This runs the full test suite, builds `build/dist/vibecloud-0.2.0.zip`, commits all pending
changes as `Release 0.2.0`, tags `v0.2.0` and pushes both. The pushed tag triggers GitHub
Actions (`.github/workflows/release.yml`), which attaches the zip to the GitHub Release and
kicks JitPack so the published API artifacts are rebuilt from the same tag — no extra steps.

Manual alternative (no push, no bump):

```bash
./gradlew :launcher:releaseZip
# → build/dist/vibecloud-<version>.zip
```


The zip layout is location-independent — extract it anywhere:

```text
vibecloud/
├── bin/vibecloud(.sh/.bat)   start scripts (stored executable in the zip)
├── lib/                  runtime jars
├── config.yml            initial configuration
├── README.md
└── LICENSE
```

The launcher finds `config.yml` in this order: `--config <path>` argument → current working directory → `VIBECLOUD_HOME` environment variable → next to the installation (derived from the launcher jar location). `templates/`, `services/`, and `forwarding.secret` are created or resolved **relative to the config file**, so the extracted folder is fully self-contained and can be moved or copied between machines. Run `bin/vibecloud(.bat)` from any working directory; the scripts also accept `VIBECLOUD_OPTS` for JVM flags (e.g. `set VIBECLOUD_OPTS=-Xmx1g`).

The launcher reads `config.yml` from its current working directory (or `--config /path/to/config.yml`). The example
groups pin exact PaperMC builds but start with zero desired services; the first `service create` will fetch the JAR if
that pinned build is not cached. No Minecraft binaries are bundled in the source archive.

## Version catalog and templates

`group create` opens a guided wizard. It queries the live catalog, lets you select a server distribution, version, and
exact build, displays build metadata (channel, date, Java requirement, artifact URL, checksum, commits), then downloads
and verifies the JAR before creating the group. The group version stores the pinned build key, for example
`paper-26.2-129`; this makes future services reproducible. Metadata is kept beside the cached JAR in
`.server-build.properties`.

- **Paper, Velocity, and Waterfall:** version/build metadata and download URLs come from PaperMC's Fill v3 API
  (`fill.papermc.io` / `fill-data.papermc.io`). SHA-256 and expected file size are checked before install.
- **BungeeCord:** build metadata and artifact paths come from the official SpigotMC Jenkins API. Jenkins does not
  publish a SHA-256, so the cloud records a local checksum after download.
- **Waterfall:** also available as a Bungee-compatible PaperMC project, but it is end-of-life. The wizard labels it
  clearly; prefer Velocity for new networks.
- **Spigot:** no ready-to-run server JAR is available from PaperMC Fill. Use the official BuildTools to prepare a local
  template at `templates/spigot/<version>/server.jar`.

The downloader caches builds under `templates/<type>/<pinned-build-key>/server.jar`. Existing prepared local templates
can also be selected from the wizard. Velocity/Bungee-compatible templates get starter configs when absent; configure
backend addresses, secrets, authentication, and firewall rules before public use. Each service receives a recursive copy
in `services/`, and port settings are rewritten for that service. For static services, existing worlds remain when a
process stops; delete a service only when you intend to remove its data.

### Group overlays and non-static services

Template folders are keyed by **server type and build**, not by group name: a `lobby` group pinned to `paper-26.2-129`
provisions from `templates/paper/paper-26.2-129/`, and a `proxy` group pinned to `velocity-4.0.0-6` from
`templates/velocity/velocity-4.0.0-6/`. Groups sharing the same pinned build also share that cached template.

To give a group its own plugins and configs, use the overlay folder at `templates/groups/<group>/` (for example
`templates/groups/lobby/` or `templates/groups/proxy/`). These folders are created automatically for every configured
group on cloud startup and whenever a group is created via CLI, so they are easy to find. They are copied **on top
of** the shared build template
during every provisioning: overlay files at the same path replace the template's copy, everything else is inherited.
Drop `plugins/`, `world/` seeds, or preconfigured config files there.

Non-static groups (`static: false`) are fully template-based: before every start the service directory is deleted and
re-provisioned from the build template + group overlay, and the cloud re-applies its managed files (`eula.txt`,
`server.properties` port/online-mode, proxy forwarding). Worlds, logs, and any files created at runtime never survive a
restart — only the service's identity (name, port, record) persists. Static groups keep their directories between stops.

## Proxy wiring (forwarding and online-mode)

When the cloud manages a network, it applies a consistent proxy setup automatically:

- A shared forwarding secret is generated once at `forwarding.secret` next to `config.yml` and written to every Velocity
  proxy's `forwarding.secret` file (`forwarding-secret-file` in `velocity.toml`) and to every Paper backend's
  `config/paper-global.yml` (`proxies.velocity.enabled: true`, `secret`).
- Velocity configs are normalized on provision and on every backend sync: an explicit `[forced-hosts]` table is
  guaranteed (an absent table makes Velocity fall back to its sample hosts and refuse to start), and the inline
  `forwarding-secret` is replaced by the shared secret file.
- Backends behind a proxy get `online-mode=false` in `server.properties` (authentication is enforced at the proxy,
  keeping Velocity's `online-mode = true`). Spigot backends also get `settings.bungeecord: true` when legacy forwarding
  is selected.
- Existing service directories are auto-healed on the next reconciliation cycle; a fully clean start is recommended
  after upgrading.

This controller only assigns ports and does not infer firewall rules. Review the generated service configurations and
host firewall before exposing ports publicly.

## Bridge (live player counts and programmatic access)

The bridge is a small local HTTP endpoint plus an agent plugin that connect your Minecraft servers to the cloud:

- The endpoint listens on `bridge.bind-address:bridge.port` (default `127.0.0.1:25580`) and is protected by a shared
  token generated at `bridge.token` next to `config.yml`.
- On every backend service start, the cloud automatically installs the bundled `VibeCloud-Agent.jar` (from `lib/`)
  into the service's `plugins/` folder and writes `plugins/VibeCloud/agent.properties` with the service identity and
  token. Proxies are skipped. You never place the agent into templates.
- The agent reports the exact online player roster every `heartbeat-interval-seconds`. For services without an agent,
  the cloud derives an estimate from console join/leave lines.
- Any plugin or tool can query the status with the agent token:

```text
curl -H "Authorization: Bearer <bridge.token>" http://127.0.0.1:25580/bridge/status
```

The JSON document contains `totals.players-online`, per-service `state`, `port`, `agent-online`, `players-online`,
and the `players` name list. See `docs/API.md` for using the bundled `VibeCloudClient` from Kotlin/Java code, e.g. to
show network-wide player counts inside your lobby plugin.

Keep the endpoint on localhost; if you must expose it, put an authenticated TLS reverse proxy in front of it.

## EULA and network safety

The example defaults to `runtime.eula-accepted: true`. Read the [Minecraft EULA](https://www.minecraft.net/eula); set it
to `false` if you do not agree. The cloud writes the choice to each Paper/Spigot service's `eula.txt` at provision time.

Velocity and BungeeCord templates should be configured with the correct player forwarding / online-mode security for
your network. This controller only assigns ports and does not infer a secure proxy setup. Review the generated service
configurations and host firewall rules before exposing ports publicly.

## Desired-state rules

For each group, the controller computes:

```text
desired running services = max(minServices, alwaysRunningServices)
```

- `minServices` is the minimum running/available count maintained by reconciliation.
- `alwaysRunningServices` is a continuously maintained running count; it can raise the target above `minServices`.
- `maxServices` is the hard cap on **provisioned service records** in the group, including stopped and crashed services.
  Existing stopped services are reused before new instances are created. `minServices <= maxServices` and
  `0 <= alwaysRunningServices <= maxServices` are validated.
- Therefore `alwaysRunningServices: 0` does not disable the minimum: if `minServices` is 1, the target is still 1.
- Scale-down stops excess processes but keeps their directories, names, and ports for reuse. `service delete`
  permanently removes one record and its directory and frees its port. If a group still needs that capacity,
  reconciliation may provision a replacement.
- Unexpected exits are marked `CRASHED` and restarted with bounded exponential backoff. A manual stop is respected only
  while the group's desired count is already met; otherwise reconciliation brings the group back to target.
- Type/version are recorded on each service when it is created. Editing a group's type/version affects future services;
  stop and delete old services (after backing up their data) to migrate them.

## Configuration

`config.yml` contains directory paths, an inclusive port range, runtime defaults, reconciliation timing, and groups.
Paths are resolved relative to the configuration file. Group names are lowercase slugs. New catalog-backed groups store
the exact upstream build key as their `version`; legacy/local groups may continue to use an exact template-folder name.
If a configured pinned build is not cached yet, service provisioning resolves and downloads it from the catalog.

```yaml
groups:
  lobby:
    type: PAPER
    version: "paper-26.2-129"
    min-services: 0
    max-services: 5
    always-running-services: 0
  proxy:
    type: VELOCITY
    version: "velocity-4.0.0-6"
    min-services: 0
    max-services: 2
    always-running-services: 0
```

Group create/delete operations update the YAML configuration. `cloud reload` reloads group definitions from disk;
directory, port, and process runtime settings take effect after a restart. The sample keeps desired counts at zero so it
does not download or launch servers until you explicitly create services or raise the desired counts.

## CLI

```text
help
cloud status
cloud reload

group list
group info lobby
group create                       # prompts for name, type, version, build, and service counts
group create lobby                 # same wizard, with the name prefilled
group create lobby --type PAPER --version 26.2 --build 129 --min-services 1 --max-services 5 --always-running-services 2
group version lobby 1.8.8          # switch the group's version within its system (paper → paper); services pick it up on restart
group delete lobby                 # stops and deletes every service of the group, then the group itself

service list
service info lobby-1
service create lobby               # provisions from the cached or catalog-downloaded build
service start lobby-1
service stop lobby-1
service restart lobby-1
service screen lobby-1             # attach to the live server console; type 'exit' to detach
service delete lobby-1             # stops, then permanently deletes its service directory
ser list                           # 'ser' is a shortcut for 'service'

exit
```

The wizard shows current catalog versions/builds and retries blank or invalid answers instead of abandoning the group.
It downloads and verifies the selected artifact only after all group settings are valid, then persists the group with
its exact build key. Use `local:<version-folder>` (or the `--version` plus an installed local key) to select a prepared
local template. For Spigot, prepare a BuildTools template locally. Service names use the lowest free positive suffix
(`lobby-1`, `lobby-2`, ...); a name is not reused while its service directory/record remains.

## Architecture

```text
api/       Public immutable domain models, group/service/template/catalog APIs, lifecycle events
common/    Safe YAML configuration and structured console logging
core/      Group/service managers, live server catalogs/downloads, adapters, ports, process lifecycle, reconciler
bridge/    Paper agent plugin (auto-installed into services) and the VibeCloudClient SDK
launcher/  Composition root and guided CLI
```

The default adapters support `VELOCITY`, `BUNGEECORD`, `PAPER`, and `SPIGOT`. Add an enum value plus a `ServerAdapter`,
and provide a `ServerCatalog` implementation to `CloudBootstrap`; group management, process supervision, and
reconciliation do not need to change. `TemplateManager`, `ServerCatalog`, `ProcessManager`, and `PortAllocator` are
interfaces so distribution metadata, artifact acquisition, and later remote-node implementations remain replaceable.

The controller is a single-node process manager, not a network control plane. Run it under a dedicated OS account, keep
templates and service directories writable only by that account, and back up service data before upgrades or deletion.

To use VibeCloud programmatically — embedding the controller, listing groups/services, start/stop from your own code,
or reacting to lifecycle events — see [docs/API.md](docs/API.md).
