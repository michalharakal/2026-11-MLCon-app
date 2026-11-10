#!/usr/bin/env bash
# Materialize the app's cartridges from the public blueprints. Weights are downloaded by the blueprint plugin,
# compiled with the pinned IREE tools image, packed and signed; nothing model-related ever lands in this repository.
#
#   scripts/materialize.sh [asr|nlu|all] [vulkan-arm64|cpu-arm64|both]
#
# Requires: JDK 21+, Docker (skainet/iree-compiler:3.11.0 is built on demand from SKaiNET-IREE-tools),
# a sibling checkout of SKaiNET-cartridge-blueprints (override with BLUEPRINTS_DIR), and the signing key in
# CARTRIDGE_SIGNING_KEY (PEM, `openssl genpkey -algorithm ed25519`). Results: build/cartridges/<cartridge id>/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
BLUEPRINTS_DIR="${BLUEPRINTS_DIR:-$HERE/../../SKaiNET-cartridge-blueprints}"
IREE_TOOLS_DIR="${IREE_TOOLS_DIR:-}"
WHAT="${1:-all}"; TARGETS="${2:-both}"
OUT="$HERE/build/cartridges"

[ -d "$BLUEPRINTS_DIR/blueprints" ] || { echo "blueprints checkout not found at $BLUEPRINTS_DIR (set BLUEPRINTS_DIR)"; exit 1; }
if [ -z "${CARTRIDGE_SIGNING_KEY:-}" ] && [ -z "${SIGNING_KEY_FILE:-}" ]; then
  echo "no signing key: export CARTRIDGE_SIGNING_KEY=\"\$(cat key.pem)\" or SIGNING_KEY_FILE=key.pem (openssl genpkey -algorithm ed25519)"; exit 1
fi
if ! docker image inspect skainet/iree-compiler:3.11.0 >/dev/null 2>&1; then
  [ -n "$IREE_TOOLS_DIR" ] || { echo "skainet/iree-compiler:3.11.0 missing: clone SKaiNET-IREE-tools and set IREE_TOOLS_DIR, or run 'make compiler' there"; exit 1; }
  (cd "$IREE_TOOLS_DIR" && make compiler)
fi

# The tool catalog is code (HomeTools.catalog): render it before the NLU pack bakes it into its prompt.
case "$WHAT" in all|nlu) (cd "$HERE" && ./gradlew -q :core:writeToolCatalog);; esac

case "$TARGETS" in both) TARGETS="vulkan-arm64 cpu-arm64";; vulkan-arm64|cpu-arm64) ;; *) echo "unknown target set $TARGETS"; exit 1;; esac
case "$WHAT" in all) WHAT="asr nlu";; asr|nlu) ;; *) echo "unknown selection $WHAT"; exit 1;; esac

materialize() { # <blueprint module> <profile path>
  local module="$1" profile="$2"
  echo "==> $module  profile=$(basename "$profile")"
  local key_args=()
  [ -n "${SIGNING_KEY_FILE:-}" ] && key_args=(-PsigningKeyFile="$(cd "$(dirname "$SIGNING_KEY_FILE")" && pwd)/$(basename "$SIGNING_KEY_FILE")")
  (cd "$BLUEPRINTS_DIR" && ./gradlew ":blueprints:$module:materializeCartridge" -Pprofile="$profile" "${key_args[@]}")
  mkdir -p "$OUT"
  for pack in "$BLUEPRINTS_DIR/blueprints/$module/build/cartridge"/*/; do
    [ -f "$pack/descriptor.json" ] || continue
    local id; id="$(basename "$pack")"
    [ -n "$id" ] && [ -d "$OUT" ] || { echo "refusing to replace '$OUT/$id'"; exit 1; }
    rm -rf -- "${OUT:?}/${id:?}"
    # hard links when both live on one file system (the packs are gigabytes); a plain copy otherwise
    cp -rl "$pack" "$OUT/$id" 2>/dev/null || cp -r "$pack" "$OUT/$id"
    echo "    -> $OUT/$id ($(du -sh "$OUT/$id" | cut -f1))"
  done
}

for t in $TARGETS; do
  for w in $WHAT; do
    case "$w" in
      asr) materialize asr-moonshine-v2-streaming-iree "$HERE/cartridges/profiles/asr-moonshine.$t.en.json";;
      nlu) materialize nlu-functiongemma-270m-iree "$HERE/cartridges/profiles/nlu-functiongemma.$t.home.json";;
    esac
  done
done
echo "done: $(ls "$OUT")"
