const portInput = document.getElementById("port");
const tokenInput = document.getElementById("token");
const state = document.getElementById("state");
const autoInput = document.getElementById("auto");
const ext = typeof browser !== "undefined" ? browser : chrome;

// Saved as soon as it is ticked: it needs no pairing to mean something.
autoInput.addEventListener("change", () => ext.storage.local.set({ autoCapture: autoInput.checked }));

function render(status) {
  if (!status.reachable) {
    state.className = "bad";
    state.textContent = "The app is not running. Start it and this connects by itself.";
    return;
  }
  if (!status.paired) {
    state.className = "bad";
    state.textContent = "Could not connect by itself. Paste the pairing code from the app's Settings > Browser.";
    return;
  }
  state.className = "ok";
  state.textContent = "Connected on port " + status.port + ".";
}

async function refresh() {
  const stored = await ext.storage.local.get(["port", "token", "autoCapture"]);
  autoInput.checked = stored.autoCapture !== false;
  portInput.value = stored.port || 38621;
  tokenInput.value = stored.token || "";
  const status = await (typeof browser !== "undefined" ? browser : chrome).runtime.sendMessage({ type: "status" });
  render(status || {});
}

document.getElementById("save").addEventListener("click", async () => {
  await (typeof browser !== "undefined" ? browser : chrome).runtime.sendMessage({
    type: "save-config",
    port: Number(portInput.value) || 38621,
    token: tokenInput.value.trim(),
  });
  await refresh();
});

refresh();

// ---- media the page played -----------------------------------------------------------
function formatSize(bytes) {
  if (!bytes) return "";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return value.toFixed(value < 10 && unit > 0 ? 1 : 0) + " " + units[unit];
}

async function renderMedia() {
  const [tab] = await ext.tabs.query({ active: true, currentWindow: true });
  if (!tab) return;
  const items = (await ext.runtime.sendMessage({ type: "page-media", tabId: tab.id })) || [];
  const box = document.getElementById("media");
  if (!items.length) return;
  box.replaceChildren();
  items.slice().reverse().forEach((item) => {
    const row = document.createElement("div");
    row.className = "media";
    const info = document.createElement("div");
    info.className = "info";
    const name = document.createElement("div");
    name.className = "name";
    let file = item.url;
    try {
      file = decodeURIComponent(new URL(item.url).pathname.split("/").pop()) || item.url;
    } catch (error) {}
    name.textContent = file;
    name.title = item.url;
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = [item.kind.toUpperCase(), formatSize(item.size)].filter(Boolean).join(" · ");
    info.append(name, meta);
    const button = document.createElement("button");
    button.textContent = "Download";
    button.addEventListener("click", async () => {
      button.disabled = true;
      // Opens the app's Add Download window; nothing is queued until it is confirmed there.
      const result = await ext.runtime.sendMessage({
        type: "queue-link",
        url: item.url,
        referer: tab.url || "",
        review: true,
      });
      button.textContent = result && result.ok ? "Sent" : "Failed";
    });
    row.append(info, button);
    box.appendChild(row);
  });
}

renderMedia();
