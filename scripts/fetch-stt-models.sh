#!/bin/bash
# Fetch the sherpa-onnx on-device STT engine (JNI lib) + the two moonshine
# models into the sttEngine source set (the `local` flavor ONLY, since
# 2026-08-04 — see the sourceSets block in app/build.gradle.kts. Staging used
# to bundle them too and now exercises the real download lane instead).
#
# ~200 MB total, deliberately NOT in git. Builds succeed without it — the
# sherpa STT providers feature-detect the lib+assets at runtime and
# self-disable — but on-device STT testing needs it.
#
# Provenance + bench results: dashieapp_staging
# .reference/build-plans/20260727_SHERPA_ONNX_STT_SPIKE.md (Mio + Fire numbers)
# and 20260728_SHERPA_STT_INTEGRATION_PLAN.md (structure). All artifacts from
# github.com/k2-fsa/sherpa-onnx releases (v1.13.4 + asr-models tags), EXCEPT
# moonshine-base since 2026-08-23 (Dashie-hosted .onnx re-export — see the note
# at its fetch below). All sha256-pinned.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/app/src/sttEngine"
# 🔴 The VAD lives in its OWN source set because it ships to a DIFFERENT set of flavors.
# src/sttEngine/assets is the ~177 MB of models (`local` only); src/sttVad/assets is
# 629 KB that ships everywhere alongside the engine .so. Splitting the DIRECTORY is what lets
# build.gradle.kts express that without a per-file rule — see the srcDir loops there.
VAD_DEST="$REPO_ROOT/app/src/sttVad"
CACHE="${DASHIE_STT_CACHE:-$HOME/.cache/dashie-stt-models}"
GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p "$CACHE" "$DEST/jniLibs/arm64-v8a" "$DEST/jniLibs/armeabi-v7a" \
  "$DEST/assets/models/stt" "$VAD_DEST/assets/models/stt"

fetch() { # name url sha256
  local f="$CACHE/$1"
  if [ ! -f "$f" ] || [ "$(shasum -a 256 "$f" | awk '{print $1}')" != "$3" ]; then
    echo "==> downloading $1"
    curl -fL --retry 3 -o "$f" "$2"
  fi
  local got; got=$(shasum -a 256 "$f" | awk '{print $1}')
  if [ "$got" != "$3" ]; then
    echo "❌ sha256 mismatch for $1 (got $got)"; exit 1
  fi
  echo "✓ $1"
}

# ── engine: libsherpa-onnx-jni.so, ORT statically linked ─────────────────
# (the plain android tarball ships its own libonnxruntime.so which would
# collide with the Microsoft ORT AAR speaker-ID already uses — keep static)
fetch sherpa-android-static.tar.bz2 \
  "$GH/v1.13.4/sherpa-onnx-v1.13.4-android-static-link-onnxruntime.tar.bz2" \
  e23223a35d4878b0f61f6d0ae47095ce090fd10d0d8ce41550f91fdbf7d431b1
# 🔴 BOTH ABIs, since 2026-08-25. This script previously extracted arm64-v8a ONLY, and the
# build.gradle comment beside `abiFilters` asserted that upstream's static build "has no
# armeabi-v7a slice". That assertion is FALSE — verified against this exact tarball, which
# carries four slices (arm64-v8a, armeabi-v7a, x86, x86_64). The 32-bit one is a genuine peer:
# ELF 32-bit ARM EABI5, 16,110,348 B, and `strings` finds ZERO `libonnxruntime.so` references,
# i.e. ORT is statically linked exactly as in the arm64 build.
#
# Why it matters enough to spell out: every device the "cheap HA dashboard" market actually
# converts is 32-bit. Measured 2026-08-25 — Echo Show 5 (LineageOS) and the onn Full HD stick
# both report `armeabi-v7a,armeabi` with no 64-bit slice at all, so on-device STT could not
# load on either, while the 48 MB model still downloaded happily and reported "installed".
# The arm64-only extraction, not upstream, was the whole cause.
for abi in arm64-v8a armeabi-v7a; do
  tar xjf "$CACHE/sherpa-android-static.tar.bz2" -C "$CACHE" "./jniLibs/$abi/libsherpa-onnx-jni.so"
  cp "$CACHE/jniLibs/$abi/libsherpa-onnx-jni.so" "$DEST/jniLibs/$abi/"
done

# ── silero VAD (endpointing for the buffered/offline providers) ──────────
# Goes to VAD_DEST, which ships in EVERY flavor — see the note at VAD_DEST above. A stale copy
# from before the 08-04 split would be merged as a duplicate asset, so remove it.
fetch silero_vad.onnx "$GH/asr-models/silero_vad.onnx" \
  9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6
rm -f "$DEST/assets/models/stt/silero_vad.onnx"
cp "$CACHE/silero_vad.onnx" "$VAD_DEST/assets/models/stt/"

# ── models ───────────────────────────────────────────────────────────────
# 🔴 zipf20m (streaming zipformer 20M) was REMOVED 2026-08-04 — it was DEAD PAYLOAD.
# Nothing ever loaded it: SherpaEngineLoader builds Moonshine recognizers only, and the
# only other mention of the name in the tree was a comment. It was fetched, bundled and
# never read — 43.6 MB in every local/staging APK, 12% of the staging artifact.
# A stale copy left by an older run of this script would still be merged into the APK,
# so remove it rather than just stopping the fetch.
rm -rf "$DEST/assets/models/stt/zipf20m"

fetch moonshine-tiny.tar.bz2 \
  "$GH/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2" \
  9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d
T=sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27
tar xjf "$CACHE/moonshine-tiny.tar.bz2" -C "$CACHE" \
  $T/encoder_model.ort $T/decoder_model_merged.ort $T/tokens.txt
mkdir -p "$DEST/assets/models/stt/moonshine-tiny"
cp "$CACHE/$T"/encoder_model.ort "$CACHE/$T"/decoder_model_merged.ort \
   "$CACHE/$T"/tokens.txt "$DEST/assets/models/stt/moonshine-tiny/"

# ⚠️ moonshine-base is the ONE model NOT fetched from k2-fsa (2026-08-23). It is the un-fused
# `.onnx` export of the same weights, re-hosted by Dashie because HuggingFace serves individual
# files rather than a tarball. Wire 111 MB -> 48 MB, on-disk 141 MB -> 64 MB. The reasoning, and
# what it costs the edition-independence rule, is in SttModelRegistry's KDoc ("PARTIALLY SPENT").
#
# 🔴 THIS URL + DIGEST ARE A HAND-MIRROR OF SttModelRegistry.FAMILIES, ON PURPOSE — the model a
# developer bundles and the model a user downloads are then provably the same artifact. The
# registry KDoc states the rule; `SttModelBundleParityTest` is what enforces it. Bump one, bump
# the other, or that test fails.
#
# ⚠️ ALSO NOTE THE MEMBER FILENAMES BELOW ARE `.onnx` WHILE moonshine-tiny's ARE `.ort`. A dev
# APK built before this change bundles `.ort` assets; SherpaEngineLoader probes the asset listing
# rather than trusting the registry for bundled models, so such an APK keeps working. Re-run this
# script to move a dev build onto the smaller export.
fetch moonshine-base.tar.bz2 \
  "https://cseaywxcvnxcsypaqaid.supabase.co/storage/v1/object/public/stt-models/moonshine-base-en-onnx-2026-08-23.tar.bz2" \
  89781f83d51cc082f6da98a4e61eb39607b75e31bd0bdb858bd408becfe0da08
B=moonshine-base-en-onnx-2026-08-23
# Remove the previous `.ort` members: this directory is merged into the APK wholesale, so a stale
# encoder_model.ort would ship ALONGSIDE the new .onnx and quietly double the bundled model size.
rm -rf "$DEST/assets/models/stt/moonshine-base"
tar xjf "$CACHE/moonshine-base.tar.bz2" -C "$CACHE" \
  $B/encoder_model.onnx $B/decoder_model_merged.onnx $B/tokens.txt
mkdir -p "$DEST/assets/models/stt/moonshine-base"
cp "$CACHE/$B"/encoder_model.onnx "$CACHE/$B"/decoder_model_merged.onnx \
   "$CACHE/$B"/tokens.txt "$DEST/assets/models/stt/moonshine-base/"

echo ""
echo "✅ sttEngine populated:"
du -sh "$DEST/jniLibs" "$DEST/assets/models/stt"/*
