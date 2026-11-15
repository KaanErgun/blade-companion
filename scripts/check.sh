#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for forbidden in .github/workflows .github/dependabot.yml .github/dependabot.yaml .gitlab-ci.yml .gitlab-ci.yaml; do
    if [[ -e "$forbidden" ]]; then
        printf 'Hosted CI is not allowed: %s\n' "$forbidden" >&2
        exit 1
    fi
done
sh -n gradlew
bash -n scripts/check.sh
command -v rg >/dev/null
if rg -n '^import (android\.|androidx\.)' app/src/main/java/com/kaanergun/bladecompanion/core; then
    echo 'The core package must not import Android.' >&2
    exit 1
fi
exec ./gradlew --offline --no-daemon --console=plain :check
