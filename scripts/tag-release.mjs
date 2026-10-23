// Команда publish для changesets/action: создаёт и пушит тег v<версия>, если его ещё нет.
// Строка "New tag:" сообщает changesets/action, что релиз состоялся (output published).
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const version = readFileSync('gradle.properties', 'utf8').match(/^mimir\.version=(.*)$/m)?.[1];
if (!version) {
  console.error('mimir.version not found in gradle.properties');
  process.exit(1);
}
const tag = `v${version}`;
const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();

if (git('ls-remote', '--tags', 'origin', `refs/tags/${tag}`)) {
  console.log(`Tag ${tag} already exists, nothing to release`);
} else {
  git('tag', tag);
  git('push', 'origin', tag);
  console.log(`New tag: mimir@${version}`);
}
