#!/usr/bin/env bash
#
# bump.sh — raise the project version in build.gradle.kts (the single source of truth).
#
# Mirrors the -js reference's `deno task bump` UX:
#
#   ./scripts/bump.sh            # patch:  0.1.2 -> 0.1.3   (default)
#   ./scripts/bump.sh patch      # patch:  0.1.2 -> 0.1.3
#   ./scripts/bump.sh minor      # minor:  0.1.2 -> 0.2.0
#   ./scripts/bump.sh major      # major:  0.1.2 -> 1.0.0
#
# Unlike a no-op "re-sync", this RAISES the version. build.gradle.kts is the only
# place the version lives, so nothing else needs to be propagated.
#
# After bumping, the usual release flow is:
#   git commit -am "bump version to vX.Y.Z"
#   ./gradlew release        # tags vX.Y.Z and pushes the tag -> CI publishes
#
set -euo pipefail

# Resolve repo root (parent of this script's dir) so it works from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_FILE="$ROOT/build.gradle.kts"

kind="${1:-patch}"
case "$kind" in
  patch|minor|major) ;;
  *)
    echo "❌ bump: unknown release kind \"$kind\" — use patch | minor | major" >&2
    exit 1
    ;;
esac

if [ ! -f "$GRADLE_FILE" ]; then
  echo "❌ bump: $GRADLE_FILE not found" >&2
  exit 1
fi

# Extract the current version from the `version = "X.Y.Z"` line.
current="$(grep -E '^[[:space:]]*version[[:space:]]*=[[:space:]]*"[0-9]+\.[0-9]+\.[0-9]+"' "$GRADLE_FILE" \
  | head -n1 \
  | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')"

if [ -z "$current" ]; then
  echo "❌ bump: could not find a 'version = \"X.Y.Z\"' line in build.gradle.kts" >&2
  exit 1
fi

IFS='.' read -r major minor patch <<< "$current"

case "$kind" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
esac

to="${major}.${minor}.${patch}"

# Targeted replace of ONLY the top-level `version = "..."` line so the rest of
# build.gradle.kts formatting is untouched. The anchor `^[[:space:]]*version =`
# avoids touching dependency version strings or the chicoryVersion val.
tmp="$(mktemp)"
sed -E "s/^([[:space:]]*version[[:space:]]*=[[:space:]]*)\"[0-9]+\.[0-9]+\.[0-9]+\"/\1\"${to}\"/" \
  "$GRADLE_FILE" > "$tmp"
mv "$tmp" "$GRADLE_FILE"

echo "✅ build.gradle.kts → ${to}  (${kind} bump from ${current})"
