/**
 * Firefox build of the hand-off, for the same loopback endpoint as the Chromium
 * one.
 *
 * Firefox does not provide `chrome.*`; it provides `browser.*`. The rest of the
 * logic is identical, so this file is the Chromium background script with the
 * namespace swapped and a shim so the shared code can stay as it is.
 *
 * The manifest also differs and cannot be shared: Firefox uses
 * `background.scripts` rather than a `service_worker`, and needs a
 * `browser_specific_settings` id. Those are in ../browser-extension-firefox/.
 */

const api = typeof browser !== "undefined" ? browser : chrome;
const DEFAULT_PORT = 38621;

async function getConfig() {
  const stored = await api.storage.local.get(["port", "token"]);
  return {
    port: stored.port || DEFAULT_PORT,
    token: stored.token || "",
  };
}

async function isAppReachable(port) {
  try {
    const response = await fetch(`http://127.0.0.1:${port}/ping`, {
      method: "GET",
      cache: "no-store",
    });
    return response.ok;
  } catch (error) {
    return false;
  }
}

async function sendToApp(url, options = {}) {
  if (!url) {
    return { ok: false, reason: "no link" };
  }
  const { port, token } = await getConfig();
  if (!token) {
    return { ok: false, reason: "not paired" };
  }
  try {
    const response = await fetch(`http://127.0.0.1:${port}/queue`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-DLM-Token": token,
      },
      body: JSON.stringify({
        url: url,
        referer: options.referer || "",
        fileName: options.fileName || "",
      }),
    });
    if (response.status === 403) {
      return { ok: false, reason: "token rejected" };
    }
    if (!response.ok) {
      return { ok: false, reason: `app said ${response.status}` };
    }
    return { ok: true };
  } catch (error) {
    return { ok: false, reason: "app not running" };
  }
}

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "queue-link") {
    sendToApp(message.url, { referer: message.referer, fileName: message.fileName }).then(
      sendResponse
    );
    return true;
  }
  if (message.type === "status") {
    getConfig().then(async (config) => {
      sendResponse({
        port: config.port,
        paired: Boolean(config.token),
        reachable: await isAppReachable(config.port),
      });
    });
    return true;
  }
  if (message.type === "save-config") {
    api.storage.local.set({ port: message.port, token: message.token }, () => {
      sendResponse({ saved: true });
    });
    return true;
  }
  return false;
});
