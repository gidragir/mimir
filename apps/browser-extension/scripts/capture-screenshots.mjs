import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, "../../..");
const extensionHtml = resolve(rootDir, "apps/browser-extension/build/extension/popup.html");
const outputDir = resolve(rootDir, "build/reports/screenshots/extension");

if (!existsSync(extensionHtml)) {
  console.error(`Extension popup.html not found at ${extensionHtml}. Run './gradlew :apps:browser-extension:assembleExtension' first.`);
  process.exit(1);
}

mkdirSync(outputDir, { recursive: true });

const chromiumBin = process.env.CHROME_BIN || "/usr/bin/chromium";
if (!existsSync(chromiumBin)) {
  console.error(`Chromium binary not found at ${chromiumBin}`);
  process.exit(1);
}

const targets = [
  {
    name: "extension_panel_dark.png",
    url: `file://${extensionHtml}`,
    width: 380,
    height: 600,
    dark: true,
  },
  {
    name: "extension_panel_light.png",
    url: `file://${extensionHtml}`,
    width: 380,
    height: 600,
    dark: false,
  },
  {
    name: "extension_tab_dark.png",
    url: `file://${extensionHtml}?mode=tab`,
    width: 1200,
    height: 800,
    dark: true,
  },
  {
    name: "extension_auth_dark.png",
    url: `file://${extensionHtml}?mock=auth`,
    width: 380,
    height: 600,
    dark: true,
  },
  {
    name: "extension_unlock_dark.png",
    url: `file://${extensionHtml}?mock=unlock`,
    width: 380,
    height: 600,
    dark: true,
  },
];

console.log(`Capturing browser extension screenshots using ${chromiumBin}...`);

for (const target of targets) {
  const outFile = resolve(outputDir, target.name);
  const args = [
    "--headless=new",
    "--disable-gpu",
    "--virtual-time-budget=2000",
    `--screenshot=${outFile}`,
    `--window-size=${target.width},${target.height}`,
  ];
  if (target.dark) {
    args.push("--force-dark-mode");
  }
  args.push(target.url);

  try {
    execFileSync(chromiumBin, args, { stdio: "pipe" });
    console.log(`  ✓ ${target.name} (${target.width}x${target.height})`);
  } catch (err) {
    console.error(`  ✗ Failed to capture ${target.name}:`, err.message);
  }
}

console.log(`Screenshots saved to: ${outputDir}`);
