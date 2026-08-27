// JNI wrapper around WebRTC AudioProcessing (AEC3) for Dashie conversation mode.
// Build plan 20260627_REALTIME_VOICE_AEC, Phase 1.
//
// Exposes a tiny handle-based API to Kotlin (RealtimeAec3):
//   nativeCreate(captureRate, renderRate, ns) -> handle (0 on failure)
//   nativeSetStreamDelay(handle, ms)
//   nativeProcessReverse(handle, frame)              // far-end / playback, 10 ms @ renderRate
//   nativeProcessCapture(handle, inFrame, outFrame)  // near-end / mic,    10 ms @ captureRate
//   nativeDestroy(handle)
//
// AEC3 (echo_canceller.mobile_mode = false) does proper linear echo subtraction and
// preserves near-end during double-talk — that's the barge-in win over AECM. It also
// takes per-stream rates, so render (24 kHz) and capture (16 kHz) are handled natively;
// no resampling on our side.

#include <jni.h>
#include <cstdint>
#include <modules/audio_processing/include/audio_processing.h>
#include "api/scoped_refptr.h"
#include "api/make_ref_counted.h"
#include "modules/audio_processing/residual_echo_detector.h"

using webrtc::AudioProcessing;
using webrtc::AudioProcessingBuilder;
using webrtc::ResidualEchoDetector;
using webrtc::StreamConfig;

namespace {
struct ApmHandle {
  rtc::scoped_refptr<AudioProcessing> apm;
  StreamConfig captureCfg;
  StreamConfig renderCfg;
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_dashieapp_Dashie_voice_realtime_RealtimeAec3_nativeCreate(
    JNIEnv*, jobject, jint captureRate, jint renderRate, jboolean ns) {
  // Attach the residual-echo detector so GetStatistics() populates residual_echo_likelihood
  // (0..1) — the per-frame probability the cleaned mic still contains echo. We use it to tell
  // genuine near-end speech (low likelihood) from leftover echo (high likelihood) during
  // double-talk, which raw energy cannot.
  rtc::scoped_refptr<AudioProcessing> apm =
      AudioProcessingBuilder()
          .SetEchoDetector(rtc::make_ref_counted<ResidualEchoDetector>())
          .Create();
  if (!apm) return 0;

  AudioProcessing::Config config;
  config.echo_canceller.enabled = true;
  config.echo_canceller.mobile_mode = false;   // AEC3 (full-band, double-talk friendly)
  config.high_pass_filter.enabled = true;
  config.noise_suppression.enabled = (ns == JNI_TRUE);
  config.noise_suppression.level =
      AudioProcessing::Config::NoiseSuppression::kModerate;
  // AGC is OFF. AGC2 adaptive-digital ramps its gain up during the user's silence and
  // amplifies AEC3's residual echo into self-hearing — the energy then overlaps real
  // double-talk speech and can't be separated downstream. Leaving it off keeps the
  // echo-cancelled floor near-silent; the cleaned near-end is sent at its natural level.
  config.gain_controller1.enabled = false;
  config.gain_controller2.enabled = false;
  apm->ApplyConfig(config);

  ApmHandle* h = new ApmHandle();
  h->apm = apm;
  h->captureCfg = StreamConfig(captureRate, 1);
  h->renderCfg = StreamConfig(renderRate, 1);
  return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_dashieapp_Dashie_voice_realtime_RealtimeAec3_nativeSetStreamDelay(
    JNIEnv*, jobject, jlong handle, jint delayMs) {
  ApmHandle* h = reinterpret_cast<ApmHandle*>(handle);
  if (h) h->apm->set_stream_delay_ms(delayMs);
}

JNIEXPORT void JNICALL
Java_com_dashieapp_Dashie_voice_realtime_RealtimeAec3_nativeProcessReverse(
    JNIEnv* env, jobject, jlong handle, jshortArray frame) {
  ApmHandle* h = reinterpret_cast<ApmHandle*>(handle);
  if (!h) return;
  jshort* buf = env->GetShortArrayElements(frame, nullptr);
  if (!buf) return;
  int16_t* pcm = reinterpret_cast<int16_t*>(buf);
  h->apm->ProcessReverseStream(pcm, h->renderCfg, h->renderCfg, pcm);
  env->ReleaseShortArrayElements(frame, buf, 0);
}

JNIEXPORT jint JNICALL
Java_com_dashieapp_Dashie_voice_realtime_RealtimeAec3_nativeProcessCapture(
    JNIEnv* env, jobject, jlong handle, jshortArray inFrame, jshortArray outFrame) {
  ApmHandle* h = reinterpret_cast<ApmHandle*>(handle);
  if (!h) return -1;
  jshort* in = env->GetShortArrayElements(inFrame, nullptr);
  jshort* out = env->GetShortArrayElements(outFrame, nullptr);
  jint r = -1;
  if (in && out) {
    r = h->apm->ProcessStream(reinterpret_cast<int16_t*>(in), h->captureCfg,
                              h->captureCfg, reinterpret_cast<int16_t*>(out));
  }
  if (out) env->ReleaseShortArrayElements(outFrame, out, 0);
  if (in) env->ReleaseShortArrayElements(inFrame, in, JNI_ABORT);
  return r;
}

// Returns [residual_echo_likelihood, ERLE_dB, ERL_dB, dominant_nearend(0/1),
// nearend_enr, nearend_snr]; -1 for any stat not available. The last three come from
// AEC3's DominantNearendDetector (surfaced by the aec3-nearend-stats source patch) —
// a ratio-based double-talk signal for talk-over barge-in, unlike the envelope-
// correlation residual_echo_likelihood.
JNIEXPORT jdoubleArray JNICALL
Java_com_dashieapp_Dashie_voice_realtime_RealtimeAec3_nativeStats(
    JNIEnv* env, jobject, jlong handle) {
  jdouble vals[6] = {-1.0, -1.0, -1.0, -1.0, -1.0, -1.0};
  ApmHandle* h = reinterpret_cast<ApmHandle*>(handle);
  if (h) {
    webrtc::AudioProcessingStats s = h->apm->GetStatistics();
    if (s.residual_echo_likelihood.has_value()) vals[0] = *s.residual_echo_likelihood;
    if (s.echo_return_loss_enhancement.has_value()) vals[1] = *s.echo_return_loss_enhancement;
    if (s.echo_return_loss.has_value()) vals[2] = *s.echo_return_loss;
    if (s.dominant_nearend.has_value()) vals[3] = *s.dominant_nearend ? 1.0 : 0.0;
    if (s.nearend_enr.has_value()) vals[4] = *s.nearend_enr;
    if (s.nearend_snr.has_value()) vals[5] = *s.nearend_snr;
  }
  jdoubleArray arr = env->NewDoubleArray(6);
  if (arr) env->SetDoubleArrayRegion(arr, 0, 6, vals);
  return arr;
}

JNIEXPORT void JNICALL
Java_com_dashieapp_Dashie_voice_realtime_RealtimeAec3_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
  ApmHandle* h = reinterpret_cast<ApmHandle*>(handle);
  delete h;  // scoped_refptr releases the APM
}

}  // extern "C"
