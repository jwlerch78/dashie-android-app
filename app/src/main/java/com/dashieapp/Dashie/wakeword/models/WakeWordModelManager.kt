package com.dashieapp.Dashie.wakeword.models

import android.content.Context
import com.dashieapp.Dashie.BuildConfig
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages wake word model updates with silent auto-update.
 *
 * Simplified design:
 * - There's only ONE wake word model at any time
 * - Bundled model is the fallback (ships with APK)
 * - Downloaded model replaces bundled when available
 * - Updates happen silently - user never sees version selection
 * - New model takes effect on next app launch
 *
 * Usage:
 *   val manager = WakeWordModelManager(context)
 *   manager.checkForUpdates()  // Call on app startup (background)
 *   val modelFile = manager.getModelFile()  // Get file to load (null = use bundled)
 */
class WakeWordModelManager(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordModelManager"

        /**
         * Over-the-air wake-word model manifest, on Dashie's PROD Supabase storage.
         *
         * 🔵 **This is a DELIBERATE, RECORDED `dashie-host` residue, not an oversight** — it is
         * the one surviving `supabase.co` string in the Chickadee artifact after the credential
         * cut (2026-08-02), and `lint:chickadee-surface` will keep reporting it. A hit that is
         * intentional should read as intentional, so:
         *
         * - It is a **public storage bucket**: no key, no account, no user data leaves the
         *   device. It is categorically unlike the `SUPABASE_URL`/`ANON_KEY` pair that was
         *   blanked — those were a credential, this is a CDN.
         * - The **trade-off is real and belongs to John, not to this file**: an account-free
         *   Chickadee device still phones a Dashie-controlled host to ask whether a newer wake-word
         *   model exists. Removing it is one line, and the cost is that Chickadee never receives a
         *   model update — its wake word would be frozen at whatever shipped in the APK.
         * - 🔴 Do **not** "fix" this by silently blanking it for Chickadee. That would leave
         *   `checkForModelUpdate` making a request to `https:///…` and failing inside a catch —
         *   the same caught-and-silent shape that made `sendTelemetryNow` worth gating rather than
         *   blanking. If it goes, it goes behind an `EditionSeams` capability with a loud `DROP:`.
         *
         * Flagged for John's call; until then this is the recorded decision.
         */
        private const val MANIFEST_URL = "https://cseaywxcvnxcsypaqaid.supabase.co/storage/v1/object/public/wake-word-models/manifest.json"

        // Storage
        private const val MODELS_DIR = "wake_word_models"
        private const val MODEL_FILENAME = "current.tflite"
        private const val PREFS_NAME = "wake_word_prefs"
        private const val PREF_CURRENT_VERSION = "current_version"
        private const val PREF_PENDING_VERSION = "pending_version"  // Downloaded but not yet active
        private const val PREF_LAST_CHECK = "last_update_check"
        private const val PREF_SELECTED_MODEL_ID = "selected_model_id"  // User-selected bundled model

        // Check for updates at most once per hour
        private const val UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000L

        // Test-version cache (process-wide — instances are created per use site).
        // Lets synchronous callers (the JS bridge) read the last-known value without
        // hitting the network. Refreshed at most once per TEST_VERSION_CACHE_MS.
        private const val TEST_VERSION_CACHE_MS = 10 * 60 * 1000L

        @Volatile
        private var cachedTestVersion: String? = null

        @Volatile
        private var lastTestVersionCheckMs = 0L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }

    private val currentModelFile: File
        get() = File(modelsDir, MODEL_FILENAME)

    private val pendingModelFile: File
        get() = File(modelsDir, "pending.tflite")

    /**
     * Get the currently active model version.
     * Returns bundled version if no downloaded model exists.
     */
    fun getCurrentVersion(): String {
        return prefs.getString(PREF_CURRENT_VERSION, null)
            ?: WakeWordModel.BUNDLED.version
    }

    /**
     * Get the model file to load.
     * Returns null if using bundled model (caller should load from assets).
     */
    fun getModelFile(): File? {
        // First, apply any pending update
        applyPendingUpdate()

        // Check if we have a downloaded model
        return if (currentModelFile.exists() && currentModelFile.length() > 1000) {
            Log.d(TAG, "Using downloaded model v${getCurrentVersion()}")
            currentModelFile
        } else {
            Log.d(TAG, "Using bundled model v${WakeWordModel.BUNDLED.version}")
            null
        }
    }

    /**
     * Get the active model info (for logging/display).
     * Checks for a selected bundled model first, then falls back to downloaded/bundled EI.
     */
    fun getActiveModel(): WakeWordModel {
        // Check if user selected a specific bundled model
        val selectedModelId = prefs.getString(PREF_SELECTED_MODEL_ID, null)
        Log.i(TAG, "getActiveModel: selectedModelId=$selectedModelId")
        if (selectedModelId != null) {
            val model = WakeWordModel.fromId(selectedModelId)
            Log.i(TAG, "getActiveModel: returning ${model.wakeWordName} v${model.version} (asset=${model.assetPath})")
            return model
        }

        // Legacy path: downloaded EI model or default Hey Dashie (dual engine)
        val version = getCurrentVersion()
        return if (currentModelFile.exists()) {
            Log.i(TAG, "getActiveModel: legacy path - downloaded model v$version")
            WakeWordModel(
                version = version,
                downloadUrl = "",  // Already downloaded
                sizeKb = (currentModelFile.length() / 1024).toInt()
            )
        } else {
            val default = defaultModelForEdition()
            Log.i(TAG, "getActiveModel: default - ${default.wakeWordName} (edition=${BuildConfig.EDITION})")
            default
        }
    }

    /**
     * The wake word a device listens for when the user has never chosen one.
     *
     * 🔴 **The DEFAULT is the brand difference — both models still ship** (B's both-ship ruling,
     * John 2026-08-02). Chickadee's live site promises "say Chickadee" and the add-on defaults to
     * it; an APK that shipped listening for "Hey Dashie" made that promise false, which is how a
     * tester ended up reporting silent voice that was in fact working perfectly for a phrase
     * nobody told them to say.
     *
     * Keyed off `BuildConfig.EDITION` — the same brand axis as `ApiPaths` (#63) and
     * `DashieMdnsService` (#62), exhaustive with a loud `DROP:` — rather than a fourth
     * hand-rolled per-flavor field. An unknown edition falls back to Hey Dashie, which is the
     * safe direction: it is the model every existing install already uses.
     *
     * ⚠️ Verified before switching: BOTH Chickadee assets ship in the chickadeeDev APK
     * (`assets/models/chickadee.tflite` + `assets/models/mww/chickadee.tflite`, dual-gate).
     * Defaulting to a model that is not bundled would be strictly worse than the wrong phrase —
     * it would be no wake word at all.
     */
    fun defaultModelForEdition(): WakeWordModel = when (BuildConfig.EDITION) {
        "dashie" -> WakeWordModel.HEY_DASHIE
        "chickadee" -> WakeWordModel.CHICKADEE
        else -> {
            Log.w(TAG, "DROP: unrecognised BuildConfig.EDITION '${BuildConfig.EDITION}' — no " +
                "default wake model is defined for it. Falling back to Hey Dashie, which is " +
                "WRONG for any non-Dashie edition (the device will listen for a phrase that " +
                "brand never advertises). Add the edition here.")
            WakeWordModel.HEY_DASHIE
        }
    }

    /**
     * Select a specific bundled model (EI or MWW).
     * Takes effect immediately — caller must reinitialize the detector.
     *
     * @return the selected model
     */
    fun selectModel(model: WakeWordModel): WakeWordModel {
        Log.i(TAG, "Selecting model: ${model.wakeWordName} [${model.modelId}] (${model.engine.displayName})")
        prefs.edit().putString(PREF_SELECTED_MODEL_ID, model.modelId).commit()
        return model
    }

    /**
     * Select a model by its ID string.
     * @return the selected model (falls back to BUNDLED_EI if ID not found)
     */
    fun selectModelById(modelId: String): WakeWordModel {
        val model = WakeWordModel.fromId(modelId)
        return selectModel(model)
    }

    /**
     * Check for updates and download if available.
     * Call this on app startup - it runs in background and doesn't block.
     * New model will take effect on next app launch.
     */
    suspend fun checkForUpdates(): Boolean {
        // Throttle update checks
        val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
        val now = System.currentTimeMillis()
        if (now - lastCheck < UPDATE_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Skipping update check - checked recently")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Checking for wake word model updates...")
                prefs.edit().putLong(PREF_LAST_CHECK, now).commit()

                // Fetch manifest
                val manifest = fetchManifest() ?: return@withContext false

                // Compare versions
                val currentVersion = getCurrentVersion()
                if (!isNewerVersion(manifest.latestVersion, currentVersion)) {
                    Log.d(TAG, "Already on latest version ($currentVersion)")
                    return@withContext false
                }

                // Skip test versions (contain "T")
                if (manifest.latestVersion.contains("T", ignoreCase = true)) {
                    Log.d(TAG, "Skipping test version: ${manifest.latestVersion}")
                    return@withContext false
                }

                Log.i(TAG, "New version available: ${manifest.latestVersion} (current: $currentVersion)")

                // Download to pending file
                val success = downloadModel(manifest.downloadUrl, manifest.latestVersion)
                if (success) {
                    Log.i(TAG, "Update downloaded - will apply on next app launch")
                }
                success

            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                false
            }
        }
    }

    /**
     * Fetch the manifest from server.
     */
    private fun fetchManifest(): WakeWordManifest? {
        return try {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Manifest fetch failed: ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            WakeWordManifest.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching manifest", e)
            null
        }
    }

    /**
     * Download model to pending file.
     *
     * 🔵 **Delegates to [ModelInstaller] since 2026-08-04** (O's v1 seam ruling). This method used
     * to hand-roll download → temp file → size check → rename, and on-device STT was about to
     * author a second copy of exactly that. The shared core is the same shape with two upgrades
     * this path gains for free: the `.part` file is verified *before* the rename, so a partial
     * download can never occupy [pendingModelFile], and every failure is a distinct greppable
     * `DROP:` rather than a bare `Log.e`.
     *
     * ⚠️ **This call passes `expectedSha256 = null`, and that is a KNOWN GAP, not an oversight.**
     * The wake-word manifest has no digest field — `WakeWordManifest` carries only
     * `latestVersion`/`downloadUrl`/`sizeKb`/`manifestVersion`, and the live manifest confirms it.
     * There is nothing on the wire to pin against, so [ModelInstaller] logs a loud
     * `DROP: [unexpected] … supplied NO sha256`. **That marker is the point**: this path has always
     * been unverified (its only check was a 1000-byte floor) and the gap was invisible. Now it
     * announces itself every time.
     *
     * ➡️ To close it: add `sha256` to the manifest JSON, then pass it here. No change to
     * [ModelInstaller] is needed — it already verifies whenever a digest is supplied.
     */
    private fun downloadModel(url: String, version: String): Boolean {
        Log.d(TAG, "Downloading model v$version from $url")

        val result = com.dashieapp.Dashie.download.ModelInstaller.install(
            url = url,
            destination = pendingModelFile,
            expectedSha256 = null,   // see KDoc — the manifest has no digest field yet
            tag = "wakeword v$version",
        )

        if (result !is com.dashieapp.Dashie.download.ModelInstaller.Result.Installed) return false

        // Only recorded once the file is verifiably in place — the pending VERSION and the pending
        // FILE must not be able to disagree, or applyPendingUpdate would promote a version whose
        // bytes never landed.
        prefs.edit().putString(PREF_PENDING_VERSION, version).commit()
        Log.i(TAG, "Model v$version downloaded successfully (${pendingModelFile.length()} bytes)")
        return true
    }

    /**
     * Apply pending update if available.
     * Called automatically when getModelFile() is invoked (on app startup).
     */
    private fun applyPendingUpdate() {
        val pendingVersion = prefs.getString(PREF_PENDING_VERSION, null) ?: return

        if (!pendingModelFile.exists()) {
            // Pending file missing - clear the flag
            prefs.edit().remove(PREF_PENDING_VERSION).commit()
            return
        }

        try {
            Log.i(TAG, "Applying pending update: v$pendingVersion")

            // Delete old model
            if (currentModelFile.exists()) {
                currentModelFile.delete()
            }

            // Move pending to current
            pendingModelFile.renameTo(currentModelFile)

            // Update version tracking
            prefs.edit()
                .putString(PREF_CURRENT_VERSION, pendingVersion)
                .remove(PREF_PENDING_VERSION)
                .commit()

            Log.i(TAG, "Wake word model updated to v$pendingVersion")

        } catch (e: Exception) {
            Log.e(TAG, "Error applying pending update", e)
            // Don't clear pending version - try again next time
        }
    }

    /**
     * Compare version strings to determine if newVersion is newer than currentVersion.
     * Handles versions like "3.3", "3.4", "3.10", etc.
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        try {
            // Remove any non-numeric suffix (like "T" for test versions)
            val newParts = newVersion.replace(Regex("[^0-9.]"), "").split(".")
            val currentParts = currentVersion.replace(Regex("[^0-9.]"), "").split(".")

            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrNull(i)?.toIntOrNull() ?: 0
                val currentPart = currentParts.getOrNull(i)?.toIntOrNull() ?: 0

                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }
            return false  // Equal versions
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions: $newVersion vs $currentVersion", e)
            return false
        }
    }

    /**
     * Force an update check (ignores throttling).
     * Useful for testing or manual refresh.
     */
    suspend fun forceCheckForUpdates(): Boolean {
        prefs.edit().remove(PREF_LAST_CHECK).commit()
        return checkForUpdates()
    }

    /**
     * Clear the update check throttle.
     * Called when app is updated to force a fresh check.
     */
    fun clearManifestCache() {
        prefs.edit().remove(PREF_LAST_CHECK).commit()
        Log.i(TAG, "Update check throttle cleared")
    }

    /**
     * Clear all downloaded models and reset to bundled.
     */
    fun resetToBundled() {
        currentModelFile.delete()
        pendingModelFile.delete()
        prefs.edit()
            .remove(PREF_CURRENT_VERSION)
            .remove(PREF_PENDING_VERSION)
            .commit()
        Log.i(TAG, "Reset to bundled model")
    }

    /**
     * Download test/beta model from manifest (ignores "T" suffix check).
     * Used for manual testing of new wake word versions.
     * Returns the version downloaded, or null if failed.
     */
    suspend fun downloadTestModel(): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Downloading test wake word model...")

                // Fetch manifest
                val manifest = fetchManifest() ?: run {
                    Log.e(TAG, "Failed to fetch manifest")
                    return@withContext null
                }

                Log.i(TAG, "Manifest version: ${manifest.latestVersion}")

                // Download regardless of version (no "T" check)
                val success = downloadModel(manifest.downloadUrl, manifest.latestVersion)
                if (success) {
                    Log.i(TAG, "Test model downloaded - will apply on next app launch")
                    manifest.latestVersion
                } else {
                    null
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading test model", e)
                null
            }
        }
    }

    /**
     * Check if a test/beta version is available in the manifest.
     * Returns the version string if available, null otherwise.
     * On fetch failure, falls back to the last successfully-fetched value.
     */
    suspend fun getAvailableTestVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val manifest = fetchManifest() ?: return@withContext cachedTestVersion
                // Only return if it's a test version (contains "T")
                val testVersion = if (manifest.latestVersion.contains("T", ignoreCase = true)) {
                    manifest.latestVersion
                } else {
                    null
                }
                lastTestVersionCheckMs = System.currentTimeMillis()
                cachedTestVersion = testVersion
                testVersion
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for test version", e)
                cachedTestVersion
            }
        }
    }

    /**
     * Non-blocking variant for synchronous callers (the JS bridge): returns the
     * cached test version immediately and, if the cache is stale, refreshes it on
     * a background thread so the NEXT query sees fresh data.
     *
     * Synchronous `@JavascriptInterface` methods must never hit the network — the
     * WebView's JS thread blocks on the return value, so a manifest fetch here
     * froze all page JS for up to the 30s/60s HTTP timeouts.
     */
    fun getCachedTestVersionAndRefresh(): String? {
        val now = System.currentTimeMillis()
        if (now - lastTestVersionCheckMs > TEST_VERSION_CACHE_MS) {
            lastTestVersionCheckMs = now  // claim the slot so concurrent callers don't double-fetch
            Thread({
                kotlinx.coroutines.runBlocking { getAvailableTestVersion() }
            }, "WakeWordTestVersionRefresh").apply { isDaemon = true }.start()
        }
        return cachedTestVersion
    }
}
