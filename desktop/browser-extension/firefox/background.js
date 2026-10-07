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

/**
 * Asks the app for its pairing code, so nothing has to be copied across by hand.
 * The app hands it only to a browser extension, never to a web page.
 */
async function pairWithApp(port) {
  try {
    const response = await fetch(`http://127.0.0.1:${port}/pair`, {
      method: "POST",
      headers: { "X-DLM-Pair": "1" },
    });
    if (!response.ok) return "";
    const data = await response.json();
    if (!data.token) return "";
    await (typeof browser !== "undefined" ? browser : chrome).storage.local.set({ token: data.token });
    return data.token;
  } catch (error) {
    return "";
  }
}

async function sendToApp(url, options = {}) {
  if (!url) {
    return { ok: false, reason: "no link" };
  }
  const config = await getConfig();
  const port = config.port;
  let token = config.token || (await pairWithApp(port));
  if (!token) {
    return { ok: false, reason: "app not running" };
  }
  const body = JSON.stringify({
    url: url,
    referer: options.referer || "",
    fileName: options.fileName || "",
    cookies: options.cookies || "",
    review: Boolean(options.review),
    // A quality picked on the page's video button: the app queues it without asking.
    audioOnly: Boolean(options.audioOnly),
    height: options.height || undefined,
  });
  const post = (code) =>
    fetch(`http://127.0.0.1:${port}/queue`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-DLM-Token": code },
      body: body,
    });
  try {
    let response = await post(token);
    if (response.status === 403) {
      // The app's code changed (reinstalled, or reset): pair again once and retry.
      const fresh = await pairWithApp(port);
      if (fresh) response = await post(fresh);
    }
    if (response.status === 403) {
      return { ok: false, reason: "pairing refused" };
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
    sendToApp(message.url, {
      referer: message.referer,
      fileName: message.fileName,
      review: message.review,
      audioOnly: message.audioOnly,
      height: message.height,
    }).then(
      sendResponse
    );
    return true;
  }
  if (message.type === "status") {
    getConfig().then(async (config) => {
      const reachable = await isAppReachable(config.port);
      const token = config.token || (reachable ? await pairWithApp(config.port) : "");
      sendResponse({ port: config.port, paired: Boolean(token), reachable });
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

// Pairs as soon as the extension is installed or the browser starts, if the app is up.
async function pairIfNeeded() {
  const config = await getConfig();
  if (!config.token) await pairWithApp(config.port);
}
(typeof browser !== "undefined" ? browser : chrome).runtime.onInstalled.addListener(pairIfNeeded);
(typeof browser !== "undefined" ? browser : chrome).runtime.onStartup.addListener(pairIfNeeded);

// ---- media on the page, AB Download Manager-style ----------------------------------
//
// Watches the page's own responses for video, audio and streaming playlists (HLS .m3u8,
// DASH .mpd) and lists them per tab, with a count on the toolbar icon. The popup offers
// each one to the app. It reads only response headers the browser already received - no
// extra requests, nothing sent anywhere until the user picks an item. YouTube's internal
// segments are skipped: the button on the player handles YouTube properly.
const mediaApi = typeof browser !== "undefined" ? browser : chrome;
const tabMedia = new Map(); // tabId -> [{ url, kind, size }]
const MEDIA_TYPE = /^(video|audio)\/|mpegurl|dash\+xml/i;
const MEDIA_PATH = /\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|flac|ogg|oga|opus|wav)$/i;
const STREAM = /mpegurl|dash\+xml|\.m3u8$|\.mpd$/i;

function responseHeader(details, name) {
  const found = (details.responseHeaders || []).find((h) => h.name.toLowerCase() === name);
  return found ? found.value || "" : "";
}

function updateBadge(tabId) {
  const count = (tabMedia.get(tabId) || []).length;
  mediaApi.action.setBadgeText({ tabId, text: count ? String(count) : "" }).catch?.(() => {});
  mediaApi.action.setBadgeBackgroundColor({ tabId, color: "#C6F135" }).catch?.(() => {});
}

mediaApi.webRequest.onResponseStarted.addListener(
  (details) => {
    if (details.tabId < 0 || !/^https?:/i.test(details.url)) return;
    let url;
    try {
      url = new URL(details.url);
    } catch (error) {
      return;
    }
    if (url.hostname.endsWith("googlevideo.com")) return;
    const type = responseHeader(details, "content-type");
    if (!MEDIA_TYPE.test(type) && !MEDIA_PATH.test(url.pathname)) return;
    const stream = STREAM.test(type) || STREAM.test(url.pathname);
    // A byte range names the whole file's size after the slash; prefer that.
    const range = responseHeader(details, "content-range").split("/")[1];
    const size = Number(range) || Number(responseHeader(details, "content-length")) || 0;
    // Tiny files are previews, beeps and ad blips rather than the thing being watched.
    if (!stream && size > 0 && size < 512 * 1024) return;
    const list = tabMedia.get(details.tabId) || [];
    if (list.some((m) => m.url === details.url)) return;
    const kind = stream ? "Stream" : /^audio\//i.test(type) || /\.(mp3|m4a|aac|flac|ogg|oga|opus|wav)$/i.test(url.pathname) ? "Audio" : "Video";
    list.push({ url: details.url, kind, size });
    tabMedia.set(details.tabId, list.slice(-25));
    updateBadge(details.tabId);
  },
  { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] },
  ["responseHeaders"]
);

mediaApi.tabs.onUpdated.addListener((tabId, change) => {
  // A new page in the tab: what the last one played no longer applies.
  if (change.status === "loading" && change.url) {
    tabMedia.delete(tabId);
    updateBadge(tabId);
  }
});
mediaApi.tabs.onRemoved.addListener((tabId) => tabMedia.delete(tabId));

mediaApi.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message && message.type === "page-media") {
    sendResponse(tabMedia.get(message.tabId) || []);
    return false;
  }
  return false;
});
