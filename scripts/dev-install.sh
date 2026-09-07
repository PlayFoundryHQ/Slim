#!/usr/bin/env bash
#
# One-shot developer (re)install for Slim on a USB-connected device.
#
# A fresh install wipes the things you otherwise have to re-tap by hand
# every reinstall cycle: the default-Home (launcher) assignment and the
# notification-listener grant that the "Active" section depends on. This
# script restores all of them in one go so the reinstall loop is friction-free.
#
# Usage:
#   scripts/dev-install.sh          # build + install + set up + launch
#   scripts/dev-install.sh --no-build   # skip Gradle, just re-apply the setup
#
# Target a specific device by exporting ANDROID_SERIAL (adb honors it natively):
#   ANDROID_SERIAL=8cda5b9a scripts/dev-install.sh
#
set -euo pipefail

PKG="io.github.playfoundryhq.slim"
MAIN="$PKG/.MainActivity"
LISTENER="$PKG/$PKG.SlimNotificationListener"

cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--no-build" ]]; then
    echo "==> Building + installing debug APK"
    ./gradlew installDebug
fi

echo "==> Setting Slim as the default Home / launcher (no chooser dialog)"
adb shell cmd package set-home-activity "$MAIN"

echo "==> Granting notification-listener access (powers the Active section)"
adb shell cmd notification allow_listener "$LISTENER"

echo "==> Launching Slim"
adb shell am start -n "$MAIN" >/dev/null

echo "Done — Slim is installed, default launcher, and has notification access."
