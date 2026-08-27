package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * Schema definition for the Photos settings page.
 *
 * Matches the JS settings-photos-page.js layout:
 * 1. Photo Source — source picker, source-specific config
 * 2. Slideshow — duration, transition, shuffle
 * 3. Display — show metadata, weather overlay
 */
object PhotosPageSchema {

    // Reusable conditions
    private val isHaMedia = Condition.Equals("photos.sourceType", "ha_media")
    private val isImmich = Condition.Equals("photos.sourceType", "immich")
    private val isUnsplash = Condition.Equals("photos.sourceType", "unsplash")

    fun create(isLoggedIn: Boolean = false, isHaEnabled: Boolean = false) = SettingsPageSchema(
        id = "photos",
        title = "Photos",
        sections = listOf(
            sourceSection(isLoggedIn, isHaEnabled),
            slideshowSection(),
            displaySection()
        )
    )

    // ── Section 1: Photo Source ─────────────────────────────────────

    private fun sourceSection(isLoggedIn: Boolean, isHaEnabled: Boolean) = SettingsSection(
        header = "Photo Source",
        items = listOf(
            SchemaItem.Picker(
                id = "photo_source_type",
                label = "Source",
                settingKey = "photos.sourceType",
                options = buildList {
                    // HA Media and Immich both rely on a configured HA
                    // connection (HA Media uses HA's media browser; Immich
                    // routes through HA's network proxy). Hide both when
                    // HA isn't linked — they can't function without it and
                    // would just lead the user into a dead end.
                    if (isHaEnabled) {
                        add(SchemaPickerOption("ha_media", "Home Assistant", "Photos from HA media browser"))
                        add(SchemaPickerOption("immich", "Immich", "Self-hosted Immich server"))
                    }
                    if (isLoggedIn) {
                        add(SchemaPickerOption("google_drive", "Google Drive", "Photos from your Drive folder"))
                        add(SchemaPickerOption("supabase", "Dashie Cloud", "Upload via Dashie app"))
                    }
                    add(SchemaPickerOption("unsplash", "Unsplash", "Curated stock photos"))
                },
                onChanged = "notifyPhotoSourceChanged"
            ),
            SchemaItem.Navigation(
                id = "ha_media_folder",
                label = "Media Folder",
                navigateTo = "ext:ha_media_folder_picker",
                displayValueKey = "photos.haMediaFolderDisplay",
                visibleWhen = isHaMedia
            ),
            SchemaItem.Navigation(
                id = "immich_login",
                label = "Immich Account",
                navigateTo = "ext:immich_login",
                displayValueKey = "photos.immichStatusDisplay",
                visibleWhen = isImmich
            ),
            SchemaItem.Navigation(
                id = "immich_albums",
                label = "Select Albums",
                navigateTo = "ext:immich_album_picker",
                displayValueKey = "photos.immichAlbumsDisplay",
                visibleWhen = isImmich
            ),
            SchemaItem.TextInput(
                id = "unsplash_query_input",
                label = "Search Keywords",
                settingKey = "photos.unsplashQuery",
                placeholder = "e.g. nature, mountains, abstract",
                dialogMode = true,
                dialogTitle = "Unsplash Search Keywords",
                onChanged = "notifyPhotoSettingsChanged",
                visibleWhen = isUnsplash
            ),
            SchemaItem.Toggle(
                id = "unsplash_artist_hyperlinks",
                label = "Enable Hyperlinks to Artists",
                settingKey = "photos.unsplashArtistHyperlinks",
                onChanged = "notifyPhotoSettingsChanged",
                visibleWhen = isUnsplash
            )
        )
    )

    // ── Section 2: Slideshow ────────────────────────────────────────

    private fun slideshowSection() = SettingsSection(
        header = "Slideshow",
        items = listOf(
            SchemaItem.Picker(
                id = "slideshow_duration",
                label = "Duration",
                settingKey = "photos.slideshowInterval",
                options = listOf(
                    SchemaPickerOption("5", "5 seconds"),
                    SchemaPickerOption("15", "15 seconds"),
                    SchemaPickerOption("30", "30 seconds"),
                    SchemaPickerOption("60", "1 minute")
                ),
                onChanged = "notifyPhotoSettingsChanged"
            ),
            SchemaItem.Picker(
                id = "slideshow_transition",
                label = "Transition",
                settingKey = "photos.slideshowTransition",
                options = listOf(
                    SchemaPickerOption("fade", "Fade"),
                    SchemaPickerOption("slide", "Slide"),
                    SchemaPickerOption("none", "None")
                ),
                onChanged = "notifyPhotoSettingsChanged"
            ),
            SchemaItem.Picker(
                id = "photo_fit",
                label = "Scaling",
                settingKey = "photos.photoFit",
                options = listOf(
                    SchemaPickerOption("fit", "Fit"),
                    SchemaPickerOption("fill", "Fill")
                ),
                onChanged = "notifyPhotoSettingsChanged"
            ),
            SchemaItem.Toggle(
                id = "slideshow_shuffle",
                label = "Shuffle",
                settingKey = "photos.slideshowShuffle",
                onChanged = "notifyPhotoSettingsChanged"
            )
        )
    )

    // ── Section 3: Display ──────────────────────────────────────────

    private fun displaySection() = SettingsSection(
        header = "Display",
        items = listOf(
            SchemaItem.Toggle(
                id = "photo_show_metadata",
                label = "Show Info",
                sublabel = "Date and location overlay",
                settingKey = "photos.showMetadata",
                onChanged = "notifyPhotoSettingsChanged"
            )
        )
    )
}
