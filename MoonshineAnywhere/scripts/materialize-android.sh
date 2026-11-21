#!/usr/bin/env bash
# Android packs of the Moonshine cartridge, for trying it on a device.
#
#   scripts/materialize-android.sh [arm64|armv7] [cpu|vulkan|both]
#
# Runs the blueprint's own recipe steps in the SKaiNET-cartridge-blueprints checkout (weights pinned by digest,
# exported from the Kotlin model definition, compiled with the pinned IREE image) and lays the result out as a
# pack_dir under build/cartridges/<id>/.
#
# It stops short of `materializeCartridge` on purpose: that task writes a descriptor only with performance measured
# on a device, and a signed manifest. These packs are what you measure with. Once you have numbers, put them and a
# `signing` block into a profile and run `materializeCartridge`, as HomeEvenSmarter does.
#
# Requires: JDK 21+, Docker with skainet/iree-compiler:3.11.0, BLUEPRINTS_DIR.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
BLUEPRINTS_DIR="${BLUEPRINTS_DIR:-$HERE/../../SKaiNET-cartridge-blueprints}"
ABI="${1:-arm64}"; KIND="${2:-both}"; LANG_TAG=en
[ -d "$BLUEPRINTS_DIR/blueprints" ] || { echo "blueprints checkout not found at $BLUEPRINTS_DIR (set BLUEPRINTS_DIR)"; exit 1; }
case "$ABI" in arm64|armv7) ;; *) echo "unknown ABI $ABI (arm64|armv7)"; exit 1;; esac
case "$KIND" in both) KIND="cpu vulkan";; cpu|vulkan) ;; *) echo "unknown kind $KIND (cpu|vulkan|both)"; exit 1;; esac
MODULE=asr-moonshine-v2-streaming-iree
BP="$BLUEPRINTS_DIR/blueprints/$MODULE"; WORK="$BP/build/blueprint/work"
P=":blueprints:$MODULE"
mkdir -p "$HERE/build/cartridges"
for K in $KIND; do
  T="$K-$ABI"; ID="$MODULE-$T"; PACK="$HERE/build/cartridges/$ID"
  echo "==> $ID"
  (cd "$BLUEPRINTS_DIR" && ./gradlew -q -Pprofile="$HERE/profiles/asr-moonshine.$T.$LANG_TAG.json" \
    $P:compileEnFrontend $P:compileEnEncoder $P:compileEnAdapter $P:compileEnPrefill $P:compileEnWithPast \
    $P:compileEnFrontendStep $P:weightsEn)
  rm -rf -- "${PACK:?}"
  mkdir -p "$PACK/artifacts/model/$LANG_TAG" "$PACK/artifacts/weights/$LANG_TAG" "$PACK/artifacts/tokenizer/$LANG_TAG"
  cp "$WORK/compile/$T/$LANG_TAG"/*.vmfb "$PACK/artifacts/model/$LANG_TAG/"
  cp "$WORK/weights/$LANG_TAG"/* "$PACK/artifacts/weights/$LANG_TAG/"
  cp "$WORK/export/$LANG_TAG/vocab.bin" "$PACK/artifacts/tokenizer/$LANG_TAG/"
  python3 "$HERE/scripts/descriptor.py" "$BP/blueprint.json" "$PACK/descriptor.json" "$ID" "$T" "$LANG_TAG"
  echo "    -> build/cartridges/$ID ($(du -sh "$PACK" | cut -f1), unsigned, unmeasured)"
done
