#!/usr/bin/env bash
# Desktop packs of the Moonshine cartridge: the blueprint's exported StableHLO graphs, compiled for linux-x86_64.
#
#   scripts/materialize-desktop.sh [cpu-x86_64|vulkan-x86_64|both] [language]
#
# The blueprint (asr-moonshine-v2-streaming-iree) declares Android and SL26xx targets only, so this script does by
# hand what two more entries in its blueprint.json would do: it takes the graphs `materializeCartridge` already
# exported (run scripts/materialize.sh in HomeEvenSmarter, or any Android profile, once), compiles them with the
# same pinned skainet/iree-compiler:3.11.0 image, and lays the result out as a pack_dir the cartridge's PackDir
# reads. The packs are NOT signed: there is no manifest.json. Move the two targets into the blueprint to get
# signed desktop cartridges.
#
# Requires: Docker with skainet/iree-compiler:3.11.0, and BLUEPRINTS_DIR pointing at a SKaiNET-cartridge-blueprints
# checkout whose Moonshine blueprint has been materialized for the language. Result: build/cartridges/<id>/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
BLUEPRINTS_DIR="${BLUEPRINTS_DIR:-$HERE/../../SKaiNET-cartridge-blueprints}"
TARGETS="${1:-both}"; LANG_TAG="${2:-en}"
BP="$BLUEPRINTS_DIR/blueprints/asr-moonshine-v2-streaming-iree"
WORK="$BP/build/blueprint/work"
EXPORT="$WORK/export/$LANG_TAG"
[ -f "$EXPORT/prefill.mlir" ] || { echo "no exported graphs at $EXPORT: materialize the blueprint for '$LANG_TAG' first (set BLUEPRINTS_DIR)"; exit 1; }
[ -f "$WORK/parameters/$LANG_TAG/params.irpa" ] || { echo "no parameter archive at $WORK/parameters/$LANG_TAG"; exit 1; }
case "$TARGETS" in both) TARGETS="cpu-x86_64 vulkan-x86_64";; cpu-x86_64|vulkan-x86_64) ;; *) echo "unknown target $TARGETS"; exit 1;; esac

for T in $TARGETS; do
  ID="asr-moonshine-v2-streaming-iree-$T"
  PACK="$HERE/build/cartridges/$ID"
  STAGE="$HERE/build/desktop-compile/$T/$LANG_TAG"
  mkdir -p "$STAGE"
  case "$T" in
    # haswell = AVX2 + FMA: runs on any x86-64 desktop of the last decade, not just the build machine.
    # Data tiling and the microkernels are what make the CPU pack fast: on a 2019 laptop the encoder goes from
    # 450 ms to 13 ms per window and a decode hop from 850 ms to 75 ms. The packed weights have to be in the
    # module for that, so the CPU decoder graphs carry theirs inline instead of reading the shared archive.
    cpu-x86_64)    INLINE=1; FLAGS="--iree-hal-target-backends=llvm-cpu --iree-llvmcpu-target-triple=x86_64-unknown-linux-gnu --iree-llvmcpu-target-cpu=haswell --iree-global-opt-data-tiling --iree-llvmcpu-enable-ukernels=all" ;;
    # no --iree-vulkan-target: IREE's portable Vulkan defaults rather than a vendor preset
    vulkan-x86_64) INLINE=""; FLAGS="--iree-hal-target-backends=vulkan-spirv" ;;
  esac
  echo "==> $ID ($LANG_TAG)"
  # graph file : output name : source dir : decoder graph (weights in the shared archive, unless inline)
  for G in "frontend:frontend:export:" "encoder:encoder:export:" "adapter:adapter:export:" \
           "prefill:prefill:export:p" "with_past:step:export:p" "frontend:frontend_step:export-frontend-step:"; do
    IFS=: read -r SRC OUTN DIR PARAM <<<"$G"
    [ -f "$STAGE/$OUTN.vmfb" ] && [ "$STAGE/$OUTN.vmfb" -nt "$WORK/$DIR/$LANG_TAG/$SRC.mlir" ] && { echo "    $OUTN.vmfb up to date"; continue; }
    P=""; [ -z "$PARAM" ] || [ -n "$INLINE" ] || P="--iree-opt-export-parameters=model=/out/.$OUTN.scratch.irpa"
    docker run --rm -u "$(id -u):$(id -g)" -v "$WORK/$DIR/$LANG_TAG":/in:ro -v "$STAGE":/out \
      skainet/iree-compiler:3.11.0 compile -- "/in/$SRC.mlir" $FLAGS $P -o "/out/$OUTN.vmfb"
    rm -f "$STAGE"/.*.scratch.irpa
    echo "    $OUTN.vmfb ($(du -h "$STAGE/$OUTN.vmfb" | cut -f1))"
  done

  [ -n "$ID" ] && [ -d "$HERE/build/cartridges" ] || mkdir -p "$HERE/build/cartridges"
  rm -rf -- "${PACK:?}"
  mkdir -p "$PACK/artifacts/model/$LANG_TAG" "$PACK/artifacts/weights/$LANG_TAG" "$PACK/artifacts/tokenizer/$LANG_TAG"
  cp "$STAGE"/*.vmfb "$PACK/artifacts/model/$LANG_TAG/"
  # the runtime opens the archive in every case, so it travels with the CPU pack as well
  cp "$WORK/parameters/$LANG_TAG/params.irpa" "$EXPORT/dec_embed.bin" "$PACK/artifacts/weights/$LANG_TAG/"
  cp "$EXPORT/vocab.bin" "$PACK/artifacts/tokenizer/$LANG_TAG/"
  python3 "$HERE/scripts/descriptor.py" "$BP/blueprint.json" "$PACK/descriptor.json" "$ID" "$T" "$LANG_TAG"
  echo "    -> $PACK ($(du -sh "$PACK" | cut -f1), unsigned)"
done
