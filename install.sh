#!/usr/bin/env bash
# Build Home Famiglia, install it on the TV over the network and make it the Home screen.
#   TV_HOST=192.168.1.50 ./install.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the IP address of the TV, e.g. TV_HOST=192.168.1.50 $0}"
ADB="${ADB:-adb}"
TV="$TV_HOST"; [[ "$TV" == *:* ]] || TV="$TV:5555"
PKG=com.famiglia.home
ALIAS=$PKG/com.cosyhome.tv.HomeAlias

"$ADB" connect "$TV" >/dev/null
[ -f local.properties ] || [ -n "${ANDROID_HOME:-}" ] || {
  echo "Set ANDROID_HOME, or write sdk.dir=<path to the Android SDK> into local.properties." >&2; exit 1; }
./gradlew assembleRelease -q --console=plain
"$ADB" -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" -s "$TV" shell pm grant "$PKG" android.permission.READ_TV_LISTINGS
# Android 11+ refuses component changes to the shell, so the app turns its own Home alias on.
"$ADB" -s "$TV" shell am broadcast -f 32 -n "$PKG/com.cosyhome.tv.HomeSwitch" -a "$PKG.HOME" --ez on true >/dev/null
sleep 2
"$ADB" -s "$TV" shell cmd package set-home-activity --user 0 "$ALIAS" >/dev/null
NOW=$("$ADB" -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r')
case "$NOW" in
  "$PKG"/*) echo "Home Famiglia is the Home screen. If the Home key still opens the old one, reboot the TV." ;;
  *) echo "Home is still '$NOW': a stock launcher outranks it until it is disabled (see README)." >&2; exit 1 ;;
esac
