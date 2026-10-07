[🏠 Home](Home.md) · [🚀 Installation](Installation.md) · [⌨️ Commands](Commands.md) · [🖥️ Dashboard](Dashboard.md) · [🧩 Bridge API](Bridge-API.md) · **📦 Versions** · [🧑 ServerMobs](ServerMobs.md)

# 📦 Versions & Compatibility

## Controller requirements

| Component | Version |
|---|---|
| Java (controller JVM) | **25** |
| Language / build | Kotlin 2.4.20, Gradle 9.8.0 |
| Agent plugin target | Paper servers, Java 21+ |
| Supported OS | Linux (recommended), Windows, macOS |

## Supported server software

| Software | Source | Notes |
|---|---|---|
| **Paper** | PaperMC Fill v3 catalog | All versions, exact builds, SHA-256 + size verified before install |
| **Velocity** | PaperMC Fill v3 catalog | Recommended proxy; full automatic wiring (backend table, secret, forwarding mode) |
| **BungeeCord** | SpigotMC Jenkins API | Upstream publishes no SHA-256, so a local checksum is recorded after download |
| **Waterfall** | PaperMC (EOL) | Selectable but end-of-life — the wizard labels it; prefer Velocity |
| **Spigot** | BuildTools (local) | Prepare `templates/spigot/<version>/server.jar` yourself; no ready-made download exists |

Every downloaded artifact is cached under `templates/<type>/<pinned-build-key>/server.jar` with a
`.server-build.properties` metadata file (origin URL, checksum, size, channel, release date, Java requirement).
Groups sharing the same pinned build share the cache.

## Java per Minecraft version

The controller picks launch behavior per server version automatically — you only configure the binaries:

| Minecraft version | Java needed | Set via |
|---|---|---|
| 26.x (current) | 25 | `runtime.java-command` |
| 1.17 – 1.21.x | 17 – 21 | `runtime.java-command` |
| pre-1.17 (legacy) | 8 – 16 | `runtime.legacy-java-command` (falls back to `java-command` when unset) |

- Pre-1.14 builds do not understand `--nogui`; the cloud launches them with `--nojline` automatically.
- Run the controller itself on Java 25 regardless — legacy services get the configured legacy binary instead.

## Forwarding modes (Velocity)

Velocity's forwarding mode is proxy-global, and VibeCloud keeps it correct automatically:

| Network composition | Mode applied |
|---|---|
| All backends ≥ 1.13 | `modern` |
| Any backend pre-1.13 | `legacy` (BungeeCord-style — understood by every version) |

The mode is recomputed and rewritten into every proxy's `velocity.toml` whenever services change — including when you
switch a group's version (1.8 → 26.2 flips the proxy to modern; adding a 1.8 group back flips it to legacy). The
shared forwarding secret and `online-mode` are managed the same way.

## Version switching

`group version <name> <version>` (console or `/cloud`) changes a group's version **within its own system** —
paper → paper, velocity → velocity; cross-system switches are rejected.

What happens:

1. The requested version is resolved against the live catalog (or `local:<template>` for prepared templates).
2. The exact build is downloaded + checksum-verified into the shared template cache (skipped when cached).
3. The group **and every one of its service records** are re-pinned to the pinned build key (e.g. `paper-26.2-129`).
4. The proxy backend table/forwarding mode is resynced immediately.

Running services keep their current bits until restarted — the command output lists exactly which running services
need a restart. Static services merge the new artifact on their next start (worlds and plugin data survive);
non-static services re-provision from the new build anyway.

Version selection accepts a catalog version id/display name (`26.2`, `Paper 26.2`), an exact pinned build key, or
`local:<template-folder>`.

---

**Back to:** [🏠 Home](Home.md)
