package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.BuildConfig

/**
 * Where an opted-in device sends wake-word training clips.
 *
 * ## Why this exists at all
 *
 * `BuildConfig.SUPABASE_URL` is **blank in every Chickadee flavor** (2026-08-02 decision: the
 * account-free edition ships no Supabase credentials), so that edition had nowhere to send samples
 * — it captured clips and then discarded them.
 *
 * That mattered because the `chickadee` wake model ships enabled by default and its field recall is
 * only measurable from real captures. The account-free edition was therefore carrying whatever
 * recall cost the model has while returning no measurement of it. Closing that gap is what this
 * file is for — and only for devices whose owner has switched sample collection on.
 *
 * ## Why a URL constant is the right shape, and not a credential
 *
 * The endpoint is `anonymous-wake-word-upload` — its own header says *"No authentication required —
 * uses device hash for rate limiting and consent tracking"*. So this is a **destination** decision,
 * not a re-introduction of the account credentials removed by a product decision.
 *
 * The precedent is exact and already accepted: [com.dashieapp.Dashie.wakeword.models
 * .WakeWordModelManager]'s `MANIFEST_URL` is a public Dashie-hosted URL that ALREADY ships in
 * Chickadee as a ruled residue — public, keyless, Dashie-hosted, and for the SAME wake word.
 * This is the same class of trade, pointed at the same (prod) project.
 *
 * ⚠️ **The trade-off is real and is stated here rather than hidden:** an account-free Chickadee
 * device whose owner opts in sends audio to a Dashie-controlled host. Collection is off unless the
 * user turns it on, and the upload carries a device hash rather than an identity. Whether that
 * trade is acceptable is a product decision recorded elsewhere, not one this file makes.
 *
 * ## ✅ USABLE — the 2026-08-04 blocker is retired, and this is the measurement not the memory
 *
 * A keyless POST used to be refused by the Supabase **gateway** before the function ran
 * (`401 UNAUTHORIZED_NO_AUTH_HEADER`), because the endpoint was deployed with the default
 * `verify_jwt = true`. A `config.toml` entry fixed that. Verified from a Fire tablet with an
 * untouched device-written WAV (**200**, with a corrupted-RIFF control returning **415**, so the
 * validator discriminates rather than accepting anything), and re-probed keylessly on 2026-08-05
 * against the staging host below: `POST {}` → **400** `{"error":"Missing audioBase64 or
 * metadata"}` — a function-body answer, so the request arrives.
 *
 * [uploadUnavailableReason] therefore no longer hard-blocks keyless builds. It still names a
 * blank host, and the uploader still treats a 401 as PERMANENT with a loud `DROP:`, so a future
 * host that refuses keyless posts fails loudly instead of retrying forever.
 */
object WakeSampleIngest {

    /**
     * Public ingest host for editions that ship no Supabase credentials.
     *
     * 🔴 **STAGING, changed from prod 2026-08-05** — decision that day: no prod deploys yet, dev
     * only.
     *
     * The prior comment here read *"PROD deliberately… a Chickadee beta box is a real user's
     * device, and its clips are training data for the shipped model, not staging traffic"*, and
     * that reasoning is still sound about WHERE the clips ideally belong. What made it wrong is a
     * fact about the other end, probed rather than assumed: **the prod function exists and is
     * ACTIVE (v32, 2026-07-09)** — so "no prod deploys" does not mean this URL 404s. It means a
     * released beta APK would upload into **July code, keyless and unhardened**, which is the
     * exact condition the pre-release precondition was written to prevent. A live-but-stale
     * endpoint is more dangerous than an absent one, because nothing fails loudly.
     *
     * Staging carries the hardened build, keyless-verified (`23359c718`) — including the
     * structural WAV validator probed from a Fire tablet (real clip → 200; the same clip with
     * only its RIFF size corrupted → 415, so the validator discriminates rather than waving
     * everything through).
     *
     * ⚠️ **Named cost, so this is a decision and not a drift:** opted-in beta clips land in the
     * STAGING project, not prod — open to reversal. Flip this back to `cseaywxcvnxcsypaqaid` the
     * moment prod carries the hardened function; that flip is the ONLY change needed, because
     * everything else here is environment-agnostic.
     */
    private const val PUBLIC_BASE_URL = "https://cwglbtosingboqepsmjk.supabase.co"

    /** Path on whichever base URL applies. One spelling for both editions. */
    const val ENDPOINT_PATH = "/functions/v1/anonymous-wake-word-upload"

    /**
     * The base URL this build uploads to: its own project when it has one, else the public host.
     *
     * Dashie builds are unchanged — they keep using their own project (and staging keeps uploading
     * to staging). Only a credential-free edition falls through to the public constant.
     */
    fun baseUrl(): String =
        if (BuildConfig.SUPABASE_URL.isNotBlank()) BuildConfig.SUPABASE_URL else PUBLIC_BASE_URL

    /** Full upload URL for this build. Never relative — a relative URL was a silent-failure bug. */
    fun uploadUrl(): String = baseUrl() + ENDPOINT_PATH

    /** True when this build sends its own Supabase key (i.e. an edition WITH an account project). */
    fun hasProjectKey(): Boolean = BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /**
     * Why uploads cannot work in this build, or null when they can be attempted.
     *
     * ⚠️ Returns a reason rather than a boolean so the caller's `DROP:` names the ACTUAL blocker.
     * "Uploads are off" is the kind of message that sends the next person to read the wrong file.
     */
    fun uploadUnavailableReason(): String? = when {
        baseUrl().isBlank() ->
            "no ingest host is configured for this build"
        // 🔴 THE KEYLESS BLOCK THAT USED TO LIVE HERE IS GONE — it was retired by measurement,
        // twice. It hard-refused every account-free build on a 401 measured 2026-08-04, when the
        // endpoint was still deployed verify_jwt=true. A config.toml entry landed; it was probed
        // from a Fire tablet with a real device-written WAV (200, plus a corrupted-RIFF control
        // that returned 415, so the validator discriminates), and again keylessly on 2026-08-05
        // against the staging host this file now points at:
        //
        //     POST {}  ->  HTTP 400 {"success":false,"error":"Missing audioBase64 or metadata"}
        //
        // A 400 from the FUNCTION BODY, not a 401 from the gateway — the request arrives.
        //
        // ⚠️ Deliberately NOT replaced with a new precondition, because a hardcoded one is what
        // went stale: it kept the feature inert for a day after the blocker was fixed, which is
        // the authored-but-unreached shape from the other direction. The runtime already handles
        // refusal correctly — the uploader treats 401 as PERMANENT and logs a loud DROP naming it
        // — so attempting is safe and self-reporting, and re-pointing PUBLIC_BASE_URL at a host
        // that refuses keyless posts degrades loudly instead of silently.
        else -> null
    }
}
