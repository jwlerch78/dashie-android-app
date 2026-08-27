package com.dashieapp.Dashie.controlcenter

import android.content.Context
import com.dashieapp.Dashie.halite.voice.lease.LeaseStateHolder.Share
import android.os.BatteryManager
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.FeatureVisibilityPreferences
import com.dashieapp.Dashie.halite.preferences.PowerPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Reads HalitePreferences directly (no JS bridge) to build card states
 * for all 9 control center features + device footer.
 *
 * Mirrors the state-loading logic from settings-dashie-config-page.js _loadState().
 */
class ControlCenterStateProvider(
    private val context: Context,
    private val prefsProvider: () -> HalitePreferences?,
    private val rtspRunningProvider: () -> Boolean,
    private val wakeWordModelManagerProvider: () -> WakeWordModelManager?,
    private val apiBindFailedProvider: () -> Pair<Boolean, Int> = { Pair(false, 2323) }
) {
    companion object {
        private const val TAG = "CCState"

        /**
         * Label for the dashboard-config card and its section.
         *
         * Renamed from "Home Assistant" (2026-08-18) because the section is
         * the DASHBOARD configuration, and HA is only one of the things a
         * dashboard can be — a "host your own dashboard" device configures a
         * custom URL through exactly the same card. Named once here rather than
         * at the four call sites so the two cannot drift apart.
         */
        const val CARD_LABEL = "Dashboard config"

        /** Section header above [CARD_LABEL]. */
        const val SECTION_LABEL = "Dashboard"
    }

    /** Subscription state for feature gating — via the edition seam, so the Chickadee build
     *  answers "everything is accessible" without carrying the subscription implementation. */
    private val subscriptionState = com.dashieapp.Dashie.edition.EditionSeams.subscription(context)

    /** Per-feature visibility (rollout-status + access-level), synced from JS.
     *  Hides alpha-rollout features from default beta users — see §1.6 of
     *  dashieapp_staging .reference/build-plans/20260508_DASHIE_CLOUD_BETA_READINESS.md */
    private val featureVisibility = FeatureVisibilityPreferences(context)

    /** Map a Control Center card ID to the JS feature_access feature_id used
     *  by the visibility gate. Most card IDs already match the JS naming;
     *  these two diverge for legacy reasons. */
    private fun isCardHidden(cardId: String): Boolean {
        val featureId = when (cardId) {
            "voice-ai" -> "ai_voice"
            "locations" -> "gps"
            else -> cardId
        }
        return featureVisibility.isHidden(featureId)
    }

    /** True if the current user has beta+ access (the cloud Voice/AI cohort). Voice/AI moved
     *  alpha→beta in the 2026-07-03 access-tier restructure. */
    private fun hasBetaAccess(): Boolean = featureVisibility.hasBetaAccess()

    /** Whether the device has a real battery (not a TV/Fire TV) */
    var hasBattery: Boolean = true
        private set

    /** Current battery level (0-100) */
    var batteryLevel: Int = -1
        private set

    /** Whether the device is currently charging */
    var batteryCharging: Boolean = false
        private set

    /** Cached counts from JS bridge for CC summaries — seeded from SharedPreferences */
    private val ccPrefs = context.getSharedPreferences("cc_counts", Context.MODE_PRIVATE)

    var cachedFamilyMemberCount: Int = ccPrefs.getInt("family_count", 0)
        set(value) { field = value; ccPrefs.edit().putInt("family_count", value).apply() }
    var cachedCalendarAccountCount: Int = ccPrefs.getInt("cal_accounts", 0)
        set(value) { field = value; ccPrefs.edit().putInt("cal_accounts", value).apply() }
    var cachedCalendarActiveCount: Int = ccPrefs.getInt("cal_active", 0)
        set(value) { field = value; ccPrefs.edit().putInt("cal_active", value).apply() }
    var cachedCalendarTotalCount: Int = ccPrefs.getInt("cal_total", 0)
        set(value) { field = value; ccPrefs.edit().putInt("cal_total", value).apply() }
    /** True once a calendar count push from JS has ever landed for this
     *  account session. Distinguishes "counts unknown (still loading)" from a
     *  confirmed zero — without it the calendar card flashes "Not configured"
     *  on every open until the async enumerate returns. Cleared with the rest
     *  of cc_counts on sign-out. */
    var calendarCountsLoaded: Boolean = ccPrefs.getBoolean("cal_counts_loaded", false)
        set(value) { field = value; ccPrefs.edit().putBoolean("cal_counts_loaded", value).apply() }
    /** Number of calendar accounts whose refresh tokens have been terminally
     *  revoked (auth_invalid in Supabase). When > 0 the calendar CC card
     *  flips to WARN status with a "Sign-in required" summary. */
    var cachedCalendarAuthInvalidCount: Int = ccPrefs.getInt("cal_auth_invalid", 0)
        set(value) { field = value; ccPrefs.edit().putInt("cal_auth_invalid", value).apply() }
    /** Email of the sole invalid account when count===1, else empty. */
    var cachedCalendarAuthInvalidEmail: String = ccPrefs.getString("cal_auth_invalid_email", "") ?: ""
        set(value) { field = value; ccPrefs.edit().putString("cal_auth_invalid_email", value).apply() }

    /** Cached IP address with TTL */
    private var cachedIpAddress: String? = null
    private var ipCacheTimestamp: Long = 0
    private val IP_CACHE_TTL_MS = 30_000L // 30 seconds

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Refresh cached data that is expensive to compute.
     * Call once when CC is shown, not on every poll cycle.
     */
    fun refreshCachedState() {
        // FB25.5: refresh the account credit balance into CreditStateHolder (async —
        // the Voice & AI card picks it up on the next poll cycle, like the JS counts).
        try {
            com.dashieapp.Dashie.edition.EditionSeams.credits(context).refreshBalance()
        } catch (_: Exception) { /* balance line is optional */ }
    }

    /**
     * Item 22: clear all per-account cached counts on sign-out. The cc_counts
     * SharedPreferences is cleared by MainBroadcastManager — this resets the
     * in-memory mirrors so the CC reflects a fresh state immediately, before
     * the next account's JS pushes new values.
     */
    fun resetCachedCountsForSignOut() {
        cachedFamilyMemberCount = 0
        cachedCalendarAccountCount = 0
        cachedCalendarActiveCount = 0
        cachedCalendarTotalCount = 0
        calendarCountsLoaded = false
        cachedCalendarAuthInvalidCount = 0
        cachedCalendarAuthInvalidEmail = ""
    }

    /**
     * Build the Dashboard section feature cards.
     *
     * HA mode — 2-column grid, unchanged:
     *   Row 0: Dashboard config | Video Feeds (in)
     *   Row 1: Voice Assistant  | Camera Streaming (out)
     *   Row 2: Music Assistant  | Battery Charging
     *
     * Custom-URL ("host your own dashboard") mode — the HA-entity cards drop out:
     *   Row 0: Dashboard config | Camera Streaming (out)
     *   Row 1: Battery Charging
     */
    fun buildHomeAssistantCards(): List<ControlCenterCard> {
        val prefs = prefsProvider() ?: return emptyList()
        val cards = mutableListOf<ControlCenterCard>()

        val haEnabled = prefs.connection.haEnabled

        // Order below deliberately preserves the HA-mode grid unchanged
        // (HA | Video Feeds ⁄ Voice | Camera ⁄ Music | Battery) — the HA-only
        // cards drop out around Camera rather than being appended after it.
        cards.add(buildHaCard(prefs))

        // Video Feeds (in), HA Voice and Music Assistant all read HOME-ASSISTANT
        // entities and do nothing without it — on a custom-URL dashboard they
        // would be dead rows, so they are HA-only.
        if (haEnabled) {
            cards.add(buildVideoFeedsCard(prefs))
            // Voice & AI lives in HA section when HA mode is enabled
            cards.add(buildVoiceAiCard(prefs))
        }

        // ⭐ Camera Streaming (OUT) is NOT an HA feature — it stands up this
        // device's own RTSP server (reads prefs.camera.* + rtspRunningProvider,
        // no HA read anywhere in buildCameraCard). It works on a custom-URL
        // dashboard exactly as it does with HA, so it shows in both.
        // (2026-08-18 — do not fold this into the haEnabled blocks.)
        cards.add(buildCameraCard(prefs))

        if (haEnabled) {
            cards.add(buildMusicCard(prefs))
        }

        // Power card — only on devices with battery
        refreshBattery()
        detectDeviceType(prefs)
        if (hasBattery) {
            cards.add(buildPowerCard(prefs))
        }

        // Drop any card the JS featureAccessService told us to hide
        // (rollout-status / access-level / tier gates, e.g. alpha-only
        // features for non-alpha users, or tier-gated features for basic
        // users). HA / Camera / Music / Power are infrastructure cards
        // not in feature_access — they pass through.
        //
        // EXCEPTION: Voice & AI is always shown in the HA section regardless
        // of any hide rule. The card here represents access to HA Assist
        // (HA's pipeline), which works regardless of tier/access-level.
        // The page itself gates the cloud-handler sub-option separately.
        return cards.filter { card ->
            when (card.id) {
                "voice-ai" -> true
                else -> !isCardHidden(card.id)
            }
        }
    }

    /**
     * Build the Dashie Features section cards.
     * Layout: 2-column grid
     * Row 0: Calendar         | Photos
     * Row 1: AI & Voice       | Family
     * Row 2: Chores & Rewards | Locations
     */
    fun buildDashieFeatureCards(): List<ControlCenterCard> {
        val prefs = prefsProvider() ?: return emptyList()
        val cards = mutableListOf<ControlCenterCard>()

        cards.add(buildCalendarCard())
        cards.add(buildPhotosCard(prefs))
        // Voice & AI lives in Dashie section only when HA mode is disabled
        if (!prefs.connection.haEnabled) {
            cards.add(buildVoiceAiCard(prefs))
        }
        cards.add(buildFamilyFeatureCard())
        cards.add(buildChoresCard())
        cards.add(buildLocationsCard())

        // Drop any card the JS featureAccessService told us to hide.
        // Default beta user with gps=alpha rollout → Locations card removed.
        // Card stays visible (fail-open) until JS has synced — see
        // FeatureVisibilityPreferences.isHidden() docs.
        //
        // SPECIAL CASE: Voice & AI in the Dashie Features section means
        // cloud Voice/AI (no HA pipeline available here — HA is disabled).
        // Cloud Voice/AI is a BETA feature (moved alpha→beta 2026-07-03) — show
        // the card for beta+ (the hand-selected cohort), not standard users.
        return cards.filter { card ->
            when (card.id) {
                "voice-ai" -> hasBetaAccess()
                else -> !isCardHidden(card.id)
            }
        }
    }

    /**
     * Build the General section nav cards.
     * Layout: 2-column grid
     * Row 0: Account     | Preferences
     * Row 1: Display     | Advanced
     */
    fun buildGeneralCards(): List<ControlCenterCard> {
        return listOf(
            buildAccountCard(),
            ControlCenterCard("preferences", "Preferences", CardStatus.OFF, "", "open-preferences", showDot = false),
            buildDisplayCard(),
            buildAdvancedCard(),
        )
    }

    /**
     * Build the Advanced nav card.
     * Shows MQTT status when enabled (connected/error), empty when disabled.
     */
    private fun buildAdvancedCard(): ControlCenterCard {
        val mqttPrefs = prefsProvider()?.mqtt
        if (mqttPrefs != null && mqttPrefs.enabled) {
            val status = when {
                mqttPrefs.isError -> CardStatus.ERROR
                mqttPrefs.lastStatus == com.dashieapp.Dashie.halite.preferences.MqttPreferences.STATUS_CONNECTED -> CardStatus.ON
                else -> CardStatus.OFF
            }
            return ControlCenterCard(
                "advanced", "Advanced", status,
                "MQTT: ${mqttPrefs.statusDisplayText}",
                "open-advanced", showDot = false
            )
        }
        return ControlCenterCard("advanced", "Advanced", CardStatus.OFF, "", "open-advanced", showDot = false)
    }

    /**
     * Whether the user is signed in with a Dashie account.
     */
    fun isSignedIn(): Boolean {
        return prefsProvider()?.account?.isLinked == true
    }

    /**
     * Whether this device should present the full Dashie DASHBOARD experience —
     * signed in AND not an HA-only display. False for a voice-only kiosk login
     * (haOnlyDisplay=true — an account added just for cloud voice, still showing
     * HA) and for the trial-expired ha-only opt-in (forceKioskMode). The Dashie
     * Features section + "you're a dashboard user" state gate on THIS; the Account
     * card still uses isSignedIn (a voice-only account has an email + credits to
     * show, and the free-trial CTA should stay up for it). Mirrors
     * AccountPreferences.showsDashboard.
     */
    fun showsDashboard(): Boolean = prefsProvider()?.account?.showsDashboard == true

    /**
     * Build the Account nav card.
     */
    /**
     * Household key-sharing, as a sentence — `null` when the honest answer is to say nothing.
     *
     * 🔴 **Four states, three sentences.** `NOT_APPLICABLE` (HA-Assist: the lease does not govern
     * this lane) and `null` (nothing observed yet) both render **nothing**, because the only
     * alternative is to assert "Not shared" about a device that was never refused and never even
     * asked. That is the same wrong-message class as telling a user a speaker "may be offline"
     * when the server had in fact answered — and worse here, because it reads plausibly.
     *
     * ⚠️ Only the GRANTED sentence is John's. The other two are O's proposals pending his word —
     * see `JS_KOTLIN_CONTRACTS #72`, which exists so a change to them is one edit in one place
     * rather than a hunt through two codebases.
     */
    private fun sharingSummary(): String? {
        val snap = com.dashieapp.Dashie.halite.voice.lease.LeaseStateHolder.snapshot ?: return null
        return when (snap.share) {
            Share.USING_KEYS -> "Using your Home Assistant's AI keys"
            Share.FREE_ENGINES_ONLY -> "Using your Home Assistant's built-in voice"
            // 🔴 The brand token, NOT a literal. This string ships in main/ and therefore in BOTH
            // artifacts, so a hardcoded "Chickadee console" would be a brand leak in the Dashie
            // APK — my own dashie-brand-prose class pointing the other way. `brand_name` is
            // per-source-set and already proven.
            Share.NO_KEYS_CONFIGURED ->
                "No AI keys set up — add them in the ${context.getString(com.dashieapp.Dashie.R.string.brand_name)} console"
            Share.SHARING_OFF, Share.REFUSED -> "AI sharing is off for this device"
            // Renders NOTHING — see the enum. Nothing was withheld and nothing was asked.
            Share.NOT_APPLICABLE -> null
        }
    }

    fun buildAccountCard(): ControlCenterCard {
        val prefs = prefsProvider()
        // 🔴 "Not linked" is an ACCOUNT-LINKAGE claim, and in an account-free edition it asserts
        // the opposite of the truth: there is no account to be linked to, so reporting the device
        // as "not linked" describes a deficiency that does not exist. A Chickadee device is not a
        // Dashie device that failed to sign in.
        //
        // Empty rather than a substitute sentence, deliberately. The card's real content for this
        // edition is the household KEY-SHARING state ("shared by your HA account", John's
        // wording) — but that is lease state, `CapabilityLease` exposes no readable snapshot to
        // this class yet, and the exact strings for the partial-grant and refused cases are still
        // being agreed with the console side. Writing a placeholder here would either be a claim
        // I cannot yet substantiate or a second hand-written variant of a sentence that is about
        // to get a shared-vocabulary contract row. Saying nothing is the honest interim; saying
        // the wrong thing was the bug.
        val base = if (prefs?.account?.isLinked == true) {
            prefs.account.email.ifEmpty { "Linked" }
        } else if (!com.dashieapp.Dashie.edition.EditionSeams.hasAccounts) {
            // The account-free edition says what is TRUE of it: whether the household is lending
            // this tablet its voice/AI keys. That is lease state, not account state — see
            // JS_KOTLIN_CONTRACTS #72 for the canonical sentences, shared with the console.
            sharingSummary()
        } else {
            "Not linked"
        }
        // FB27: credits live on the Account card now — balance in the summary, and an orange
        // (WARN) dot when the balance is low. Out-of-credits keeps the Voice & AI card red;
        // Account carries the money state. Balance comes from the CreditBalanceReader cache
        // (refreshed on CC open); null → no credits account → plain nav card.
        val credit = com.dashieapp.Dashie.halite.voice.CreditStateHolder
        // "No credits" once effectively empty (< $0.02), else "$X.XX credits".
        val creditPart = credit.balance?.let { if (it < 0.02) "No credits" else credit.formatBalance(it) + " credits" }
        val summary = listOfNotNull(base, creditPart).joinToString(" · ")
        // FB27 + WS-L.3: credit dot only for a billable (cloud) pipeline. Out of credits → RED dot;
        // auto-refill card DECLINED → RED dot (actionable regardless of balance — the card needs
        // replacing whether or not credits are low, so it OUTRANKS the low dot); low but spendable
        // → orange dot; otherwise none. Uses the raw `autorefillFailed` (not `showAutorefillFailure`)
        // so the persistent account-row indicator stays until a charge succeeds, even if the
        // dismissible dashboard PILL was hidden. (Switch to `showAutorefillFailure` if dismissing the
        // pill should also clear the dot.)
        val billable = prefs?.voice?.isBillableVoice == true
        val outOfCredits = billable && credit.balance != null && !credit.spendable
        val autorefillFailed = billable && credit.autorefillFailed
        val low = billable && credit.low && credit.spendable
        val status = when {
            outOfCredits -> CardStatus.ERROR
            autorefillFailed -> CardStatus.ERROR
            low -> CardStatus.WARN
            else -> CardStatus.OFF
        }
        return ControlCenterCard(
            "account", "Account", status, summary, "open-account",
            showDot = outOfCredits || autorefillFailed || low
        )
    }

    /**
     * Build the Display nav card (consolidated: sleep + screensaver + wake mode + display settings).
     */
    private fun buildDisplayCard(): ControlCenterCard {
        val prefs = prefsProvider()
        val summary = if (prefs != null) buildDisplaySummary(prefs) else ""
        return ControlCenterCard("display", "Screensaver & Display", CardStatus.OFF, summary, "open-display", showDot = false)
    }

    /**
     * Build the Family feature card (in Dashie Features section).
     * Always ON once an account is created.
     */
    private fun buildFamilyFeatureCard(): ControlCenterCard {
        // Member count is loaded dynamically from JS bridge — show cached count if available
        val count = cachedFamilyMemberCount
        val summary = if (count > 0) "$count member${if (count != 1) "s" else ""}" else ""
        return ControlCenterCard("family", "Family", CardStatus.ON, summary, "open-family")
    }

    /**
     * Build the Calendar feature card.
     * Gated: requires subscription or active trial.
     */
    private fun buildCalendarCard(): ControlCenterCard {
        val subtext = subscriptionState.featureSubtext("calendar")
        if (subtext != null && !subscriptionState.hasFeatureAccess("calendar")) {
            return ControlCenterCard("calendar", "Calendar", CardStatus.OFF, subtext, "upsell-calendar")
        }
        // Auth-invalid takes precedence — surface as WARN with a concrete
        // call to action so the user knows why events stopped loading.
        if (cachedCalendarAuthInvalidCount > 0) {
            val warnSummary = if (cachedCalendarAuthInvalidCount == 1 && cachedCalendarAuthInvalidEmail.isNotEmpty()) {
                "Sign-in required: $cachedCalendarAuthInvalidEmail"
            } else {
                "${cachedCalendarAuthInvalidCount} accounts need re-sign-in"
            }
            return ControlCenterCard("calendar", "Calendar", CardStatus.WARN, warnSummary, "open-calendar")
        }
        val summary = if (cachedCalendarTotalCount > 0) {
            "${cachedCalendarAccountCount} account${if (cachedCalendarAccountCount != 1) "s" else ""}. ${cachedCalendarActiveCount} of ${cachedCalendarTotalCount} enabled"
        } else if (!calendarCountsLoaded) {
            // Counts haven't arrived from JS yet this account session —
            // "Not configured" here would be a lie for anyone with calendars
            // (the momentary flash on every CC open). Show a neutral loading
            // summary until the enumerate lands (or confirms a real zero).
            subtext ?: "Loading…"
        } else {
            subtext ?: "Not configured"
        }
        val status = if (cachedCalendarActiveCount > 0) CardStatus.ON else CardStatus.OFF
        return ControlCenterCard("calendar", "Calendar", status, summary, "open-calendar")
    }

    /**
     * Build the Locations feature card.
     * Gated: requires subscription or active trial.
     */
    private fun buildLocationsCard(): ControlCenterCard {
        val subtext = subscriptionState.featureSubtext("locations")
        if (subtext != null && !subscriptionState.hasFeatureAccess("locations")) {
            return ControlCenterCard("locations", "Locations", CardStatus.OFF, subtext, "upsell-locations")
        }
        val prefs = prefsProvider() ?: return ControlCenterCard("locations", "Locations", CardStatus.OFF, "Inactive", "open-locations")
        val tracking = prefs.locations.trackingEnabled
        val summary = subtext ?: if (tracking) "Tracking enabled" else "Inactive"
        val status = if (tracking) CardStatus.ON else CardStatus.OFF
        return ControlCenterCard("locations", "Locations", status, summary, "open-locations")
    }

    /**
     * Build the Chores & Rewards feature card.
     * Gated: requires subscription or active trial.
     */
    private fun buildChoresCard(): ControlCenterCard {
        val subtext = subscriptionState.featureSubtext("chores")
        if (subtext != null && !subscriptionState.hasFeatureAccess("chores")) {
            return ControlCenterCard("chores", "Chores & Rewards", CardStatus.OFF, subtext, "upsell-chores")
        }
        val prefs = prefsProvider() ?: return ControlCenterCard("chores", "Chores & Rewards", CardStatus.OFF, "Inactive", "open-chores")
        if (!prefs.choresRewards.choresEnabled) {
            return ControlCenterCard("chores", "Chores & Rewards", CardStatus.OFF, subtext ?: "Inactive", "open-chores")
        }
        val participantsDisplay = prefs.choresRewards.participantsDisplay()
        val summary = subtext ?: participantsDisplay
        return ControlCenterCard("chores", "Chores & Rewards", CardStatus.ON, summary, "open-chores")
    }

    /** Expose subscription state for summary text styling in the adapter */
    fun isSubscriptionWarning(featureId: String): Boolean = subscriptionState.isTrialWarning(featureId)
    fun isSubscriptionBlocked(featureId: String): Boolean = !subscriptionState.hasFeatureAccess(featureId)

    // getSubscriptionStateProvider() removed in Phase 2c: it had ZERO callers (verified by
    // grep across both source sets) and its return type pinned the concrete
    // SubscriptionStateProvider, which moves to src/dashie/ in 2e. Dead code, so removing it
    // is behaviour-neutral. If the Account page ever needs this again, expose the
    // `SubscriptionState` INTERFACE, not the implementation.

    /**
     * Build the device footer info.
     */
    fun buildFooter(): ControlCenterFooter {
        val ip = getLocalIpAddress() ?: ""
        val version = BuildConfig.VERSION_NAME.replace("-staging", "")
        // First 8 chars of the hardware-tied stable ID = the voice-license Device
        // ID shown on the purchase page (dual-accept hashes against these 8). Still
        // a stable-ID prefix, so it lines up with HA integration / license server /
        // telemetry / crash reports that key on the same ID in server-side logs.
        val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(context)
            .take(8)
            .uppercase()
        return ControlCenterFooter(ip, version, deviceId)
    }

    // ── Feature card builders ───────────────────────────────────────

    /**
     * Build the unified Voice & AI card.
     * Shown in HA section when HA connected, Dashie section otherwise.
     */
    private fun buildVoiceAiCard(prefs: HalitePreferences): ControlCenterCard {
        if (!prefs.voice.voiceEnabled) {
            return ControlCenterCard("voice-ai", "Voice & AI", CardStatus.OFF, "Inactive", "open-voice-ai")
        }

        // FB27: out of credits (cloud voice can't run) → RED with a "No credits" summary
        // (in place of the wake word / AI model). ONLY for a billable (cloud) pipeline — a
        // voice-assistant / local config never bills credits, so it shows normal.
        val credit0 = com.dashieapp.Dashie.halite.voice.CreditStateHolder
        if (prefs.voice.isBillableVoice && credit0.balance != null && !credit0.spendable) {
            // WS-D.1: when the pipeline actually fell back to free engines, voice is WORKING —
            // a red "No credits" here would contradict a tablet that just answered. Report the
            // real state (degraded, warn) and keep ERROR for the genuinely-dead case.
            return if (credit0.degraded)
                ControlCenterCard("voice-ai", "Voice & AI", CardStatus.WARN, "Local voice — out of credits", "open-voice-ai")
            else
                ControlCenterCard("voice-ai", "Voice & AI", CardStatus.ERROR, "No credits", "open-voice-ai")
        }

        val parts = mutableListOf<String>()

        // Wake word model name
        try {
            val model = wakeWordModelManagerProvider()?.getActiveModel()
            if (model != null) {
                parts.add(model.wakeWordName)
            }
        } catch (_: Exception) { }

        // AI model summary — label the EFFECTIVE pipeline's AI, not the raw aiModel pref.
        // In Voice-Assistant mode without customize, aiModel can hold a stale cloud model
        // (e.g. "gemini-…") while HA actually answers; voicePipelineMode derives the truth.
        val aiModel = prefs.voice.aiModel
        val aiLabel = when {
            prefs.voice.voicePipelineMode == VoicePreferences.VOICE_PIPELINE_MODE_HA -> "HA"
            aiModel == "home_assistant" -> "HA"
            aiModel.contains("gemini") -> "Gemini"
            aiModel.contains("claude") || aiModel.contains("sonnet") || aiModel.contains("opus") || aiModel.contains("haiku") -> "Claude"
            aiModel.contains("gpt") -> "GPT"
            aiModel.contains("nova") -> "Nova"
            else -> "AI"
        }
        parts.add(aiLabel)

        // FB27: the credit BALANCE lives on the Account card. The out-of-credits RED case is
        // handled by the early-return above (billable + not spendable); reaching here the card
        // is normal green.
        val status = CardStatus.ON

        val summary = getVoiceDisplaySummary(parts.joinToString(", ").ifEmpty { "Enabled" })
        return ControlCenterCard("voice-ai", "Voice & AI", status, summary, "open-voice-ai")
    }

    /** Check if HA is connected (URL configured) */
    private fun isHaConnected(prefs: HalitePreferences): Boolean {
        val haUrl = prefs.connection.haUrl
        return haUrl.isNotEmpty() && haUrl != HalitePreferences.DEFAULT_HA_URL
    }

    /**
     * Whether the user has Home Assistant mode enabled on this device.
     * This is the canonical "show HA UI" signal — controls visibility of
     * the HA section in the control center and HA-specific settings pages.
     * Independent of haUrl/connection state: a user can leave HA configured
     * but flip this off to hide HA UI.
     */
    fun isHaEnabled(): Boolean {
        return prefsProvider()?.connection?.haEnabled == true
    }

    /**
     * Whether this device displays a custom ("host your own dashboard") URL
     * instead of Home Assistant. Such a device has [isHaEnabled] false but still
     * needs the Dashboard section — that is where its URL is configured.
     */
    fun isCustomUrlMode(): Boolean {
        val conn = prefsProvider()?.connection ?: return false
        return conn.useCustomUrl && conn.customUrl.isNotEmpty()
    }

    /**
     * The voice card's summary.
     *
     * Used to allow the edition seam to override this with a licence-trial note ("Trial: N days
     * left"). Voice became free on 2026-08-02, so there is no note and the default always wins.
     * Kept as a named accessor rather than inlined at the call site: it is the one place a future
     * voice-summary override would go, and the callers already read it.
     */
    fun getVoiceDisplaySummary(defaultSummary: String): String = defaultSummary

    private fun buildPhotosCard(prefs: HalitePreferences): ControlCenterCard {
        val sourceType = prefs.screensaver.photoSourceType
        if (sourceType == "none" || sourceType.isEmpty()) {
            return ControlCenterCard("photos", "Photos", CardStatus.OFF, "No source", "open-photos")
        }
        val sourceLabel = when (sourceType) {
            "ha_media" -> "Home Assistant"
            "google_drive" -> "Google Drive"
            "supabase" -> "Dashie Cloud"
            "local" -> "Local Folder"
            "unsplash" -> "Unsplash"
            "immich" -> "Immich"
            else -> sourceType
        }
        val interval = prefs.screensaver.slideshowInterval
        return ControlCenterCard("photos", "Photos", CardStatus.ON, "Source: $sourceLabel, Interval: ${interval}s", "open-photos")
    }

    private fun buildMusicCard(prefs: HalitePreferences): ControlCenterCard {
        if (!prefs.connection.musicPlayerEnabled) {
            return ControlCenterCard("music", "Music Assistant", CardStatus.OFF, "Inactive", "open-music")
        }
        // Speaker-only mode — show as active
        if (prefs.connection.musicSpeakerOnly) {
            return ControlCenterCard("music", "Music Assistant", CardStatus.ON, "Speaker only", "open-music")
        }
        // Not connected — show warning
        if (!prefs.connection.hasMaApiToken) {
            return ControlCenterCard("music", "Music Assistant", CardStatus.WARN, "Not connected", "open-music")
        }
        // Token present but a 401 marked it expired — surface it (previously the card
        // still read "Connected"). The re-login prompt reappears on the next music action.
        if (prefs.connection.maTokenExpired) {
            return ControlCenterCard("music", "Music Assistant", CardStatus.WARN, "Session expired — sign in", "open-music")
        }
        // Show the default media player name
        val conn = prefs.connection
        val entityId = conn.getEffectiveMusicPlayerEntityId()
        val playerName = if (entityId.isNotEmpty()) {
            conn.musicPlayerDisplayName.ifEmpty {
                entityId.removePrefix("media_player.").replace("_", " ")
                    .replaceFirstChar { it.uppercaseChar() }
            }
        } else {
            "No player selected"
        }
        return ControlCenterCard("music", "Music Assistant", CardStatus.ON, playerName, "open-music")
    }

    private fun buildCameraCard(prefs: HalitePreferences): ControlCenterCard {
        if (!prefs.camera.rtspEnabled) {
            return ControlCenterCard("camera", "Camera Streaming (out)", CardStatus.OFF, "Inactive", "open-camera")
        }
        val isRunning = rtspRunningProvider()
        val resolution = prefs.camera.rtspResolution
        val fps = prefs.camera.rtspFps
        val details = listOfNotNull(
            resolution.ifEmpty { null },
            if (fps > 0) "${fps}fps" else null
        ).joinToString(", ")
        val label = if (isRunning) "Streaming" else "Not running"
        val summary = if (details.isNotEmpty()) "$label ($details)" else label
        return ControlCenterCard(
            "camera", "Camera Streaming (out)",
            if (isRunning) CardStatus.ON else CardStatus.WARN,
            summary, "open-camera"
        )
    }

    private fun buildVideoFeedsCard(prefs: HalitePreferences): ControlCenterCard {
        if (!prefs.videoFeed.enabled) {
            return ControlCenterCard("video-feeds", "Video Feeds (in)", CardStatus.OFF, "Inactive", "open-video-feeds")
        }
        val enabledRules = try {
            prefs.videoFeed.getEnabledRules()
        } catch (_: Exception) { emptyList() }
        val count = enabledRules.size
        return if (count > 0) {
            ControlCenterCard("video-feeds", "Video Feeds (in)", CardStatus.ON,
                "$count stream${if (count != 1) "s" else ""}", "open-video-feeds")
        } else {
            ControlCenterCard("video-feeds", "Video Feeds (in)", CardStatus.WARN, "No streams", "open-video-feeds")
        }
    }

    private fun buildHaCard(prefs: HalitePreferences): ControlCenterCard {
        val conn = prefs.connection
        val haUrl = conn.haUrl
        val connected = haUrl.isNotEmpty() && haUrl != HalitePreferences.DEFAULT_HA_URL

        // Custom-URL ("host your own dashboard") devices have haEnabled=false but
        // still need this card — it is the ONLY route to the dashboard URL
        // settings. Report the custom URL rather than the HA one, which is unset
        // there and would otherwise read "Not configured" on a working device.
        if (conn.useCustomUrl && conn.customUrl.isNotEmpty()) {
            return ControlCenterCard(
                "ha", CARD_LABEL,
                CardStatus.ON, conn.customUrl.removePrefix("https://").removePrefix("http://"),
                "open-ha"
            )
        }

        // Needs setup / needs login: surface as orange WARN with a
        // descriptive summary, but keep the action as the standard
        // "open-ha" so the click routes through the same settings
        // navigation path as a normal HA card tap. The HA settings page
        // schema renders a "Sign-in Required" section + the existing
        // "Dashboard URL" navigation, so the user can fix either issue
        // from there.
        val needsUrlSetup = conn.haEnabled
            && (haUrl.isEmpty() || haUrl == HalitePreferences.DEFAULT_HA_URL)
        val needsLogin = conn.haEnabled && !needsUrlSetup && !conn.hasHaAccessToken
        if (needsUrlSetup) {
            return ControlCenterCard(
                "ha", CARD_LABEL,
                CardStatus.WARN, "URL not configured", "open-ha"
            )
        }
        if (needsLogin) {
            return ControlCenterCard(
                "ha", CARD_LABEL,
                CardStatus.WARN, "Sign-in required", "open-ha"
            )
        }

        val parts = mutableListOf<String>()
        if (haUrl.isEmpty() || haUrl == HalitePreferences.DEFAULT_HA_URL) parts.add("URL: Not set")

        // API status: check if enabled and whether the server bound successfully
        var apiWarn = false
        if (conn.apiEnabled) {
            val (bindFailed, port) = apiBindFailedProvider()
            if (bindFailed) {
                parts.add("API: Port $port in use")
                apiWarn = true
            } else {
                parts.add("API: Enabled")
            }
        }

        val hides = mutableListOf<String>()
        if (conn.hideSidebar) hides.add("Sidebar")
        if (conn.hideTabs) hides.add("Header")
        if (hides.isNotEmpty()) parts.add("Hide: ${hides.joinToString(", ")}")

        val summary = parts.joinToString(", ").ifEmpty {
            if (connected) "Connected" else "Not configured"
        }
        val status = when {
            apiWarn -> CardStatus.WARN
            connected -> CardStatus.ON
            else -> CardStatus.OFF
        }
        return ControlCenterCard("ha", CARD_LABEL,
            status, summary, "open-ha")
    }

    private fun buildWeatherCard(prefs: HalitePreferences): ControlCenterCard {
        val enabled = prefs.screensaver.weatherOverlayEnabled
        if (!enabled) {
            return ControlCenterCard("weather", "Weather & Date/Time", CardStatus.OFF, "Inactive", "open-weather")
        }
        val zip = prefs.general.zipCode
        val entity = prefs.screensaver.weatherEntityId
        val parts = mutableListOf<String>()
        if (zip.isNotEmpty()) parts.add("ZIP: $zip")
        if (entity.isNotEmpty() && entity != "weather.forecast_home") parts.add(entity)
        val summary = parts.joinToString(", ").ifEmpty { "Enabled" }
        return ControlCenterCard("weather", "Weather & Date/Time", CardStatus.ON, summary, "open-weather")
    }

    private fun buildPowerCard(prefs: HalitePreferences): ControlCenterCard {
        val powerConfig = loadPowerConfig()
        if (!powerConfig.enabled) {
            return ControlCenterCard("power", "Battery Charging", CardStatus.OFF, "Inactive", "open-power")
        }

        // Check for configuration problems (orange indicators)
        if (powerConfig.entityId.isEmpty()) {
            return ControlCenterCard("power", "Battery Charging", CardStatus.WARN,
                "No switch configured", "open-power")
        }
        val reachable = com.dashieapp.Dashie.halite.settings.SettingsActivity.powerSwitchReachable
        if (reachable == false) {
            return ControlCenterCard("power", "Battery Charging", CardStatus.WARN,
                "Switch unreachable", "open-power")
        }

        var summary = "Enabled"
        var powerStatus = CardStatus.WARN
        if (batteryLevel >= 0) {
            val ctx = if (batteryCharging && batteryLevel > powerConfig.maxThreshold) {
                powerStatus = CardStatus.ERROR
                ">${powerConfig.maxThreshold}%, charging"
            } else if (batteryCharging) {
                powerStatus = CardStatus.ON
                "Charging to ${powerConfig.maxThreshold}%"
            } else if (batteryLevel < powerConfig.minThreshold) {
                powerStatus = CardStatus.ERROR
                "<${powerConfig.minThreshold}%, not charging"
            } else if (powerConfig.preferNight) {
                val now = java.util.Calendar.getInstance()
                val hhmm = String.format("%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
                val nightStart = powerConfig.nightStart
                val nightEnd = powerConfig.nightEnd
                val inNight = if (nightStart <= nightEnd) {
                    hhmm >= nightStart && hhmm < nightEnd
                } else {
                    hhmm >= nightStart || hhmm < nightEnd
                }
                if (inNight) {
                    "Discharging to ${powerConfig.minThreshold}%"
                } else {
                    val h = nightStart.split(":").firstOrNull()?.toIntOrNull() ?: 22
                    val period = if (h < 12) "am" else "pm"
                    val displayH = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
                    "Charge after $displayH$period"
                }
            } else {
                "Discharging to ${powerConfig.minThreshold}%"
            }
            summary = "Battery: $batteryLevel% ($ctx)"
        }

        return ControlCenterCard("power", "Battery Charging",
            powerStatus, summary, "open-power")
    }

    /**
     * Load power management config from PowerPreferences (individual SharedPreferences keys).
     */
    private fun loadPowerConfig(): PowerConfig {
        return try {
            val p = PowerPreferences(context)
            PowerConfig(
                enabled = p.enabled,
                entityId = p.entityId,
                preferNight = p.preferNight,
                minThreshold = p.minThreshold,
                maxThreshold = p.maxThreshold,
                nightStart = p.nightStart,
                nightEnd = p.nightEnd
            )
        } catch (_: Exception) { PowerConfig() }
    }

    private data class PowerConfig(
        val enabled: Boolean = false,
        val entityId: String = "",
        val preferNight: Boolean = false,
        val minThreshold: Int = 20,
        val maxThreshold: Int = 80,
        val nightStart: String = "22:00",
        val nightEnd: String = "06:00"
    )

    /**
     * Check if camera card is in WARN state (enabled but not yet streaming).
     * Lightweight check — avoids rebuilding all cards just to inspect camera.
     */
    fun isCameraWarn(): Boolean {
        val prefs = prefsProvider() ?: return false
        if (!prefs.camera.rtspEnabled) return false
        return !rtspRunningProvider()
    }

    // ── Nav card summary builders ───────────────────────────────────

    private fun buildDisplaySummary(prefs: HalitePreferences): String {
        val ss = prefs.screensaver
        val sleep = prefs.sleep

        // Screensaver mode + interval
        val modeLabels = mapOf(
            "dim" to "Dim", "black" to "Black Overlay", "off" to "Screen Off",
            "photos" to "Photos", "ha_page" to "HA Page", "url" to "URL", "app" to "App"
        )
        val modeStr = modeLabels[ss.screensaverMode] ?: ss.screensaverMode.replaceFirstChar { it.uppercase() }
        val interval = ss.screensaverTimeout
        // Timeout = 0 means screensaver is disabled — report "Off" instead of
        // the (now-inactive) mode so the card doesn't lie about the device state.
        val screensaverPart = if (interval > 0) "$modeStr (${formatTimeout(interval)})" else "Off"

        // Sleep
        val sleepPart = if (sleep.sleepEnabled) "on" else "off"

        // Final segment: the wake mode is only meaningful for HA / advanced-
        // tablet-control users (touch/motion/etc.). For a plain cloud tablet
        // "Wake: Touch" is noise — surface the display theme instead.
        val lastPart = if (prefs.connection.advancedTabletControlsEnabled) {
            val wakeLabels = mapOf(
                "disabled" to "Touch", "touch" to "Touch",
                "brightness" to "Brightness", "camera" to "Motion", "face" to "Face"
            )
            "Wake: ${wakeLabels[ss.motionWakeMode] ?: "Touch"}"
        } else {
            val themeFamily = context
                .getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                .getString("theme_family", "default") ?: "default"
            "Theme: ${themeFamily.replaceFirstChar { it.uppercase() }}"
        }

        return "$screensaverPart · Sleep: $sleepPart · $lastPart"
    }

    private fun buildScreensaverSummary(prefs: HalitePreferences): String {
        val ss = prefs.screensaver
        val mode = ss.screensaverMode
        val timeout = ss.screensaverTimeout
        val modeLabels = mapOf(
            "dim" to "Dim", "black" to "Black Overlay", "off" to "Screen Off",
            "photos" to "Photos", "ha_page" to "HA Page", "url" to "URL", "app" to "App"
        )
        var modeStr = modeLabels[mode] ?: mode.replaceFirstChar { it.uppercase() }
        if (mode == "app") {
            val appLabel = ss.launchAppLabel
            if (appLabel.isNotEmpty()) modeStr += " ($appLabel)"
        }
        // Timeout = 0 disables the screensaver entirely — report "Off"
        // instead of the (inactive) mode label.
        return if (timeout > 0) "$modeStr, ${formatTimeout(timeout)}" else "Off"
    }

    private fun buildSleepSummary(prefs: HalitePreferences): String {
        val sleep = prefs.sleep
        if (!sleep.sleepEnabled) return "Inactive"

        val timeStr = if (sleep.sleepMethod == "inactivity") {
            "${formatTimeout(sleep.inactivityTimeout)} timeout"
        } else {
            "${formatTime(sleep.sleepTime, prefs)} / ${formatTime(sleep.wakeTime, prefs)}"
        }
        val hardwareOff = prefs.sleep.hardwareScreenOff
        val offLabel = if (hardwareOff) "Power Off" else "Black Overlay"
        return "$timeStr ($offLabel)"
    }

    private fun buildWakeModeSummary(prefs: HalitePreferences): String {
        return when (prefs.screensaver.motionWakeMode) {
            "disabled" -> "Touch Only"
            "brightness" -> "Brightness Sensor"
            "camera" -> "Motion (Camera)"
            "face" -> "Face Detection (Camera)"
            else -> "Touch Only"
        }
    }

    private fun buildSystemSummary(): String {
        val version = android.os.Build.VERSION.RELEASE
        return "Android $version"
    }

    // ── Battery ─────────────────────────────────────────────────────

    fun refreshBattery() {
        try {
            // Use sticky ACTION_BATTERY_CHANGED broadcast (same source as SettingsActivity)
            // for reliable charging detection — BatteryManager.isCharging can lag behind
            val intent = context.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                batteryCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            } else {
                // Fallback to BatteryManager API
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
                batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                batteryCharging = bm.isCharging
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery", e)
        }
    }

    private fun detectDeviceType(prefs: HalitePreferences) {
        val uiMode = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK
        hasBattery = uiMode != android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun formatTimeout(seconds: Int): String {
        if (seconds <= 0) return "Off"
        if (seconds < 60) return "${seconds}s"
        return "${seconds / 60} min"
    }

    private fun formatTime(time24h: String, prefs: HalitePreferences): String {
        val use24h = prefs.display.use24HourClock
        if (use24h) return time24h
        val parts = time24h.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return time24h
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val period = if (h >= 12) "pm" else "am"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$h12$period" else "$h12:${String.format("%02d", m)}$period"
    }

    private fun formatEntityId(entityId: String): String {
        val name = if (entityId.contains(".")) entityId.substringAfter(".") else entityId
        return name.replace("_", " ").split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun getLocalIpAddress(): String? {
        val now = System.currentTimeMillis()
        if (cachedIpAddress != null && now - ipCacheTimestamp < IP_CACHE_TTL_MS) {
            return cachedIpAddress
        }
        cachedIpAddress = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
        ipCacheTimestamp = now
        return cachedIpAddress
    }
}
