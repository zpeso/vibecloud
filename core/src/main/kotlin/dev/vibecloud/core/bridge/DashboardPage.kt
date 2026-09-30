package dev.vibecloud.core.bridge

/**
 * The built-in dashboard: a single, dependency-free HTML page served by the bridge at `/`.
 * It authenticates with the same bearer token as every other bridge endpoint (stored in the
 * browser's localStorage after login) and renders live cloud state plus metric charts.
 *
 * No web framework and no build step on purpose: the controller ships as one zip, and the page
 * must work offline behind a plain `java.net.http` server.
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
    --border: #27272a;
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

  /* ---- login ---- */
  #login {
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
  }
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
  input[type="text"], input[type="password"], .console-input input {
    background: #18181b;
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--foreground);
    padding: 8px 10px;
    font-size: 13px;
    outline: none;
  }

  /* ---- app layout ---- */
  #app { display: flex; flex-direction: column; min-height: 100%; }
  header.topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 24px;
    border-bottom: 1px solid var(--border);
    position: sticky;
    top: 0;
    background: rgba(9,9,11,.92);
    backdrop-filter: blur(6px);
    z-index: 5;
  }
  .brand { display: flex; align-items: center; gap: 10px; font-weight: 600; letter-spacing: -.01em; }
  .brand .dot { width: 9px; height: 9px; border-radius: 50%; background: var(--green); box-shadow: 0 0 8px var(--green); }
  .topbar .meta { display: flex; align-items: center; gap: 10px; color: var(--muted-foreground); font-size: 12px; }
  main {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding: 20px 24px 32px;
    max-width: 1400px;
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
    font-size: 13px;
    font-weight: 500;
    color: var(--muted-foreground);
    text-transform: uppercase;
    letter-spacing: .06em;
    margin-bottom: 12px;
  }
  .stat-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
    gap: 16px;
  }
  .stat .value { font-size: 26px; font-weight: 600; letter-spacing: -.02em; margin: 2px 0; font-variant-numeric: tabular-nums; }
  .stat .hint { font-size: 12px; color: var(--muted-foreground); }
  .trend { font-size: 12px; margin-left: 8px; }
  .trend.up { color: var(--green); }
  .trend.down { color: var(--red); }

  .charts {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
    gap: 16px;
  }
  canvas { width: 100%; height: 180px; display: block; }

  /* ---- table ---- */
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

  .meter { width: 90px; height: 6px; border-radius: 999px; background: var(--muted); overflow: hidden; display: inline-block; vertical-align: middle; }
  .meter > span { display: block; height: 100%; border-radius: 999px; background: var(--accent); }
  .meter > span.warn { background: var(--yellow); }
  .meter > span.bad { background: var(--red); }
  .num { font-variant-numeric: tabular-nums; }

  /* ---- console ---- */
  .console-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; gap: 10px; }
  .console-head select {
    background: #18181b;
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--foreground);
    padding: 6px 8px;
    font-size: 13px;
  }
  .console {
    background: #0a0a0c;
    border: 1px solid var(--border);
    border-radius: 8px;
    height: 260px;
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

  .actions-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
  .actions-row input { flex: 1; min-width: 220px; }
  footer {
    color: var(--muted-foreground);
    font-size: 11px;
    text-align: center;
    padding: 10px 0 16px;
  }
  footer a { color: var(--muted-foreground); }
</style>
</head>
<body>

<div id="login">
  <form class="login-card" id="login-form">
    <h1>VibeCloud</h1>
    <p class="sub">Sign in with the bridge token (bridge.token on the cloud host).</p>
    <label for="token">Access token</label>
    <input type="password" id="token" autocomplete="off" autofocus>
    <div class="login-error" id="login-error"></div>
    <button class="btn primary" type="submit" style="width:100%;justify-content:center;margin-top:8px;">Sign in</button>
  </form>
</div>

<div id="app" class="hidden">
  <header class="topbar">
    <div class="brand"><span class="dot"></span> VibeCloud</div>
    <div class="meta">
      <span id="updated-at"></span>
      <button class="btn small" id="logout">Sign out</button>
    </div>
  </header>

  <main>
    <section class="stat-grid" id="stats"></section>

    <section class="charts">
      <div class="card"><h2>Players online</h2><canvas id="chart-players"></canvas></div>
      <div class="card"><h2>Worst backend TPS</h2><canvas id="chart-tps"></canvas></div>
      <div class="card"><h2>Avg. backend memory</h2><canvas id="chart-ram"></canvas></div>
      <div class="card"><h2>Services running</h2><canvas id="chart-services"></canvas></div>
    </section>

    <section class="card">
      <h2>Services</h2>
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>Name</th><th>Group</th><th>State</th><th>Type</th><th>Port</th>
            <th>TPS</th><th>Memory</th><th>Players</th><th></th>
          </tr></thead>
          <tbody id="services-body"></tbody>
        </table>
      </div>
      <div class="actions-row">
        <input type="text" id="quick-command" placeholder="Cloud command, e.g. group start lobby — Tab &amp; Enter work too">
        <button class="btn primary" id="quick-run">Run</button>
      </div>
    </section>

    <section class="card">
      <h2>Groups</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Name</th><th>Type</th><th>Version</th><th>Desired</th><th>Max</th><th></th></tr></thead>
          <tbody id="groups-body"></tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <div class="console-head">
        <h2 style="margin:0;">Console</h2>
        <select id="console-service"></select>
        <div style="flex:1;"></div>
        <button class="btn small" id="console-refresh">Refresh</button>
      </div>
      <div class="console" id="console-output"></div>
      <div class="console-input">
        <input type="text" id="console-command" placeholder="Console command for the selected service">
        <button class="btn" id="console-send">Send</button>
      </div>
    </section>
  </main>

  <footer>VibeCloud dashboard &middot; served by the bridge &middot; <a href="/bridge/status">raw status</a></footer>
</div>

<script>
(function () {
  "use strict";

  var TOKEN_KEY = "vibecloud.token";
  var root = document.getElementById("app");
  var login = document.getElementById("login");
  var token = null;
  var lastStatus = null;
  var lastMetrics = null;
  var selectedService = null;

  function byId(id) { return document.getElementById(id); }
  function esc(text) {
    var div = document.createElement("div");
    div.textContent = text === null || text === undefined ? "" : String(text);
    return div.innerHTML;
  }

  // ---- HTTP helpers -------------------------------------------------------
  function api(path, options) {
    options = options || {};
    options.headers = Object.assign({}, options.headers, { "Authorization": "Bearer " + token });
    if (options.body && !(options.body instanceof FormData)) {
      options.headers["Content-Type"] = options.headers["Content-Type"] || "application/x-www-form-urlencoded";
    }
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
    try { localStorage.removeItem(TOKEN_KEY); } catch (error) {}
    token = null;
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
    token = byId("token").value.trim();
    byId("login-error").textContent = "";
    api("/bridge/status").then(function () {
      try { localStorage.setItem(TOKEN_KEY, token); } catch (error) {}
      showApp();
    }).catch(function (error) {
      if (error.message !== "unauthorized") byId("login-error").textContent = "Sign-in failed: " + error.message;
      else byId("login-error").textContent = "Invalid token.";
    });
  });
  byId("logout").addEventListener("click", showLogin);

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

  function serviceMeta(name) {
    var list = (lastMetrics && lastMetrics.services) || [];
    for (var i = 0; i < list.length; i++) if (list[i].name === name) return list[i];
    return null;
  }
  function fmtRam(ratio) {
    if (ratio === null || ratio === undefined) return "—";
    var used = Math.round(ratio * 100);
    return used + "%";
  }
  function ramClass(ratio) {
    if (ratio === null || ratio === undefined) return "";
    if (ratio >= 0.9) return "bad";
    if (ratio >= 0.75) return "warn";
    return "";
  }
  function fmtTps(tps) {
    if (tps === null || tps === undefined) return "—";
    return (Math.round(tps * 10) / 10).toFixed(1);
  }
  function tpsClass(tps) {
    if (tps === null || tps === undefined) return "";
    if (tps < 15) return "bad";
    if (tps < 19) return "warn";
    return "";
  }

  // ---- rendering ----------------------------------------------------------
  function renderAll() {
    var totals = lastStatus.totals;
    byId("updated-at").textContent = "updated " + new Date().toLocaleTimeString();
    renderStats(totals);
    renderServices(lastStatus.services);
    renderGroups(lastStatus.groups || []);
    renderCharts(lastMetrics);
    renderConsoleTarget();
  }

  function renderStats(totals) {
    var services = lastStatus.services || [];
    var reporting = 0, ramSum = 0, tpsSum = 0;
    services.forEach(function (service) {
      var meta = serviceMeta(service.name);
      if (meta) {
        reporting++;
        ramSum += (meta.heap_used_mb || 0);
        tpsSum += (meta.tps === null || meta.tps === undefined ? 20 : meta.tps);
      }
    });
    var avgTps = reporting ? (tpsSum / reporting) : null;
    var ramMb = reporting ? ramSum : null;
    var html = "";
    html += statCard("Players online", totals["players-online"], "across all services", deltaPlayers());
    html += statCard("Services running", totals.online + " / " + totals.services, "provisioned services");
    html += statCard("Groups", totals.groups, "configured groups");
    html += statCard("Avg. backend TPS", avgTps === null ? "—" : (Math.round(avgTps * 10) / 10).toFixed(1), reporting + " backend(s) reporting");
    html += statCard("Backend memory", ramMb === null ? "—" : ramMb + " MB", "heap used, all backends");
    byId("stats").innerHTML = html;

    function deltaPlayers() {
      var points = (lastMetrics && lastMetrics.points) || [];
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

  function renderServices(services) {
    var rows = services.map(function (service) {
      var meta = serviceMeta(service.name);
      var tps = meta ? meta.tps : null;
      var ram = meta ? meta.ram_usage : null;
      var heap = meta ? (meta.heap_used_mb + " / " + meta.heap_max_mb + " MB") : "";
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
        '<td>' + (ram === null || ram === undefined ? '—' :
          '<span class="meter"><span class="' + ramClass(ram) + '" style="width:' + Math.round(ram * 100) + '%"></span></span>' +
          ' <span class="num hint" style="font-size:11px;color:var(--muted-foreground);">' + esc(heap) + '</span>') + '</td>' +
        '<td class="num">' + esc(players === null || players === undefined ? "—" : players) + '</td>' +
        '<td>' + actions + '</td>' +
        '</tr>';
    }).join("");
    byId("services-body").innerHTML = rows || '<tr><td colspan="9" style="color:var(--muted-foreground);">No services provisioned.</td></tr>';

    Array.prototype.forEach.call(byId("services-body").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function (event) {
        event.stopPropagation();
        var action = button.getAttribute("data-act");
        var name = button.getAttribute("data-name");
        runCloudCommand([action, name]);
      });
    });
    Array.prototype.forEach.call(byId("services-body").querySelectorAll("tr.clickable"), function (row) {
      row.addEventListener("click", function () { openConsole(row.getAttribute("data-service")); });
    });
  }

  function renderGroups(groups) {
    var rows = groups.map(function (group) {
      return '<tr>' +
        '<td>' + esc(group.name) + '</td>' +
        '<td>' + esc(group.type) + '</td>' +
        '<td>' + esc(group.version) + '</td>' +
        '<td class="num">' + esc(group["min-services"] || 0) + '</td>' +
        '<td class="num">' + esc(group["max-services"] || 0) + '</td>' +
        '<td><button class="btn small" data-group="' + esc(group.name) + '">Start another</button></td>' +
        '</tr>';
    }).join("");
    byId("groups-body").innerHTML = rows || '<tr><td colspan="6" style="color:var(--muted-foreground);">No groups configured.</td></tr>';
    Array.prototype.forEach.call(byId("groups-body").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        runCloudCommand(["group", "start", button.getAttribute("data-group")]);
      });
    });
  }

  // ---- charts (dependency-free line charts) --------------------------------
  function drawLine(canvasId, points, pick, options) {
    var canvas = byId(canvasId);
    if (!canvas) return;
    var ratio = window.devicePixelRatio || 1;
    var width = canvas.clientWidth || 300;
    var height = 180;
    canvas.width = width * ratio;
    canvas.height = height * ratio;
    var context = canvas.getContext("2d");
    context.scale(ratio, ratio);
    context.clearRect(0, 0, width, height);

    var pad = { top: 10, right: 44, bottom: 18, left: 10 };
    var innerWidth = width - pad.left - pad.right;
    var innerHeight = height - pad.top - pad.bottom;
    var series = points.map(pick);
    var valid = series.filter(function (value) { return value !== null && value !== undefined; });
    var lo = options && options.zero ? 0 : Math.min.apply(null, valid.length ? valid : [0]);
    var hi = valid.length ? Math.max.apply(null, valid) : 1;
    if (options && options.max) hi = Math.max(hi, options.max);
    if (options && options.zero) hi = Math.max(hi, 1);
    if (hi === lo) hi = lo + 1;
    var span = hi - lo;

    function x(index) { return pad.left + (points.length <= 1 ? 0 : (innerWidth * index / (points.length - 1))); }
    function y(value) { return pad.top + innerHeight - (innerHeight * (value - lo) / span); }

    // grid
    context.strokeStyle = "#1f1f23";
    context.lineWidth = 1;
    for (var g = 0; g <= 4; g++) {
      var gy = pad.top + innerHeight * g / 4;
      context.beginPath(); context.moveTo(pad.left, gy); context.lineTo(pad.left + innerWidth, gy); context.stroke();
    }
    // line
    context.strokeStyle = (options && options.color) || "#22d3ee";
    context.lineWidth = 2;
    context.lineJoin = "round";
    var started = false;
    context.beginPath();
    series.forEach(function (value, index) {
      if (value === null || value === undefined) { started = false; return; }
      if (!started) { context.moveTo(x(index), y(value)); started = true; }
      else context.lineTo(x(index), y(value));
    });
    context.stroke();
    // area
    var firstValid = series.findIndex(function (v) { return v !== null && v !== undefined; });
    var lastValid = -1;
    for (var i = series.length - 1; i >= 0; i--) if (series[i] !== null && series[i] !== undefined) { lastValid = i; break; }
    if (firstValid >= 0 && lastValid > firstValid) {
      context.lineTo(x(lastValid), pad.top + innerHeight);
      context.lineTo(x(firstValid), pad.top + innerHeight);
      context.closePath();
      context.globalAlpha = 0.12;
      context.fillStyle = (options && options.color) || "#22d3ee";
      context.fill();
      context.globalAlpha = 1;
    }
    // axis labels
    context.fillStyle = "#71717a";
    context.font = "11px ui-sans-serif, system-ui, sans-serif";
    context.textAlign = "left";
    context.fillText(String(Math.round(hi * 10) / 10), pad.left + innerWidth + 6, pad.top + 6);
    context.fillText(String(Math.round(lo * 10) / 10), pad.left + innerWidth + 6, pad.top + innerHeight);
  }

  function renderCharts(metrics) {
    var points = (metrics && metrics.points) || [];
    if (!points.length) {
      var placeholder = [{ players: null, tps: null, ram: null, running: null }];
      drawLine("chart-players", placeholder, function (p) { return p.players; }, { zero: true, max: 10 });
      drawLine("chart-tps", placeholder, function (p) { return p.tps; }, { zero: true, max: 20, color: "#34d399" });
      drawLine("chart-ram", placeholder, function (p) { return p.ram === null ? null : p.ram * 100; }, { zero: true, max: 100, color: "#fbbf24" });
      drawLine("chart-services", placeholder, function (p) { return p.running; }, { zero: true, max: 4, color: "#a78bfa" });
      return;
    }
    drawLine("chart-players", points, function (p) { return p.players; }, { zero: true });
    drawLine("chart-tps", points, function (p) { return p.tps; }, { zero: true, max: 20, color: "#34d399" });
    drawLine("chart-ram", points, function (p) { return p.ram === null ? null : p.ram * 100; }, { zero: true, max: 100, color: "#fbbf24" });
    drawLine("chart-services", points, function (p) { return p.running; }, { zero: true, max: 4, color: "#a78bfa" });
  }

  // ---- cloud commands ------------------------------------------------------
  function runCloudCommand(args) {
    return api("/bridge/cloud", { method: "POST", body: form({ arg: args }) })
      .then(function (result) {
        toast((result.lines || []).map(function (line) {
          return line.replace(/\u00a7./g, "");
        }).join("\n") || "OK");
        return refresh();
      })
      .catch(function (error) { toast("Failed: " + error.message); });
  }

  function toast(message) {
    var element = document.createElement("div");
    element.style.cssText = "position:fixed;bottom:20px;right:20px;max-width:420px;white-space:pre-wrap;" +
      "background:#18181b;border:1px solid var(--border);border-radius:10px;padding:12px 16px;font-size:13px;" +
      "box-shadow:0 10px 30px rgba(0,0,0,.5);z-index:50;";
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
    if (event.key !== "Tab") return;
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
  });
  byId("quick-command").addEventListener("keydown", function (event) {
    if (event.key === "Enter") byId("quick-run").click();
  });

  // ---- console -------------------------------------------------------------
  function renderConsoleTarget() {
    var select = byId("console-service");
    var services = (lastStatus.services || []).filter(function (service) { return service.state === "RUNNING"; });
    var options = '<option value="">— running service —</option>' + services.map(function (service) {
      var selected = service.name === selectedService ? " selected" : "";
      return '<option value="' + esc(service.name) + '"' + selected + '>' + esc(service.name) + '</option>';
    }).join("");
    select.innerHTML = options;
    if (selectedService && !services.some(function (service) { return service.name === selectedService; })) {
      selectedService = null;
      select.selectedIndex = 0;
    }
  }
  byId("console-service").addEventListener("change", function () {
    selectedService = byId("console-service").value || null;
    loadConsole();
  });
  byId("console-refresh").addEventListener("click", loadConsole);
  function openConsole(name) {
    selectedService = name;
    var select = byId("console-service");
    Array.prototype.forEach.call(select.options, function (option, index) {
      if (option.value === name) select.selectedIndex = index;
    });
    loadConsole();
  }
  function loadConsole() {
    if (!selectedService) { byId("console-output").textContent = ""; return; }
    api("/bridge/console?service=" + encodeURIComponent(selectedService))
      .then(function (result) {
        byId("console-output").textContent = (result.lines || []).join("\n");
        var box = byId("console-output");
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
  try { token = localStorage.getItem(TOKEN_KEY); } catch (error) { token = null; }
  if (token) {
    api("/bridge/status").then(showApp).catch(function (error) {
      if (error.message === "unauthorized") showLogin();
    });
  } else {
    showLogin();
  }
  window.setInterval(function () { if (token && !root.classList.contains("hidden")) refresh(); }, 5000);
  window.addEventListener("resize", function () { if (token && !root.classList.contains("hidden")) renderCharts(lastMetrics); });
})();
</script>
</body>
</html>"""
}
