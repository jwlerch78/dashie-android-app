plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.play.publisher)
    kotlin("plugin.serialization") version "2.2.0"
}

// File-size budget gate — fails the build when a Kotlin file outgrows its budget.
// Grandfathered files: file-size-baseline.txt. Details in file-size-gate.gradle.
apply(from = "file-size-gate.gradle")

/**
 * 🔴 BUILD STAMP — the machine-readable identity of this APK.
 *
 * Required by the 2026-08-02 versioning policy, which is what created the gap: versionName is
 * now a hand-assigned letter suffix and versionCode is frozen until a store release, so
 * **neither field discriminates two builds any more.** T hit this in the field — a Mio and a
 * Samsung both reporting `1.0.16-staging` / code 176, installed ten hours apart, one of them
 * predating the whole lease wiring. Nothing on the device could tell them apart.
 *
 * Letter suffixes stay John's human-facing label; this is the discriminator. Same shape the
 * kiosk and brain bundles already use (`builtFrom` + SHA).
 *
 * Fails SOFT to "unknown": a build from a tarball with no git, or a git that errors, must not
 * break the build over a diagnostic string.
 */
fun gitStamp(): String = try {
    val sha = providers.exec {
        commandLine("git", "rev-parse", "--short=9", "HEAD")
    }.standardOutput.asText.get().trim()
    val dirty = providers.exec {
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText.get().trim().isNotEmpty()
    if (sha.isEmpty()) "unknown" else if (dirty) "$sha-dirty" else sha
} catch (e: Exception) {
    "unknown"
}

val buildGitSha: String = gitStamp()

android {
    namespace = "com.dashieapp.Dashie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dashieapp.Dashie"
        // Lowered to API 23 (Android 6) for older device support
        // Note: This is experimental - some features may not work on Android 6 devices
        // CameraX requires API 23 minimum, Notification channels fallback for API <26
        minSdk = 23
        targetSdk = 36
        // 🔴 VERSIONING POLICY (John, 2026-08-02): branches do NOT climb the version.
        // versionName on a non-release branch = the last SHIPPED release plus a letter
        // (1.0.16B, 1.0.16C, …) — human-readable, and it cannot race the release line upward.
        // versionCode is RESERVED for store releases and is assigned at /publish time as
        // last-shipped + 1; a sideload installs over any code via `adb install -r`, and the only
        // collision that ever mattered is a store upload, which only the release line does.
        //
        // ⚠️ AMENDED 2026-08-04 (John, the 1.1.0 wake charter) — the lines below no longer
        // follow the letter-suffix rule, and that is deliberate rather than a violation.
        // OPENING a release line is the one act the policy reserves to John, and he opened
        // 1.1.0 (the voice line — see memory `release_lines_v1`, and .reference/release-state
        // .json, whose last SHIPPED entry is 1.0.16 / code 176). So 177 IS "last-shipped + 1",
        // the policy's own formula, applied at the moment the policy says to apply it.
        //
        // The letter-suffix rule is untouched and still governs ordinary branch work: it is
        // what you do BETWEEN release lines, not instead of one.
        //
        // 📌 This comment used to end "So this stays at the last shipped code (176) rather
        // than claiming 177." It was true when written and the bump made it false. Rewritten
        // with the bump, in the same change, because a policy note that outlives its own
        // condition is the exact defect class the brand-gen provenance leg exists to catch —
        // and prose has no gate.
        // ⚠️ RE-AMENDED 2026-08-18 (John's recut word, executed by O): 1.0.17 un-shelved and
        // took 177 (release/v1.0.17, A s168 cont.10), so last-shipped becomes 177 and the
        // 1.1.0 line takes 178 — the same last-shipped + 1 formula, re-applied. The 1.1.0
        // freeze tag advances from prod-voice-mvp-apk-9b4ce64a to the commit carrying this bump.
        // ⚠️ RE-AMENDED 2026-08-22 (John's re-cut word, executed by O): four artifacts came to
        // share vc178 across the 1.1.0 iteration (T s43 cont.24's version-identity flag), so the
        // 0ffb0d43 re-cut takes 179 — 178 is spent, exactly as 177 was. versionName stays 1.1.0
        // (John: "keep version name"; the line has never shipped to a store, so the name holds
        // and the code disambiguates). The freeze tag advances to the commit carrying this bump.
        // ⚠️ RE-AMENDED 2026-08-23 (the vc180 advance, executed by O per the accepted release
        // train): vc179 re-collided across two artifacts (T s43 cont.33), and this advance
        // carries A's cont.5–8 batch (TTS-window barge-in bar 70% — John's product ruling —
        // suppressed-fire + echo-deafness + AEC-engagement instrumentation, wake-tail decoder
        // fix, provenance per-bundle stamps, AiModelChangeGuard WARN fix). Same formula:
        // 179 is spent, this build takes 180; versionName holds 1.1.0. The freeze tag advances
        // to the commit carrying this bump.
        // ⚠️ RE-AMENDED 2026-08-23 (the vc181 advance, executed by A per John's ship ruling —
        // "Keep at 1.1.0. We're still pre any 1.1 releases."): the wake-word RE-ARM RACE fix
        // folds INTO the 1.1.0 line rather than waiting for a 1.1.1 that does not exist yet,
        // so the vc180 lock dissolves into a pin ADVANCE, not a reopen. 180 is spent; this
        // build takes 181; versionName holds 1.1.0 for the same reason as every advance above
        // (the line has never shipped to a store, so the name holds and the code disambiguates).
        //
        // What this advance carries, all Thread A, and NOTHING from another thread rides along
        // (verified: `git log d121e05d..HEAD` is six commits, all mine):
        //   22cb40d3  WeatherTemplate.kt answers "tomorrow"/"precip_timing" (the third twin)
        //   b47d596a  tail-skip KDoc correction — the net-skip arithmetic (0ms/180ms)
        //   710d72bf  …and its amendment, withdrawing "IS FALSIFIED" after T retracted cont.39
        //   76c10ace  RE-ARM RACE fix: MWW back-fills the history it missed; every discard loud
        //   8105a76e  9 device-free vectors + a negative control over the back-fill arithmetic
        //   986fb796  reach matched to EI's window; re-arm settle bar dropped (device-verified)
        //
        // 📌 The cold-start settle window stays ~3 s, DOCUMENTED AND DELIBERATELY NOT RETUNED
        // (John: "Not worried about cold start on app boot"). It is a separate call from this
        // fix, and MicroWakeWordDetector.COLD_START_MIN_INFERENCES records why.
        //
        // The freeze tag advances to the commit carrying this bump.
        // ⚠️ RE-AMENDED 2026-08-23 (the vc182 advance, executed by A): carries the moonshine-base
        // .onnx re-export John ruled INTO 1.1.0 (download 111 MB -> 48 MB, on-disk 141 -> 64),
        // the overlay-dismiss race fix, and the closure of the AND-gate-during-playback question.
        // 181 is spent; this build takes 182; versionName holds 1.1.0.
        //
        // 🔴 THIS PIN HAS AN EXTERNAL PRECONDITION THE OTHERS DID NOT: the model archive must be
        // live at SttModelRegistry's DASHIE_MODELS path before the download leg can pass. As of
        // this commit the bucket returns HTTP 400 (does not exist). The failure is loud and safe
        // — ModelInstaller fails CLOSED on a bad response — but a device pass run before the
        // upload will red on the model leg for a reason that is not in this code. Probe first:
        //   curl -sI <DASHIE_MODELS>/moonshine-base-en-onnx-2026-08-23.tar.bz2   # expect 200
        //
        // Everything else in this pin is testable now: the three commits below are code-only.
        //   b78a3bf3  AND-gate-during-playback closed NEGATIVE on T cont.8 (comment-only)
        //   e2cacf8e  overlay-dismiss race — the orange bar no longer vanishes mid-sentence
        //   e621083b  .onnx re-export: per-family members, sweep for superseded installs,
        //             narrowed edition-independence gate, new build/runtime parity gate
        //
        // ⚠️ RE-AMENDED 2026-08-23 (the vc184 advance, executed by A): 183 is spent; this build
        // takes 184; versionName holds 1.1.0.
        //
        // 🟢 THE RE-ARM RACE IS CLOSED, and this is the first pin in the sequence that says so on
        // evidence rather than on machinery. Full-placement sweep, John's acceptance:
        //     LEAD-IN (his scenario)   5/5 WOKE   — T s44 cont.14 measured 0/5 on vc182
        //     ≥0.5 s ladder            4/4 WOKE   — no regression
        //     COLD START               WOKE, and verifiably did not back-fill
        //
        // The fix is "prime long, honour short": the back-fill's job is to make microWakeWord
        // arrive WARM after a re-arm, not to reach far into the past. A wide honoured reach only
        // exposed unsettled frames as ghosts — and those ghosts burned MWW's 1.5 s cooldown, which
        // is what blocked the real wake 800 ms later. See MicroWakeWordRearmPolicy for the
        // measurement that shows the "detection" and the wake were different sounds.
        //
        // Carries, since 63cd7ea7:
        //   8bcd0e8e  EI scoring serialised — scoreWindowEndingAt raced the live loop's scratch
        //             buffers (MfeFeatureExtractor + TFLite Interpreter are not thread-safe)
        //   a1785e55  the re-arm fix + the which-audio-was-compared diagnostic that found it
        // ⚠️ ADVANCED 2026-08-26 (vc191, executed by A): 190 is spent; this build takes 191;
        // versionName holds 1.1.0.
        //
        // 🔀 THE HA-ASSIST / CASCADE REPLY-LANE UNIFICATION (row 73). The HA-Assist reply now
        // goes through the SAME TTS router as the cascade, so `voice.ttsProvider` is honoured on
        // both lanes — the Piper defect John found by ear, where 0 attempts + 0 warnings were the
        // signature of a setting never consulted rather than one failing over. This is the
        // unification, not the patch: the cascade had grown a ROUTER while HA-Assist called a
        // LEAF, so every capability added to the router was inherited by one lane and silently
        // missed by the other, and the gap widened by itself.
        //
        // Carries:
        //   · asymmetry 2 — `VoiceStageTiming.reportTts` now fires on the HA-Assist lane (its
        //     TTS latency was invisible to every benchmark), and on the DEVICE branch of both
        //   · asymmetry 3 — the WS-D.1 degraded override, WITHOUT which routing this lane would
        //     have opened a billing hole: the override's owner (DegradedVoiceMode, coordinator-
        //     scoped) does not exist on HA-Assist, so the lane would have had none at all
        //   · the `isCommand` tone gate hoisted ABOVE the engine branch
        //   · #64 — the per-turn line now names the resolved ENGINE (`device:tts.piper`), not
        //     just the stage, which is what makes any of this checkable from a log
        versionCode = 192
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // See gitStamp() above. `-dirty` means the tree had uncommitted changes at build time —
        // for a test build that is the common case, and it is exactly what you want to know when
        // a device behaves unlike the commit you believe is on it.
        buildConfigField("String", "GIT_SHA", "\"$buildGitSha\"")


        // Which EDITION this build is: "dashie" (paid, account, credits) or
        // "chickadee" (free HA edition — no account, no metered service).
        // Defined here so BuildConfig.EDITION exists for ALL flavors; the four
        // chickadee* flavors override it. NOTE this is NOT how implementations
        // are selected — source sets do that at compile time (see sourceSets
        // below). EDITION is for DROP: logging, diagnostics, and the rare
        // branch that legitimately needs to know at runtime.
        buildConfigField("String", "EDITION", "\"dashie\"")

        // Speaker ID (voice enrollment / "learn my voice") — dev-only for
        // now. false here so prod/firetv/amazon/sideload/release compile the
        // stub source set and ship NEITHER the 25 MB ResNet34 asset NOR the
        // ONNX Runtime dependency. Overridden true in local/staging, which
        // also add src/speakeridImpl + src/speakeridAssets (see sourceSets
        // below). Model fetched by scripts/fetch-speakerid-model.sh (not in git).
        buildConfigField("Boolean", "SPEAKER_ID_ENABLED", "false")

        // v3.0: Removed native SDK - now using pure Kotlin MFE + TFLite
        // No more CMake/NDK configuration needed!
        ndk {
            // Now supports both ARM32 and ARM64 since we removed native SDK dependency
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Release channel for the `sideload` flavor — picks which self-update
    // manifest a build follows. Pass -PreleaseChannel=beta to build a beta
    // sideload APK; defaults to the stable channel. (Play/Amazon use their
    // own store tracks for beta vs stable, so this only affects `sideload`.)
    val releaseChannel = (project.findProperty("releaseChannel") as String?) ?: "stable"

    // Product flavors for different environments
    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            dimension = "environment"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            resValue("string", "app_name", "Dashie (Local)")
            // Same as halite - user configures their own HA instance on first run
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            // Mirror halite: enable URL configuration + kiosk features
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            // Local dev proxy - local.dashieapp.com proxies to the local dev machine
            buildConfigField("String", "DASHIE_URL", "\"https://local.dashieapp.com\"")
            // Update backend: "play" (store In-App Updates), "sideload"
            // (self APK install), or "amazon" (Appstore deep-link).
            buildConfigField("String", "UPDATE_BACKEND", "\"play\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
            // Distribution channel exposed to JS via DashieNative.getBuildChannel().
            // Only "sideload" hides the App Reviewer Access link on the login page.
            buildConfigField("String", "BUILD_CHANNEL", "\"local\"")
            // Supabase config - staging database for testing
            buildConfigField("String", "SUPABASE_URL", "\"https://cwglbtosingboqepsmjk.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImN3Z2xidG9zaW5nYm9xZXBzbWprIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTc2NDY4NjYsImV4cCI6MjA3MzIyMjg2Nn0.VCP5DSfAwwZMjtPl33bhsixSiu_lHsM6n42FMJRP3YA\"")
            buildConfigField("Boolean", "SPEAKER_ID_ENABLED", "true")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            resValue("string", "app_name", "Dashie (Staging)")
            // Same as halite - user configures their own HA instance on first run
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            // Mirror halite: enable URL configuration + kiosk features
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            buildConfigField("String", "DASHIE_URL", "\"https://dev.dashieapp.com\"")
            // Update backend — staging is the sideload (self-install) testbed.
            buildConfigField("String", "UPDATE_BACKEND", "\"sideload\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://dashieapp.com/downloads/sideload-latest-staging.json\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"staging\"")
            // Supabase config - staging uses staging database
            buildConfigField("String", "SUPABASE_URL", "\"https://cwglbtosingboqepsmjk.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImN3Z2xidG9zaW5nYm9xZXBzbWprIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTc2NDY4NjYsImV4cCI6MjA3MzIyMjg2Nn0.VCP5DSfAwwZMjtPl33bhsixSiu_lHsM6n42FMJRP3YA\"")
            buildConfigField("Boolean", "SPEAKER_ID_ENABLED", "true")
        }
        create("prod") {
            dimension = "environment"
            // No suffix for prod - uses base applicationId
            resValue("string", "app_name", "Dashie")
            // Same as halite - user configures their own HA instance on first run
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            // Mirror halite: enable URL configuration + kiosk features
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            buildConfigField("String", "DASHIE_URL", "\"https://app.dashieapp.com\"")
            // Update backend — prod ships via Play (In-App Updates). The
            // manifest URL is reused for release-notes metadata only (vc
            // detection comes from Play's API). Same file the amazon
            // flavor uses since both ship the same release line.
            buildConfigField("String", "UPDATE_BACKEND", "\"play\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://dashieapp.com/downloads/amazon-latest.json\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"play\"")
            // Supabase config - production database
            buildConfigField("String", "SUPABASE_URL", "\"https://cseaywxcvnxcsypaqaid.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNzZWF5d3hjdm54Y3N5cGFxYWlkIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTc2MDIxOTEsImV4cCI6MjA3MzE3ODE5MX0.Wnd7XELrtPIDKeTcHVw7dl3awn3BlI0z9ADKPgSfHhA\"")
        }
        create("firetv") {
            dimension = "environment"
            applicationIdSuffix = ".firetv"
            versionNameSuffix = "-firetv"
            resValue("string", "app_name", "Dashie (Fire TV)")
            buildConfigField("String", "BASE_URL", "\"https://dashieapp.com/login\"")
            buildConfigField("String", "ENVIRONMENT", "\"firetv\"")
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "false")
            buildConfigField("int", "API_PORT", "2323")
            buildConfigField("String", "DASHIE_URL", "\"https://dashieapp.com\"")
            // Update backend — firetv uses Play (no-op on non-Play devices).
            buildConfigField("String", "UPDATE_BACKEND", "\"play\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"play\"")
            // Empty Supabase config - not used in firetv (only Halite uses VoiceLicenseManager)
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
        }
        create("amazon") {
            dimension = "environment"
            // No suffix — same package as the Amazon Appstore listing so the
            // amzn:// update deep link targets the real Dashie listing.
            resValue("string", "app_name", "Dashie")
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            buildConfigField("String", "DASHIE_URL", "\"https://app.dashieapp.com\"")
            // Update backend — Amazon Appstore distribution: detect via the
            // Dashie-hosted manifest, prompt, deep-link to the Appstore.
            buildConfigField("String", "UPDATE_BACKEND", "\"amazon\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://dashieapp.com/downloads/amazon-latest.json\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"amazon\"")
            // Supabase config - production database
            buildConfigField("String", "SUPABASE_URL", "\"https://cseaywxcvnxcsypaqaid.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNzZWF5d3hjdm54Y3N5cGFxYWlkIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTc2MDIxOTEsImV4cCI6MjA3MzE3ODE5MX0.Wnd7XELrtPIDKeTcHVw7dl3awn3BlI0z9ADKPgSfHhA\"")
        }
        create("sideload") {
            dimension = "environment"
            applicationIdSuffix = ".sideload"
            versionNameSuffix = "-sideload"
            // Prod server + sideload self-update — a prod build that updates
            // itself via APK (Fire devices, pre-Amazon testing). The release
            // channel (-PreleaseChannel=beta|stable) picks which self-update
            // manifest it follows; beta and stable share the same package so
            // a device moves between them just by installing the other APK.
            val isBeta = releaseChannel == "beta"
            resValue("string", "app_name", "Dashie")
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            buildConfigField("String", "DASHIE_URL", "\"https://app.dashieapp.com\"")
            buildConfigField("String", "UPDATE_BACKEND", "\"sideload\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL",
                if (isBeta) "\"https://dashieapp.com/downloads/sideload-beta.json\""
                else "\"https://dashieapp.com/downloads/sideload-latest.json\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"sideload\"")
            // Supabase config - production database
            buildConfigField("String", "SUPABASE_URL", "\"https://cseaywxcvnxcsypaqaid.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNzZWF5d3hjdm54Y3N5cGFxYWlkIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTc2MDIxOTEsImV4cCI6MjA3MzE3ODE5MX0.Wnd7XELrtPIDKeTcHVw7dl3awn3BlI0z9ADKPgSfHhA\"")
        }

        // ── Chickadee edition ────────────────────────────────────────────────
        // Chickadee is the free, source-published HA edition: no account, no
        // credits, no metered service (BYOK or HA's own engines). Same codebase,
        // different edition — see dashieapp_staging/.reference/build-plans/
        // 20260731_CHICKADEE_INDEPENDENT_EDITION.md and 20260801_PHASE2B_*.md.
        //
        // PHASE 2b = SCAFFOLDING ONLY. These build and behave IDENTICALLY to the
        // Dashie flavors today: every file is still in main/ and src/chickadeeStub/
        // is empty. The commercial code moves out in 2c–2e.
        //
        // ⚠️ applicationId (a different BASE), not applicationIdSuffix — Chickadee
        // is a separate listing under its own id base. It is
        // IMMUTABLE once a store listing exists. (Product decision 2026-08-21: the
        // chickadee domain registration is being dropped; the reversed-domain
        // applicationIds deliberately stay — inert strings with no dependency on
        // any registration, and renaming would churn the frozen branch.) `namespace` deliberately stays
        // com.dashieapp.Dashie: it is module-level, not per-flavor, and all 712
        // Kotlin files declare packages under it. Disclosed in PROVENANCE rather
        // than "fixed" with a 712-file rename.
        //
        // Broadcast actions keep the com.dashieapp.Dashie.* literals. They are
        // identifiers, not addresses: every custom receiver is exported=false and
        // all 128 setPackage() calls use context.packageName, so a co-installed
        // Dashie + Chickadee stay isolated. (Verified 2026-08-01.)
        //
        // NOT here yet, deliberately: sttEngineFlavors membership (a behaviour
        // change + 242 MB of gitignored assets — rides the model-choice decision)
        // and a Chickadee release signing config (needed before the first release,
        // since a different applicationId means its own Play app-signing key).
        create("chickadeeDev") {
            dimension = "environment"
            applicationId = "org.getchickadee.chickadee.dev"
            versionNameSuffix = "-chickadee-dev"
            resValue("string", "app_name", "Chickadee (Dev)")
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            // Account-free edition: no Dashie web root (O, extending John's 2026-08-03 credential
            // ruling to this field). Blank rather than omitted so BuildConfig still compiles for
            // shared main/ code. The main/ consumers are either gated on `showsDashboard` (never
            // true here) or made blank-SAFE — see MainBroadcastManager.dashieWebRootOrDrop, which
            // logs a loud DROP instead of building "/login" out of an empty root.
            buildConfigField("String", "DASHIE_URL", "\"\"")
            buildConfigField("String", "UPDATE_BACKEND", "\"sideload\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"chickadee-dev\"")
            buildConfigField("String", "EDITION", "\"chickadee\"")
            // Account-free edition: no Supabase project, no anon key (John, 2026-08-02).
            // Blank rather than omitted so BuildConfig still compiles for shared main/ code;
            // the two main/ bridge readers are ALSO gated on EditionSeams.hasAccounts, because
            // blanking removes the value while the gate removes the counter that hands it out.
            // Same pattern the firetv flavor already uses.
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("Boolean", "SPEAKER_ID_ENABLED", "false")
        }
        create("chickadeeProd") {
            dimension = "environment"
            applicationId = "org.getchickadee.chickadee"
            versionNameSuffix = "-chickadee"
            resValue("string", "app_name", "Chickadee")
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            // Account-free edition — blank, same rationale as chickadeeDev above.
            buildConfigField("String", "DASHIE_URL", "\"\"")
            buildConfigField("String", "UPDATE_BACKEND", "\"play\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"play\"")
            buildConfigField("String", "EDITION", "\"chickadee\"")
            // Account-free edition: no Supabase project, no anon key (John, 2026-08-02).
            // Blank rather than omitted so BuildConfig still compiles for shared main/ code;
            // the two main/ bridge readers are ALSO gated on EditionSeams.hasAccounts, because
            // blanking removes the value while the gate removes the counter that hands it out.
            // Same pattern the firetv flavor already uses.
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("Boolean", "SPEAKER_ID_ENABLED", "false")
        }
        create("chickadeeAmazon") {
            dimension = "environment"
            // Same applicationId as chickadeeProd on purpose — different store,
            // same app identity. Mirrors Dashie's prod/amazon pair. One flavor
            // per device still applies (memory: sendspin_flavor_collision).
            applicationId = "org.getchickadee.chickadee"
            versionNameSuffix = "-chickadee-amazon"
            resValue("string", "app_name", "Chickadee")
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            // Account-free edition — blank, same rationale as chickadeeDev above.
            buildConfigField("String", "DASHIE_URL", "\"\"")
            buildConfigField("String", "UPDATE_BACKEND", "\"amazon\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"amazon\"")
            buildConfigField("String", "EDITION", "\"chickadee\"")
            // Account-free edition: no Supabase project, no anon key (John, 2026-08-02).
            // Blank rather than omitted so BuildConfig still compiles for shared main/ code;
            // the two main/ bridge readers are ALSO gated on EditionSeams.hasAccounts, because
            // blanking removes the value while the gate removes the counter that hands it out.
            // Same pattern the firetv flavor already uses.
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("Boolean", "SPEAKER_ID_ENABLED", "false")
        }
        create("chickadeeSideload") {
            dimension = "environment"
            applicationId = "org.getchickadee.chickadee.sideload"
            versionNameSuffix = "-chickadee-sideload"
            resValue("string", "app_name", "Chickadee")
            buildConfigField("String", "BASE_URL", "\"http://homeassistant.local:8123\"")
            buildConfigField("String", "ENVIRONMENT", "\"halite\"")
            buildConfigField("Boolean", "ALLOW_URL_CONFIG", "true")
            buildConfigField("int", "API_PORT", "2323")
            // Account-free edition — blank, same rationale as chickadeeDev above.
            buildConfigField("String", "DASHIE_URL", "\"\"")
            buildConfigField("String", "UPDATE_BACKEND", "\"sideload\"")
            // Chickadee's own self-update manifest — NOT dashieapp.com's. Empty:
            // no site serves one (2b ships no release channel, and the brand's
            // domain registration was dropped 2026-08-21); an empty URL disables
            // the self-updater.
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
            buildConfigField("String", "BUILD_CHANNEL", "\"sideload\"")
            buildConfigField("String", "EDITION", "\"chickadee\"")
            // Account-free edition: no Supabase project, no anon key (John, 2026-08-02).
            // Blank rather than omitted so BuildConfig still compiles for shared main/ code;
            // the two main/ bridge readers are ALSO gated on EditionSeams.hasAccounts, because
            // blanking removes the value while the gate removes the counter that hands it out.
            // Same pattern the firetv flavor already uses.
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("Boolean", "SPEAKER_ID_ENABLED", "false")
        }
    }

    signingConfigs {
        // Play Store upload key — credentials live in ~/.gradle/gradle.properties (never committed).
        // Falls back to the debug keystore so contributors without the upload key can still produce
        // (non-publishable) release builds locally.
        create("release") {
            val uploadKeystorePath = (project.findProperty("DASHIE_UPLOAD_KEYSTORE_PATH") as String?)
            val uploadKeystoreFile = uploadKeystorePath?.let { file(it) }
            if (uploadKeystoreFile != null && uploadKeystoreFile.exists()) {
                storeFile = uploadKeystoreFile
                storePassword = project.findProperty("DASHIE_UPLOAD_KEYSTORE_PASSWORD") as String?
                keyAlias = project.findProperty("DASHIE_UPLOAD_KEY_ALIAS") as String?
                keyPassword = project.findProperty("DASHIE_UPLOAD_KEY_PASSWORD") as String?
            } else {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // Speaker-ID flavor split: local/staging compile the real ONNX-backed
    // implementation and bundle the model asset; every other flavor compiles
    // a same-API stub, so prod APKs carry no model, no ONNX natives, no code
    // path. The .onnx asset is NOT in git — run scripts/fetch-speakerid-model.sh
    // (build-install.sh preflights it); builds succeed without it (runtime
    // feature-detects the asset).
    sourceSets {
        val speakerIdFlavors = setOf("local", "staging")
        // DERIVED from the declared flavors, never restated. This was a
        // hardcoded list; a flavor added to productFlavors but forgotten here
        // got NEITHER speakeridImpl nor speakeridStub and failed with a
        // missing-symbol error pointing nowhere near the cause. Deriving it
        // makes that class of mistake impossible (CLAUDE.md seam rule: share
        // the first copy rather than hand-mirroring it).
        val allFlavors = productFlavors.map { it.name }
        for (flavor in allFlavors) {
            getByName(flavor).java.srcDir(
                if (flavor in speakerIdFlavors) "src/speakeridImpl/java"
                else "src/speakeridStub/java"
            )
        }
        for (flavor in speakerIdFlavors) {
            getByName(flavor).assets.srcDir("src/speakeridAssets/assets")
        }

        // Edition split (Chickadee, phase 2b). Same impl/stub shape as
        // speaker-ID above: dashie flavors compile the commercial edition
        // sources, chickadee flavors compile same-API no-ops.
        //
        // BOTH DIRECTORIES ARE EMPTY IN 2b — this only establishes the seam so
        // 2c can move code into it one interface at a time with every commit
        // still compiling both editions.
        val chickadeeFlavors = allFlavors.filter { it.startsWith("chickadee") }.toSet()
        for (flavor in allFlavors) {
            getByName(flavor).java.srcDir(
                if (flavor in chickadeeFlavors) "src/chickadeeStub/java"
                else "src/dashie/java"
            )
            // Edition RESOURCES, same axis. Commercial LAYOUTS were left in main/res when 2e/2f
            // moved their Kotlin to src/dashie/java — so the paywall dialogs still shipped
            // inside the Chickadee APK, inert but present, with copy like "Subscribe to Dashie"
            // sitting in resources.arsc. "No Dashie surface in the published tree" is a claim
            // about the ARTIFACT, and a layout is surface.
            //
            // Dashie-only: there is no chickadee/res stub counterpart and there must not be —
            // the point is that these layouts do not EXIST in that edition, not that they exist
            // and render blank. Nothing in chickadeeStub inflates them.
            if (flavor !in chickadeeFlavors) {
                getByName(flavor).res.srcDir("src/dashie/res")
            }

            // UNIT TESTS, same edition axis — established 2026-08-06 (C3's rider).
            //
            // Why this is needed the moment any TESTED class moves into src/dashie: `src/test/`
            // is shared by every variant, but the edition dirs above are attached to each
            // flavor's MAIN source set. So a test that names a Dashie-only class compiles fine
            // on Dashie variants and fails to compile on chickadee* ones — the whole
            // chickadeeDev unit-test variant goes red, which is how a mechanical extraction
            // turns into "delete the test to get green".
            //
            // 🔴 That deletion is the failure mode this exists to prevent: the first class up
            // (`SupabasePhotoSource`) is covered by a REGRESSION test for a shipped crash fix
            // (SocketTimeoutException escaping getNextPhoto, 2026-05-28). Dropping it to satisfy
            // the compiler would silently un-cover a real crash.
            //
            // Derived from the declared flavors for the same reason as the loop above — a new
            // flavor must not silently miss its test source set.
            val testSourceSet = "test" + flavor.replaceFirstChar { it.uppercase() }
            findByName(testSourceSet)?.java?.srcDir(
                if (flavor in chickadeeFlavors) "src/testChickadeeStub/java"
                else "src/testDashie/java"
            )
        }

        // Chickadee BRAND resources — launcher icon, splash, brand colour.
        // ONE shared dir for all four Chickadee flavors rather than src/chickadeeDev/res +
        // three siblings: the branding is per-BRAND, not per-channel, and four copies of the
        // same mipmap set is the mirror shape standing rule 1 exists to stop.
        // Dashie flavors get nothing here and keep main/'s resources untouched.
        for (flavor in chickadeeFlavors) {
            getByName(flavor).res.srcDir("src/chickadeeBrand/res")
            // Chickadee ASSETS, same per-brand axis. Currently one file: the shell bundle built
            // with the account/session bridge stubbed out (John, 2026-08-02 — "no shell bridge in
            // chickadee"). It sits at the SAME asset path as main's, and a flavor source set wins
            // over main, so each artifact carries exactly one kiosk-shell.bundle.js.
            //
            // 🔴 This is an OVERRIDE mechanism, not a removal one — asset merging adds and
            // replaces, it cannot subtract. It works here because the file exists in both
            // editions and we are substituting it. It cannot remove the Dashie mark files that
            // live in main/assets; those would have to MOVE out of main/, which is a separate
            // change and deliberately not folded in here.
            getByName(flavor).assets.srcDir("src/chickadeeBrand/assets")
        }

        // sherpa-onnx on-device STT (dashieapp_staging build-plan
        // 20260728_SHERPA_STT_INTEGRATION_PLAN.md): JNI lib + dev models are
        // flavor-gated assets, NOT in git — run scripts/fetch-stt-models.sh.
        // No impl/stub java split needed: the providers feature-detect the
        // lib+assets at runtime, so all flavors compile the same code and
        // prod APKs simply carry no engine, no models.
        // 🔴 SPLIT 2026-08-04 (John's Shape A ruling): the ENGINE ships everywhere, the MODELS do
        // not. Previously both were gated to local+staging, which is why on-device STT was not
        // merely heavy in shipping builds — it was ABSENT from them, with no way to obtain it.
        //
        // Models stay dev-only because they are the ~219 MB; a user now downloads the one family
        // they want on demand (SttModelInstaller). John: "+51 MB is fine, just not +200."
        //
        // ⭐ MEASURED, and it is cheaper than the ruling assumed: this costs **22.5 MB**, not 51.
        // `libsherpa-onnx-jni.so` is the static-link build (see scripts/fetch-stt-models.sh) with
        // ONNX Runtime linked IN — verified: the binary has no `libonnxruntime.so` DT_NEEDED. The
        // separate 30 MB of `libonnxruntime.so` in dev APKs belongs to the Microsoft ORT AAR that
        // SPEAKER-ID uses, and speaker-ID is already dev-gated, so none of it ships here.
        //
        // ✅ BOTH arm64-v8a AND armeabi-v7a, since 2026-08-25 (a7c21608).
        //
        // 🔴 This comment previously read "arm64-v8a ONLY — upstream's static build has no
        // armeabi-v7a slice." That was FALSE and it was load-bearing: the tarball carries four
        // slices, and `scripts/fetch-stt-models.sh` was simply extracting one of them. The
        // 32-bit slice is a genuine peer (ELF 32-bit ARM EABI5, 16,110,348 B, ORT statically
        // linked — no libonnxruntime.so DT_NEEDED, same as arm64).
        //
        // Why the wrong note cost real money: EVERY device the "cheap HA dashboard" market
        // converts is 32-bit. Measured 2026-08-25 — Echo Show 5 (LineageOS) and the onn Full HD
        // stick both report `armeabi-v7a,armeabi` with NO 64-bit slice, so on-device STT could
        // not load on either, while the 48 MB model downloaded happily and reported "installed".
        // The failure mode was NOT the honest absence this note claimed: SherpaEngineLoader
        // caught the UnsatisfiedLinkError, but the model UI upstream of it still said installed.
        //
        // 📌 The durable lesson: `abiFilters` (line ~181) has listed both ABIs all along. The
        // gap was in the FETCH script, one file away — so a comment describing "what ships" was
        // written from the wrong artifact. Check what the extractor produces, not what the
        // build config permits.
        for (flavor in productFlavors.names) {
            getByName(flavor).jniLibs.srcDir("src/sttEngine/jniLibs")
            // 🔴 The silero VAD ships WITH THE ENGINE, not with the models (2026-08-04, after the
            // download lane was driven end to end on the Fire). It is 629 KB — 3% of the engine's
            // 22.5 MB — and without it sherpa falls back to the ENERGY endpointer, which needs
            // ~1200 ms of silence to close a turn where the neural one needs ~500 ms.
            //
            // That fallback is graceful and therefore invisible: a user who downloads a model gets
            // working speech that just feels sluggish, with nothing saying why. Leaving it in the
            // model source set would have degraded EXACTLY the lane the on-demand feature creates,
            // on every shipping flavor, for 0.6 MB. By John's own framing ("the engine ships
            // everywhere, the ~219 MB of models do not") the VAD is engine.
            getByName(flavor).assets.srcDir("src/sttVad/assets")
        }
        // 🔴 STAGING DROPPED 2026-08-04 (John: "delete & drop, with the check"). `local` is now the
        // ONLY flavor with bundled models; every other flavor — staging included — downloads on
        // demand.
        //
        // Why staging was wrong to bundle: the gating dates from the original sherpa scaffolding
        // (37c92462) and its recorded reason was "dev-only". Staging is NOT dev-only —
        // publish-sideload.sh runs assembleStagingDebug and publishes it to
        // dashieapp.com/downloads as the beta self-update release. So the ONE channel most likely
        // to surface a download problem was the one channel that could never reach the download
        // lane: `installable()` filters bundled families, correctly, so the row had no surface
        // there at all. The component/outcome trap at release level — a feature "verified" on a
        // channel that cannot execute it.
        //
        // ✅ THE UPGRADE PATH IS PROVEN, not assumed (s130, on the Fire, via the s129 TEST_INJECT
        // hook). An existing staging user updating into a bundle-less APK lands in exactly the
        // state this change creates — a selection pointing at a model that is no longer present —
        // and that state is loud and recoverable: nothing crashes, `[sherpa_moonshine_tiny]
        // available=false`, `W HaLocalStt: DROP: no local STT rung is available ... falling back
        // to the HA Assist pipeline for STT`, the row reads "download required (~42 MB)", and ONE
        // tap from there opens the download card. Voice keeps working via HA Assist meanwhile.
        //
        // Cost: −229 MB of the 374.9 MB staging artifact (61%), of which 43.6 MB was zipf20m,
        // deleted separately as dead payload.
        val sttModelFlavors = setOf("local")
        for (flavor in sttModelFlavors) {
            getByName(flavor).assets.srcDir("src/sttEngine/assets")
        }
    }

    // int8 model weights are already dense — deflate wastes build time and
    // forces decompress-on-load; stored-uncompressed lets ORT/sherpa stream
    // them straight from the APK (covers the speaker-ID .onnx too).
    androidResources {
        noCompress += listOf("onnx", "ort")
    }

    buildTypes {
        debug {
            // Feature flags for in-development features
            buildConfigField("Boolean", "MUSIC_PLAYER_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Feature flags
            buildConfigField("Boolean", "MUSIC_PLAYER_ENABLED", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time (used by the voice agenda/calendar/sports renderers) is
        // API 26+. minSdk is 23, so without desugaring those cards throw
        // NoClassDefFoundError on API 23–25 devices (e.g. Fire OS 5 tablets).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // v3.0: Removed native SDK - no more CMake/NDK configuration needed!
    // Wake word detection now uses pure Kotlin MFE + TFLite

    // Rename APK for each build variant (includes flavor name)
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "Dashie-${variant.flavorName}-${variant.versionName}.apk"
        }
    }

    // gradle-play-publisher per-flavor overrides — only the prod flavor
    // publishes to Play. The others distribute through their own channels
    // (amazon → Amazon Appstore, sideload → dashieapp.com, staging /
    // local / firetv → internal ADB) and would error trying to find a
    // matching Play app entry, so disable them explicitly.
    playConfigs {
        register("amazon") { enabled.set(false) }
        register("sideload") { enabled.set(false) }
        register("staging") { enabled.set(false) }
        register("local") { enabled.set(false) }
        register("firetv") { enabled.set(false) }
    }

}

// ──────────────────────────────────────────────────────────────────
// gradle-play-publisher
// ──────────────────────────────────────────────────────────────────
//
// Automates uploads to Google Play Console. Replaces the manual
// "build AAB → log into Play Console → upload → fill in release notes
// → roll out" cycle with a single Gradle task.
//
// One-time setup (see .reference/PLAY_PUBLISHER_SETUP.md):
//   1. Create a Google Cloud service account
//   2. Download its JSON key
//   3. Save the key path in ~/.gradle/gradle.properties as
//      DASHIE_PLAY_PUBLISHER_KEY (NOT committed — it's a secret)
//   4. Add the service account email to Play Console with the
//      "Release Manager" role
//
// Usage:
//   ./gradlew publishProdReleaseBundle           # upload to default track
//   ./gradlew publishProdReleaseBundle -Ptrack=internal    # internal testing
//   ./gradlew publishProdReleaseBundle -Ptrack=production  # production
//
// Only the prod flavor publishes — amazon / sideload / staging / local
// are explicitly disabled below since they distribute through other
// channels (Amazon Appstore, dashieapp.com, ADB).
play {
    // Service account JSON — points to a file outside the repo (path
    // configured per-developer in ~/.gradle/gradle.properties). If the
    // property isn't set the plugin is effectively disabled (the
    // publishing tasks will fail with a clear "no credentials" error,
    // ordinary builds remain unaffected).
    val keyPath = (project.findProperty("DASHIE_PLAY_PUBLISHER_KEY") as String?)
    if (!keyPath.isNullOrBlank() && file(keyPath).exists()) {
        serviceAccountCredentials.set(file(keyPath))
    }

    // Default to Closed Testing track ("alpha" in Play API terms). Can
    // override via `-Ptrack=internal|production|...` on the CLI.
    val track = (project.findProperty("track") as String?) ?: "alpha"
    this.track.set(track)

    // Use the AAB upload path (not APK). Matches what we've been doing
    // manually — Play prefers AABs for new uploads.
    defaultToAppBundles.set(true)

    // Roll out at 100% of testers/users immediately. Override with
    // `-PuserFraction=0.1` for a staged rollout (e.g. 10% to production).
    val fraction = (project.findProperty("userFraction") as String?)?.toDoubleOrNull()
    if (fraction != null) {
        userFraction.set(fraction)
    }

    // Release notes — picked up from
    //   app/src/<flavor>/play/release-notes/<locale>/<track>.txt
    // if those files exist. Otherwise no release notes are sent and
    // Play Console keeps whatever's already there (or empty).
}

dependencies {
    // Backports java.time (and other Java 8+ APIs) to minSdk 23 — required by
    // isCoreLibraryDesugaringEnabled above so the voice renderers don't crash
    // on API 23–25.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-process:2.6.1")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("com.google.android.material:material:1.11.0")  // For DayNight theme support
    // play-services-auth REMOVED 2026-08-05 (A, s140). It had ZERO references in any
    // .kt/.java/.xml/manifest in any source set — Google sign-in on this app is done by the
    // WEBAPP, not natively — so it was linking a proprietary library into both editions for
    // nothing. Measured: −199 KB, clean-packaged on both sides (an incremental rebuild reads
    // LARGER, per the zipflinger orphaned-bytes finding; APK size after an incremental package
    // is not a measurement). Removing it also drops one of the three proprietary deps from the
    // AGPL-3.0 published tree (#25) — though note it was never the licence tension: `paho`
    // (EPL-1.0) is, and Chickadee genuinely needs it. See A-status s139.

    // Play In-App Updates — app-initiated update checks + flexible download/install.
    // Used by DashieUpdateManager so wall-mounted kiosks can self-update on the
    // refresh-scheduler's sleep window instead of relying on passive Play auto-update.
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Baseline Profile support - enables ahead-of-time compilation of hot paths
    // ProfileInstaller compiles baseline profiles on first launch, reducing cold start time
    implementation(libs.androidx.profileinstaller)

    // Voice Assistant - Wake Word Detection (Kotlin MFE + TensorFlow Lite)
    // v3.0: Now using pure Kotlin MFE feature extraction + TFLite interpreter
    // No more native C++ SDK - works on both ARM32 and ARM64!
    implementation("org.tensorflow:tensorflow-lite:2.11.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.3")

    // Speaker embeddings (WeSpeaker ResNet34) — local/staging ONLY, matching
    // the speakeridImpl source-set split above. Do not promote to a plain
    // implementation() without a plan for prod APK size (+~15 MB natives).
    "localImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    "stagingImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // microWakeWord - Native audio feature extraction (64-bit only)
    // Used by MicroWakeWordDetector as alternative wake word engine
    implementation(project(":microfrontend"))

    // Voice Assistant - WebRTC VAD for real-time silence detection (only webrtc module, excludes ONNX-based silero/yamnet)
    implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")  // >=2.0.10 ships 16 KB-aligned libvad_jni.so

    // Voice Assistant - OkHttp for Deepgram streaming WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // bzip2 + tar, for on-demand STT model install (John's Shape A ruling, 2026-08-04).
    //
    // 🔴 Needed because upstream publishes the sherpa models ONLY as `.tar.bz2` — verified against
    // the k2-fsa release assets: `.tar.gz` and `.zip` both 404 — and Java/Android has no built-in
    // bzip2. The alternatives were to mirror or re-pack the models on a host WE control, and both
    // reintroduce a Dashie-controlled dependency into the delivery path of the account-free
    // edition. That is the independence constraint John already ruled on for the wake-word
    // manifest, so ~1 MB of decompressor is the cheap side of that trade — next to the 51 MB of
    // native libs already accepted.
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Edge Impulse - FFT library for MFE feature extraction
    implementation("org.apache.commons:commons-math3:3.6.1")

    // CameraX for visual motion detection
    val cameraxVersion = "1.4.0"  // >=1.4.0 ships 16 KB-aligned libimage_processing_util_jni.so
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")  // For PreviewView in motion wake settings

    // ML Kit Face Detection for face-based wake mode (bundled model, works offline on Fire tablets)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // RecyclerView for WiFi setup list
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // NanoHTTPD for Fully Kiosk-compatible REST API (Dashie Lite)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Eclipse Paho MQTT client for Home Assistant MQTT integration
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // RootEncoder + RTSP Server for camera streaming (hardware-accelerated H.264)
    // Using 2.6.1 for compatibility with RTSP-Server 1.3.6 (flip implemented manually)
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.1")

    // Coil for album artwork loading in music player
    implementation("io.coil-kt:coil:2.5.0")

    // Sendspin protocol — synchronized multi-room audio for Music Assistant
    // Ktor WebSocket client for Sendspin protocol transport
    implementation("io.ktor:ktor-client-okhttp:3.1.1")
    implementation("io.ktor:ktor-client-websockets:3.1.1")
    // kotlinx-serialization for Sendspin JSON protocol messages
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.github.pedroSG94:RTSP-Server:1.3.6")

    // ZXing for QR code generation (license activation)
    implementation("com.google.zxing:core:3.5.2")

    // AndroidX ExifInterface - more reliable EXIF reading than android.media.ExifInterface
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // metadata-extractor - robust EXIF/GPS reading that handles more formats than Android's ExifInterface
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // Media3 ExoPlayer for native RTSP camera playback (hardware-accelerated decoding)
    // Used by dashie-camera-card to overlay native video on WebView for better performance
    val media3Version = "1.10.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    testImplementation(libs.junit)
    // JVM unit tests (Robolectric for Android stubs, MockWebServer for okhttp).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ─────────────────────────────────────────────────────────────────────────
// Settings-wiring gate (cross-repo)
// ─────────────────────────────────────────────────────────────────────────
// Runs the dashieapp_staging static check that catches broadcast-synced
// setting drift — e.g. the June 2026 displaySize bug, where a Kotlin
// ACTION_APPLY_* push (in MainBroadcastManager.kt) had no matching
// SETTINGS_KEY_MAP entry on the JS side, so the value never left the device.
// The Kotlin half of that JS↔Kotlin contract ships in THIS APK, so we
// validate before building any shippable (non-`local`) variant. The web half
// runs the same check in dashieapp_staging/deploy-to-dev.sh.
// See dashieapp_staging/js/data/settings/SETTINGS.md → "ACTION_APPLY_* broadcast".
//
// Resilience: the check is skipped (build proceeds) when the staging repo
// isn't present — so a machine without it checked out, or future CI, won't
// break. On the dev Mac (both repos present + node on PATH) it runs and a
// drift fails the build with a clear message. `local` builds are intentionally
// NOT gated so day-to-day iteration stays fast.
val checkSettingsWiring = tasks.register<Exec>("checkSettingsWiring") {
    group = "verification"
    description = "Validate JS↔Kotlin settings wiring (dashieapp_staging static check)"
    val script = File(
        System.getProperty("user.home"),
        "projects/dashieapp_staging/scripts/check-settings-wiring.mjs"
    )
    onlyIf { script.exists() }
    workingDir = rootDir
    commandLine("node", script.absolutePath)
}

tasks.matching {
    it.name.matches(Regex("(assemble|bundle)(Staging|Prod|Firetv|Amazon|Sideload).*"))
}.configureEach {
    dependsOn(checkSettingsWiring)
}