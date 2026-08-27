package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the consolidated Display settings page.
 *
 * Sections:
 * 1. Dashboard — Layout mode, Theme
 * 2. Screen Management — Sleep Mode, Screensaver, Wake Mode, Calibration
 * 3. Display Preferences — Dashboard Zoom, Sidebar Icon Size, Screen Off, Auto Brightness
 */
object DisplayPageSchema {

    // Per-account dashboard items (Layout, Theme, Animations) are visible
    // whenever the user has a Dashie account, regardless of their current
    // layout mode. Previously gated on `display.layoutMode != 'kiosk'`,
    // which created a chicken-and-egg trap: signed-in users in kiosk mode
    // (e.g. trial-expired users who picked "Continue with HA Only") had no
    // way to switch back to widgets/single-panel because the Layout entry
    // was hidden. Account.isLinked is the correct gate per the schema's
    // intent: hide only in pure kiosk (no Dashie account at all).
    private val hasDashieAccount = Condition.IsTrue("account.isLinked")

    // D.56 — Dashie cloud "Dashboard" items (Layout, Theme, Animations,
    // Animation Level) are hidden when the user is in `ha_only` mode
    // (trial-expired → "Continue with HA Only"). The legitimate path
    // back to a Dashie experience is the B.2 subscribe-restore flow,
    // not Settings. Without this gate the user could escape ha_only
    // kiosk lockdown by switching Layout to Widgets via Settings —
    // which also corrupted the `kiosk_from_ha_only` flag (see D.56 in
    // the beta plan). The visibility shape mirrors the not-signed-in
    // case: no Dashie features visible in Settings.
    private val notHaOnly = Condition.IsFalse("subscription.isHaOnly")

    /**
     * 🔴 Product decision 2026-08-21: *"widget layout and themes can't work without a full paying
     * account, though. This is a shared voice account. There are no widgets active."* Reported
     * from a shared-voice-account device in HA mode showing Layout, Themes and Font Size.
     *
     * [hasDashieAccount] + [notHaOnly] BOTH PASS on such a device — it is linked, and its
     * subscription status is not `ha_only` — so the gate conflated "has an account" with "has
     * widgets to configure".
     *
     * `account.haOnlyDisplay` is the discriminator, and it is two-sided safe, which matters here
     * because over-gating strips Layout/Theme from a paying family. Per its own KDoc it is
     * DEVICE-scoped and *"set once when a kiosk provisions itself… **never set for a full-app
     * device**"*:
     *  - shared voice account / self-provisioned kiosk → true  → widget rows hidden (the fix)
     *  - paying family device sitting in kiosk LAYOUT mode   → false → Layout/Theme still shown,
     *    so the `:20-38` chicken-and-egg rationale (switching back to widgets) is fully preserved
     *
     * 📌 Deliberately NOT `FeatureVisibilityPreferences.isHidden(<widget feature>)`, which the
     * handoff spec proposed: measured against `feature_access` on BOTH staging and prod
     * (2026-08-21), there is no widgets/dashboard row — the table holds 12 per-FEATURE ids
     * (calendar, photos, chores, …) and nothing representing the dashboard surface itself.
     * Inventing one would have needed a min_tier ruling, and gating on that snapshot would also
     * have inherited the un-deployed `ccb6a9ac5` refresh bug. This predicate needs neither.
     */
    private val notSharedVoiceKiosk = Condition.IsFalse("account.haOnlyDisplay")

    private val dashieDashboardVisible = Condition.And(listOf(
        hasDashieAccount,
        notHaOnly,
        notSharedVoiceKiosk
    ))

    // Wake mode conditions (for calibration items on the wake mode sub-screen)
    private val isCameraMode = Condition.Equals("display.motionWakeMode", "camera")
    private val isFaceMode = Condition.Equals("display.motionWakeMode", "face")

    /** Advanced tablet-control visibility — see AdvancedPageSchema for the full rationale. */
    private val advancedVisible = Condition.Or(listOf(
        Condition.IsTrue("home_assistant.enabled"),
        Condition.IsTrue("advanced.tabletControlsEnabled")
    ))

    /**
     * @param isHaEnabled controls whether the "Kiosk mode (HA-only)" picker
     *   option appears — kiosk requires a configured HA URL to land on.
     * @param isDevFlavor controls whether "Canvas (Dev)" is offered — only
     *   in the `dev` build flavor, hidden in staging + prod.
     */
    fun create(isHaEnabled: Boolean = false, isDevFlavor: Boolean = false, isTv: Boolean = false) = SettingsPageSchema(
        id = "display",
        title = "Display",
        sections = listOf(
            dashboardSection(),
            screenBehaviorSection(),
            displayPreferencesSection()
        ),
        subScreens = mapOf(
            "display-layout-mode" to layoutModeSubScreen(isHaEnabled, isDevFlavor),
            "display-orientation-lock" to orientationLockSubScreen(),
            "display-theme" to themeSubScreen(),
            "display-animation-level" to animationLevelSubScreen(),
            "display-dashboard-zoom" to dashboardZoomSubScreen(),
            "display-widget-zoom" to widgetZoomSubScreen(),
            "display-font-size" to fontSizeSubScreen(),
            "display-display-size" to displaySizeSubScreen(),
            "display-sidebar-icon-size" to sidebarIconSizeSubScreen(),
            "display-screen-off-behavior" to screenOffBehaviorSubScreen(isTv)
        )
    )

    // ── Section 1: Dashboard ──────────────────────────────────────────

    private fun dashboardSection() = SettingsSection(
        header = "Dashboard",
        // D.56 follow-up — gate the entire Dashboard section, including
        // the "DASHBOARD" header. Previously items were individually
        // gated on hasDashieAccount; the section header rendered even
        // when all items were hidden (visible orphan), and ha_only users
        // saw Theme / Animations / Animation Level since those weren't
        // ha_only-aware.
        visibleWhen = dashieDashboardVisible,
        items = listOf(
            SchemaItem.Navigation(
                id = "layout_mode",
                label = "Layout",
                navigateTo = "display-layout-mode",
                displayValueKey = "display.layoutModeDisplay",
                directPicker = true
            ),
            // Orientation lock — only meaningful in Widgets mode. Single
            // Panel + Kiosk follow whatever orientation the device is in.
            // Hidden on TVs: their HDMI output is fixed landscape (no
            // rotatable display / accelerometer), so a portrait lock can't
            // truly rotate — it only pillar-boxes a portrait window into the
            // landscape frame. Portrait is a tablet-only feature for now.
            SchemaItem.Navigation(
                id = "orientation_lock",
                label = "Orientation",
                navigateTo = "display-orientation-lock",
                displayValueKey = "display.orientationLockDisplay",
                directPicker = true,
                visibleWhen = Condition.And(listOf(
                    Condition.Equals("display.layoutMode", "widgets"),
                    Condition.IsFalse("device.isTv")
                ))
            ),
            SchemaItem.Navigation(
                id = "theme",
                label = "Theme",
                navigateTo = "display-theme",
                displayValueKey = "display.themeDisplay",
                directPicker = true
            ),
            SchemaItem.Toggle(
                id = "animations_enabled",
                label = "Animations",
                settingKey = "display.animationsEnabled",
                onChanged = "notifyAnimationsEnabledChanged",
                // Default theme has no themed animations to enable, so the
                // toggle is meaningless there. Hide it (and Animation Level
                // below) until the user picks a non-default theme.
                visibleWhen = Condition.NotEquals("display.themeFamily", "default")
            ),
            SchemaItem.Navigation(
                id = "animation_level",
                label = "Animation Level",
                navigateTo = "display-animation-level",
                displayValueKey = "display.animationLevelDisplay",
                directPicker = true,
                visibleWhen = Condition.And(listOf(
                    Condition.NotEquals("display.themeFamily", "default"),
                    Condition.IsTrue("display.animationsEnabled")
                ))
            )
        )
    )

    // ── Section 2: Screen Behavior ────────────────────────────────────

    private fun screenBehaviorSection() = SettingsSection(
        header = "Screen Management",
        items = listOf(
            SchemaItem.Navigation(
                id = "sleep_mode",
                label = "Sleep Mode",
                navigateTo = "ext:sleep_page",
                displayValueKey = "display.sleepSummary"
            ),
            SchemaItem.Navigation(
                id = "screensaver",
                label = "Screensaver",
                navigateTo = "ext:screensaver_page",
                displayValueKey = "display.screensaverSummary"
            ),
            SchemaItem.Navigation(
                id = "wake_mode",
                label = "Wake Mode",
                navigateTo = "ext:wake_mode_page",
                displayValueKey = "display.wakeModeSummary",
                visibleWhen = advancedVisible
            )
        )
    )

    // ── Section 3: Display Preferences ────────────────────────────────

    private fun displayPreferencesSection() = SettingsSection(
        header = "Display Preferences",
        items = listOf(
            SchemaItem.Navigation(
                id = "dashboard_zoom",
                label = "HA Dashboard Zoom",
                navigateTo = "display-dashboard-zoom",
                displayValueKey = "display.dashboardZoomDisplay",
                directPicker = true,
                // Only meaningful when HA is configured — HA-only users
                // expect this control to be present, full-Dashie users
                // without HA see only the Widget Zoom row below.
                visibleWhen = Condition.IsTrue("home_assistant.enabled")
            ),
            SchemaItem.Navigation(
                id = "widget_zoom",
                label = "Widget Zoom",
                navigateTo = "display-widget-zoom",
                displayValueKey = "display.widgetZoomDisplay",
                directPicker = true,
                // Hidden in kiosk mode — kiosk shows only HA, so Dashie
                // core widgets (calendar/chores/rewards/locations) aren't
                // on screen and Widget Zoom would be a dead control.
                // ⚠️ layoutMode alone LEAKS (found 2026-08-21): it is a SYNCED key, so on a
                // household device it carries the PRIMARY device's value (often "widgets")
                // while this device displays HA via account.haOnlyDisplay. Checking the
                // device-scoped flag too is what makes the gate describe THIS device.
                visibleWhen = Condition.And(listOf(
                    Condition.NotEquals("display.layoutMode", "kiosk"),
                    notSharedVoiceKiosk
                ))
            ),
            SchemaItem.Navigation(
                id = "font_size",
                label = "Font Size",
                navigateTo = "display-font-size",
                displayValueKey = "display.widgetFontSizeDisplay",
                directPicker = true,
                // Scales web widget text (calendar, chores, weather, photos).
                // Like Widget Zoom, only meaningful when Dashie widgets show — and with the same
                // synced-layoutMode leak, so it takes the same device-scoped second condition.
                visibleWhen = Condition.And(listOf(
                    Condition.NotEquals("display.layoutMode", "kiosk"),
                    notSharedVoiceKiosk
                ))
            ),
            SchemaItem.Navigation(
                id = "display_size",
                label = "Display Size",
                navigateTo = "display-display-size",
                displayValueKey = "display.displaySizeDisplay",
                directPicker = true
                // Scales native chrome/overlays (sidebar, control center, music/
                // video/timer/voice). Useful on every device class, so no kiosk gate.
                // DECIDED DELIBERATELY, 2026-08-21 (it was flagged beside the widget
                // rows): it stays UNGATED. Unlike Layout/Theme/Font Size/Widget Zoom this scales
                // NATIVE chrome, and a shared-voice kiosk has all of it — sidebar, control
                // center, the voice overlay. It is not a widget-scoped control, so the ruling
                // ("there are no widgets active") does not reach it.
            ),
            SchemaItem.Navigation(
                id = "sidebar_icon_size",
                label = "Sidebar Icon Size",
                navigateTo = "display-sidebar-icon-size",
                displayValueKey = "display.sidebarIconSizeDisplay",
                directPicker = true
            ),
            SchemaItem.Navigation(
                id = "screen_off_behavior",
                label = "Screen Off Behavior",
                navigateTo = "display-screen-off-behavior",
                displayValueKey = "display.screenOffBehaviorDisplay",
                directPicker = true,
                visibleWhen = advancedVisible
            ),
            SchemaItem.Toggle(
                id = "auto_brightness_enabled",
                label = "Auto Brightness",
                sublabel = "Adjust brightness using light sensor",
                settingKey = "display.autoBrightnessEnabled",
                visibleWhen = advancedVisible
            ),
            SchemaItem.Action(
                id = "configure_auto_brightness",
                label = "Configure Auto-Brightness",
                action = "openAutoBrightnessSettings",
                visibleWhen = Condition.And(listOf(
                    advancedVisible,
                    Condition.IsTrue("display.autoBrightnessEnabled")
                ))
            )
        )
    )

    // ── Sub-screens ───────────────────────────────────────────────────

    private fun layoutModeSubScreen(isHaEnabled: Boolean, isDevFlavor: Boolean) = SubScreenSchema(
        title = "Layout",
        parent = "display",
        sections = listOf(
            SettingsSection(
                footer = "Choose how your dashboard displays. Widgets is the standard grid layout. Single Panel shows one widget at a time full-screen. Kiosk mode shows only Home Assistant dashboards.",
                items = listOf(
                    SchemaItem.Picker(
                        id = "layout_mode_picker",
                        label = "Layout",
                        settingKey = "display.layoutMode",
                        // D.51 — Legacy removed entirely; Kiosk requires HA;
                        // Canvas is dev-flavor only. Build the option list
                        // conditionally; same buildList pattern as
                        // PhotosPageSchema.sourceSection.
                        options = buildList {
                            add(SchemaPickerOption("widgets", "Widgets"))
                            add(SchemaPickerOption("single_panel", "Single Panel"))
                            if (isHaEnabled) {
                                add(SchemaPickerOption("kiosk", "Kiosk mode (HA-only)"))
                            }
                            if (isDevFlavor) {
                                add(SchemaPickerOption("canvas", "Canvas (Dev)"))
                            }
                        },
                        onChanged = "notifyLayoutModeChanged"
                    )
                )
            )
        )
    )

    private fun orientationLockSubScreen() = SubScreenSchema(
        title = "Orientation",
        parent = "display",
        sections = listOf(
            SettingsSection(
                footer = "Auto follows the device's rotation sensor. The fixed orientations lock the dashboard regardless of how the device is held — useful for wall-mounted TVs and tablets without a usable accelerometer. \"Reverse\" variants flip 180° so you can mount the device upside-down without flipping content too.",
                items = listOf(
                    SchemaItem.Picker(
                        id = "orientation_lock_picker",
                        label = "Orientation",
                        settingKey = "display.orientationLock",
                        options = listOf(
                            SchemaPickerOption("auto", "Auto"),
                            SchemaPickerOption("landscape", "Landscape"),
                            SchemaPickerOption("landscape_reverse", "Landscape (reversed)"),
                            SchemaPickerOption("portrait", "Portrait"),
                            SchemaPickerOption("portrait_reverse", "Portrait (reversed)")
                        ),
                        onChanged = "notifyOrientationLockChanged"
                    )
                )
            )
        )
    )

    private fun themeSubScreen() = SubScreenSchema(
        title = "Theme",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "theme_family_picker",
                        label = "Theme",
                        settingKey = "display.themeFamily",
                        options = listOf(
                            SchemaPickerOption("default", "Default"),
                            SchemaPickerOption("blue", "Blue"),
                            SchemaPickerOption("halloween", "Halloween"),
                            SchemaPickerOption("christmas", "Christmas")
                        ),
                        onChanged = "notifyThemeFamilyChanged"
                    )
                ),
                footer = "Seasonal themes (Halloween, Christmas) auto-activate during their respective months."
            ),
            SettingsSection(
                items = listOf(
                    SchemaItem.Toggle(
                        id = "dark_mode_toggle",
                        label = "Dark Mode",
                        settingKey = "display.darkMode",
                        onChanged = "notifyDarkModeChanged"
                    )
                )
            )
        )
    )

    private fun animationLevelSubScreen() = SubScreenSchema(
        title = "Animation Level",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "animation_level_picker",
                        label = "Animation Level",
                        settingKey = "display.animationLevel",
                        options = listOf(
                            SchemaPickerOption("high", "High"),
                            SchemaPickerOption("low", "Low")
                        ),
                        onChanged = "notifyAnimationLevelChanged"
                    )
                )
            )
        )
    )

    private fun dashboardZoomSubScreen() = SubScreenSchema(
        title = "HA Dashboard Zoom",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "dashboard_zoom_picker",
                        label = "Zoom Level",
                        settingKey = "display.dashboardZoom",
                        options = listOf(
                            SchemaPickerOption("25", "25%"),
                            SchemaPickerOption("50", "50%"),
                            SchemaPickerOption("75", "75%"),
                            SchemaPickerOption("90", "90%"),
                            SchemaPickerOption("100", "100%"),
                            SchemaPickerOption("110", "110%"),
                            SchemaPickerOption("125", "125%"),
                            SchemaPickerOption("150", "150%"),
                            SchemaPickerOption("175", "175%"),
                            SchemaPickerOption("200", "200%"),
                            SchemaPickerOption("250", "250%"),
                            SchemaPickerOption("300", "300%"),
                            SchemaPickerOption("custom", "Custom", isCustomInput = true)
                        ),
                        onChanged = "notifyZoomChanged",
                        customInputPlaceholder = "10–300"
                    )
                )
            )
        )
    )

    private fun widgetZoomSubScreen() = SubScreenSchema(
        title = "Widget Zoom",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "widget_zoom_picker",
                        label = "Zoom Level",
                        settingKey = "display.widgetZoom",
                        options = listOf(
                            SchemaPickerOption("50", "50%"),
                            SchemaPickerOption("75", "75%"),
                            SchemaPickerOption("90", "90%"),
                            SchemaPickerOption("100", "100%"),
                            SchemaPickerOption("110", "110%"),
                            SchemaPickerOption("125", "125%"),
                            SchemaPickerOption("150", "150%"),
                            SchemaPickerOption("175", "175%"),
                            SchemaPickerOption("200", "200%"),
                            SchemaPickerOption("custom", "Custom", isCustomInput = true)
                        ),
                        onChanged = "notifyWidgetZoomChanged"
                    )
                )
            )
        )
    )

    private fun fontSizeSubScreen() = SubScreenSchema(
        title = "Font Size",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "font_size_picker",
                        label = "Font Size",
                        settingKey = "display.widgetFontSize",
                        options = listOf(
                            SchemaPickerOption("75", "75%"),
                            SchemaPickerOption("100", "100%"),
                            SchemaPickerOption("125", "125%"),
                            SchemaPickerOption("150", "150%"),
                            SchemaPickerOption("175", "175%"),
                            SchemaPickerOption("200", "200%"),
                            SchemaPickerOption("custom", "Custom", isCustomInput = true)
                        ),
                        onChanged = "notifyFontSizeChanged"
                    )
                )
            )
        )
    )

    private fun displaySizeSubScreen() = SubScreenSchema(
        title = "Display Size",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "display_size_picker",
                        label = "Display Size",
                        settingKey = "display.displaySize",
                        options = listOf(
                            SchemaPickerOption("100", "100%"),
                            SchemaPickerOption("125", "125%"),
                            SchemaPickerOption("150", "150%"),
                            SchemaPickerOption("175", "175%"),
                            SchemaPickerOption("200", "200%"),
                            SchemaPickerOption("custom", "Custom", isCustomInput = true)
                        ),
                        onChanged = "notifyDisplaySizeChanged"
                    )
                )
            )
        )
    )

    private fun sidebarIconSizeSubScreen() = SubScreenSchema(
        title = "Sidebar Icon Size",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "sidebar_icon_size_picker",
                        label = "Icon Size",
                        settingKey = "display.sidebarIconSize",
                        options = listOf(
                            SchemaPickerOption("0.75", "Very Small"),
                            SchemaPickerOption("0.9", "Small"),
                            SchemaPickerOption("1", "Medium"),
                            SchemaPickerOption("1.15", "Large"),
                            SchemaPickerOption("1.3", "Extra Large")
                        ),
                        onChanged = "notifySidebarIconSizeChanged"
                    )
                )
            )
        )
    )

    private fun screenOffBehaviorSubScreen(isTv: Boolean) = SubScreenSchema(
        title = "Screen Off Behavior",
        parent = "display",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "screen_off_picker",
                        label = "Behavior",
                        settingKey = "display.screenOffBehavior",
                        // Black Overlay leaves the LED backlight on — on TVs
                        // that's indistinguishable from "do nothing" and not
                        // a useful screen-off behavior. Only offer Power Off.
                        options = buildList {
                            if (!isTv) {
                                add(SchemaPickerOption("black_overlay", "Black Overlay",
                                    "Screen goes black but LED backlight stays on"))
                            }
                            add(SchemaPickerOption("power_off", "Power Off Screen",
                                "Turns off display hardware (saves power)"))
                        },
                        onChanged = "notifyScreenOffChanged"
                    )
                )
            )
        )
    )

}
