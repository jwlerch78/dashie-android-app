package com.dashieapp.Dashie.halite.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dashieapp.Dashie.halite.preferences.SettingsSyncNotifier
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring
import com.dashieapp.Dashie.halite.settings.schemas.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Robolectric schema-walker (SETTINGS_ROBUSTNESS_PLAN Phase 3).
 *
 * Locks the Phase-2 sync-by-default semantics in CI: for every schema item
 * with a settingKey, flipping the value through the REAL value provider (the
 * exact path a native Settings tap takes) must
 *   1. have a registered provider setter (an unregistered key renders a dead
 *      control — tapping it throws or silently no-ops), and
 *   2. if the write lands on a pref key classified in PREF_KEY_TO_CATEGORY,
 *      the SettingsSyncNotifier must dispatch that key's category (the
 *      Kotlin→cloud half of auto-sync).
 * Plus the two correctness properties the on-device sim proved (echo/suppress):
 * suppressed writes must NOT dispatch, and a burst of writes debounces to one
 * dispatch per category.
 *
 * Precedent: HaUrlTrimTest.kt (same runner/config).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSchemaWalkerTest {

    private lateinit var context: Context
    private lateinit var schemaCtx: SettingsSchemaWiring.SchemaContext
    private lateinit var notifier: SettingsSyncNotifier
    private val dispatched = mutableListOf<String>()
    private val changedPrefKeys = mutableListOf<String>()
    private val prefListeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    /** settingKeys with no provider setter, tracked with reasons. SHRINK ONLY —
     *  a new entry means a schema item whose tap does nothing. */
    private val unregisteredAllowlist = mapOf<String, String>(
        // populated on first run if the walker finds pre-existing gaps
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        schemaCtx = SettingsSchemaWiring.create(context)
        notifier = SettingsSyncNotifier(context) { cat -> dispatched.add(cat) }
        notifier.start()
        // Record raw pref-key changes on every synced file so the walker can
        // tell WHICH pref key a provider setter actually wrote.
        for (name in SettingsSyncNotifier.SYNCED_PREF_FILES) {
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k != null) changedPrefKeys.add(k)
            }
            sp.registerOnSharedPreferenceChangeListener(l)
            prefListeners.add(sp to l)
        }
    }

    @After
    fun tearDown() {
        notifier.stop()
        for ((sp, l) in prefListeners) sp.unregisterOnSharedPreferenceChangeListener(l)
    }

    // Every statically-constructible schema page (permissive flags so gated
    // sections/items are present). Dynamic sub-screens (runtime-loaded item
    // lists) are skipped in the walk.
    private fun allSchemas(): List<SettingsPageSchema> = listOf(
        DisplayPageSchema.create(isHaEnabled = true, isDevFlavor = true, isTv = false),
        SleepPageSchema.create(),
        ScreensaverPageSchema.create(isTv = false, isAdvancedEnabled = true),
        PhotosPageSchema.create(isLoggedIn = true, isHaEnabled = true),
        PreferencesPageSchema.create(),
        CalendarPageSchema.create(),
        VoiceAiPageSchema.create(hasAccount = true, cloudAvailable = true, haEnabled = true, feedbackPromptAvailable = true),
        LocationsPageSchema.create(),
        ChoresRewardsPageSchema.create(),
        AdvancedPageSchema.create(),
        MqttPageSchema.create(),
        PerformancePageSchema.create(),
        CameraPageSchema.create(),
        BatteryChargingPageSchema.create(),
        HomeAssistantPageSchema.create(),
        AccountPageSchema.create(),
    )

    private data class Walked(val page: String, val itemId: String, val item: SchemaItem)

    private fun collectItems(): List<Walked> {
        val out = mutableListOf<Walked>()
        for (schema in allSchemas()) {
            val sections = schema.sections + schema.subScreens.values.filter { !it.dynamic }.flatMap { it.sections }
            for (s in sections) for (i in s.items) out.add(Walked(schema.id, i.id, i))
        }
        return out
    }

    private fun idle() {
        // The notifier debounces 400 ms on the main looper; SharedPreferences
        // listener callbacks are also main-looper work under Robolectric.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    }

    /** Flip an item through the value provider. Returns null on success, or a
     *  failure description; "UNREGISTERED" marks a missing setter. */
    private fun flip(item: SchemaItem): String? {
        val vp = schemaCtx.valueProvider
        return try {
            when (item) {
                is SchemaItem.Toggle -> { vp.setBoolean(item.settingKey, !vp.getBoolean(item.settingKey)); null }
                is SchemaItem.Slider -> {
                    val cur = vp.getInt(item.settingKey)
                    vp.setInt(item.settingKey, if (cur == item.min) item.max else item.min)
                    null
                }
                is SchemaItem.Picker -> {
                    val target = item.options.firstOrNull { !it.isCustomInput && it.value != vp.getString(item.settingKey) }
                        ?: return null // single-option / custom-only picker — nothing to flip
                    try { vp.setString(item.settingKey, target.value) } catch (e: IllegalArgumentException) {
                        // int-typed picker (registered via registerInt)
                        val n = target.value.toIntOrNull() ?: throw e
                        vp.setInt(item.settingKey, n)
                    }
                    null
                }
                is SchemaItem.TextInput -> {
                    vp.setString(item.settingKey, (vp.getString(item.settingKey) ?: "") + "x")
                    null
                }
                else -> null // no settingKey to flip
            }
        } catch (e: IllegalArgumentException) {
            if (e.message?.startsWith("Unknown") == true) "UNREGISTERED" else "flip threw: ${e.message}"
        } catch (e: Exception) {
            "flip threw: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Test
    fun `every schema settingKey flips through the provider and classified keys dispatch`() {
        val failures = mutableListOf<String>()
        var flipped = 0
        var dispatchChecked = 0

        for (w in collectItems()) {
            val settingKey = when (val i = w.item) {
                is SchemaItem.Toggle -> i.settingKey
                is SchemaItem.Picker -> i.settingKey
                is SchemaItem.Slider -> i.settingKey
                is SchemaItem.TextInput -> i.settingKey
                else -> null
            } ?: continue

            changedPrefKeys.clear()
            dispatched.clear()

            val err = flip(w.item)
            if (err == "UNREGISTERED") {
                if (!unregisteredAllowlist.containsKey(settingKey)) {
                    failures.add("${w.page}/${w.itemId}: settingKey '$settingKey' has NO value-provider setter — dead control")
                }
                continue
            }
            if (err != null) {
                failures.add("${w.page}/${w.itemId} ('$settingKey'): $err")
                continue
            }
            flipped++

            idle()
            // Every classified pref key this flip touched must have dispatched
            // its category (the Kotlin→cloud half of auto-sync).
            val expected = changedPrefKeys.mapNotNull { SettingsSyncNotifier.PREF_KEY_TO_CATEGORY[it] }.toSet()
            for (cat in expected) {
                dispatchChecked++
                if (cat !in dispatched) {
                    failures.add(
                        "${w.page}/${w.itemId}: flip of '$settingKey' wrote classified pref key(s) " +
                            "${changedPrefKeys.filter { SettingsSyncNotifier.PREF_KEY_TO_CATEGORY[it] == cat }} " +
                            "but the notifier did NOT dispatch '$cat'"
                    )
                }
            }
        }

        // Sanity: the walk actually exercised the pipeline (guards against a
        // silent all-skip making this test vacuous).
        assertTrue("walker flipped only $flipped items — schema enumeration broke", flipped >= 40)
        assertTrue("walker verified only $dispatchChecked dispatches — classification coverage broke", dispatchChecked >= 15)
        assertTrue("schema-walker failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `suppressed writes do not dispatch`() {
        dispatched.clear()
        notifier.suppress {
            context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("show_date", true).commit()
        }
        idle()
        assertTrue("suppressed write dispatched: $dispatched", dispatched.isEmpty())
    }

    @Test
    fun `a burst of classified writes debounces to one dispatch per category`() {
        dispatched.clear()
        val sp = context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("show_date", true).commit()
        sp.edit().putBoolean("show_clock", true).commit()
        sp.edit().putInt("screensaver_timeout", 123).commit()
        idle()
        assertTrue("expected exactly one 'screensaver' dispatch, got $dispatched",
            dispatched.count { it == "screensaver" } == 1)
    }
}
