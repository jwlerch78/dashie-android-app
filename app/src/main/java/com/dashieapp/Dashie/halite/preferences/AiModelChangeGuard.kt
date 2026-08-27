package com.dashieapp.Dashie.halite.preferences

import android.content.SharedPreferences
import android.util.Log

/**
 * Invalidates the cached brain route when the AI model selection changes.
 *
 * Defect class (recurring, recorded 2026-08-21): a route/mode key that should follow the model
 * selection is hand-maintained instead, and goes stale when the model moves — the UI truthfully
 * shows the new model while the router truthfully obeys the old route. (Same family as the
 * preset-seeder agentMode repair; this is the brainRoute instance: the account moved ai.model to
 * a cloud model and the device kept routing cascade turns to a dead local box.)
 *
 * The add-on still OWNS the route decision — the device deliberately never derives route from
 * model (see [VoicePreferences.brainRoute]). What the device must not do is keep serving a cached
 * answer produced under the OLD model: [noteAiModelChanged] zeroes the probe TTL so the next
 * wake's refreshBrainRouteIfStale re-probes the add-on immediately.
 *
 * ## ⚠️ What `route=local` DOES NOT mean (corrected 2026-08-22)
 *
 * This file's WARN used to tell the reader that a fresh probe still reporting "local" meant the
 * add-on's config was stale. **That is wrong, and it named the wrong suspect during a live
 * incident** (, John's Fire: model = Gemini 2.5 Flash with his own key, every turn
 * dying on a dead endpoint after ~10 s).
 *
 * On the add-on, `route` answers *where the brain orchestration runs*, not *which endpoint gets
 * the inference call* — its own `withRoute()` comment says "'local' = run the brain in this
 * add-on". `resolveBrainRoute` returns `local` for THREE different targets, distinguished only by
 * `routeReason`: `local_model` (the household's own LLM box), `hermes` (the on-box agent), and
 * **`byok` (a CLOUD provider, called with the household's own key)**. So a cloud model reporting
 * route=local is the CORRECT answer for a BYOK household, not stale config.
 *
 * The WARN below therefore reports the disagreement without diagnosing it. A cloud model on a
 * cached local route is still worth saying out loud — it is the state that precedes real
 * stranding — but the device cannot tell BYOK-working from misrouted, and pointing at "stale
 * add-on config" sent a reader past the actual defect (a Tier-1 gate reading `route` where it
 * needed `routeReason`).
 *
 * Called from BOTH writers of the shared "ai_model" key ([VoicePreferences.aiModel] and
 * [AiPreferences.aiModel] wrap the same key in the same prefs file), so the picker, the account
 * push, and the preset seeder all pass through this one seam.
 */
internal object AiModelChangeGuard {

    private const val TAG = "AiModelChangeGuard"

    fun noteAiModelChanged(prefs: SharedPreferences, oldModel: String, newModel: String) {
        if (oldModel == newModel) return
        val cachedRoute = prefs.getString(VoicePreferences.KEY_BRAIN_ROUTE, "") ?: ""
        prefs.edit().putLong(VoicePreferences.KEY_BRAIN_ROUTE_CHECKED_AT, 0L).commit()
        val newIsCloud = newModel != VoicePreferences.AI_MODEL_HOME_ASSISTANT &&
            newModel != VoicePreferences.AI_MODEL_OLLAMA &&
            newModel != VoicePreferences.AI_MODEL_LOCAL
        if (newIsCloud && cachedRoute == VoicePreferences.BRAIN_ROUTE_LOCAL) {
            Log.w(TAG,
                "WARN: ai_model changed '$oldModel' -> '$newModel' (cloud) but cached " +
                "brain_route is 'local'. Probe TTL invalidated; next wake re-probes the add-on. " +
                "NOTE: route='local' means 'the add-on runs the brain', which is ALSO correct " +
                "for a BYOK cloud model — so this is a disagreement to check, not a proven " +
                "fault, and the device cannot tell the two apart. The add-on's routeReason " +
                "(local_model|hermes|byok) is the discriminator; read it there before " +
                "suspecting anything.")
        } else {
            Log.i(TAG,
                "ai_model changed '$oldModel' -> '$newModel' — brain-route probe TTL " +
                "invalidated (cached route='$cachedRoute'); next wake re-probes.")
        }
    }
}
