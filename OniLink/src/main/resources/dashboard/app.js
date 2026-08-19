"use strict";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const ranks = { viewer: 0, operator: 1, admin: 2, owner: 3 };
const model = {
  token: sessionStorage.getItem("onilink_dashboard_token") || "",
  principal: null,
  state: null,
  players: [],
  backends: [],
  allowlist: null,
  config: null,
  backendSetup: null,
  action: null,
  totpSecret: "",
};

function escapeHtml(value) {
  return String(value ?? "").replace(
    /[&<>'"]/g,
    (character) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        "'": "&#39;",
        '"': "&quot;",
      })[character],
  );
}

function formBody(values) {
  const body = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => body.set(key, value ?? ""));
  return body;
}

function downloadText(name, content) {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(
    new Blob([content], { type: "text/plain;charset=UTF-8" }),
  );
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 2000);
}

function downloadBase64(name, encoded, type) {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob([bytes], { type }));
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 2000);
}

async function copyText(value) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const temporary = document.createElement("textarea");
  temporary.value = value;
  temporary.style.position = "fixed";
  temporary.style.opacity = "0";
  document.body.appendChild(temporary);
  temporary.select();
  if (!document.execCommand("copy"))
    throw new Error("Clipboard access is unavailable; copy the field manually");
  temporary.remove();
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (model.token) headers.set("Authorization", `Bearer ${model.token}`);
  let body = options.body;
  if (body && !(body instanceof URLSearchParams)) body = formBody(body);
  if (body)
    headers.set(
      "Content-Type",
      "application/x-www-form-urlencoded;charset=UTF-8",
    );
  const response = await fetch(path, {
    ...options,
    headers,
    body,
    cache: "no-store",
  });
  const contentType = response.headers.get("content-type") || "";
  const payload = contentType.includes("application/json")
    ? await response.json()
    : await response.text();
  if (!response.ok) {
    if (response.status === 401 && path !== "/api/login") signOut(false);
    throw new Error(
      payload.error || payload || `Request failed (${response.status})`,
    );
  }
  return payload;
}

function hasRole(role) {
  return model.principal && ranks[model.principal.role] >= ranks[role];
}

let noticeTimer;
function notice(message, error = false) {
  const element = $("#notice");
  element.textContent = message;
  element.classList.toggle("error", error);
  element.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => (element.hidden = true), 6000);
}

function formatBytes(value) {
  const bytes = Number(value || 0);
  if (!Number.isFinite(bytes)) return "—";
  const units = ["B", "KB", "MB", "GB"];
  let size = bytes;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(unit > 1 ? 1 : 0)} ${units[unit]}`;
}

function formatDuration(value) {
  let seconds = Math.max(0, Math.floor(Number(value || 0) / 1000));
  const days = Math.floor(seconds / 86400);
  seconds %= 86400;
  const hours = Math.floor(seconds / 3600);
  seconds %= 3600;
  const minutes = Math.floor(seconds / 60);
  if (days) return `${days}d ${hours}h`;
  if (hours) return `${hours}h ${minutes}m`;
  return `${minutes}m ${seconds % 60}s`;
}

function healthClass(status) {
  return ["online", "offline", "degraded"].includes(status)
    ? status
    : "checking";
}

function applyRole() {
  $$(".operator-only").forEach((element) =>
    element.classList.toggle("role-hidden", !hasRole("operator")),
  );
  $$(".admin-only").forEach((element) =>
    element.classList.toggle("role-hidden", !hasRole("admin")),
  );
  $$(".owner-only").forEach((element) =>
    element.classList.toggle("role-hidden", !hasRole("owner")),
  );
  $("#identity-name").textContent =
    model.principal?.username || "Not signed in";
  $("#identity-role").textContent = model.principal?.role || "Offline";
  $("#avatar").textContent = (model.principal?.username || "?")
    .slice(0, 1)
    .toUpperCase();
  $("#logout").hidden = !model.principal;
}

function setConnected(connected) {
  $("#status-dot").classList.toggle("online", connected);
  $("#status-text").textContent = connected ? "Online" : "Offline";
}

async function authenticate() {
  const status = await api("/api/setup/status");
  if (status.setupRequired) {
    $("#login-view").hidden = true;
    $("#setup-view").hidden = false;
    $("#auth-dialog").showModal();
    return;
  }
  if (model.token) {
    try {
      model.principal = await api("/api/whoami");
      applyRole();
      await loadRuntime();
      return;
    } catch (_) {
      model.token = "";
      sessionStorage.removeItem("onilink_dashboard_token");
    }
  }
  $("#login-view").hidden = false;
  $("#setup-view").hidden = true;
  $("#auth-dialog").showModal();
}

async function completeAuthentication(payload) {
  model.token = payload.token;
  model.principal = { username: payload.username, role: payload.role };
  sessionStorage.setItem("onilink_dashboard_token", payload.token);
  $("#auth-error").hidden = true;
  $("#auth-dialog").close();
  applyRole();
  await loadRuntime();
}

function signOut(callServer = true) {
  const oldToken = model.token;
  model.token = "";
  model.principal = null;
  model.state = null;
  sessionStorage.removeItem("onilink_dashboard_token");
  applyRole();
  setConnected(false);
  if (callServer && oldToken) {
    fetch("/api/logout", {
      method: "POST",
      headers: { Authorization: `Bearer ${oldToken}` },
    }).catch(() => {});
  }
  if (!$("#auth-dialog").open) {
    $("#login-view").hidden = false;
    $("#setup-view").hidden = true;
    $("#auth-dialog").showModal();
  }
}

async function loadRuntime() {
  const [state, players, backends] = await Promise.all([
    api("/api/state"),
    api("/api/players"),
    api("/api/backends"),
  ]);
  model.state = state;
  model.players = players.players;
  model.backends = backends.backends;
  model.principal = state.principal;
  applyRole();
  renderState();
  renderPlayers();
  renderBackends();
  setConnected(true);
}

function renderState() {
  const state = model.state;
  if (!state) return;
  $("#metric-players").textContent = state.players;
  $("#metric-capacity").textContent = `${state.maxPlayers} configured capacity`;
  $("#metric-backends").textContent = state.backends;
  const healthy = model.backends.filter(
    (item) => item.health?.status === "online",
  ).length;
  $("#metric-healthy").textContent = `${healthy} reporting online`;
  $("#metric-uptime").textContent = formatDuration(state.uptimeMillis);
  $("#metric-started").textContent =
    `Started ${new Date(state.startedAt).toLocaleString()}`;
  $("#metric-memory").textContent = formatBytes(state.memoryUsedBytes);
  $("#metric-memory-max").textContent =
    `${formatBytes(state.memoryMaxBytes)} maximum`;
  $("#refresh-time").textContent =
    `Updated ${new Date(state.timestamp).toLocaleTimeString()}`;
  const cpuLoad =
    Number(state.systemLoadAverage) < 0
      ? "Unavailable"
      : Number(state.systemLoadAverage).toFixed(2);
  $("#runtime-facts").innerHTML = `
    <div><dt>Version</dt><dd>${escapeHtml(state.version)}</dd></div>
    <div><dt>Listener</dt><dd>${escapeHtml(state.listener?.host)}:${escapeHtml(state.listener?.port)}</dd></div>
    <div><dt>Threads</dt><dd>${escapeHtml(state.threads)}</dd></div>
    <div><dt>CPU load</dt><dd>${cpuLoad}</dd></div>`;
  renderOverviewBackends();
}

function renderOverviewBackends() {
  const target = $("#overview-backends");
  if (!model.backends.length) {
    target.className = "health-list empty";
    target.textContent = "No backends configured.";
    return;
  }
  target.className = "health-list";
  target.innerHTML = model.backends
    .slice(0, 6)
    .map((backend) => {
      const status = backend.health?.status || "checking";
      const latency =
        backend.health?.latencyMillis >= 0
          ? `${backend.health.latencyMillis} ms`
          : status;
      return `
        <div class="health-row">
          <i class="${healthClass(status)}"></i>
          <b>${escapeHtml(backend.name)}</b>
          <small>${escapeHtml(latency)}</small>
        </div>`.trim();
    })
    .join("");
}

function renderPlayers() {
  const body = $("#players-body");
  if (!model.players.length) {
    body.innerHTML = `<tr><td colspan="6" class="empty">No players are connected.</td></tr>`;
    return;
  }
  body.innerHTML = model.players
    .map((player) => {
      const actions = hasRole("operator")
        ? `<div class="player-actions">
            <button class="mini-button" data-player="${escapeHtml(player.name)}" data-action="transfer">Transfer</button>
            <button class="mini-button" data-player="${escapeHtml(player.name)}" data-action="trace">Trace</button>
            <button class="mini-button" data-player="${escapeHtml(player.name)}" data-action="disconnect">Disconnect</button>
          </div>`
        : "";
      const status = player.switching
        ? "Switching"
        : player.joinedWorld
          ? "Playing"
          : "Joining";
      return `
        <tr>
          <td><b>${escapeHtml(player.name)}</b><small>${escapeHtml(player.address)}</small></td>
          <td>${escapeHtml(player.backend)}</td>
          <td>${escapeHtml(player.protocol)}</td>
          <td>${formatDuration(player.connectedMillis)}</td>
          <td><span class="badge ${player.switching ? "warn" : ""}">${status}</span></td>
          <td class="operator-only">${actions}</td>
        </tr>`.trim();
    })
    .join("");
  applyRole();
}

function renderBackends() {
  const grid = $("#backend-grid");
  if (!model.backends.length) {
    grid.innerHTML = `<article class="panel empty">No backends configured.</article>`;
    return;
  }
  grid.innerHTML = model.backends
    .map((backend) => {
      const health = backend.health || {
        status: "checking",
        latencyMillis: -1,
      };
      const flags = [
        backend.default ? "Default" : "",
        backend.hub ? "Hub" : "",
        backend.forwarding ? "Forwarding" : "",
      ].filter(Boolean);
      const endpoint = `${escapeHtml(backend.host)}${
        backend.port ? `:${escapeHtml(backend.port)}` : ""
      }`;
      const badges =
        flags
          .map((flag) => `<span class="badge">${escapeHtml(flag)}</span>`)
          .join("") || `<span class="badge">Route</span>`;
      const latency =
        health.latencyMillis >= 0 ? `${health.latencyMillis} ms` : "—";
      const lastProbe = health.checkedAt
        ? new Date(health.checkedAt).toLocaleTimeString()
        : "Pending";
      return `
        <article class="panel backend-card">
          <div class="backend-top">
            <div>
              <p class="eyebrow">${escapeHtml(backend.protocol)}</p>
              <h3>${escapeHtml(backend.name)}</h3>
              <span class="endpoint">${endpoint}</span>
            </div>
            <span class="badge">
              <i class="health-dot ${healthClass(health.status)}"></i>
              ${escapeHtml(health.status)}
            </span>
          </div>
          <div class="backend-flags">${badges}</div>
          <div class="backend-stats">
            <div><span>Players</span><b>${escapeHtml(backend.players)}</b></div>
            <div><span>Latency</span><b>${latency}</b></div>
            <div><span>Probe</span><b>${lastProbe}</b></div>
          </div>
        </article>`.trim();
    })
    .join("");
  renderOverviewBackends();
}

async function refreshRuntime(showNotice = false) {
  if (!model.token) return;
  try {
    await loadRuntime();
    if (showNotice) notice("Runtime data refreshed.");
  } catch (error) {
    setConnected(false);
    if (showNotice) notice(error.message, true);
  }
}

function renderConfig(config) {
  $("#config-content").value = config.content;
  const revision = `Revision ${config.revision.slice(0, 12)}`;
  $("#config-revision").textContent = revision;
  $("#backend-config-revision").textContent = revision;
}

async function loadConfig() {
  model.config = await api("/api/config");
  renderConfig(model.config);
}

async function loadAllowlist() {
  model.allowlist = await api("/api/allowlist");
  renderAllowlist();
}

function renderAllowlist() {
  const data = model.allowlist || { enabled: false, count: 0, entries: [] };
  const status = $("#allowlist-status");
  status.textContent = data.enabled ? "ENFORCING" : "DISABLED";
  status.classList.toggle("warn", !data.enabled);
  $("#allowlist-summary").textContent = data.enabled
    ? `${data.count} authenticated XUID(s) may join. Unlisted players are denied before backend connection.`
    : `${data.count} XUID(s) are prepared, but enforcement is disabled until configuration is enabled and OniLink restarts.`;
  $("#allowlist-count").textContent = data.count;
  const body = $("#allowlist-body");
  body.innerHTML = data.entries.length
    ? data.entries
        .map((entry) => {
          const xuid = escapeHtml(entry.xuid);
          return `
            <tr>
              <td><code>${xuid}</code></td>
              <td>${escapeHtml(entry.name || "—")}</td>
              <td><button class="mini-button" data-remove-allowlist="${xuid}">Remove</button></td>
            </tr>`.trim();
        })
        .join("")
    : `<tr><td colspan="3" class="empty">No XUIDs are allow-listed.</td></tr>`;
  const select = $("#allowlist-player-select");
  const allowed = new Set(data.entries.map((entry) => entry.xuid));
  const candidates = model.players.filter(
    (player) => !allowed.has(player.xuid),
  );
  select.innerHTML = candidates.length
    ? candidates
        .map((player) => {
          const name = escapeHtml(player.name);
          const xuid = escapeHtml(player.xuid);
          return `<option value="${xuid}" data-name="${name}">${name} — ${xuid}</option>`;
        })
        .join("")
    : `<option value="">No unlisted connected players</option>`;
}

function showBackendSetup(result) {
  model.backendSetup = result;
  $("#backend-setup-title").textContent = `${result.backendName} files ready`;
  $("#backend-secret-label").textContent = result.secretFileName;
  $("#backend-proxy-secret-path").textContent = result.onilinkSecretFile;
  $("#backend-proxy-result").value = result.onilinkProperties;
  $("#backend-secret-result").value = result.secret;
  $("#backend-toml-result").value = result.onibridgeToml;
  $("#backend-endpoint-summary").textContent =
    `${result.backendEndpoint} · trusted proxy ${result.trustedProxyCidr}`;
  $("#backend-test-command").textContent = `/server ${result.backendName}`;
  $("#backend-setup-result").hidden = false;
  $("#backend-setup-result").scrollIntoView({
    behavior: "smooth",
    block: "start",
  });
}

async function loadLogs() {
  const result = await api("/api/logs?limit=400");
  $("#log-output").textContent = result.lines.join("\n") || "Log is empty.";
}

async function loadAudit() {
  const result = await api("/api/audit?limit=250");
  $("#audit-output").textContent =
    result.lines.join("\n") || "Audit log is empty.";
}

async function loadUsers() {
  if (!hasRole("owner")) return;
  const result = await api("/api/users");
  $("#user-list").className = "user-list";
  $("#user-list").innerHTML = result.users
    .map((user) => {
      const username = escapeHtml(user.username);
      const removeButton =
        user.role === "owner"
          ? ""
          : `<button class="button danger-outline" data-delete-user="${username}">Remove</button>`;
      return `
        <div class="user-row">
          <div>
            <b>${username}</b>
            <small>${user.totpEnabled ? "TOTP enabled" : "Password only"}</small>
          </div>
          <span class="badge">${escapeHtml(user.role)}</span>
          ${removeButton}
        </div>`.trim();
    })
    .join("");
}

async function navigate(page) {
  const button = $(`.nav-link[data-page="${page}"]`);
  if (!button || button.classList.contains("role-hidden")) return;
  $$(".nav-link").forEach((item) =>
    item.classList.toggle("active", item === button),
  );
  $$(".page").forEach((item) =>
    item.classList.toggle("active", item.id === `page-${page}`),
  );
  $("#page-title").textContent = button.textContent.replace(/^\d+/, "").trim();
  try {
    if (["add-backend", "configuration"].includes(page)) await loadConfig();
    if (page === "allowlist") await loadAllowlist();
    if (page === "operations") await loadLogs();
    if (page === "audit") await loadAudit();
    if (page === "accounts") await loadUsers();
    if (["overview", "players", "backends"].includes(page))
      await refreshRuntime();
  } catch (error) {
    notice(error.message, true);
  }
}

function openPlayerAction(action, player) {
  model.action = action;
  const labels = {
    transfer: "Transfer player",
    disconnect: "Disconnect player",
    trace: "Trace packets",
  };
  $("#action-title").textContent = `${labels[action]} — ${player}`;
  $("#action-form [name=player]").value = player;
  $("#action-backend-field").hidden = action !== "transfer";
  $("#action-reason-field").hidden = action !== "disconnect";
  $("#action-duration-field").hidden = action !== "trace";
  $("#action-form [name=backend]").innerHTML = model.backends
    .map(
      (backend) =>
        `<option value="${escapeHtml(backend.name)}">${escapeHtml(backend.name)}</option>`,
    )
    .join("");
  $("#action-dialog").showModal();
}

$("#auth-dialog").addEventListener("cancel", (event) => event.preventDefault());
$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget));
  try {
    await completeAuthentication(
      await api("/api/login", { method: "POST", body: data }),
    );
  } catch (error) {
    $("#auth-error").textContent = error.message;
    $("#auth-error").hidden = false;
  }
});
$("#setup-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget));
  try {
    await completeAuthentication(
      await api("/api/setup", { method: "POST", body: data }),
    );
  } catch (error) {
    $("#auth-error").textContent = error.message;
    $("#auth-error").hidden = false;
  }
});
$("#logout").addEventListener("click", () => signOut());
$("#nav").addEventListener("click", (event) => {
  const button = event.target.closest("[data-page]");
  if (button) navigate(button.dataset.page);
});
document.addEventListener("click", (event) => {
  const button = event.target.closest("[data-goto]");
  if (button) navigate(button.dataset.goto);
});
$("#refresh-players").addEventListener("click", () => refreshRuntime(true));
$("#refresh-backends").addEventListener("click", () => refreshRuntime(true));
$("#refresh-allowlist").addEventListener("click", () =>
  loadAllowlist()
    .then(() => notice("Allowlist refreshed."))
    .catch((error) => notice(error.message, true)),
);
$("#allowlist-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  try {
    const result = await api("/api/allowlist", {
      method: "POST",
      body: Object.fromEntries(new FormData(form)),
    });
    form.reset();
    await loadAllowlist();
    notice(result.message);
  } catch (error) {
    notice(error.message, true);
  }
});
$("#allowlist-add-connected").addEventListener("click", async () => {
  const select = $("#allowlist-player-select");
  const option = select.selectedOptions[0];
  if (!option?.value)
    return notice("Select an unlisted connected player.", true);
  try {
    const result = await api("/api/allowlist", {
      method: "POST",
      body: { xuid: option.value, name: option.dataset.name || "" },
    });
    await loadAllowlist();
    notice(result.message);
  } catch (error) {
    notice(error.message, true);
  }
});
$("#allowlist-body").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-remove-allowlist]");
  if (
    !button ||
    !confirm(
      `Remove XUID ${button.dataset.removeAllowlist} from the allowlist?`,
    )
  )
    return;
  try {
    const result = await api("/api/allowlist", {
      method: "DELETE",
      body: { xuid: button.dataset.removeAllowlist },
    });
    await loadAllowlist();
    notice(result.message);
  } catch (error) {
    notice(error.message, true);
  }
});
$("#players-body").addEventListener("click", (event) => {
  const button = event.target.closest("[data-action]");
  if (button) openPlayerAction(button.dataset.action, button.dataset.player);
});
$("#action-cancel").addEventListener("click", () =>
  $("#action-dialog").close(),
);
$("#action-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget));
  try {
    const result = await api(`/api/action/${model.action}`, {
      method: "POST",
      body: data,
    });
    $("#action-dialog").close();
    notice(result.message);
    await refreshRuntime();
  } catch (error) {
    notice(error.message, true);
  }
});
$("#alert-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = Object.fromEntries(new FormData(form));
  try {
    const result = await api("/api/action/alert", {
      method: "POST",
      body: data,
    });
    notice(result.message);
    form.reset();
  } catch (error) {
    notice(error.message, true);
  }
});
$("#add-backend-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!model.config)
    return notice(
      "Configuration is still loading. Try again in a moment.",
      true,
    );
  const form = event.currentTarget;
  const submit = form.querySelector("[type=submit]");
  const data = Object.fromEntries(new FormData(form));
  data.revision = model.config.revision;
  submit.disabled = true;
  submit.textContent = "Building secure setup package…";
  try {
    const result = await api("/api/config/backends", {
      method: "POST",
      body: data,
    });
    model.config = result;
    renderConfig(result);
    showBackendSetup(result);
    notice(
      `${result.backendName} was added. Install the generated Endstone files, then restart OniLink.`,
    );
  } catch (error) {
    notice(error.message, true);
  } finally {
    submit.disabled = false;
    submit.textContent = "Create backend setup package";
  }
});
$("#download-backend-bundle").addEventListener("click", () => {
  if (!model.backendSetup) return;
  downloadBase64(
    model.backendSetup.setupBundleFileName,
    model.backendSetup.setupBundleBase64,
    "application/zip",
  );
  notice("Setup ZIP downloaded. Keep it private until it is installed on BDS.");
});
$("#download-backend-secret").addEventListener("click", () => {
  if (!model.backendSetup) return;
  downloadText(
    model.backendSetup.secretFileName,
    `${model.backendSetup.secret}\n`,
  );
});
$("#download-backend-toml").addEventListener("click", () => {
  if (!model.backendSetup) return;
  downloadText("onibridge.toml", model.backendSetup.onibridgeToml);
});
$("#copy-backend-proxy").addEventListener("click", async () => {
  try {
    await copyText($("#backend-proxy-result").value);
    notice("Saved OniLink properties copied.");
  } catch (error) {
    notice(error.message, true);
  }
});
$("#copy-backend-secret").addEventListener("click", async () => {
  try {
    await copyText($("#backend-secret-result").value);
    notice("Backend secret copied.");
  } catch (error) {
    notice(error.message, true);
  }
});
$("#copy-backend-toml").addEventListener("click", async () => {
  try {
    await copyText($("#backend-toml-result").value);
    notice("onibridge.toml copied.");
  } catch (error) {
    notice(error.message, true);
  }
});
$("#reset-backend-wizard").addEventListener("click", () => {
  model.backendSetup = null;
  $("#backend-setup-result").hidden = true;
  $("#add-backend-form").reset();
  $("#add-backend-form [name=name]").focus();
  $("#page-add-backend").scrollIntoView({ behavior: "smooth", block: "start" });
});
$("#save-config").addEventListener("click", async () => {
  if (!model.config) return;
  try {
    model.config = await api("/api/config", {
      method: "POST",
      body: {
        revision: model.config.revision,
        content: $("#config-content").value,
      },
    });
    renderConfig(model.config);
    notice(
      "Configuration validated, backed up, and saved. Restart OniLink to apply it.",
    );
  } catch (error) {
    notice(error.message, true);
  }
});
$("#rollback-config").addEventListener("click", async () => {
  if (!confirm("Restore the last dashboard configuration backup?")) return;
  try {
    model.config = await api("/api/config/rollback", { method: "POST" });
    renderConfig(model.config);
    notice("Previous configuration restored. Restart OniLink to apply it.");
  } catch (error) {
    notice(error.message, true);
  }
});
$("#refresh-logs").addEventListener("click", () =>
  loadLogs().catch((error) => notice(error.message, true)),
);
$("#refresh-audit").addEventListener("click", () =>
  loadAudit().catch((error) => notice(error.message, true)),
);
$("#download-support").addEventListener("click", async () => {
  try {
    const response = await fetch("/api/support-bundle", {
      headers: { Authorization: `Bearer ${model.token}` },
    });
    if (!response.ok)
      throw new Error(
        (await response.json()).error || "Support download failed",
      );
    const link = document.createElement("a");
    link.href = URL.createObjectURL(await response.blob());
    link.download = `onilink-support-${Date.now()}.zip`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 2000);
  } catch (error) {
    notice(error.message, true);
  }
});
$("#shutdown-proxy").addEventListener("click", async () => {
  if (
    !confirm(
      "Gracefully stop OniLink now? Connected players will be disconnected.",
    )
  )
    return;
  try {
    const result = await api("/api/shutdown", { method: "POST" });
    notice(result.message);
  } catch (error) {
    notice(error.message, true);
  }
});
$("#create-user-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = Object.fromEntries(new FormData(form));
  try {
    await api("/api/users", { method: "POST", body: data });
    form.reset();
    await loadUsers();
    notice("Dashboard account created.");
  } catch (error) {
    notice(error.message, true);
  }
});
$("#user-list").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-delete-user]");
  if (
    !button ||
    !confirm(`Remove dashboard account ${button.dataset.deleteUser}?`)
  )
    return;
  try {
    await api("/api/users", {
      method: "DELETE",
      body: { username: button.dataset.deleteUser },
    });
    await loadUsers();
    notice("Dashboard account removed.");
  } catch (error) {
    notice(error.message, true);
  }
});
$("#password-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await api("/api/account/password", {
      method: "POST",
      body: Object.fromEntries(new FormData(event.currentTarget)),
    });
    notice("Password changed. Sign in again.");
    signOut(false);
  } catch (error) {
    notice(error.message, true);
  }
});
$("#begin-totp").addEventListener("click", async () => {
  try {
    const result = await api("/api/account/totp/begin", { method: "POST" });
    model.totpSecret = result.secret;
    $("#totp-secret").textContent = result.secret;
    $("#totp-uri").textContent = result.uri;
    $("#totp-setup").hidden = false;
  } catch (error) {
    notice(error.message, true);
  }
});
$("#enable-totp").addEventListener("click", async () => {
  try {
    await api("/api/account/totp/enable", {
      method: "POST",
      body: { secret: model.totpSecret, code: $("#totp-code").value },
    });
    notice("TOTP enabled. Sign in again.");
    signOut(false);
  } catch (error) {
    notice(error.message, true);
  }
});
$("#disable-totp").addEventListener("click", async () => {
  const password = prompt("Enter your current password:");
  if (password === null) return;
  const code = prompt("Enter the six-digit authenticator code:");
  if (code === null) return;
  try {
    await api("/api/account/totp/disable", {
      method: "POST",
      body: { password, code },
    });
    notice("TOTP disabled. Sign in again.");
    signOut(false);
  } catch (error) {
    notice(error.message, true);
  }
});

authenticate().catch((error) => {
  setConnected(false);
  $("#auth-error").textContent = `Dashboard unavailable: ${error.message}`;
  $("#auth-error").hidden = false;
  if (!$("#auth-dialog").open) $("#auth-dialog").showModal();
});
setInterval(() => refreshRuntime(false), 5000);
