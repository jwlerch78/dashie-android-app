package com.dashieapp.Dashie.halite.settings

import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schemas.VoiceAiPageSchema
import com.dashieapp.Dashie.halite.voice.VoiceFeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asserts that DLG-6 "Open dialog after commands" stays hidden while
 * [VoiceFeatureFlags.KEEP_DIALOG_OPEN_ENABLED] is off.
 *
 * Why a test for a hide: the toggle governs a setting accounts already have stored as `true` (the
 * console seeded it for the cloud/hybrid presets). If someone re-adds the row without also flipping
 * the flag, those accounts get a control that reads ON while the runtime ignores it — the setting
 * would visibly lie. This pins the two halves together.
 *
 * When the feature is deliberately re-enabled, flip the flag and this test follows automatically —
 * it asserts the item's presence MATCHES the flag rather than asserting "always absent".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepDialogOpenHiddenTest {

    private fun allItems(): List<SchemaItem> {
        val schema: SettingsPageSchema = VoiceAiPageSchema.create(
            hasAccount = true, cloudAvailable = true, haEnabled = true,
            feedbackPromptAvailable = true,
        )
        return schema.sections.flatMap { it.items }
    }

    @Test
    fun `the always_open_dialog toggle is present only when the flag is on`() {
        val present = allItems().any { it.id == "always_open_dialog" }
        assertEquals(
            "schema visibility must track VoiceFeatureFlags.KEEP_DIALOG_OPEN_ENABLED — " +
                "a visible toggle over a flag-disabled behavior is a setting that lies",
            VoiceFeatureFlags.KEEP_DIALOG_OPEN_ENABLED, present,
        )
    }

    @Test
    fun `hiding it did not disturb the rest of the voice page`() {
        // Guards the takeIf: listOfNotNull drops exactly one entry, not a whole branch.
        val ids = allItems().mapNotNull { it.id }
        assertTrue("dialog_mode should still be on the page", ids.contains("dialog_mode"))
        assertTrue("the page should still have plenty of items", ids.size > 10)
        assertEquals("no duplicate item ids", ids.size, ids.toSet().size)
    }
}
