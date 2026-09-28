/**
 * Sends links to 1 download manager.
 *
 * The app listens on loopback only and requires a per-install token, so the
 * extension stores that token once and attaches it to every request. Nothing is
 * sent anywhere else: there is no remote server in this path.
 */

const DEFAULT_PORT = 38621;

async function getConfig() {
  const stored = await chrome.storage.local.get(["port", "token"]);
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

/**
 * Queues one link.
 *
 * Returns a short status string rather than throwing, because every caller is a
 * click handler that has nothing useful to do with an exception.
 */
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

// The page asks for the current tab's links, and for any link to be queued.
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "queue-link") {
    sendToApp(message.url, { referer: message.referer, fileName: message.fileName }).then(
      sendResponse
    );
    return true; // keep the channel open for the async reply
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
    chrome.storage.local.set({ port: message.port, token: message.token }, () => {
      sendResponse({ saved: true });
    });
    return true;
  }
  return false;
});

// Allow the page to ask us to queue a link it discovered.
chrome.runtime.onMessageExternal?.addListener?.(() => false);
