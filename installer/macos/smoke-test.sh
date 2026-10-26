#!/usr/bin/env bash
# Mounts a PostgresForAll DMG, "installs" the app into a scratch Applications folder,
# and checks it the way a user would get it:
#   1. The DMG holds PostgresForAll.app and an Applications shortcut to drag it onto.
#   2. Info.plist has the expected version and the app icon.
#   3. PostgresForAll-check runs with the bare PATH a Finder-launched app gets
#      (exit 0, or 2-7 = its documented health-check results).
#   4. The GUI starts and is still running after 15 seconds.
# The launched app gets a scratch HOME, so real settings and Keychain logins are untouched.
#
#   bash installer/macos/smoke-test.sh app/build/installer/PostgresForAll-1.1.0-arm64.dmg 1.1.0
set -uo pipefail

DMG="${1:?usage: smoke-test.sh <dmg> [expected-version]}"
EXPECTED="${2:-}"
work="$(mktemp -d -t pfa-smoke)"
mnt="$work/mnt"
mkdir -p "$mnt" "$work/Applications" "$work/home"
failures=0

check() {
    if [ "$2" = "0" ]; then echo "  ok   $1"; else echo "  FAIL $1"; failures=$((failures + 1)); fi
}

cleanup() {
    [ -n "${GUI_PID:-}" ] && kill "$GUI_PID" 2>/dev/null
    hdiutil detach "$mnt" -quiet 2>/dev/null || true
}
trap cleanup EXIT

hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$mnt" -quiet
check "DMG mounts" $?
[ -d "$mnt/PostgresForAll.app" ]; check "DMG contains PostgresForAll.app" $?
[ -L "$mnt/Applications" ] || [ -e "$mnt/Applications" ]; check "DMG has an Applications shortcut" $?

ditto "$mnt/PostgresForAll.app" "$work/Applications/PostgresForAll.app"
check "app copied out of the DMG" $?
app="$work/Applications/PostgresForAll.app"
plist="$app/Contents/Info.plist"

if [ -n "$EXPECTED" ]; then
    v="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist" 2>/dev/null)"
    [ "$v" = "$EXPECTED" ]; check "version $v is $EXPECTED" $?
fi
icon="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIconFile' "$plist" 2>/dev/null)"
[ -n "$icon" ] && [ -f "$app/Contents/Resources/${icon%.icns}.icns" ]; check "app icon present ($icon)" $?

out="$(env -i HOME="$work/home" USER="${USER:-runner}" PATH=/usr/bin:/bin:/usr/sbin:/sbin \
    "$app/Contents/MacOS/PostgresForAll-check" --port 5999 2>&1)"
rc=$?
echo "$out" | head -3 | sed 's/^/       /'
{ [ "$rc" -eq 0 ] || { [ "$rc" -ge 2 ] && [ "$rc" -le 7 ]; }; }; check "health check ran (exit $rc)" $?

HOME="$work/home" "$app/Contents/MacOS/PostgresForAll" > "$work/gui.log" 2>&1 &
GUI_PID=$!
sleep 15
kill -0 "$GUI_PID" 2>/dev/null; check "GUI still running after 15 s" $?
if ! kill -0 "$GUI_PID" 2>/dev/null; then tail -20 "$work/gui.log"; fi

if [ "$failures" -gt 0 ]; then
    echo "SMOKE TEST FAILED ($failures); files in $work"
    exit 1
fi
echo "SMOKE TEST PASSED"
