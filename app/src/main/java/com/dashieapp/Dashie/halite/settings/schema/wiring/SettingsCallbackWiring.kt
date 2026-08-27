package com.dashieapp.Dashie.halite.settings.schema.wiring

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.pages.showAddCreditsFlow
import com.dashieapp.Dashie.halite.preferences.AccountPreferences
import com.dashieapp.Dashie.halite.preferences.CameraPreferences
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import com.dashieapp.Dashie.halite.preferences.PowerPreferences
import com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.openWakeCalibrationDialog
import com.dashieapp.Dashie.halite.settings.pages.togglePowerSwitch
import com.dashieapp.Dashie.halite.settings.pages.showVideoFeedEditor
import com.dashieapp.Dashie.halite.settings.showClearCacheConfirmation
import com.dashieapp.Dashie.halite.settings.showRelinquishDeviceOwnerConfirmation
import com.dashieapp.Dashie.halite.settings.pages.checkMaTokenRole
import com.dashieapp.Dashie.halite.settings.schema.SettingsCallbackRegistry
import com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment
import com.dashieapp.Dashie.halite.sidebar.BrightnessUI
import com.dashieapp.Dashie.halite.sidebar.SettingsCallbacks
import androidx.lifecycle.lifecycleScope

/**
 * Callback and interceptor registrations extracted from SettingsSchemaWiring.
 *
 * Contains:
 * - registerCallbacks: all registry.register() side-effect callbacks
 * - registerInterceptors: toggle interceptors (voice license, music login)
 * - checkPendingMusicEnable: resume hook for MA login flow
 */
object SettingsCallbackWiring {

    /** Pending callback for music enable — called from SettingsActivity.onResume after MA login. */
    @Volatile
    var pendingMusicEnableCallback: (() -> Unit)? = null

    /**
     * Matching cancel callback for the music enable flow. Fires when MA login
     * fails or the user backs out of MaLoginActivity without completing. Reverts
     * the Enable Music Player Switch visual state.
     */
    @Volatile
    var pendingMusicEnableCancel: (() -> Unit)? = null

    fun registerCallbacks(registry: SettingsCallbackRegistry, context: Context, prefs: HalitePreferences, voicePrefs: VoicePreferences, cameraPrefs: CameraPreferences, powerPrefs: PowerPreferences, videoFeedPrefs: VideoFeedPreferences) {
        registry.register("notifyZoomChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_APPLY_ZOOM").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyWidgetZoomChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_APPLY_WIDGET_ZOOM").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyFontSizeChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_APPLY_WIDGET_FONT_SIZE").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyDisplaySizeChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_APPLY_DISPLAY_SIZE").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyLayoutModeChanged") {
            val mode = prefs.display.layoutMode
            val wasKiosk = AccountPreferences(context).forceKioskMode
            if (mode == "kiosk") {
                // Entering kiosk: set flag and switch to kiosk shell
                AccountPreferences(context).forceKioskMode = true
                context.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_KIOSK").apply {
                        setPackage(context.packageName)
                    }
                )
            } else {
                // Non-kiosk: clear flag
                val accountPrefs = AccountPreferences(context)
                accountPrefs.forceKioskMode = false
                if (wasKiosk) {
                    // D.52 — first time exiting kiosk to widgets specifically:
                    // auto-pin the sidebar (optimal default for the widgets
                    // canvas). One-shot via firstKioskToWidgetsAutoPinDone —
                    // subsequent layout toggles respect whatever the user set.
                    if (mode == "widgets" && !accountPrefs.firstKioskToWidgetsAutoPinDone) {
                        prefs.display.dashMenuPinned = true
                        accountPrefs.firstKioskToWidgetsAutoPinDone = true
                    }
                    // Exiting kiosk: switch back to full Dashie URL first
                    context.sendBroadcast(
                        android.content.Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_FULL").apply {
                            setPackage(context.packageName)
                        }
                    )
                } else {
                    // Non-kiosk to non-kiosk: just notify JS of mode change
                    context.sendBroadcast(
                        android.content.Intent("com.dashieapp.Dashie.ACTION_LAYOUT_MODE_CHANGED").apply {
                            setPackage(context.packageName)
                        }
                    )
                }
            }
        }

        registry.register("notifyOrientationLockChanged") {
            // OrientationController re-reads the pref + applies requestedOrientation.
            // MainBroadcastManager also dispatches native-settings-changed(display)
            // so the JS device-settings-sync picks the new value up.
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_ORIENTATION_LOCK_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyScreenOffChanged") {
            // Screen-off behavior change takes effect on next sleep cycle, no immediate action needed
        }

        registry.register("openMotionCalibration") {
            val activity = context as? SettingsActivity ?: return@register
            activity.openWakeCalibrationDialog("camera")
        }

        registry.register("openFaceCalibration") {
            val activity = context as? SettingsActivity ?: return@register
            activity.openWakeCalibrationDialog("face")
        }

        registry.register("notifySidebarIconSizeChanged") {
            // Broadcast so NativeSidebarController can re-apply icon sizes
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_SIDEBAR_ICON_SIZE").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Theme family changed — notify WebView to apply new theme
        registry.register("notifyThemeFamilyChanged") {
            val family = context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                .getString("theme_family", "default") ?: "default"
            // Dispatch to WebView to update settingsStore and apply theme CSS
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_THEME_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("themeFamily", family)
                }
            )
        }

        // Dark mode changed — notify WebView and recreate SettingsActivity for theme
        registry.register("notifyDarkModeChanged") {
            val isDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context) ?: false
            // Notify WebView of dark mode change
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_DARK_MODE_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("darkMode", isDark)
                }
            )
            // Recreate SettingsActivity to apply new night mode theme
            (context as? SettingsActivity)?.recreate()
        }

        // Animations enabled changed — notify WebView to persist + apply overlay
        registry.register("notifyAnimationsEnabledChanged") {
            val enabled = context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                .getBoolean("animations_enabled", false)
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_ANIMATIONS_ENABLED_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("animationsEnabled", enabled)
                }
            )
        }

        // Animation level changed — notify WebView to persist + reapply overlay
        registry.register("notifyAnimationLevelChanged") {
            val level = context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                .getString("animation_level", "high") ?: "high"
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_ANIMATION_LEVEL_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("animationLevel", level)
                }
            )
        }

        // Photo callbacks — notify WebView and screensaver of photo settings changes
        registry.register("notifyPhotoSourceChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_PHOTO_SOURCE_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
            // Auto-launch Immich login when selected without credentials
            val activity = context as? com.dashieapp.Dashie.halite.settings.SettingsActivity
            if (activity != null
                && prefs.screensaver.photoSourceType == com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences.PHOTO_SOURCE_IMMICH
                && !prefs.screensaver.hasImmichConfig
            ) {
                SettingsDialogWiring.launchImmichLogin(activity, prefs)
            }
        }
        registry.register("notifyPhotoSettingsChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_PHOTO_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // GPU hardware-layer toggle — the layer type is set during
        // WebView init in MainWebViewSetup, so any change requires
        // a restart to take effect.
        registry.register("notifyGpuHardwareLayerChanged") {
            if (context is android.app.Activity) {
                val activity = context as android.app.Activity
                val enabled = prefs.display.gpuHardwareLayerEnabled
                val message = if (enabled) {
                    "GPU Hardware Mode enabled.\n\nThe new compositor mode takes effect after restarting the app. If the dashboard renders blank after restart, turn this setting back off.\n\nRestart now?"
                } else {
                    "GPU Hardware Mode disabled.\n\nDefault compositor restores after restarting the app.\n\nRestart now?"
                }
                showRestartDialog(activity, message)
            }
        }

        // Music callbacks
        registry.register("notifyMusicEnabledChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_MUSIC_ENABLED_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("enabled", prefs.connection.musicPlayerEnabled)
                }
            )
            // Prompt restart so Sendspin speaker connection starts/stops
            if (context is android.app.Activity) {
                val activity = context as android.app.Activity
                val enabled = prefs.connection.musicPlayerEnabled
                val message = if (enabled) {
                    "Music player enabled.\n\nThe speaker connection will start after restarting the app.\n\nRestart now?"
                } else {
                    "Music player disabled.\n\nThe speaker connection will stop after restarting the app.\n\nRestart now?"
                }
                showRestartDialog(activity, message)
            }
        }

        // Intercept speaker-only enable so we can run discovery + let the user confirm/edit
        // the MA URL before flipping the flag. Disable path bypasses the dialog entirely.
        registry.registerToggleInterceptor("music.speakerOnly") { newValue, proceed, cancel ->
            if (!newValue) {
                // Turning OFF: clear the saved URL so the next enable re-runs discovery.
                prefs.connection.speakerOnlyMaUrl = ""
                // If the user has no MA token either, they've just removed their only
                // remaining way to reach a music backend — auto-disable music.enabled so
                // we don't sit in a broken state with a sidebar icon that can't do anything
                // (polling failures + retry spam in logs). Broadcast the change so the
                // sidebar updates without requiring a restart.
                if (prefs.connection.musicPlayerEnabled && !prefs.connection.hasMaApiToken) {
                    android.util.Log.i(
                        "SettingsCallbackWiring",
                        "🎵 Speaker-only disabled and no MA token — auto-disabling music.enabled to avoid broken state"
                    )
                    prefs.connection.musicPlayerEnabled = false
                    context.sendBroadcast(
                        android.content.Intent("com.dashieapp.Dashie.ACTION_MUSIC_ENABLED_CHANGED").apply {
                            setPackage(context.packageName)
                            putExtra("enabled", false)
                        }
                    )
                }
                proceed()
                return@registerToggleInterceptor
            }
            val activity = context as? android.app.Activity ?: run {
                // No activity context — can't show a dialog; fall back to the pre-dialog
                // behavior (flip flag, rely on mDNS discovery at connect time).
                proceed()
                return@registerToggleInterceptor
            }
            com.dashieapp.Dashie.halite.music.sendspin.SpeakerOnlyConnectDialog(
                activity = activity,
                priorUrl = prefs.connection.speakerOnlyMaUrl
            ) { confirmed, url ->
                if (confirmed && !url.isNullOrBlank()) {
                    prefs.connection.speakerOnlyMaUrl = url
                    proceed()
                } else {
                    // Cancel path (or URL blank) — tell the settings UI to re-read the
                    // stored value so the optimistically-flipped Switch snaps back to OFF.
                    cancel()
                }
            }.show()
        }

        registry.register("notifySpeakerOnlyChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_MUSIC_ENABLED_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("enabled", prefs.connection.musicPlayerEnabled)
                }
            )
            val speakerOnly = prefs.connection.musicSpeakerOnly
            val message = if (speakerOnly) {
                "Speaker-only mode enabled.\n\nThe device will auto-discover and connect to Music Assistant as a speaker after restarting.\n\nRestart now?"
            } else {
                "Speaker-only mode disabled.\n\nRestart now?"
            }
            if (context is android.app.Activity) {
                showRestartDialog(context as android.app.Activity, message)
            }
        }

        // Camera callbacks
        registry.register("notifyCameraEnabledChanged") {
            // Auto-enable HA sensor motion (not face) when RTSP is turned on.
            // Face detection is expensive (ML Kit on every frame) and should be opt-in.
            if (cameraPrefs.rtspEnabled) {
                if (!cameraPrefs.haSensorEnabled) cameraPrefs.haSensorEnabled = true
                if (!cameraPrefs.haSensorMotionEnabled) cameraPrefs.haSensorMotionEnabled = true
                // Refresh settings UI to reflect auto-enabled toggles
                (context as? com.dashieapp.Dashie.halite.settings.SettingsActivity)?.runOnUiThread {
                    (context as com.dashieapp.Dashie.halite.settings.SettingsActivity)
                        .supportFragmentManager.fragments
                        .filterIsInstance<SchemaSettingsFragment>().firstOrNull()
                        ?.refresh()
                }
                // Notify HA sensor config changed so publisher starts
                context.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_HA_SENSOR_CONFIG_CHANGED").apply {
                        setPackage(context.packageName)
                    }
                )
            }
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CAMERA_ENABLED_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("enabled", cameraPrefs.rtspEnabled)
                }
            )
        }

        registry.register("notifyCameraResolutionChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CAMERA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyCameraFpsChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CAMERA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyCameraSoftwareEncodingChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CAMERA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyCameraPortraitStreamChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CAMERA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyCameraRotateStream180Changed") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CAMERA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyCameraMirrorCorrectionChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CAMERA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("showStreamPreview") {
            val activity = (context as? android.app.Activity) ?: return@register
            val debugDialog = com.dashieapp.Dashie.halite.sidebar.dialogs.RtspStreamDebugDialog(
                activity, prefs
            )
            debugDialog.isStreamRunning = SettingsActivity.isRtspRunning
            debugDialog.getStreamUrl = SettingsActivity.getRtspStreamUrl
            debugDialog.getClientCount = SettingsActivity.getRtspClientCount
            debugDialog.getCameraPreviewFrame = SettingsActivity.getCameraPreviewFrame
            debugDialog.show()
        }

        registry.register("notifyHaSensorConfigChanged") {
            // Auto-enable master HA sensor flag when motion or face is toggled on
            if (cameraPrefs.haSensorMotionEnabled || cameraPrefs.haSensorFaceEnabled) {
                if (!cameraPrefs.haSensorEnabled) cameraPrefs.haSensorEnabled = true
            }
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_HA_SENSOR_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Voice callbacks
        // Unified: every voice-setting change also fires ACTION_VOICE_SETTINGS_CHANGED
        // so MainBroadcastManager can persist the full voice category blob to
        // user_devices via saveDeviceSettings('voice', …). Individual
        // ACTION_VOICE_ENABLED_CHANGED is kept for the voice-controller side
        // effect (shutdown/re-init) which doesn't want to fire on other
        // voice changes.
        val broadcastVoiceSettingsChanged = {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VOICE_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Live preview of the selected confirmation tone as the user auditions
        // options in the picker (the picker stays open and fires onChanged on
        // each tap). Routes through the same ConfirmationTonePlayer (ALARM
        // stream) as real playback, so the audition matches what's actually
        // heard — including the independent-of-music volume behaviour.
        val previewConfirmationTone = {
            val tone = voicePrefs.confirmationToneType
            if (tone != com.dashieapp.Dashie.halite.preferences.VoicePreferences.CONFIRMATION_TONE_DISABLED) {
                com.dashieapp.Dashie.halite.audio.ConfirmationTonePlayer.play(
                    context, tone, voicePrefs.confirmationToneVolume
                )
            }
        }

        // Fire when a setting that changes the *effective voice pipeline*
        // (control method, STT/TTS provider, AI model, customize toggle) is
        // changed natively, so the running voice service switches immediately
        // instead of only after a dashboard reload / app restart. Routed to
        // HaliteVoiceController.reinitialize() in MainBroadcastManager.
        val requestVoicePipelineReinit = {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VOICE_PIPELINE_REINIT").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Round-trip an account-scoped voice pipeline change (customize toggle /
        // STT / TTS provider) up to user_settings.voice.* — MainBroadcastManager's
        // ACTION_AI_VOICE_SETTINGS_CHANGED receiver reads voicePrefs and calls
        // window.saveAccountSettings({voice:{…}}) (echo-guarded). Without this a
        // native pipeline edit only wrote device prefs + reinit and the console
        // never saw it (native→cloud upload gap, fixed 2026-07-20). Same broadcast
        // the alwaysOpenDialog / pipeline_preset / ai_model handlers already fire.
        // changedKey = the ONE account path the user just changed (e.g. "voice.ttsProvider").
        // MainBroadcastManager forwards it to applyNativeVoiceAiAccountChange so that key uploads
        // unconditionally (heals a DB drifted behind the store), while siblings still diff (no clobber).
        // Every ACTION_AI_VOICE_SETTINGS_CHANGED sender goes through here so the extra is never forgotten.
        val roundTripAccountVoiceChange = { changedKey: String ->
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_AI_VOICE_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("changedKey", changedKey)
                }
            )
        }

        registry.register("notifyVoiceEnabledChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VOICE_ENABLED_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("enabled", voicePrefs.voiceEnabled)
                }
            )
            broadcastVoiceSettingsChanged()
        }

        // REMOVED 2026-07-15 (dead wiring): `notifyVoiceControlMethodChanged` fired from the old
        // "Voice Control Method" picker, which the Open Brain preset picker superseded in Phase 3a/3b
        // (b8a924e1). The provider-default seeding it did now lives in VoicePresetSeeder.applyPreset,
        // driven by notifyPipelinePresetChanged below (controlMethod is a derived runtime key now).
        // With no schema item referencing it, lint:settings Check E2 flagged it as a typo trap.

        registry.register("notifyPipelinePresetChanged") {
            // Open Brain preset picked (Phase 3 Kotlin mirror): seed the
            // granular keys (controlMethod/providers/model — console-parity
            // rules live in VoicePresetSeeder), then the same change plumbing
            // as the old controlMethod picker. voice.pipelinePreset itself is
            // account-scoped — round-trip via the AI broadcast like
            // alwaysOpenDialog so the console/other devices pick it up.
            val ai = com.dashieapp.Dashie.halite.preferences.AiPreferences(context)
            VoicePresetSeeder.applyPreset(voicePrefs, ai, voicePrefs.pipelinePreset)
            roundTripAccountVoiceChange("voice.pipelinePreset")
            broadcastVoiceSettingsChanged()
            requestVoicePipelineReinit()
        }

        registry.registerValueCallback("onCloudPresetBlocked") { tappedPreset ->
            // A grayed Cloud/Hybrid preset was tapped while cloud isn't available
            // (VoiceAiOptions sets onDisabledTap → here). Explain why and offer a
            // path — create an account, or (account but not cloud-ready) add
            // credits / set up the Dashie Console. Kiosk cloud/voice onboarding
            // phase 1 — see CloudActivationDialog.
            //
            // tappedPreset ("cloud" | "hybrid") is threaded through from the picker
            // (PickerSubScreenFragment passes the tapped option's value). We REMEMBER
            // it so that once the blocker clears (credits added / stale row), we can
            // auto-apply exactly the preset the user reached for — no re-tap, no
            // back-and-return. Defensive default to "cloud" if it ever arrives blank.
            val activity = context as? SettingsActivity ?: return@registerValueCallback
            val conn = prefs.connection
            val remembered = tappedPreset.ifBlank { "cloud" }

            // Single source of truth (CloudActivationState) — the SAME check the
            // schema gate uses, so the popup can never contradict the row.
            val refreshVoiceAi = {
                (activity.supportFragmentManager.findFragmentById(com.dashieapp.Dashie.R.id.settingsFragmentContainer)
                    as? com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment)?.refresh()
            }

            // Apply the remembered preset, then return to the main Voice & AI page
            // showing it selected. Mirrors notifyPipelinePresetChanged's plumbing
            // (seed granular keys → round-trip account key → broadcast → reinit) so
            // an auto-applied preset is indistinguishable from a hand-picked one.
            val applyRememberedPreset = { preset: String ->
                activity.runOnUiThread {
                    voicePrefs.pipelinePreset = preset
                    VoicePresetSeeder.applyPreset(
                        voicePrefs,
                        com.dashieapp.Dashie.halite.preferences.AiPreferences(context),
                        preset
                    )
                    roundTripAccountVoiceChange("voice.pipelinePreset")
                    broadcastVoiceSettingsChanged()
                    requestVoicePipelineReinit()
                    // Pop the picker sub-screen (synchronously, so the main fragment
                    // is back in the container) then refresh it — the schema rebuilds
                    // with cloudAvailable=true and preset=<remembered>, so the row is
                    // both selectable and shown selected.
                    activity.supportFragmentManager.popBackStackImmediate()
                    refreshVoiceAi()
                }
            }

            // Cloud activation is a PAYWALL flow: the tri-state evaluation and all three
            // dialogs now live behind the seam, so CloudActivationState and
            // CloudActivationDialog leave main/ entirely. main/ keeps only what it owns —
            // applying the preset and refreshing its own fragment.
            //
            // Unreachable in an edition with no metered service: VoiceAiOptions omits the
            // Cloud/Hybrid rows there (2f-2), so nothing can set onDisabledTap to get here.
            com.dashieapp.Dashie.edition.EditionSeams.paywall(activity)
                .handleCloudPresetBlocked(remembered) { preset -> applyRememberedPreset(preset) }
        }

        // "Dashie Details & Setup" — the non-selectable top row of the Voice
        // & AI Setup picker (VoiceAiOptions, enabled=false + onDisabledTap).
        // Tapping it opens the Voice & AI explainer + setup CTAs. The value arg
        // is the sentinel row value; ignored.
        // An On-Device STT row whose model is not installed (VoiceAiOptions: enabled=false +
        // onDisabledTap). The row is deliberately un-selectable until the model is really here —
        // so this is the ONLY way that option becomes selected, and it selects it only after the
        // install succeeds. A failed download leaves the pipeline exactly where it was.
        registry.registerValueCallback("onSttModelDownloadRequested") { tappedValue ->
            val activity = context as? SettingsActivity ?: return@registerValueCallback
            val family = com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry
                .byProviderValue(tappedValue)
            if (family == null) {
                // A stale picker against a changed registry. Do nothing rather than download
                // something arbitrary — and say so, because silence here would look like a
                // dead row (rule 2: no silent drops).
                android.util.Log.w("SttModelDownload",
                    "DROP: [unexpected] tapped STT row '$tappedValue' names no registry family — " +
                    "no download offered")
                return@registerValueCallback
            }
            com.dashieapp.Dashie.halite.settings.dialogs.SttModelDownloadPrompt
                .offer(activity, family) {
                    // Installed. The SELECTION is already applied by SttAutoSelect from the
                    // install thread (pref + the same three broadcasts this file fires for a
                    // hand-picked row) — it must not ride this activity-gated callback, which
                    // is skipped whenever settings closed during the ~2-minute extraction.
                    // Leave the picker sub-screen and rebuild the page: the schema recomputes
                    // modelUsable=true, so the row comes back selectable AND shown selected.
                    activity.supportFragmentManager.popBackStackImmediate()
                    (activity.supportFragmentManager.findFragmentById(
                        com.dashieapp.Dashie.R.id.settingsFragmentContainer
                    ) as? SchemaSettingsFragment)?.refresh()
                }
        }

        registry.registerValueCallback("onVoiceAiLearnMore") { _ ->
            val activity = context as? android.app.Activity ?: return@registerValueCallback
            com.dashieapp.Dashie.halite.settings.dialogs.DashieVoiceInfoDialog.show(activity)
        }

        registry.register("notifyDialogModeChanged") {
            // Conversation-dialog toggle → voice.agentMode dialog|single
            // (account-scoped, like alwaysOpenDialog) — round-trip + reinit so
            // the running pipeline picks up the mode.
            roundTripAccountVoiceChange("voice.agentMode")
            requestVoicePipelineReinit()
        }

        // REMOVED 2026-07-13 (Local Engines): `notifyLocalLlmConfigChanged` existed to round-trip
        // an on-device edit of the "My own AI" URL/model. Those TextInputs are gone — own-box
        // engines are now selected on the console's Local Engines page (which can probe the box),
        // and the tablet shows the engine's NAME read-only. With no schema item referencing it,
        // the callback was dead wiring — `lint:settings` check E2 flagged it, which is exactly
        // the near-miss-typo trap that check exists for. Deleted rather than left dangling.

        registry.register("notifyCustomizePipelineChanged") {
            broadcastVoiceSettingsChanged()
            roundTripAccountVoiceChange("voice.customizePipeline")
            requestVoicePipelineReinit()
        }

        registry.register("notifySttProviderChanged") {
            broadcastVoiceSettingsChanged()
            roundTripAccountVoiceChange("voice.sttProvider")
            requestVoicePipelineReinit()
        }

        registry.register("notifyAiModelChanged") {
            // AI model persisted via value provider. Round-trip to
            // user_devices.aiVoice + window.settingsStore.ai.model the
            // same way personality does, so the WebView's next AI call
            // picks the new model instead of falling back to the JS
            // default (gemini-2.5-flash).
            roundTripAccountVoiceChange("ai.model")
            requestVoicePipelineReinit()
        }

        registry.register("notifyRetrievePicturesChanged") {
            // ai.retrievePicturesEnabled is account-scoped (ACCOUNT_AI_KEYS) and
            // gates image search in BOTH cascade + Live. Round-trip to
            // user_settings.ai.retrievePicturesEnabled via the same broadcast as
            // ai.model so the console/web + other devices pick up the change.
            roundTripAccountVoiceChange("ai.retrievePicturesEnabled")
        }

        registry.register("notifyWebSearchChanged") {
            // ai.webSearchEnabled is account-scoped (ACCOUNT_AI_KEYS) — the edge
            // brain reads user_settings.ai.webSearchEnabled. Round-trip via the same
            // broadcast as ai.model so the native toggle reaches the account (was a
            // dead local-only pref that the edge never saw).
            roundTripAccountVoiceChange("ai.webSearchEnabled")
        }

        registry.register("notifyAlwaysUseAiChanged") {
            // "Always Use AI for Chores" — canonical key is account-scoped
            // voice.alwaysUseAI (fast-path + console + web read it); the native pref
            // lives on AiPreferences. Round-trip via the AI broadcast so the native
            // toggle reaches user_settings.voice.alwaysUseAI (was a dead-end pref).
            roundTripAccountVoiceChange("voice.alwaysUseAI")
        }

        registry.register("notifyPromptForFeedbackChanged") {
            // ai.promptForFeedback is account-scoped (ACCOUNT_AI_KEYS) — the voice
            // response feedback prompt toggle. Round-trip to user_settings.ai.
            // promptForFeedback via the same broadcast as ai.model/retrievePictures
            // so the console/web + other devices pick up the change.
            roundTripAccountVoiceChange("ai.promptForFeedback")
        }

        registry.register("notifyAlwaysOpenDialogChanged") {
            // DLG-6: voice.alwaysOpenDialog is account-scoped (ACCOUNT_VOICE_KEYS).
            // Round-trip to user_settings.voice.alwaysOpenDialog via the same broadcast
            // so the console/web + other devices pick up the change.
            roundTripAccountVoiceChange("voice.alwaysOpenDialog")
        }

        registry.register("notifyTtsProviderChanged") {
            broadcastVoiceSettingsChanged()
            roundTripAccountVoiceChange("voice.ttsProvider")
            requestVoicePipelineReinit()
        }

        // GAP-2: Live voice picked → round-trip voice.liveVoiceName up to user_settings.voice
        // (account-scoped). MainBroadcastManager's ACTION_AI_VOICE_SETTINGS_CHANGED payload carries
        // it (conversationVoice). Live owns TTS, so no pipeline reinit needed here.
        registry.register("notifyLiveVoiceChanged") {
            roundTripAccountVoiceChange("voice.liveVoiceName")
        }

        registry.register("notifyResponseHandlingChanged") {
            broadcastVoiceSettingsChanged()
        }

        registry.register("notifyDisplayFormatChanged") {
            broadcastVoiceSettingsChanged()
        }

        registry.register("notifyConfirmationToneChanged") {
            broadcastVoiceSettingsChanged()
            previewConfirmationTone()
        }

        // showHaPipelinePicker: intentionally not registered — the pipeline picker is a
        // navigation target (PipelinePickerFragment) reached from the Voice & AI schema,
        // not a callback-invoked dialog.

        registry.register("openAutoBrightnessSettings") {
            // Show brightness dialog on top of settings (no dismiss)
            val activity = context as? SettingsActivity ?: return@register
            val brightnessUI = BrightnessUI(activity, prefs)
            brightnessUI.showAutoBrightnessSettingsDialog(object : SettingsCallbacks {
                override fun onAutoBrightnessChanged(enabled: Boolean, min: Int, max: Int, curve: String) {
                    context.sendBroadcast(
                        android.content.Intent("com.dashieapp.Dashie.ACTION_BRIGHTNESS_CHANGED").apply {
                            setPackage(context.packageName)
                        }
                    )
                }
            })
        }

        // HA connection callbacks
        registry.register("notifyHaDisplayChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_HA_DISPLAY_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyApiChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_API_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyReturnHomeChanged") {
            // Return home timeout takes effect immediately via PerformancePreferences
        }

        // Weather callbacks — triggered when user changes location or the
        // "Use Home Assistant for weather" toggle in Preferences. Forces
        // an immediate weather re-fetch so the widget reflects the new
        // source / coordinates without waiting for the next 15-min poll.
        registry.register("notifyWeatherConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_WEATHER_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Location callback — triggered when the user edits Preferences →
        // Location. Distinct from the generic weather callback: besides
        // refreshing weather, this must push the new zip up to the canonical
        // account setting family.zipCode (via the WebView native-settings
        // listener) so the edit reaches the cloud instead of reverting on
        // reload. MainBroadcastManager handles ACTION_LOCATION_CONFIG_CHANGED.
        registry.register("notifyLocationConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_LOCATION_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Account-level preference callback — language picker. Fires a broadcast
        // that MainBroadcastManager turns into a native-settings-changed
        // 'account_prefs' dispatch so the JS handler writes general.language to
        // user_settings (the useHa toggles ride the existing weather/time
        // broadcasts, which now also dispatch account_prefs).
        registry.register("notifyAccountPrefsChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_ACCOUNT_PREFS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Time-source callback — triggered when the user toggles "Use HA
        // for time". Tells the screensaver / clock subsystem to (re)start
        // or stop HaTimeProvider.
        registry.register("notifyTimeConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_TIME_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Sleep callbacks
        registry.register("notifySleepConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_SLEEP_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Locations callbacks — notify WebView so JS can sync to settingsStore + Supabase
        registry.register("notifyLocationsConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_LOCATIONS_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Chores & Rewards callbacks
        registry.register("notifyChoresRewardsConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CHORES_REWARDS_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Calendar callbacks
        registry.register("notifyCalendarConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_CALENDAR_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
            // Sync calendar display settings to JS widget via WebView localStorage
            val prefs = context.getSharedPreferences("dashie_calendar_prefs", android.content.Context.MODE_PRIVATE)
            val startWeekOn = prefs.getString("start_week_on", "sunday") ?: "sunday"
            val scrollTime = prefs.getString("scroll_time", "8") ?: "8"
            val hoursToShow = prefs.getString("hours_to_show", "auto") ?: "auto"
            // Map Kotlin values to widget format: "sunday" → "sun", "monday" → "mon"
            val widgetWeekStart = when (startWeekOn) { "monday" -> "mon"; "saturday" -> "sat"; else -> "sun" }
            val wv = com.dashieapp.Dashie.halite.settings.SettingsActivity.webViewRef?.get()
            wv?.post {
                wv.evaluateJavascript("""
                    try {
                        var s = JSON.parse(localStorage.getItem('dashie-calendar-settings') || '{}');
                        s.startWeekOn = '$widgetWeekStart';
                        s.scrollTime = $scrollTime;
                        s.hoursToShow = '$hoursToShow';
                        localStorage.setItem('dashie-calendar-settings', JSON.stringify(s));
                    } catch(e) {}
                """.trimIndent(), null)
            }
        }

        // Power management callbacks
        registry.register("notifyPowerConfigChanged") {
            // Live-apply: the watchdog re-reads the SharedPreferences config on
            // its next 60s poll cycle, so no native action is needed here.
            // Sync (audit 2026-07-04 §2B): power.* rides the Kotlin-owned HA
            // blob, and this callback used to be a full no-op — the config
            // never reached user_devices until the next boot upload. Fire the
            // generic HA-config broadcast so MainBroadcastManager dispatches
            // home_assistant_config and JS persists the blob now.
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_HA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Generic HA-blob sync callback (audit §2B): for schema items whose
        // values ride getHomeAssistantSettings() but have no domain-specific
        // live-apply — custom URL toggle/field, advanced tablet controls.
        // MainBroadcastManager turns the broadcast into a
        // home_assistant_config dispatch (JS blob readback → user_devices).
        registry.register("notifyHaConfigChanged") {
            // 125c9987 made haEnabled=false a persistent state for custom-URL
            // ("host your own dashboard") devices. Flipping "Use Non-HA URL"
            // back OFF must hand the dashboard back to HA — otherwise a kiosk
            // device strands in haEnabled=false with useCustomUrl=false, where
            // every HA surface (including this page's own sign-in row) is
            // gated off and no UI can re-enable it. Kiosk-shell (no linked
            // account) devices only: in full mode the welcome wizard owns
            // haEnabled, and a stale HA URL there must not re-arm HA.
            val hp = com.dashieapp.Dashie.halite.HalitePreferences(context)
            val haConn = hp.connection
            val haUrlConfigured = haConn.buildFullUrl().let {
                it.isNotEmpty() && it != com.dashieapp.Dashie.halite.HalitePreferences.DEFAULT_HA_URL
            }
            if (!haConn.useCustomUrl && !haConn.haEnabled
                && !hp.account.isLinked && haUrlConfigured) {
                haConn.haEnabled = true
                android.util.Log.i("SettingsCallbackWiring",
                    "🏠 Custom-URL toggle released the dashboard back to HA — re-enabling haEnabled")
            }
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_HA_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyPowerSwitchToggled") {
            // Toggle the HA switch via REST API
            val activity = context as? SettingsActivity ?: return@register
            activity.togglePowerSwitch(powerPrefs)
        }

        // Video feed callbacks
        registry.register("notifyVideoFeedEnabledChanged") {
            // Pull feeds from HA when video feeds are enabled
            val vfPrefs = VideoFeedPreferences(context)
            if (vfPrefs.enabled) {
                vfPrefs.pullFeedsFromHa { success ->
                    if (success) {
                        android.util.Log.i("SettingsCallbackWiring", "Pulled HA feeds on enable")
                        (context as? com.dashieapp.Dashie.halite.settings.SettingsActivity)?.runOnUiThread {
                            val frag = (context as com.dashieapp.Dashie.halite.settings.SettingsActivity)
                                .supportFragmentManager.fragments
                                .filterIsInstance<SchemaSettingsFragment>().firstOrNull()
                            frag?.refresh()
                        }
                    }
                }
            }
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("notifyVideoFeedConfigChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("videoFeedAddFeed") {
            val activity = context as? SettingsActivity ?: return@register
            activity.showVideoFeedEditor(null)
        }

        // Advanced callbacks
        registry.register("advancedClearCache") {
            val activity = context as? SettingsActivity ?: return@register
            activity.showClearCacheConfirmation()
        }

        registry.register("choresAddOnMobile") {
            val activity = context as? SettingsActivity ?: return@register
            com.dashieapp.Dashie.edition.EditionSeams.paywall(activity).showConnectMobile(
                titleOverride = "Add Chores on Mobile",
                // ⚠️ No host in this string. It used to name `app.dashieapp.com/hub`, which put a
                // Dashie endpoint in main/'s dex for every edition — and it was redundant anyway:
                // ConnectMobileDialog already renders a scannable URL, so the prose was a second,
                // hand-maintained copy of an address the dialog knows for itself.
                subtitleOverride = "Add chores & rewards on your phone or in your browser"
            )
        }

        registry.register("advancedGoAndroidHome") {
            // Escape hatch for kiosk users whose default home app is Dashie:
            // open the device's system launcher for a one-shot visit. Logic
            // lives in SettingsLauncher (enumerate home apps, chooser if >1).
            try {
                com.dashieapp.Dashie.devicecontrols.SettingsLauncher(context).openSystemLauncher()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Could not open system launcher", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        registry.register("advancedOpenAndroidSettings") {
            val launcher = com.dashieapp.Dashie.devicecontrols.SettingsLauncher(context)
            launcher.openSystemSettings()
        }

        registry.register("advancedChangeWifi") {
            // Route through Dashie's branded WifiSetupActivity (same UI as
            // the boot-time no-internet redirect) so the experience is
            // consistent. EXTRA_FORCE_SHOW prevents the auto-bounce that
            // would normally fire when the user is already connected.
            val intent = android.content.Intent(context, com.dashieapp.Dashie.wifi.WifiSetupActivity::class.java)
                .putExtra(com.dashieapp.Dashie.wifi.WifiSetupActivity.EXTRA_FORCE_SHOW, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        registry.register("advancedCheckForUpdate") {
            // Hand off to MainActivity's DashieUpdateController via broadcast —
            // it owns the update banner. If an update is available the banner
            // appears on the dashboard once Settings is closed.
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_CHECK_UPDATE")
                    .apply { setPackage(context.packageName) }
            )
            android.widget.Toast.makeText(
                context, "Checking for updates…", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        registry.register("advancedRelinquishDeviceOwner") {
            val activity = context as? SettingsActivity ?: return@register
            activity.showRelinquishDeviceOwnerConfirmation()
        }

        // Manage Account: QR to the mobile site
        // Account management/deletion is intentionally NOT possible from the
        // shared tablet (a family member could otherwise wipe the household
        // account in one tap). Instead we show a QR pointing at the mobile
        // dashboard, where the owner can manage — or delete, with a 15-day
        // recoverable grace — their account from their own phone. The console
        // (/console) is offered as an alternative in the dialog text.
        registry.register("accountManageAccount") {
            android.util.Log.i("AccountManage", "👤 Manage account tapped — showing mobile QR")
            val activity = context as? android.app.Activity
            if (activity == null) {
                android.util.Log.w("AccountManage", "No Activity context available — aborting")
                return@register
            }
            com.dashieapp.Dashie.edition.EditionSeams.paywall(activity).showManageAccount()
        }

        // FB27: Add Credits — the orange add-credits QR modal (mobile add-credits page).
        registry.register("accountAddCredits") {
            (context as? com.dashieapp.Dashie.halite.settings.SettingsActivity)
                ?.showAddCreditsFlow()
        }

        // Brand-split T4: the free path that sits beside Add Credits on the Account page.
        // Keys and local engines are configured in the Dashie Console (the HA add-on) —
        // there is deliberately no on-device API-key entry — so this is the setup
        // explainer rather than an in-place switch.
        registry.register("accountUseOwnKeysOrLocal") {
            val activity = context as? android.app.Activity ?: return@register
            // Through the seam: the dialog carries a dashieapp.com guide URL and now lives in
            // src/dashie/, so that endpoint no longer ships in the account-free artifact.
            com.dashieapp.Dashie.edition.EditionSeams.paywall(activity).showConsoleSetup()
        }

        // Dev-only: open the RT Audio Test (realtime conversation-mode spike).
        // The schema only surfaces this action on staging/local flavors.
        registry.register("openRtAudioTest") {
            val activity = context as? android.app.Activity ?: return@register
            activity.startActivity(
                android.content.Intent(activity, com.dashieapp.Dashie.devtools.RtAudioTestActivity::class.java)
            )
        }

        // Dev-only: open Continuous Audio Capture (harvest real mic-path VoIP audio
        // for training negatives). Schema surfaces this on staging/local flavors only.
        registry.register("openContinuousCapture") {
            val activity = context as? android.app.Activity ?: return@register
            activity.startActivity(
                android.content.Intent(activity, com.dashieapp.Dashie.devtools.ContinuousCaptureActivity::class.java)
            )
        }

        // MQTT callbacks
        registry.register("mqttEnabledChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_MQTT_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }
        registry.register("mqttDiscoveryChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_MQTT_DISCOVERY_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }
        // A sensor toggle changed — refresh HA discovery in place so the entity
        // appears/disappears immediately instead of only on the next reconnect.
        registry.register("mqttSensorsChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_MQTT_SENSORS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }
        // Base topic changed — reconnect so the running client re-subscribes the
        // command topic and republishes discovery with the new state/command
        // topics (they're embedded in the discovery config payloads).
        registry.register("mqttBaseTopicChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_MQTT_BASE_TOPIC_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }
        registry.register("mqttTestConnection") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_MQTT_TEST_CONNECTION").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Performance callbacks
        registry.register("performanceToggleOverlay") {
            val perfPrefs = PerformancePreferences(context)
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_TOGGLE_PERFORMANCE_OVERLAY").apply {
                    setPackage(context.packageName)
                    putExtra("enabled", perfPrefs.performanceOverlayEnabled)
                }
            )
        }

        registry.register("performanceRefreshThresholds") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_REFRESH_THRESHOLDS").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("performanceWifiLockChanged") {
            val perfPrefs = PerformancePreferences(context)
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_WIFI_LOCK_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("enabled", perfPrefs.wifiLockEnabled)
                }
            )
        }

        // Periodic (stealth) refresh enable/interval changed from the native menu —
        // tell the running scheduler to re-read prefs and reschedule/cancel the alarm
        // live (disabling cancels a pending alarm) instead of waiting for a reload.
        registry.register("notifyStealthRefreshChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_STEALTH_REFRESH_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        registry.register("performanceSendDiagnostics") {
            val activity = context as? SettingsActivity ?: return@register
            val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
            val diagnosticsDialogs = com.dashieapp.Dashie.halite.sidebar.dialogs.DiagnosticsDialogs(
                activity, halitePrefs
            )
            diagnosticsDialogs.showSendDiagnosticsDialog()
        }

        registry.register("performanceSendCrashReport") {
            val activity = context as? SettingsActivity ?: return@register
            com.dashieapp.Dashie.MainCrashReportHandler.showCrashReportDialog(
                activity, activity.lifecycleScope
            )
        }

        // Screensaver callbacks
        registry.register("notifyScreensaverChanged") {
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_SCREENSAVER_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Account mode switch — immediately switch viewport when kiosk toggle changes
        registry.register("accountSwitchMode") {
            val accountPrefs = AccountPreferences(context)
            if (accountPrefs.forceKioskMode) {
                // Switching to kiosk: tell MainActivity to load HA shell
                context.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_KIOSK").apply {
                        setPackage(context.packageName)
                    }
                )
            } else {
                // Switching to full mode: tell MainActivity to load Dashie URL
                context.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_FULL").apply {
                        setPackage(context.packageName)
                    }
                )
            }
            // Close settings + CC so the viewport switch is visible
            (context as? SettingsActivity)?.let {
                it.setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
                it.finish()
            }
        }

        // When screensaver mode changes to "photos", auto-enable clock + current weather
        val ssPrefs = prefs.screensaver
        registry.register("notifyScreensaverModeChanged") {
            if (ssPrefs.screensaverMode == "photos") {
                if (!ssPrefs.showClock) ssPrefs.showClock = true
                if (ssPrefs.weatherMode == "disabled") ssPrefs.weatherMode = "current"
            }
            // Also fire the standard screensaver changed broadcast
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_SCREENSAVER_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
            // Refresh settings UI to reflect auto-enabled toggles
            (context as? com.dashieapp.Dashie.halite.settings.SettingsActivity)?.runOnUiThread {
                (context as com.dashieapp.Dashie.halite.settings.SettingsActivity)
                    .supportFragmentManager.fragments
                    .filterIsInstance<SchemaSettingsFragment>().firstOrNull()
                    ?.refresh()
            }
        }

    }

    /**
     * Called from SettingsActivity.onResume to complete the music enable flow
     * after returning from MaLoginActivity.
     */
    fun checkPendingMusicEnable(prefs: HalitePreferences, activity: SettingsActivity? = null) {
        val callback = pendingMusicEnableCallback ?: return
        val cancelCallback = pendingMusicEnableCancel
        pendingMusicEnableCallback = null
        pendingMusicEnableCancel = null
        if (prefs.connection.hasMaApiToken) {
            // Login succeeded — save token centrally for other devices
            SettingsDialogWiring.saveCentralMaToken(prefs)
            callback()
            // Check role + show non-admin warning right after login
            activity?.checkMaTokenRole()
        } else {
            // User cancelled login (X button, back, failure) — revert the toggle via the
            // interceptor's cancel path. That snaps the Switch back to OFF and clears any
            // optimistic visual state. Falls back to the legacy fragment.refresh() call
            // for older callers that still pass a SettingsActivity.
            android.util.Log.i("SettingsCallbackWiring", "🎵 MA login cancelled — reverting toggle")
            cancelCallback?.invoke()
            activity?.let { act ->
                val fragment = act.supportFragmentManager.findFragmentById(
                    com.dashieapp.Dashie.R.id.settingsFragmentContainer
                )
                if (fragment is SchemaSettingsFragment) {
                    fragment.refresh()
                }
            }
        }
    }

    fun registerInterceptors(registry: SettingsCallbackRegistry, context: Context, prefs: HalitePreferences) {
        // WS-L.3 P2 — auto-refill kill switch. The ONLY settings toggle whose value lives on the
        // server rather than in SharedPreferences, so the write has to round-trip before the
        // switch is allowed to stick: proceed() on a confirmed write, cancel() on any failure
        // (which snaps the optimistically-flipped Switch back). Enabling legitimately fails when
        // no card is saved — the server's own message is surfaced verbatim rather than a generic
        // error, because "buy a pack once to save a card" is the actual next step.
        registry.registerToggleInterceptor("credits.autorefillEnabled") { newValue, proceed, cancel ->
            com.dashieapp.Dashie.edition.EditionSeams.credits(context).setAutorefillEnabled(newValue) { ok, err ->
                val activity = context as? android.app.Activity
                val finish = {
                    if (ok) {
                        // The client already applied the confirmed state to the cache; proceed()
                        // lets the row re-render (its no-op setter writes nothing).
                        proceed()
                    } else {
                        cancel()
                        android.widget.Toast.makeText(
                            context,
                            err ?: "Couldn't update auto-refill.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (activity != null) activity.runOnUiThread { finish() }
                else android.os.Handler(android.os.Looper.getMainLooper()).post { finish() }
            }
        }

        // Disabling HA hides advanced tablet-control sections + HA pages across
        // the settings tree — confirm before flipping. Enabling without a stored
        // access token surfaces the HA login prompt immediately, so the user
        // doesn't have to discover the broken state later.
        registry.registerToggleInterceptor("home_assistant.enabled") { newValue, proceed, cancel ->
            if (newValue) {
                proceed()
                // Tell JS the HA toggle flipped so the sidebar / rotator /
                // photo source picker re-render without waiting for a reload.
                notifyHaEnabledChanged(context)
                // No-op for now: the user can navigate to HA settings via
                // normal channels (sidebar HA icon, CC card, etc.). The
                // warning indicators we render on those surfaces — orange
                // sidebar badge, orange CC card — make it discoverable.
                // Auto-routing them after a toggle introduced surprising
                // navigation; treating it like any other toggle is cleaner.
                return@registerToggleInterceptor
            }
            val activity = context as? android.app.Activity ?: run { proceed(); return@registerToggleInterceptor }
            // Use the shared dialog_confirm layout to match other
            // confirmation dialogs (Sign Out, Account Deleted, etc.) —
            // gives consistent button styling (Cancel-bordered on the
            // left, primary-orange action on the right) instead of the
            // system AlertDialog default which renders both buttons in
            // the app theme's accent color.
            val dialogView = activity.layoutInflater.inflate(
                com.dashieapp.Dashie.R.layout.dialog_confirm, null
            )
            dialogView.findViewById<android.widget.TextView>(
                com.dashieapp.Dashie.R.id.dialogTitle
            ).text = "Disable Home Assistant?"
            dialogView.findViewById<android.widget.TextView>(
                com.dashieapp.Dashie.R.id.dialogMessage
            ).text = "This hides Home Assistant pages and advanced tablet controls on this device. You can re-enable it any time."

            val dialog = android.app.AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .setOnCancelListener { cancel() }
                .create()
            dialog.window?.let { w ->
                w.setBackgroundDrawableResource(android.R.color.transparent)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0.6f)
            }

            val cancelBtn = dialogView.findViewById<android.widget.Button>(
                com.dashieapp.Dashie.R.id.buttonNegative
            )
            val confirmBtn = dialogView.findViewById<android.widget.Button>(
                com.dashieapp.Dashie.R.id.buttonPositive
            )
            cancelBtn.text = "Cancel"
            confirmBtn.text = "Disable"
            cancelBtn.setOnClickListener {
                dialog.dismiss()
                cancel()
            }
            confirmBtn.setOnClickListener {
                dialog.dismiss()
                proceed()
                // Disabling HA hides the Screensaver settings entry as part
                // of the advanced controls cluster. If the screensaver was
                // already armed, the user would be stuck with a screensaver
                // running and no UI surface to disable it. Force-deactivate
                // here so the device returns to a clean state.
                deactivateScreensaverIfActive(context)
                // Tell JS the HA toggle flipped so the sidebar / rotator /
                // photo source picker re-render without a full reload.
                notifyHaEnabledChanged(context)
            }

            dialog.show()
            // D-pad focus default → Cancel (safer for a destructive toggle).
            cancelBtn.isFocusable = true
            confirmBtn.isFocusable = true
            cancelBtn.nextFocusRightId = com.dashieapp.Dashie.R.id.buttonPositive
            confirmBtn.nextFocusLeftId = com.dashieapp.Dashie.R.id.buttonNegative
            cancelBtn.post { cancelBtn.requestFocus() }
        }

        registry.registerToggleInterceptor("voice.enabled") { newValue, proceed, cancel ->
            com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                "VOICE_ENABLE",
                "toggle interceptor: newValue=$newValue, contextType=${context.javaClass.simpleName}"
            )
            if (!newValue) {
                // Turning off — always allowed
                proceed()
                return@registerToggleInterceptor
            }
            // Turning on — check license first
            val activity = context as? SettingsActivity ?: run {
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn(
                    "VOICE_ENABLE",
                    "context not SettingsActivity (${context.javaClass.simpleName}) — proceeding without license check"
                )
                proceed()
                return@registerToggleInterceptor
            }
            // Voice went free on 2026-08-02, so there is no licence check to pass first —
            // enabling proceeds directly. The old wrapper only ever called back on acceptance,
            // so this is the same path minus the gate.
            com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                "VOICE_ENABLE",
                "proceed() (pref will be written true) — no licence gate since voice is free"
            )
            proceed()
        }

        registry.registerToggleInterceptor("music.enabled") { newValue, proceed, cancel ->
            if (!newValue) {
                proceed()
                return@registerToggleInterceptor
            }
            // Already have a token — just enable
            if (prefs.connection.hasMaApiToken) {
                proceed()
                return@registerToggleInterceptor
            }
            // Previously gated on SettingsActivity context — that prevented the login /
            // "Speaker Only" choice dialog from ever firing when music.enabled was toggled
            // from a non-SettingsActivity host (e.g. Control Center's schema-driven flow).
            // Relaxed to any Activity so the dialog shows regardless of entry point.
            val activity = context as? android.app.Activity ?: run { proceed(); return@registerToggleInterceptor }
            val maUrl = prefs.connection.getEffectiveMaApiUrl()

            // Try to fetch a centrally-stored token from HA first
            android.util.Log.i("SettingsCallbackWiring", "🎵 Music enable: checking central store (maUrl=$maUrl)")
            Thread {
                val centralToken = SettingsDialogWiring.fetchCentralMaToken(prefs)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (centralToken != null) {
                        // Got a token from another device — save locally and enable
                        prefs.connection.maApiToken = centralToken.first
                        prefs.connection.maApiUrl = centralToken.second
                        android.util.Log.i("SettingsCallbackWiring", "🎵 Got MA token from central store (${centralToken.first.length} chars)")
                        proceed()
                    } else {
                        android.util.Log.i("SettingsCallbackWiring", "🎵 No central token — showing login / speaker-only choice dialog")
                        showMaLoginDialog(activity, maUrl, proceed, cancel)
                    }
                }
            }.start()
        }
    }

    // ── Private Helpers ─────────────────────────────────────────────────

    /**
     * Step 1 — mode picker. Previously this dialog ALSO had a URL field wedged in,
     * which was confusing because the Speaker Only path opens its own URL-entry dialog
     * (SpeakerOnlyConnectDialog), resulting in double URL entry. Now this is a pure
     * choice dialog: description + three buttons (Cancel / Speaker Only / Sign In),
     * and each active path owns its own step-2 URL-entry dialog.
     */
    /**
     * Deactivate the screensaver (timeout=0) if it was active, then
     * broadcast so HaliteScreenController applies the change live.
     * Called from the HA-disable confirmation flow — the Screensaver
     * settings entry is gated by the advanced-controls cluster, so
     * leaving the screensaver armed after disabling HA would strand
     * the user with no in-app way to turn it off.
     */
    private fun deactivateScreensaverIfActive(context: android.content.Context) {
        val prefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
        if (prefs.screensaver.screensaverTimeout > 0) {
            prefs.screensaver.screensaverTimeout = 0
            android.util.Log.i(
                "SettingsCallbackWiring",
                "🖥️ HA disabled → deactivating screensaver (timeout 0)"
            )
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_SCREENSAVER_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }
    }

    /**
     * Fire ACTION_HA_ENABLED_CHANGED so MainBroadcastManager dispatches a
     * `native-settings-changed` event into the WebView. The JS listener
     * re-reads getHaConnectionSettings(), updates settingsStore, and
     * rebuilds the layout — so the HA icon / photo source picker /
     * rotator update immediately instead of waiting for the next reload.
     */
    private fun notifyHaEnabledChanged(context: android.content.Context) {
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_HA_ENABLED_CHANGED").apply {
                setPackage(context.packageName)
            }
        )
    }

    private fun showMaLoginDialog(activity: android.app.Activity, maUrl: String, proceed: () -> Unit, cancel: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        // Track whether any path has been taken (proceed or cancel) so the mode picker's
        // dismiss doesn't double-fire cancel() after, say, a Speaker Only sub-dialog
        // already resolved.
        var resolved = false

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.dialog_background)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // Title
        android.widget.TextView(activity).apply {
            text = "Connect to Music Assistant"
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            container.addView(this)
        }

        // Spacer
        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            container.addView(this)
        }

        // Description
        android.widget.TextView(activity).apply {
            text = "Choose how this tablet connects to Music Assistant:\n\n" +
                "• Sign In — Enter your Home Assistant admin account for full access " +
                "(playback control, library browsing, profiles). Works best when this " +
                "tablet is your primary music controller.\n\n" +
                "• Speaker Only — Use this tablet as a Sendspin speaker with no login " +
                "required. Music is controlled from another Music Assistant client."
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            container.addView(this)
        }

        // Spacer
        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            container.addView(this)
        }

        // Buttons row
        val buttons = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(true)
            .create()

        // Dismiss-without-resolve (back button, outside tap) should revert the toggle.
        dialog.setOnDismissListener {
            if (!resolved) {
                resolved = true
                cancel()
            }
        }

        // Cancel button
        android.widget.Button(activity).apply {
            text = "Cancel"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                resolved = true
                cancel()
                dialog.dismiss()
            }
            buttons.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { marginEnd = dp(12) })
        }

        // Speaker Only button — step 2 = SpeakerOnlyConnectDialog (discovery + URL
        // confirmation, existing iOS-styled dialog). User confirms URL → saves
        // speakerOnlyMaUrl + flips musicSpeakerOnly + proceeds with music.enabled.
        android.widget.Button(activity).apply {
            text = "Speaker Only"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
            textSize = 14f
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                resolved = true  // suppress dismiss → cancel; sub-dialog will resolve
                dialog.dismiss()
                val prefs = com.dashieapp.Dashie.halite.HalitePreferences(activity)
                com.dashieapp.Dashie.halite.music.sendspin.SpeakerOnlyConnectDialog(
                    activity = activity,
                    priorUrl = prefs.connection.speakerOnlyMaUrl
                ) { confirmed, url ->
                    if (confirmed && !url.isNullOrBlank()) {
                        prefs.connection.speakerOnlyMaUrl = url
                        prefs.connection.musicSpeakerOnly = true
                        proceed()
                        showRestartDialog(activity,
                            "Speaker-only mode enabled.\n\nThe device will connect to Music Assistant as a speaker after restarting.\n\nRestart now?")
                    } else {
                        // User cancelled the sub-dialog — revert the toggle.
                        cancel()
                    }
                }.show()
            }
            buttons.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { marginEnd = dp(12) })
        }

        // Sign In button — step 2 = showMaSignInUrlDialog (URL entry + Connect button
        // that launches MaLoginActivity for OAuth)
        android.widget.Button(activity).apply {
            text = "Sign In"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_primary)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                resolved = true  // suppress dismiss → cancel; sub-dialog will resolve
                dialog.dismiss()
                showMaSignInUrlDialog(activity, maUrl, proceed, cancel)
            }
            buttons.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        }

        container.addView(buttons)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Step 2 for the Sign In path — URL entry + Connect button. Launches
     * MaLoginActivity with the entered URL for OAuth. Sign In completion lands in
     * SettingsActivity.onResume which calls pendingMusicEnableCallback (set here).
     */
    private fun showMaSignInUrlDialog(activity: android.app.Activity, priorUrl: String, proceed: () -> Unit, cancel: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var resolved = false

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.dialog_background)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        android.widget.TextView(activity).apply {
            text = "Sign In to Music Assistant"
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            container.addView(this)
        }

        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            container.addView(this)
        }

        android.widget.TextView(activity).apply {
            text = "Enter the Music Assistant URL you use in your browser. We'll redirect you to Home Assistant to authorize this tablet — this only needs to be done once."
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            container.addView(this)
        }

        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
            container.addView(this)
        }

        android.widget.TextView(activity).apply {
            text = "Music Assistant URL"
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 12f
            container.addView(this)
        }

        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
            container.addView(this)
        }

        val urlInput = android.widget.EditText(activity).apply {
            setText(priorUrl)
            hint = "http://homeassistant.local:8095"
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
            setHintTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            container.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            container.addView(this)
        }

        val buttons = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(true)
            .create()

        // Dismiss-without-resolve (back button, outside tap) reverts the toggle.
        dialog.setOnDismissListener {
            if (!resolved) {
                resolved = true
                cancel()
            }
        }

        android.widget.Button(activity).apply {
            text = "Cancel"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                resolved = true
                cancel()
                dialog.dismiss()
            }
            buttons.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { marginEnd = dp(12) })
        }

        android.widget.Button(activity).apply {
            text = "Connect"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_primary)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                val enteredUrl = urlInput.text.toString().trim().trimEnd('/')
                if (enteredUrl.isEmpty() || !enteredUrl.startsWith("http")) {
                    urlInput.error = "Enter a valid URL (e.g. http://homeassistant.local:8095)"
                    return@setOnClickListener
                }
                resolved = true  // suppress dismiss-listener cancel; MaLoginActivity owns the outcome
                dialog.dismiss()
                pendingMusicEnableCallback = proceed
                pendingMusicEnableCancel = cancel
                val intent = com.dashieapp.Dashie.halite.music.MaLoginActivity.createIntent(activity, enteredUrl)
                activity.startActivity(intent)
            }
            buttons.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        }

        container.addView(buttons)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    private fun showRestartDialog(activity: android.app.Activity, message: String) {
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Restart Required"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text = message

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative).apply {
            text = "Later"
            setOnClickListener { dialog.dismiss() }
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive).apply {
            text = "Restart Now"
            setOnClickListener {
                dialog.dismiss()
                val intent = android.content.Intent(
                    activity, com.dashieapp.Dashie.MainActivity::class.java
                )
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                activity.startActivity(intent)
                activity.finish()
            }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
    }
}
