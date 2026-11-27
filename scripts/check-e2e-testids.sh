#!/usr/bin/env bash
# Check that literal getByTestId('...') calls in frontend/e2e/ match a real
# data-testid in frontend/src/ (static literal or dynamic template prefix).
#
# Usage: scripts/check-e2e-testids.sh
# Exit:  0 = all literal getByTestId calls resolve; 1 = at least one dead testid
set -euo pipefail
cd "$(dirname "$0")/.."

node -e '
const fs = require("node:fs");
const path = require("node:path");

function walk(dir, filter) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walk(full, filter));
    } else if (filter(full)) {
      out.push(full);
    }
  }
  return out;
}

const srcFiles = walk("frontend/src", (f) =>
  /\.(vue|ts|tsx|js)$/.test(f) && !/\.(spec|test)\.(ts|tsx|js)$/.test(f)
);

const literals = new Set();
const prefixes = new Set();

for (const file of srcFiles) {
  const text = fs.readFileSync(file, "utf8");

  // 1. Static literals: data-testid="foo", menu-test-id="foo", testId: "foo"
  for (const m of text.matchAll(/(?:data-testid|[a-z-]*test-id|[A-Za-z]*TestId)["\x27]?\s*[:=]\s*["\x27]([A-Za-z0-9_.-]+)["\x27]/g)) {
    literals.add(m[1]);
  }

  // 2. Quoted literals inside :data-testid="..." or :data-testid=\x27...\x27
  for (const m of text.matchAll(/:data-testid\s*=\s*"([^"]*)"/g)) {
    for (const q of m[1].matchAll(/["\x27]([A-Za-z0-9_.-]+)["\x27]/g)) {
      literals.add(q[1]);
    }
    for (const t of m[1].matchAll(/`([A-Za-z0-9_.-]+)\$\{/g)) {
      prefixes.add(t[1]);
    }
  }
  for (const m of text.matchAll(/:data-testid\s*=\s*\x27([^\x27]*)\x27/g)) {
    for (const q of m[1].matchAll(/["\x27]([A-Za-z0-9_.-]+)["\x27]/g)) {
      literals.add(q[1]);
    }
    for (const t of m[1].matchAll(/`([A-Za-z0-9_.-]+)\$\{/g)) {
      prefixes.add(t[1]);
    }
  }

  // 3. Kebab/snake template literal prefixes anywhere in non-test src (covers helper functions like rule-period-${n})
  for (const m of text.matchAll(/`([A-Za-z0-9_.-]*[-_])\$\{/g)) {
    prefixes.add(m[1]);
  }

  // 4. String concatenation adjacent to data-testid: data-testid: "foo-" + ...
  for (const m of text.matchAll(/data-testid["\x27]?\s*[:=]\s*["\x27]([A-Za-z0-9_.-]+)["\x27]\s*\+/g)) {
    prefixes.add(m[1]);
  }
}

const e2eFiles = walk("frontend/e2e", (f) => /\.ts$/.test(f)).sort();
const missing = [];
let checked = 0;

for (const file of e2eFiles) {
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (/^\s*\/\//.test(line)) continue;
    for (const m of line.matchAll(/\.getByTestId\(\s*(["\x27])([A-Za-z0-9_.-]+)\1\s*\)/g)) {
      const id = m[2];
      checked += 1;
      if (literals.has(id)) continue;
      let matchedPrefix = false;
      for (const p of prefixes) {
        if (id.startsWith(p)) {
          matchedPrefix = true;
          break;
        }
      }
      if (!matchedPrefix) {
        missing.push(`${file.replace(/\\/g, "/")}:${i + 1}: missing data-testid "${id}"`);
      }
    }
  }
}

if (missing.length > 0) {
  for (const msg of missing) console.error(msg);
  console.error(`e2e testids check failed: ${missing.length} unresolved getByTestId call(s) out of ${checked}`);
  process.exit(1);
}

console.log(`e2e testids ok (${checked} literal references checked)`);
'
