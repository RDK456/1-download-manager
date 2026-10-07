/**
 * Turns download clicks into hand-offs to 1 download manager.
 *
 * Only intercepts a click when the app is actually reachable and paired, so the
 * browser keeps its normal behaviour whenever the app is closed. That matters: a
 * silently broken download button would be worse than no integration at all.
 */

const MEDIA_EXTENSIONS = [
  ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".flv", ".mp3", ".m4a",
  ".flac", ".wav", ".opus", ".aac", ".zip", ".rar", ".7z", ".tar", ".gz",
  ".pdf", ".iso", ".apk", ".exe", ".msi", ".torrent",
];

let appAvailable = false;

function looksLikeFile(href) {
  if (!href) return false;
  let path = href;
  try {
    path = new URL(href, location.href).pathname;
  } catch (error) {
    return false;
  }
  const lower = path.toLowerCase();
  if (lower.endsWith(".torrent")) return true;
  return MEDIA_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

function isYoutube() {
  const host = location.hostname.replace(/^www\./, "");
  return host === "youtu.be" || host === "youtube.com" || host.endsWith("youtube.com");
}

function isDownloadLink(anchor) {
  if (!anchor) return false;
  // The author already said so.
  if (anchor.hasAttribute("download")) return true;
  if (anchor.dataset.dlm === "skip") return false;
  if (looksLikeFile(anchor.href)) return true;
  const host = (() => {
    try {
      return new URL(anchor.href, location.href).hostname;
    } catch (error) {
      return "";
    }
  })();
  return host === "youtu.be" || host.endsWith("youtube.com");
}

function notify(text) {
  const existing = document.getElementById("dlm-toast");
  if (existing) existing.remove();
  const toast = document.createElement("div");
  toast.id = "dlm-toast";
  toast.textContent = text;
  Object.assign(toast.style, {
    position: "fixed",
    right: "16px",
    bottom: "16px",
    zIndex: "2147483647",
    padding: "10px 14px",
    borderRadius: "8px",
    background: "#12171A",
    color: "#E6EDEE",
    font: "13px system-ui, sans-serif",
    boxShadow: "0 6px 20px rgba(0,0,0,0.4)",
  });
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 2600);
}

async function handOff(url, fileName) {
  const result = await chrome.runtime.sendMessage({
    type: "queue-link",
    url: url,
    referer: location.href,
    fileName: fileName || "",
  });
  if (result && result.ok) {
    notify("Queued in 1 download manager");
  } else {
    notify("1 download manager: " + ((result && result.reason) || "not connected"));
  }
}

document.addEventListener(
  "click",
  (event) => {
    if (event.defaultPrevented || event.button !== 0) return;
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;

    const anchor = event.target instanceof Element
      ? event.target.closest("a[href]")
      : null;
    if (!anchor) return;
    if (!isDownloadLink(anchor)) return;
    // A magnet handed to the browser is a torrent we can take over.
    const href = anchor.getAttribute("href") || "";
    const isMagnet = href.toLowerCase().startsWith("magnet:");

    if (!isMagnet && !appAvailable) {
      // The app is closed: let the browser handle the download as usual.
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    const name = anchor.getAttribute("download") || "";
    handOff(anchor.href, name);
  },
  true
);

// ---- the video button, IDM-style ------------------------------------------------------
//
// A small "Download" key pinned to the top-right corner of every video player on the
// page - YouTube's player, or any <video> big enough to be the point of the page. It
// opens a short quality menu; picking one queues the video in the app straight away.
// "Choose in app..." opens the app's own Add Download window with every format.
//
// Drawn in a shadow root on an overlay at the top of the page, so no site's CSS can
// restyle it and it never changes the page's own layout. It follows the player as the
// page scrolls and resizes, and only exists while the app is running and paired.

const QUALITIES = [
  { label: "Best quality", height: 4320 },
  { label: "1080p", height: 1080 },
  { label: "720p", height: 720 },
  { label: "480p", height: 480 },
  { label: "Audio only", audioOnly: true },
  { label: "Choose in app...", review: true },
];

let overlayHost = null;
const buttons = new Map(); // player element -> button wrapper

function overlayRoot() {
  if (overlayHost && overlayHost.isConnected) return overlayHost.shadowRoot;
  overlayHost = document.createElement("dlm-overlay");
  Object.assign(overlayHost.style, { position: "fixed", inset: "0", pointerEvents: "none", zIndex: "2147483646" });
  const root = overlayHost.attachShadow({ mode: "open" });
  const style = document.createElement("style");
  style.textContent = `
    .dlm { position: fixed; pointer-events: auto; font: 600 12px/1 system-ui, sans-serif; }
    .key { display: flex; align-items: center; gap: 6px; padding: 7px 10px; border-radius: 6px;
      border: 1px solid #000; background: #C6F135; color: #0F0F0D; cursor: pointer;
      box-shadow: 0 3px 0 #5E7314; opacity: .88; transition: opacity .15s, transform .05s; }
    .key:hover { opacity: 1; }
    .key:active { transform: translateY(3px); box-shadow: none; }
    .menu { margin-top: 6px; min-width: 170px; border-radius: 8px; overflow: hidden;
      background: #181815; border: 1px solid #4A4840; box-shadow: 4px 4px 0 rgba(0,0,0,.45); }
    .item { padding: 9px 12px; color: #EEEBE1; cursor: pointer; font-weight: 500; }
    .item:hover { background: #26251F; color: #C6F135; }
    .item.sep { border-top: 1px solid #2B2A25; }
  `;
  root.appendChild(style);
  document.documentElement.appendChild(overlayHost);
  return root;
}

/** What to hand the app for this player: the page for YouTube, else the file if it is a real URL. */
function linkFor(player) {
  if (isYoutube()) return location.href;
  const video = player.tagName === "VIDEO" ? player : player.querySelector("video");
  const src = video && (video.currentSrc || video.src);
  return src && /^https?:/i.test(src) ? src : location.href;
}

function players() {
  if (isYoutube()) {
    return Array.from(document.querySelectorAll("#movie_player, #shorts-player")).filter((p) => p.offsetWidth > 0);
  }
  return Array.from(document.querySelectorAll("video")).filter((v) => {
    const r = v.getBoundingClientRect();
    return r.width >= 240 && r.height >= 135;
  });
}

function closeMenus() {
  buttons.forEach((wrap) => wrap.querySelector(".menu")?.remove());
}

function buttonFor(player) {
  const root = overlayRoot();
  const wrap = document.createElement("div");
  wrap.className = "dlm";
  const key = document.createElement("div");
  key.className = "key";
  key.title = "Download with 1 download manager";
  // Built with DOM calls, never innerHTML: YouTube enforces Trusted Types, and a string
  // assigned to innerHTML there throws and the button never appears.
  key.append(downloadIcon(), Object.assign(document.createElement("span"), { textContent: "Download" }));
  key.addEventListener("click", (event) => {
    event.stopPropagation();
    const open = wrap.querySelector(".menu");
    closeMenus();
    if (open) return;
    const menu = document.createElement("div");
    menu.className = "menu";
    QUALITIES.forEach((q, i) => {
      const item = document.createElement("div");
      item.className = "item" + (q.review || q.audioOnly ? " sep" : "");
      item.textContent = q.label;
      item.addEventListener("click", async (e) => {
        e.stopPropagation();
        closeMenus();
        const result = await chrome.runtime.sendMessage({
          type: "queue-link",
          url: linkFor(player),
          referer: location.href,
          audioOnly: Boolean(q.audioOnly),
          height: q.height,
          review: Boolean(q.review),
        });
        notify(result && result.ok
          ? (q.review ? "Opened in 1 download manager" : `Queued: ${q.label}`)
          : "1 download manager: " + ((result && result.reason) || "not connected"));
      });
      menu.appendChild(item);
    });
    wrap.appendChild(menu);
  });
  wrap.appendChild(key);
  root.appendChild(wrap);
  return wrap;
}

function placeButtons() {
  if (!appAvailable) {
    buttons.forEach((wrap) => wrap.remove());
    buttons.clear();
    return;
  }
  const current = new Set(players());
  buttons.forEach((wrap, player) => {
    if (!current.has(player) || !player.isConnected) {
      wrap.remove();
      buttons.delete(player);
    }
  });
  // YouTube's top bar is fixed over the page: once scrolled, the player's top edge is
  // under it, so the key is kept below the bar rather than on top of the search box.
  const header = isYoutube()
    ? (document.querySelector("#masthead-container")?.getBoundingClientRect().bottom || 0)
    : 0;
  current.forEach((player) => {
    const wrap = buttons.get(player) || buttons.set(player, buttonFor(player)).get(player);
    const r = player.getBoundingClientRect();
    const top = Math.max(r.top, header) + 12;
    const visible = r.bottom > top + 48 && r.top < innerHeight - 40 && r.width > 0;
    wrap.style.display = visible && !document.fullscreenElement ? "block" : "none";
    // Inside the player's top-right corner.
    wrap.style.top = top + "px";
    wrap.style.left = Math.max(8, r.right - 118) + "px";
  });
}

document.addEventListener("click", closeMenus);
addEventListener("scroll", placeButtons, { passive: true });
addEventListener("resize", placeButtons);
// YouTube swaps videos without reloading the page; the button stays and links to the
// new one, since it reads the address only when clicked.
document.addEventListener("yt-navigate-finish", placeButtons);
setInterval(placeButtons, 700);

function probeApp() {
  chrome.runtime
    .sendMessage({ type: "status" })
    .then((status) => {
      appAvailable = Boolean(status && status.paired && status.reachable);
      placeButtons();
    })
    .catch(() => {
      appAvailable = false;
    });
}

probeApp();
setInterval(probeApp, 4000);

// A short confirmation for the right-click "Download with 1DM".
chrome.runtime.onMessage.addListener((message) => {
  if (message && message.type === "dlm-toast") notify(message.text);
});

/** The download arrow as SVG elements, Trusted-Types safe. */
function downloadIcon() {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  for (const [k, v] of Object.entries({ width: "14", height: "14", viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "stroke-width": "2.6", "stroke-linecap": "round", "stroke-linejoin": "round" })) {
    svg.setAttribute(k, v);
  }
  for (const d of ["M12 4v11", "m7 10 5 5 5-5", "M5 20h14"]) {
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", d);
    svg.appendChild(path);
  }
  return svg;
}
