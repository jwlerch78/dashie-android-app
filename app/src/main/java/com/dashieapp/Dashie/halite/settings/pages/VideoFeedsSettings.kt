package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring

/**
 * Create the Video Feeds schema fragment with dynamic feed list.
 * Feed Navigation items route to feed detail via navigationCallback.
 */
internal fun SettingsActivity.createVideoFeedsFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val videoFeedPrefs = com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences(this)

    android.util.Log.i("VF_DEBUG", "createVideoFeedsFragment: enabled=${videoFeedPrefs.enabled}, rules=${videoFeedPrefs.getRules().size}, configJson=${videoFeedPrefs.configJson.take(200)}")

    // Pull feeds from HA in background so the list is up-to-date when the page loads
    if (videoFeedPrefs.enabled) {
        videoFeedPrefs.pullFeedsFromHa { success ->
            android.util.Log.i("VF_DEBUG", "pullFeedsFromHa callback: success=$success, enabled=${videoFeedPrefs.enabled}, rules=${videoFeedPrefs.getRules().size}")
            if (success) {
                runOnUiThread {
                    val frag = supportFragmentManager.fragments.filterIsInstance<
                        com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().firstOrNull()
                    android.util.Log.i("VF_DEBUG", "pullFeedsFromHa refresh: frag=${frag != null}, fragCount=${supportFragmentManager.fragments.size}")
                    frag?.refresh()
                }
            }
        }
    }

    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            val rules = videoFeedPrefs.getRules()
            android.util.Log.i("VF_DEBUG", "schemaProvider called: enabled=${videoFeedPrefs.enabled}, rules=${rules.size}")
            com.dashieapp.Dashie.halite.settings.schemas.VideoFeedsPageSchema.create(
                feedProvider = { rules }
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when {
                target.startsWith("ext:feed_detail:") -> {
                    val feedId = target.removePrefix("ext:feed_detail:")
                    showVideoFeedDetail(feedId)
                    true
                }
                else -> false
            }
        }
    )
}

/**
 * Show the feed detail fragment for a specific feed.
 */
fun SettingsActivity.showVideoFeedDetail(feedId: String) {
    val fragment = com.dashieapp.Dashie.halite.settings.fragments.VideoFeedDetailFragment.create(
        feedId = feedId,
        onModeChanged = {
            // Refresh parent feed list
            val frag = supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().firstOrNull()
            frag?.refresh()
            // Notify the rest of the app (camera popout, trigger injector)
            syncVideoFeedConfigToWebView()
            sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED").apply {
                    setPackage(packageName)
                }
            )
        },
        onEdit = { id -> showVideoFeedEditor(id) },
        onDelete = { id ->
            val prefs = com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences(this)
            prefs.deleteRule(id)
            syncVideoFeedConfigToWebView()
            sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED").apply {
                    setPackage(packageName)
                }
            )
            // Pop back to feed list and refresh
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
            val frag = supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().firstOrNull()
            frag?.refresh()
        }
    )
    showFragment(fragment, "feed_detail_$feedId")
}

/**
 * Show the feed editor fragment for adding or editing a feed.
 * @param feedId null for new feed, non-null for editing existing
 */
fun SettingsActivity.showVideoFeedEditor(feedId: String?) {
    val fragment = com.dashieapp.Dashie.halite.settings.fragments.VideoFeedEditorFragment.create(
        feedId = feedId,
        onSync = {
            syncVideoFeedConfigToWebView()
            sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED").apply {
                    setPackage(packageName)
                }
            )
            // Re-register per-feed mode keys so the schema page can resolve new/updated feeds
            SettingsSchemaWiring.refreshVideoFeedModeKeys(
                this, schemaContext.valueProvider as com.dashieapp.Dashie.halite.settings.schema.HaliteSettingsValueProvider
            )
        },
        onSave = {
            // Pop back synchronously — the schema fragment's view is recreated by
            // popBackStackImmediate, which triggers onViewCreated -> loadItems() with
            // the updated feed list. No explicit refresh() needed (and calling refresh
            // on a view-destroyed fragment would crash).
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate()
            }
        }
    )
    showFragment(fragment, "feed_editor_${feedId ?: "new"}")
}

/**
 * Sync the current video feed config from SharedPreferences to the WebView's
 * localStorage so the JS side stays in sync with native changes.
 */
fun SettingsActivity.syncVideoFeedConfigToWebView() {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    val prefs = com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences(this)
    val configJson = prefs.configJson
    // Escape for JS string literal
    val escaped = configJson
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
    wv.post {
        wv.evaluateJavascript(
            "localStorage.setItem('dashie-video-feeds-config', '$escaped');", null
        )
    }
}
