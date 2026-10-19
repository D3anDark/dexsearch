#!/usr/bin/env bash
# Publishes the current commit as an update: builds it, pushes it and attaches the APK to release v<versionCode>.
# The release starts .github/workflows/pages.yml, which puts the APK and its versionCode on GitHub Pages, where
# installed apps find it (Update.kt). Needs the gh CLI, logged in.
set -euo pipefail
cd "$(dirname "$0")"
[ -z "$(git status --porcelain)" ] || { echo "Commit first: the versionCode is the commit count." >&2; exit 1; }
./build.sh
code=$(git rev-list --count HEAD)
git push origin HEAD
gh release create "v$code" dexsearch.apk --target "$(git rev-parse HEAD)" --title "Version $code" \
    --notes "$(git log -1 --format=%s)"
