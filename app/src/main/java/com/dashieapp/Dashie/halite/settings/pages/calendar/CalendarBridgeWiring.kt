package com.dashieapp.Dashie.halite.settings.pages.calendar

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity

internal fun SettingsActivity.loadCalendarDataFromJs() {
    val wv = SettingsActivity.webViewRef?.get() ?: run {
        calendarLoading = false
        return
    }
    val dashieUrl = com.dashieapp.Dashie.BuildConfig.DASHIE_URL
    wv.post {
        // Use window.calendarService (already initialized by service-initializer.js)
        // and calendarService.getAllAccountCalendars() or manual iteration.
        // tokenStore is not on window, but calendarService has getCalendars() which
        // handles tokens internally. We use listAllAccounts() from tokenStore via
        // the calendarService's internal reference.
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    if (!cs) {
                        window.DashieNative.onCalendarError('Calendar service not initialized');
                        return;
                    }

                    const tokenStore = window.tokenStore;
                    if (!tokenStore) {
                        window.DashieNative.onCalendarError('Token store not initialized');
                        return;
                    }

                    // Two-phase load. Phase 1 reads tokenStore (in-memory) and
                    // pushes account metadata (email, provider, authInvalid)
                    // immediately so the Kotlin schema can render the
                    // "Sign-in Required" section without waiting for any
                    // network calls. Phase 2 fetches calendars in parallel
                    // and re-pushes the full result.
                    const googleAccounts = await tokenStore.getProviderAccounts('google');
                    const msAccounts = await tokenStore.getProviderAccounts('microsoft');

                    const phase1 = [];
                    for (const [acctType, tokens] of Object.entries(googleAccounts || {})) {
                        phase1.push({
                            accountType: acctType,
                            provider: 'google',
                            email: tokens?.email || '',
                            authInvalid: !!tokens?.auth_invalid,
                            calendars: []
                        });
                    }
                    for (const [acctType, tokens] of Object.entries(msAccounts || {})) {
                        phase1.push({
                            accountType: 'microsoft-' + acctType,
                            provider: 'microsoft',
                            email: tokens?.email || '',
                            authInvalid: !!tokens?.auth_invalid,
                            calendars: []
                        });
                    }
                    // Fast push so the user sees account list + auth_invalid
                    // status right away. Calendar lists arrive in phase 2 via
                    // the regular onCalendarDataLoaded path; this callback
                    // doesn't flip calendarLoading=false so the calendar
                    // summary stays as "Loading..." rather than "0 of 0".
                    window.DashieNative.onCalendarMetadataLoaded(JSON.stringify(phase1));

                    // Phase 2: fetch calendars in parallel.
                    const fetchGoogle = Object.entries(googleAccounts || {}).map(async ([acctType, tokens]) => {
                        try {
                            const cals = tokens?.auth_invalid ? [] : (await cs.getCalendars(acctType) || []);
                            return {
                                accountType: acctType,
                                provider: 'google',
                                email: tokens?.email || '',
                                authInvalid: !!tokens?.auth_invalid,
                                calendars: cals.map(c => {
                                    const pid = c.prefixedId || (acctType + '-' + c.id);
                                    return {
                                        prefixedId: pid,
                                        rawId: c.rawId || c.id,
                                        summary: c.summary || c.name || 'Unnamed',
                                        backgroundColor: c.backgroundColor || '#4285F4',
                                        isActive: c.isActive !== undefined ? c.isActive : true,
                                        editable: cs.isCalendarEditable ? cs.isCalendarEditable(pid, c.accessRole || null) : true,
                                        providerReadOnly: c.accessRole === 'reader' || c.accessRole === 'freeBusyReader'
                                    };
                                })
                            };
                        } catch (e) {
                            console.warn('Calendar load error for ' + acctType, e);
                            return {
                                accountType: acctType,
                                provider: 'google',
                                email: tokens?.email || '',
                                authInvalid: !!tokens?.auth_invalid,
                                calendars: []
                            };
                        }
                    });

                    const fetchMs = Object.entries(msAccounts || {}).map(async ([acctType, tokens]) => {
                        try {
                            const cals = tokens?.auth_invalid ? [] : (await cs.getCalendars('microsoft-' + acctType) || []);
                            return {
                                accountType: 'microsoft-' + acctType,
                                provider: 'microsoft',
                                email: tokens?.email || '',
                                authInvalid: !!tokens?.auth_invalid,
                                calendars: cals.map(c => {
                                    const pid = c.prefixedId || ('microsoft-' + acctType + '-' + c.id);
                                    return {
                                        prefixedId: pid,
                                        rawId: c.rawId || c.id,
                                        summary: c.summary || c.name || 'Unnamed',
                                        backgroundColor: c.backgroundColor || '#0078D4',
                                        isActive: c.isActive !== undefined ? c.isActive : true,
                                        editable: cs.isCalendarEditable ? cs.isCalendarEditable(pid, c.accessRole || null) : true,
                                        providerReadOnly: c.accessRole === 'reader' || c.accessRole === 'freeBusyReader'
                                    };
                                })
                            };
                        } catch (e) {
                            console.warn('MS Calendar load error for ' + acctType, e);
                            return {
                                accountType: 'microsoft-' + acctType,
                                provider: 'microsoft',
                                email: tokens?.email || '',
                                authInvalid: !!tokens?.auth_invalid,
                                calendars: []
                            };
                        }
                    });

                    const fetchHa = (async () => {
                        try {
                            if (window.haService && window.haService.isEnabled()) {
                                const haCals = await cs.getCalendars('ha');
                                if (haCals && haCals.length > 0) {
                                    return [{
                                        accountType: 'ha',
                                        provider: 'ha',
                                        email: 'Home Assistant',
                                        calendars: haCals.map(c => {
                                            const pid = c.prefixedId || ('ha-' + c.id);
                                            return {
                                                prefixedId: pid,
                                                rawId: c.rawId || c.id,
                                                summary: c.summary || c.name || 'Unnamed',
                                                backgroundColor: cs.getCalendarColor(pid, c.backgroundColor || ''),
                                                isActive: c.isActive !== undefined ? c.isActive : true,
                                                editable: cs.isCalendarEditable ? cs.isCalendarEditable(pid, null) : true
                                            };
                                        })
                                    }];
                                }
                            }
                        } catch (e) { console.warn('HA Calendar load error', e); }
                        return [];
                    })();

                    const fetchCaldav = (async () => {
                        try {
                            const { default: caldavClient } = await import('$dashieUrl/js/data/services/caldav/caldav-client.js');
                            const caldavAccounts = await caldavClient.listAccounts();
                            const out = [];
                            for (const acc of caldavAccounts) {
                                let cals = [];
                                try {
                                    const fetched = await cs.getCalendars('caldav-' + acc.accountType);
                                    cals = Array.isArray(fetched) ? fetched : [];
                                } catch (inner) {
                                    console.warn('CalDAV load error for ' + acc.accountType, inner);
                                }
                                out.push({
                                    accountType: 'caldav-' + acc.accountType,
                                    provider: acc.provider || 'caldav',
                                    email: acc.email || '',
                                    calendars: cals.map(c => {
                                        const pid = c.prefixedId || ('caldav-' + acc.accountType + '-' + c.id);
                                        return {
                                            prefixedId: pid,
                                            rawId: c.rawId || c.id,
                                            summary: c.summary || c.name || 'Unnamed',
                                            backgroundColor: cs.getCalendarColor(pid, c.backgroundColor || ''),
                                            isActive: c.isActive !== undefined ? c.isActive : true,
                                            editable: cs.isCalendarEditable ? cs.isCalendarEditable(pid, null) : true
                                        };
                                    })
                                });
                            }
                            return out;
                        } catch (e) { console.debug('CalDAV module not available', e); return []; }
                    })();

                    const [googleRes, msRes, haRes, caldavRes] = await Promise.all([
                        Promise.all(fetchGoogle),
                        Promise.all(fetchMs),
                        fetchHa,
                        fetchCaldav
                    ]);
                    const result = [...googleRes, ...msRes, ...haRes, ...caldavRes];
                    window.DashieNative.onCalendarDataLoaded(JSON.stringify(result));
                } catch (e) {
                    window.DashieNative.onCalendarError(e.message || 'Failed to load calendars');
                }
            })()
        """.trimIndent(), null)
    }
}
internal fun SettingsActivity.wireCalendarBridgeCallbacks() {
    val delegate = SettingsActivity.jsBridgeRef?.settingsDataDelegate ?: return

    // Helper: register the per-account re-auth action callback for any
    // account marked auth_invalid. Idempotent (CallbackRegistry replaces
    // an existing key) so it's safe to call from both phase-1 and phase-2
    // load handlers.
    val registerReauthCallbacks = { accts: List<com.dashieapp.Dashie.halite.settings.data.CalendarAccount> ->
        // Cloud-only, via the seam — the implementation is absent in an account-free edition.
        // Safe by construction as well as by gate: an HA account can never reach here, because
        // the account object built below never sets `authInvalid` and this filters on it.
        val cloudFlows = com.dashieapp.Dashie.edition.EditionSeams.cloudCalendarFlows
        accts.filter { it.authInvalid }.forEach { account ->
            val bareType = account.accountType.removePrefix("microsoft-")
            cloudFlows?.registerReauthCallback(this, account.provider, bareType, account.email)
        }
    }

    // Phase 1: metadata-only push. Account list + authInvalid is set so the
    // schema can render "Sign-in Required" instantly, but calendarLoading
    // stays TRUE — the calendarSummary keeps reading "Loading..." rather
    // than "0 of 0 active" until phase 2 arrives.
    delegate.onCalendarMetadataLoaded = { accounts ->
        calendarAccounts = accounts
        registerReauthCallbacks(accounts)
        runOnUiThread {
            supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
        }
    }

    delegate.onCalendarDataLoaded = { accounts ->
        calendarAccounts = accounts
        calendarLoading = false
        // Register dynamic toggle keys for each calendar's active state
        accounts.forEach { account ->
            account.calendars.forEach { cal ->
                schemaContext.valueProvider.registerBoolean("calendar.active.${cal.prefixedId}",
                    getter = { cal.isActive },
                    setter = { })
            }
        }
        registerReauthCallbacks(accounts)
        runOnUiThread {
            supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
        }
    }

    delegate.onCalendarAssignmentDataLoaded = { json ->
        onCalendarAssignmentDataLoaded(json)
    }

    delegate.onCalendarToggled = {
        // Reload calendar data to get updated active states
        loadCalendarDataFromJs()
    }

    delegate.onCalendarError = { message ->
        calendarLoading = false
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
        }
    }

    delegate.onCalendarImportSuccess = { message ->
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            // Refresh calendar data to show newly imported HA calendars
            loadCalendarDataFromJs()
        }
    }
}

// ── Categorize & Assign ────────────────────────────────────────
fun SettingsActivity.onCalendarAssignmentDataLoaded(json: String) {
    try {
        val obj = org.json.JSONObject(json)
        val assignObj = obj.optJSONObject("assignments")
        calendarAssignmentTypes.clear()
        if (assignObj != null) {
            assignObj.keys().forEach { key ->
                calendarAssignmentTypes[key] = assignObj.optString(key, "family")
            }
        }
        val tagsObj = obj.optJSONObject("tags")
        calendarTags.clear()
        if (tagsObj != null) {
            tagsObj.keys().forEach { key ->
                val arr = tagsObj.optJSONArray(key)
                if (arr != null) {
                    calendarTags[key] = (0 until arr.length()).map { arr.optString(it) }
                }
            }
        }
        // Parse display name overrides
        val namesObj = obj.optJSONObject("display_names")
        calendarDisplayNames.clear()
        if (namesObj != null) {
            namesObj.keys().forEach { key ->
                val name = namesObj.optString(key, "")
                if (name.isNotEmpty()) calendarDisplayNames[key] = name
            }
        }
        // Parse color overrides
        val colorsObj = obj.optJSONObject("color_overrides")
        calendarColorOverrides.clear()
        if (colorsObj != null) {
            colorsObj.keys().forEach { key ->
                val color = colorsObj.optString(key, "")
                if (color.isNotEmpty()) calendarColorOverrides[key] = color
            }
        }
        // Parse family members if included
        val membersArr = obj.optJSONArray("members")
        if (membersArr != null) {
            familyMembers = com.dashieapp.Dashie.halite.settings.data.FamilyMember.fromJsonArray(membersArr.toString())
        }
    } catch (e: Exception) {
        android.util.Log.e("Settings", "Failed to parse assignment data", e)
    }

    android.util.Log.i("CalAssign", "Assignment data loaded: ${calendarAssignmentTypes.size} assignments, ${calendarTags.size} tags, ${calendarDisplayNames.size} names, ${calendarColorOverrides.size} colors, ${familyMembers.size} members")

    // Refresh the already-visible categorize fragment with fresh data
    runOnUiThread {
        supportFragmentManager.fragments.filterIsInstance<
            com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().lastOrNull()?.refresh()
    }
}
