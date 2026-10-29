# VibeCloud security model

This document describes what is trusted, how the Redis bridge authenticates, and how to deploy VibeCloud safely.
Anything that depends on your deployment is called out explicitly.

## Architecture overview

```
              VibeCloud controller (localhost)
                 │  ▲
      commands,  │  │  heartbeats, answers
      status     ▼  │
            ┌────────────┐
            │   Redis    │◀──── agent plugins inside the backend servers
            └────────────┘
                 ▲
                 │  status document (vibecloud:status)
             your plugins / scripts
```

- Redis is a **trusted component** of the network: it carries the controller's commands and every agent's roster.
- The default URL is `redis://127.0.0.1:6379`, and that is the recommended deployment: a loopback Redis that only
  local processes can reach. If you already run Redis for other plugins, give VibeCloud its own database
  (`redis://127.0.0.1:6379/3`) or namespace and protect it with `requirepass`.
- There is **no HTTP server and no dashboard**. Browsers are not part of the trust model.

## Backend exposure

Only the proxy is public. When a proxy is configured, the cloud writes `server-ip=127.0.0.1` and `online-mode=false`
into every Paper/Spigot service's `server.properties`, so backends listen on loopback and are reachable only by the
proxy on the same machine. The assigned port stays managed by the cloud — you do not pin the address in a template.

This closes two holes that an open backend port leaves:

- Players trying the backend port directly are no longer answered (Paper would reply with *"If you wish to use IP
  forwarding, please enable it in your BungeeCord config as well!"*); the connection is refused before Minecraft
  sees it.
- With BungeeCord-style (pre-1.13) forwarding, a backend trusts any connection that carries a crafted BungeeCord
  handshake — including the player's own name, UUID and IP. A remote client that can reach the port can therefore
  skip the proxy and its authentication. Loopback binding removes that path entirely.

Networks without a proxy are untouched: backends keep the bind address the operator set (`server-ip=` empty means all
interfaces), because players connect to them directly.

## Authentication

One shared secret, `bridge.token`, generated at first start next to `config.yml` (32 bytes of `SecureRandom`, file
mode `0600`, gitignored). It is written into every service's `plugins/VibeCloud/agent.properties` and embedded in
every bridge message:

- **Agent → controller** (`vibecloud:heartbeat`, `vibecloud:cloud` results): the controller validates the token
  before touching any state; wrong or missing tokens are ignored.
- **Controller/SDK → agents** (`vibecloud:actions`, `vibecloud:commands:<service>`): agents validate the token
  before executing anything; a forged message without the token does nothing.
- **Reading `vibecloud:status`** needs no token — anyone who can reach Redis can read the network state. Keep Redis
  on loopback (or password-protected) and treat read access as access to player names, addresses and server state.

The token is not a per-user account: every holder has full administrative power (start/stop services, run console
commands, kick/transfer players). Rotate it by deleting `bridge.token`, restarting the controller, and restarting
the backend services so they receive the new file.

## Authorization

There is **one** administrative credential, and it deliberately grants full control. There are no roles and no
per-user audit trail. Do not share it with people who should only read state — for read-only access, create a
separate Redis user (Redis 6+ ACLs) that is allowed `GET vibecloud:status` and nothing else.

## Command execution model (no shell)

- All server processes launch via `ProcessBuilder(list)` with structured arguments — no `sh -c`, no string
  concatenation into a shell anywhere in the codebase.
- Console/cloud commands are written to the **stdin** of the target Minecraft process, which is the intended
  administrative semantic — the Minecraft server parses them as commands, not as shell input.
- Server-side caps: ≤32 cloud args × 200 chars, console command ≤256 chars, player name ≤16, kick reason ≤200,
  permission node ≤128.
- Agents execute player actions on the Bukkit **main thread** only, and only for players who are online locally.

## Redis exposure

| Setup | Risk | Recommendation |
|---|---|---|
| `redis://127.0.0.1:6379` (default) | Local processes only | Recommended |
| `redis://:password@127.0.0.1:6379/2` | Local, password-protected | Good on shared machines |
| `rediss://host:port` | Remote Redis, TLS | Use only on a private network with ACLs |
| `redis://0.0.0.0:6379` without `requirepass` | Anyone can read player data and publish commands | Never |

VibeCloud never listens on a socket itself. If Redis is remote, put it behind a private network, enable TLS
(`rediss://`), and restrict which keys/clients can reach it. The status document is not secret in the sense of
credentials, but it contains player names and /24-style addresses reported by the agents.

Set Redis itself to reject remote access:

```conf
# /etc/redis/redis.conf
bind 127.0.0.1 -::1
protected-mode yes
requirepass <a long random password>
```

and give VibeCloud the password in `config.yml`: `redis: { url: "redis://:password@127.0.0.1:6379/0" }`.

## Logging & secrets

- Tokens are never logged; agent heartbeats are processed without their token in any log line.
- `bridge.token`, `forwarding.secret`, `config.yml` are gitignored.
- Player inspection data (world, coordinates, address) travels through Redis and is visible to anyone with read
  access to the instance.

## Failure behavior

- Redis unreachable at startup: the controller logs one clear error and keeps retrying; services still run, but no
  roster/命令 traffic flows and SDK reads fail until Redis returns.
- An agent whose heartbeat is older than `bridge.offline-timeout-seconds` is treated as offline and its roster is
  cleared.
- If the controller restarts, the in-memory command queue is empty — commands are delivered through Redis lists, so
  nothing that was queued is lost.

## Known limitations / residual risks

- **Single shared administrative credential.** No per-user accounts, no roles, no audit trail.
- **Redis is trusted.** A compromised Redis can issue arbitrary console commands to every server that runs the
  agent. Protect the instance as you would protect the controller's own process.
- **No encryption on loopback.** Plain `redis://` on `127.0.0.1` is unencrypted; use `rediss://` when Redis is not
  on the same host.
- **No rate limiting at the transport level.** A plugin with the token can publish as fast as Redis accepts; the
  controller and agents process messages in order.
