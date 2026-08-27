package com.dashieapp.Dashie.wakeword.models

import org.json.JSONObject

/**
 * Wake word sensitivity levels.
 * Each model defines its own threshold mapping for these levels.
 */
enum class WakeWordSensitivity(val displayName: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    companion object {
        fun fromString(value: String): WakeWordSensitivity {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MEDIUM
        }
    }
}

/**
 * Per-sensitivity thresholds for a DUAL-ENGINE (EI ∧ MWW) model.
 * eiOnly=true means that sensitivity level runs the EI leg alone (no gate).
 *
 * eiEscape / mwwEscape add OR branches to the AND gate: a single engine this
 * confident triggers on its own, without the other's corroboration. The rung is
 * then `(EI ∧ MWW) ∨ EI@eiEscape ∨ MWW@mwwEscape`, which is a SUPERSET of the
 * bare gate by construction — that is the property that makes a sensitivity
 * ladder monotone, and the property the pre-2026-08-01 HIGH rung lacked.
 *
 * NO_ESCAPE disables a branch. It is deliberately > 1.0 so no confidence can
 * reach it; a threshold of 1.0f would still fire on a saturated score.
 */
data class DualGateThresholds(
    val eiThreshold: Float,
    val mwwGate: Float,
    val eiOnly: Boolean = false,
    val eiEscape: Float = NO_ESCAPE,
    val mwwEscape: Float = NO_ESCAPE
) {
    companion object {
        const val NO_ESCAPE = 2f
    }
}

/**
 * Represents the wake word model configuration.
 *
 * Dual-engine models (Hey Dashie, Chickadee) use an EI + MWW AND-gate for
 * zero false positives. Other models use MWW only with different probability
 * cutoffs per sensitivity.
 */
data class WakeWordModel(
    val version: String,
    val downloadUrl: String,
    val sizeKb: Int,
    val assetPath: String? = null,
    val wakeWordName: String = NAME,
    val probabilityCutoff: Float = 0.85f,     // Default/medium cutoff
    val slidingWindowSize: Int = 5,
    val isDualEngine: Boolean = false,        // EI + MWW AND-gate
    val sensitivityThresholds: Map<WakeWordSensitivity, Float> = emptyMap(),  // MWW-only models
    // Dual-engine only: the EI leg's bundled asset (null → TFLiteClassifier's
    // hey_dashie default) and the per-sensitivity gate ladder (empty → the
    // legacy DualEngineDetector.SensitivityMode hey_dashie values).
    val eiAssetPath: String? = null,
    val dualThresholds: Map<WakeWordSensitivity, DualGateThresholds> = emptyMap(),
    // Dual-engine only: max |EI fire − MWW crossing| for the AND-gate. Chickadee's
    // MWW leg peaks ~0.6-0.8s after EI on real voices, so it needs 800ms; measured
    // 2026-07-28 (field_dual_gate_v01.json: 500ms passed 9/27 real utterances,
    // 800ms 19/27, FA cost +1 fire in 62h). hey_dashie keeps 500 (unmeasured at 800).
    //
    // ⚠️ The FA-side benchmark does NOT use this value: `dual_gate_fa.py` hardcodes
    // COOCCUR_S = 0.5 (T, 2026-08-04). So every recorded dual-gate FA number was produced
    // at a STRICTER window than the 800ms Chickadee ships, and can only under-count. Any
    // future FA run must pass this value through rather than assume the default — see the
    // correction block on CHICKADEE below.
    val agreementWindowMs: Long = 500L
) {
    /** The detection engine for this model, derived from version suffix. */
    val engine: WakeWordEngine
        get() = WakeWordEngine.fromVersionSuffix(version)

    /** Check if this model is a bundled asset model. */
    val isBundled: Boolean
        get() = downloadUrl.isEmpty()

    /** Unique identifier for persistence. */
    val modelId: String
        get() = BUNDLED_MODEL_IDS[this] ?: "ei_hey_dashie"

    /** Get the MWW probability cutoff for a given sensitivity level. */
    fun getCutoffForSensitivity(sensitivity: WakeWordSensitivity): Float {
        return sensitivityThresholds[sensitivity] ?: probabilityCutoff
    }

    companion object {
        const val NAME = "Hey Dashie"
        const val LABEL_NAME = "hey_dashie"

        /**
         * Hey Dashie — dual-engine AND-gate, EI 4.0 + MWW 2.0 (re-instituted July 2026).
         * EI 4.0 standalone @0.80 held 0 conversational FA on clean meetings but trips
         * ~1.06/hr on VoIP/Zoom audio (a domain the meeting tiers miss). MWW 2.0 fires on
         * completely different audio (0.20 on all 11 field Zoom FPs), so an AND-gate vetoes
         * those FPs for a small recall cost — cheap now that MWW 2.0 recall is high (the old
         * dual gate was 17% only because MWW 1.1M was near-blind). Live thresholds and the
         * sensitivity ladder live in DualEngineDetector.SensitivityMode (re-tuned
         * 2026-07-31 off the first full EI × MWW false-accept sweep — see there):
         * - HIGH   EI 4.0 only @0.60
         * - MEDIUM EI 4.0 @0.35 AND MWW 2.0 @0.20  (default; 84.0%/92.8%, 0.065 FA/hr)
         * - LOW    EI 4.0 @0.80 AND MWW 2.0 @0.30  (the pre-2026-07-31 MEDIUM, 0 FA)
         * Capture stays EI-driven (DualEngineDetector disables MWW collection). The EI 4.0
         * INT8 model loads internally via BUNDLED_EI; assetPath here is the MWW 2.0 model.
         * See WAKEWORD_2026-07_OUTCOMES.md §5a. (Version "M" suffix routes to the dual path.)
         */
        val HEY_DASHIE = WakeWordModel(
            version = "2.0M",
            downloadUrl = "",
            sizeKb = 61,
            assetPath = "models/mww/hey_dashie.tflite",  // MWW 2.0 (EI 4.0 loads internally)
            wakeWordName = "Hey Dashie",
            probabilityCutoff = 0.30f,  // MWW veto cutoff (medium); real gate in DualEngineDetector
            slidingWindowSize = 5,
            isDualEngine = true,
            // NOT read on the dual path — getCutoffForSensitivity() is only called in
            // HaliteVoiceController's non-dual branch, so these never reach the gate.
            // Kept in step with DualEngineDetector.SensitivityMode's EI ladder anyway:
            // a map that says MEDIUM=0.80 while the live gate runs 0.35 is exactly the
            // kind of drift that gets read as truth later.
            sensitivityThresholds = mapOf(
                WakeWordSensitivity.HIGH to 0.60f,
                WakeWordSensitivity.MEDIUM to 0.35f,
                WakeWordSensitivity.LOW to 0.80f
            )
        )

        /**
         * Chickadee v0 — dual-engine AND-gate, EI v0 + MWW v0 (2026-07-26). The wake word
         * for the chickadee open-core voice engine; standalone keyword, no "hey" carrier.
         * Cold-start TTS-only corpus (4-engine blend + kid sims + confusables); benchmarks in
         * wake-word-training/benchmark-results/chickadee-v0-fa/.
         *
         * 🔴 EVERY NUMBER BELOW IS SYNTHETIC-TTS RECALL, AND THE FA FIGURE WAS MEASURED AT A
         * WINDOW THIS MODEL DOES NOT SHIP. Both corrections are Thread T's (2026-08-04); neither
         * is re-measured here, because wake-word benchmarking is off the board by the charter
         * ruling. The numbers are kept — they are the only ones that exist — but they are labelled
         * so the code stops asserting more than any run supports.
         *
         * - **The "0.00 FA/hr on all 9 tiers" claim does not describe this configuration.**
         *   `dual_gate_fa.py` hardcodes a **0.5 s** co-occurrence window; this model ships
         *   [agreementWindowMs] = **800 ms** (below). A narrower window is STRICTER — the two
         *   engines must agree sooner — so the measured 0.00 can only UNDER-count what the
         *   shipping config produces. The true FA rate at 800 ms is **unknown**, not zero.
         * - **Recall is synthetic TTS-holdout, not field.** Measured field recall at the shipped
         *   default is **~69% absolute** (T cont.35, n=134) against the 96.2% below. The gap is
         *   what v0 exists to close: its job is to collect real clips, which is exactly why the
         *   opt-in sample path matters.
         *
         * Synthetic TTS-holdout recall / real-audio FA over ~62 h **at a 0.5 s agreement window**:
         * - HIGH   EI only @0.70          (~99.0% synthetic recall; EI-alone FA up to ~3.5/hr on VoIP)
         * - MEDIUM EI @0.80 AND MWW @0.30 (96.2% synthetic recall — ~69% FIELD; 30/30 EI false fires vetoed)
         * - LOW    EI @0.90 AND MWW @0.50 (92.8% synthetic recall, strictest)
         */
        val CHICKADEE = WakeWordModel(
            version = "0.1M",
            downloadUrl = "",
            sizeKb = 61,
            assetPath = "models/mww/chickadee.tflite",
            wakeWordName = "Chickadee",
            probabilityCutoff = 0.30f,
            slidingWindowSize = 5,
            isDualEngine = true,
            eiAssetPath = "models/chickadee.tflite",
            sensitivityThresholds = mapOf(  // picker display + EI-only fallback path
                WakeWordSensitivity.HIGH to 0.70f,
                WakeWordSensitivity.MEDIUM to 0.80f,
                WakeWordSensitivity.LOW to 0.90f
            ),
            dualThresholds = mapOf(
                WakeWordSensitivity.HIGH to DualGateThresholds(0.70f, 0f, eiOnly = true),
                WakeWordSensitivity.MEDIUM to DualGateThresholds(0.80f, 0.30f),
                WakeWordSensitivity.LOW to DualGateThresholds(0.90f, 0.50f)
            ),
            agreementWindowMs = 800L
        )

        /** Bundled EI 4.0 model (also usable internally by the dual-engine fallback). */
        val BUNDLED_EI = WakeWordModel(
            version = "4.0",
            downloadUrl = "",
            sizeKb = 596,
            assetPath = "models/hey_dashie.tflite",
            wakeWordName = "Hey Dashie"
        )

        /** Bundled MWW 2.0 model (retrained; kept for the dual-engine/cascade fallback). */
        val BUNDLED_MWW = WakeWordModel(
            version = "2.0",
            downloadUrl = "",
            sizeKb = 61,
            assetPath = "models/mww/hey_dashie.tflite",
            wakeWordName = "Hey Dashie",
            probabilityCutoff = 0.50f,
            slidingWindowSize = 5
        )

        // Legacy alias
        val BUNDLED = BUNDLED_EI

        // ── Bundled microWakeWord models (esphome/micro-wake-word-models v2) ──

        val MWW_OKAY_NABU = WakeWordModel(
            version = "1.0M",
            downloadUrl = "",
            sizeKb = 59,
            assetPath = "models/mww/okay_nabu.tflite",
            wakeWordName = "Okay Nabu",
            probabilityCutoff = 0.97f,
            slidingWindowSize = 5,
            sensitivityThresholds = mapOf(
                WakeWordSensitivity.HIGH to 0.85f,
                WakeWordSensitivity.MEDIUM to 0.97f,
                WakeWordSensitivity.LOW to 0.99f
            )
        )

        val MWW_HEY_JARVIS = WakeWordModel(
            version = "1.0M",
            downloadUrl = "",
            sizeKb = 51,
            assetPath = "models/mww/hey_jarvis.tflite",
            wakeWordName = "Hey Jarvis",
            probabilityCutoff = 0.97f,
            slidingWindowSize = 5,
            sensitivityThresholds = mapOf(
                WakeWordSensitivity.HIGH to 0.85f,
                WakeWordSensitivity.MEDIUM to 0.97f,
                WakeWordSensitivity.LOW to 0.99f
            )
        )

        val MWW_HEY_MYCROFT = WakeWordModel(
            version = "1.0M",
            downloadUrl = "",
            sizeKb = 56,
            assetPath = "models/mww/hey_mycroft.tflite",
            wakeWordName = "Hey Mycroft",
            probabilityCutoff = 0.95f,
            slidingWindowSize = 5,
            sensitivityThresholds = mapOf(
                WakeWordSensitivity.HIGH to 0.85f,
                WakeWordSensitivity.MEDIUM to 0.95f,
                WakeWordSensitivity.LOW to 0.97f
            )
        )

        val MWW_ALEXA = WakeWordModel(
            version = "1.0M",
            downloadUrl = "",
            sizeKb = 55,
            assetPath = "models/mww/alexa.tflite",
            wakeWordName = "Alexa",
            probabilityCutoff = 0.90f,
            slidingWindowSize = 5,
            sensitivityThresholds = mapOf(
                WakeWordSensitivity.HIGH to 0.80f,
                WakeWordSensitivity.MEDIUM to 0.90f,
                WakeWordSensitivity.LOW to 0.93f
            )
        )

        // Legacy — kept for backward compat with persisted "mww_hey_dashie" selections
        val MWW_HEY_DASHIE = HEY_DASHIE

        /** All user-facing wake word models. */
        val ALL_MODELS = listOf(
            HEY_DASHIE, CHICKADEE,
            MWW_OKAY_NABU, MWW_HEY_JARVIS, MWW_HEY_MYCROFT, MWW_ALEXA
        )

        /** All MWW models (for backward compat). */
        val ALL_MWW_MODELS = ALL_MODELS

        /** All bundled models including internal EI. */
        val ALL_BUNDLED_MODELS = listOf(BUNDLED_EI) + ALL_MODELS

        /** Model ID mapping (used for persistence). */
        private val BUNDLED_MODEL_IDS = mapOf(
            BUNDLED_EI to "ei_hey_dashie",
            HEY_DASHIE to "hey_dashie",
            CHICKADEE to "chickadee",
            MWW_OKAY_NABU to "mww_okay_nabu",
            MWW_HEY_JARVIS to "mww_hey_jarvis",
            MWW_HEY_MYCROFT to "mww_hey_mycroft",
            MWW_ALEXA to "mww_alexa"
        )

        /** Persisted ids of every user-facing bundled model — the device's
         *  wake-word availability catalog (WS-G §13.2 model-gap rule: JS only
         *  applies an account-default wake word whose id appears here). */
        fun availableModelIds(): List<String> = ALL_MODELS.mapNotNull { BUNDLED_MODEL_IDS[it] }

        /** Look up a model by its persisted ID. */
        fun fromId(id: String): WakeWordModel {
            // Handle legacy IDs
            val normalizedId = when (id) {
                "mww_hey_dashie" -> "hey_dashie"
                else -> id
            }
            val found = BUNDLED_MODEL_IDS.entries.firstOrNull { it.value == normalizedId }?.key
            android.util.Log.i("WakeWordModel", "fromId('$id') → ${found?.wakeWordName ?: "NOT FOUND, fallback to Hey Dashie"} (map size=${BUNDLED_MODEL_IDS.size})")
            return found ?: HEY_DASHIE
        }
    }
}

/**
 * Server manifest for wake word model updates.
 */
data class WakeWordManifest(
    val latestVersion: String,
    val downloadUrl: String,
    val sizeKb: Int,
    val manifestVersion: Int = 1
) {
    companion object {
        fun fromJson(json: JSONObject): WakeWordManifest {
            return WakeWordManifest(
                latestVersion = json.getString("latest_version"),
                downloadUrl = json.getString("download_url"),
                sizeKb = json.getInt("size_kb"),
                manifestVersion = json.optInt("manifest_version", 1)
            )
        }
    }
}
