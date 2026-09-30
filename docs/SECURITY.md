# VibeCloud security model

This document describes how the bridge + dashboard authenticate, what is trusted, and how to
deploy VibeCloud for **remote browser access over HTTPS**. It reflects the implementation as of
0.5.2; anything that depends on your deployment is called out explicitly.

## Architecture overview

```
Team browser ──HTTPS/TLS──► Reverse proxy (Caddy / nginx / Traefik)
                                │ HTTP, private network / localhost
                                ▼
                     VibeCloud bridge (127.0.0.1:25580 by default)
                                ▲
                     Minecraft agents (Bearer token, LAN only)
```

- The bridge binds to `127.0.0.1` by default. Remote access = TLS reverse proxy in front.
- Binding `0.0.0.0` is supported (direct LAN access with bearer auth) but a reverse proxy is the
  recommended production topology; do not expose the bridge port to the Internet directly.

## Authentication

Two credential types, both resolved server-side before any processing (`BridgeHttpServer.authorize`):

1. **`Authorization: Bearer <token>`** — used by the Minecraft agents and API clients. The token is
   generated at first start into `bridge.token` (32 bytes of `SecureRandom`, file mode `0600`,
   never committed — see `.gitignore`).
2. **Dashboard session cookie** (`vibecloud_session`) — browsers log in at
   `POST /bridge/dashboard/login` by submitting the bridge token once; the server returns an
   `HttpOnly; SameSite=Strict` cookie (plus `Secure` when the request arrived over forwarded
   HTTPS). Sessions are random 256-bit ids, stored server-side **hashed (SHA-256)**, expire after
   12 hours, and are revoked by `POST /bridge/dashboard/logout` or a cloud restart. The raw token
   is never stored in the browser.

The session cookie is a convenience wrapper around the one shared bridge token — it is **not** a
per-user account. Everyone who can log in holds full administrative power (see below). Revoke
exposure by rotating `bridge.token` (delete the file, restart the cloud, redistribute to agents).

The old `?token=...` query-string fallback was **removed** (URLs leak into proxy/access logs).

### CSRF

Cookie-authenticated state-changing requests must send `X-Requested-With: XMLHttpRequest`;
cross-site form posts cannot set custom headers, so this blocks CSRF. Bearer-token clients are
CSRF-immune by construction (no ambient credential) and are not affected.

## Authorization

There is **one** administrative credential. It intentionally grants full control: cloud commands,
service start/stop/restart, console commands, player kick/transfer. There are no roles; do not
share the token with people who should only *see* the dashboard.

## Command execution model (no shell)

- All server processes launch via `ProcessBuilder(list)` with structured arguments — no `sh -c`,
  no string concatenation into a shell anywhere in the codebase.
- Console/cloud commands are written to the **stdin** of the target Minecraft process, which is
  the intended administrative semantic — the Minecraft server parses them as commands, not as
  shell input. There is no path for dashboard input to reach an OS shell.
- Server-side caps: ≤32 cloud args × 200 chars, console command ≤256 chars, player name ≤16,
  kick reason ≤200, request body ≤64 KiB, explicit action allowlists.

## Endpoint inventory & protections

| Endpoint | Auth | Extra protections |
|---|---|---|
| `GET /` | public | static shell, no data, CSP, no-store |
| `POST /bridge/dashboard/login` | token check | rate limited (10/min/IP), constant-time compare |
| `POST /bridge/dashboard/logout` | session | CSRF header required |
| `GET /bridge/status`, `/bridge/services` | bearer or session | — |
| `GET /bridge/metrics` | bearer or session | — |
| `GET /bridge/console?service=` | bearer or session | name length cap |
| `POST /bridge/heartbeat` | bearer (agents) | body cap, unknown-service 404 |
| `POST /bridge/players` | bearer or session | CSRF, rate limit, action allowlist, caps |
| `POST /bridge/services/command` | bearer or session | CSRF, rate limit, action allowlist, caps |
| `POST /bridge/cloud` | bearer or session | CSRF, rate limit, arg caps, validation before execution |

Every response carries: `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and a `Content-Security-Policy`
(`default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline';
img-src 'self' https://mc-heads.net; connect-src 'self'; frame-ancestors 'none';
base-uri 'none'; form-action 'self'`). `Strict-Transport-Security` is emitted only when the
request arrived with `X-Forwarded-Proto: https` (i.e. behind your TLS proxy).

Errors are generic (`400/401/403/404/429/500` JSON); unexpected exceptions are logged server-side
and answered with a body-less-detail 500 — no stack traces, paths, or configuration leak.
Unknown URL paths fall through to the public dashboard shell only.

## Trusted proxy / forwarded headers

The bridge reads `X-Forwarded-Proto` for two things only: emitting HSTS and the cookie `Secure`
flag. It never derives authorization from headers. Both features activate only when the header
says `https` — a client spoofing the header on plain HTTP gains nothing (it just gets HSTS/Secure
flags, which its browser ignores over http). Do not let untrusted clients reach the bridge with
spoofed `X-Forwarded-*` headers *and* terminate TLS for them; your proxy should overwrite these
headers (Caddy and nginx do by default).

## Rate limiting

In-memory, per client IP, sliding window: 10 failed auths (bearer or login) per 60s → further
logins/auth from that IP get `429`; state-changing command endpoints cap at 120/min/IP. Read-only
dashboard polling is never limited. Limits reset on restart.

## Logging & secrets

- Tokens are never logged. Bridge request logs contain no Authorization headers or query strings
  with tokens (query tokens are rejected outright).
- `bridge.token`, `forwarding.secret`, `config.yml` are gitignored.
- Your **reverse proxy's** access logs are outside VibeCloud's control: configure them to not log
  request bodies (they don't by default) — tokens only ever travel in headers or the login body.

## Deployment: HTTPS for remote access

Caddy (simplest, automatic certificates):

```
vibecloud.example.com {
    reverse_proxy 127.0.0.1:25580
}
```

nginx (manual certificates):

```nginx
server {
    listen 443 ssl;
    server_name vibecloud.example.com;
    ssl_certificate     /etc/letsencrypt/live/vibecloud.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/vibecloud.example.com/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:25580;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
}
```

Checklist:

1. Keep `bridge.bind-address: "127.0.0.1"` — the proxy talks to it over loopback.
2. Open only TCP 443 publicly; keep 25580 off the firewall (`ufw deny 25580` or your cloud
   provider's security group).
3. The dashboard is then reachable at `https://vibecloud.example.com/` for every team member;
   they each paste the shared token once at login (exchanged for a personal HttpOnly session).
4. If you must access without a proxy, prefer an SSH tunnel (`ssh -L 25580:127.0.0.1:25580 host`)
   rather than binding `0.0.0.0` over plain HTTP. If you do bind `0.0.0.0`, restrict 25580 to your
   LAN with a firewall and remember the connection is **not encrypted** — bearer tokens over
   plain HTTP across the Internet are trivially interceptable.

## Known limitations / residual risks

- **Single shared administrative credential.** No per-user accounts, no roles, no audit trail of
  who did what (only IP-level rate limiting). Treat the token as a team password; rotate it when
  a team member leaves.
- Sessions are in-memory: a cloud restart logs dashboard users out (agents are unaffected).
- CSP allows `img-src https://mc-heads.net` — the players page sends player names to that CDN to
  render avatar heads. Remove that `img-src` entry and self-host heads if this is unacceptable.
- No HTTP-level integration tests behind an actual TLS proxy; HSTS/`Secure` behavior is covered
  by unit tests with synthetic `X-Forwarded-Proto` headers only.
- Rate limits are per-IP and in-memory: distributed guessing or proxies that aggregate many users
  behind one IP can trip the limit for everyone (fail closed, 429, retry in a minute).
