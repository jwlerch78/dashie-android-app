package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.JavascriptInterface
import com.dashieapp.Dashie.halite.settings.data.CalendarAccount
import com.dashieapp.Dashie.halite.settings.data.FamilyMember

/**
 * JS bridge delegate for settings data callbacks.
 *
 * Provides @JavascriptInterface methods that JS calls to deliver
 * async data (family members, calendar accounts) to native settings pages.
 *
 * Data flow:
 * 1. Kotlin calls evaluateJavascript() to trigger a JS service method
 * 2. JS service fetches from Supabase, then calls DashieNative.onXxxLoaded(json)
 * 3. This delegate parses the JSON and invokes a callback on the main thread
 *
 * Note: @JavascriptInterface methods run on the WebView background thread,
 * so all UI updates must be posted to the main thread via the callbacks.
 */
class JsBridgeSettingsDataDelegate {

    companion object {
        private const val TAG = "JsBridgeData"
    }

    // ── Persistent count callbacks (for Control Center — not overwritten by SettingsActivity) ──

    /** Fires with member count whenever family data is loaded */
    var onFamilyCountUpdated: ((Int) -> Unit)? = null

    /** Fires with (accountCount, activeCount, totalCount) whenever calendar data is loaded */
    var onCalendarCountsUpdated: ((Int, Int, Int) -> Unit)? = null

    // ── Family data callbacks ───────────────────────────────────────

    /** Called when family members list is loaded from Supabase */
    var onFamilyMembersLoaded: ((List<FamilyMember>) -> Unit)? = null

    /** Called when a family member is created or updated */
    var onFamilyMemberSaved: ((FamilyMember) -> Unit)? = null

    /** Called when a family member is deleted */
    var onFamilyMemberDeleted: ((String) -> Unit)? = null

    /** Called when a family data operation fails */
    var onFamilyError: ((String) -> Unit)? = null

    /** Called when the family name is loaded from the JS settings store */
    var onFamilyNameLoaded: ((String) -> Unit)? = null

    // ── @JavascriptInterface methods ────────────────────────────────

    @JavascriptInterface
    fun onFamilyMembersLoaded(json: String) {
        Log.d(TAG, "onFamilyMembersLoaded: ${json.take(200)}")
        try {
            val members = FamilyMember.fromJsonArray(json)
            onFamilyMembersLoaded?.invoke(members)
            onFamilyCountUpdated?.invoke(members.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse family members", e)
            onFamilyError?.invoke("Failed to load family members")
        }
    }

    @JavascriptInterface
    fun onFamilyMemberSaved(json: String) {
        Log.d(TAG, "onFamilyMemberSaved: ${json.take(200)}")
        try {
            val member = FamilyMember.fromJson(org.json.JSONObject(json))
            onFamilyMemberSaved?.invoke(member)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved family member", e)
            onFamilyError?.invoke("Failed to save family member")
        }
    }

    @JavascriptInterface
    fun onFamilyMemberDeleted(memberId: String) {
        Log.d(TAG, "onFamilyMemberDeleted: $memberId")
        onFamilyMemberDeleted?.invoke(memberId)
    }

    @JavascriptInterface
    fun onFamilyError(message: String) {
        Log.w(TAG, "Family error from JS: $message")
        onFamilyError?.invoke(message)
    }

    @JavascriptInterface
    fun onFamilyNameLoaded(name: String) {
        Log.d(TAG, "onFamilyNameLoaded: $name")
        onFamilyNameLoaded?.invoke(name)
    }

    // ── Chores participants callback ───────────────────────────────

    /** One-shot callback for loading family members for chores participant picker */
    var onChoresParticipantsLoaded: ((List<FamilyMember>) -> Unit)? = null

    @JavascriptInterface
    fun onChoresParticipantsLoaded(json: String) {
        Log.d(TAG, "onChoresParticipantsLoaded: ${json.take(200)}")
        try {
            val members = FamilyMember.fromJsonArray(json)
            onChoresParticipantsLoaded?.invoke(members)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse chores participants", e)
        }
    }

    // ── Calendar data callbacks ─────────────────────────────────────

    /** Called when calendar accounts + calendars are loaded */
    var onCalendarDataLoaded: ((List<CalendarAccount>) -> Unit)? = null

    /**
     * Called with phase-1 metadata only (account list + auth_invalid flag,
     * empty calendars). Lets the schema render the "Sign-in Required"
     * section immediately while phase 2 fetches calendar lists in the
     * background. Phase 2 finishes by calling [onCalendarDataLoaded] with
     * the full result.
     */
    var onCalendarMetadataLoaded: ((List<CalendarAccount>) -> Unit)? = null

    /** Called when a calendar is toggled on/off */
    var onCalendarToggled: (() -> Unit)? = null

    /** Called when calendar assignment/tag data is loaded */
    var onCalendarAssignmentDataLoaded: ((String) -> Unit)? = null

    /** Called when a calendar data operation fails */
    var onCalendarError: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onCalendarDataLoaded(json: String) {
        Log.d(TAG, "onCalendarDataLoaded: ${json.take(200)}")
        try {
            val accounts = CalendarAccount.fromJsonArray(json)
            onCalendarDataLoaded?.invoke(accounts)
            val totalCount = accounts.sumOf { it.calendars.size }
            val activeCount = accounts.sumOf { a -> a.calendars.count { it.isActive } }
            onCalendarCountsUpdated?.invoke(accounts.size, activeCount, totalCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse calendar data", e)
            onCalendarError?.invoke("Failed to load calendars")
        }
    }

    @JavascriptInterface
    fun onCalendarMetadataLoaded(json: String) {
        Log.d(TAG, "onCalendarMetadataLoaded: ${json.take(200)}")
        try {
            val accounts = CalendarAccount.fromJsonArray(json)
            onCalendarMetadataLoaded?.invoke(accounts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse calendar metadata", e)
        }
    }

    @JavascriptInterface
    fun onCalendarAssignmentDataLoaded(json: String) {
        Log.d(TAG, "onCalendarAssignmentDataLoaded: len=${json.length}")
        // Log just the members' assigned_calendars for debugging persistence
        try {
            val obj = org.json.JSONObject(json)
            val members = obj.optJSONArray("members")
            if (members != null) {
                for (i in 0 until members.length()) {
                    val m = members.getJSONObject(i)
                    Log.i(TAG, "  member: ${m.optString("full_name")} assigned_calendars=${m.opt("assigned_calendars")}")
                }
            }
        } catch (_: Exception) { }
        onCalendarAssignmentDataLoaded?.invoke(json)
    }

    @JavascriptInterface
    fun onCalendarToggled() {
        Log.d(TAG, "onCalendarToggled")
        onCalendarToggled?.invoke()
    }

    @JavascriptInterface
    fun onCalendarError(message: String) {
        Log.w(TAG, "Calendar error from JS: $message")
        onCalendarError?.invoke(message)
    }

    var onCalendarImportSuccess: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onCalendarImportSuccess(message: String) {
        Log.i(TAG, "Calendar import success: $message")
        onCalendarImportSuccess?.invoke(message)
    }

    // ── Personality data callbacks ──────────────────────────────────

    /** Called when personality templates list is loaded from JS */
    var onPersonalitiesLoaded: ((String) -> Unit)? = null

    /** Called when personality data loading fails */
    var onPersonalitiesError: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onPersonalitiesLoaded(json: String) {
        Log.d(TAG, "onPersonalitiesLoaded: ${json.take(200)}")
        onPersonalitiesLoaded?.invoke(json)
    }

    @JavascriptInterface
    fun onPersonalitiesError(message: String) {
        Log.w(TAG, "Personalities error from JS: $message")
        onPersonalitiesError?.invoke(message)
    }
}
