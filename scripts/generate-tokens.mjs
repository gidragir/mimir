#!/usr/bin/env node
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const rootDir = resolve(__dirname, '..');

const tokensFile = resolve(rootDir, 'design/tokens.json');
const kotlinOutFile = resolve(rootDir, 'ui/src/commonMain/kotlin/dev/mimir/ui/MimirTokens.kt');
const cssOutFile = resolve(rootDir, 'browser-extension/src/jsMain/resources/tokens.css');

function hexToCompose(hex) {
  const clean = hex.replace('#', '').toUpperCase();
  return `Color(0xFF${clean})`;
}

function rgbaToCompose(rgba) {
  const match = rgba.match(/rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/);
  if (!match) return `Color(0xFFFFFFFF)`;
  const [, r, g, b, a] = match;
  const alphaInt = Math.round(parseFloat(a) * 255);
  const alphaHex = alphaInt.toString(16).padStart(2, '0').toUpperCase();
  const rHex = parseInt(r, 10).toString(16).padStart(2, '0').toUpperCase();
  const gHex = parseInt(g, 10).toString(16).padStart(2, '0').toUpperCase();
  const bHex = parseInt(b, 10).toString(16).padStart(2, '0').toUpperCase();
  return `Color(0x${alphaHex}${rHex}${gHex}${bHex})`;
}

function parseColorToCompose(val) {
  if (val.startsWith('#')) return hexToCompose(val);
  if (val.startsWith('rgba')) return rgbaToCompose(val);
  return `Color(0xFFFFFFFF)`;
}

export function generateKotlin(tokens) {
  const c = tokens.colors;
  const s = tokens.spacing;
  const r = tokens.radius;

  return `package dev.mimir.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single source of truth design tokens for Mimir UI.
 * Generated automatically from design/tokens.json by scripts/generate-tokens.mjs.
 * Do not edit directly.
 */
object MimirTokens {
    // Accent
    val Accent = ${parseColorToCompose(c.accent.default)}
    val AccentHover = ${parseColorToCompose(c.accent.hover)}
    val AccentSubtleDark = ${parseColorToCompose(c.accent.subtleDark)}
    val AccentSubtleLight = ${parseColorToCompose(c.accent.subtleLight)}

    // Status
    val StatusSuccess = ${parseColorToCompose(c.status.success)}
    val StatusError = ${parseColorToCompose(c.status.error)}
    val StatusOffline = ${parseColorToCompose(c.status.offline)}

    // Dark theme surfaces & text
    val DarkBase = ${parseColorToCompose(c.dark.base)}
    val DarkSurface = ${parseColorToCompose(c.dark.surface)}
    val DarkInput = ${parseColorToCompose(c.dark.input)}
    val DarkBorder = ${parseColorToCompose(c.dark.border)}
    val DarkText = ${parseColorToCompose(c.dark.text)}
    val DarkTextMuted = ${parseColorToCompose(c.dark.textMuted)}

    // Light theme surfaces & text
    val LightBase = ${parseColorToCompose(c.light.base)}
    val LightSurface = ${parseColorToCompose(c.light.surface)}
    val LightInput = ${parseColorToCompose(c.light.input)}
    val LightBorder = ${parseColorToCompose(c.light.border)}
    val LightText = ${parseColorToCompose(c.light.text)}
    val LightTextMuted = ${parseColorToCompose(c.light.textMuted)}

    object Dark {
        val Base = DarkBase
        val Surface = DarkSurface
        val Input = DarkInput
        val Border = DarkBorder
        val Text = DarkText
        val TextMuted = DarkTextMuted
    }

    object Light {
        val Base = LightBase
        val Surface = LightSurface
        val Input = LightInput
        val Border = LightBorder
        val Text = LightText
        val TextMuted = LightTextMuted
    }

    // Radius
    val RadiusXs: Dp = ${r.xs}.dp
    val RadiusSm: Dp = ${r.sm}.dp
    val RadiusMd: Dp = ${r.md}.dp

    val Radius4: Dp = ${r.xs}.dp
    val Radius6: Dp = ${r.sm}.dp
    val Radius8: Dp = ${r.md}.dp

    // Spacing
    val SpaceXs: Dp = ${s.xs}.dp
    val SpaceSm: Dp = ${s.sm}.dp
    val SpaceMd: Dp = ${s.md}.dp
    val SpaceLg: Dp = ${s.lg}.dp
    val SpaceXl: Dp = ${s.xl}.dp
    val Space2xl: Dp = ${s['2xl']}.dp
    val SpaceXxl: Dp = ${s['2xl']}.dp

    val Space4: Dp = ${s.xs}.dp
    val Space8: Dp = ${s.sm}.dp
    val Space12: Dp = ${s.md}.dp
    val Space16: Dp = ${s.lg}.dp
    val Space24: Dp = ${s.xl}.dp
    val Space32: Dp = ${s['2xl']}.dp
}
`;
}

export function generateCss(tokens) {
  const c = tokens.colors;
  const s = tokens.spacing;
  const r = tokens.radius;

  return `/**
 * Single source of truth design tokens for Mimir UI.
 * Generated automatically from design/tokens.json by scripts/generate-tokens.mjs.
 * Do not edit directly.
 */

:root {
  /* Accent & Interactive */
  --mimir-accent: ${c.accent.default};
  --mimir-accent-hover: ${c.accent.hover};
  --mimir-accent-subtle: ${c.accent.subtleLight};

  /* Status */
  --mimir-status-success: ${c.status.success};
  --mimir-status-error: ${c.status.error};
  --mimir-status-offline: ${c.status.offline};
  --mimir-success: ${c.status.success};
  --mimir-error: ${c.status.error};
  --mimir-offline: ${c.status.offline};

  /* Surfaces & Text (Default / Light) */
  --mimir-bg: ${c.light.base};
  --mimir-bg-base: ${c.light.base};
  --mimir-bg-surface: ${c.light.surface};
  --mimir-bg-input: ${c.light.input};
  --mimir-border: ${c.light.border};
  --mimir-text: ${c.light.text};
  --mimir-text-muted: ${c.light.textMuted};

  /* Spacing */
  --mimir-space-xs: ${s.xs}px;
  --mimir-space-sm: ${s.sm}px;
  --mimir-space-md: ${s.md}px;
  --mimir-space-lg: ${s.lg}px;
  --mimir-space-xl: ${s.xl}px;
  --mimir-space-2xl: ${s['2xl']}px;
  --mimir-space-xxl: ${s['2xl']}px;

  /* Radius */
  --mimir-radius-xs: ${r.xs}px;
  --mimir-radius-sm: ${r.sm}px;
  --mimir-radius-md: ${r.md}px;
}

@media (prefers-color-scheme: dark) {
  :root {
    --mimir-accent-subtle: ${c.accent.subtleDark};
    --mimir-bg: ${c.dark.base};
    --mimir-bg-base: ${c.dark.base};
    --mimir-bg-surface: ${c.dark.surface};
    --mimir-bg-input: ${c.dark.input};
    --mimir-border: ${c.dark.border};
    --mimir-text: ${c.dark.text};
    --mimir-text-muted: ${c.dark.textMuted};
  }
}

[data-theme="dark"] {
  --mimir-accent-subtle: ${c.accent.subtleDark};
  --mimir-bg: ${c.dark.base};
  --mimir-bg-base: ${c.dark.base};
  --mimir-bg-surface: ${c.dark.surface};
  --mimir-bg-input: ${c.dark.input};
  --mimir-border: ${c.dark.border};
  --mimir-text: ${c.dark.text};
  --mimir-text-muted: ${c.dark.textMuted};
}

[data-theme="light"] {
  --mimir-accent-subtle: ${c.accent.subtleLight};
  --mimir-bg: ${c.light.base};
  --mimir-bg-base: ${c.light.base};
  --mimir-bg-surface: ${c.light.surface};
  --mimir-bg-input: ${c.light.input};
  --mimir-border: ${c.light.border};
  --mimir-text: ${c.light.text};
  --mimir-text-muted: ${c.light.textMuted};
}
`;
}

function run() {
  const isCheck = process.argv.includes('--check');

  if (!existsSync(tokensFile)) {
    console.error(`Tokens file not found: ${tokensFile}`);
    process.exit(1);
  }

  const rawTokens = readFileSync(tokensFile, 'utf8');
  const tokens = JSON.parse(rawTokens);

  const expectedKotlin = generateKotlin(tokens);
  const expectedCss = generateCss(tokens);

  if (isCheck) {
    let hasDiff = false;

    if (!existsSync(kotlinOutFile)) {
      console.error(`Missing generated file: ${kotlinOutFile}`);
      hasDiff = true;
    } else {
      const currentKotlin = readFileSync(kotlinOutFile, 'utf8');
      if (currentKotlin !== expectedKotlin) {
        console.error(`Design drift detected in: ${kotlinOutFile}`);
        hasDiff = true;
      }
    }

    if (!existsSync(cssOutFile)) {
      console.error(`Missing generated file: ${cssOutFile}`);
      hasDiff = true;
    } else {
      const currentCss = readFileSync(cssOutFile, 'utf8');
      if (currentCss !== expectedCss) {
        console.error(`Design drift detected in: ${cssOutFile}`);
        hasDiff = true;
      }
    }

    if (hasDiff) {
      console.error('Tokens out of sync! Run "node scripts/generate-tokens.mjs" to update.');
      process.exit(1);
    }

    console.log('Design tokens are in sync.');
    process.exit(0);
  } else {
    mkdirSync(dirname(kotlinOutFile), { recursive: true });
    mkdirSync(dirname(cssOutFile), { recursive: true });

    writeFileSync(kotlinOutFile, expectedKotlin, 'utf8');
    writeFileSync(cssOutFile, expectedCss, 'utf8');

    console.log(`Generated Kotlin tokens -> ${kotlinOutFile}`);
    console.log(`Generated CSS tokens    -> ${cssOutFile}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(__filename)) {
  run();
}
