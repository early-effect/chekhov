#!/usr/bin/env bash
# Install Playwright browsers for the pinned npm dependency into the default OS cache.
#
# Prefers official `npx playwright install`. On Node ≥24.16 with Playwright before
# 1.60, extract hangs (yauzl / extract-zip); see microsoft/playwright#40724. In that
# case (or when CHEKHOV_BROWSER_INSTALL=curl) we curl + ditto/unzip instead.
#
# Cursor agent shells may inject PLAYWRIGHT_BROWSERS_PATH into an ephemeral sandbox
# cache; clear only those overrides so install/run agree. Intentional paths (CI puts
# browsers under target/ so they ride the zipx LocalDir sbt cache key) are kept.
#
# Usage:
#   ./scripts/install-browsers.sh
#   ./scripts/install-browsers.sh chromium firefox
#   CHEKHOV_BROWSER_INSTALL=curl|npx|auto ./scripts/install-browsers.sh
set -euo pipefail
cd "$(dirname "$0")/.."

case "${PLAYWRIGHT_BROWSERS_PATH:-}" in
  "" | 0 | *cursor-sandbox-cache*) unset PLAYWRIGHT_BROWSERS_PATH || true ;;
esac
unset PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD || true

BROWSERS=("$@")
if [[ ${#BROWSERS[@]} -eq 0 ]]; then
  BROWSERS=(chromium chromium-headless-shell firefox webkit ffmpeg)
fi

node_major="$(node -p "process.versions.node.split('.')[0]")"
node_minor="$(node -p "process.versions.node.split('.')[1]")"
pw_version="$(node -p "require('./node_modules/playwright/package.json').version" 2>/dev/null || echo "0.0.0")"
pw_major="$(echo "$pw_version" | cut -d. -f1)"
pw_minor="$(echo "$pw_version" | cut -d. -f2)"

node_hangs=0
if [[ "$node_major" -gt 24 ]] || { [[ "$node_major" -eq 24 ]] && [[ "$node_minor" -ge 16 ]]; }; then
  node_hangs=1
fi
pw_fixed=0
if [[ "$pw_major" -gt 1 ]] || { [[ "$pw_major" -eq 1 ]] && [[ "$pw_minor" -ge 60 ]]; }; then
  pw_fixed=1
fi

mode="${CHEKHOV_BROWSER_INSTALL:-auto}"
if [[ "$mode" == "auto" ]]; then
  if [[ "$node_hangs" -eq 1 && "$pw_fixed" -eq 0 ]]; then
    mode=curl
  else
    mode=npx
  fi
fi

echo "node=$(node -v) playwright=$pw_version mode=$mode"

if [[ "$(uname -s)" == "Linux" ]] && { [[ "${CI:-}" == "true" ]] || [[ "${GITHUB_ACTIONS:-}" == "true" ]]; }; then
  npx playwright install-deps "${BROWSERS[@]}"
fi

if [[ "$mode" == "npx" ]]; then
  exec npx playwright install "${BROWSERS[@]}"
fi

node - "${BROWSERS[@]}" <<'NODE'
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const https = require('https');
const http = require('http');

const names = process.argv.slice(2);
const reg = require(path.resolve('node_modules/playwright-core/lib/server/registry/index.js'));

function download(url, dest) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(dest);
    const get = url.startsWith('https') ? https.get : http.get;
    const req = get(url, { headers: { 'User-Agent': 'chekhov-install-browsers' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        file.close();
        fs.unlinkSync(dest);
        download(res.headers.location, dest).then(resolve, reject);
        return;
      }
      if (res.statusCode !== 200) {
        reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        res.resume();
        return;
      }
      res.pipe(file);
      file.on('finish', () => file.close(resolve));
    });
    req.on('error', reject);
  });
}

function extract(zipPath, dir) {
  fs.mkdirSync(dir, { recursive: true });
  if (process.platform === 'darwin') {
    const r = spawnSync('ditto', ['-x', '-k', zipPath, dir], { stdio: 'inherit' });
    if (r.status !== 0) throw new Error(`ditto failed: ${r.status}`);
  } else {
    const r = spawnSync('unzip', ['-q', '-o', zipPath, '-d', dir], { stdio: 'inherit' });
    if (r.status !== 0) throw new Error(`unzip failed: ${r.status}`);
  }
}

(async () => {
  console.log(`registry: ${reg.registryDirectory}`);
  for (const name of names) {
    const exe = reg.registry.findExecutable(name);
    if (!exe) {
      console.error(`unknown browser: ${name}`);
      process.exit(1);
    }
    const marker = path.join(exe.directory, 'INSTALLATION_COMPLETE');
    if (fs.existsSync(marker)) {
      console.log(`skip ${name} (already installed at ${exe.directory})`);
      continue;
    }
    const url = exe.downloadURLs[exe.downloadURLs.length - 1];
    const zipPath = path.join(os.tmpdir(), `chekhov-${name}-${Date.now()}.zip`);
    console.log(`download ${name}\n  ${url}`);
    fs.rmSync(exe.directory, { recursive: true, force: true });
    await download(url, zipPath);
    console.log(`extract ${name} -> ${exe.directory}`);
    extract(zipPath, exe.directory);
    fs.unlinkSync(zipPath);
    const execPath = typeof exe.executablePath === 'function' ? exe.executablePath('javascript') : null;
    if (execPath && fs.existsSync(execPath)) fs.chmodSync(execPath, 0o755);
    fs.writeFileSync(marker, '');
    console.log(`ok ${name}`);
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
NODE
