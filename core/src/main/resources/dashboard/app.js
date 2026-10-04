/* VibeCloud dashboard — dependency-free SPA served by the bridge.
 * Auth works with HttpOnly session cookies (POST /bridge/dashboard/login exchanges the bridge
 * token); cookie-authenticated state-changing requests carry the CSRF header. */
(function () {
  "use strict";

  var root = document.getElementById("app");
  var login = document.getElementById("login");
  var lastStatus = null;
  var lastMetrics = null;
  var lastHost = null;
  var page = "overview";
  var serviceTab = "ALL";
  var groupTab = "ALL";
  var chartRanges = {}; // chartId -> seconds
  var hover = {};       // chartId -> hovered index or null
  var selectedService = null;
  var chartsBuilt = false;
  var activityLoadedAt = 0;

  function byId(id) { return document.getElementById(id); }
  function esc(text) {
    var div = document.createElement("div");
    div.textContent = text === null || text === undefined ? "" : String(text);
    return div.innerHTML;
  }

  // ---- HTTP helpers -------------------------------------------------------
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
    refreshAll();
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
      submitted = null;
      if (response.ok) { showApp(); return; }
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
  function refreshAll() {
    Promise.all([
      api("/bridge/status"),
      api("/bridge/metrics").catch(function () { return null; }),
      api("/bridge/host").catch(function () { return null; }),
    ]).then(function (results) {
      lastStatus = results[0];
      lastMetrics = results[1];
      lastHost = results[2];
      renderAll();
    }).catch(function () {});
  }
  function refresh() { refreshAll(); }

  function services() { return (lastStatus && lastStatus.services) || []; }
  function groups() { return (lastStatus && lastStatus.groups) || []; }
  function metaOf(name) {
    var list = (lastMetrics && lastMetrics.services) || [];
    for (var i = 0; i < list.length; i++) if (list[i].name === name) return list[i];
    return null;
  }
  function hostOf(name) { return (lastHost && lastHost.processes && lastHost.processes[name]) || null; }
  function fmtTps(tps) { return tps === null || tps === undefined ? "—" : (Math.round(tps * 10) / 10).toFixed(1); }
  function tpsClass(tps) { return tps === null || tps === undefined ? "" : (tps < 15 ? "bad" : (tps < 19 ? "warn" : "")); }
  function fmtRam(ratio) { return ratio === null || ratio === undefined ? "—" : Math.round(ratio * 100) + "%"; }
  function ramClass(ratio) { return ratio === null || ratio === undefined ? "" : (ratio >= 0.9 ? "bad" : (ratio >= 0.75 ? "warn" : "")); }
  function fmtCpu(ratio) { return ratio === null || ratio === undefined ? "—" : Math.round(ratio * 100) + "%"; }
  function cpuClass(ratio) { return ratio === null || ratio === undefined ? "" : (ratio >= 0.9 ? "bad" : (ratio >= 0.6 ? "warn" : "")); }
  function fmtUptime(seconds) {
    if (seconds === null || seconds === undefined) return "—";
    var d = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60);
    return d > 0 ? d + "d " + h + "h" : (h > 0 ? h + "h " + m + "m" : m + "m");
  }
  function fmtMb(value) { return value === null || value === undefined ? "—" : Math.round(value) + " MB"; }
  function pingClass(ms) { return ms === null || ms === undefined ? "" : (ms >= 250 ? "bad" : (ms >= 100 ? "warn" : "good")); }
  function fmtPing(ms) { return ms === null || ms === undefined ? "—" : ms + " ms"; }
  function detailOf(serviceName) {
    var list = (serviceName && services()) || [];
    for (var i = 0; i < list.length; i++) if (list[i].name === serviceName) return list[i];
    return null;
  }
  function detailFind(serviceName, playerName) {
    var service = detailOf(serviceName);
    if (!service || !service["player-details"]) return null;
    for (var i = 0; i < service["player-details"].length; i++) {
      if (service["player-details"][i].name.toLowerCase() === String(playerName).toLowerCase()) {
        return service["player-details"][i];
      }
    }
    return null;
  }

  // ---- routing ------------------------------------------------------------
  var PAGE_TITLES = { overview: "Overview", players: "Players", services: "Services", groups: "Groups", console: "Console", system: "Host & Health", activity: "Activity" };
  function navigate(target) {
    page = PAGE_TITLES[target] ? target : "overview";
    try { history.replaceState(null, "", "#" + page); } catch (error) {}
    Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (item) {
      item.classList.toggle("active", item.getAttribute("data-page") === page);
    });
    Array.prototype.forEach.call(document.querySelectorAll(".page-section"), function (section) {
      section.classList.toggle("hidden", section.id !== "page-" + page);
    });
    byId("page-title").textContent = PAGE_TITLES[page];
    if (page === "console") loadConsole();
    if (page === "activity") loadActivity();
    renderCharts();
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
    if (lastHost && lastHost.version) byId("sidebar-version").textContent = "VibeCloud v" + lastHost.version;
    renderAlert();
    renderStats(totals);
    buildCharts();
    renderCharts();
    renderGroupDistribution();
    renderServices();
    renderGroups();
    renderPlayers();
    renderConsoleTarget();
    renderSystem();
  }

  function renderAlert() {
    var crashed = services().filter(function (service) { return service.state === "CRASHED"; });
    var box = byId("alert-banner");
    if (!crashed.length) { box.innerHTML = ""; return; }
    var names = crashed.map(function (service) { return esc(service.name); }).join(", ");
    box.innerHTML = '<div class="alert"><span class="pip"></span>' +
      "<span><strong>" + crashed.length + " service" + (crashed.length > 1 ? "s" : "") + " crashed</strong> — " + names +
      ". Check the console and restart from the services page.</span></div>";
  }

  function renderStats(totals) {
    var list = services();
    var reporting = 0, ramSum = 0, tpsSum = 0, cpuSum = 0, cpuReports = 0;
    list.forEach(function (service) {
      var meta = metaOf(service.name);
      if (meta && meta.tps !== null && meta.tps !== undefined) {
        reporting++;
        ramSum += (meta.heap_used_mb || 0);
        tpsSum += meta.tps;
      }
      var proc = hostOf(service.name);
      if (proc && proc.cpu !== null && proc.cpu !== undefined) { cpuSum += proc.cpu; cpuReports++; }
    });
    var html = "";
    html += statCard("Players online", totals["players-online"] || 0, "across all services", deltaPlayers());
    html += statCard("Services running", (totals.online || 0) + " / " + (totals.services || 0), "provisioned · " + (totals["agents-online"] || 0) + " agents online");
    html += statCard("Groups", totals.groups || 0, "configured groups");
    html += statCard("Avg. backend TPS", reporting ? (tpsSum / reporting).toFixed(1) : "—", reporting + " backend(s) reporting");
    html += statCard("Backend memory", reporting ? Math.round(ramSum) + " MB" : "—", "heap used, all backends");
    html += statCard("Cloud CPU load", lastHost && lastHost.cpu !== null && lastHost.cpu !== undefined ? Math.round(lastHost.cpu * 100) + "%" : "—", "host CPU, all processes");
    html += statCard("Host uptime", lastHost ? fmtUptime(lastHost["uptime-seconds"]) : "—", lastHost && lastHost["os-name"] ? lastHost["os-name"] : "host system");
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
      trend = '<span class="trend ' + (delta > 0 ? "up" : "down") + '">' + (delta > 0 ? "▲ +" : "▼ ") + delta + "</span>";
    }
    return '<div class="card stat"><h2 style="margin-bottom:4px;">' + esc(label) + "</h2>" +
      '<div class="value">' + esc(value) + trend + "</div>" +
      '<div class="hint">' + esc(hint) + "</div></div>";
  }

  function renderGroupDistribution() {
    var totals = {};
    services().forEach(function (service) {
      if (service.state === "RUNNING") totals[service.group] = (totals[service.group] || 0) + 1;
    });
    var names = Object.keys(totals).sort(function (a, b) { return totals[b] - totals[a]; });
    var max = names.length ? totals[names[0]] : 1;
    byId("group-distribution").innerHTML = names.length ? names.map(function (name) {
      return '<div class="dist-row"><div class="d-name">' + esc(name) + "</div>" +
        '<div class="d-bar"><span style="width:' + Math.round(totals[name] * 100 / max) + '%"></span></div>' +
        '<div class="d-num">' + totals[name] + " running</div></div>";
    }).join("") : '<div class="empty">No running services yet.</div>';
  }

  // ---- players page -------------------------------------------------------
  function renderPlayers() {
    renderPlayerStats();
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
      if (!filter) return true;
      var detail = detailFind(row.server, row.name);
      return row.name.toLowerCase().indexOf(filter) >= 0 ||
        row.server.toLowerCase().indexOf(filter) >= 0 ||
        (detail && detail.world && detail.world.toLowerCase().indexOf(filter) >= 0);
    });
    byId("players-count").textContent = visible.length === rows.length
      ? "· " + rows.length : "· " + visible.length + " of " + rows.length;

    var options = Object.keys(running).sort().map(function (name) {
      return '<option value="' + esc(name) + '">' + esc(name) + "</option>";
    }).join("");

    byId("player-grid").innerHTML = visible.length ? visible.map(function (row) {
      var detail = detailFind(row.server, row.name);
      var initial = esc(row.name.charAt(0).toUpperCase());
      // No inline onerror (CSP): failed avatar loads are handled after insertion.
      var head = '<img class="head" alt="" loading="lazy" src="https://mc-heads.net/avatar/' + encodeURIComponent(row.name) + '/44">' +
        '<div class="fallback-head" style="display:none;">' + initial + "</div>";
      var meta = "on " + esc(row.server);
      if (detail && detail.world) meta += '<span class="sep">·</span>' + esc(detail.world);
      if (detail && detail.gamemode) meta += '<span class="sep">·</span>' + esc(detail.gamemode.toLowerCase());
      if (detail && detail.level !== null && detail.level !== undefined) meta += '<span class="sep">·</span>lvl ' + esc(detail.level);
      var ping = detail && detail.ping !== null && detail.ping !== undefined
        ? '<span class="ping ' + pingClass(detail.ping) + '"><span class="bar"></span>' + detail.ping + " ms</span>"
        : "";
      var vitals = "";
      if (detail && detail.health !== null && detail.health !== undefined) {
        vitals += '<span class="card-vital hp" title="Health">♥ ' + detail.health + "</span>";
      }
      if (detail && detail.food !== null && detail.food !== undefined) {
        vitals += '<span class="card-vital food" title="Hunger">🍖 ' + detail.food + "</span>";
      }
      return '<div class="player" tabindex="0" role="button" data-player="' + esc(row.name) + '" data-server="' + esc(row.server) + '">' +
        head +
        '<div class="who"><div class="name">' + esc(row.name) + "</div>" +
        '<div class="on">' + meta + "</div>" +
        (vitals ? '<div class="card-vitals">' + vitals + "</div>" : "") +
        "</div>" +
        ping +
        "</div>";
    }).join("") : '<div class="empty">No players online.</div>';

    Array.prototype.forEach.call(byId("player-grid").querySelectorAll(".player"), function (card) {
      function open() { openPlayerModal(card.getAttribute("data-player"), card.getAttribute("data-server")); }
      card.addEventListener("click", open);
      card.addEventListener("keydown", function (event) {
        if (event.key === "Enter" || event.key === " ") { event.preventDefault(); open(); }
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
    // Keep the open modal's stats fresh without rebuilding it — a full re-render would reset
    // scroll position, close the inventory poll and re-fetch the rendered view every cycle.
    if (openModalPlayer) {
      var input = byId("pm-message");
      var active = document.activeElement;
      var focused = active && byId("modal-root").contains(active);
      if ((!input || !input.value) && !focused) refreshModalStats();
    }
  }

  function renderPlayerStats() {
    var online = 0, pingSum = 0, pingCount = 0, best = null, worst = null;
    services().forEach(function (service) {
      var count = service["players-online"];
      if (service.state === "RUNNING" && count) online += count;
      (service["player-details"] || []).forEach(function (player) {
        if (player.ping === null || player.ping === undefined) return;
        pingSum += player.ping;
        pingCount++;
        if (best === null || player.ping < best) best = player.ping;
        if (worst === null || player.ping > worst) worst = player.ping;
      });
    });
    var html = "";
    html += statCard("Players online", online, "across running backends");
    html += statCard("Average ping", pingCount ? Math.round(pingSum / pingCount) + " ms" : "—", pingCount + " player(s) reporting");
    html += statCard("Best / worst ping", pingCount ? best + " / " + worst + " ms" : "—", "live round-trip times");
    html += statCard("Services running", (lastStatus.totals ? lastStatus.totals.online : 0) + " / " + (lastStatus.totals ? lastStatus.totals.services : 0), "open a card for player details");
    byId("player-stats").innerHTML = html;
  }
  byId("player-filter").addEventListener("input", renderPlayers);

  // ---- player details modal -------------------------------------------------
  var openModalPlayer = null; // { name, server } while the modal is visible
  var modalTab = "inventory"; // selected tab inside the player modal
  var invState = null;        // per-open inventory poll/render state
  var ITEM_API = "https://api.minecraftitems.xyz";

  function closeModal() {
    openModalPlayer = null;
    invState = null;
    byId("modal-root").innerHTML = "";
    document.body.classList.remove("modal-open");
    hideItemTip();
  }

  function openPlayerModal(playerName, serviceName) {
    openModalPlayer = { name: playerName, server: serviceName };
    invState = null;
    document.body.classList.add("modal-open"); // nothing scrolls behind the dialog
    var service = detailOf(serviceName);
    var detail = detailFind(serviceName, playerName);
    var uuid = detail ? detail.uuid : null;
    var render = '<img alt="" src="https://mc-heads.net/body/' + encodeURIComponent(uuid || playerName) + '/left">';

    var stats = buildModalStats(service, detail);

    byId("modal-root").innerHTML =
      '<div class="modal-backdrop" id="modal-backdrop"><div class="player-modal" role="dialog" aria-modal="true" aria-label="Player details">' +
        '<div class="pm-top"><button class="pm-close" id="pm-close" title="Close (Esc)">✕</button>' +
          '<div class="pm-hero">' +
            '<div class="pm-render">' + render + "</div>" +
            '<div class="pm-id">' +
              '<div class="pm-name">' + esc(playerName) + "</div>" +
              '<div class="pm-sub">online on <strong>' + esc(serviceName) + "</strong></div>" +
              '<div class="pm-uuid"><span class="dim">UUID</span> ' +
                (uuid ? "<code>" + esc(uuid) + "</code>" : '<span class="dim">not reported</span>') + "</div>" +
              '<div class="pm-links">' +
                '<a href="https://namemc.com/profile/' + encodeURIComponent(playerName) + '" target="_blank" rel="noopener noreferrer">NameMC</a>' +
                '<a href="https://laby.net/@' + encodeURIComponent(playerName) + '" target="_blank" rel="noopener noreferrer">laby.net</a>' +
              "</div>" +
            "</div>" +
            '<div class="pm-vitals">' + vitalsHtml(detail) + "</div>" +
          "</div>" +
        "</div>" +
        // The scroll region owns stats + tabs + tab body; hero and close button stay pinned.
        '<div class="pm-scroll">' +
          '<div class="pm-stats">' + stats + "</div>" +
          '<div class="pm-tabs">' +
            '<button class="tab' + (modalTab === "inventory" ? " active" : "") + '" data-mtab="inventory">Inventory</button>' +
            '<button class="tab' + (modalTab === "details" ? " active" : "") + '" data-mtab="details">Details</button>' +
            '<button class="tab' + (modalTab === "actions" ? " active" : "") + '" data-mtab="actions">Actions</button>' +
          "</div>" +
          '<div class="pm-body" id="pm-body"></div>' +
        "</div>" +
      "</div></div>";

    bindPlayerModal(playerName, serviceName);
    showModalTab(modalTab);
  }

  /** One delegated listener set per open: tabs, actions, transfer select, Enter-to-send. */
  function bindPlayerModal(playerName, serviceName) {
    var backdrop = byId("modal-backdrop");
    backdrop.addEventListener("click", function (event) {
      if (event.target === backdrop) { closeModal(); return; }
      var target = event.target.closest ? event.target.closest("[data-mtab],#pm-close,#pm-send,#pm-kick,#pm-copy-coords") : null;
      if (!target) return;
      if (target.id === "pm-close") closeModal();
      else if (target.id === "pm-send") sendPlayerMessage(playerName);
      else if (target.id === "pm-kick") kickPlayer(playerName, serviceName);
      else if (target.id === "pm-copy-coords") copyCoords();
      else if (target.hasAttribute("data-mtab")) showModalTab(target.getAttribute("data-mtab"));
    });
    backdrop.addEventListener("change", function (event) {
      if (event.target.id !== "pm-transfer") return;
      var select = event.target;
      var target = select.value;
      if (!target) return;
      api("/bridge/players", { method: "POST", body: form({ player: [playerName], action: ["transfer"], target: [target] }) })
        .then(function () { toast("Sending " + playerName + " to " + target + "…"); })
        .catch(function (error) { toast("Transfer failed: " + error.message); });
      select.value = "";
    });
    backdrop.addEventListener("keydown", function (event) {
      if (event.target.id === "pm-message" && event.key === "Enter") sendPlayerMessage(playerName);
    });
    // CSP-safe body-render fallback: swap to the letter tile when the CDN image fails.
    Array.prototype.forEach.call(backdrop.querySelectorAll(".pm-render img"), function (image) {
      image.addEventListener("error", function () {
        image.outerHTML = '<div class="fallback-head">' + esc(String(playerName).charAt(0).toUpperCase()) + "</div>";
      });
    });
    var first = byId("pm-close");
    if (first) first.focus();
  }

  function sendPlayerMessage(playerName) {
    var input = byId("pm-message");
    if (!input) return;
    var text = input.value.trim();
    if (!text) return;
    api("/bridge/players", { method: "POST", body: form({ player: [playerName], action: ["message"], lines: [text] }) })
      .then(function () { toast("Message sent to " + playerName + "."); input.value = ""; })
      .catch(function (error) { toast("Message failed: " + error.message); });
  }

  function kickPlayer(playerName, serviceName) {
    var reason = window.prompt("Kick " + playerName + " from " + serviceName + " — reason:", "Kicked from the dashboard");
    if (reason === null) return;
    api("/bridge/players", { method: "POST", body: form({ player: [playerName], action: ["kick"], reason: [reason || "Kicked from the dashboard"] }) })
      .then(function () { toast(playerName + " was kicked."); closeModal(); refresh(); })
      .catch(function (error) { toast("Kick failed: " + error.message); });
  }

  /** Copies the open player's XYZ to the clipboard (Details tab). */
  function copyCoords() {
    if (!openModalPlayer) return;
    var detail = detailFind(openModalPlayer.server, openModalPlayer.name);
    if (!detail || detail.x === null || detail.x === undefined) return;
    var text = Math.round(detail.x) + " " + Math.round(detail.y) + " " + Math.round(detail.z);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { toast("Copied: " + text); });
    } else {
      toast(text); // clipboard unavailable (insecure context) — show the coordinates
    }
  }

  /** The stat-tile grid under the hero, shared by the initial render and background refreshes. */
  function buildModalStats(service, detail) {
    var stats = "";
    stats += modalStat("Server", esc(openModalPlayer.server), service ? esc(service.group) + " · " + esc(service.type) : "");
    stats += modalStat("World", detail && detail.world ? esc(detail.world) : "—", "current world");
    stats += modalStat("Gamemode", detail && detail.gamemode ? esc(detail.gamemode.toLowerCase()) : "—", "player state");
    stats += modalStat("Ping", detail && detail.ping !== null && detail.ping !== undefined
      ? '<span class="' + pingClass(detail.ping) + '">' + esc(fmtPing(detail.ping)) + "</span>"
      : "—", "round-trip to this backend");
    if (service) {
      stats += modalStat("TPS", esc(fmtTps(service.tps)), "last 1m · 20 is ideal", tpsClass(service.tps));
      stats += modalStat("Memory", service.ram_usage === null || service.ram_usage === undefined ? "—" :
        Math.round(service.ram_usage * 100) + "%",
        service["heap-used-mb"] !== null && service["heap-used-mb"] !== undefined
          ? Math.round(service["heap-used-mb"]) + " / " + Math.round(service["heap-max-mb"] || 0) + " MB" : "server heap",
        ramClass(service.ram_usage));
      stats += modalStat("Server CPU", esc(fmtCpu(service.cpu)), "of the backend JVM", cpuClass(service.cpu));
      stats += modalStat("Port", esc(service.port), service["agent-online"] ? "agent v" + esc(service["agent-version"]) : "no agent reporting");
    }
    return stats;
  }

  /** Background refresh: updates only the open modal's stat tiles — scroll position and tabs stay. */
  function refreshModalStats() {
    if (!openModalPlayer) return;
    var grid = byId("modal-root").querySelector(".pm-stats");
    if (!grid) return;
    grid.innerHTML = buildModalStats(detailOf(openModalPlayer.server), detailFind(openModalPlayer.server, openModalPlayer.name));
  }

  function modalStat(label, value, hint, stateClass) {
    var valueClass = stateClass ? ' class="v ' + stateClass + '"' : '"v"';
    return '<div class="pm-stat"><div class="k">' + esc(label) + "</div>" +
      "<div" + valueClass + ">" + value + "</div>" +
      '<div class="s">' + esc(hint) + "</div></div>";
  }
  // ---- player modal helpers ------------------------------------------------

  /** Health/hunger/XP bars next to the skin render; fields the agent omitted stay hidden. */
  function vitalsHtml(detail) {
    if (!detail) return "";
    var rows = "";
    if (detail.health !== null && detail.health !== undefined) rows += vitalBar("Health", detail.health, 20, "");
    if (detail.food !== null && detail.food !== undefined) rows += vitalBar("Food", detail.food, 20, "");
    if (detail.level !== null && detail.level !== undefined) {
      rows += '<div class="vital"><span class="vk">Level</span><span class="vv">' + esc(detail.level) + "</span></div>";
    }
    if (detail.xp !== null && detail.xp !== undefined) rows += vitalBar("XP", Math.round(detail.xp * 100), 100, "%");
    return rows;
  }

  function vitalBar(label, value, max, unit) {
    var pct = Math.max(0, Math.min(100, Math.round(value * 100 / (max || 1))));
    var kind = label === "Health" ? (pct <= 30 ? "bad" : pct <= 60 ? "warn" : "good") :
               label === "Food" ? (pct <= 30 ? "warn" : "good") : "xp";
    return '<div class="vital"><span class="vk">' + esc(label) + '</span>' +
      '<span class="vbar"><span class="fill ' + kind + '" style="width:' + pct + '%"></span></span>' +
      '<span class="vv">' + esc(String(value) + unit) + "</span></div>";
  }

  /** Switches the modal's scrollable body between the three tabs. */
  function showModalTab(tab) {
    modalTab = tab;
    if (tab !== "inventory") invState = null;
    Array.prototype.forEach.call(byId("modal-root").querySelectorAll("[data-mtab]"), function (button) {
      button.classList.toggle("active", button.getAttribute("data-mtab") === tab);
    });
    var body = byId("pm-body");
    if (!body || !openModalPlayer) return;
    if (tab === "details") {
      body.innerHTML = detailsHtml(detailFind(openModalPlayer.server, openModalPlayer.name));
    } else if (tab === "actions") {
      body.innerHTML = actionsHtml(openModalPlayer.name, openModalPlayer.server);
    } else {
      body.innerHTML = '<div class="inv-loading">Waiting for an inventory snapshot from ' + esc(openModalPlayer.server) + "…</div>";
      requestInventory(openModalPlayer.name, openModalPlayer.server);
    }
  }

  /** Asks the backend agent for a snapshot (queued via the cloud) and polls for the result. */
  function requestInventory(playerName, serviceName) {
    var token = { cancelled: false };
    invState = token;
    var attempt = 0;
    function poll() {
      if (token.cancelled || invState !== token || !openModalPlayer || modalTab !== "inventory") return;
      if (openModalPlayer.name !== playerName || openModalPlayer.server !== serviceName) return;
      api("/bridge/players/inventory?service=" + encodeURIComponent(serviceName) + "&player=" + encodeURIComponent(playerName))
        .then(function (snapshot) {
          if (token.cancelled || invState !== token) return;
          renderInventory(snapshot);
        })
        .catch(function () {
          if (token.cancelled || invState !== token) return;
          attempt++;
          if (attempt > 8) {
            var body = byId("pm-body");
            if (body) body.innerHTML = '<div class="inv-empty">No snapshot arrived — is an up-to-date agent online on ' + esc(serviceName) + "?</div>";
            return;
          }
          if (attempt === 1) {
            api("/bridge/players", { method: "POST", body: form({ player: [playerName], action: ["inventory"] }) }).catch(function () {});
          }
          setTimeout(poll, 2500);
        });
    }
    poll();
  }

  /** True Minecraft-shaped storage: 9 hotbar slots + 27 backpack slots, no double mapping. */
  function renderInventory(snapshot) {
    if (!openModalPlayer || modalTab !== "inventory") return;
    var body = byId("pm-body");
    if (!body) return;
    var bySlot = {};
    var itemCount = 0;
    (snapshot.items || []).forEach(function (item) {
      if (!bySlot[item.slot]) itemCount++;
      bySlot[item.slot] = item;
    });
    function cell(slot, label) {
      var item = bySlot[slot];
      if (!item) return '<div class="inv-cell empty" title="' + esc(label) + '"></div>';
      var dataAttr = encodeURIComponent(JSON.stringify([
        item.name || materialLabel(item.material), item.material, item.count,
        item.durability === null || item.durability === undefined ? null : item.durability,
        item.lore || [], item.enchantments || [],
      ]));
      var icon = '<img alt="" loading="lazy" src="' + itemIconUrl(item.material) + '">' +
        '<div class="fallback-item">▚</div>';
      var durBar = "";
      if (item.durability !== null && item.durability !== undefined && item.durability < 100) {
        var kind = item.durability <= 20 ? "bad" : item.durability <= 50 ? "warn" : "";
        durBar = '<span class="inv-dur"><span class="' + kind + '" style="width:' + Math.max(4, item.durability) + '%"></span></span>';
      }
      return '<div class="inv-cell filled" data-tip="' + dataAttr + '">' + icon +
        (item.count > 1 ? '<span class="inv-count">' + esc(item.count) + "</span>" : "") +
        durBar +
        "</div>";
    }
    var storage = "";
    for (var slot = 10; slot <= 36; slot++) storage += cell(String(slot), "Inventory");
    var hotbar = "";
    for (var bar = 1; bar <= 9; bar++) hotbar += cell(String(bar), "Hotbar");
    var captured = snapshot["captured-at"] ? new Date(snapshot["captured-at"] * 1000).toLocaleTimeString() : "";
    var anyItems = itemCount > 0;
    body.innerHTML =
      '<div class="inv-wrap">' +
        '<div class="inv-side">' +
          '<div class="inv-side-label">Armor</div>' +
          cell("helmet", "Helmet") + cell("chestplate", "Chestplate") +
          cell("leggings", "Leggings") + cell("boots", "Boots") +
          '<div class="inv-side-label" style="margin-top:8px;">Off-hand</div>' +
          cell("offhand", "Off-hand") +
        "</div>" +
        '<div class="inv-main">' +
          (anyItems ? "" : '<div class="inv-empty" style="padding:6px 0 10px;">The inventory was empty when the snapshot was captured.</div>') +
          '<div class="inv-grid">' + storage + "</div>" +
          '<div class="inv-grid hotbar">' + hotbar + "</div>" +
        "</div>" +
      "</div>" +
      '<div class="inv-meta">Snapshot captured ' + esc(captured || "just now") + " · hover an item for details</div>" +
      '<div class="inv-render" id="inv-render"></div>';
    loadRenderedInventory(snapshot, bySlot);
    // CSP-safe item-icon fallback: the letter tile shows when the texture CDN is unreachable.
    Array.prototype.forEach.call(body.querySelectorAll(".inv-cell img"), function (image) {
      image.addEventListener("error", function () {
        image.style.display = "none";
        var fallback = image.nextElementSibling;
        if (fallback) fallback.style.display = "flex";
      });
    });
  }

  /** Material id → readable label: netherite_pickaxe → Netherite Pickaxe. */
  function materialLabel(material) {
    return String(material || "").split("_").map(function (word) {
      return word.charAt(0).toUpperCase() + word.slice(1);
    }).join(" ");
  }

  /** Item icon from api.minecraftitems.xyz (official-style textures, CSP-allowed). */
  function itemIconUrl(material) {
    return ITEM_API + "/api/item/" + encodeURIComponent(String(material || "stone").toLowerCase()) + "/size=3";
  }

  /** Second look, straight from the same API: a full player-inventory screen with the player's
   * skin head, armor, storage and hotbar rendered by the API itself. Built from the snapshot
   * slots; skipped when the browser cannot reach the API (the slot grid above still shows). */
  function loadRenderedInventory(snapshot, bySlot) {
    var holder = byId("inv-render");
    if (!holder) return;
    var pick = function (slot) {
      var item = bySlot[slot];
      return item ? materialForApi(item.material) : undefined;
    };
    var inventory = {};
    var hasInventory = false;
    for (var slot = 10; slot <= 36; slot++) {
      var material = pick(String(slot));
      if (material) { inventory[String(slot - 10)] = material; hasInventory = true; }
    }
    var hotbar = {};
    var hasHotbar = false;
    for (var bar = 1; bar <= 9; bar++) {
      var hotMaterial = pick(String(bar));
      if (hotMaterial) { hotbar[String(bar - 1)] = hotMaterial; hasHotbar = true; }
    }
    var payload = {
      playerName: openModalPlayer ? openModalPlayer.name : undefined,
      helmet: pick("helmet"),
      chestplate: pick("chestplate"),
      leggings: pick("leggings"),
      boots: pick("boots"),
      offhand: pick("offhand"),
      inventory: hasInventory ? inventory : undefined,
      hotbar: hasHotbar ? hotbar : undefined,
    };
    if (!hasInventory && !hasHotbar && !payload.helmet && !payload.chestplate &&
        !payload.leggings && !payload.boots && !payload.offhand) return;
    fetch(ITEM_API + "/api/gui/player?scale=3", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }).then(function (response) {
      if (!response.ok) throw new Error("render failed");
      return response.blob();
    }).then(function (blob) {
      if (!byId("inv-render")) return;
      var image = document.createElement("img");
      image.alt = "Rendered inventory";
      image.src = URL.createObjectURL(blob);
      holder.appendChild(image);
      var note = document.createElement("div");
      note.className = "inv-render-note";
      note.textContent = "Rendered view — the same snapshot as the grid above.";
      holder.appendChild(note);
    }).catch(function () {
      // Grid view above already covers it; the render is a bonus.
    });
  }

  /** Strips a namespaced id (minecraft:diamond_sword) down to what the API expects. */
  function materialForApi(material) {
    return String(material || "").replace(/^minecraft:/, "").toLowerCase();
  }

  // ---- floating item tooltip ------------------------------------------------
  var itemTip = null;

  function hideItemTip() {
    if (itemTip) { itemTip.style.display = "none"; }
  }

  /** One document-level tooltip element, fed by data-tip attributes on inventory cells. */
  function bindItemTip() {
    itemTip = document.createElement("div");
    itemTip.className = "item-tip";
    document.body.appendChild(itemTip);
    document.addEventListener("mousemove", function (event) {
      var cell = event.target && event.target.closest ? event.target.closest(".inv-cell.filled[data-tip]") : null;
      if (!cell || !openModalPlayer) { hideItemTip(); return; }
      var parsed;
      try { parsed = JSON.parse(decodeURIComponent(cell.getAttribute("data-tip"))); } catch (error) { return; }
      var name = parsed[0], material = parsed[1], count = parsed[2], durability = parsed[3], lore = parsed[4], enchants = parsed[5];
      var html = '<div class="t-name">' + esc(name) + (count > 1 ? " ×" + esc(count) : "") + "</div>" +
        '<div class="t-sub">' + esc(materialLabel(material)) +
        (durability !== null && durability !== undefined ? " · " + esc(durability) + "% durability" : "") + "</div>";
      (enchants || []).forEach(function (enchant) {
        html += '<div class="t-ench">✦ ' + esc(enchantmentLabel(enchant.type)) + " " + esc(enchant.level) + "</div>";
      });
      (lore || []).forEach(function (line) {
        html += '<div class="t-lore">' + esc(line) + "</div>";
      });
      itemTip.innerHTML = html;
      itemTip.style.display = "block";
      var x = Math.min(event.clientX + 14, window.innerWidth - itemTip.offsetWidth - 10);
      var y = Math.min(event.clientY + 16, window.innerHeight - itemTip.offsetHeight - 10);
      itemTip.style.left = Math.max(6, x) + "px";
      itemTip.style.top = Math.max(6, y) + "px";
    });
  }

  /** minecraft:sharpness → Sharpness for the tooltip line. */
  function enchantmentLabel(type) {
    var id = String(type || "").replace(/^minecraft:/, "");
    return id.split("_").map(function (word) {
      return word.charAt(0).toUpperCase() + word.slice(1);
    }).join(" ");
  }

  /** Deep-dive: connection, client, movement state and links, grouped into cards. */
  function detailsHtml(detail) {
    if (!detail) {
      return '<div class="inv-empty">No live details reported for this player yet.</div>';
    }
    var cards = "";
    function card(title, rows) {
      if (!rows.length) return;
      var body = rows.map(function (row) {
        return '<div class="extra-row"><span class="ek">' + esc(row[0]) + '</span><span class="ev">' + row[1] + "</span></div>";
      }).join("");
      cards += '<div class="extra-group"><h3>' + esc(title) + "</h3>" + body + "</div>";
    }
    function yesNo(value) {
      return value === true ? "yes" : value === false ? "no" : null;
    }
    var connection = [];
    if (detail.ping !== null && detail.ping !== undefined) {
      connection.push(["Ping", '<span class="' + pingClass(detail.ping) + '">' + esc(fmtPing(detail.ping)) + "</span>"]);
    }
    if (detail.address) connection.push(["Address", "<code>" + esc(detail.address) + "</code>"]);
    if (detail["client-brand"]) connection.push(["Client brand", esc(detail["client-brand"])]);
    card("Connection", connection);

    var state = [];
    if (detail.gamemode) state.push(["Gamemode", esc(detail.gamemode.toLowerCase())]);
    var flying = yesNo(detail.flying);
    if (flying !== null) state.push(["Flying", flying]);
    var sneaking = yesNo(detail.sneaking);
    if (sneaking !== null) state.push(["Sneaking", sneaking]);
    var sprinting = yesNo(detail.sprinting);
    if (sprinting !== null) state.push(["Sprinting", sprinting]);
    var gliding = yesNo(detail.gliding);
    if (gliding !== null) state.push(["Gliding", gliding]);
    var sleeping = yesNo(detail.sleeping);
    if (sleeping !== null) state.push(["Sleeping", sleeping]);
    if (detail["allowed-flight"] !== null && detail["allowed-flight"] !== undefined) {
      state.push(["Flight allowed", yesNo(detail["allowed-flight"]) || "no"]);
    }
    if (detail["in-vehicle"]) state.push(["Riding", esc(String(detail["in-vehicle"]).toLowerCase())]);
    if (detail.op !== null && detail.op !== undefined) state.push(["Operator", yesNo(detail.op)]);
    card("State", state);

    var position = [];
    if (detail.x !== null && detail.x !== undefined) {
      position.push(["World", esc(detail.world || "—")]);
      position.push(["Coordinates", Math.round(detail.x) + ", " + Math.round(detail.y) + ", " + Math.round(detail.z)]);
      position.push(["Copy", '<button class="btn small" id="pm-copy-coords">Copy XYZ</button>']);
    }
    card("Position", position);

    var account = [];
    if (detail["first-played"]) account.push(["First played", new Date(detail["first-played"]).toLocaleString()]);
    if (detail.uuid) account.push(["UUID", "<code>" + esc(detail.uuid) + "</code>"]);
    card("Account", account);

    if (!cards) {
      return '<div class="inv-empty">The agent on this server did not report extended metadata yet (older agent version).</div>';
    }
    return '<div class="extras">' + cards + "</div>" +
      '<div class="pm-links" style="padding:12px 2px 8px;">' +
        '<a href="https://namemc.com/profile/' + encodeURIComponent(openModalPlayer.name) + '" target="_blank" rel="noopener noreferrer">NameMC profile</a>' +
        '<a href="https://laby.net/@' + encodeURIComponent(openModalPlayer.name) + '" target="_blank" rel="noopener noreferrer">laby.net</a>' +
      "</div>";
  }

  /** Actions tab: the same live actions the old footer held, with room to explain them. */
  function actionsHtml(playerName, serviceName) {
    var transferOptions = services()
      .filter(function (candidate) { return candidate.state === "RUNNING" && candidate.name !== serviceName; })
      .map(function (candidate) { return '<option value="' + esc(candidate.name) + '">' + esc(candidate.name) + "</option>"; })
      .join("");
    return '<div class="extras">' +
      '<div class="extra-group"><h3>Message</h3>' +
        '<div class="pm-action" style="margin-bottom:8px;"><input type="text" id="pm-message" placeholder="Message ' + esc(playerName) + '…" style="flex:1;"></div>' +
        '<button class="btn small" id="pm-send">Send</button>' +
        '<div class="inv-render-note">Delivered by the backend agent, in-game and private.</div>' +
      "</div>" +
      '<div class="extra-group"><h3>Move</h3>' +
        '<div class="pm-action"><select id="pm-transfer"><option value="">Move to…</option>' + transferOptions + "</select></div>" +
        '<div class="inv-render-note">Runs through the proxy console; the player is sent to the chosen backend.</div>' +
      "</div>" +
      '<div class="extra-group"><h3>Danger zone</h3>' +
        '<button class="btn small danger" id="pm-kick">Kick from ' + esc(serviceName) + "</button>" +
        '<div class="inv-render-note">Disconnects the player with a reason you can edit.</div>' +
      "</div>" +
    "</div>";
  }

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && openModalPlayer) closeModal();
  });
  bindItemTip();

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
        (tab === "ALL" ? "All" : tab.charAt(0) + tab.slice(1).toLowerCase()) + '<span class="n">' + counts[tab] + "</span></button>";
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
      var proc = hostOf(service.name);
      var tps = meta ? meta.tps : null;
      var ram = meta ? meta.ram_usage : null;
      var cpu = proc ? proc.cpu : null;
      var heap = meta && meta.heap_used_mb !== null && meta.heap_used_mb !== undefined
        ? Math.round(meta.heap_used_mb) + " / " + Math.round(meta.heap_max_mb || 0) + " MB" : "";
      var players = service["players-online"];
      var detail = "";
      if (service["agent-version"]) detail += " · agent v" + esc(service["agent-version"]);
      if (service.restarts) detail += " · " + esc(service.restarts) + " restarts";
      if (service["last-error"]) detail += ' · <span class="dim" title="' + esc(service["last-error"]) + '">error</span>';
      var actions =
        '<button class="btn small" data-act="start" data-name="' + esc(service.name) + '">Start</button> ' +
        '<button class="btn small" data-act="restart" data-name="' + esc(service.name) + '">Restart</button> ' +
        '<button class="btn small danger" data-act="stop" data-name="' + esc(service.name) + '">Stop</button>';
      return '<tr class="clickable" data-service="' + esc(service.name) + '">' +
        "<td>" + esc(service.name) + detail + "</td>" +
        "<td>" + esc(service.group) + "</td>" +
        '<td><span class="badge ' + esc(service.state) + '"><span class="pip"></span>' + esc(service.state) + "</span></td>" +
        "<td>" + esc(service.type) + "</td>" +
        '<td class="num">' + esc(service.port) + "</td>" +
        '<td class="num ' + cpuClass(cpu) + '">' + esc(fmtCpu(cpu)) + "</td>" +
        '<td class="num ' + tpsClass(tps) + '">' + esc(fmtTps(tps)) + "</td>" +
        "<td>" + (ram === null || ram === undefined ? '<span class="dim">—</span>' :
          '<span class="meter"><span class="' + ramClass(ram) + '" style="width:' + Math.round(ram * 100) + '%"></span></span>' +
          ' <span class="dim" style="font-size:11px;">' + esc(heap) + "</span>") + "</td>" +
        '<td class="num">' + esc(players === null || players === undefined ? "—" : players) + "</td>" +
        '<td style="text-align:right;">' + actions + "</td>" +
        "</tr>";
    }).join("");
    byId("services-body").innerHTML = body || '<tr><td colspan="10" class="empty">No services in this view.</td></tr>';

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
  var IS_PROXY = { VELOCITY: true, BUNGEECORD: true };
  function renderGroups() {
    var list = groups().slice().sort(function (a, b) { return a.name.localeCompare(b.name); });
    var runningByGroup = {}, provisionedByGroup = {}, playersByGroup = {};
    services().forEach(function (service) {
      provisionedByGroup[service.group] = (provisionedByGroup[service.group] || 0) + 1;
      playersByGroup[service.group] = (playersByGroup[service.group] || 0) + (service["players-online"] || 0);
      if (service.state === "RUNNING" || service.state === "STARTING") {
        runningByGroup[service.group] = (runningByGroup[service.group] || 0) + 1;
      }
    });
    var kinds = { ALL: list.length, BACKEND: 0, PROXY: 0 };
    list.forEach(function (group) { kinds[IS_PROXY[group.type] ? "PROXY" : "BACKEND"]++; });
    byId("group-tabs").innerHTML = ["ALL", "BACKEND", "PROXY"].map(function (tab) {
      return '<button class="tab' + (groupTab === tab ? " active" : "") + '" data-tab="' + tab + '">' +
        (tab === "ALL" ? "All" : tab.charAt(0) + tab.slice(1).toLowerCase() + "s") + '<span class="n">' + kinds[tab] + "</span></button>";
    }).join("");
    Array.prototype.forEach.call(byId("group-tabs").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        groupTab = button.getAttribute("data-tab");
        renderGroups();
      });
    });

    byId("group-cards").innerHTML = list.map(function (group) {
      var running = runningByGroup[group.name] || 0;
      var provisioned = provisionedByGroup[group.name] || 0;
      var desired = group["min-services"] || 0;
      var max = group["max-services"] || 0;
      var players = playersByGroup[group.name] || 0;
      var pct = max > 0 ? Math.min(100, Math.round(provisioned * 100 / max)) : 0;
      return '<div class="card group-card">' +
        '<div class="g-head"><div class="g-name">' + esc(group.name) + "</div>" +
        '<span class="badge ' + (IS_PROXY[group.type] ? "" : "RUNNING") + '"><span class="pip"></span>' + esc(group.type) + "</span></div>" +
        '<div class="g-meta">' + esc(group.version) + (group.static ? " · static" : "") + "</div>" +
        '<div class="g-row"><span class="k">Running</span><span>' + running + " / " + desired + "</span></div>" +
        '<div class="g-row"><span class="k">Provisioned</span><span>' + provisioned + " / " + max + " max</span></div>" +
        '<div class="g-row"><span class="k">Players</span><span>' + players + "</span></div>" +
        '<div class="g-bar"><span style="width:' + pct + '%"></span></div>' +
        '<div style="margin-top:12px;text-align:right;">' +
        (provisioned >= max && running >= desired
          ? '<span class="dim" style="font-size:12px;">at capacity</span>'
          : '<button class="btn small" data-group="' + esc(group.name) + '">Start another</button>') +
        "</div></div>";
    }).join("");
    Array.prototype.forEach.call(byId("group-cards").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        runCloudCommand(["group", "start", button.getAttribute("data-group")]);
      });
    });

    var visible = list.filter(function (group) {
      if (groupTab === "ALL") return true;
      return groupTab === (IS_PROXY[group.type] ? "PROXY" : "BACKEND");
    });
    byId("groups-body").innerHTML = visible.length ? visible.map(function (group) {
      var running = runningByGroup[group.name] || 0;
      var provisioned = provisionedByGroup[group.name] || 0;
      var desired = group["min-services"] || 0;
      var max = group["max-services"] || 0;
      var atCapacity = provisioned >= max;
      return "<tr>" +
        "<td>" + esc(group.name) + "</td>" +
        "<td>" + esc(group.type) + "</td>" +
        "<td>" + esc(group.version) + "</td>" +
        '<td class="num">' + running + "</td>" +
        '<td class="num">' + desired + "</td>" +
        '<td class="num">' + provisioned + "</td>" +
        '<td class="num">' + max + "</td>" +
        '<td class="num">' + (playersByGroup[group.name] || 0) + "</td>" +
        '<td style="text-align:right;">' +
        (atCapacity
          ? '<span class="dim" style="font-size:12px;">at max</span>'
          : '<button class="btn small" data-group-row="' + esc(group.name) + '">Start another</button>') +
        "</td></tr>";
    }).join("") : '<tr><td colspan="9" class="empty">No groups in this view.</td></tr>';

    Array.prototype.forEach.call(byId("groups-body").querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        runCloudCommand(["group", "start", button.getAttribute("data-group-row")]);
      });
    });
  }

  // ---- charts (smooth curved gradient areas with floating tooltip) ----------
  var CHARTS = [
    { id: "players", title: "Players online", static: true, series: [
      { label: "Players", pick: function (p) { return p.players; }, color: "#22d3ee" },
    ] },
    { id: "tps", title: "Worst backend TPS", series: [
      { label: "TPS", pick: function (p) { return p.tps; }, color: "#34d399" },
    ], max: 20 },
    { id: "ram", title: "Avg. backend memory", suffix: "%", series: [
      { label: "Memory", pick: function (p) { return p.ram === null || p.ram === undefined ? null : p.ram * 100; }, color: "#fbbf24" },
    ], max: 100 },
    { id: "services", title: "Services running", series: [
      { label: "Running", pick: function (p) { return p.running; }, color: "#a78bfa" },
      { label: "Provisioned", pick: function (p) { return p.services; }, color: "#52525b", lineOnly: true },
    ] },
    { id: "cpu", title: "Host CPU load", static: true, suffix: "%", series: [
      { label: "CPU", pick: function (p) { return p.cpu === null || p.cpu === undefined ? null : p.cpu * 100; }, color: "#22d3ee" },
    ], max: 100 },
    { id: "sysram", title: "System memory", static: true, suffix: "%", series: [
      { label: "System RAM", pick: function (p) { return p.sysram === null || p.sysram === undefined ? null : p.sysram * 100; }, color: "#a78bfa" },
      { label: "Cloud JVM heap", pick: function (p) { return p.jvmheap === null || p.jvmheap === undefined ? null : p.jvmheap * 100; }, color: "#22d3ee" },
    ], max: 100 },
  ];
  var RANGES = [
    { label: "15m", seconds: 900 },
    { label: "30m", seconds: 1800 },
    { label: "1h", seconds: 3600 },
  ];
  CHARTS.forEach(function (chart) {
    if (chartRanges[chart.id] === undefined) chartRanges[chart.id] = 1800;
    hover[chart.id] = null;
  });

  function historyPoints() {
    return (lastMetrics && lastMetrics.history && lastMetrics.history.points) || [];
  }

  function buildCharts() {
    if (chartsBuilt) return;
    chartsBuilt = true;
    // Charts not statically present in the HTML get their card built here.
    byId("charts").innerHTML = CHARTS.filter(function (chart) { return !chart.static; }).map(function (chart) {
      var tabs = RANGES.map(function (range) {
        return '<button data-range="' + range.seconds + '"' +
          (chartRanges[chart.id] === range.seconds ? ' class="active"' : "") + ">" + range.label + "</button>";
      }).join("");
      return '<div class="card"><div class="card-head"><h2>' + esc(chart.title) + "</h2>" +
        '<div class="range-tabs" data-chart="' + chart.id + '">' + tabs + "</div></div>" +
        '<div class="chart-wrap"><canvas id="chart-' + chart.id + '"></canvas>' +
        '<div class="tooltip" id="tip-' + chart.id + '"></div></div></div>';
    }).join("");
    CHARTS.forEach(function (chart) {
      var canvas = byId("chart-" + chart.id);
      if (!canvas) return;
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
    var rows = chart.series.map(function (series) {
      var value = series.pick(points[index]);
      return '<div class="row"><span class="swatch" style="background:' + series.color + '"></span>' +
        esc(series.label) + ' <span class="t-val">' + esc(fmtChartValue(value, chart.suffix)) + "</span></div>";
    }).join("");
    tip.innerHTML = '<div class="t-label">' + new Date(points[index].t * 1000).toLocaleTimeString() + "</div>" + rows;
    tip.style.display = "block";
    var wrap = canvas.parentElement;
    var left = Math.min(Math.max(offsetX + 14, 0), wrap.clientWidth - tip.offsetWidth - 4);
    tip.style.left = left + "px";
    tip.style.top = "6px";
  }

  function fmtChartValue(value, suffix) {
    if (value === null || value === undefined) return "—";
    return (Math.round(value * 10) / 10) + (suffix || "");
  }

  function chartPad(width) {
    return { top: 12, right: width < 260 ? 12 : 44, bottom: 20, left: 12 };
  }

  /** Catmull-Rom spline through the given points, returned as canvas path commands. */
  function splinePath(pts) {
    if (pts.length < 3) {
      var simple = "";
      pts.forEach(function (p, i) { simple += (i === 0 ? "M" : "L") + p.x.toFixed(1) + " " + p.y.toFixed(1) + " "; });
      return simple;
    }
    var d = "M" + pts[0].x.toFixed(1) + " " + pts[0].y.toFixed(1) + " ";
    for (var i = 0; i < pts.length - 1; i++) {
      var p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
      var c1x = p1.x + (p2.x - p0.x) / 6, c1y = p1.y + (p2.y - p0.y) / 6;
      var c2x = p2.x - (p3.x - p1.x) / 6, c2y = p2.y - (p3.y - p1.y) / 6;
      d += "C" + c1x.toFixed(1) + " " + c1y.toFixed(1) + " " + c2x.toFixed(1) + " " + c2y.toFixed(1) + " " +
        p2.x.toFixed(1) + " " + p2.y.toFixed(1) + " ";
    }
    return d;
  }

  function drawChart(chart) {
    var canvas = byId("chart-" + chart.id);
    if (!canvas) return;
    var points = chartPoints(chart);
    var ratio = window.devicePixelRatio || 1;
    var width = canvas.clientWidth || 320;
    var height = canvas.parentElement.classList.contains("tall") ? 240 : 190;
    canvas.width = width * ratio;
    canvas.height = height * ratio;
    var context = canvas.getContext("2d");
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.scale(ratio, ratio);
    context.clearRect(0, 0, width, height);

    var pad = chartPad(width);
    var innerWidth = width - pad.left - pad.right;
    var innerHeight = height - pad.top - pad.bottom;

    var seriesValues = chart.series.map(function (series) {
      return points.map(series.pick);
    });
    var flat = [];
    seriesValues.forEach(function (values) {
      values.forEach(function (value) { if (value !== null && value !== undefined) flat.push(value); });
    });
    var lo = 0;
    var hi = flat.length ? Math.max.apply(null, flat) : 1;
    if (chart.max) hi = Math.max(hi, chart.max);
    if (hi === lo) hi = lo + 1;
    var span = hi - lo;

    function x(index) { return pad.left + (points.length <= 1 ? innerWidth / 2 : innerWidth * index / (points.length - 1)); }
    function y(value) { return pad.top + innerHeight - innerHeight * (value - lo) / span; }

    // grid + axis labels
    context.strokeStyle = "#1a1a20";
    context.lineWidth = 1;
    context.fillStyle = "#6f6f78";
    context.font = "10.5px ui-sans-serif, system-ui, sans-serif";
    for (var g = 0; g <= 4; g++) {
      var gy = pad.top + innerHeight * g / 4;
      context.beginPath(); context.moveTo(pad.left, gy); context.lineTo(pad.left + innerWidth, gy); context.stroke();
      if (width >= 260) context.fillText(String(Math.round((hi - span * g / 4) * 10) / 10), pad.left + innerWidth + 6, gy + 3);
    }
    if (points.length > 1 && width >= 300) {
      context.textAlign = "center";
      [0, Math.floor((points.length - 1) / 2), points.length - 1].forEach(function (index) {
        context.fillText(new Date(points[index].t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }), x(index), height - 6);
      });
      context.textAlign = "left";
    }

    // areas + smooth lines, split into contiguous runs around nulls
    chart.series.forEach(function (series, seriesIndex) {
      var values = seriesValues[seriesIndex];
      var runs = [];
      var current = null;
      values.forEach(function (value, index) {
        if (value === null || value === undefined) {
          if (current && current.length > 1) runs.push(current);
          current = null;
        } else {
          if (!current) current = [];
          current.push({ x: x(index), y: y(value) });
        }
      });
      if (current && current.length > 1) runs.push(current);

      runs.forEach(function (run) {
        var line = splinePath(run);
        if (!series.lineOnly) {
          var gradient = context.createLinearGradient(0, pad.top, 0, pad.top + innerHeight);
          gradient.addColorStop(0, series.color + "52");
          gradient.addColorStop(1, series.color + "05");
          var area = new Path2D(line +
            "L" + run[run.length - 1].x.toFixed(1) + " " + (pad.top + innerHeight) + " " +
            "L" + run[0].x.toFixed(1) + " " + (pad.top + innerHeight) + " Z");
          context.fillStyle = gradient;
          context.fill(area);
        }
        context.strokeStyle = series.color;
        context.lineWidth = series.lineOnly ? 1.2 : 2;
        context.setLineDash(series.lineOnly ? [4, 4] : []);
        context.lineJoin = "round";
        context.stroke(new Path2D(line));
        context.setLineDash([]);
      });
    });

    // hover crosshair + dots (drawn last so refreshes never hide it)
    var hoverIndex = hover[chart.id];
    if (hoverIndex !== null && hoverIndex !== undefined && points[hoverIndex]) {
      var hx = x(hoverIndex);
      context.strokeStyle = "#3f3f46";
      context.beginPath(); context.moveTo(hx, pad.top); context.lineTo(hx, pad.top + innerHeight); context.stroke();
      chart.series.forEach(function (series, seriesIndex) {
        var value = seriesValues[seriesIndex][hoverIndex];
        if (value === null || value === undefined) return;
        context.beginPath();
        context.arc(hx, y(value), 3.5, 0, Math.PI * 2);
        context.fillStyle = series.color;
        context.fill();
        context.strokeStyle = "#09090b";
        context.stroke();
      });
    }
  }

  function renderCharts() {
    // While the pointer hovers a chart, skip its data redraw so the tooltip stays stable.
    CHARTS.forEach(function (chart) {
      if (hover[chart.id] === null || hover[chart.id] === undefined) drawChart(chart);
    });
  }

  // ---- system page ----------------------------------------------------------
  function renderSystem() {
    var box = byId("system-stats");
    if (!lastHost) { box.innerHTML = ""; byId("system-details").innerHTML = ""; return; }
    var html = "";
    html += statCard("Host CPU load", lastHost.cpu === null || lastHost.cpu === undefined ? "—" : Math.round(lastHost.cpu * 100) + "%",
      lastHost["cores"] + " cores · " + (lastHost["process-cpu"] === null || lastHost["process-cpu"] === undefined ? "—" : Math.round(lastHost["process-cpu"] * 100) + "%") + " used by the cloud");
    html += statCard("System memory", lastHost["memory-used-mb"] === null || lastHost["memory-used-mb"] === undefined ? "—" :
      Math.round(lastHost["memory-used-mb"]) + " / " + Math.round(lastHost["memory-total-mb"] || 0) + " MB", "RAM of the root server");
    html += statCard("Cloud JVM heap", lastHost["jvm-used-mb"] === null || lastHost["jvm-used-mb"] === undefined ? "—" :
      Math.round(lastHost["jvm-used-mb"]) + " / " + Math.round(lastHost["jvm-max-mb"] || 0) + " MB", "controller process");
    html += statCard("Uptime", fmtUptime(lastHost["uptime-seconds"]), "since " + (lastHost["started-at"] ? new Date(lastHost["started-at"] * 1000).toLocaleString() : "—"));
    box.innerHTML = html;

    var rows = [
      ["Operating system", (lastHost["os-name"] || "—") + " " + (lastHost["os-version"] || "") + " (" + (lastHost["os-arch"] || "—") + ")"],
      ["Processor cores", lastHost["cores"] === undefined ? "—" : String(lastHost["cores"])],
      ["Cloud version", lastHost.version ? "v" + lastHost.version : "—"],
      ["Java version", lastHost["java-version"] || "—"],
      ["Bridge bind address", lastHost["bind-address"] || "—"],
      ["Heartbeat interval", lastHost["heartbeat-interval-seconds"] === undefined ? "—" : lastHost["heartbeat-interval-seconds"] + "s"],
      ["Reconciliation interval", lastHost["reconcile-interval-seconds"] === undefined ? "—" : lastHost["reconcile-interval-seconds"] + "s"],
      ["Backend agents reporting", String((lastHost.processes && Object.keys(lastHost.processes).length) || 0)],
    ];
    byId("system-details").innerHTML = rows.map(function (row) {
      return "<tr><td class='dim'>" + esc(row[0]) + "</td><td>" + esc(row[1]) + "</td></tr>";
  }).join("");
  }

  // ---- activity page ----------------------------------------------------------
  var ACTIVITY_COLORS = {
    started: "#34d399", stopped: "#71717a", crashed: "#f87171",
    created: "#a78bfa", deleted: "#fbbf24", warning: "#fbbf24", info: "#22d3ee",
  };
  function loadActivity() {
    api("/bridge/activity").then(function (result) {
      activityLoadedAt = Date.now();
      var items = (result && result.events) || [];
      byId("activity-list").innerHTML = items.length ? items.map(function (item) {
        var color = ACTIVITY_COLORS[item.kind] || "#52525b";
        return '<div class="tl-item">' +
          '<span class="tl-time">' + new Date(item.t * 1000).toLocaleTimeString() + "</span>" +
          '<span class="tl-dot" style="background:' + color + ';box-shadow:0 0 6px ' + color + ';"></span>' +
          '<span class="tl-msg">' + esc(item.message) + "</span></div>";
      }).join("") : '<div class="empty">Nothing has happened yet — events appear here as services start, stop and crash.</div>';
    }).catch(function (error) {
      byId("activity-list").innerHTML = '<div class="empty">Failed to load activity: ' + esc(error.message) + "</div>";
    });
  }
  byId("activity-refresh").addEventListener("click", loadActivity);

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
    byId("toasts").appendChild(element);
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
      return '<option value="' + esc(service.name) + '"' + selected + ">" + esc(service.name) + "</option>";
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
      if (page === "activity" && Date.now() - activityLoadedAt > 10000) loadActivity();
    }
  }, 5000);
})();
