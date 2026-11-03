// Проверка собранного плагина: node check-plugin.mjs <каталог с main.js и manifest.json>
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { join, resolve } from "node:path";
import { createRequire } from "node:module";
import vm from "node:vm";

const dir = resolve(process.argv[2] ?? "build/plugin");
const fail = (msg) => {
  console.error(`checkPlugin FAILED: ${msg}`);
  process.exit(1);
};

let manifest;
let source;
try {
  manifest = JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8"));
  source = readFileSync(join(dir, "main.js"), "utf8");
} catch (e) {
  fail(`cannot read plugin files in ${dir}: ${e.message}`);
}

if (manifest.isDesktopOnly !== false) fail("manifest.isDesktopOnly must be false");

// Фейковый модуль obsidian; любой другой require — ошибка (плагин должен работать на телефоне)
const statusBarItem = { textContent: "" };
const commands = [];
const notices = [];
const writes = new Map();
const fetched = [];
const NEW_VERSION = "9.9.9";
const newMain = Buffer.from("// updated main.js\n");
const newManifest = Buffer.from(JSON.stringify({ ...manifest, version: NEW_VERSION }));
const sha = (b) => createHash("sha256").update(b).digest("hex");
const checksumsText = `${sha(newMain)}  main.js\n${sha(newManifest)}  manifest.json\n`;
const dl = "https://github.example/dl";
const releaseJson = JSON.stringify({
  tag_name: `v${NEW_VERSION}`,
  html_url: "https://github.example/release",
  published_at: "2026-11-03T00:00:00Z",
  body: "notes",
  assets: [
    { name: "main.js", browser_download_url: `${dl}/main.js`, size: newMain.length },
    { name: "manifest.json", browser_download_url: `${dl}/manifest.json`, size: newManifest.length },
    { name: "sha256sums.txt", browser_download_url: `${dl}/sha256sums.txt`, size: checksumsText.length },
  ],
});
const files = {
  [`${dl}/main.js`]: newMain,
  [`${dl}/manifest.json`]: newManifest,
};
const headerBag = (h) => ({ get: (k) => h[k.toLowerCase()] ?? null, forEach: (f) => Object.entries(h).forEach(([k, v]) => f(v, k)) });
const fakeFetch = async (url) => {
  fetched.push(url);
  if (url.startsWith("https://api.github.com/") && url.endsWith("/releases/latest"))
    return { status: 200, headers: headerBag({ etag: "e1" }), text: async () => releaseJson };
  if (url.endsWith("/sha256sums.txt")) return { status: 200, headers: headerBag({}), text: async () => checksumsText };
  const bytes = files[url];
  if (!bytes) return { status: 404, headers: headerBag({}), text: async () => "" };
  let sent = false;
  return {
    status: 200,
    headers: headerBag({ "content-length": String(bytes.length) }),
    body: {
      getReader: () => ({
        read: async () => (sent ? { done: true } : ((sent = true), { done: false, value: new Uint8Array(bytes) })),
        cancel: () => {},
      }),
    },
  };
};
const toAb = (b) => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
const adapter = {
  exists: async () => true,
  readBinary: async () => toAb(Buffer.from("old")),
  writeBinary: async (path, data) => void writes.set(path, Buffer.from(data)),
};
let savedData = null;
class Plugin {
  app = { vault: { configDir: ".obsidian", adapter } };
  manifest = { id: manifest.id, version: manifest.version, dir: `.obsidian/plugins/${manifest.id}` };
  addStatusBarItem() {
    return statusBarItem;
  }
  addCommand(c) {
    commands.push(c);
  }
  async loadData() {
    return savedData;
  }
  async saveData(d) {
    savedData = d;
  }
}
class Notice {
  constructor(message) {
    notices.push(message);
  }
}
const required = [];
const fakeRequire = (name) => {
  required.push(name);
  if (name === "obsidian") return { Plugin, Notice };
  throw new Error(`forbidden require("${name}")`);
};

const module = { exports: {} };
try {
  vm.runInNewContext(
    source,
    { module, exports: module.exports, require: fakeRequire, console, setTimeout, clearTimeout, fetch: fakeFetch },
    { filename: "main.js" },
  );
} catch (e) {
  fail(`main.js threw on load: ${e.message}`);
}

const PluginClass = module.exports.default;
if (typeof PluginClass !== "function") fail("module.exports.default is not a class");

const bad = required.filter((n) => n !== "obsidian");
if (bad.length) fail(`main.js requires non-obsidian modules: ${bad.join(", ")}`);

const plugin = new PluginClass();
try {
  plugin.onload();
} catch (e) {
  fail(`onload() threw: ${e.message}`);
}
const expected = process.argv[3] ?? "Mimir: not connected";
if (statusBarItem.textContent !== expected) {
  fail(`status bar text is "${statusBarItem.textContent}", expected "${expected}"`);
}
// Обновление: команда регистрируется, а её запуск заменяет оба файла на месте (без демона, через fetch и vault.adapter)
const update = commands.find((c) => c.id === "update-plugin");
if (!update) fail("command update-plugin is not registered");
update.callback();
const deadline = Date.now() + 10000;
while (writes.size < 2 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 25));
const pluginPath = `.obsidian/plugins/${manifest.id}`;
if (!writes.get(`${pluginPath}/main.js`)?.equals(newMain)) fail(`main.js was not replaced in place; notices: ${notices.join(" | ")}`);
if (!writes.get(`${pluginPath}/manifest.json`)?.equals(newManifest)) fail("manifest.json was not replaced in place");
if (![...writes.keys()].every((k) => k.startsWith(`${pluginPath}/`))) fail("update wrote outside the plugin directory");
if (!notices.some((n) => n.includes("Plugin updated"))) fail(`no success notice; notices: ${notices.join(" | ")}`);
console.log(`checkPlugin OK (${manifest.id} ${manifest.version})`);
process.exit(0); // отложенная автопроверка держит таймер
