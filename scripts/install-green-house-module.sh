#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: bash scripts/install-green-house-module.sh /path/to/green-house" >&2
    exit 1
fi

SOURCE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION_ROOT="$(cd -- "$1" && pwd)"
BACKEND="$DESTINATION_ROOT/backend"
MODULE="$BACKEND/modules/slip-image-core"
SETTINGS="$BACKEND/settings.gradle.kts"
BUILD="$BACKEND/build.gradle.kts"
DOCKER="$BACKEND/Dockerfile"

fail() { echo "$*" >&2; exit 1; }

# Check the destination before copying or changing any host files.
[ ! -e "$MODULE" ] && [ ! -L "$MODULE" ] || fail "Module already exists: $MODULE. Review/update it manually."
[ -d "$SOURCE_ROOT/slip-image-core/src" ] || fail "Core sources are missing."
[ -f "$SOURCE_ROOT/slip-image-core/build.gradle.kts" ] || fail "Core build file is missing."
for path in "$SETTINGS" "$BUILD" "$DOCKER"; do
    [ -f "$path" ] || fail "Expected Green House build file is missing: $path"
done
if grep -q 'slip-image-core' "$SETTINGS" "$BUILD"; then
    fail "Existing slip-image-core configuration found. No files were changed."
fi
if grep -q 'FAIL_ON_PROJECT_REPOS' "$SETTINGS"; then
    fail "Central repository policy changed. Follow docs/MONOREPO_INTEGRATION.md."
fi
[ "$(grep -cE '^dependencies[[:space:]]*[{]' "$BUILD" || true)" = 1 ] || fail "Expected one top-level dependencies block."
[ "$(grep -cE '^COPY src src[[:space:]]*$' "$DOCKER" || true)" = 1 ] || fail "Dockerfile differs from the verified layout."
if grep -qE '^COPY modules[[:space:]]' "$DOCKER"; then
    fail "Dockerfile already copies modules. Review it manually."
fi

SETTINGS_TMP=''
BUILD_TMP=''
DOCKER_TMP=''
cleanup() {
    for path in "$SETTINGS_TMP" "$BUILD_TMP" "$DOCKER_TMP"; do
        if [ -n "$path" ]; then rm -f -- "$path"; fi
    done
}
trap cleanup EXIT
SETTINGS_TMP="$(mktemp "$BACKEND/.slip-settings.XXXXXX")"
BUILD_TMP="$(mktemp "$BACKEND/.slip-build.XXXXXX")"
DOCKER_TMP="$(mktemp "$BACKEND/.slip-docker.XXXXXX")"

awk '{ sub(/\r$/, ""); print } END {
    print ""
    print "include(\":slip-image-core\")"
    print "project(\":slip-image-core\").projectDir = file(\"modules/slip-image-core\")"
}' "$SETTINGS" > "$SETTINGS_TMP"

awk '
    { sub(/\r$/, "") }
    /^dependencies[[:space:]]*[{]/ {
        print
        print "\timplementation(project(\":slip-image-core\"))"
        next
    }
    index($0, "systemProperty(\"greenhouse.cli.runtime-classpath\", sourceSets[\"main\"].runtimeClasspath.asPath)") {
        match($0, /^[ \t]*/)
        indent = substr($0, 1, RLENGTH)
        print indent "doFirst {"
        print "\t" $0
        print indent "}"
        next
    }
    { print }
' "$BUILD" > "$BUILD_TMP"

awk '{ sub(/\r$/, "") }
    /^COPY src src$/ { print "COPY modules modules" }
    { print }
' "$DOCKER" > "$DOCKER_TMP"

mkdir -p -- "$MODULE"
cp -R -- "$SOURCE_ROOT/slip-image-core/src" "$MODULE/"
cp -- "$SOURCE_ROOT/slip-image-core/build.gradle.kts" "$MODULE/"
# Preserve host file modes, including group-readable settings.
cat "$SETTINGS_TMP" > "$SETTINGS"
cat "$BUILD_TMP" > "$BUILD"
cat "$DOCKER_TMP" > "$DOCKER"

echo "Installed source module: $MODULE"
echo "Review git diff in Green House, then run in backend:"
echo "  ./gradlew :slip-image-core:test check bootJar --no-daemon"
echo "Connect the Sales singleton Bean using docs/MIGRATION_GREEN_HOUSE.md."
echo "Record the module/deployment changes in the host architecture/deployment docs."
echo "No commit or push was performed."
