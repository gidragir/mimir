// Версия релиза: единая для всех workspace-пакетов (changesets, группа fixed).
// Без аргументов записывает её в mimir.version (gradle.properties); с --check только проверяет.
import { readFileSync, writeFileSync } from 'node:fs';

const workspaces = [...readFileSync('pnpm-workspace.yaml', 'utf8').matchAll(/^\s+-\s+(\S+)\s*$/gm)].map((m) => m[1]);
const versions = new Map(
  workspaces.map((dir) => [dir, JSON.parse(readFileSync(`${dir}/package.json`, 'utf8')).version]),
);
const unique = new Set(versions.values());
if (unique.size !== 1) {
  console.error('Workspace versions differ (fixed group is broken):', Object.fromEntries(versions));
  process.exit(1);
}
const [version] = unique;

const propsFile = 'gradle.properties';
const props = readFileSync(propsFile, 'utf8');
const current = props.match(/^mimir\.version=(.*)$/m)?.[1];
if (current === undefined) {
  console.error('mimir.version not found in gradle.properties');
  process.exit(1);
}

if (process.argv.includes('--check')) {
  if (current !== version) {
    console.error(`gradle.properties mimir.version=${current}, workspaces=${version}`);
    process.exit(1);
  }
  console.log(`Versions in sync: ${version}`);
} else {
  writeFileSync(propsFile, props.replace(/^mimir\.version=.*$/m, `mimir.version=${version}`));
  console.log(`mimir.version=${version}`);
}
