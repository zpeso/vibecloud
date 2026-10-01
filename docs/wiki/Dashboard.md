[🏠 Home](Home.md) · [🚀 Installation](Installation.md) · [⌨️ Commands](Commands.md) · **🖥️ Dashboard** · [🧩 Bridge API](Bridge-API.md) · [📦 Versions](Versions.md)

# 🖥️ Dashboard

The bridge serves a complete web dashboard at `http://<cloud-host>:<bridge-port>/` (default `http://127.0.0.1:25580/`).
No extra service and no build step — the page ships inside the controller.

## Sign in

Paste the contents of `bridge.token` (next to `config.yml`). The token is exchanged for an **HttpOnly session cookie**
(12 hours) — the token itself never touches JavaScript or browser storage. State-changing requests carry a CSRF header
automatically, and login attempts are rate-limited. `Sign out` revokes the session.

Without the token the dashboard shows the empty login page only — no data leaks.

## Pages

| Page | What you get |
|---|---|
| **Overview** | Stat cards (running/starting/crashed, players online), network activity chart, group distribution bars, per-group capacity cards, crash alert banner |
| **Players** | Every online player with skin head, current server, one-click **transfer** (dropdown) and **kick** |
| **Services** | State tabs with counts (ALL/RUNNING/STARTING/STOPPED/CRASHED), CPU/TPS/memory meters per service, start/restart/stop buttons |
| **Groups** | Per-group cards with capacity bars, backend/proxy tabs, `Start another`, delete with all services |
| **Console** | Live per-service console output (auto-refresh) with a command input |
| **Host & Health** | Your root server: whole-host CPU chart, cloud JVM CPU, system memory & swap, uptime, load average, OS/Java/cloud versions, per-process CPU of every service |
| **Activity** | Recent service lifecycle events (created/started/stopped/crashed/deleted), newest first |

Charts have range tabs (15m/30m/1h) and hovering shows a floating tooltip card.

## Actions

- Start / restart / stop any service from the Services page.
- Start another service of a group; delete a group (cascades to its services).
- Transfer and kick players from the Players page.
- The **command bar** at the top runs the full cloud command surface with Tab completion — the same authority as the
  console: `restart citybuild-1`, `group start lobby`, `send Notch lobby-1`, …

## Remote access (recommended setup)

The bridge binds to `127.0.0.1` by default. To reach it from the outside, keep that binding and put a TLS reverse
proxy in front — Caddy example:

```caddy
dash.example.com {
    reverse_proxy 127.0.0.1:25580
}
```

The dashboard detects `X-Forwarded-Proto: https` and automatically enables HSTS and the cookie `Secure` flag.
Full hardening checklist: [docs/SECURITY.md](../SECURITY.md).

## Security model in short

- Token or session for every data endpoint; constant-time comparisons; no query-token fallback.
- Strict Content-Security-Policy: scripts are same-origin only, no inline scripts (the release build even
  minifies/mangles the dashboard script).
- Rate limiting (10 failed logins/min/IP, 120 commands/min/IP), generic error bodies, no secrets in logs.
- Read-only polling is never rate-limited, so the dashboard stays live.

---

**Next:** [🧩 Bridge API](Bridge-API.md) — use the cloud from your own code.
