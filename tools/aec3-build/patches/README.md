# AEC3 source patches

Patches applied to `webrtc-audio-processing` (tag `v2.1`) after clone by `../build.sh`.
They are applied **only on a fresh clone** (the `if [ ! -d webrtc-audio-processing ]`
block); a cached `.work/` tree is already patched, so re-runs skip them.

## `aec3-nearend-stats.patch`

**Why:** talk-over barge-in needs a double-talk detector that separates the user's voice
from residual echo during loud TTS. Energy thresholds and `residual_echo_likelihood`
(envelope correlation) both failed on device — no dynamic range, full overlap
(`20260703_BARGEIN_ESCALATION_HANDOFF.md`). AEC3 already computes the right signal
internally in `DominantNearendDetector` using energy **ratios** (low-freq ENR + SNR), but
it is not exposed through the public `GetStatistics()` API.

**What it surfaces** (mirrors the existing ERL/ERLE metrics path exactly):

| Field (`AudioProcessingStats`) | Source | Meaning |
|---|---|---|
| `dominant_nearend` | `SuppressionGain::IsDominantNearend()` | detector's near-end-dominant boolean |
| `nearend_enr` | `echo_sum / nearend_sum` (low-freq, ch 0) | **low ⇒ near-end dominant** (user talking) |
| `nearend_snr` | `nearend_sum / noise_sum` (low-freq, ch 0) | high ⇒ real near-end vs noise |

**Files touched** (8): `dominant_nearend_detector.{h,cc}` (store/expose ratios),
`nearend_detector.h` (base virtuals, default −1), `suppression_gain.h` (const accessors),
`echo_control.h` (`Metrics` fields), `echo_remover.cc` (`GetMetrics` fill),
`audio_processing_statistics.h` + `audio_processing_impl.cc` (map into `GetStatistics()`).

Consumed by `realtime_aec3_jni.cpp` → `RealtimeAec3.nativeStats()` (indices 3/4/5) →
logged as `dn/enr/snr` in `GeminiLiveEngine`.

**Regenerate** (after editing the cloned tree in place):
```bash
cd .work/webrtc-audio-processing && git diff > ../../patches/aec3-nearend-stats.patch
```
