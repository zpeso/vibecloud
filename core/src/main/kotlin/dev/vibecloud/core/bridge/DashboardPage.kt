package dev.vibecloud.core.bridge

/**
 * The built-in dashboard: a single, dependency-free HTML single-page app served by the bridge at
 * `/`. It authenticates with the same bearer token as every other bridge endpoint (stored in the
 * browser's localStorage after login) and renders live cloud state plus metric charts across
 * several pages: Overview, Players, Services, Groups and Console.
 *
 * No web framework and no build step on purpose: the controller ships as one zip, and the page
 * must work offline behind a plain `com.sun.net.httpserver` server.
 */
internal object DashboardPage {
    val html: String = """<!DOCTYPE html>
<html lang="en" class="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VibeCloud Dashboard</title>
<style>
  :root {
    --background: #09090b;
    --foreground: #fafafa;
    --card: #0c0c0f;
    --card-foreground: #fafafa;
    --muted: #27272a;
    --muted-foreground: #a1a1aa;
    --border: #232328;
    --primary: #fafafa;
    --primary-foreground: #18181b;
    --accent: #22d3ee;
    --green: #34d399;
    --yellow: #fbbf24;
    --red: #f87171;
    --radius: 12px;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    background: var(--background);
    color: var(--foreground);
    font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 14px;
    line-height: 1.5;
  }
  .hidden { display: none !important; }
  button { font-family: inherit; }

  /* ---- login ---- */
  #login { height: 100%; display: flex; align-items: center; justify-content: center; }
  .login-card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 32px;
    width: 360px;
    box-shadow: 0 10px 30px rgba(0,0,0,.5);
  }
  .login-card h1 { font-size: 18px; font-weight: 600; letter-spacing: -.01em; }
  .login-card p.sub { color: var(--muted-foreground); margin: 6px 0 20px; }
  .login-card label { display: block; font-size: 12px; color: var(--muted-foreground); margin-bottom: 6px; }
  .login-card input {
    width: 100%;
    background: #18181b;
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--foreground);
    padding: 10px 12px;
    font-size: 14px;
    outline: none;
  }
  .login-card input:focus { border-color: #52525b; }
  .login-error { color: var(--red); font-size: 12px; margin-top: 10px; min-height: 16px; }

  /* ---- buttons & inputs ---- */
  .btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    border: 1px solid var(--border);
    background: var(--card);
    color: var(--foreground);
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 13px;
    cursor: pointer;
  }
  .btn:hover { background: #18181b; }
  .btn.primary { background: var(--primary); color: var(--primary-foreground); border-color: var(--primary); font-weight: 500; }
  .btn.primary:hover { background: #e4e4e7; }
  .btn.danger { color: var(--red); border-color: #7f1d1d; }
  .btn.danger:hover { background: #1c0f0f; }
  .btn.small { padding: 3px 8px; font-size: 12px; border-radius: 6px; }
  input[type="text"], input[type="password"], select {
    background: #18181b;
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--foreground);
    padding: 7px 10px;
    font-size: 13px;
    outline: none;
    font-family: inherit;
  }
  input:focus, select:focus { border-color: #52525b; }

  /* ---- app layout ---- */
  #app { display: flex; min-height: 100%; }
  nav.sidebar {
    width: 210px;
    flex-shrink: 0;
    border-right: 1px solid var(--border);
    padding: 18px 12px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    position: sticky;
    top: 0;
    height: 100vh;
  }
  .brand { display: flex; align-items: center; gap: 10px; font-weight: 600; letter-spacing: -.01em; padding: 0 10px 16px; }
  .brand .dot { width: 9px; height: 9px; border-radius: 50%; background: var(--green); box-shadow: 0 0 8px var(--green); }
  .nav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border-radius: 8px;
    color: var(--muted-foreground);
    cursor: pointer;
    font-size: 13.5px;
    border: 1px solid transparent;
    background: none;
    text-align: left;
    width: 100%;
  }
  .nav-item:hover { color: var(--foreground); background: #141417; }
  .nav-item.active { color: var(--foreground); background: #18181b; border-color: var(--border); }
  .nav-item .icon { width: 16px; text-align: center; opacity: .85; }
  .nav-item .count {
    margin-left: auto;
    font-size: 11px;
    color: var(--muted-foreground);
    background: #1d1d21;
    border-radius: 999px;
    padding: 0 7px;
    font-variant-numeric: tabular-nums;
  }
  .sidebar .foot { margin-top: auto; padding: 10px; font-size: 11px; color: var(--muted-foreground); }
  .sidebar .foot a { color: var(--muted-foreground); }

  .content { flex: 1; display: flex; flex-direction: column; min-width: 0; }
  header.topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 12px 24px;
    border-bottom: 1px solid var(--border);
    position: sticky;
    top: 0;
    background: rgba(9,9,11,.92);
    backdrop-filter: blur(6px);
    z-index: 5;
  }
  .topbar .page-title { font-weight: 600; font-size: 15px; letter-spacing: -.01em; }
  .topbar .meta { display: flex; align-items: center; gap: 10px; }
  .quick-cmd { display: flex; gap: 8px; flex: 1; max-width: 560px; }
  .quick-cmd input { flex: 1; }
  .updated { color: var(--muted-foreground); font-size: 12px; white-space: nowrap; }

  main.page {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding: 20px 24px 36px;
    max-width: 1500px;
    width: 100%;
    margin: 0 auto;
    flex: 1;
  }

  /* ---- cards ---- */
  .card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 18px;
  }
  .card h2 {
    font-size: 12px;
    font-weight: 500;
    color: var(--muted-foreground);
    text-transform: uppercase;
    letter-spacing: .07em;
    margin-bottom: 12px;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
  .card-head h2 { margin: 0; }
  .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 16px; }
  .stat .value { font-size: 26px; font-weight: 600; letter-spacing: -.02em; margin: 2px 0; font-variant-numeric: tabular-nums; }
  .stat .hint { font-size: 12px; color: var(--muted-foreground); }
  .trend { font-size: 12px; margin-left: 8px; }
  .trend.up { color: var(--green); }
  .trend.down { color: var(--red); }

  .charts { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 16px; }
  .chart-wrap { position: relative; }
  canvas { width: 100%; height: 190px; display: block; cursor: crosshair; }

  .range-tabs { display: inline-flex; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
  .range-tabs button {
    background: none; border: none; color: var(--muted-foreground);
    font-size: 11.5px; padding: 4px 10px; cursor: pointer;
  }
  .range-tabs button + button { border-left: 1px solid var(--border); }
  .range-tabs button.active { background: #1d1d21; color: var(--foreground); }

  /* ---- tabs ---- */
  .tabs { display: inline-flex; flex-wrap: wrap; gap: 6px; margin-bottom: 14px; }
  .tab {
    border: 1px solid var(--border);
    background: var(--card);
    color: var(--muted-foreground);
    border-radius: 999px;
    padding: 4px 12px;
    font-size: 12.5px;
    cursor: pointer;
  }
  .tab:hover { color: var(--foreground); }
  .tab.active { background: var(--primary); color: var(--primary-foreground); border-color: var(--primary); font-weight: 500; }
  .tab .n { opacity: .75; margin-left: 5px; font-variant-numeric: tabular-nums; }

  /* ---- tables ---- */
  .table-wrap { overflow-x: auto; }
  table { width: 100%; border-collapse: collapse; }
  th {
    text-align: left;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: .06em;
    color: var(--muted-foreground);
    padding: 8px 10px;
    border-bottom: 1px solid var(--border);
    white-space: nowrap;
  }
  td { padding: 9px 10px; border-bottom: 1px solid #1b1b1f; font-variant-numeric: tabular-nums; white-space: nowrap; }
  tr:last-child td { border-bottom: none; }
  tr.clickable { cursor: pointer; }
  tr.clickable:hover td { background: #121216; }
  .empty { color: var(--muted-foreground); padding: 14px 10px; }

  .badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    border-radius: 999px;
    padding: 2px 10px;
    font-size: 11px;
    font-weight: 500;
    border: 1px solid var(--border);
    color: var(--muted-foreground);
  }
  .badge .pip { width: 6px; height: 6px; border-radius: 50%; background: var(--muted-foreground); }
  .badge.RUNNING { color: var(--green); border-color: #14532d; }
  .badge.RUNNING .pip { background: var(--green); }
  .badge.STARTING, .badge.STOPPING { color: var(--yellow); border-color: #713f12; }
  .badge.STARTING .pip, .badge.STOPPING .pip { background: var(--yellow); }
  .badge.CRASHED { color: var(--red); border-color: #7f1d1d; }
  .badge.CRASHED .pip { background: var(--red); }
  .badge.STOPPED, .badge.CREATED { color: var(--muted-foreground); }

  .meter { width: 86px; height: 6px; border-radius: 999px; background: var(--muted); overflow: hidden; display: inline-block; vertical-align: middle; }
  .meter > span { display: block; height: 100%; border-radius: 999px; background: var(--accent); }
  .meter > span.warn { background: var(--yellow); }
  .meter > span.bad { background: var(--red); }
  .num { font-variant-numeric: tabular-nums; }
  .dim { color: var(--muted-foreground); }

  /* ---- players ---- */
  .player-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
  .player {
    display: flex; align-items: center; gap: 12px;
    background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 12px;
  }
  .player img.head {
    width: 36px; height: 36px; border-radius: 8px; image-rendering: pixelated;
    background: #1d1d21; border: 1px solid var(--border);
    image-rendering: pixelated;
  }
  .player .fallback-head {
    width: 36px; height: 36px; border-radius: 8px; background: #1d1d21; border: 1px solid var(--border);
    display: flex; align-items: center; justify-content: center; font-weight: 600; color: var(--accent);
  }
  .player .who { min-width: 0; flex: 1; }
  .player .name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; }
  .player .on { font-size: 12px; color: var(--muted-foreground); }
  .player select { max-width: 130px; font-size: 12px; padding: 5px 6px; }

  /* ---- console ---- */
  .console-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
  .console-head h2 { margin: 0; }
  .console-head .grow { flex: 1; }
  .console {
    background: #0a0a0c;
    border: 1px solid var(--border);
    border-radius: 8px;
    height: 340px;
    overflow-y: auto;
    padding: 10px 12px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
    color: #d4d4d8;
  }
  .console-input { display: flex; gap: 8px; margin-top: 10px; }
  .console-input input { flex: 1; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }

  .toast {
    position: fixed; bottom: 20px; right: 20px; max-width: 440px; white-space: pre-wrap;
    background: #18181b; border: 1px solid var(--border); border-radius: 10px; padding: 12px 16px;
    font-size: 13px; box-shadow: 0 10px 30px rgba(0,0,0,.5); z-index: 50;
  }
  .tooltip {
    position: absolute; pointer-events: none; display: none; z-index: 10;
    background: #18181b; border: 1px solid var(--border); border-radius: 8px;
    padding: 6px 10px; font-size: 12px; box-shadow: 0 6px 20px rgba(0,0,0,.45);
    min-width: 90px;
  }
  .tooltip .t-label { color: var(--muted-foreground); font-size: 11px; margin-bottom: 3px; }
  .tooltip .row { display: flex; align-items: center; gap: 6px; }
  .tooltip .swatch { width: 8px; height: 8px; border-radius: 2px; }

  @media (max-width: 900px) {
    #app { flex-direction: column; }
    nav.sidebar {
      width: 100%; height: auto; position: static; flex-direction: row; overflow-x: auto;
      border-right: none; border-bottom: 1px solid var(--border); padding: 10px; align-items: center;
    }
    .brand { padding: 0 10px 0 4px; }
    .sidebar .foot { display: none; }
    .nav-item { width: auto; white-space: nowrap; }
    .nav-item .count { display: none; }
  }
</style>
</head>
<body>

<div id="login">
  <form class="login-card" id="login-form">
    <h1>VibeCloud</h1>
    <p class="sub">Sign in with the bridge token (bridge.token on the cloud host). It is exchanged
    for an HttpOnly session cookie and never stored in the browser.</p>
    <label for="token">Access token</label>
    <input type="password" id="token" autocomplete="off" autofocus>
    <div class="login-error" id="login-error"></div>
    <button class="btn primary" type="submit" style="width:100%;justify-content:center;margin-top:8px;">Sign in</button>
  </form>
</div>

<div id="app" class="hidden">
  <nav class="sidebar">
    <div class="brand"><span class="dot"></span> VibeCloud</div>
    <button class="nav-item" data-page="overview"><span class="icon">◎</span> Overview</button>
    <button class="nav-item" data-page="players"><span class="icon">☺</span> Players <span class="count" id="nav-players">0</span></button>
    <button class="nav-item" data-page="services"><span class="icon">▦</span> Services <span class="count" id="nav-services">0</span></button>
    <button class="nav-item" data-page="groups"><span class="icon">❏</span> Groups <span class="count" id="nav-groups">0</span></button>
    <button class="nav-item" data-page="console"><span class="icon">⌘</span> Console</button>
    <div class="foot"><a href="/bridge/status" target="_blank">raw status JSON</a></div>
  </nav>

  <div class="content">
    <header class="topbar">
      <div class="page-title" id="page-title">Overview</div>
      <div class="quick-cmd">
        <input type="text" id="quick-command" placeholder="Cloud command — try 'group start lobby', Tab completes">
        <button class="btn primary" id="quick-run">Run</button>
      </div>
      <div class="meta">
        <span class="updated" id="updated-at"></span>
        <button class="btn small" id="logout">Sign out</button>
      </div>
    </header>

    <main class="page">
      <!-- ============ OVERVIEW ============ -->
      <section id="page-overview" class="page-section" style="display:flex;flex-direction:column;gap:16px;">
        <div class="stat-grid" id="stats"></div>
        <div class="charts" id="charts"></div>
      </section>

      <!-- ============ PLAYERS ============ -->
      <section id="page-players" class="page-section hidden" style="display:flex;flex-direction:column;gap:16px;">
        <div class="card">
          <div class="card-head">
            <h2>Players online <span id="players-count" class="dim" style="text-transform:none;"></span></h2>
            <input type="text" id="player-filter" placeholder="Filter by name or server…" style="width:220px;">
          </div>
          <div class="player-grid" id="player-grid"></div>
        </div>
      </section>

      <!-- ============ SERVICES ============ -->
      <section id="page-services" class="page-section hidden" style="display:flex;flex-direction:column;gap:16px;">
        <div class="card">
          <h2>Services</h2>
          <div class="tabs" id="service-tabs"></div>
          <div class="table-wrap">
            <table>
              <thead><tr>
                <th>Name</th><th>Group</th><th>State</th><th>Type</th><th>Port</th>
                <th>TPS</th><th>Memory</th><th>Players</th><th style="text-align:right;">Actions</th>
              </tr></thead>
              <tbody id="services-body"></tbody>
            </table>
          </div>
        </div>
      </section>

      <!-- ============ GROUPS ============ -->
      <section id="page-groups" class="page-section hidden" style="display:flex;flex-direction:column;gap:16px;">
        <div class="card">
          <h2>Groups</h2>
          <div class="tabs" id="group-tabs"></div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>Name</th><th>Type</th><th>Version</th><th>Running</th><th>Desired</th><th>Provisioned</th><th>Max</th><th style="text-align:right;">Actions</th></tr></thead>
              <tbody id="groups-body"></tbody>
            </table>
          </div>
        </div>
      </section>

      <!-- ============ CONSOLE ============ -->
      <section id="page-console" class="page-section hidden" style="display:flex;flex-direction:column;gap:16px;">
        <div class="card">
          <div class="console-head">
            <h2>Console</h2>
            <select id="console-service"></select>
            <div class="grow"></div>
            <label class="dim" style="font-size:12px;display:flex;align-items:center;gap:6px;">
              <input type="checkbox" id="console-follow" checked> auto-refresh
            </label>
            <button class="btn small" id="console-refresh">Refresh</button>
          </div>
          <div class="console" id="console-output"></div>
          <div class="console-input">
            <input type="text" id="console-command" placeholder="Console command for the selected service">
            <button class="btn" id="console-send">Send</button>
          </div>
        </div>
      </section>
    </main>
  </div>
</div>

<script>
(function () {
  "use strict";

  var CHART_COLORS = { players: "#22d3ee", tps: "#34d399", ram: "#fbbf24", services: "#a78bfa" };
  var root = document.getElementById("app");
  var login = document.getElementById("login");
  var lastStatus = null;
  var lastMetrics = null;
  var page = "overview";
  var serviceTab = "ALL";
  var groupTab = "ALL";
  var chartRanges = { players: 1800, tps: 1800, ram: 1800, services: 1800 }; // seconds
  var hover = {};           // chartId -> hovered index or null
  var selectedService = null;
  var chartsBuilt = false;

  function byId(id) { return document.getElementById(id); }
  function esc(text) {
    var div = document.createElement("div");
    div.textContent = text === null || text === undefined ? "" : String(text);
    return div.innerHTML;
  }

  // ---- HTTP helpers -------------------------------------------------------
  // Authentication uses an HttpOnly session cookie (POST /bridge/dashboard/login exchanges the
  // bridge token for it). Cookie-authenticated state-changing requests must carry the
  // X-Requested-With header — the server rejects them otherwise (CSRF guard).
  function api(path, options) {
    options = options || {};
    if (options.method && options.method !== "GET") {
      options.headers = Object.assign({}, options.headers, { "X-Requested-With": "XMLHttpRequest" });
    }
    if (options.body && typeof options.body === "string") {
      options.headers["Content-Type"] = options.headers["Content-Type"] || "application/x-www-form-urlencoded";
    }
    options.credentials = "same-origin";
    return fetch(path, options).then(function (response) {
      if (response.status === 401) { showLogin(); throw new Error("unauthorized"); }
      if (!response.ok) { return response.text().then(function (body) { throw new Error(body || (response.status + " " + response.statusText)); }); }
      return response.text().then(function (body) { return body ? JSON.parse(body) : null; });
    });
  }
  function form(fields) {
    var parts = [];
    Object.keys(fields).forEach(function (key) {
      (fields[key] || []).forEach(function (value) { parts.push(encodeURIComponent(key) + "=" + encodeURIComponent(value)); });
    });
    return parts.join("&");
  }

  // ---- auth ---------------------------------------------------------------
  function showLogin() {
    login.classList.remove("hidden");
    root.classList.add("hidden");
  }
  function showApp() {
    login.classList.add("hidden");
    root.classList.remove("hidden");
    refresh();
  }
  byId("login-form").addEventListener("submit", function (event) {
    event.preventDefault();
    var submitted = byId("token").value;
    byId("token").value = "";
    byId("login-error").textContent = "";
    fetch("/bridge/dashboard/login", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest" },
      body: form({ token: [submitted] }),
      credentials: "same-origin",
    }).then(function (response) {
      if (response.ok) { submitted = null; showApp(); return; }
      if (response.status === 401) throw new Error("Invalid token.");
      if (response.status === 429) throw new Error("Too many attempts — wait a minute and retry.");
      throw new Error("Sign-in failed (" + response.status + ").");
    }).catch(function (error) {
      byId("login-error").textContent = error.message;
    });
  });
  byId("logout").addEventListener("click", function () {
    fetch("/bridge/dashboard/logout", { method: "POST", headers: { "X-Requested-With": "XMLHttpRequest" }, credentials: "same-origin" })
      .catch(function () {})
      .then(showLogin);
  });

  // ---- data ---------------------------------------------------------------
  function refresh() {
    Promise.all([
      api("/bridge/status"),
      api("/bridge/metrics").catch(function () { return null; }),
    ]).then(function (results) {
      lastStatus = results[0];
      lastMetrics = results[1];
      renderAll();
    }).catch(function () {});
  }

  function services() { return (lastStatus && lastStatus.services) || []; }
  function groups() { return (lastStatus && lastStatus.groups) || []; }
  function metaOf(name) {
    var list = (lastMetrics && lastMetrics.services) || [];
    for (var i = 0; i < list.length; i++) if (list[i].name === name) return list[i];
    return null;
  }
  function fmtTps(tps) { return tps === null || tps === undefined ? "—" : (Math.round(tps * 10) / 10).toFixed(1); }
  function tpsClass(tps) { return tps === null || tps === undefined ? "" : (tps < 15 ? "bad" : (tps < 19 ? "warn" : "")); }
  function fmtRam(ratio) { return ratio === null || ratio === undefined ? "—" : Math.round(ratio * 100) + "%"; }
  function ramClass(ratio) { return ratio === null || ratio === undefined ? "" : (ratio >= 0.9 ? "bad" : (ratio >= 0.75 ? "warn" : "")); }

  // ---- routing ------------------------------------------------------------
  var PAGE_TITLES = { overview: "Overview", players: "Players", services: "Services", groups: "Groups", console: "Console" };
  function navigate(target) {
    page = PAGE_TITLES[target] ? target : "overview";
    try { location.hash = page; } catch (error) {}
    Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (item) {
      item.classList.toggle("active", item.getAttribute("data-page") === page);
    });
    Array.prototype.forEach.call(document.querySelectorAll(".page-section"), function (section) {
      section.classList.toggle("hidden", section.id !== "page-" + page);
    });
    byId("page-title").textContent = PAGE_TITLES[page];
    if (page === "console") loadConsole();
    if (page === "overview") renderCharts();
  }
  Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (item) {
    item.addEventListener("click", function () { navigate(item.getAttribute("data-page")); });
  });
  window.addEventListener("hashchange", function () { navigate((location.hash || "#overview").slice(1)); });

  // ---- rendering ----------------------------------------------------------
  function renderAll() {
    byId("updated-at").textContent = "updated " + new Date().toLocaleTimeString();
    var totals = lastStatus.totals || {};
    byId("nav-players").textContent = totals["players-online"] || 0;
    byId("nav-services").textContent = services().length;
    byId("nav-groups").textContent = groups().length;
    renderStats(totals);
    buildCharts();
    renderCharts();
    renderServices();
    renderGroups();
    renderPlayers();
    renderConsoleTarget();
  }

  function renderStats(totals) {
    var list = services();
    var reporting = 0, ramSum = 0, tpsSum = 0;
    list.forEach(function (service) {
      var meta = metaOf(service.name);
      if (meta && meta.tps !== null && meta.tps !== undefined) {
        reporting++;
        ramSum += (meta.heap_used_mb || 0);
        tpsSum += meta.tps;
      }
    });
    var html = "";
    html += statCard("Players online", totals["players-online"] || 0, "across all services", deltaPlayers());
    html += statCard("Services running", (totals.online || 0) + " / " + (totals.services || 0), "provisioned · " + (totals["agents-online"] || 0) + " agents online");
    html += statCard("Groups", totals.groups || 0, "configured groups");
    html += statCard("Avg. backend TPS", reporting ? (tpsSum / reporting).toFixed(1) : "—", reporting + " backend(s) reporting");
    html += statCard("Backend memory", reporting ? Math.round(ramSum) + " MB" : "—", "heap used, all backends");
    byId("stats").innerHTML = html;

    function deltaPlayers() {
      var points = historyPoints();
      if (points.length < 2) return null;
      var diff = points[points.length - 1].players - points[points.length - 2].players;
      return diff === 0 ? null : diff;
    }
  }
  function statCard(label, value, hint, delta) {
    var trend = "";
    if (delta !== null && delta !== undefined) {
      trend = '<span class="trend ' + (delta > 0 ? "up" : "down") + '">' + (delta > 0 ? "▲ +" : "▼ ") + delta + '</span>';
    }
    return '<div class="card stat"><h2 style="margin-bottom:4px;">' + esc(label) + '</h2>' +
      '<div class="value">' + esc(value) + trend + '</div>' +
      '<div class="hint">' + esc(hint) + '</div></div>';
  }

  // ---- players page -------------------------------------------------------
  function renderPlayers() {
    var filter = (byId("player-filter").value || "").trim().toLowerCase();
    var running = {};
    services().forEach(function (service) {
      if (service.state === "RUNNING" && service.players) running[service.name] = service.players;
    });
    var rows = [];
    Object.keys(running).sort().forEach(function (serviceName) {
      running[serviceName].forEach(function (playerName) {
        rows.push({ name: playerName, server: serviceName });
      });
    });
    rows.sort(function (a, b) { return a.name.toLowerCase().localeCompare(b.name.toLowerCase()); });
    var visible = rows.filter(function (row) {
      return !filter || row.name.toLowerCase().indexOf(filter) >= 0 || row.server.toLowerCase().indexOf(filter) >= 0;
    });
    byId("players-count").textContent = visible.length === rows.length
      ? "· " + rows.length : "· " + visible.length + " of " + rows.length;

    var options = Object.keys(running).sort().map(function (name) {
      return '<option value="' + esc(name) + '">' + esc(name) + '</option>';
    }).join("");

    byId("player-grid").innerHTML = visible.length ? visible.map(function (row) {
      var initial = esc(row.name.charAt(0).toUpperCase());
      // No inline onerror (CSP): failed avatar loads are handled after insertion.
      var head = '<img class="head" alt="" loading="lazy" src="https://mc-heads.net/avatar/' + encodeURIComponent(row.name) + '/36">' +
        '<div class="fallback-head" style="display:none;">' + initial + '</div>';
      return '<div class="player">' + head +
        '<div class="who"><div class="name">' + esc(row.name) + '</div>' +
        '<div class="on">on ' + esc(row.server) + '</div></div>' +
        '<select data-player="' + esc(row.name) + '" data-from="' + esc(row.server) + '">' +
        '<option value="">Move to…</option>' + options + '</select>' +
        '<button class="btn small danger" data-kick="' + esc(row.name) + '">Kick</button>' +
        '</div>';
    }).join("") : '<div class="empty">No players online.</div>';

    Array.prototype.forEach.call(byId("player-grid").querySelectorAll("select"), function (select) {
      select.addEventListener("change", function () {
        var target = select.value;
        var player = select.getAttribute("data-player");
        if (!target) return;
        api("/bridge/players", { method: "POST", body: form({ player: [player], action: ["transfer"], target: [target] }) })
          .then(function () { toast("Sending " + player + " to " + target + "…"); })
          .catch(function (error) { toast("Transfer failed: " + error.message); })
          .then(function () { select.value = ""; refresh(); });
      });
    });
    Array.prototype.forEach.call(byId("player-grid").querySelectorAll("button[data-kick]"), function (button) {
      button.addEventListener("click", function () {
        var player = button.getAttribute("data-kick");
        if (!window.confirm("Kick " + player + "?")) return;
        api("/bridge/players", { method: "POST", body: form({ player: [player], action: ["kick"], reason: ["Kicked from the dashboard"] }) })
          .then(function () { toast(player + " was kicked."); })
          .catch(function (error) { toast("Kick failed: " + error.message); })
          .then(refresh);
      });
    });
    // CSP-safe avatar fallback: swap to the letter tile when the CDN image fails.
    Array.prototype.forEach.call(byId("player-grid").querySelectorAll("img.head"), function (image) {
      image.addEventListener("error", function () {
        image.style.display = "none";
        var fallback = image.nextElementSibling;
        if (fallback) fallback.style.display = "flex";
      });
    });
  }
  byId("player-filter").addEventListener("input", renderPlayers);

  // ---- services page ------------------------------------------------------
  function renderServices() {
    var list = services().slice().sort(function (a, b) { return a.name.localeCompare(b.name); });
    var counts = { ALL: list.length, RUNNING: 0, STARTING: 0, STOPPED: 0, CRASHED: 0 };
    list.forEach(function (service) {
      var key = service.state === "STOPPING" ? "STARTING" : (service.state === "CREATED" ? "STOPPED" : service.state);
      if (counts[key] === undefined) counts[key] = 0;
      counts[key]++;
    });
    var tabs = ["ALL", "RUNNING", "STARTING", "STOPPED", "CRASHED"];
    byId("service-tabs").innerHTML = tabs.filter(function (tab) { return counts[tab] > 0; }).map(function (tab) {
      return '<button class="tab' + (serviceTab === tab ? " active" : "") + '" data-tab="' + tab + '">' +
        (tab === "ALL" ? "All" : tab.charAt(0) + tab.slice(1).toLowerCase()) + '<span class="n">' + counts[tab] + '</span></button>';
    }).join("");
    Array.prototype.forEach.call(byId("service-tabs").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        serviceTab = button.getAttribute("data-tab");
        renderServices();
      });
    });

    var visible = list.filter(function (service) {
      if (serviceTab === "ALL") return true;
      if (serviceTab === "STARTING") return service.state === "STARTING" || service.state === "STOPPING";
      if (serviceTab === "STOPPED") return service.state === "STOPPED" || service.state === "CREATED";
      return service.state === serviceTab;
    });
    var body = visible.map(function (service) {
      var meta = metaOf(service.name);
      var tps = meta ? meta.tps : null;
      var ram = meta ? meta.ram_usage : null;
      var heap = meta && meta.heap_used_mb !== null && meta.heap_used_mb !== undefined
        ? Math.round(meta.heap_used_mb) + " / " + Math.round(meta.heap_max_mb || 0) + " MB" : "";
      var players = service["players-online"];
      var actions =
        '<button class="btn small" data-act="start" data-name="' + esc(service.name) + '">Start</button> ' +
        '<button class="btn small" data-act="restart" data-name="' + esc(service.name) + '">Restart</button> ' +
        '<button class="btn small danger" data-act="stop" data-name="' + esc(service.name) + '">Stop</button>';
      return '<tr class="clickable" data-service="' + esc(service.name) + '">' +
        '<td>' + esc(service.name) + '</td>' +
        '<td>' + esc(service.group) + '</td>' +
        '<td><span class="badge ' + esc(service.state) + '"><span class="pip"></span>' + esc(service.state) + '</span></td>' +
        '<td>' + esc(service.type) + '</td>' +
        '<td class="num">' + esc(service.port) + '</td>' +
        '<td class="num ' + tpsClass(tps) + '">' + esc(fmtTps(tps)) + '</td>' +
        '<td>' + (ram === null || ram === undefined ? '<span class="dim">—</span>' :
          '<span class="meter"><span class="' + ramClass(ram) + '" style="width:' + Math.round(ram * 100) + '%"></span></span>' +
          ' <span class="dim" style="font-size:11px;">' + esc(heap) + '</span>') + '</td>' +
        '<td class="num">' + esc(players === null || players === undefined ? "—" : players) + '</td>' +
        '<td style="text-align:right;">' + actions + '</td>' +
        '</tr>';
    }).join("");
    byId("services-body").innerHTML = body || '<tr><td colspan="9" class="empty">No services in this view.</td></tr>';

    Array.prototype.forEach.call(byId("services-body").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function (event) {
        event.stopPropagation();
        runCloudCommand([button.getAttribute("data-act"), button.getAttribute("data-name")]);
      });
    });
    Array.prototype.forEach.call(byId("services-body").querySelectorAll("tr.clickable"), function (row) {
      row.addEventListener("click", function () {
        selectedService = row.getAttribute("data-service");
        navigate("console");
      });
    });
  }

  // ---- groups page --------------------------------------------------------
  function renderGroups() {
    var list = groups().slice().sort(function (a, b) { return a.name.localeCompare(b.name); });
    var runningByGroup = {};
    var provisionedByGroup = {};
    services().forEach(function (service) {
      provisionedByGroup[service.group] = (provisionedByGroup[service.group] || 0) + 1;
      if (service.state === "RUNNING" || service.state === "STARTING") {
        runningByGroup[service.group] = (runningByGroup[service.group] || 0) + 1;
      }
    });
    var isProxy = { VELOCITY: true, BUNGEECORD: true };
    var kinds = { ALL: list.length, BACKEND: 0, PROXY: 0 };
    list.forEach(function (group) { kinds[isProxy[group.type] ? "PROXY" : "BACKEND"]++; });
    byId("group-tabs").innerHTML = ["ALL", "BACKEND", "PROXY"].map(function (tab) {
      return '<button class="tab' + (groupTab === tab ? " active" : "") + '" data-tab="' + tab + '">' +
        (tab === "ALL" ? "All" : tab.charAt(0) + tab.slice(1).toLowerCase() + "s") + '<span class="n">' + kinds[tab] + '</span></button>';
    }).join("");
    Array.prototype.forEach.call(byId("group-tabs").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        groupTab = button.getAttribute("data-tab");
        renderGroups();
      });
    });

    var visible = list.filter(function (group) {
      if (groupTab === "ALL") return true;
      return groupTab === (isProxy[group.type] ? "PROXY" : "BACKEND");
    });
    byId("groups-body").innerHTML = visible.length ? visible.map(function (group) {
      var running = runningByGroup[group.name] || 0;
      var provisioned = provisionedByGroup[group.name] || 0;
      var desired = group["min-services"] || 0;
      var max = group["max-services"] || 0;
      var atCapacity = provisioned >= max;
      return '<tr>' +
        '<td>' + esc(group.name) + '</td>' +
        '<td>' + esc(group.type) + '</td>' +
        '<td>' + esc(group.version) + '</td>' +
        '<td class="num">' + running + '</td>' +
        '<td class="num">' + desired + '</td>' +
        '<td class="num">' + provisioned + '</td>' +
        '<td class="num">' + max + '</td>' +
        '<td style="text-align:right;">' +
        (atCapacity
          ? '<span class="dim" style="font-size:12px;">at max</span>'
          : '<button class="btn small" data-group="' + esc(group.name) + '">Start another</button>') +
        '</td></tr>';
    }).join("") : '<tr><td colspan="8" class="empty">No groups in this view.</td></tr>';

    Array.prototype.forEach.call(byId("groups-body").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        runCloudCommand(["group", "start", button.getAttribute("data-group")]);
      });
    });
  }

  // ---- charts (gradient area charts with hover tooltip) --------------------
  var CHARTS = [
    { id: "players", title: "Players online", pick: function (p) { return p.players; }, zero: true, color: CHART_COLORS.players },
    { id: "tps", title: "Worst backend TPS", pick: function (p) { return p.tps; }, zero: true, max: 20, color: CHART_COLORS.tps },
    { id: "ram", title: "Avg. backend memory", pick: function (p) { return p.ram === null || p.ram === undefined ? null : p.ram * 100; }, zero: true, max: 100, color: CHART_COLORS.ram, suffix: "%" },
    { id: "services", title: "Services running", pick: function (p) { return p.running; }, zero: true, color: CHART_COLORS.services },
  ];
  var RANGES = [
    { label: "15m", seconds: 900 },
    { label: "30m", seconds: 1800 },
    { label: "1h", seconds: 3600 },
  ];

  function historyPoints() {
    var points = (lastMetrics && lastMetrics.history && lastMetrics.history.points) || [];
    return points;
  }

  function buildCharts() {
    if (chartsBuilt) return;
    chartsBuilt = true;
    byId("charts").innerHTML = CHARTS.map(function (chart) {
      var tabs = RANGES.map(function (range) {
        return '<button data-range="' + range.seconds + '"' +
          (chartRanges[chart.id] === range.seconds ? ' class="active"' : '') + '>' + range.label + '</button>';
      }).join("");
      return '<div class="card"><div class="card-head"><h2>' + esc(chart.title) + '</h2>' +
        '<div class="range-tabs" data-chart="' + chart.id + '">' + tabs + '</div></div>' +
        '<div class="chart-wrap"><canvas id="chart-' + chart.id + '"></canvas>' +
        '<div class="tooltip" id="tip-' + chart.id + '"></div></div></div>';
    }).join("");
    CHARTS.forEach(function (chart) {
      var canvas = byId("chart-" + chart.id);
      canvas.addEventListener("mousemove", function (event) {
        var rect = canvas.getBoundingClientRect();
        onChartHover(chart, event.clientX - rect.left, rect.width);
      });
      canvas.addEventListener("mouseleave", function () {
        hover[chart.id] = null;
        byId("tip-" + chart.id).style.display = "none";
        drawChart(chart);
      });
      Array.prototype.forEach.call(
        document.querySelectorAll('.range-tabs[data-chart="' + chart.id + '"] button'),
        function (button) {
          button.addEventListener("click", function () {
            chartRanges[chart.id] = parseInt(button.getAttribute("data-range"), 10);
            Array.prototype.forEach.call(
              document.querySelectorAll('.range-tabs[data-chart="' + chart.id + '"] button'),
              function (other) { other.classList.toggle("active", other === button); },
            );
            drawChart(chart);
          });
        },
      );
    });
    window.addEventListener("resize", function () { CHARTS.forEach(drawChart); });
  }

  function chartPoints(chart) {
    var cutoff = Math.floor(Date.now() / 1000) - chartRanges[chart.id];
    var points = historyPoints().filter(function (point) { return point.t >= cutoff; });
    return points.length ? points : historyPoints();
  }

  function onChartHover(chart, offsetX, width) {
    var points = chartPoints(chart);
    if (points.length < 2) return;
    var canvas = byId("chart-" + chart.id);
    var pad = chartPad(width);
    var innerWidth = width - pad.left - pad.right;
    var ratio = (offsetX - pad.left) / innerWidth;
    var index = Math.round(ratio * (points.length - 1));
    index = Math.max(0, Math.min(points.length - 1, index));
    hover[chart.id] = index;
    drawChart(chart);
    var tip = byId("tip-" + chart.id);
    var value = chart.pick(points[index]);
    tip.innerHTML = '<div class="t-label">' + new Date(points[index].t * 1000).toLocaleTimeString() + '</div>' +
      '<div class="row"><span class="swatch" style="background:' + chart.color + '"></span>' +
      esc(value === null || value === undefined ? "—" : (chart.suffix ? Math.round(value) + chart.suffix : Math.round(value * 10) / 10)) + '</div>';
    tip.style.display = "block";
    var wrap = canvas.parentElement;
    var left = Math.min(Math.max(offsetX + 12, 0), wrap.clientWidth - tip.offsetWidth - 4);
    tip.style.left = left + "px";
    tip.style.top = "6px";
  }

  function chartPad(width) {
    return { top: 12, right: width < 260 ? 12 : 44, bottom: 20, left: 12 };
  }

  function drawChart(chart) {
    var canvas = byId("chart-" + chart.id);
    if (!canvas) return;
    var points = chartPoints(chart);
    var ratio = window.devicePixelRatio || 1;
    var width = canvas.clientWidth || 320;
    var height = 190;
    canvas.width = width * ratio;
    canvas.height = height * ratio;
    var context = canvas.getContext("2d");
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.scale(ratio, ratio);
    context.clearRect(0, 0, width, height);

    var pad = chartPad(width);
    var innerWidth = width - pad.left - pad.right;
    var innerHeight = height - pad.top - pad.bottom;
    var series = points.map(chart.pick);
    var valid = series.filter(function (value) { return value !== null && value !== undefined; });
    var lo = chart.zero ? 0 : (valid.length ? Math.min.apply(null, valid) : 0);
    var hi = valid.length ? Math.max.apply(null, valid) : 1;
    if (chart.max) hi = Math.max(hi, chart.max);
    if (hi === lo) hi = lo + 1;
    var span = hi - lo;

    function x(index) { return pad.left + (points.length <= 1 ? innerWidth / 2 : innerWidth * index / (points.length - 1)); }
    function y(value) { return pad.top + innerHeight - innerHeight * (value - lo) / span; }

    // grid + axis labels
    context.strokeStyle = "#1c1c20";
    context.lineWidth = 1;
    context.fillStyle = "#6f6f78";
    context.font = "10.5px ui-sans-serif, system-ui, sans-serif";
    for (var g = 0; g <= 4; g++) {
      var gy = pad.top + innerHeight * g / 4;
      context.beginPath(); context.moveTo(pad.left, gy); context.lineTo(pad.left + innerWidth, gy); context.stroke();
      if (width >= 260) context.fillText(String(Math.round((hi - span * g / 4) * 10) / 10), pad.left + innerWidth + 6, gy + 3);
    }
    // time axis (first / middle / last)
    if (points.length > 1 && width >= 300) {
      context.textAlign = "center";
      [0, Math.floor((points.length - 1) / 2), points.length - 1].forEach(function (index) {
        context.fillText(new Date(points[index].t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }), x(index), height - 6);
      });
      context.textAlign = "left";
    }

    // gradient area + line
    var firstValid = -1, lastValid = -1;
    series.forEach(function (value, index) {
      if (value === null || value === undefined) return;
      if (firstValid < 0) firstValid = index;
      lastValid = index;
    });
    if (firstValid >= 0) {
      var gradient = context.createLinearGradient(0, pad.top, 0, pad.top + innerHeight);
      gradient.addColorStop(0, chart.color + "66");
      gradient.addColorStop(1, chart.color + "05");
      context.beginPath();
      context.moveTo(x(firstValid), y(series[firstValid]));
      for (var i = firstValid + 1; i <= lastValid; i++) {
        if (series[i] === null || series[i] === undefined) continue;
        context.lineTo(x(i), y(series[i]));
      }
      context.lineTo(x(lastValid), pad.top + innerHeight);
      context.lineTo(x(firstValid), pad.top + innerHeight);
      context.closePath();
      context.fillStyle = gradient;
      context.fill();

      context.beginPath();
      context.moveTo(x(firstValid), y(series[firstValid]));
      for (var j = firstValid + 1; j <= lastValid; j++) {
        if (series[j] === null || series[j] === undefined) continue;
        context.lineTo(x(j), y(series[j]));
      }
      context.strokeStyle = chart.color;
      context.lineWidth = 2;
      context.lineJoin = "round";
      context.stroke();
    }

    // hover crosshair + dot (drawn last so refreshes never hide it)
    var hoverIndex = hover[chart.id];
    if (hoverIndex !== null && hoverIndex !== undefined && points[hoverIndex]) {
      var hx = x(hoverIndex);
      context.strokeStyle = "#3f3f46";
      context.beginPath(); context.moveTo(hx, pad.top); context.lineTo(hx, pad.top + innerHeight); context.stroke();
      var hv = series[hoverIndex];
      if (hv !== null && hv !== undefined) {
        context.beginPath();
        context.arc(hx, y(hv), 3.5, 0, Math.PI * 2);
        context.fillStyle = chart.color;
        context.fill();
        context.strokeStyle = "#09090b";
        context.stroke();
      }
    }
  }

  function renderCharts() {
    // While the pointer hovers a chart, skip its data redraw so the tooltip stays stable.
    CHARTS.forEach(function (chart) {
      if (hover[chart.id] === null || hover[chart.id] === undefined) drawChart(chart);
    });
  }

  // ---- cloud commands ------------------------------------------------------
  function runCloudCommand(args) {
    return api("/bridge/cloud", { method: "POST", body: form({ arg: args }) })
      .then(function (result) {
        toast((result.lines || []).map(function (line) { return line.replace(/\u00a7./g, ""); }).join("\n") || "OK");
        return refresh();
      })
      .catch(function (error) { toast("Failed: " + error.message); });
  }
  function toast(message) {
    var element = document.createElement("div");
    element.className = "toast";
    element.textContent = message;
    document.body.appendChild(element);
    setTimeout(function () { element.remove(); }, 5000);
  }

  byId("quick-run").addEventListener("click", function () {
    var input = byId("quick-command");
    var args = input.value.trim().split(/\s+/).filter(Boolean);
    if (!args.length) return;
    runCloudCommand(args);
    input.value = "";
  });
  byId("quick-command").addEventListener("keydown", function (event) {
    if (event.key === "Tab") {
      event.preventDefault();
      var input = byId("quick-command");
      var args = input.value.split(/\s+/).filter(Boolean);
      api("/bridge/cloud", { method: "POST", body: form({ arg: args, mode: ["complete"] }) })
        .then(function (result) {
          var suggestions = result.suggestions || [];
          if (suggestions.length === 1) {
            args[args.length - 1] = suggestions[0];
            input.value = args.join(" ");
          } else if (suggestions.length > 1) {
            toast(suggestions.join("  "));
          }
        }).catch(function () {});
    } else if (event.key === "Enter") {
      byId("quick-run").click();
    }
  });

  // ---- console -------------------------------------------------------------
  function renderConsoleTarget() {
    var select = byId("console-service");
    var running = services().filter(function (service) { return service.state === "RUNNING"; });
    select.innerHTML = '<option value="">— running service —</option>' + running.map(function (service) {
      var selected = service.name === selectedService ? " selected" : "";
      return '<option value="' + esc(service.name) + '"' + selected + '>' + esc(service.name) + '</option>';
    }).join("");
    if (selectedService && !running.some(function (service) { return service.name === selectedService; })) {
      selectedService = null;
      select.selectedIndex = 0;
    }
  }
  byId("console-service").addEventListener("change", function () {
    selectedService = byId("console-service").value || null;
    loadConsole();
  });
  byId("console-refresh").addEventListener("click", loadConsole);
  function loadConsole() {
    if (page !== "console" || !selectedService) { byId("console-output").textContent = ""; return; }
    api("/bridge/console?service=" + encodeURIComponent(selectedService))
      .then(function (result) {
        var box = byId("console-output");
        box.textContent = (result.lines || []).join("\n");
        box.scrollTop = box.scrollHeight;
      })
      .catch(function (error) { byId("console-output").textContent = "Failed to load console: " + error.message; });
  }
  byId("console-send").addEventListener("click", function () {
    var input = byId("console-command");
    var line = input.value.trim();
    if (!line || !selectedService) return;
    input.value = "";
    api("/bridge/services/command", {
      method: "POST",
      body: form({ service: [selectedService], action: ["command"], command: [line] }),
    }).then(function () {
      byId("console-output").textContent += "\n> " + line;
      setTimeout(loadConsole, 600);
    }).catch(function (error) { toast("Failed: " + error.message); });
  });
  byId("console-command").addEventListener("keydown", function (event) {
    if (event.key === "Enter") byId("console-send").click();
  });

  // ---- boot ----------------------------------------------------------------
  // A valid HttpOnly session cookie (if any) is proven by a probe request; the token itself never
  // touches JavaScript — the browser attaches the cookie automatically.
  api("/bridge/status").then(function () {
    showApp();
    navigate((location.hash || "#overview").slice(1));
  }).catch(function () { showLogin(); });
  window.setInterval(function () {
    if (!root.classList.contains("hidden")) {
      refresh();
      if (page === "console" && byId("console-follow").checked) loadConsole();
    }
  }, 5000);
})();
</script>
</body>
</html>"""
}
