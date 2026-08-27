#!/bin/bash
# Fetch the WeSpeaker ResNet34-LM ONNX speaker-embedding model into the
# speakerid asset source set (local/staging flavors only — see the
# sourceSets block in app/build.gradle.kts).
#
# The 25 MB .onnx is deliberately NOT in git. Builds succeed without it —
# SpeakerEmbedder.create() returns null and speaker-ID self-disables — but
# voice enrollment needs it, so build-install.sh runs this as a preflight.
#
# Model provenance: HuggingFace Wespeaker/wespeaker-voxceleb-resnet34-LM,
# validated in dashieapp_staging/wake-word-training/speaker-id/
# (results/LIBRI_DEV_BASELINE.md). If the URL ever changes, keep the sha256
# below in sync with a freshly verified copy.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_DIR="$REPO_ROOT/app/src/speakeridAssets/assets/models/speakerid"
DEST="$DEST_DIR/wespeaker_resnet34_lm.onnx"
URL="https://huggingface.co/Wespeaker/wespeaker-voxceleb-resnet34-LM/resolve/main/voxceleb_resnet34_LM.onnx"
EXPECTED_SHA256="7bb2f06e9df17cdf1ef14ee8a15ab08ed28e8d0ef5054ee135741560df2ec068"

# Local mirror: the benchmark harness already downloaded the same file.
LOCAL_MIRROR="$HOME/projects/dashieapp_staging/wake-word-training/speaker-id/models/wespeaker_resnet34_LM.onnx"

verify() {
  local got
  got=$(shasum -a 256 "$1" | awk '{print $1}')
  [ "$got" = "$EXPECTED_SHA256" ]
}

if [ -f "$DEST" ] && verify "$DEST"; then
  echo "✓ speaker-ID model already present: $DEST"
  exit 0
fi

mkdir -p "$DEST_DIR"

if [ -f "$LOCAL_MIRROR" ] && verify "$LOCAL_MIRROR"; then
  cp "$LOCAL_MIRROR" "$DEST"
  echo "✓ speaker-ID model copied from local mirror"
  exit 0
fi

echo "Downloading speaker-ID model (~25 MB) from HuggingFace..."
curl -fSL --retry 3 -o "$DEST.tmp" "$URL"
if ! verify "$DEST.tmp"; then
  rm -f "$DEST.tmp"
  echo "✗ sha256 mismatch on downloaded model — refusing to install" >&2
  exit 1
fi
mv "$DEST.tmp" "$DEST"
echo "✓ speaker-ID model installed: $DEST"
