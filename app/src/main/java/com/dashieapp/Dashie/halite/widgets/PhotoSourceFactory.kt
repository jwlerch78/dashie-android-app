package com.dashieapp.Dashie.halite.widgets

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.screensaver.*
import kotlinx.coroutines.*

/**
 * Factory for creating and initializing photo sources.
 *
 * Extracts the source setup pattern from ScreenDimmer into a reusable
 * component. Both the screensaver and the photo widget use this to create
 * and start photo sources with the same logic:
 *   1. Create the source (loads disk cache)
 *   2. Show cached photos immediately if available
 *   3. Sync new photos from network in background
 *   4. Wire streaming callbacks for on-demand fetching
 */
class PhotoSourceFactory(
    private val context: Context,
    private val prefs: ScreensaverPreferences,
    private val halitePrefs: HalitePreferences?
) {
    companion object {
        private const val TAG = "PhotoSourceFactory"
    }

    var photoSource: PhotoSource? = null
        private set

    private var haMediaSource: HaMediaPhotoSource? = null
    private var immichSource: ImmichPhotoSource? = null
    private var unsplashSource: UnsplashPhotoSource? = null
    private var googleDriveSource: GoogleDrivePhotoSource? = null
    private var supabaseSource: SupabasePhotoSource? = null

    /**
     * Optional provider for shared photos from the screensaver.
     * When set and returns a non-empty list, the widget uses these photos
     * instead of fetching its own (avoids duplicate Unsplash API calls
     * and ensures both show the same photo pool).
     */
    var sharedPhotosProvider: (() -> List<PhotoItem>)? = null

    fun setup(
        view: PhotoSlideshowView,
        scope: CoroutineScope,
        webViewProvider: (() -> WebView?)? = null,
        onDestroyed: () -> Boolean = { false },
        onReady: (PhotoSlideshow) -> Unit = {}
    ) {
        Log.i(TAG, "Setting up photo source: ${prefs.photoSourceType}")

        // Try shared photos from screensaver first (avoids duplicate fetches)
        val sharedPhotos = sharedPhotosProvider?.invoke()
        if (!sharedPhotos.isNullOrEmpty()) {
            Log.i(TAG, "Using ${sharedPhotos.size} shared photos from screensaver")
            val slideshow = PhotoSlideshow(view, prefs)
            slideshow.start(sharedPhotos)
            onReady(slideshow)
            return
        }

        when (prefs.photoSourceType) {
            ScreensaverPreferences.PHOTO_SOURCE_HA_MEDIA ->
                setupHaMedia(view, scope, webViewProvider, onDestroyed, onReady)
            ScreensaverPreferences.PHOTO_SOURCE_IMMICH ->
                setupImmich(view, scope, onDestroyed, onReady)
            ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH ->
                setupUnsplash(view, scope, onDestroyed, onReady)
            ScreensaverPreferences.PHOTO_SOURCE_GOOGLE_DRIVE ->
                setupGoogleDrive(view, scope, onDestroyed, onReady)
            ScreensaverPreferences.PHOTO_SOURCE_SUPABASE ->
                setupSupabase(view, scope, onDestroyed, onReady)

            // Explicitly "no photo source" — show nothing rather than quietly falling into a
            // source the user never picked. `clearUnsplashConfig()` migrates the OLD DEFAULT
            // (unsplash) to this value, so this is the branch an un-reconfigured device lands on.
            ScreensaverPreferences.PHOTO_SOURCE_NONE -> {
                Log.i(TAG, "photo source is NONE — no slideshow")
                view.showEmptyState("No photo source selected\n\nPick one in Settings → Photos")
            }

            // The retired local-folder source. Still reachable by an explicit stored value on a
            // device configured before it left the picker, so it keeps working — but only when
            // it was actually CHOSEN.
            ScreensaverPreferences.PHOTO_SOURCE_LOCAL ->
                setupLocal(view, onReady)

            // Everything else is a value nothing in this build understands.
            //
            // 🔴 This used to be `else -> setupLocal(...)`, which made a RETIRED, no-longer-
            // pickable source the silent catch-all for every unrecognised value — including
            // PHOTO_SOURCE_NONE and PHOTO_SOURCE_GOOGLE, which are declared but have no branch.
            // A device that had never chosen anything therefore routed into the local-folder
            // reader BY DEFAULT rather than by choice, with no log line saying so. It only ever
            // looked harmless because that reader renders nothing unless /Pictures/Dashie/
            // happens to hold images — a silent fallthrough hiding behind an empty directory.
            //
            // Now: no fallback to a retired source, and the drop is loud (standing rule 2) so an
            // unrecognised value is diagnosable instead of being absorbed.
            else -> {
                Log.w(TAG, "DROP: unrecognised photo source '${prefs.photoSourceType}' — " +
                    "no slideshow started. Known values: none, local, immich, google_drive, " +
                    "ha_media, unsplash, supabase.")
                view.showEmptyState("Photo source not available\n\nPick one in Settings → Photos")
            }
        }
    }

    fun cleanup() {
        photoSource = null
    }

    // ========================================================================
    // Local
    // ========================================================================

    private fun setupLocal(view: PhotoSlideshowView, onReady: (PhotoSlideshow) -> Unit) {
        Log.i(TAG, "Setting up local photo source")
        val source = LocalPhotoSource(context, prefs)
        photoSource = source
        val photos = source.getPhotos()
        if (photos.isNotEmpty()) {
            val slideshow = PhotoSlideshow(view, prefs)
            slideshow.start(photos)
            onReady(slideshow)
        } else {
            view.showEmptyState("No photos found\n\nCopy photos to:\n${source.getFolderPath()}")
        }
    }

    // ========================================================================
    // HA Media
    // ========================================================================

    private fun setupHaMedia(
        view: PhotoSlideshowView, scope: CoroutineScope,
        webViewProvider: (() -> WebView?)?,
        onDestroyed: () -> Boolean, onReady: (PhotoSlideshow) -> Unit
    ) {
        Log.i(TAG, "Setting up HA Media photo source")
        val hp = halitePrefs
        if (hp == null) { view.showEmptyState("Configuration error"); return }

        if (haMediaSource == null) {
            haMediaSource = HaMediaPhotoSource(context, prefs).also { source ->
                source.tokenProvider = { hp.connection.haAccessToken.ifEmpty { null } }
                source.onTokenRefreshNeeded = {
                    withContext(Dispatchers.IO) {
                        val result = HaTokenExtractor.refreshTokenSync(hp)
                        if (result.success) result.accessToken else null
                    }
                }
            }
        }
        photoSource = haMediaSource

        val cachedPhotos = haMediaSource!!.getPhotos()
        if (cachedPhotos.isNotEmpty()) {
            Log.i(TAG, "INSTANT: Starting with ${cachedPhotos.size} cached HA photos")
            val slideshow = PhotoSlideshow(view, prefs)
            wireHaMediaCallbacks(slideshow)
            slideshow.start(cachedPhotos)
            onReady(slideshow)

            HaTokenExtractor.ensureToken(webViewProvider?.invoke(), hp) { hasToken ->
                if (onDestroyed()) return@ensureToken
                if (hasToken) {
                    val baseUrl = hp.connection.haBaseUrl.ifEmpty { hp.connection.haUrl.substringBefore("?").trimEnd('/') }
                    if (baseUrl.isNotEmpty()) {
                        haMediaSource?.setCredentials(hp.connection.haAccessToken, baseUrl)
                        scope.launch {
                            if (onDestroyed()) return@launch
                            haMediaSource?.sync()
                        }
                    }
                }
            }
            return
        }

        view.showLoading("Loading photos from Home Assistant...")
        HaTokenExtractor.ensureToken(webViewProvider?.invoke(), hp) { hasToken ->
            if (onDestroyed()) return@ensureToken
            if (hasToken) {
                val baseUrl = hp.connection.haBaseUrl.ifEmpty { hp.connection.haUrl.substringBefore("?").trimEnd('/') }
                if (baseUrl.isNotEmpty()) {
                    syncHaMediaFromNetwork(view, scope, hp.connection.haAccessToken, baseUrl, onDestroyed, onReady)
                } else {
                    view.showEmptyState("Home Assistant URL not configured")
                }
            } else {
                view.showEmptyState("Not logged in to Home Assistant\n\nOpen dashboard first to authenticate")
            }
        }
    }

    private fun syncHaMediaFromNetwork(
        view: PhotoSlideshowView, scope: CoroutineScope,
        accessToken: String, baseUrl: String,
        onDestroyed: () -> Boolean, onReady: (PhotoSlideshow) -> Unit
    ) {
        val source = haMediaSource ?: return
        source.setCredentials(accessToken, baseUrl)
        scope.launch {
            if (onDestroyed()) return@launch
            val result = source.sync()
            if (onDestroyed()) return@launch
            if (result.success && result.photosFound > 0) {
                var photos = source.getPhotos()
                if (photos.isEmpty()) {
                    view.showLoading("Downloading photos...")
                    source.prefetchInitialPhotos(8)
                    if (onDestroyed()) return@launch
                    photos = source.getPhotos()
                }
                if (photos.isNotEmpty()) {
                    val slideshow = PhotoSlideshow(view, prefs)
                    wireHaMediaCallbacks(slideshow)
                    slideshow.start(photos)
                    onReady(slideshow)
                } else {
                    view.showEmptyState(emptyHaMessage())
                }
            } else {
                view.showEmptyState(result.error ?: emptyHaMessage())
            }
        }
    }

    private fun wireHaMediaCallbacks(slideshow: PhotoSlideshow) {
        val source = haMediaSource ?: return
        slideshow.fetchNextPhoto = { source.getNextPhoto() }
        slideshow.onPhotoDisplayed = { photoId -> source.markPhotoDisplayed(photoId) }
    }

    private fun emptyHaMessage(): String {
        val folder = prefs.haMediaFolder
        val folderDisplay = if (folder == ".") "/config/media" else "/config/media/$folder"
        return "No photos in Home Assistant\n\nUpload photos to:\n$folderDisplay"
    }

    // ========================================================================
    // Immich
    // ========================================================================

    private fun setupImmich(
        view: PhotoSlideshowView, scope: CoroutineScope,
        onDestroyed: () -> Boolean, onReady: (PhotoSlideshow) -> Unit
    ) {
        Log.i(TAG, "Setting up Immich photo source")
        if (!prefs.hasImmichConfig) {
            // Local SharedPreferences is empty — try the central HA store
            // first. Credentials may have been saved on another device or
            // wiped here by `pm clear`. If central has them, populate local
            // prefs and proceed normally; otherwise fall through to the
            // "not configured" empty state.
            val hp = halitePrefs
            if (hp != null) {
                view.showLoading("Connecting to Immich…")
                scope.launch(Dispatchers.IO) {
                    val central = com.dashieapp.Dashie.halite.settings.schema.wiring
                        .SettingsDialogWiring.fetchCentralImmichToken(hp)
                    if (onDestroyed()) return@launch
                    if (central != null) {
                        prefs.immichServerUrl = central.second
                        prefs.immichAccessToken = central.first
                        if (central.third.isNotEmpty()) {
                            prefs.immichSelectedAlbums = central.third
                        }
                        Log.i(TAG, "Auto-connected Immich from central HA store")
                        withContext(Dispatchers.Main) {
                            if (onDestroyed()) return@withContext
                            setupImmich(view, scope, onDestroyed, onReady)
                        }
                    } else {
                        Log.i(TAG, "No central Immich token; showing not-configured state")
                        withContext(Dispatchers.Main) {
                            if (onDestroyed()) return@withContext
                            view.showEmptyState("Immich not configured\n\nSet up in Photos settings")
                        }
                    }
                }
                return
            }
            view.showEmptyState("Immich not configured\n\nSet up in Photos settings")
            return
        }

        if (immichSource == null) {
            immichSource = ImmichPhotoSource(context, prefs)
        }
        immichSource!!.setCredentials(prefs.immichServerUrl, prefs.immichAccessToken)
        photoSource = immichSource

        val cachedPhotos = immichSource!!.getPhotos()
        if (cachedPhotos.isNotEmpty()) {
            Log.i(TAG, "INSTANT: Starting with ${cachedPhotos.size} cached Immich photos")
            val slideshow = PhotoSlideshow(view, prefs)
            wireImmichCallbacks(slideshow)
            slideshow.start(cachedPhotos)
            onReady(slideshow)

            // Background sync — reload with enriched metadata
            scope.launch {
                if (onDestroyed()) return@launch
                immichSource?.sync()
                if (onDestroyed()) return@launch
                val enrichedPhotos = immichSource?.getPhotos() ?: return@launch
                if (enrichedPhotos.isNotEmpty()) {
                    slideshow.reloadPhotos(enrichedPhotos)
                    Log.i(TAG, "Reloaded Immich slideshow with enriched photos")
                }
            }
            return
        }

        view.showLoading("Loading photos from Immich...")
        scope.launch {
            if (onDestroyed()) return@launch
            val result = immichSource!!.sync()
            if (onDestroyed()) return@launch
            if (result.success && result.photosFound > 0) {
                val photos = immichSource!!.getPhotos()
                if (photos.isNotEmpty()) {
                    val slideshow = PhotoSlideshow(view, prefs)
                    wireImmichCallbacks(slideshow)
                    slideshow.start(photos)
                    onReady(slideshow)
                } else {
                    view.showEmptyState("No photos found in Immich")
                }
            } else {
                view.showEmptyState(result.error ?: "No photos found in Immich")
            }
        }
    }

    private fun wireImmichCallbacks(slideshow: PhotoSlideshow) {
        val source = immichSource ?: return
        slideshow.fetchNextPhoto = { source.getNextPhoto() }
        slideshow.onPhotoDisplayed = { photoId -> source.markPhotoDisplayed(photoId) }
    }

    // ========================================================================
    // Unsplash
    // ========================================================================

    private fun setupUnsplash(
        view: PhotoSlideshowView, scope: CoroutineScope,
        onDestroyed: () -> Boolean, onReady: (PhotoSlideshow) -> Unit
    ) {
        Log.i(TAG, "Setting up Unsplash photo source")
        if (!prefs.hasUnsplashConfig) { view.showEmptyState("Unsplash not configured"); return }

        if (unsplashSource == null) { unsplashSource = UnsplashPhotoSource(context, prefs) }
        photoSource = unsplashSource

        val cachedPhotos = unsplashSource!!.getPhotos()
        if (cachedPhotos.isNotEmpty()) {
            val slideshow = PhotoSlideshow(view, prefs)
            wireUnsplashCallbacks(slideshow)
            slideshow.start(cachedPhotos)
            onReady(slideshow)
            scope.launch { if (!onDestroyed()) unsplashSource?.sync() }
            return
        }

        view.showLoading("Loading photos from Unsplash...")
        scope.launch {
            if (onDestroyed()) return@launch
            val result = unsplashSource!!.sync()
            if (result.success && result.photosFound > 0) {
                val photos = unsplashSource!!.getPhotos()
                if (photos.isNotEmpty() && !onDestroyed()) {
                    val slideshow = PhotoSlideshow(view, prefs)
                    wireUnsplashCallbacks(slideshow)
                    slideshow.start(photos)
                    onReady(slideshow)
                }
            } else {
                view.showEmptyState(result.error ?: "Could not load Unsplash photos")
            }
        }
    }

    private fun wireUnsplashCallbacks(slideshow: PhotoSlideshow) {
        val source = unsplashSource ?: return
        slideshow.fetchNextPhoto = { source.getNextPhoto() }
        slideshow.onPhotoDisplayed = { photoId -> source.markPhotoDisplayed(photoId) }
    }

    // ========================================================================
    // Google Drive
    // ========================================================================

    private fun setupGoogleDrive(
        view: PhotoSlideshowView, scope: CoroutineScope,
        onDestroyed: () -> Boolean, onReady: (PhotoSlideshow) -> Unit
    ) {
        Log.i(TAG, "Setting up Google Drive photo source")
        val connectionPrefs = halitePrefs?.connection
        if (connectionPrefs == null || !connectionPrefs.hasSupabaseJwt) {
            view.showEmptyState("Not signed in — Google Drive requires login")
            return
        }

        val source = googleDriveSource ?: GoogleDrivePhotoSource(context, connectionPrefs, prefs).also {
            googleDriveSource = it
        }
        photoSource = source

        val cachedPhotos = source.getPhotos()
        if (cachedPhotos.isNotEmpty()) {
            Log.i(TAG, "INSTANT: Starting with ${cachedPhotos.size} cached Google Drive photos")
            val slideshow = PhotoSlideshow(view, prefs)
            slideshow.fetchNextPhoto = { source.getNextPhoto() }
            slideshow.start(cachedPhotos)
            onReady(slideshow)

            // Background sync — enriches cached photos with metadata, then reload
            scope.launch {
                if (onDestroyed()) return@launch
                source.sync()
                if (onDestroyed()) return@launch
                val enrichedPhotos = source.getPhotos()
                if (enrichedPhotos.isNotEmpty()) {
                    slideshow.reloadPhotos(enrichedPhotos)
                    Log.i(TAG, "Reloaded slideshow with enriched Google Drive photos")
                }
            }
            return
        }

        view.showLoading("Syncing photos from Google Drive...")
        scope.launch {
            if (onDestroyed()) return@launch
            val result = source.sync()
            if (result.success && result.photosFound > 0) {
                val photos = source.getPhotos()
                if (photos.isNotEmpty() && !onDestroyed()) {
                    val slideshow = PhotoSlideshow(view, prefs)
                    slideshow.fetchNextPhoto = { source.getNextPhoto() }
                    slideshow.start(photos)
                    onReady(slideshow)
                }
            } else {
                view.showEmptyState(result.error ?: "No photos in Google Drive folder")
            }
        }
    }

    // ========================================================================
    // Dashie Cloud (Supabase Storage)
    // ========================================================================

    private fun setupSupabase(
        view: PhotoSlideshowView, scope: CoroutineScope,
        onDestroyed: () -> Boolean, onReady: (PhotoSlideshow) -> Unit
    ) {
        Log.i(TAG, "Setting up Dashie Cloud (Supabase) photo source")
        val connectionPrefs = halitePrefs?.connection
        if (connectionPrefs == null || !connectionPrefs.hasSupabaseJwt) {
            // Friendlier than "Not signed in — Dashie Cloud requires login":
            // photo icon + "Click to add photos". This empty state also flips
            // a flag the JS photos menu reads to skip the activated/hint
            // state and open the Upload bar directly when the user d-pads
            // onto the widget.
            view.showDashieCloudEmptyState()
            return
        }

        val source = supabaseSource ?: SupabasePhotoSource(context, connectionPrefs, prefs).also {
            supabaseSource = it
        }
        photoSource = source

        val cachedPhotos = source.getPhotos()
        if (cachedPhotos.isNotEmpty()) {
            Log.i(TAG, "INSTANT: Starting with ${cachedPhotos.size} cached Dashie Cloud photos")
            val slideshow = PhotoSlideshow(view, prefs)
            slideshow.fetchNextPhoto = { source.getNextPhoto() }
            slideshow.start(cachedPhotos)
            onReady(slideshow)

            scope.launch {
                if (onDestroyed()) return@launch
                source.sync()
                if (onDestroyed()) return@launch
                val enrichedPhotos = source.getPhotos()
                if (enrichedPhotos.isNotEmpty()) {
                    slideshow.reloadPhotos(enrichedPhotos)
                    Log.i(TAG, "Reloaded slideshow with enriched Dashie Cloud photos")
                }
            }
            keepDashieCloudIndexFresh(source, scope, onDestroyed)
            return
        }

        view.showLoading("Syncing photos from Dashie Cloud...")
        scope.launch {
            if (onDestroyed()) return@launch
            val result = source.sync()
            if (result.success && result.photosFound > 0) {
                val photos = source.getPhotos()
                if (photos.isNotEmpty() && !onDestroyed()) {
                    val slideshow = PhotoSlideshow(view, prefs)
                    slideshow.fetchNextPhoto = { source.getNextPhoto() }
                    slideshow.start(photos)
                    onReady(slideshow)
                    // Fill out the rest of the prefetch window in the
                    // background so subsequent slideshow advances are instant.
                    scope.launch {
                        if (onDestroyed()) return@launch
                        source.startBackgroundPrefetch()
                    }
                    keepDashieCloudIndexFresh(source, scope, onDestroyed)
                }
            } else {
                // Same friendlier empty state as the no-JWT path — sync
                // succeeded but the user just hasn't uploaded any photos
                // yet. result.error (when present) means an actual sync
                // failure, in which case we fall back to the plain text
                // so the user sees what went wrong.
                if (result.error != null) {
                    view.showEmptyState(result.error)
                } else {
                    view.showDashieCloudEmptyState()
                    pollDashieCloudUntilFirstPhotos(source, view, scope, onDestroyed, onReady)
                }
            }
        }
    }

    /**
     * While the Dashie Cloud empty state is on screen, re-sync periodically
     * so a phone-side upload swaps the slideshow on the TV without the user
     * having to touch the dashboard. Stops as soon as photos appear or the
     * widget is destroyed / source changes (PhotoWidgetController.reconfigure
     * tears down the old slideshow + factory before launching a new one, so
     * onDestroyed will trip).
     *
     * 30s cadence keeps the latency reasonable without hammering the edge
     * function. We stop polling once a slideshow is live; new uploads after
     * that point are handled by the slideshow's own re-sync paths.
     */
    private fun pollDashieCloudUntilFirstPhotos(
        source: SupabasePhotoSource,
        view: PhotoSlideshowView,
        scope: CoroutineScope,
        onDestroyed: () -> Boolean,
        onReady: (PhotoSlideshow) -> Unit
    ) {
        scope.launch {
            while (!onDestroyed() && view.isShowingDashieCloudEmptyState()) {
                delay(30_000)
                if (onDestroyed() || !view.isShowingDashieCloudEmptyState()) return@launch

                val pollResult = source.sync()
                if (!pollResult.success || pollResult.photosFound <= 0) continue

                val photos = source.getPhotos()
                if (photos.isEmpty() || onDestroyed()) continue

                Log.i(TAG, "Dashie Cloud poll found ${photos.size} new photo(s) — starting slideshow")
                view.hideEmptyState()
                val slideshow = PhotoSlideshow(view, prefs)
                slideshow.fetchNextPhoto = { source.getNextPhoto() }
                slideshow.start(photos)
                onReady(slideshow)
                scope.launch {
                    if (onDestroyed()) return@launch
                    source.startBackgroundPrefetch()
                }
                keepDashieCloudIndexFresh(source, scope, onDestroyed)
                return@launch
            }
        }
    }

    /**
     * Long-lived re-sync loop that keeps SupabasePhotoSource.photoIndex
     * fresh after the slideshow starts. The slideshow's streaming-mode
     * fetcher (source.getNextPhoto) reads photoIndex on every advance, so
     * a fresh index is enough — the next natural advance picks up new
     * photos without us having to reload the slideshow.
     *
     * Without this, the first sync's photoIndex snapshot is permanent for
     * the rest of the session: a phone that uploads 3 photos one-at-a-time
     * around the moment of the initial poll can leave the slideshow stuck
     * cycling photo 1 forever, even though photos 2/3 are in the bucket.
     *
     * 60s cadence — list_photos is cheap when nothing changed, so server
     * load is minimal. PhotoWidgetController.reconfigure tears down the
     * coroutine scope on source change / destroy, which cancels this
     * loop too.
     */
    private fun keepDashieCloudIndexFresh(
        source: SupabasePhotoSource,
        scope: CoroutineScope,
        onDestroyed: () -> Boolean
    ) {
        scope.launch {
            while (!onDestroyed()) {
                delay(60_000)
                if (onDestroyed()) return@launch
                try {
                    val before = source.getPhotos().size
                    source.sync()
                    val afterIndex = source.getPhotos().size
                    if (afterIndex > before) {
                        Log.i(TAG, "Dashie Cloud index refreshed: $before → $afterIndex photos cached")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dashie Cloud periodic re-sync failed: ${e.message}")
                }
            }
        }
    }
}
