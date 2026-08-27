package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.content.Intent
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Probes the Dashie integration's voice capability endpoint —
 * `GET {haUrl}/api/dashie/voice/status` — to learn whether the add-on account
 * holder has enabled household Dashie Cloud sharing. The result is cached in
 * [VoicePreferences] so the (synchronous) settings schema can offer "Dashie
 * Cloud" on an anonymous kiosk tablet (the §16.5 OR) without the tablet being
 * logged in.
 *
 * Native (OkHttp), not a WebView fetch: avoids CORS and reuses the HA url/token
 * already in native preferences. Best-effort — never throws.
 */
class DashieCloudCapabilityClient {

    private val client: OkHttpClient = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Refresh the cached availability flag. Persists to [voicePrefs] and invokes
     * [onChanged] (on an OkHttp background thread) only when the value flips —
     * so a caller can trigger a settings re-render exactly when it matters.
     *
     * [routeOnly] — logged-in devices don't need the anon-kiosk fields (account push
     * covers those) but DO need `brain_route` (Open Brain §5: it depends on the box's
     * key store, which never syncs). When true, only the brain-route cache is written.
     */
    fun refresh(
        haUrl: String,
        haToken: String,
        voicePrefs: VoicePreferences,
        routeOnly: Boolean = false,
        context: Context? = null,
        onChanged: ((Boolean) -> Unit)? = null,
    ) {
        if (haUrl.isBlank() || haToken.isBlank()) return

        val url = haUrl.trimEnd('/') + "${ApiPaths.HA}/voice/status"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $haToken")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "capability probe failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string()
                    if (!it.isSuccessful || body.isNullOrEmpty()) {
                        Log.d(TAG, "capability probe HTTP ${it.code}")
                        return
                    }
                    val available: Boolean
                    val agentMode: String
                    var retrievePictures: Boolean? = null
                    val brainRoute: String
                    val o: JSONObject
                    try {
                        o = JSONObject(body)
                        // Where the account's brain runs (Open Brain §5) — 'local' sends
                        // cascade turns through the gateway to the add-on brain.
                        //
                        // STICKY (2026-07-14 resilience audit): an absent/invalid field is
                        // NO INFORMATION — it is not an assertion of 'cloud'. We used to map
                        // it to '' (→ cloud), which meant any 200 response missing the field
                        // silently stranded a LOCAL-brain household on a cloud route: one they
                        // may have no account for, and which — during a Dashie outage — does
                        // not answer at all. The tablet had no way to refuse: `useLocalBrain`,
                        // the only override, is a dev toggle with no UI writer.
                        //
                        // Now only an EXPLICIT, valid route changes the cached value; absence
                        // keeps whatever we had. The original ''-downgrade guarded against a
                        // stale 'local' surviving an add-on/integration downgrade — but a
                        // downgraded add-on still serves /converse-local, and an integration
                        // that is genuinely gone fails the probe outright (onFailure above,
                        // which already leaves the cached value alone). So the guard was
                        // protecting against a narrower case than the harm it caused.
                        brainRoute = o.optString("brain_route", "")
                            .takeIf { r -> r in VALID_BRAIN_ROUTES }
                            ?: voicePrefs.brainRoute
                        available = o.optBoolean("available", false)
                        // Household agentMode (live|dialog|single) — set from the console's
                        // Voice & AI page, carried add-on → integration → here so an anonymous
                        // kiosk behaves like the account chose. Absent on older integrations →
                        // empty → the effective-mode resolver uses the kiosk default ('live').
                        agentMode = o.optString("agent_mode", "").lowercase()
                            .takeIf { m -> m in VALID_AGENT_MODES } ?: ""
                        // Household ai.retrievePicturesEnabled — same account-setting-can't-
                        // reach-anon-device class. Only cached when the field is present
                        // (older integrations omit it → keep the current cached value).
                        if (o.has("retrieve_pictures")) retrievePictures = o.optBoolean("retrieve_pictures", false)
                    } catch (e: Exception) {
                        Log.d(TAG, "capability probe parse failed: ${e.message}")
                        return
                    }
                    if (voicePrefs.brainRoute != brainRoute) {
                        Log.i(TAG, "brain route → '${brainRoute.ifEmpty { "(unreported)" }}'")
                        voicePrefs.brainRoute = brainRoute
                    }
                    // Stamp the probe time so refreshBrainRouteIfStale can TTL-gate wake-time
                    // re-probes — this is what lets "add an API key → next turn uses it" work
                    // without an app restart (Open Brain §5 staleness fix).
                    voicePrefs.brainRouteCheckedAtMs = System.currentTimeMillis()
                    // Hub identity (published-edition id | absent = full edition) — must
                    // land BEFORE the routeOnly return: signed-in kiosks (the ones showing
                    // the Account page's Disconnect row) only ever probe route-only. Direct
                    // assign so switching the household back to the full add-on self-heals.
                    // Recognised ids live in VoicePreferences (contract #59) — an unknown one
                    // DROPs loudly there rather than silently reading as the full edition.
                    val hub = o.optString("hub", "")
                    if (voicePrefs.hubBrand != hub) {
                        Log.i(TAG, "gateway hub → '${hub.ifEmpty { "full-edition (absent)" }}'")
                        voicePrefs.hubBrand = hub
                        VoicePreferences.isPublishedHubValue(hub)   // fires the DROP warn on arrival
                    }
                    if (routeOnly) return
                    val prevAvail = voicePrefs.cloudShareAvailable
                    val prevMode = voicePrefs.kioskAgentMode
                    voicePrefs.cloudShareAvailable = available
                    voicePrefs.kioskAgentMode = agentMode
                    retrievePictures?.let { voicePrefs.kioskRetrievePictures = it }
                    voicePrefs.cloudShareCheckedAtMs = System.currentTimeMillis()
                    // Kiosk voice-config mirror (Phase 1): hard-apply the account's full
                    // voice pipeline / personality / wake word onto this anon kiosk. Only
                    // when sharing is available and a Context was supplied (the anon-kiosk
                    // caller). Reinit the running pipeline when something actually changed.
                    if (available && context != null) {
                        try {
                            if (KioskAccountVoiceApplier.apply(context, voicePrefs, o)) {
                                context.sendBroadcast(
                                    Intent("com.dashieapp.Dashie.ACTION_VOICE_PIPELINE_REINIT")
                                        .apply { setPackage(context.packageName) }
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "kiosk account voice apply failed: ${e.message}")
                        }
                    }
                    // Sharing was just turned OFF (true→false): the cloud pipeline this
                    // kiosk was running via the household account is no longer authorized.
                    // Revert providers off cloud + wipe the cached credits, and reinit so
                    // it stops mid-session — don't wait for the settings page to be opened.
                    // (The JWT-liveness path also signs out via endSession; this covers the
                    // case where the share probe notices first.)
                    if (prevAvail && !available) {
                        CreditStateHolder.reset()
                        com.dashieapp.Dashie.halite.settings.schema.wiring.VoicePresetSeeder.revertOffCloud(voicePrefs)
                        context?.sendBroadcast(
                            Intent("com.dashieapp.Dashie.ACTION_VOICE_PIPELINE_REINIT")
                                .apply { setPackage(context.packageName) }
                        )
                    }
                    if (available != prevAvail || agentMode != prevMode) {
                        Log.i(TAG, "household Dashie Cloud sharing → $available (agentMode='$agentMode')")
                        onChanged?.invoke(available)
                    }
                }
            }
        })
    }

    companion object {
        private const val TAG = "DashieCloudCapability"
        private const val TIMEOUT_MS = 5_000L
        private val VALID_AGENT_MODES = setOf("live", "dialog", "single")
        private val VALID_BRAIN_ROUTES = setOf("local", "cloud")
    }
}
