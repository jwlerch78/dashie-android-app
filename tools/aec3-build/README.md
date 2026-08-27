# libdashie_aec3.so — WebRTC AEC3 echo canceller (conversation mode)

Prebuilt native library for the realtime-voice software AEC (build plan
`dashieapp_staging/.reference/build-plans/20260627_REALTIME_VOICE_AEC.md`, Phase 1).

`libdashie_aec3.so` is a **prebuilt** lib committed under `app/src/main/jniLibs/<abi>/`
— the same model as `libAEC.so` (AECM). Gradle does **not** build it; rebuild only when
the JNI wrapper or upstream library changes, then commit the refreshed `.so`.

## What it is

A thin JNI wrapper ([`realtime_aec3_jni.cpp`](realtime_aec3_jni.cpp)) over WebRTC's
`AudioProcessing` module (AEC3, `echo_canceller.mobile_mode = false`), built from
[webrtc-audio-processing](https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing)
v2.1 (the maintained PulseAudio fork; abseil pulled as a meson subproject).

- **AEC3 vs AECM:** AEC3 does linear echo subtraction and preserves near-end speech during
  double-talk, so the user can talk over Dashie at a normal volume (real barge-in). AECM
  (`libAEC.so`) gates the mic on far-end energy and is kept only as a fallback.
- **Rates:** takes 24 kHz render + 16 kHz capture at native rates (no resampler).
- **Size:** ~0.9 MB/ABI (arm64-v8a + armeabi-v7a), `--gc-sections` + stripped.
- **Self-contained:** static libc++ (`-static-libstdc++`, `--exclude-libs ALL`); NEEDED =
  liblog/libm/libc/libdl only — no `libc++_shared.so` dependency.
- **16 KB page aligned** (`max-page-size=16384`) for Android 15 compliance.

Kotlin side: [`RealtimeAec3`](../../app/src/main/java/com/dashieapp/Dashie/voice/realtime/RealtimeAec3.kt)
(loads `dashie_aec3`, declares the `native*` methods), behind the
[`SoftwareAec`](../../app/src/main/java/com/dashieapp/Dashie/voice/realtime/SoftwareAec.kt)
seam. `RealtimeAudioIo.initSoftwareAec()` picks AEC3, then AECM, then platform-AEC-only.

## JNI symbol contract

The `.so` exports `Java_com_dashieapp_Dashie_voice_realtime_RealtimeAec3_native*`. If you
rename/move the Kotlin `RealtimeAec3` class, update the function names in
`realtime_aec3_jni.cpp` to match (and vice versa) or `System.loadLibrary` will link but the
methods won't bind.

## Rebuild

```bash
# one-time toolchain (a venv is fine):
python3 -m venv ~/.venvs/meson && ~/.venvs/meson/bin/pip install meson ninja
export PATH="$HOME/.venvs/meson/bin:$PATH"

# rebuild both ABIs (fetches the upstream source + abseil on first run):
NDK_HOME="$HOME/Library/Android/sdk/ndk/27.0.12077973" ./build.sh
# → overwrites app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libdashie_aec3.so
git add app/src/main/jniLibs/*/libdashie_aec3.so && git commit ...
```

Notes:
- **v7a builds at API 24** (`fseeko`/`ftello` are 32-bit-only at API ≥ 24); arm64 at the
  app's minSdk 23. A v7a device on API 23 would fail to load the `.so` and fall back to
  AECM/pass-through — acceptable (real API-23 32-bit devices are effectively extinct).
- Upstream tag pinned via `APM_REF` (default `v2.1`).
- Intermediate build artifacts land in `.work/` (gitignored — add if not already).
