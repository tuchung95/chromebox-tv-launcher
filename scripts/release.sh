#!/bin/bash
# Publishes a new Chromebox TV release on GitHub.
#
#   scripts/release.sh "What changed in this version"
#
# Raises the version, runs the tests, builds an APK signed with the release key, commits
# and tags the version, then creates a GitHub release with the APK and updates.json.
# The launcher's in-app updater reads updates.json from the latest release.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
REPO="tuchung95/chromebox-tv-launcher"
NOTES="${1:-Bản cập nhật Chromebox TV.}"

export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}"
SDK="$HOME/Library/Android/sdk"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"

if [[ ! -f "$HOME/.android/chromebox-tv-release.properties" ]]; then
  echo "Missing ~/.android/chromebox-tv-release.properties: updates must be signed with the release key." >&2
  exit 1
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Commit or stash your changes first." >&2
  exit 1
fi

code=$(( $(sed -n 's/^VERSION_CODE=//p' version.properties) + 1 ))
name="1.0.$code"
tag="v$name"
printf 'VERSION_CODE=%s\nVERSION_NAME=%s\n' "$code" "$name" > version.properties

./gradlew -q testDebugUnitTest assembleRelease

out="$ROOT/build/release"
rm -rf "$out"
mkdir -p "$out"
apk_name="Chromebox-TV-$name.apk"
apk="$out/$apk_name"
cp app/build/outputs/apk/release/app-release.apk "$apk"

if ! "$BUILD_TOOLS/apksigner" verify --print-certs "$apk" | grep -q "CN=Chromebox TV"; then
  echo "The APK is not signed with the release key." >&2
  git checkout -- version.properties
  exit 1
fi

python3 - "$apk" "$name" "$code" "https://github.com/$REPO/releases/download/$tag/$apk_name" "$out/updates.json" <<'PY'
import hashlib, json, os, sys
apk, name, code, url, out = sys.argv[1:]
entry = {
    "package": "local.chromebox.tvlauncher",
    "name": "Chromebox TV",
    "versionCode": int(code),
    "versionName": name,
    "apk": url,
    "sha256": hashlib.sha256(open(apk, "rb").read()).hexdigest(),
    "size": os.path.getsize(apk),
}
json.dump({"apps": [entry]}, open(out, "w"), ensure_ascii=False, indent=2)
PY

git add version.properties
git commit -q -m "Release $name" ${RELEASE_COMMIT_TRAILER:+-m "$RELEASE_COMMIT_TRAILER"}
git tag "$tag"
git push -q origin HEAD "$tag"
gh release create "$tag" "$apk" "$out/updates.json" --repo "$REPO" --title "Chromebox TV $name" --notes "$NOTES"
echo "Released Chromebox TV $name"
