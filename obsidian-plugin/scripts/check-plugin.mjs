// Проверка собранного плагина: node check-plugin.mjs <каталог с main.js и manifest.json>
import { readFileSync } from "node:fs";
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
class Plugin {
  addStatusBarItem() {
    return statusBarItem;
  }
}
const required = [];
const fakeRequire = (name) => {
  required.push(name);
  if (name === "obsidian") return { Plugin };
  throw new Error(`forbidden require("${name}")`);
};

const module = { exports: {} };
try {
  vm.runInNewContext(
    source,
    { module, exports: module.exports, require: fakeRequire, console, globalThis: undefined },
    { filename: "main.js" },
  );
} catch (e) {
  fail(`main.js threw on load: ${e.message}`);
}

const PluginClass = module.exports.default;
if (typeof PluginClass !== "function") fail("module.exports.default is not a class");

const bad = required.filter((n) => n !== "obsidian");
if (bad.length) fail(`main.js requires non-obsidian modules: ${bad.join(", ")}`);

try {
  new PluginClass().onload();
} catch (e) {
  fail(`onload() threw: ${e.message}`);
}
const expected = process.argv[3] ?? "Mimir: not connected";
if (statusBarItem.textContent !== expected) {
  fail(`status bar text is "${statusBarItem.textContent}", expected "${expected}"`);
}
console.log(`checkPlugin OK (${manifest.id} ${manifest.version})`);
