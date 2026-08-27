package com.dashieapp.Dashie.halite.screensaver

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Controller for the photo slideshow.
 * Manages photo rotation, timing, and shuffle logic.
 */
class PhotoSlideshow(
    private val view: PhotoSlideshowView,
    private val prefs: ScreensaverPreferences
) {
    companion object {
        private const val TAG = "PhotoSlideshow"
        // How many recently-shown streaming photos to keep so previous()
        // can step back through them.
        private const val MAX_STREAM_HISTORY = 60
    }

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private var photos: List<PhotoItem> = emptyList()
    private var shuffledIndices: List<Int> = emptyList()
    private var currentIndex = 0
    private var isRunning = false
    private var autoAdvanceEnabled = true
    private var loadJob: Job? = null
    private var lastDisplayedPhoto: PhotoItem? = null

    // Streaming-mode navigation history. Streaming sources (Immich, HA
    // Media, Unsplash) only expose a forward fetcher, so we keep a buffer
    // of shown photos to make previous() actually step back instead of
    // fetching yet another fresh photo.
    private val streamHistory = mutableListOf<PhotoItem>()
    private var streamHistoryPos = -1

    /**
     * Callback invoked when the slideshow advances to a new photo.
     * Parameter is the current index (0-based position in the photo list).
     * Use this to trigger prefetching of additional photos.
     */
    var onPhotoAdvanced: ((Int) -> Unit)? = null

    /**
     * Callback invoked when a photo is successfully displayed.
     * Parameter is the photo ID. Use this to mark photos as "shown" for safe eviction.
     */
    var onPhotoDisplayed: ((String) -> Unit)? = null

    /**
     * Callback to fetch the next photo dynamically (for streaming sources like HA Media).
     * When set, the slideshow will call this instead of cycling through its internal list.
     * The callback should return the next PhotoItem to display, or null if none available.
     */
    var fetchNextPhoto: (suspend () -> PhotoItem?)? = null

    private val advanceRunnable = Runnable {
        if (isRunning) {
            advanceToNext()
        }
    }

    /**
     * Start the slideshow with the given photos.
     */
    fun start(photoList: List<PhotoItem>) {
        Log.i(TAG, "Starting slideshow with ${photoList.size} photos")

        photos = photoList
        currentIndex = 0
        autoAdvanceEnabled = true

        if (photos.isEmpty()) {
            view.showEmptyState()
            return
        }

        // Shuffle if enabled
        if (prefs.slideshowShuffle) {
            shuffledIndices = photos.indices.shuffled()
        } else {
            shuffledIndices = photos.indices.toList()
        }

        isRunning = true
        showCurrentPhoto(isFirst = true)
    }

    /**
     * Load photos without auto-advancing (for manual explorer mode).
     * Displays the photo at startAt index immediately.
     */
    fun loadPhotos(photoList: List<PhotoItem>, shuffle: Boolean = false, startAt: Int = 0) {
        Log.i(TAG, "Loading ${photoList.size} photos (explorer mode, startAt=$startAt)")

        photos = photoList
        if (photos.isEmpty()) {
            view.showEmptyState()
            return
        }

        shuffledIndices = if (shuffle) photos.indices.shuffled() else photos.indices.toList()
        currentIndex = startAt.coerceIn(0, photos.size - 1)

        // Show the starting photo — isRunning must be true for showCurrentPhoto/next/previous
        // to work, but we cancel auto-advance scheduling after each display
        isRunning = true
        autoAdvanceEnabled = false
        showCurrentPhoto(isFirst = true)
    }

    /**
     * Stop the slideshow.
     */
    fun stop() {
        Log.i(TAG, "Stopping slideshow")
        isRunning = false
        handler.removeCallbacks(advanceRunnable)
        loadJob?.cancel()
        view.clear()
    }

    /**
     * Pause the slideshow (keep state, stop advancing).
     */
    fun pause() {
        isRunning = false
        handler.removeCallbacks(advanceRunnable)
    }

    /**
     * Resume the slideshow from current position.
     */
    fun resume() {
        if (photos.isEmpty()) return
        isRunning = true
        scheduleNextAdvance()
    }

    /**
     * Reload photos (call when files change).
     */
    fun reloadPhotos(photoList: List<PhotoItem>) {
        Log.d(TAG, "Reloading photos: ${photoList.size}")

        val wasRunning = isRunning
        pause()

        // Remember the photo currently on screen so we can keep showing it
        // after the shuffle order is rebuilt. Without this, currentIndex
        // would point into the freshly-shuffled order at an unrelated photo
        // and next/previous would jump instead of stepping from what the
        // user is actually viewing.
        val displayedPhotoId = lastDisplayedPhoto?.id

        photos = photoList
        if (photos.isEmpty()) {
            view.showEmptyState()
            return
        }

        // Reshuffle if enabled
        if (prefs.slideshowShuffle) {
            shuffledIndices = photos.indices.shuffled()
        } else {
            shuffledIndices = photos.indices.toList()
        }

        // Re-anchor currentIndex on the currently-displayed photo's position
        // in the new shuffle order. Falls back to 0 if that photo is gone
        // (e.g. removed during the sync) or nothing was displayed yet.
        val beforeReanchor = currentIndex
        currentIndex = displayedPhotoId
            ?.let { id -> photos.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?.let { actual -> shuffledIndices.indexOf(actual) }
            ?.takeIf { it >= 0 }
            ?: 0
        Log.d(TAG, "reloadPhotos: re-anchored currentIndex $beforeReanchor -> " +
            "$currentIndex (displayedPhotoId=$displayedPhotoId, photos=${photos.size}, " +
            "shuffle=${prefs.slideshowShuffle})")

        if (wasRunning) {
            resume()
        }
    }

    /**
     * Get the full list of photos in this slideshow.
     */
    fun getPhotos(): List<PhotoItem> = photos

    /**
     * Get the current photo being displayed.
     * Uses lastDisplayedPhoto (set on successful display) to avoid returning
     * a photo that's still loading after an auto-advance.
     */
    fun getCurrentPhoto(): PhotoItem? {
        // Prefer the last successfully displayed photo over currentIndex,
        // since currentIndex advances before the photo finishes loading
        lastDisplayedPhoto?.let { return it }
        if (photos.isEmpty()) return null
        val actualIndex = shuffledIndices.getOrNull(currentIndex) ?: return null
        return photos.getOrNull(actualIndex)
    }

    /**
     * Manually advance to the next photo.
     * In streaming mode (fetchNextPhoto set), step forward through the
     * history buffer if the user has gone back, otherwise pull a fresh
     * photo from the source.
     * In static-list mode, cycles through the internal list as before.
     */
    fun next() {
        // Cancel any pending auto-advance so it can't fire mid-load and add
        // an extra increment on top of this manual navigation.
        handler.removeCallbacks(advanceRunnable)
        if (fetchNextPhoto != null) {
            streamForward(); return
        }
        if (photos.isEmpty()) return
        val from = currentIndex
        currentIndex = (currentIndex + 1) % photos.size
        Log.d(TAG, "next(): currentIndex $from -> $currentIndex " +
            "(photos=${photos.size}, shuffle=${prefs.slideshowShuffle})")
        showCurrentPhoto(isFirst = false)
    }

    /**
     * Manually go to the previous photo.
     * In streaming mode, step back through the history buffer of
     * recently-shown photos (streaming sources only expose a forward
     * fetcher, so without the buffer "previous" would just fetch another
     * fresh photo). In static-list mode, decrement the index.
     */
    fun previous() {
        // Cancel any pending auto-advance so it can't fire mid-load and add
        // an extra increment on top of this manual navigation.
        handler.removeCallbacks(advanceRunnable)
        if (fetchNextPhoto != null) {
            streamBack(); return
        }
        if (photos.isEmpty()) return
        val from = currentIndex
        currentIndex = if (currentIndex > 0) currentIndex - 1 else photos.size - 1
        Log.d(TAG, "previous(): currentIndex $from -> $currentIndex " +
            "(photos=${photos.size}, shuffle=${prefs.slideshowShuffle})")
        showCurrentPhoto(isFirst = false)
    }

    /**
     * Seed the streaming history with the photo currently on screen so the
     * first previous() has somewhere to step back to. The photos shown via
     * the static bootstrap-cache path before streaming kicks in aren't
     * otherwise in the buffer.
     */
    private fun seedStreamHistory() {
        if (streamHistory.isEmpty()) {
            lastDisplayedPhoto?.let {
                streamHistory.add(it)
                streamHistoryPos = 0
                Log.d(TAG, "seedStreamHistory: seeded with ${it.filename}")
            }
        }
    }

    /**
     * Streaming-mode "next": re-show the next photo in the history buffer
     * if the user has stepped back, otherwise fetch a fresh photo from the
     * source and append it to the buffer. Also used by auto-advance.
     */
    private fun streamForward() {
        seedStreamHistory()
        // Behind the tip of history → re-show an already-seen photo.
        if (streamHistoryPos in 0 until streamHistory.lastIndex) {
            streamHistoryPos++
            val photo = streamHistory[streamHistoryPos]
            Log.d(TAG, "streamForward: history $streamHistoryPos/${streamHistory.lastIndex} ${photo.filename}")
            loadJob?.cancel()
            loadJob = coroutineScope.launch { showStreamingPhoto(photo, addToHistory = false) }
            return
        }
        // At the tip → pull a fresh photo from the source.
        val fetcher = fetchNextPhoto ?: return
        loadJob?.cancel()
        loadJob = coroutineScope.launch {
            val next = fetcher()
            if (next != null) {
                Log.d(TAG, "streamForward: fetched fresh ${next.filename}")
                showStreamingPhoto(next, addToHistory = true)
            } else {
                Log.w(TAG, "streamForward: no streaming photo available")
                scheduleNextAdvance()
            }
        }
    }

    /**
     * Streaming-mode "previous": step back through the history buffer.
     * No-op (keeps the current photo) when already at the oldest entry.
     */
    private fun streamBack() {
        seedStreamHistory()
        if (streamHistoryPos <= 0) {
            Log.d(TAG, "streamBack: at oldest history entry (pos=$streamHistoryPos) — staying put")
            scheduleNextAdvance()
            return
        }
        streamHistoryPos--
        val photo = streamHistory[streamHistoryPos]
        Log.d(TAG, "streamBack: history $streamHistoryPos/${streamHistory.lastIndex} ${photo.filename}")
        loadJob?.cancel()
        loadJob = coroutineScope.launch { showStreamingPhoto(photo, addToHistory = false) }
    }

    /**
     * Show the current photo.
     */
    private fun showCurrentPhoto(isFirst: Boolean) {
        if (!isRunning || photos.isEmpty()) return

        val actualIndex = shuffledIndices.getOrNull(currentIndex) ?: return
        val photo = photos.getOrNull(actualIndex) ?: return

        Log.d(TAG, "showCurrentPhoto: currentIndex=$currentIndex actualIndex=$actualIndex " +
            "id=${photo.id} file=${photo.filename} (${currentIndex + 1}/${photos.size})")

        // Determine transition type
        val transition = if (isFirst) {
            PhotoSlideshowView.TransitionType.NONE
        } else {
            when (prefs.slideshowTransition) {
                ScreensaverPreferences.TRANSITION_FADE -> PhotoSlideshowView.TransitionType.FADE
                ScreensaverPreferences.TRANSITION_SLIDE -> PhotoSlideshowView.TransitionType.SLIDE
                else -> PhotoSlideshowView.TransitionType.NONE
            }
        }

        // Load and display photo
        loadJob?.cancel()
        loadJob = coroutineScope.launch {
            val success = view.showPhoto(photo, transition)
            if (success && isRunning) {
                lastDisplayedPhoto = photo
                // Notify that this photo was displayed (for safe eviction tracking)
                onPhotoDisplayed?.invoke(photo.id)
                scheduleNextAdvance()
            } else if (!success) {
                // Skip to next photo if this one failed
                Log.w(TAG, "Failed to load photo, skipping to next")
                handler.postDelayed({
                    if (isRunning) {
                        advanceToNext()
                    }
                }, 500)
            }
        }
    }

    /**
     * Advance to the next photo automatically.
     */
    private fun advanceToNext() {
        if (!isRunning) return

        // Streaming mode — advance forward (steps through history first if
        // the user swiped back, then fetches fresh at the tip).
        if (fetchNextPhoto != null) {
            streamForward()
            return
        }

        // Static list mode (local photos)
        if (photos.isEmpty()) return

        val from = currentIndex
        currentIndex = (currentIndex + 1) % photos.size
        Log.d(TAG, "advanceToNext (auto-advance): currentIndex $from -> $currentIndex")

        // Reshuffle when we've shown all photos
        if (currentIndex == 0 && prefs.slideshowShuffle) {
            shuffledIndices = photos.indices.shuffled()
            Log.d(TAG, "advanceToNext: reshuffled photos for new cycle")
        }

        // Notify listener that we advanced (for prefetching more photos)
        onPhotoAdvanced?.invoke(currentIndex)

        showCurrentPhoto(isFirst = false)
    }

    /**
     * Show a photo from streaming source (HA Media).
     */
    private suspend fun showStreamingPhoto(photo: PhotoItem, addToHistory: Boolean) {
        if (!isRunning) return

        if (addToHistory) {
            streamHistory.add(photo)
            if (streamHistory.size > MAX_STREAM_HISTORY) streamHistory.removeAt(0)
            streamHistoryPos = streamHistory.lastIndex
        }

        Log.d(TAG, "showStreamingPhoto: ${photo.filename} " +
            "(historyPos=$streamHistoryPos/${streamHistory.lastIndex}, addToHistory=$addToHistory)")

        // Determine transition type
        val transition = when (prefs.slideshowTransition) {
            ScreensaverPreferences.TRANSITION_FADE -> PhotoSlideshowView.TransitionType.FADE
            ScreensaverPreferences.TRANSITION_SLIDE -> PhotoSlideshowView.TransitionType.SLIDE
            else -> PhotoSlideshowView.TransitionType.NONE
        }

        val success = view.showPhoto(photo, transition)
        if (success && isRunning) {
            lastDisplayedPhoto = photo
            // Notify that this photo was displayed (for safe eviction tracking)
            onPhotoDisplayed?.invoke(photo.id)
            scheduleNextAdvance()
        } else if (!success) {
            // Photo failed to load - try next
            Log.w(TAG, "Failed to show streaming photo, trying next")
            handler.postDelayed({
                if (isRunning) {
                    advanceToNext()
                }
            }, 500)
        }
    }

    /**
     * Schedule the next automatic advance.
     */
    private fun scheduleNextAdvance() {
        handler.removeCallbacks(advanceRunnable)
        if (!autoAdvanceEnabled) return
        // Schedule if running AND either:
        // - We're in streaming mode (fetchNextPhoto is set), OR
        // - We have more than 1 photo in the static list
        val isStreamingMode = fetchNextPhoto != null
        if (isRunning && (isStreamingMode || photos.size > 1)) {
            val interval = prefs.slideshowInterval * 1000L
            handler.postDelayed(advanceRunnable, interval)
        }
    }

    /**
     * Check if slideshow is currently running.
     */
    fun isPlaying(): Boolean = isRunning

    /**
     * Get total photo count.
     */
    fun getPhotoCount(): Int = photos.size
}
