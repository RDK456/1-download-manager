const portInput = document.getElementById("port");
const tokenInput = document.getElementById("token");
const state = document.getElementById("state");

function render(status) {
  if (!status.paired) {
    state.className = "bad";
    state.textContent = "Not paired. Copy the pairing code from the app's Settings page.";
    return;
  }
  if (!status.reachable) {
    state.className = "bad";
    state.textContent = "The app is not running. Start it and reload this page.";
    return;
  }
  state.className = "ok";
  state.textContent = "Connected on port " + status.port + ".";
}

async function refresh() {
  const stored = await chrome.storage.local.get(["port", "token"]);
  portInput.value = stored.port || 38621;
  tokenInput.value = stored.token || "";
  const status = await chrome.runtime.sendMessage({ type: "status" });
  render(status || {});
}

document.getElementById("save").addEventListener("click", async () => {
  await chrome.runtime.sendMessage({
    type: "save-config",
    port: Number(portInput.value) || 38621,
    token: tokenInput.value.trim(),
  });
  await refresh();
});

refresh();
