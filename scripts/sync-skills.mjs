#!/usr/bin/env node
// Единый источник проектных скиллов — .agents/skills (его читают Antigravity, Codex и др.).
// .claude/skills содержит только относительные симлинки на него (Claude Code читает этот каталог).
// Исключение — VENDOR: скиллы, которые установщик генерирует отдельно под каждый инструмент.
//
//   node scripts/sync-skills.mjs          проверить, код 1 при расхождении
//   node scripts/sync-skills.mjs --fix    перенести реальные каталоги из .claude/skills в .agents/skills
//                                         и создать недостающие симлинки
import { existsSync, lstatSync, mkdirSync, readdirSync, readlinkSync, renameSync, rmSync, statSync, symlinkSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE = join(root, '.agents/skills');
const MIRROR = join(root, '.claude/skills');
// Генерируются установщиком под каждый инструмент (разные команды и инструменты внутри) — не синхронизируются.
const VENDOR = new Set(['impeccable']);

const fix = process.argv.includes('--fix');
const problems = [];

const list = (dir) => (existsSync(dir) ? readdirSync(dir) : []);
const isLink = (p) => lstatSync(p).isSymbolicLink();
const isRealDir = (p) => !isLink(p) && lstatSync(p).isDirectory();
const target = (name) => `../../.agents/skills/${name}`;

mkdirSync(SOURCE, { recursive: true });
mkdirSync(MIRROR, { recursive: true });

// 1. Реальный каталог в .claude/skills — скилл создан не там.
for (const name of list(MIRROR)) {
  const p = join(MIRROR, name);
  if (VENDOR.has(name) || !isRealDir(p)) continue;
  const src = join(SOURCE, name);
  if (fix && (!existsSync(src) || isLink(src))) {
    if (existsSync(src) || isLinkSafe(src)) rmSync(src, { force: true });
    renameSync(p, src);
    console.log(`moved   .claude/skills/${name} -> .agents/skills/${name}`);
  } else {
    problems.push(`.claude/skills/${name}: real directory, must live in .agents/skills (run: mise run skills:sync)`);
  }
}

// 2. В .agents/skills не должно быть симлинков обратно в .claude.
for (const name of list(SOURCE)) {
  const p = join(SOURCE, name);
  if (isLink(p)) problems.push(`.agents/skills/${name}: symlink (${readlinkSync(p)}), must be a real directory`);
}

// 3. Каждый скилл источника — симлинк в .claude/skills.
for (const name of list(SOURCE)) {
  const src = join(SOURCE, name);
  if (VENDOR.has(name) || isLink(src) || !statSync(src).isDirectory()) continue;
  const p = join(MIRROR, name);
  const ok = isLinkSafe(p) && readlinkSync(p) === target(name);
  if (ok) continue;
  if (fix) {
    if (isLinkSafe(p) || existsSync(p)) rmSync(p, { recursive: true, force: true });
    symlinkSync(target(name), p, 'dir');
    console.log(`linked  .claude/skills/${name} -> ${target(name)}`);
  } else {
    problems.push(`.claude/skills/${name}: missing or wrong symlink, expected -> ${target(name)}`);
  }
}

// 4. Висячие симлинки в .claude/skills.
for (const name of list(MIRROR)) {
  const p = join(MIRROR, name);
  if (isLink(p) && !existsSync(p)) {
    if (fix) {
      rmSync(p);
      console.log(`removed dangling .claude/skills/${name}`);
    } else problems.push(`.claude/skills/${name}: dangling symlink`);
  }
}

function isLinkSafe(p) {
  try {
    return lstatSync(p).isSymbolicLink();
  } catch {
    return false;
  }
}

if (problems.length) {
  console.error(`Skills out of sync (source of truth: .agents/skills):\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log(fix ? 'Skills synced.' : 'Skills in sync.');
