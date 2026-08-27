package com.dashieapp.Dashie.halite.settings.schema.wiring

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AccountPreferences
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import com.dashieapp.Dashie.halite.preferences.GeneralPreferences
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import com.dashieapp.Dashie.halite.settings.data.FamilyMember
import com.dashieapp.Dashie.halite.settings.schema.HaliteSettingsValueProvider

/**
 * Wiring for Connection (HA), Music, Account, Subscription, Locations,
 * Chores & Rewards, Calendar, and Family settings sections.
 *
 * Extracted from SettingsSchemaWiring to reduce file size.
 */
object ConnectionSettingsWiring {

    // ── Connection (Home Assistant) ─────────────────────────────────────

    fun registerConnectionPreferences(
        vp: HaliteSettingsValueProvider,
        conn: ConnectionPreferences,
        perfPrefs: PerformancePreferences
    ) {
        // Booleans
        vp.registerBoolean("connection.hideSidebar",
            getter = { conn.hideSidebar },
            setter = { conn.hideSidebar = it }
        )
        vp.registerBoolean("connection.hideTabs",
            getter = { conn.hideTabs },
            setter = { conn.hideTabs = it }
        )
        vp.registerBoolean("connection.hideSearch",
            getter = { conn.hideSearch },
            setter = { conn.hideSearch = it }
        )
        vp.registerBoolean("connection.hideAssistant",
            getter = { conn.hideAssistant },
            setter = { conn.hideAssistant = it }
        )
        vp.registerBoolean("connection.showFloatingBackButton",
            getter = { conn.showFloatingBackButton },
            setter = { conn.showFloatingBackButton = it }
        )
        vp.registerBoolean("connection.apiEnabled",
            getter = { conn.apiEnabled },
            setter = { conn.apiEnabled = it }
        )
        vp.registerBoolean("connection.useCustomUrl",
            getter = { conn.useCustomUrl },
            setter = { conn.useCustomUrl = it }
        )

        // Per-device. Schema conditions read this to gate HA-only and
        // advanced-tablet-control surfaces in the control center.
        vp.registerBoolean("home_assistant.enabled",
            getter = { conn.haEnabled },
            setter = { conn.haEnabled = it }
        )

        // Per-device opt-in for non-HA users to see advanced tablet
        // controls (layout, screensaver, sleep options, screen locking,
        // performance settings, etc.). Schemas typically gate on
        // Or(home_assistant.enabled, advanced.tabletControlsEnabled) so
        // HA users see those sections automatically.
        vp.registerBoolean("advanced.tabletControlsEnabled",
            getter = { conn.advancedTabletControlsEnabled },
            setter = { conn.advancedTabletControlsEnabled = it }
        )

        // Strings (custom URL)
        vp.registerString("connection.customUrl",
            getter = { conn.customUrl },
            setter = { conn.customUrl = it }
        )

        // Strings
        vp.registerString("connection.apiPassword",
            getter = { conn.apiPassword },
            setter = { conn.apiPassword = it }
        )
        // API port stored as int, exposed as string for TextInput compatibility
        vp.registerString("connection.apiPort",
            getter = { conn.apiPort.toString() },
            setter = {
                val port = it.toIntOrNull() ?: ConnectionPreferences.DEFAULT_API_PORT
                conn.apiPort = port.coerceIn(1024, 65535)
            }
        )
        // Return home timeout in seconds, stored as string for picker
        vp.registerString("connection.returnHomeTimeout",
            getter = { perfPrefs.returnHomeTimeout.toString() },
            setter = { perfPrefs.returnHomeTimeout = it.toIntOrNull() ?: 0 }
        )

        // Computed display values (read-only)
        vp.registerString("connection.urlDisplay",
            getter = {
                val url = conn.buildFullUrl()
                if (url.isEmpty() || url == HalitePreferences.DEFAULT_HA_URL) "Not configured"
                else shortenUrl(url)
            },
            setter = { }
        )
        vp.registerString("connection.returnHomeDisplay",
            getter = {
                val seconds = perfPrefs.returnHomeTimeout
                when {
                    seconds <= 0 -> "Disabled"
                    seconds < 60 -> "${seconds}s"
                    else -> "${seconds / 60} min"
                }
            },
            setter = { }
        )
    }

    /** Shorten a URL for display (strip protocol, trailing slash). */
    private fun shortenUrl(url: String): String {
        return url
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
    }

    // ── Music ─────────────────────────────────────────────────────────

    fun registerMusicPreferences(vp: HaliteSettingsValueProvider, conn: com.dashieapp.Dashie.halite.preferences.ConnectionPreferences) {
        // Booleans
        vp.registerBoolean("music.enabled",
            getter = { conn.musicPlayerEnabled },
            setter = { conn.musicPlayerEnabled = it }
        )
        vp.registerBoolean("music.fullScreenOnPlay",
            getter = { conn.musicPlayerFullScreenOnPlay },
            setter = { conn.musicPlayerFullScreenOnPlay = it }
        )
        vp.registerBoolean("music.showWithScreensaver",
            getter = { conn.musicPlayerShowWithScreensaver },
            setter = { conn.musicPlayerShowWithScreensaver = it }
        )
        vp.registerBoolean("music.speakerOnly",
            getter = { conn.musicSpeakerOnly },
            setter = { conn.musicSpeakerOnly = it }
        )
        vp.registerBoolean("music.useHaIntegration",
            getter = { conn.musicUseHaIntegration },
            setter = { conn.musicUseHaIntegration = it }
        )

        // Strings
        vp.registerString("music.entityId",
            getter = { conn.musicPlayerEntityId },
            setter = { conn.musicPlayerEntityId = it }
        )
        vp.registerString("music.defaultEntityId",
            getter = { conn.musicPlayerDefaultEntityId },
            setter = { conn.musicPlayerDefaultEntityId = it }
        )

        // Computed display values (read-only)
        vp.registerString("music.entityDisplay",
            getter = {
                val entityId = conn.getEffectiveMusicPlayerEntityId()
                if (entityId.isEmpty()) "Not set"
                else conn.musicPlayerDisplayName.ifEmpty {
                    entityId.removePrefix("media_player.")
                }
            },
            setter = { }
        )
    }

    // ── Account ──────────────────────────────────────────────────────────

    fun registerAccountPreferences(
        vp: HaliteSettingsValueProvider,
        accountPrefs: AccountPreferences,
        context: Context
        // `voicePrefs` dropped 2026-08-05 with the disconnect-hint hand-mirror: it was read
        // ONLY to answer isPublishedHub, which the seam now resolves for itself.
    ) {
        vp.registerBoolean("account.isLinked",
            getter = { accountPrefs.isLinked },
            setter = { }
        )
        // Shared-kiosk Disconnect hint, named for whichever add-on serves the household
        // gateway (status probe's `hub` field, cached in VoicePreferences) — one brand,
        // two editions, two add-on names.
        //
        // 🔧 Was a HAND-MIRROR of CreditUrls.managementSurface (2026-08-05): the same two
        // add-on names, off the same isPublishedHub input, spelled a second time here because
        // `main/` cannot see `src/dashie/`. The comment even said "same split as" it, which is
        // the tell. Standing rule 1 — share the first rather than write the second — so it goes
        // through the seam that exists for exactly this, and the names now have ONE home.
        vp.registerString("account.disconnectHint",
            getter = { com.dashieapp.Dashie.edition.EditionSeams.accountDisconnectHint(context) },
            setter = { }
        )
        vp.registerString("account.email",
            getter = { accountPrefs.email.ifEmpty { "Not set" } },
            setter = { }
        )
        vp.registerBoolean("account.forceKioskMode",
            getter = { accountPrefs.forceKioskMode },
            setter = { accountPrefs.forceKioskMode = it }
        )
        // D2 (Kiosk Real Login): this device is SIGNED IN but displays Home Assistant — a
        // self-provisioned kiosk. The Account page uses it to explain what "signed in" means
        // here, and to offer the off-switches that actually work (a local Sign Out would just
        // silently re-provision on the next boot while household sharing is on).
        // Read-only: the flag is owned by KioskSessionProvisioner, not the settings UI.
        vp.registerBoolean("account.haOnlyDisplay",
            getter = { accountPrefs.haOnlyDisplay },
            setter = { }
        )
        // Direct voice-only account (flow=voice) vs household-shared kiosk — both are
        // haOnlyDisplay, but only the voice-only one supports a local Sign Out (the
        // shared kiosk re-provisions on boot). AccountPageSchema uses this to pick the
        // Sign Out section over the "manage in console" one. Read-only: owned by the
        // signup/provisioner, not the settings UI.
        vp.registerBoolean("account.haOnlyVoiceSignup",
            getter = { accountPrefs.haOnlyVoiceSignup },
            setter = { }
        )
        // SESSION origin: was this session minted by the kiosk provisioner? Distinct from
        // haOnlyDisplay, which is a sticky DEVICE-DISPLAY property and diverges the moment an
        // HA-displaying tablet is signed into normally. AccountPageSchema gates the kiosk
        // section on this so it describes the session, not the screen. Read-only: owned by the
        // provisioner + the login path, not the settings UI.
        vp.registerBoolean("account.kioskProvisionedSession",
            getter = { accountPrefs.kioskProvisionedSession },
            setter = { }
        )
    }

    // ── Subscription Display ────────────────────────────────────────────

    fun registerSubscriptionDisplayKeys(
        vp: HaliteSettingsValueProvider,
        context: Context
    ) {
        val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(context)

        vp.registerString("subscription.statusDisplay",
            getter = {
                when (subPrefs.subscriptionStatus) {
                    "active" -> "Active"
                    "trialing" -> {
                        val days = subPrefs.daysRemaining
                        if (days > 0) "Trial — $days day${if (days != 1) "s" else ""} remaining"
                        else "Trial expired"
                    }
                    "past_due" -> "Past Due"
                    "canceled" -> "Canceled"
                    "trial_expired" -> "Trial Expired"
                    "complimentary" -> "Complimentary"
                    else -> "None"
                }
            },
            setter = { })

        vp.registerString("subscription.planDisplay",
            getter = {
                when (subPrefs.subscriptionPlan) {
                    "dashie_monthly" -> "Monthly"
                    "dashie_annual" -> "Annual"
                    else -> if (subPrefs.subscriptionStatus == "trialing") "Free Trial" else "—"
                }
            },
            setter = { })

        vp.registerBoolean("subscription.isActive",
            getter = { subPrefs.hasActiveAccess },
            setter = { })

        vp.registerBoolean("subscription.isPaidSubscriber",
            getter = { subPrefs.subscriptionStatus in listOf("active", "complimentary") },
            setter = { })

        // D.56 — used by DisplayPageSchema to hide the Layout picker for
        // `ha_only` users (trial-expired → "Continue with HA Only"). The
        // legitimate exit path is the B.2 subscribe-restore flow, not
        // Settings.
        vp.registerBoolean("subscription.isHaOnly",
            getter = { subPrefs.subscriptionStatus == "ha_only" },
            setter = { })

        // FB27: Credits — account voice/AI credit balance on the Account page. Balance comes
        // from the CreditStateHolder cache (refreshed on demand via CreditBalanceReader); the
        // getter kicks a throttled refresh so it's fresh on the next render. hasCredits gates
        // the whole section (only shown to accounts that actually have a credit balance).
        val connPrefs = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(context)
        vp.registerString("credits.balance",
            getter = {
                com.dashieapp.Dashie.edition.EditionSeams.credits(context).refreshBalance()
                com.dashieapp.Dashie.halite.voice.CreditStateHolder.balance
                    ?.let { if (it < 0.02) "No credits" else com.dashieapp.Dashie.halite.voice.CreditStateHolder.formatBalance(it) } ?: "—"
            },
            setter = { })
        vp.registerBoolean("credits.hasCredits",
            getter = { com.dashieapp.Dashie.halite.voice.CreditStateHolder.balance != null },
            setter = { })

        // WS-L.3 P2 — auto-refill kill switch. Unlike every other registered key, the value does
        // NOT live in SharedPreferences: the server row is the sole source of truth (the console
        // writes it too), so the getter reads the same CreditStateHolder cache the balance uses.
        // The setter is deliberately a NO-OP — the schema's async toggle interceptor performs the
        // server write and only then applies the cached value, so an offline/failed flip snaps the
        // switch back instead of leaving a local lie. (setBoolean throws on unregistered keys, so
        // this registration is required even though it stores nothing.)
        vp.registerBoolean("credits.autorefillEnabled",
            getter = { com.dashieapp.Dashie.halite.voice.CreditStateHolder.autorefillEnabled },
            setter = { })
        vp.registerString("credits.autorefillSummary",
            getter = {
                val holder = com.dashieapp.Dashie.halite.voice.CreditStateHolder
                when {
                    !holder.autorefillEnabled -> "Off — top up manually"
                    holder.autorefillFailed -> "Payment failed — card needs attention"
                    else -> {
                        val below = holder.autorefillThreshold
                        val topup = holder.autorefillTopup
                        if (below != null && topup != null)
                            "When below $${String.format("%.2f", below)}, add $${String.format("%.2f", topup)}"
                        else "On"
                    }
                }
            },
            setter = { })
    }

    // ── Locations ────────────────────────────────────────────────────

    fun registerLocationsPreferences(
        vp: HaliteSettingsValueProvider,
        prefs: com.dashieapp.Dashie.halite.preferences.LocationsPreferences
    ) {
        // Booleans
        vp.registerBoolean("locations.trackingEnabled",
            getter = { prefs.trackingEnabled },
            setter = { prefs.trackingEnabled = it })
        vp.registerBoolean("locations.showRadiusCircles",
            getter = { prefs.showRadiusCircles },
            setter = { prefs.showRadiusCircles = it })
        vp.registerBoolean("locations.travelTimeEnabled",
            getter = { prefs.travelTimeEnabled },
            setter = { prefs.travelTimeEnabled = it })
        vp.registerBoolean("locations.notificationSounds",
            getter = { prefs.notificationSounds },
            setter = { prefs.notificationSounds = it })

        // Strings
        vp.registerString("locations.trafficModel",
            getter = { prefs.trafficModel },
            setter = { prefs.trafficModel = it })

        // Ints (stored as strings for picker compatibility)
        vp.registerString("locations.earlyArrivalGames",
            getter = { prefs.earlyArrivalGames.toString() },
            setter = { prefs.earlyArrivalGames = it.toIntOrNull() ?: 15 })
        vp.registerString("locations.earlyArrivalPractices",
            getter = { prefs.earlyArrivalPractices.toString() },
            setter = { prefs.earlyArrivalPractices = it.toIntOrNull() ?: 15 })
        vp.registerString("locations.earlyArrivalOther",
            getter = { prefs.earlyArrivalOther.toString() },
            setter = { prefs.earlyArrivalOther = it.toIntOrNull() ?: 0 })

        // Display values (read-only)
        vp.registerString("locations.trafficModelDisplay",
            getter = { prefs.trafficModelDisplay() },
            setter = { })
        vp.registerString("locations.earlyArrivalGamesDisplay",
            getter = { prefs.earlyArrivalDisplay(prefs.earlyArrivalGames) },
            setter = { })
        vp.registerString("locations.earlyArrivalPracticesDisplay",
            getter = { prefs.earlyArrivalDisplay(prefs.earlyArrivalPractices) },
            setter = { })
        vp.registerString("locations.earlyArrivalOtherDisplay",
            getter = { prefs.earlyArrivalDisplay(prefs.earlyArrivalOther) },
            setter = { })
    }

    // ── Chores & Rewards ────────────────────────────────────────────

    fun registerChoresRewardsPreferences(
        vp: HaliteSettingsValueProvider,
        prefs: com.dashieapp.Dashie.halite.preferences.ChoresRewardsPreferences
    ) {
        // Booleans
        vp.registerBoolean("choresRewards.choresEnabled",
            getter = { prefs.choresEnabled },
            setter = { prefs.choresEnabled = it })
        vp.registerBoolean("choresRewards.rewardsEnabled",
            getter = { prefs.rewardsEnabled },
            setter = { prefs.rewardsEnabled = it })
        vp.registerBoolean("choresRewards.anyoneEnabled",
            getter = { prefs.anyoneEnabled },
            setter = { prefs.anyoneEnabled = it })

        // Strings (for pickers)
        vp.registerString("choresRewards.upcomingDays",
            getter = { prefs.upcomingDays.toString() },
            setter = { prefs.upcomingDays = it.toIntOrNull() ?: 7 })

        // Display values (read-only)
        vp.registerString("choresRewards.participantsDisplay",
            getter = { prefs.participantsDisplay() },
            setter = { })
        vp.registerString("choresRewards.upcomingDaysDisplay",
            getter = {
                val days = prefs.upcomingDays
                if (days == 0) "Today only" else "$days day${if (days != 1) "s" else ""}"
            },
            setter = { })
    }

    // ── Calendar ─────────────────────────────────────────────────

    fun registerCalendarPreferences(
        vp: HaliteSettingsValueProvider,
        context: android.content.Context
    ) {
        val prefs = context.getSharedPreferences("dashie_calendar_prefs", android.content.Context.MODE_PRIVATE)
        vp.registerBoolean("calendar.enabled",
            getter = { prefs.getBoolean("calendar_enabled", true) },
            setter = { prefs.edit().putBoolean("calendar_enabled", it).commit() })

        // Display options
        vp.registerString("calendar.startWeekOn",
            getter = { prefs.getString("start_week_on", "sunday") ?: "sunday" },
            setter = { prefs.edit().putString("start_week_on", it).commit() })
        vp.registerString("calendar.startWeekDisplay",
            getter = { (prefs.getString("start_week_on", "sunday") ?: "sunday").replaceFirstChar { c -> c.uppercase() } },
            setter = { })
        // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
        // writeAccess mirrors startWeekOn: an ACCOUNT-level calendar key persisted
        // to dashie_calendar_prefs. Default "touch". Values: none|touch|voice|both.
        vp.registerString("calendar.writeAccess",
            getter = { prefs.getString("write_access", "touch") ?: "touch" },
            setter = { prefs.edit().putString("write_access", it).commit() })
        vp.registerString("calendar.writeAccessDisplay",
            getter = {
                when (prefs.getString("write_access", "touch") ?: "touch") {
                    "none" -> "None"
                    "voice" -> "Voice only"
                    "both" -> "Both"
                    else -> "Touch only"
                }
            },
            setter = { })
        vp.registerString("calendar.scrollTime",
            getter = { prefs.getString("scroll_time", "8") ?: "8" },
            setter = { prefs.edit().putString("scroll_time", it).commit() })
        vp.registerString("calendar.scrollTimeDisplay",
            getter = {
                val hour = (prefs.getString("scroll_time", "8") ?: "8").toIntOrNull() ?: 8
                when {
                    hour == 0 -> "12:00 AM"
                    hour < 12 -> "$hour:00 AM"
                    hour == 12 -> "12:00 PM"
                    else -> "${hour - 12}:00 PM"
                }
            },
            setter = { })
        // Vertical density preset: 'auto' (historical density) or a number of
        // hours ('6'…'24') that fills the visible calendar grid. Per-device.
        vp.registerString("calendar.hoursToShow",
            getter = { prefs.getString("hours_to_show", "auto") ?: "auto" },
            setter = { prefs.edit().putString("hours_to_show", it).commit() })
        vp.registerString("calendar.hoursToShowDisplay",
            getter = {
                val v = prefs.getString("hours_to_show", "auto") ?: "auto"
                if (v == "auto") "Auto" else "$v hours"
            },
            setter = { })
    }

    // ── Family (display keys + edit form state) ───────────────────

    object FamilyEditState {
        var name: String = ""
        var nickname: String = ""
        var role: String = "parent"
        var color: String = "#FF6B6B"
        var notes: String = ""
        var gpsEnabled: Boolean = false
        // Timer/reminder push opt-in (member_notification_preferences.notify_on_reminder).
        // Lives in a different table than the member and is immediate-apply, so it is
        // deliberately NOT part of loadFrom / snapshot / dirty-tracking — FamilySettings
        // fetches it async on editor-open and persists on toggle.
        var notifyOnReminder: Boolean = true

        val fullName: String get() = name.trim()

        fun loadFrom(member: FamilyMember) {
            name = member.fullName
            nickname = member.nickname ?: ""
            role = member.relationship
            color = member.assignedColor
            notes = member.notes ?: ""
            gpsEnabled = member.gpsEnabled
        }

        /** Snapshot of editable form fields used to detect unsaved changes (D.41). */
        data class Snapshot(
            val name: String,
            val nickname: String,
            val role: String,
            val color: String,
            val notes: String,
            val gpsEnabled: Boolean
        )

        fun snapshot(): Snapshot = Snapshot(
            name, nickname, role, color, notes, gpsEnabled
        )

        fun isDirtyFrom(s: Snapshot): Boolean =
            name != s.name
                || nickname != s.nickname
                || role != s.role
                || color != s.color
                || notes != s.notes
                || gpsEnabled != s.gpsEnabled

        fun resetForNew(usedColors: List<String> = emptyList()) {
            name = ""
            nickname = ""
            role = "parent"
            // D.37 — default to the first palette color not already assigned
            // to another member, so a new member doesn't land on a greyed-out
            // (already-used) color like #FF6B6B. Falls back to the first color
            // if every palette entry is taken.
            color = FamilyMember.COLOR_PALETTE.firstOrNull { it !in usedColors }
                ?: FamilyMember.COLOR_PALETTE.first()
            notes = ""
            gpsEnabled = false
        }
    }

    /**
     * Family-level info. The family name's source of truth is the JS
     * settingsStore key `family.familyName`; this holder caches the loaded
     * value so the `family.familyNameDisplay` value provider can return it
     * synchronously when the schema renders. Populated by
     * FamilySettings.loadFamilyNameFromJs().
     */
    object FamilyInfoState {
        var familyName: String = ""
    }

    fun registerFamilyDisplayKeys(
        vp: HaliteSettingsValueProvider,
        generalPrefs: GeneralPreferences
    ) {
        vp.registerString("family.zipCodeDisplay",
            getter = { generalPrefs.zipCode.ifEmpty { "Not set" } },
            setter = { })
        vp.registerString("family.familyNameDisplay",
            getter = { FamilyInfoState.familyName.ifEmpty { "Not set" } },
            setter = { })

        vp.registerString("family.editName",
            getter = { FamilyEditState.name },
            setter = { FamilyEditState.name = it })
        vp.registerString("family.editNickname",
            getter = { FamilyEditState.nickname },
            setter = { FamilyEditState.nickname = it })
        vp.registerString("family.editMemberRole",
            getter = { FamilyEditState.role.replaceFirstChar { c -> c.uppercase() } },
            setter = { FamilyEditState.role = it })
        vp.registerString("family.editMemberColor",
            getter = { FamilyEditState.color },
            setter = { FamilyEditState.color = it })
        vp.registerString("family.editNotes",
            getter = { FamilyEditState.notes },
            setter = { FamilyEditState.notes = it })
        vp.registerBoolean("family.editGpsEnabled",
            getter = { FamilyEditState.gpsEnabled },
            setter = { FamilyEditState.gpsEnabled = it })
        vp.registerBoolean("family.editNotifyOnReminder",
            getter = { FamilyEditState.notifyOnReminder },
            setter = { FamilyEditState.notifyOnReminder = it })
        vp.registerString("family.editMemberColorDisplay",
            getter = {
                when (FamilyEditState.color) {
                    "#FF6B6B" -> "Red"; "#4ECDC4" -> "Teal"; "#45B7D1" -> "Blue"
                    "#FFA07A" -> "Salmon"; "#98D8C8" -> "Mint"; "#F7DC6F" -> "Yellow"
                    "#BB8FCE" -> "Purple"; "#85C1E2" -> "Sky Blue"; "#F8B739" -> "Orange"
                    "#52B788" -> "Green"; else -> FamilyEditState.color
                }
            },
            setter = { })
    }
}
