[🏠 Home](Home.md) · [🚀 Installation](Installation.md) · [⌨️ Commands](Commands.md) · [🖥️ Dashboard](Dashboard.md) · [🧩 Bridge API](Bridge-API.md) · [📦 Versions](Versions.md) · **🧑 ServerMobs**

# 🧑 ServerMobs

**ServerMobs** is the NPC plugin shipped with VibeCloud. It spawns fake-player NPCs with real skins and holograms,
and runs configurable actions when a player clicks one — most commonly sending them to another backend server.

Unlike the agent, it is **not** injected automatically. ServerMobs ships as **standalone release assets** (next to
`vibecloud-<version>.zip`, not inside it), in two builds:

| Asset | For | Requires |
|---|---|---|
| `ServerMobs.jar` | Paper **1.21+** | Java 21 |
| `ServerMobs-1.8.jar` | Minecraft **1.8** (Spigot/Paper) | Java 8+ |

Both jars are self-contained: PacketEvents is shaded in, and the 1.8 build additionally bundles adventure, since a
1.8 server ships no Kyori. No other plugin is required.

## Install

1. Download the build matching your server from the release's **Assets**.
2. Copy it into a backend server's `plugins/` folder (rename it to `ServerMobs.jar` if you prefer):
   ```text
   services/lobby-1/plugins/ServerMobs.jar
   ```
3. Restart that service. On first start it writes `plugins/ServerMobs/config.yml`.
4. Point `data-directory` at the cloud home (see below), then create NPCs in-game with `/npc`.

**Every backend at once:** drop the jar into `templates/every_server/plugins/` instead. Every backend service
receives it on its next start (including re-provisioned non-static services).

## Persistence — why the cloud home

NPC definitions must survive restarts of **non-static** services, whose server directory is wiped and re-provisioned
on every start. ServerMobs therefore stores them outside the server folder, under the cloud home directory (the folder
that holds `bridge.token`):

```text
<data-directory>/servermobs/<npc>.yml
```

Each file records the NPC **name**, the **group** it was created in, its **location** (world + x/y/z + yaw/pitch),
its skin, its hologram lines, and its actions. A service of the same group respawns those NPCs on start.

The default `data-directory` is `../../`. A cloud-managed service runs with `services/<name>/` as its working
directory, so `../../` is the cloud home. Change it only if you run the server from a different layout.

## Configuration (`plugins/ServerMobs/config.yml`)

```yaml
data-directory: "../../"        # cloud home; NPCs go to <data-directory>/servermobs/
group: ""                       # leave empty to read group-name from plugins/VibeCloud/agent.properties

remove-from-tablist: true       # hide the NPC from the player list after it spawns
view-distance: 48               # range (blocks) at which an NPC is sent to a player

hologram:
  offset: 2.2                   # height of the top hologram line above the NPC's feet
  line-spacing: 0.3             # vertical gap between hologram lines

transfer-channel: "bungeecord:main"   # Velocity; use "BungeeCord" for BungeeCord/Waterfall
```

Reload definitions without restarting with `/npc reload`.

## Commands

Permission: `servermobs.admin` (default: op). NPC names allow letters, digits, `_` and `-` (up to 32 characters).

| Command | Description |
|---|---|
| `/npc create <name>` | Creates an NPC at your position, in this server's group |
| `/npc edit <name> skin <player\|url\|value[;signature]>` | Sets the skin (see below) |
| `/npc edit <name> hologram add <text>` | Adds a hologram line above the NPC |
| `/npc edit <name> hologram set <index> <text>` | Replaces one line |
| `/npc edit <name> hologram remove <index>` | Removes one line |
| `/npc edit <name> hologram clear` | Removes all lines |
| `/npc edit <name> action add <type> <value>` | Adds a click action (see below) |
| `/npc edit <name> action remove <index>` | Removes one action |
| `/npc edit <name> action clear` | Removes all actions |
| `/npc edit <name> move` | Moves the NPC to your position |
| `/npc edit <name> group <group>` | Reassigns the NPC to another group |
| `/npc remove <name>` | Deletes the NPC and its definition |
| `/npc list` | Lists the NPCs loaded on this server |
| `/npc info <name>` | Shows location, skin, holograms and actions |
| `/npc tp <name>` | Teleports you to the NPC |
| `/npc reload` | Re-reads the definitions from disk |

Tab completion covers subcommands, NPC names, settings and action types.

## Skins

```
/npc edit shopkeeper skin Notch                     # premium account, looked up from Mojang
/npc edit shopkeeper skin https://.../skin.png      # direct texture URL (unsigned)
/npc edit shopkeeper skin <base64value>;<signature> # raw texture value + signature
/npc edit shopkeeper skin <base64value>             # raw texture value (unsigned)
```

Name lookups run asynchronously and need outbound HTTPS to `api.mojang.com` / `sessionserver.mojang.com`.

## Actions

A click runs every action in order. Hologram text and `message` actions use
[MiniMessage](https://docs.advntr.dev/minimessage/format) formatting (e.g. `<yellow>`, `<gradient:#ff0000:#0000ff>`).

| Type | Aliases | Value | Effect |
|---|---|---|---|
| `transfer` | `send`, `connect` | server name (e.g. `citybuild-1`) | Sends the player to that backend through the proxy |
| `message` | `msg`, `say` | MiniMessage text | Sends the player a chat message |
| `console` | `cmd`, `command` | command (no leading `/`) | Runs the command as the console |
| `player` | `as-player`, `perform` | command (no leading `/`) | Runs the command as the clicking player |

Example — a "shop" NPC that teleports players to the citybuild server:

```text
/npc create shopkeeper
/npc edit shopkeeper skin Notch
/npc edit shopkeeper hologram add <yellow><bold>Shop
/npc edit shopkeeper hologram add <gray>Click to travel
/npc edit shopkeeper action add transfer citybuild-1
```

> **Transfers** use the proxy's BungeeCord plugin-message channel. Velocity answers on `bungeecord:main` (the
> default); BungeeCord/Waterfall answer on `BungeeCord`. Set `transfer-channel` to match your proxy. The target must
> be a service (or proxy-known server) name — the proxy does not resolve `<group>#` targets on this path.

## Data on disk

| Path | Contents |
|---|---|
| `<data-directory>/servermobs/<npc>.yml` | One NPC definition (name, group, location, skin, holograms, actions) |

## 1.8 vs modern

The modern build uses the server's adventure API (Paper provides it) and `TextDisplay` holograms. The 1.8 build
bundles its own adventure and falls back to invisible `ArmorStand` holograms, because 1.8 has neither. Behaviour,
commands, persistence and the MiniMessage formatting are identical; only the shading and the hologram entity differ.

The files are plain YAML and safe to edit or copy between machines; run `/npc reload` afterwards.

---

**Back to:** [🏠 Home](Home.md)
