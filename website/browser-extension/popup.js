"use strict";

const kindNode = document.getElementById("kind");
const urlNode = document.getElementById("url");
const copyUrlButton = document.getElementById("copy-url");
const copyCommandButton = document.getElementById("copy-command");
const statusNode = document.getElementById("status");
const recentList = document.getElementById("recent-list");

let currentTab = null;

function looksLikeDirectFile(rawUrl) {
  try {
    return /\.(zip|7z|rar|tar|gz|tgz|bz2|xz|mp4|mkv|webm|mp3|flac|wav|pdf|apk|jar|iso|dmg|exe|msi|bin)$/i.test(
      new URL(rawUrl).pathname
    );
  } catch {
    return false;
  }
}

function safeBaseName(rawUrl) {
  try {
    const name = decodeURIComponent(new URL(rawUrl).pathname.split("/").pop() || "");
    return name.replace(/[^\p{L}\p{N}._-]+/gu, "-").slice(0, 120) || "download.bin";
  } catch {
    return "download.bin";
  }
}

function cliCommand(rawUrl) {
  return `java -jar turbodl-cli-all.jar "${rawUrl.replaceAll('"', '\\"')}" -o "${safeBaseName(rawUrl)}" -c 16`;
}

async function writeClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
    return;
  } catch {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    document.body.appendChild(area);
    area.select();
    const copied = document.execCommand("copy");
    area.remove();
    if (!copied) throw new Error("copy failed");
  }
}

async function saveRecent(url) {
  const { recent = [] } = await chrome.storage.local.get("recent");
  const next = [{ url, at: Date.now() }, ...recent.filter((item) => item.url !== url)].slice(0, 5);
  await chrome.storage.local.set({ recent: next });
  renderRecent(next);
}

function renderRecent(items) {
  recentList.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "No links yet.";
    recentList.appendChild(empty);
    return;
  }
  for (const item of items) {
    const row = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = item.url;
    button.title = item.url;
    button.addEventListener("click", async () => {
      await writeClipboard(item.url);
      statusNode.textContent = "Recent URL copied.";
    });
    row.appendChild(button);
    recentList.appendChild(row);
  }
}

async function init() {
  [currentTab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = currentTab?.url || "";
  urlNode.textContent = url || "This tab has no copyable URL.";
  kindNode.textContent = url
    ? looksLikeDirectFile(url)
      ? "DIRECT FILE CANDIDATE"
      : "WEB PAGE URL"
    : "NO URL";
  copyUrlButton.disabled = !url;
  copyCommandButton.disabled = !url;

  const { recent = [] } = await chrome.storage.local.get("recent");
  renderRecent(recent);

  copyUrlButton.addEventListener("click", async () => {
    if (!currentTab?.url) return;
    await writeClipboard(currentTab.url);
    await saveRecent(currentTab.url);
    statusNode.textContent = "URL copied.";
  });

  copyCommandButton.addEventListener("click", async () => {
    if (!currentTab?.url) return;
    await writeClipboard(cliCommand(currentTab.url));
    await saveRecent(currentTab.url);
    statusNode.textContent = "CLI command copied.";
  });
}

init().catch(() => {
  kindNode.textContent = "CANNOT READ TAB";
  statusNode.textContent = "Check the extension's tab permission.";
});
