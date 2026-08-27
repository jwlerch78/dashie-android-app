#!/usr/bin/env bash
# Reproduce app/src/main/jniLibs/<abi>/libdashie_aec3.so — the WebRTC AEC3 echo canceller
# used by conversation mode (build plan 20260627_REALTIME_VOICE_AEC, Phase 1).
#
# This is a PREBUILT native lib (not built by Gradle) — same model as libAEC.so (AECM).
# Run it only when the JNI wrapper (realtime_aec3_jni.cpp) or the upstream lib changes,
# then commit the refreshed .so.
#
# Requirements:
#   - Android NDK r27 (set NDK_HOME; default is the path below)
#   - meson + ninja (pip install meson ninja — a venv is fine)
#   - network access (meson fetches webrtc-audio-processing's abseil subproject)
#
# Usage:  NDK_HOME=/path/to/ndk/27.x ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

NDK_HOME="${NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
NDKBIN="$NDK_HOME/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin"
WORK="${WORK:-$(pwd)/.work}"
PATCHES="${PATCHES:-$(pwd)/patches}"   # committed source patches applied post-clone
APM_REF="${APM_REF:-v2.1}"   # webrtc-audio-processing tag
mkdir -p "$WORK"; cd "$WORK"

# 1. Source ----------------------------------------------------------------
# Clone once, then apply our committed patches in the same block so a fresh clone is
# always patched exactly once (a cached .work is already patched — re-runs skip this).
if [ ! -d webrtc-audio-processing ]; then
  git clone --depth 1 --branch "$APM_REF" \
    https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git
  # aec3-nearend-stats: surface DominantNearendDetector state + ENR/SNR ratios through
  # GetStatistics() for ratio-based talk-over barge-in (see patches/README + build-plan
  # 20260703_BARGEIN_ESCALATION_HANDOFF). git apply from inside the repo (paths are repo-root
  # relative); --3way eases future rebases if upstream shifts context.
  for p in "$PATCHES"/*.patch; do
    [ -e "$p" ] || continue
    echo "applying patch: $(basename "$p")"
    git -C webrtc-audio-processing apply --3way "$p"
  done
fi

# 2. Cross-compile the static APM for each ABI -----------------------------
# v7a builds at API 24 (fseeko/ftello are 32-bit-only ≥ API 24); arm64 at the app minSdk 23.
gen_cross() { # $1=triple-prefix  $2=cpu_family  $3=cpu
  cat <<EOF
[binaries]
c = '$NDKBIN/$1-clang'
cpp = '$NDKBIN/$1-clang++'
ar = '$NDKBIN/llvm-ar'
strip = '$NDKBIN/llvm-strip'
ranlib = '$NDKBIN/llvm-ranlib'
[host_machine]
system = 'android'
cpu_family = '$2'
cpu = '$3'
endian = 'little'
EOF
}
gen_cross aarch64-linux-android23      aarch64 aarch64 > cross-arm64.ini
gen_cross armv7a-linux-androideabi24   arm     armv7   > cross-armv7.ini

build_apm() { # $1=abi-tag  $2=cross-file
  local b="build-$1"
  rm -rf "$b"
  meson setup "$b" webrtc-audio-processing --cross-file "$2" \
    --default-library=static --buildtype=release -Dgnustl=disabled -Db_ndebug=true
  meson compile -C "$b"
  # Merge all static archives (APM + webrtc support libs + abseil) into one fat .a.
  local mri="$b/merge.mri" out="$PWD/libwebrtc_apm-$1.a"
  rm -f "$out"; { echo "create $out"; find "$b" -name '*.a' | sed 's/^/addlib /'; echo save; echo end; } > "$mri"
  "$NDKBIN/llvm-ar" -M < "$mri"
}
build_apm arm64 cross-arm64.ini
build_apm armv7 cross-armv7.ini

# Headers are arch-independent; install once for the include path.
meson install -C build-arm64 --destdir "$PWD/install" >/dev/null 2>&1 || meson install -C build-arm64
INC="$PWD/install/usr/local/include/webrtc-audio-processing-2"
[ -d "$INC" ] || INC=$(find "$PWD/install" -type d -name 'webrtc-audio-processing-2' | head -1)

# 3. Link the JNI wrapper + fat .a into the shipped .so --------------------
link() { # $1=abi-dir  $2=cxx  $3=fat.a
  # cwd here is $WORK (.work): up three levels reaches the repo root (.work→aec3-build→tools→repo).
  local outdir="$(cd ../../.. && pwd)/app/src/main/jniLibs/$1"
  "$NDKBIN/$2" -std=c++17 -fPIC -O2 -fvisibility=hidden -ffunction-sections -fdata-sections \
    -I "$INC" -I "$INC/.." -I "$WORK/webrtc-audio-processing/webrtc" \
    -shared -o "$outdir/libdashie_aec3.so" ../realtime_aec3_jni.cpp \
    -Wl,--start-group "$3" -Wl,--end-group \
    -static-libstdc++ -llog \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-s
  ls -la "$outdir/libdashie_aec3.so"
}
link arm64-v8a   aarch64-linux-android23-clang++   "$PWD/libwebrtc_apm-arm64.a"
link armeabi-v7a armv7a-linux-androideabi24-clang++ "$PWD/libwebrtc_apm-armv7.a"
echo "✅ rebuilt libdashie_aec3.so for both ABIs — commit app/src/main/jniLibs/*/libdashie_aec3.so"
