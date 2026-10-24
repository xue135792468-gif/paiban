#!/usr/bin/env bash
# Run Playwright specs without hand-rolling the Maven/JDK paths.
# Examples: scripts/e2e.sh e2e/substitution-swap.spec.ts
#           scripts/e2e.sh --list
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.tools/env.sh" ]; then
  # shellcheck disable=SC1091
  source "$ROOT/.tools/env.sh"
fi

# Git Bash hands MSYS paths to mvnw.cmd, which cannot read them.
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    export MAVEN_ARGS="-s $(cygpath -w "$ROOT/.tools/maven-settings.xml") -Dmaven.repo.local=$(cygpath -w "$ROOT/.tools/m2repo")"
    ;;
esac

# Isolated ports so a running dev server is never silently reused.
export PAIBAN_E2E_ISOLATED="${PAIBAN_E2E_ISOLATED:-1}"

cd "$ROOT/frontend"
exec npx playwright test "$@"
