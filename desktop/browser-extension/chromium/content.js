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

function looksLikeVideoPage() {
  if (isYoutube()) return true;
  return !!document.querySelector('video[src], video source[src]');
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

// A page that is itself a video, with no clickable link, gets a small offer
// rather than a click handler nobody can trigger.
function offerPageScan() {
  if (!appAvailable) return;
  if (!looksLikeVideoPage()) return;
  if (document.getElementById("dlm-offer")) return;
  if (document.querySelector('a[href][download], a[href$=".mp4"]')) return;

  const button = document.createElement("button");
  button.id = "dlm-offer";
  button.textContent = "Download this video with 1 download manager";
  Object.assign(button.style, {
    position: "fixed",
    right: "16px",
    bottom: "16px",
    zIndex: "2147483647",
    padding: "8px 12px",
    border: "1px solid #34D399",
    borderRadius: "8px",
    background: "#1A2124",
    color: "#34D399",
    font: "12px system-ui, sans-serif",
    cursor: "pointer",
  });
  button.addEventListener("click", () => {
    button.remove();
    handOff(location.href, "");
  });
  document.body.appendChild(button);
  setTimeout(() => button.remove(), 20000);
}

function probeApp() {
  chrome.runtime
    .sendMessage({ type: "status" })
    .then((status) => {
      appAvailable = Boolean(status && status.paired && status.reachable);
      if (appAvailable) offerPageScan();
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
