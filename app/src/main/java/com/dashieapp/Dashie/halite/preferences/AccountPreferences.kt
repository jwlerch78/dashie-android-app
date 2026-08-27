package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences
import com.dashieapp.Dashie.BuildConfig

/**
 * Account preferences for Dashie Kiosk.
 *
 * Manages:
 * - Dashie account linked state (whether user has signed in to dashieapp.com)
 * - Account email
 * - Dashie URL (staging vs production)
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class AccountPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        private const val KEY_DASHIE_LINKED = "dashie_account_linked"
        private const val KEY_DASHIE_EMAIL = "dashie_account_email"
        private const val KEY_DASHIE_AUTH_USER_ID = "dashie_auth_user_id"
        private const val KEY_DASHIE_URL = "dashie_url"
        private const val KEY_FORCE_KIOSK = "force_kiosk_mode"
        // D2 (Kiosk Real Login): this DEVICE displays Home Assistant even when signed in.
        private const val KEY_HA_ONLY_DISPLAY = "ha_only_display"
        // Distinguishes a DIRECT voice-only signup (flow=voice, device_type='ha_app')
        // from a household-sharing-provisioned kiosk — both set haOnlyDisplay=true, but
        // only the shared kiosk re-provisions on boot. Drives whether Account offers a
        // real Sign Out vs "manage in the console". See KEY_HA_ONLY_VOICE_SIGNUP below.
        private const val KEY_HA_ONLY_VOICE_SIGNUP = "ha_only_voice_signup"

        // Was THIS SESSION kiosk-provisioned? See kioskProvisionedSession below.
        private const val KEY_KIOSK_PROVISIONED_SESSION = "kiosk_provisioned_session"
        // True when forceKioskMode was set as part of the trial-expired
        // 'Continue with HA Only' flow. Lets the subscribe-back path
        // distinguish a user who opted into ha_only (and should be
        // restored to full Dashie on subscription activation) from one
        // who manually toggled kiosk via Settings (whose explicit choice
        // we shouldn't undo). Cleared when the restore fires.
        private const val KEY_KIOSK_FROM_HA_ONLY = "kiosk_from_ha_only"
        // D.52 — one-shot flag for the first kiosk → widgets transition.
        // First time the user leaves kiosk for widgets we auto-pin the
        // sidebar (optimal default). Subsequent toggles respect the user's
        // explicit choice.
        private const val KEY_FIRST_KIOSK_TO_WIDGETS_DONE = "first_kiosk_to_widgets_auto_pin_done"

        private val DEFAULT_DASHIE_URL = BuildConfig.DASHIE_URL
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether a Dashie account has been linked */
    var isLinked: Boolean
        get() = prefs.getBoolean(KEY_DASHIE_LINKED, false)
        set(value) { prefs.edit().putBoolean(KEY_DASHIE_LINKED, value).commit() }

    /** Email of the linked Dashie account */
    var email: String
        get() = prefs.getString(KEY_DASHIE_EMAIL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DASHIE_EMAIL, value).commit() }

    /** Supabase auth user ID (UUID) — needed for subscription checkout URLs */
    var authUserId: String
        get() = prefs.getString(KEY_DASHIE_AUTH_USER_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DASHIE_AUTH_USER_ID, value).commit() }

    /** Dashie URL (staging or production) */
    var dashieUrl: String
        get() = prefs.getString(KEY_DASHIE_URL, DEFAULT_DASHIE_URL) ?: DEFAULT_DASHIE_URL
        set(value) { prefs.edit().putString(KEY_DASHIE_URL, value).commit() }

    /** Force kiosk mode even when signed in (debug builds only) */
    var forceKioskMode: Boolean
        get() = prefs.getBoolean(KEY_FORCE_KIOSK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FORCE_KIOSK, value).commit()
        }

    /**
     * **D2 — this DEVICE shows Home Assistant, even though it is signed in.**
     *
     * Kiosk Real Login (.reference/build-plans/20260713_KIOSK_REAL_LOGIN.md, D2): "Logged in ≠
     * shows the dashboard." A wall tablet that self-provisions into the household account is a
     * fully logged-in device — but it must keep displaying Home Assistant, which is the entire
     * reason it exists.
     *
     * Why not reuse [forceKioskMode]: that flag is **account-driven** — `maybeApplyHaOnlyKiosk`
     * sets it from the subscription tier (`ha_only`) and `maybeRestoreFromHaOnly` CLEARS it when
     * the account subscribes. Borrowing it for a device concern means the day the household
     * subscribes, every kiosk silently flips to the dashboard. This is a property of the DEVICE,
     * so it gets its own device-scoped flag.
     *
     * Set once when a kiosk provisions itself ([com.dashieapp.Dashie.halite.auth.KioskSessionProvisioner]).
     * Never set for a full-app device, so their behavior is unchanged.
     */
    var haOnlyDisplay: Boolean
        get() = prefs.getBoolean(KEY_HA_ONLY_DISPLAY, false)
        set(value) { prefs.edit().putBoolean(KEY_HA_ONLY_DISPLAY, value).commit() }

    /**
     * True when this device is signed into a DIRECT voice-only account — the
     * `flow=voice` Google signup from the Voice & AI cloud-activation dialog
     * (JsBridgeSystemDelegate.returnToKioskAfterSignIn), which creates an
     * `ha_only` account (device_type='ha_app') and keeps the device in kiosk
     * display ([haOnlyDisplay] = true).
     *
     * Both a voice-only signup AND a household-sharing-provisioned kiosk set
     * [haOnlyDisplay], but they need OPPOSITE Account UI: a shared kiosk silently
     * re-provisions on its next boot (KioskSessionProvisioner), so a local Sign
     * Out is a lie and it must be managed in the console; a voice-only account is
     * a real per-device login the user did here, so Sign Out sticks. This flag is
     * the discriminator.
     *
     * Defaults false — so existing shared kiosks (which never set it) keep the
     * "manage in console" section with no migration step. Only the voice-only
     * path sets it true; the provisioner sets it false, so a device that flips
     * from a voice-only account to a shared kiosk gets the right UI.
     */
    var haOnlyVoiceSignup: Boolean
        get() = prefs.getBoolean(KEY_HA_ONLY_VOICE_SIGNUP, false)
        set(value) { prefs.edit().putBoolean(KEY_HA_ONLY_VOICE_SIGNUP, value).commit() }

    /**
     * **Was the CURRENT SESSION provisioned by the kiosk flow?** — i.e. did this tablet
     * authorize itself into a household account via Home Assistant, rather than a human signing
     * in here.
     *
     * 🔴 **Why this exists rather than reusing [haOnlyDisplay]** (Thread M, 2026-08-01). The
     * Account page used `haOnlyDisplay` as a proxy for "this session is a shared kiosk". Those
     * are different questions and they diverge: `haOnlyDisplay` is a **sticky DEVICE-DISPLAY**
     * property, so an HA-displaying tablet that is later signed into normally still had it set —
     * and the page then asserted a false origin ("Signed in via Home Assistant") and **withheld
     * Sign Out** on a rationale that did not apply to that session, leaving no working on-device
     * control at all.
     *
     * This flag answers the SESSION question only, and the two writers are the two ways a session
     * begins:
     *  - [com.dashieapp.Dashie.halite.auth.KioskSessionProvisioner] sets it **true** where it
     *    mints the `device_type='ha_kiosk'` session,
     *  - `JsBridgeSystemDelegate.onDashieAuthComplete` sets it **false**, because a login the
     *    user performed on this device is by definition not kiosk-provisioned.
     *
     * That second writer is the actual bug fix: nothing used to clear the sticky flag, which is
     * how an ordinary login landed in the kiosk section permanently.
     *
     * Defaults false, so an existing device that never re-provisions gets a real Sign Out — the
     * safe direction. A shared kiosk re-provisions on its next boot and sets it true again.
     */
    var kioskProvisionedSession: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_PROVISIONED_SESSION, false)
        set(value) { prefs.edit().putBoolean(KEY_KIOSK_PROVISIONED_SESSION, value).commit() }

    /**
     * **The single answer to "does this device show the Dashie dashboard?"**
     *
     * This predicate (`isLinked && !forceKioskMode`) was copy-pasted across MainUrlHandler,
     * MainWebViewBootstrap, MainHaliteSetup, MainKioskController and MainLifecycleHandler — a
     * 7-way hand-mirror of one concept. D2 needed to add a term to it, and adding that term to
     * seven places independently is how they drift apart (one site forgets, and the tablet
     * half-believes it's a dashboard). Centralized here so there is exactly one definition.
     *
     * A signed-in KIOSK answers **false**: it has a real account session AND shows Home Assistant.
     * That combination is the whole point of Kiosk Real Login, and it was previously impossible
     * to express.
     */
    val showsDashboard: Boolean
        get() = isLinked && !forceKioskMode && !haOnlyDisplay

    /**
     * True when forceKioskMode was set via the trial-expired modal's
     * "Continue with HA Only" flow. Used by the subscription-update
     * handler to know whether a flip to active/trialing/complimentary
     * should automatically restore full-Dashie layout (this flag = true)
     * or leave kiosk in place (the user toggled it from Settings).
     */
    var kioskFromHaOnly: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_FROM_HA_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_KIOSK_FROM_HA_ONLY, value).commit()
        }

    /** D.52 — true once the auto-pin sidebar on first kiosk → widgets has run */
    var firstKioskToWidgetsAutoPinDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_KIOSK_TO_WIDGETS_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FIRST_KIOSK_TO_WIDGETS_DONE, value).commit()
        }

    /** Clear account state on sign out */
    fun clear() {
        prefs.edit()
            .remove(KEY_DASHIE_LINKED)
            .remove(KEY_DASHIE_EMAIL)
            // D.59 — also clear auth user ID. Previously this lingered
            // after sign-out and could re-link the same auth.users row
            // to the device on a subsequent silent OAuth restore.
            .remove(KEY_DASHIE_AUTH_USER_ID)
            // Clear the voice-only marker too — a subsequent (re)link decides
            // afresh whether it's a direct voice-only account or a shared kiosk.
            .remove(KEY_HA_ONLY_VOICE_SIGNUP)
            // Session origin dies with the session — a later (re)link decides afresh.
            .remove(KEY_KIOSK_PROVISIONED_SESSION)
            .commit()
    }
}
