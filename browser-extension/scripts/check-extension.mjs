// Проверка собранного расширения: node check-extension.mjs <каталог>
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

const dir = resolve(process.argv[2] ?? "build/extension");
const fail = (msg) => {
  console.error(`checkExtension FAILED: ${msg}`);
  process.exit(1);
};
const read = (name) => {
  const path = join(dir, name);
  if (!existsSync(path)) fail(`${name} is missing in ${dir}`);
  return readFileSync(path, "utf8");
};

const manifest = JSON.parse(read("manifest.json"));
const popup = read("popup.html");

if (manifest.manifest_version !== 3) fail("manifest_version must be 3");
if (manifest.action?.default_popup !== "popup.html") fail('action.default_popup must be "popup.html"');
if (manifest.background?.scripts) fail("background.scripts is not allowed in MV3 Chromium; use service_worker");

const scripts = [...popup.matchAll(/<script[^>]*\ssrc="([^"]+)"/g)].map((m) => m[1]);
if (scripts.length === 0) fail("popup.html references no script");
for (const src of scripts) {
  if (!existsSync(join(dir, src))) fail(`popup.html references missing script ${src}`);
}
// MV3 CSP запрещает inline-скрипты
if (/<script(?![^>]*\ssrc=)[^>]*>\s*\S/.test(popup)) fail("popup.html contains an inline script (forbidden by MV3 CSP)");
console.log(`checkExtension OK (${manifest.name} ${manifest.version})`);
