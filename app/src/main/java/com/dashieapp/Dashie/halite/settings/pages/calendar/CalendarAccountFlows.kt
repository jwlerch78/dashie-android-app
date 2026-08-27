package com.dashieapp.Dashie.halite.settings.pages.calendar

import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.showStyledConfirmDialog

/*
 * The calendar-account flows that exist in BOTH editions.
 *
 * The cloud ones (Google / Microsoft / Apple add-account and re-auth) moved to
 * `src/dashie/java/.../CloudCalendarFlowsImpl.kt` on 2026-08-02 so they are ABSENT from the
 * Chickadee artifact rather than unreachable inside it — see [CloudCalendarFlows].
 *
 * 🔴 [removeCalendarAccount] stayed on purpose, and it is the interesting one: it serves the
 * Home Assistant account too (`CalendarBridgeWiring.kt:141` builds it with `accountType: 'ha'`,
 * and the remove screen registers a callback per account). Moving it would have left a Chickadee
 * household able to import HA calendars and never able to remove them — and since that
 * registration is by string through the callback registry, the regression would have COMPILED.
 */

internal fun SettingsActivity.removeCalendarAccount(provider: String, accountType: String, email: String) {
    val displayName = email.ifEmpty { "$provider $accountType" }

    showStyledConfirmDialog(
        title = "Remove Account",
        message = "Remove $displayName and all its calendars from the dashboard?",
        confirmLabel = "Remove",
        isDestructive = true
    ) {
        val wv = SettingsActivity.webViewRef?.get() ?: return@showStyledConfirmDialog
        val dashieUrl = com.dashieapp.Dashie.BuildConfig.DASHIE_URL
        val isCalDav = provider == "caldav" || provider == "icloud"
        wv.post {
            // BUNDLE-EXEMPT: tokenStore — calendar settings are webapp-backed; absent service fails loud via DashieNative.onCalendarError
            wv.evaluateJavascript("""
                (async () => {
                    try {
                        const isCalDav = $isCalDav;
                        if (isCalDav) {
                            // CalDAV accounts live in user_caldav_accounts, not user_auth_tokens.
                            // accountType arrives as 'caldav-primary' etc; strip the prefix for the CalDAV API.
                            const { default: caldavClient } = await import('$dashieUrl/js/data/services/caldav/caldav-client.js');
                            const bareType = '$accountType'.replace(/^caldav-/, '');
                            await caldavClient.deleteAccount(bareType);
                        } else {
                            const tokenStore = window.tokenStore;
                            if (!tokenStore) throw new Error('Token store not initialized');
                            // accountType arrives provider-prefixed for Microsoft
                            // (e.g. 'microsoft-primary'), but the token slot key is
                            // the bare form ('primary'). removeAccountTokens
                            // silently no-ops on a key miss, so without stripping
                            // the prefix the Microsoft token is never deleted — the
                            // account keeps ghosting in every account list even
                            // though its calendars were removed. Google
                            // accountTypes are already bare. The activeCalendarIds
                            // prefix below intentionally keeps the full form:
                            // calendar IDs are 'microsoft-primary-…'.
                            const bareType = '$accountType'.replace(/^microsoft-/, '');
                            await tokenStore.removeAccountTokens('$provider', bareType);
                        }

                        const cs = window.calendarService;
                        if (cs && cs.activeCalendarIds) {
                            const prefix = '$accountType-';
                            const remaining = cs.activeCalendarIds.filter(id => !id.startsWith(prefix));
                            cs.activeCalendarIds = remaining;
                            await cs.saveActiveCalendars();
                        }

                        window.DashieNative.onCalendarToggled();
                    } catch (e) {
                        window.DashieNative.onCalendarError(e.message || 'Failed to remove account');
                    }
                })()
            """.trimIndent(), null)
        }

        // Pop back to main calendar page
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        }
    }
}

internal fun SettingsActivity.importHomeAssistantCalendars() {
    val wv = SettingsActivity.webViewRef?.get()
    if (wv == null) {
        android.widget.Toast.makeText(this, "WebView not available", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    android.widget.Toast.makeText(this, "Importing HA calendars…", android.widget.Toast.LENGTH_SHORT).show()

    // Sync HA connection settings to JS using DashieNative.getHaConnectionSettings()
    // (same pattern as kiosk/full mode startup sync in auth-initializer.js)
    // then discover and enable HA calendars.
    wv.post {
        // BUNDLE-EXEMPT: haService — calendar settings are webapp-backed; absent service fails loud via DashieNative.onCalendarError
        wv.evaluateJavascript("""
            (async () => {
                try {
                    // Read HA config from native bridge and sync to settingsStore + haService
                    const haJson = window.DashieNative?.getHaConnectionSettings?.();
                    if (haJson) {
                        const ha = JSON.parse(haJson);
                        if (window.settingsStore && ha.enabled) {
                            window.settingsStore.set('home_assistant.enabled', true);
                            if (ha.url) window.settingsStore.set('home_assistant.url', ha.url);
                            if (ha.base_url) window.settingsStore.set('home_assistant.base_url', ha.base_url);
                            if (ha.access_token) window.settingsStore.set('home_assistant.access_token', ha.access_token);
                        }
                        if (window.haService && ha.enabled) {
                            window.haService.configure({
                                enabled: true,
                                url: ha.url,
                                base_url: ha.base_url,
                                access_token: ha.access_token
                            });
                        }
                    }
                    const calSvc = window.calendarService;
                    if (!calSvc) throw new Error('Calendar service not available');
                    const calendars = await calSvc.getCalendars('ha', true);
                    if (calendars.length === 0) {
                        window.DashieNative.onCalendarError('No calendar entities found in Home Assistant. Make sure you have a calendar integration set up in HA.');
                        return;
                    }
                    let enabled = 0;
                    for (const cal of calendars) {
                        if (!calSvc.isCalendarActive('ha', cal.id)) {
                            await calSvc.enableCalendar('ha', cal.id);
                            enabled++;
                        }
                    }
                    const msg = enabled > 0
                        ? 'Found ' + calendars.length + ' calendar(s). ' + enabled + ' new calendar(s) enabled.'
                        : 'Found ' + calendars.length + ' calendar(s) (all already enabled).';
                    window.DashieNative.onCalendarImportSuccess(msg);
                } catch (e) {
                    window.DashieNative.onCalendarError('Import failed: ' + e.message);
                }
            })()
        """.trimIndent(), null)
    }
}

