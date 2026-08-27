/**
 * Memory Tracker for Screensaver
 *
 * Tracks image lifecycle and estimates GPU texture memory usage.
 * Provides diagnostics to help identify memory accumulation patterns
 * and signals to Android when proactive cleanup is needed.
 *
 * Key insight: JavaScript cannot directly measure GPU texture memory,
 * but we can:
 * 1. Track image dimensions loaded → estimate texture footprint
 * 2. Monitor accumulation patterns → detect when cleanup is overdue
 * 3. Signal to Android bridge → trigger native cleanup actions
 */

const MemoryTracker = (() => {
  // Track all images we've loaded
  const imageHistory = [];
  const MAX_HISTORY = 50;  // Keep last 50 images for analysis

  // Texture memory estimation (4 bytes per pixel for RGBA)
  const BYTES_PER_PIXEL = 4;

  // Thresholds for signaling cleanup (estimated texture MB)
  const WARNING_THRESHOLD_MB = 100;   // Signal to Android
  const CRITICAL_THRESHOLD_MB = 200;  // Request urgent cleanup

  // Running totals
  let estimatedActiveTextureMB = 0;
  let totalImagesLoaded = 0;
  let totalImagesCleanedUp = 0;
  let lastCleanupSignalTime = 0;

  // Callbacks
  let onCleanupNeeded = null;

  /**
   * Track a new image being loaded
   * @param {HTMLImageElement} img - The image element
   * @param {string} url - The image URL
   */
  function trackImageLoad(img, url) {
    const entry = {
      url: url.substring(0, 100),  // Truncate for logging
      timestamp: Date.now(),
      width: img.naturalWidth || 0,
      height: img.naturalHeight || 0,
      estimatedMB: 0,
      cleanedUp: false
    };

    // Calculate estimated texture size
    if (entry.width > 0 && entry.height > 0) {
      const bytes = entry.width * entry.height * BYTES_PER_PIXEL;
      entry.estimatedMB = bytes / (1024 * 1024);
      estimatedActiveTextureMB += entry.estimatedMB;
    }

    totalImagesLoaded++;
    imageHistory.push(entry);

    // Trim history
    while (imageHistory.length > MAX_HISTORY) {
      imageHistory.shift();
    }

    console.log(`[MemoryTracker] Image loaded: ${entry.width}x${entry.height} = ${entry.estimatedMB.toFixed(1)}MB, total active: ${estimatedActiveTextureMB.toFixed(1)}MB`);

    // Check thresholds
    checkThresholds();

    return entry;
  }

  /**
   * Track an image being cleaned up (src set to transparent)
   * @param {string} url - The original URL that was cleaned up
   */
  function trackImageCleanup(url) {
    const entry = imageHistory.find(e => url.startsWith(e.url) && !e.cleanedUp);
    if (entry) {
      entry.cleanedUp = true;
      entry.cleanupTime = Date.now();
      estimatedActiveTextureMB -= entry.estimatedMB;
      totalImagesCleanedUp++;

      console.log(`[MemoryTracker] Image cleaned up: ${entry.estimatedMB.toFixed(1)}MB, remaining: ${estimatedActiveTextureMB.toFixed(1)}MB`);
    }
  }

  /**
   * Check thresholds and signal cleanup if needed
   */
  function checkThresholds() {
    const now = Date.now();
    const timeSinceLastSignal = now - lastCleanupSignalTime;

    // Don't spam signals - at most once per 30 seconds
    if (timeSinceLastSignal < 30000) return;

    if (estimatedActiveTextureMB >= CRITICAL_THRESHOLD_MB) {
      console.warn(`[MemoryTracker] CRITICAL: Estimated ${estimatedActiveTextureMB.toFixed(0)}MB of textures`);
      signalCleanupNeeded('critical');
    } else if (estimatedActiveTextureMB >= WARNING_THRESHOLD_MB) {
      console.warn(`[MemoryTracker] WARNING: Estimated ${estimatedActiveTextureMB.toFixed(0)}MB of textures`);
      signalCleanupNeeded('warning');
    }
  }

  /**
   * Signal to Android that cleanup is needed
   * @param {string} level - 'warning' or 'critical'
   */
  function signalCleanupNeeded(level) {
    lastCleanupSignalTime = Date.now();

    // Call registered callback
    if (onCleanupNeeded) {
      onCleanupNeeded(level, getReport());
    }

    // Also post to parent window (Kotlin bridge)
    try {
      window.parent.postMessage({
        type: 'memory-cleanup-needed',
        level: level,
        estimatedTextureMB: estimatedActiveTextureMB,
        imagesLoaded: totalImagesLoaded,
        imagesCleanedUp: totalImagesCleanedUp
      }, '*');
    } catch (e) {
      console.warn('[MemoryTracker] Failed to post cleanup message:', e);
    }
  }

  /**
   * Force aggressive cleanup of all tracked resources
   * Call this periodically to prevent accumulation
   */
  function forceCleanup() {
    console.log('[MemoryTracker] Forcing aggressive cleanup');

    // Mark all as cleaned up
    imageHistory.forEach(entry => {
      if (!entry.cleanedUp) {
        entry.cleanedUp = true;
        entry.cleanupTime = Date.now();
      }
    });

    // Reset estimates (actual GPU memory release requires WebView action)
    estimatedActiveTextureMB = 0;

    // Request garbage collection
    if (window.gc) {
      try {
        window.gc();
        console.log('[MemoryTracker] GC requested');
      } catch (e) {
        // gc() not available in this context
      }
    }

    return {
      cleared: true,
      previousEstimateMB: estimatedActiveTextureMB
    };
  }

  /**
   * Get current memory report
   */
  function getReport() {
    const jsHeap = performance.memory ? {
      usedMB: (performance.memory.usedJSHeapSize / 1024 / 1024).toFixed(1),
      totalMB: (performance.memory.totalJSHeapSize / 1024 / 1024).toFixed(1),
      limitMB: (performance.memory.jsHeapSizeLimit / 1024 / 1024).toFixed(1)
    } : null;

    return {
      timestamp: Date.now(),
      estimatedActiveTextureMB: estimatedActiveTextureMB.toFixed(1),
      totalImagesLoaded,
      totalImagesCleanedUp,
      pendingCleanup: totalImagesLoaded - totalImagesCleanedUp,
      recentHistory: imageHistory.slice(-10).map(e => ({
        size: `${e.width}x${e.height}`,
        mb: e.estimatedMB.toFixed(1),
        cleanedUp: e.cleanedUp
      })),
      jsHeap
    };
  }

  /**
   * Use ImageBitmap for more controlled resource management
   * ImageBitmap.close() explicitly releases GPU resources
   *
   * @param {string} url - Image URL to load
   * @returns {Promise<{bitmap: ImageBitmap, width: number, height: number}>}
   */
  async function loadWithImageBitmap(url) {
    try {
      const response = await fetch(url);
      const blob = await response.blob();
      const bitmap = await createImageBitmap(blob);

      console.log(`[MemoryTracker] ImageBitmap loaded: ${bitmap.width}x${bitmap.height}`);

      return {
        bitmap,
        width: bitmap.width,
        height: bitmap.height,
        close: () => {
          bitmap.close();
          console.log('[MemoryTracker] ImageBitmap closed (resources released)');
        }
      };
    } catch (e) {
      console.error('[MemoryTracker] ImageBitmap load failed:', e);
      throw e;
    }
  }

  /**
   * Set callback for when cleanup is needed
   * @param {Function} callback - (level, report) => void
   */
  function setCleanupCallback(callback) {
    onCleanupNeeded = callback;
  }

  /**
   * Reset all tracking state
   */
  function reset() {
    imageHistory.length = 0;
    estimatedActiveTextureMB = 0;
    totalImagesLoaded = 0;
    totalImagesCleanedUp = 0;
    lastCleanupSignalTime = 0;
    console.log('[MemoryTracker] State reset');
  }

  // Public API
  return {
    trackImageLoad,
    trackImageCleanup,
    forceCleanup,
    getReport,
    loadWithImageBitmap,
    setCleanupCallback,
    reset,

    // Constants for external use
    WARNING_THRESHOLD_MB,
    CRITICAL_THRESHOLD_MB
  };
})();

// Export for use in other modules
if (typeof window !== 'undefined') {
  window.MemoryTracker = MemoryTracker;
}
