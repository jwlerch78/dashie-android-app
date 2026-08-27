/**
 * Screensaver Controller for Dashie Lite
 *
 * Simple "dumb renderer" - Android controls everything:
 * - When to activate/deactivate
 * - Which photo to show and when
 * - Photo metadata (date, location from EXIF)
 *
 * This JS just renders what Android tells it to.
 */

import { addLayer, removeLayer, getActiveLayers } from './pointer-layer-manager.js';

// ==================== DIAGNOSTIC LOGGING FOR WHITE SCREEN DEBUG ====================
const SS_DIAG_PREFIX = '[SS:DIAG]';
function ssDiagLog(msg, data) {
  const timestamp = new Date().toISOString().substring(11, 23);
  console.log(`${SS_DIAG_PREFIX} [${timestamp}] ${msg}`, data || '');
}

function notifyParentScreensaverState(active, mode) {
  if (window.parent && window.parent !== window) {
    window.parent.postMessage({
      source: 'dashie-overlay',
      type: 'screensaver-state',
      active,
      mode
    }, '*');
  }
}

function dumpScreensaverState() {
  const screensaverEl = document.getElementById('screensaver');
  if (!screensaverEl) {
    ssDiagLog('Screensaver element NOT FOUND');
    return;
  }

  const styles = window.getComputedStyle(screensaverEl);
  ssDiagLog('Screensaver state dump:', {
    isActive,
    currentMode,
    showClock,
    showMetadata,
    className: screensaverEl.className,
    computedOpacity: styles.opacity,
    computedVisibility: styles.visibility,
    computedDisplay: styles.display,
    computedPointerEvents: styles.pointerEvents,
    computedBackground: styles.backgroundColor,
    activeLayers: getActiveLayers()
  });
}
// ==================== END DIAGNOSTIC LOGGING ====================

// ==================== DOM Elements ====================

const screensaver = document.getElementById('screensaver');
const screensaverTime = document.getElementById('screensaverTime');
const screensaverAmPm = document.getElementById('screensaverAmPm');
const screensaverMetadataDate = document.getElementById('screensaverMetadataDate');
const screensaverMetadataLocation = document.getElementById('screensaverMetadataLocation');
// Photo elements for crossfade (two of each for smooth transitions)
const screensaverPhotoBg1 = document.getElementById('screensaverPhotoBg1');
const screensaverPhotoBg2 = document.getElementById('screensaverPhotoBg2');
const screensaverPhoto1 = document.getElementById('screensaverPhoto1');
const screensaverPhoto2 = document.getElementById('screensaverPhoto2');

// Initialize photo elements with transparent pixel to prevent "missing image" icon
const TRANSPARENT_PIXEL = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
screensaverPhotoBg1.src = TRANSPARENT_PIXEL;
screensaverPhotoBg2.src = TRANSPARENT_PIXEL;
screensaverPhoto1.src = TRANSPARENT_PIXEL;
screensaverPhoto2.src = TRANSPARENT_PIXEL;

// ==================== State ====================

let isActive = false;
let clockInterval = null;
let showClock = true;
let showMetadata = true;
let use24HourClock = false;  // Universal preference from HalitePreferences
let currentPhotoUrl = null;
let currentMode = 'photos'; // 'dim', 'black', 'photos', 'url'
let screensaverUrl = null;  // URL to display when mode is 'url'
let clockPosition = 'center';  // 'center', 'top-right', 'top-left', 'bottom-right', 'bottom-left'
let clockFontSize = 40;        // Font size in px (resolved from preset or custom)

// Track which photo element is currently showing (for crossfade)
let activePhotoElement = null;
let activePhotoBgElement = null;

// URL iframe element (created on demand)
let urlIframe = null;

// Timeout for clearing metadata (cancelled if EXIF arrives in time)
let metadataClearTimeout = null;

// Track if metadata was received for current photo (handles race condition where
// updateMetadata arrives before startTransition runs)
let metadataReceivedForCurrentUrl = null;

// ==================== Parent Communication ====================

// Pointer-events are managed by pointer-layer-manager.js.
// Each feature registers/unregisters its layer name.
// The manager enables pointer-events if ANY layer is active.

// ==================== Public API ====================

/**
 * Configure screensaver options.
 * Called by Android before activation.
 *
 * @param {Object} config
 * @param {string} config.mode - 'dim', 'black', 'photos', or 'url'
 * @param {boolean} config.showClock - Whether to show clock overlay
 * @param {boolean} config.showMetadata - Whether to show photo metadata
 * @param {string} config.url - URL to display when mode is 'url'
 */
function configure(config) {
  console.log('[Screensaver] configure called with:', JSON.stringify(config));
  console.log('[Screensaver] Before: mode=' + currentMode + ', showClock=' + showClock + ', showMetadata=' + showMetadata + ', use24Hour=' + use24HourClock);

  if (config.mode !== undefined) currentMode = config.mode;
  if (config.showClock !== undefined) showClock = config.showClock;
  if (config.showMetadata !== undefined) showMetadata = config.showMetadata;
  if (config.url !== undefined) screensaverUrl = config.url;
  if (config.use24HourClock !== undefined) use24HourClock = config.use24HourClock;
  if (config.clockPosition !== undefined) clockPosition = config.clockPosition;
  if (config.clockFontSize !== undefined) clockFontSize = config.clockFontSize;

  // Apply clock position and size via CSS variables
  applyClockStyle();

  console.log('[Screensaver] After: mode=' + currentMode + ', showClock=' + showClock + ', showMetadata=' + showMetadata + ', use24Hour=' + use24HourClock + ', clockPos=' + clockPosition + ', clockFont=' + clockFontSize);

  // If screensaver is already active, update the classes immediately
  if (isActive) {
    console.log('[Screensaver] Screensaver is active, updating classes immediately');
    screensaver.className = `screensaver screensaver--${currentMode} screensaver--active`;
    if (showClock) {
      screensaver.classList.add('screensaver--show-clock');
    }
    if (showMetadata && currentMode === 'photos') {
      screensaver.classList.add('screensaver--show-metadata');
    }
    console.log('[Screensaver] Updated className:', screensaver.className);
  }
}

/**
 * Activate the screensaver.
 * Called by Android when inactivity timeout reached.
 */
function activate() {
  ssDiagLog('activate() called', { wasActive: isActive, mode: currentMode });

  if (isActive) {
    ssDiagLog('Already active, returning early');
    return;
  }

  console.log('[Screensaver] activate, mode:', currentMode, 'showClock:', showClock, 'showMetadata:', showMetadata);
  isActive = true;

  // Build class list based on mode
  screensaver.className = `screensaver screensaver--${currentMode} screensaver--active`;
  if (showClock) {
    screensaver.classList.add('screensaver--show-clock');
    console.log('[Screensaver] Clock enabled, added screensaver--show-clock class');
  }
  if (showMetadata && currentMode === 'photos') {
    screensaver.classList.add('screensaver--show-metadata');
    console.log('[Screensaver] Metadata enabled for photos mode');
  }

  console.log('[Screensaver] Final className:', screensaver.className);
  ssDiagLog('Set className to:', screensaver.className);

  // Request fullscreen from parent (screensaver needs full screen)
  if (window._requestFullscreen) {
    window._requestFullscreen('screensaver');
  }

  // Start clock (for all modes that show it)
  if (showClock) {
    updateClock();
    clockInterval = setInterval(updateClock, 1000);
    console.log('[Screensaver] Clock interval started');
  }

  // Handle URL mode - load iframe
  if (currentMode === 'url' && screensaverUrl) {
    showUrlScreensaver(screensaverUrl);
  }

  // Register screensaver layer for pointer-events
  ssDiagLog('Adding screensaver layer to pointer-events manager');
  addLayer('screensaver');

  // Notify Android
  if (window.DashieBridge?.onScreensaverStateChanged) {
    window.DashieBridge.onScreensaverStateChanged(true);
  }

  // Notify parent iframe for diagnostic tracking
  notifyParentScreensaverState(true, currentMode);

  // Dump state after activation
  setTimeout(() => {
    ssDiagLog('Post-activation state check');
    dumpScreensaverState();
  }, 100);
}

/**
 * Deactivate the screensaver.
 * Called by Android on user interaction.
 */
function deactivate() {
  ssDiagLog('deactivate() called', { wasActive: isActive });

  console.log('[Screensaver] deactivate');
  isActive = false;

  // Stop clock
  if (clockInterval) {
    clearInterval(clockInterval);
    clockInterval = null;
  }

  // Hide screensaver
  const oldClassName = screensaver.className;
  screensaver.className = 'screensaver';
  ssDiagLog('Reset className', { from: oldClassName, to: screensaver.className });

  // Clear photos
  clearPhotos();

  // Clear URL iframe
  clearUrlScreensaver();

  // Unregister screensaver layer for pointer-events
  ssDiagLog('Removing screensaver layer from pointer-events manager');
  removeLayer('screensaver');

  // Release fullscreen (screensaver no longer needs it)
  if (window._releaseFullscreen) {
    window._releaseFullscreen('screensaver');
  }

  // Notify Android
  if (window.DashieBridge?.onScreensaverStateChanged) {
    window.DashieBridge.onScreensaverStateChanged(false);
  }

  // Notify parent iframe for diagnostic tracking
  notifyParentScreensaverState(false, currentMode);

  // Dump state after deactivation
  setTimeout(() => {
    ssDiagLog('Post-deactivation state check');
    dumpScreensaverState();
  }, 100);
}

/**
 * Show a photo. Called by Android for each photo in slideshow.
 * Android controls timing - we just display what we're told.
 *
 * Key insight: We're running in an iframe, so postMessage adds latency.
 * We must fully preload and decode images BEFORE starting any transition
 * to prevent the "missing image" icon flash and ensure smooth crossfades.
 */
function showPhoto(url, dateTaken, location) {
  if (!url) return;
  console.log('[Screensaver] showPhoto:', url);

  currentPhotoUrl = url;

  // Reset the metadata received tracker for this new photo
  // (will be set by updateMetadata if it arrives before startTransition)
  metadataReceivedForCurrentUrl = null;

  // Ensure screensaver is active
  if (!isActive) {
    activate();
  }

  // Pick the next photo element (alternate for crossfade)
  const nextPhoto = activePhotoElement === screensaverPhoto1 ? screensaverPhoto2 : screensaverPhoto1;
  const nextBg = activePhotoBgElement === screensaverPhotoBg1 ? screensaverPhotoBg2 : screensaverPhotoBg1;

  console.log('[Screensaver] Setting up crossfade...');

  // STEP 1: Ensure elements start invisible (opacity 0)
  nextPhoto.classList.remove('screensaver__photo--active');
  nextBg.classList.remove('screensaver__photo-bg--active');

  // STEP 2: Clear any existing handlers FIRST (prevents stale callbacks)
  nextPhoto.onload = null;
  nextPhoto.onerror = null;

  // STEP 3: Define the load handler
  const onPhotoLoad = async () => {
    // CRITICAL: Validate this onload is for the expected URL
    // Cleanup sets src to TRANSPARENT_PIXEL which triggers onload - ignore those
    // Note: We use startsWith because we may have added a cache-buster query param
    if (!nextPhoto.src.startsWith(url)) {
      console.log('[Screensaver] Ignoring stale onload (src changed):', nextPhoto.src.substring(0, 50));
      return;
    }

    const isPortrait = nextPhoto.naturalHeight > nextPhoto.naturalWidth;
    console.log('[Screensaver] Photo loaded:', `${nextPhoto.naturalWidth}x${nextPhoto.naturalHeight}`, isPortrait ? 'PORTRAIT' : 'landscape');

    // Use decode() to ensure image is fully decoded before transitioning
    // This prevents flash of partially-rendered images
    try {
      await nextPhoto.decode();
      console.log('[Screensaver] Photo decoded successfully');
    } catch (e) {
      console.warn('[Screensaver] Photo decode failed, continuing anyway:', e);
    }

    if (isPortrait) {
      // Set background to same URL for portrait photos
      nextBg.src = url;

      // Wait for background to load AND decode
      const waitForBg = () => {
        return new Promise((resolve) => {
          const onBgReady = async () => {
            try {
              await nextBg.decode();
              console.log('[Screensaver] Background decoded successfully');
            } catch (e) {
              console.warn('[Screensaver] Background decode failed:', e);
            }
            resolve();
          };

          if (nextBg.complete && nextBg.naturalHeight > 0) {
            onBgReady();
          } else {
            nextBg.onload = onBgReady;
            nextBg.onerror = () => {
              console.warn('[Screensaver] Background load failed');
              resolve();
            };
          }
        });
      };

      await waitForBg();
      console.log('[Screensaver] Background ready, starting transition');
      startTransition(isPortrait);
    } else {
      // Landscape: no background needed
      nextBg.src = '';
      console.log('[Screensaver] Landscape mode, starting transition');
      startTransition(false);
    }
  };

  const startTransition = (isPortrait) => {
    // Both images are loaded and ready - start the crossfade
    // Force reflow to ensure opacity:0 is rendered
    void nextPhoto.offsetWidth;
    if (isPortrait) void nextBg.offsetWidth;

    // Capture OLD elements before updating references
    const oldPhoto = activePhotoElement;
    const oldBg = activePhotoBgElement;

    const nextId = nextPhoto.id;
    const oldId = oldPhoto?.id || 'null';
    console.log(`[Screensaver] startTransition: next=${nextId}, old=${oldId}, isPortrait=${isPortrait}`);

    // Update references IMMEDIATELY (before async operations)
    activePhotoElement = nextPhoto;
    activePhotoBgElement = nextBg;

    // Start fade-in transition (CSS handles the 1s animation)
    requestAnimationFrame(() => {
      console.log(`[Screensaver] Adding --active to ${nextId}, isPortrait=${isPortrait}`);

      // CRITICAL: Remove --active from old photo IMMEDIATELY
      // This ensures new photo (z-index 2) is always above old photo (z-index 1)
      // Without this, DOM order determines z-index when both have --active
      if (oldPhoto) {
        oldPhoto.classList.remove('screensaver__photo--active');
      }
      if (oldBg) {
        oldBg.classList.remove('screensaver__photo-bg--active');
      }

      // Set landscape class for black background (only needed when no blurred bg)
      if (isPortrait) {
        nextPhoto.classList.remove('screensaver__photo--landscape');
        nextBg.classList.add('screensaver__photo-bg--active');
      } else {
        nextPhoto.classList.add('screensaver__photo--landscape');
      }

      nextPhoto.classList.add('screensaver__photo--active');
    });

    // Clean up old elements after transition completes (reset src to free memory)
    // Note: --active classes are already removed at transition START for z-index correctness
    setTimeout(() => {
      if (oldPhoto && oldPhoto !== nextPhoto) {
        console.log(`[Screensaver] Cleanup: resetting ${oldPhoto.id} src`);
        oldPhoto.classList.remove('screensaver__photo--landscape');
        // Clear onload handler BEFORE setting src to prevent spurious callbacks
        oldPhoto.onload = null;
        oldPhoto.src = TRANSPARENT_PIXEL; // Reset to transparent pixel (prevents "missing image" icon)
      }
      if (oldBg && oldBg !== nextBg) {
        oldBg.onload = null;
        oldBg.src = TRANSPARENT_PIXEL;
      }
    }, 1200);

    // Cancel any pending metadata clear timeout from a previous photo
    if (metadataClearTimeout) {
      clearTimeout(metadataClearTimeout);
      metadataClearTimeout = null;
    }

    // ONLY update metadata if we have actual values from this call
    // If dateTaken/location are null, don't clear - Android will send them
    // via updateMetadata() once EXIF extraction completes
    // This prevents the flicker where metadata clears before new data arrives
    if (dateTaken || location) {
      updateMetadata(dateTaken, location);
    } else {
      // Check if metadata already arrived via updateMetadata() BEFORE startTransition ran
      // (This handles the race condition where EXIF extraction completes quickly)
      if (metadataReceivedForCurrentUrl === url) {
        console.log('[Screensaver] Metadata already received for this photo (before transition), skipping timeout');
      } else {
        // Clear metadata when a new photo starts loading, but give EXIF extraction
        // a moment to arrive. Use a short delay so metadata doesn't show "wrong" data
        // for the new photo. If EXIF data arrives quickly, updateMetadata() will cancel this.
        console.log('[Screensaver] Photo has no inline metadata, waiting for EXIF extraction...');
        // Clear after 1.5 seconds if no metadata has arrived
        metadataClearTimeout = setTimeout(() => {
          // Only clear if this is still the current photo
          if (currentPhotoUrl === url) {
            console.log('[Screensaver] No EXIF metadata received, clearing display');
            updateMetadata(null, null);
          }
          metadataClearTimeout = null;
        }, 1500);
      }
    }
  };

  // STEP 4: Set up handlers BEFORE setting src (prevents race condition)
  // If src is set first, onload might fire before handler is assigned
  console.log('[Screensaver] Waiting for photo to load...');

  // Timeout fallback: if onload doesn't fire within 4 seconds, force transition
  // WebView can sometimes fail silently, especially under memory pressure
  let loadTimeout = setTimeout(() => {
    console.warn('[Screensaver] Photo load TIMEOUT - forcing transition');
    nextPhoto.onload = null;
    nextPhoto.onerror = null;
    startTransition(false);
  }, 4000);

  nextPhoto.onload = () => {
    clearTimeout(loadTimeout);
    onPhotoLoad();
  };
  nextPhoto.onerror = () => {
    clearTimeout(loadTimeout);
    console.error('[Screensaver] Photo load FAILED:', url);
    // Still try to display (Android intercept might serve it)
    startTransition(false);
  };

  // STEP 5: NOW set src to trigger the load
  // Add cache-buster to force WebView to fire onload even for repeated URLs
  const cacheBuster = `_cb=${Date.now()}`;
  const urlWithCacheBuster = url.includes('?') ? `${url}&${cacheBuster}` : `${url}?${cacheBuster}`;
  nextPhoto.src = urlWithCacheBuster;

  // STEP 6: Check if already loaded (cache hit) - manually trigger since event may have fired
  if (nextPhoto.complete && nextPhoto.naturalHeight > 0) {
    clearTimeout(loadTimeout);
    console.log('[Screensaver] Photo already loaded (cache hit)');
    onPhotoLoad();
  }
}

/**
 * Update metadata display. Called by Android after EXIF extraction.
 */
function updateMetadata(dateTaken, location, forUrl) {
  // Ignore stale updates (for a different photo)
  if (forUrl && forUrl !== currentPhotoUrl) {
    console.log('[Screensaver] Ignoring stale metadata for:', forUrl);
    return;
  }

  // If we received actual data, cancel any pending clear timeout
  // This prevents the "clear after 1.5s" timeout from wiping out data that arrived
  if (dateTaken || location) {
    // Track that we received metadata for this URL
    // This handles the race condition where updateMetadata arrives BEFORE startTransition runs
    const metadataUrl = forUrl || currentPhotoUrl;
    metadataReceivedForCurrentUrl = metadataUrl;
    console.log('[Screensaver] Received metadata for:', metadataUrl, '- marking as received');

    if (metadataClearTimeout) {
      console.log('[Screensaver] Cancelling pending clear timeout');
      clearTimeout(metadataClearTimeout);
      metadataClearTimeout = null;
    }
  }

  if (!showMetadata) {
    if (screensaverMetadataDate) screensaverMetadataDate.style.display = 'none';
    if (screensaverMetadataLocation) screensaverMetadataLocation.style.display = 'none';
    return;
  }

  // Date (bottom left)
  if (screensaverMetadataDate) {
    if (dateTaken) {
      screensaverMetadataDate.textContent = dateTaken;
      screensaverMetadataDate.style.display = 'block';
    } else {
      screensaverMetadataDate.style.display = 'none';
    }
  }

  // Location (bottom right)
  if (screensaverMetadataLocation) {
    if (location) {
      screensaverMetadataLocation.textContent = location;
      screensaverMetadataLocation.style.display = 'block';
    } else {
      screensaverMetadataLocation.style.display = 'none';
    }
  }
}

/**
 * Check if screensaver is active.
 */
function isScreensaverActive() {
  return isActive;
}

// ==================== Internal ====================

const CLOCK_POSITIONS = {
  'center':       { top: '50%', left: '50%', right: 'auto', bottom: 'auto', transform: 'translate(-50%, -50%)', align: 'center' },
  'top-right':    { top: '64px', left: 'auto', right: '48px', bottom: 'auto', transform: 'none', align: 'right' },
  'top-left':     { top: '64px', left: '48px', right: 'auto', bottom: 'auto', transform: 'none', align: 'left' },
  'bottom-right': { top: 'auto', left: 'auto', right: '48px', bottom: '64px', transform: 'none', align: 'right' },
  'bottom-left':  { top: 'auto', left: '48px', right: 'auto', bottom: '64px', transform: 'none', align: 'left' }
};

function applyClockStyle() {
  const clockEl = document.querySelector('.screensaver__clock');
  if (!clockEl) return;

  clockEl.style.setProperty('--clock-font-size', clockFontSize + 'px');

  const pos = CLOCK_POSITIONS[clockPosition] || CLOCK_POSITIONS['center'];
  clockEl.style.setProperty('--clock-top', pos.top);
  clockEl.style.setProperty('--clock-left', pos.left);
  clockEl.style.setProperty('--clock-right', pos.right);
  clockEl.style.setProperty('--clock-bottom', pos.bottom);
  clockEl.style.setProperty('--clock-transform', pos.transform);
  clockEl.style.setProperty('--clock-align', pos.align);
}

function updateClock() {
  const now = new Date();
  const rawHours = now.getHours();
  const minutes = now.getMinutes().toString().padStart(2, '0');

  if (use24HourClock) {
    // 24-hour format: 14:30
    const hours24 = rawHours.toString().padStart(2, '0');
    if (screensaverTime) screensaverTime.textContent = `${hours24}:${minutes}`;
    if (screensaverAmPm) screensaverAmPm.textContent = ''; // Hide AM/PM in 24-hour mode
  } else {
    // 12-hour format: 2:30 pm
    const ampm = rawHours >= 12 ? 'pm' : 'am';
    const hours12 = rawHours % 12 || 12;
    if (screensaverTime) screensaverTime.textContent = `${hours12}:${minutes}`;
    if (screensaverAmPm) screensaverAmPm.textContent = ampm;
  }
}

function clearPhotos() {
  currentPhotoUrl = null;
  activePhotoElement = null;
  activePhotoBgElement = null;

  [screensaverPhoto1, screensaverPhoto2].forEach(el => {
    if (el) {
      el.classList.remove('screensaver__photo--active');
      el.classList.remove('screensaver__photo--landscape');
      el.onload = null; // Clear handler before changing src
      el.src = TRANSPARENT_PIXEL; // Use transparent pixel instead of empty string
    }
  });
  [screensaverPhotoBg1, screensaverPhotoBg2].forEach(el => {
    if (el) {
      el.classList.remove('screensaver__photo-bg--active');
      el.onload = null;
      el.src = TRANSPARENT_PIXEL;
    }
  });

  if (screensaverMetadataDate) screensaverMetadataDate.style.display = 'none';
  if (screensaverMetadataLocation) screensaverMetadataLocation.style.display = 'none';
}

/**
 * Show URL screensaver - creates an iframe with the specified URL.
 */
function showUrlScreensaver(url) {
  console.log('[Screensaver] showUrlScreensaver:', url);

  // Create iframe if it doesn't exist
  if (!urlIframe) {
    urlIframe = document.createElement('iframe');
    urlIframe.className = 'screensaver__url-iframe';
    urlIframe.setAttribute('allow', 'fullscreen; autoplay');
    urlIframe.setAttribute('frameborder', '0');
    screensaver.appendChild(urlIframe);
  }

  // Set URL
  urlIframe.src = url;
}

/**
 * Clear URL screensaver - remove iframe.
 */
function clearUrlScreensaver() {
  if (urlIframe) {
    urlIframe.src = 'about:blank';
    urlIframe.remove();
    urlIframe = null;
  }
}

// ==================== Photo Thumbnail & Gallery Mode ====================
// When motion wakes the screen during photo screensaver, show a thumbnail
// in the top-right corner that user can tap to open full gallery mode.

let thumbnailElement = null;
let thumbnailCloseBtn = null;
let thumbnailFadeTimeout = null;
let galleryElement = null;
let galleryCloseBtn = null;
let galleryPhoto = null;
let galleryPhotoBg = null;
let galleryMetadataDate = null;
let galleryMetadataLocation = null;
let galleryCurrentIndex = 0;
let galleryPhotoUrls = [];  // Reference to photo URLs for gallery navigation
let galleryIsOpen = false;  // Track if gallery is currently open
let touchStartX = 0;
let touchStartY = 0;

/**
 * Show photo thumbnail in top-right corner.
 * Auto-fades after 10 seconds.
 */
function showThumbnail(photoUrl) {
  console.log('[Screensaver] showThumbnail:', photoUrl);

  // Create thumbnail element if needed
  if (!thumbnailElement) {
    createThumbnailElement();
  }

  // Set photo
  const thumbImg = thumbnailElement.querySelector('.screensaver-thumbnail__image');
  if (thumbImg) thumbImg.src = photoUrl;

  // Show thumbnail
  thumbnailElement.classList.add('screensaver-thumbnail--active');

  // Register thumbnail layer for pointer-events
  addLayer('thumbnail');

  // Request fullscreen from parent (thumbnail needs iframe to be visible)
  if (window._requestFullscreen) {
    window._requestFullscreen('thumbnail');
  }

  // Auto-fade after 5 seconds
  clearTimeout(thumbnailFadeTimeout);
  thumbnailFadeTimeout = setTimeout(() => {
    hideThumbnail();
  }, 5000);
}

/**
 * Hide thumbnail.
 */
function hideThumbnail() {
  console.log('[Screensaver] hideThumbnail, galleryIsOpen:', galleryIsOpen);
  clearTimeout(thumbnailFadeTimeout);

  if (thumbnailElement) {
    thumbnailElement.classList.remove('screensaver-thumbnail--active');
  }

  // Unregister thumbnail layer (gallery has its own layer, so no conflict)
  removeLayer('thumbnail');

  // Release fullscreen (thumbnail no longer needs it)
  if (window._releaseFullscreen) {
    window._releaseFullscreen('thumbnail');
  }

  // Notify Android
  if (window.DashieBridge?.onThumbnailDismissed) {
    window.DashieBridge.onThumbnailDismissed();
  }
}

/**
 * Create thumbnail DOM element.
 */
function createThumbnailElement() {
  thumbnailElement = document.createElement('div');
  thumbnailElement.className = 'screensaver-thumbnail';
  thumbnailElement.innerHTML = `
    <button class="screensaver-thumbnail__close">&times;</button>
    <img class="screensaver-thumbnail__image" alt="Photo thumbnail">
  `;

  // Close button handler
  thumbnailCloseBtn = thumbnailElement.querySelector('.screensaver-thumbnail__close');

  const handleClose = (e) => {
    console.log('[Screensaver] Close button tapped, event type:', e.type);
    e.stopPropagation();
    e.preventDefault();
    hideThumbnail();
  };
  thumbnailCloseBtn.addEventListener('click', handleClose);
  thumbnailCloseBtn.addEventListener('touchstart', handleClose, { passive: false });

  // Tap thumbnail to open gallery
  // Use touchstart for immediate response on Android WebView
  let touchHandled = false;

  const handleThumbnailTap = (e) => {
    console.log('[Screensaver] handleThumbnailTap called, event type:', e.type, 'target:', e.target?.className);

    // Don't trigger if it was on the close button
    if (e.target === thumbnailCloseBtn || e.target.closest('.screensaver-thumbnail__close')) {
      console.log('[Screensaver] Tap was on close button, ignoring');
      return;
    }

    // Prevent double-handling from both touchstart and click
    if (e.type === 'touchstart') {
      touchHandled = true;
      setTimeout(() => { touchHandled = false; }, 300);
    } else if (e.type === 'click' && touchHandled) {
      console.log('[Screensaver] Ignoring click after touch');
      return;
    }

    e.stopPropagation();
    e.preventDefault();

    console.log('[Screensaver] Thumbnail tapped! Opening gallery...');
    clearTimeout(thumbnailFadeTimeout);
    thumbnailElement.classList.remove('screensaver-thumbnail--active');

    console.log('[Screensaver] Checking DashieBridge availability...');
    console.log('[Screensaver] window.DashieBridge:', typeof window.DashieBridge);
    console.log('[Screensaver] onThumbnailTapped:', typeof window.DashieBridge?.onThumbnailTapped);

    if (window.DashieBridge?.onThumbnailTapped) {
      console.log('[Screensaver] Calling DashieBridge.onThumbnailTapped()');
      try {
        window.DashieBridge.onThumbnailTapped();
        console.log('[Screensaver] DashieBridge.onThumbnailTapped() called successfully');
      } catch (err) {
        console.error('[Screensaver] Error calling onThumbnailTapped:', err);
      }
    } else {
      console.warn('[Screensaver] DashieBridge.onThumbnailTapped not available!');
      // Fallback: try to open gallery directly if we have photos
      if (galleryPhotoUrls.length > 0) {
        console.log('[Screensaver] Fallback: opening gallery directly');
        openGallery(0);
      }
    }
  };

  // Use touchstart for immediate response, click as fallback
  thumbnailElement.addEventListener('touchstart', handleThumbnailTap, { passive: false });
  thumbnailElement.addEventListener('click', handleThumbnailTap);

  // Also add a touchend handler for debugging
  thumbnailElement.addEventListener('touchend', (e) => {
    console.log('[Screensaver] touchend on thumbnail, target:', e.target?.className);
  }, { passive: true });

  document.body.appendChild(thumbnailElement);
  console.log('[Screensaver] Thumbnail element created and appended to body');
  console.log('[Screensaver] Thumbnail styles:', window.getComputedStyle(thumbnailElement).pointerEvents);
}

/**
 * Open gallery mode for browsing photos.
 */
function openGallery(startIndex) {
  console.log('[Screensaver] openGallery at index:', startIndex);
  galleryCurrentIndex = startIndex;
  galleryIsOpen = true;

  // Create gallery element if needed
  if (!galleryElement) {
    createGalleryElement();
  }

  // Show gallery
  galleryElement.classList.add('screensaver-gallery--active');

  // Register gallery layer for pointer-events
  addLayer('gallery');

  // Request fullscreen from parent (gallery needs full screen)
  if (window._requestFullscreen) {
    window._requestFullscreen('gallery');
  }

  // Load current photo
  loadGalleryPhoto(galleryCurrentIndex);
}

/**
 * Close gallery mode.
 */
function closeGallery() {
  console.log('[Screensaver] closeGallery');
  galleryIsOpen = false;

  if (galleryElement) {
    galleryElement.classList.remove('screensaver-gallery--active');
  }

  // Unregister gallery layer for pointer-events
  removeLayer('gallery');

  // Release fullscreen (gallery no longer needs it)
  if (window._releaseFullscreen) {
    window._releaseFullscreen('gallery');
  }

  // Notify Android
  if (window.DashieBridge?.onGalleryClosed) {
    window.DashieBridge.onGalleryClosed();
  }
}

/**
 * Create gallery DOM element.
 */
function createGalleryElement() {
  galleryElement = document.createElement('div');
  galleryElement.className = 'screensaver-gallery';
  galleryElement.innerHTML = `
    <button class="screensaver-gallery__close">&times;</button>
    <img class="screensaver-gallery__photo-bg" alt="">
    <img class="screensaver-gallery__photo" alt="Gallery photo">
    <div class="screensaver-gallery__metadata">
      <span class="screensaver-gallery__date"></span>
      <span class="screensaver-gallery__location"></span>
    </div>
  `;

  // Close button (touchstart for immediate response on touch devices, click as fallback)
  galleryCloseBtn = galleryElement.querySelector('.screensaver-gallery__close');
  galleryCloseBtn.addEventListener('touchstart', (e) => {
    e.preventDefault();
    e.stopPropagation();
    closeGallery();
  }, { passive: false });
  galleryCloseBtn.addEventListener('click', closeGallery);

  // Get photo elements
  galleryPhoto = galleryElement.querySelector('.screensaver-gallery__photo');
  galleryPhotoBg = galleryElement.querySelector('.screensaver-gallery__photo-bg');
  galleryMetadataDate = galleryElement.querySelector('.screensaver-gallery__date');
  galleryMetadataLocation = galleryElement.querySelector('.screensaver-gallery__location');

  // Swipe navigation
  galleryElement.addEventListener('touchstart', handleGalleryTouchStart, { passive: true });
  galleryElement.addEventListener('touchend', handleGalleryTouchEnd, { passive: true });

  document.body.appendChild(galleryElement);
}

function handleGalleryTouchStart(e) {
  touchStartX = e.touches[0].clientX;
  touchStartY = e.touches[0].clientY;
}

function handleGalleryTouchEnd(e) {
  const touchEndX = e.changedTouches[0].clientX;
  const touchEndY = e.changedTouches[0].clientY;
  const diffX = touchEndX - touchStartX;
  const diffY = touchEndY - touchStartY;

  // Require horizontal swipe (more X movement than Y)
  if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 50) {
    if (diffX > 0) {
      // Swipe right = previous photo
      navigateGallery(-1);
    } else {
      // Swipe left = next photo
      navigateGallery(1);
    }
  }
}

function navigateGallery(direction) {
  if (galleryPhotoUrls.length === 0) return;

  galleryCurrentIndex = (galleryCurrentIndex + direction + galleryPhotoUrls.length) % galleryPhotoUrls.length;
  loadGalleryPhoto(galleryCurrentIndex);
}

function loadGalleryPhoto(index) {
  if (index < 0 || index >= galleryPhotoUrls.length) return;

  const url = galleryPhotoUrls[index];
  console.log('[Screensaver] loadGalleryPhoto:', index, url);

  // Load image to detect orientation
  const img = new Image();
  img.onload = () => {
    const isPortrait = img.naturalHeight > img.naturalWidth;
    if (galleryPhoto) {
      galleryPhoto.src = url;
      galleryPhoto.classList.toggle('screensaver-gallery__photo--portrait', isPortrait);
    }
    if (galleryPhotoBg && isPortrait) {
      galleryPhotoBg.src = url;
      galleryPhotoBg.classList.add('screensaver-gallery__photo-bg--active');
    } else if (galleryPhotoBg) {
      galleryPhotoBg.classList.remove('screensaver-gallery__photo-bg--active');
      galleryPhotoBg.src = '';
    }
  };
  img.onerror = () => {
    console.warn('[Screensaver] Gallery photo failed to load:', url);
    if (galleryPhoto) galleryPhoto.src = url;
  };
  img.src = url;

  // Clear metadata (Android will update via updateMetadata if available)
  if (galleryMetadataDate) galleryMetadataDate.textContent = '';
  if (galleryMetadataLocation) galleryMetadataLocation.textContent = '';
}

/**
 * Set photo URLs for gallery navigation.
 * Called after setHtmlScreensaverPhotos in Android.
 */
function setPhotos(urls) {
  console.log('[Screensaver] setPhotos:', urls.length, 'photos');
  galleryPhotoUrls = urls;
}

// ==================== Expose to Android ====================

window.screensaver = {
  configure,
  activate,
  deactivate,
  showPhoto,
  updateMetadata,
  isActive: isScreensaverActive,
  // Thumbnail & Gallery
  showThumbnail,
  hideThumbnail,
  openGallery,
  closeGallery,
  setPhotos
};

console.log('[Screensaver] Initialized (dumb renderer mode)');

// Request config from Android in case it was sent before we loaded
if (window.DashieBridge?.requestScreensaverConfig) {
  window.DashieBridge.requestScreensaverConfig();
}
