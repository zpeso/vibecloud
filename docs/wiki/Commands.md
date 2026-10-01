[🏠 Home](Home.md) · [🚀 Installation](Installation.md) · **⌨️ Commands** · [🖥️ Dashboard](Dashboard.md) · [🧩 Bridge API](Bridge-API.md) · [📦 Versions](Versions.md)

# ⌨️ Commands

VibeCloud has two command surfaces with identical authority: the **interactive console** (where the controller runs)
and the in-game **`/cloud` command** (on every server running the agent). The cloud is always the authority — completions
and results are resolved server-side.

## Console — general

| Command | Description |
|---|---|
| `help` (or `?`) | Command overview with sub-commands |
| `clear` (or `cls`) | Clear the screen |
| `exit` (or `quit`) | Stop the cloud (gracefully stops all services) and quit |

While typing: **Tab** completes commands/subcommands/service/group names, **↑/↓** walks history, **Ctrl+C** clears the
input line, **Ctrl+L** clears the screen. The prompt is `◆ vibecloud ❯`.

## Console — cloud

| Command | Description |
|---|---|
| `cloud status` | Cloud state + per-group running/desired/provisioned counts |
| `cloud reload` | Re-read group definitions from `config.yml` |

## Console — groups

| Command | Description |
|---|---|
| `group list` | Table of all groups (type, version, desired/max, provisioned) |
| `group info <name>` | Details for one group incl. its instances and states |
| `group create [name]` | Guided wizard (see below) |
| `group start <name>` | Start another service of the group — reuses stopped/crashed records first |
| `group version <name> <version>` | Switch the group's version within its own system (paper → paper) |
| `group delete <name>` | Stop + delete **every service of the group**, then delete the group |

**`group create` wizard** — prompts for type, version, build, and service counts (min / max / always-running, static).
Type `exit`, `cancel`, `abort`, or `quit` at any prompt to cancel cleanly — nothing is downloaded or created. Or skip
the wizard entirely with flags:

```text
group create lobby --type PAPER --version 26.2 --build 129 --min-services 1 --max-services 3 --always-running-services 1
group create proxy --type VELOCITY --version 3.4.0 --build 6 --min-services 1 --max-services 1
group create event --version local:1.8.8      # use a prepared local template
```

**`group version`** — downloads + verifies the new build (or uses `local:<template>`), re-pins the group **and every
service record** in it, and resyncs the proxy. Services boot the new version on their next start/restart; the proxy's
forwarding mode follows automatically (e.g. 1.8 → 26.2 switches Velocity from legacy to modern forwarding).
Cross-system switches are rejected by design (paper → paper, velocity → velocity).

**`group delete`** runs asynchronously — the prompt returns immediately with a `[#1]` tag and the result appears when
done, so you can keep typing.

## Console — services

| Command | Description |
|---|---|
| `service list` (alias `ser list`) | Table of all services (group, state, type, version, port) |
| `service info <name>` | Details incl. directory, exit code, last error, next auto-retry |
| `service create <group>` | Provision a new service from the group's pinned build + overlay |
| `service start <name>` | Launch it (static services get template updates merged in automatically) |
| `service stop <name>` | Graceful stop |
| `service restart <name>` | Stop, then start |
| `service screen <name>` | Attach to the live server console; type `exit` to detach |
| `service delete <name>` | Stop, then permanently delete the record + directory, free the port |

`ser` is a full alias: `ser list`, `ser screen lobby-1`, and Tab completion all work.

Service names use the lowest free suffix: `lobby-1`, `lobby-2`, … A name is not reused while its record or directory
still exists.

## In-game: `/cloud`

Every backend server with the agent gets `/cloud` (permission `minetropia.cloud`, default: op). Responses are the
cloud's, relayed verbatim — so lifecycle actions work from anywhere in the network:

```text
/cloud info                      Overview: running/starting/crashed, players online
/cloud groups                    Group table
/cloud group start <name>        Start another service of a group
/cloud group version <n> <v>     Switch the group's version (same system only)
/cloud group delete <name>       Delete a group with all of its services
/cloud services                  Service table
/cloud service <name>            One service's details ('ser' works too)
/cloud players                   Player rosters per service
/cloud send <player> <target>    Transfer a player via the proxy (service or group#)
/cloud msg <player> <text>       Chat message to a player
/cloud cmd <service> <command>   Run a console command on a service
/cloud start|stop|restart|delete <service>   Lifecycle
```

Tab completion is resolved by the cloud: subcommands, live service names, and online players. Your own plugins can run
the same surface programmatically — see the [Bridge API tab](Bridge-API.md#run-cloud-commands-from-code).

## Desired-state behavior in one paragraph

`min-services`/`always-running-services` is a **floor**: the reconciler restarts crashed services (bounded backoff)
and reuses stopped ones to keep at least that many running — but it never stops the extras you started, so with
`min 1 / max 2` you can happily run two. `max-services` caps the provisioned records. See the
[Installation tab](Installation.md#config-reference-configyml) for the timing knobs.

---

**Next:** [🖥️ Dashboard](Dashboard.md) — the built-in web panel.
