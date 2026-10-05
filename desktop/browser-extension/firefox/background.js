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
        cookies: options.cookies || "",
        review: Boolean(options.review),
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

// ---- every download the browser starts --------------------------------------------
//
// Link clicks are caught by content.js, but most downloads are not a plain link to a
// file: a button, a redirect, a script, a "download.php?id=" address. The downloads API
// sees all of them. Each is offered to the app first, and the browser's own copy is
// cancelled only once the app has accepted it - so with the app closed, unpaired or
// refusing, the browser downloads it as usual and nothing is lost.
const downloadsApi = typeof browser !== "undefined" ? browser : chrome;

async function cookieHeaderFor(url) {
  try {
    const cookies = await downloadsApi.cookies.getAll({ url });
    return cookies.map((c) => `${c.name}=${c.value}`).join("; ");
  } catch (error) {
    return "";
  }
}

downloadsApi.downloads.onCreated.addListener(async (item) => {
  const { autoCapture } = await downloadsApi.storage.local.get(["autoCapture"]);
  if (autoCapture === false) return;
  if (item.byExtensionId) return; // started by an extension, possibly on purpose
  const url = item.finalUrl || item.url || "";
  // blob:, data: and file: exist only inside this browser; the app cannot fetch them.
  if (!/^https?:/i.test(url)) return;
  const name = (item.filename || "").split(/[\/]/).pop();
  const result = await sendToApp(url, {
    referer: item.referrer || "",
    fileName: name,
    cookies: await cookieHeaderFor(url),
  });
  if (!result.ok) return;
  try {
    await downloadsApi.downloads.cancel(item.id);
    await downloadsApi.downloads.erase({ id: item.id });
  } catch (error) {
    // Already finished or gone; the app has its own copy either way.
  }
});

// ---- right-click "Download with 1DM" ------------------------------------------------
//
// On a link, an image, a video or audio element, or selected text containing a link.
// Sent whatever the "catch every download" switch says: choosing it is the user asking.
const menuApi = typeof browser !== "undefined" ? browser : chrome;
const menus = menuApi.menus || menuApi.contextMenus;

menuApi.runtime.onInstalled.addListener(async () => {
  await menus.removeAll();
  menus.create({
    id: "dlm-download",
    title: "Download with 1DM",
    contexts: ["link", "image", "video", "audio"],
  });
  menus.create({
    id: "dlm-download-selection",
    title: "Download link with 1DM",
    contexts: ["selection"],
  });
});

function firstLinkIn(text) {
  const match = (text || "").match(/(magnet:\?\S+|https?:\/\/\S+)/i);
  return match ? match[1] : "";
}

menus.onClicked.addListener(async (info, tab) => {
  const url =
    info.menuItemId === "dlm-download-selection"
      ? firstLinkIn(info.selectionText)
      : info.linkUrl || info.srcUrl || "";
  if (!url) return;
  const result = await sendToApp(url, {
    referer: info.pageUrl || "",
    review: true, // chosen from the menu: show the Add Download window
    cookies: /^https?:/i.test(url) ? await cookieHeaderFor(url) : "",
  });
  const text = result.ok
    ? "Sent to 1 download manager"
    : "1 download manager: " + (result.reason || "not connected");
  if (tab && tab.id !== undefined) {
    menuApi.tabs.sendMessage(tab.id, { type: "dlm-toast", text }).catch(() => {});
  }
});
