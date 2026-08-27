/**
 * Dashie Lite Configuration
 *
 * This config drives backend routing for LLM, STT, TTS, and data sources.
 * Users can configure cloud vs local backends via the HA addon settings.
 *
 * Part of the Unified NLP Architecture for Dashie Lite 3.0.
 * See: .reference/build-plans/20260122_UNIFIED_NLP_ARCHITECTURE.md
 */

// Default configuration - can be overridden by addon server injection
const defaultConfig = {
  // ═══════════════════════════════════════════════════════════════════
  // LLM CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════
  llm: {
    enabled: true,                   // LLM available via Ollama
    provider: 'local',              // 'local' | 'cloud' | 'hybrid'

    // Local: Addon server proxies to Ollama/HA
    localEndpoint: '/api/llm/chat',
    localModel: 'qwen2.5:1.5b',    // Ollama model

    // NOTE: the `cloud*` endpoints that used to sit here were Dashie Supabase URLs with no
    // readers — see the deletion note at the bottom of this file. Do not reintroduce one
    // without a caller: this bundle ships in BOTH editions, and Chickadee is account-free.

    // Provider-specific options
    max_tokens: 300,                // Keep low for faster local model responses
    temperature: 0.7,

    // Hybrid: Try local first, fall back to cloud on failure
    hybridFallbackOnError: true,
    hybridFallbackOnTimeout: 5000,  // ms
  },

  // ═══════════════════════════════════════════════════════════════════
  // STT CONFIGURATION (Speech-to-Text)
  // ═══════════════════════════════════════════════════════════════════
  stt: {
    provider: 'local',              // 'local' | 'cloud'

    // Local: Wyoming Whisper via addon server
    localEndpoint: '/api/voice/transcribe',
  },

  // ═══════════════════════════════════════════════════════════════════
  // TTS CONFIGURATION (Text-to-Speech)
  // ═══════════════════════════════════════════════════════════════════
  tts: {
    provider: 'local',              // 'local' | 'cloud'

    // Local: Wyoming Piper via addon server
    localEndpoint: '/api/voice/synthesize',
    localVoice: 'en_US-lessac-medium',
  },

  // ═══════════════════════════════════════════════════════════════════
  // DATA SOURCE CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════
  data: {
    // Calendar events
    calendar: 'ha',                 // 'ha' | 'cloud'

    // Weather
    weather: 'ha',                  // 'ha' | 'cloud'

    // Family members
    family: 'ha',                   // 'ha' | 'cloud'

    // Chores/Tasks
    chores: 'ha',                   // 'ha' | 'cloud'
  },

  // ═══════════════════════════════════════════════════════════════════
  // FEATURE FLAGS
  // ═══════════════════════════════════════════════════════════════════
  features: {
    streaming: false,               // Streaming not yet implemented for local
    localIntentClassification: true, // Bypass AI for simple commands
    sessionMemory: false,           // Conversation context (requires more tokens)
    analytics: false,               // Log interactions to Supabase
  },

  // ═══════════════════════════════════════════════════════════════════
  // VERSION INFO
  // ═══════════════════════════════════════════════════════════════════
  version: '3.0.0',
  platform: 'dashie-lite'
};

// Merge with server-injected config (if any)
const serverConfig = window.DASHIE_SERVER_CONFIG || {};

// Deep merge helper
function deepMerge(target, source) {
  const result = { ...target };
  for (const key of Object.keys(source)) {
    if (source[key] && typeof source[key] === 'object' && !Array.isArray(source[key])) {
      result[key] = deepMerge(target[key] || {}, source[key]);
    } else {
      result[key] = source[key];
    }
  }
  return result;
}

// Export merged config
export const DASHIE_CONFIG = deepMerge(defaultConfig, serverConfig);

// Also expose globally for debugging and non-module scripts
window.DASHIE_CONFIG = DASHIE_CONFIG;

// 🔴 REMOVED 2026-08-02 (2g): getLLMEndpoint / getLLMModel / getSTTEndpoint / getTTSEndpoint /
// isLocalLLM / isCloudLLM / isHybridLLM. All seven were exported and **nothing imported any of
// them** — grepped the whole overlay. They were the last readers of the `cloud*` fields deleted
// above, and they are dead for a known reason: the kiosk stopped orchestrating voice at WS5
// (see kiosk-services.js, "the legacy local AI path below is retired"). The call moved to native
// Kotlin and this config was left behind still describing it.
//
// Deleted rather than rebranded, which is the whole point: the leak was three PROD/staging
// Supabase URLs riding into an account-free artifact inside an object that only a console.log
// still reads. Rebranding unreachable code would have preserved the reachability lie.

export function isLLMEnabled() {
  return DASHIE_CONFIG.llm.enabled === true;
}

export default DASHIE_CONFIG;
