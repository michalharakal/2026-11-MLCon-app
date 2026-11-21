#!/usr/bin/env bash
# Pushes the Moonshine packs that match the connected device's ABI to the app.
#
#   scripts/install-android-packs.sh [packs dir] [cpu|vulkan|both]
#
# The packs go to the app's external files directory; the app moves each into its private storage the first time
# the engine is opened (the CPU graphs are native libraries, which Android loads from private storage only).
# Install and start the app once before this, so the directory exists. ANDROID_SERIAL picks the device.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PACKS="${1:-$HERE/build/cartridges}"; KIND="${2:-both}"
PKG=sk.ainet.examples.moonshine
case "$KIND" in both) KIND="cpu vulkan";; cpu|vulkan) ;; *) echo "unknown kind $KIND (cpu|vulkan|both)"; exit 1;; esac
case "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')" in
  arm64-v8a) ABI=arm64 ;;
  armeabi-v7a) ABI=armv7 ;;
  *) echo "the cartridge has ARM targets only; this device is $(adb shell getprop ro.product.cpu.abi)"; exit 1 ;;
esac
DEST="/sdcard/Android/data/$PKG/files/cartridges"
adb shell "mkdir -p $DEST"
for K in $KIND; do
  ID="asr-moonshine-v2-streaming-iree-$K-$ABI"
  [ -f "$PACKS/$ID/descriptor.json" ] || { echo "no pack at $PACKS/$ID (scripts/materialize-android.sh $ABI $K)"; exit 1; }
  echo "==> $ID"
  adb shell "rm -rf $DEST/$ID"
  adb push "$PACKS/$ID" "$DEST/" | tail -1
done
echo "done: open the app and tap 'Rescan packs'"
