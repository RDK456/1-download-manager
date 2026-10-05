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
