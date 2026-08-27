/**
 * Dash Menu - Sidebar controller.
 * JS-rendered sidebar for the Dashie kiosk shell page.
 * Provides quick access to navigation, music player, volume/brightness, and settings.
 *
 * Supports pinned (always visible) and unpinned (swipe to reveal overlay) modes.
 *
 * Exported for use by kiosk-shell.js. Also self-initializes when loaded as
 * standalone entry point (dash-menu.bundle.js) for backwards compatibility.
 */

import { DashMenuRenderer } from './dash-menu-renderer.js';
import { createVolumePopout } from './dash-menu-volume-popout.js';
import { createBrightnessPopout } from './dash-menu-brightness-popout.js';
import { createHamburgerPopout } from './dash-menu-hamburger-popout.js';
import { createCameraPopout } from './dash-menu-camera-popout.js';
const LOCK_POLL_INTERVAL_MS = 2000;
const AUTO_HIDE_MS = 10000;
const OVERLAY_AUTO_HIDE_MS = 10000;

export class DashMenuController {
  constructor() {
    this.renderer = new DashMenuRenderer();
    this.isLocked = false;
    this.lockPollInterval = null;
    this.autoHideTimeout = null;

    // Pin state (default unpinned — user swipes to reveal)
    this.isPinned = false;
    this.dashMenuEnabled = true;
    this.isOverlayVisible = false;
    this.overlayAutoHideTimeout = null;
  }

  initialize() {
    console.log('[DashMenu] Initializing...');

    // Read initial lock and pin state
    try {
      this.isLocked = DashieNative.isAppLocked();
    } catch (e) {
      console.warn('[DashMenu] Could not read lock state:', e);
    }
    try {
      this.isPinned = DashieNative.isDashBarPinned();
    } catch (e) {
      console.warn('[DashMenu] Could not read pin state:', e);
    }

    // Build the UI
    this.renderer.buildStrip();
    this.renderer.setLockState(this.isLocked);

    // Wire click handler and overlay dismiss
    this.renderer.onItemClick = (itemId) => this.handleItemClick(itemId);
    this.renderer.onPopoutDismiss = () => this.clearAutoHide();
    this.renderer.onOverlayDismiss = () => this.dismissOverlay();

    // Start lock state polling
    this.startLockPolling();

    // Update volume icon, music and camera visibility
    this.refreshVolumeIcon();
    this.refreshMusicVisibility();
    this.refreshCameraVisibility();

    // Sync volume popout with hardware button presses
    window.onNativeVolumeChange = (level, isMuted) => {
      this.renderer.updateVolumeIcon(isMuted);
      if (this.renderer.activePopoutId === 'volume' && this.renderer._activePopout?.updateVolume) {
        this.renderer._activePopout.updateVolume(level, isMuted);
      }
    };

    console.log('[DashMenu] Initialized (locked:', this.isLocked, ', pinned:', this.isPinned, ')');
  }

  // ============================================
  // Sidebar Config (called from Kotlin on startup)
  // ============================================

  setSidebarConfig({ enabled, pinned }) {
    this.isPinned = pinned;
    this.dashMenuEnabled = enabled;

    // When locked, always show the sidebar so user can access unlock
    const effectiveEnabled = enabled || this.isLocked;

    if (!effectiveEnabled) {
      document.body.classList.add('sidebar-hidden');
      document.body.classList.remove('sidebar-overlay');
    } else if (pinned) {
      document.body.classList.remove('sidebar-hidden');
      document.body.classList.remove('sidebar-overlay');
    } else if (this.isLocked) {
      // Locked + unpinned: show in overlay mode so user can reach unlock
      this.revealSidebar();
    } else {
      // Enabled but unpinned: hide initially, user swipes to reveal
      document.body.classList.add('sidebar-hidden');
      document.body.classList.remove('sidebar-overlay');
    }

    console.log('[DashMenu] Config applied: enabled=' + effectiveEnabled + ' (pref=' + enabled + '), pinned=' + pinned + ', locked=' + this.isLocked);
  }

  // ============================================
  // Overlay Reveal/Dismiss (unpinned mode)
  // ============================================

  revealSidebar() {
    console.log('[DashMenu] revealSidebar called: isPinned=' + this.isPinned + ', isOverlayVisible=' + this.isOverlayVisible + ', isLocked=' + this.isLocked);
    if (this.isPinned || this.isOverlayVisible) return;

    this.isOverlayVisible = true;
    document.body.classList.remove('sidebar-hidden');
    document.body.classList.add('sidebar-overlay');
    this.renderer.showOverlayBackdrop();
    this.startOverlayAutoHide();
    console.log('[DashMenu] Sidebar revealed as overlay');
  }

  dismissOverlay() {
    console.log('[DashMenu] dismissOverlay called: isOverlayVisible=' + this.isOverlayVisible + ', isLocked=' + this.isLocked);
    if (!this.isOverlayVisible) return;

    this.isOverlayVisible = false;
    this.clearOverlayAutoHide();
    this.dismissPopout();
    this.deactivateDpad();
    document.body.classList.add('sidebar-hidden');
    document.body.classList.remove('sidebar-overlay');
    this.renderer.hideOverlayBackdrop();
    console.log('[DashMenu] Sidebar overlay dismissed');
  }

  // ============================================
  // Pin Toggle
  // ============================================

  togglePin() {
    const newPinned = !this.isPinned;
    this.isPinned = newPinned;

    // Notify Kotlin to persist preference and update drawer offset
    try { DashieNative.setDashBarPinned(newPinned); } catch (e) {
      console.warn('[DashMenu] setDashBarPinned failed:', e);
    }

    if (newPinned) {
      // Overlay → pinned: keep sidebar visible, remove overlay state
      this.clearOverlayAutoHide();
      this.isOverlayVisible = false;
      document.body.classList.remove('sidebar-hidden');
      document.body.classList.remove('sidebar-overlay');
      this.renderer.hideOverlayBackdrop();
    } else {
      // Pinned → unpinned: hide the sidebar and clean up d-pad state
      this.isOverlayVisible = false;
      this.deactivateDpad();
      document.body.classList.add('sidebar-hidden');
      document.body.classList.remove('sidebar-overlay');
      this.renderer.hideOverlayBackdrop();
    }

    console.log('[DashMenu] Pin toggled:', newPinned);
  }

  // ============================================
  // Overlay Auto-Hide Timers
  // ============================================

  startOverlayAutoHide() {
    this.clearOverlayAutoHide();
    this.overlayAutoHideTimeout = setTimeout(() => {
      this.dismissOverlay();
    }, OVERLAY_AUTO_HIDE_MS);
  }

  resetOverlayAutoHide() {
    if (this.overlayAutoHideTimeout) {
      this.startOverlayAutoHide();
    }
  }

  clearOverlayAutoHide() {
    if (this.overlayAutoHideTimeout) {
      clearTimeout(this.overlayAutoHideTimeout);
      this.overlayAutoHideTimeout = null;
    }
  }

  // ============================================
  // Lock State
  // ============================================

  /** Update lock state (called by kiosk-shell.js from Kotlin) */
  setLockState(locked) {
    console.log('[DashMenu] setLockState(' + locked + '): prev isLocked=' + this.isLocked + ', isOverlayVisible=' + this.isOverlayVisible + ', dashMenuEnabled=' + this.dashMenuEnabled + ', isPinned=' + this.isPinned);
    this.isLocked = locked;
    this.renderer.setLockState(locked);
    if (locked) {
      this.dismissPopout();
      if (this.isOverlayVisible) this.dismissOverlay();
      // Force-show sidebar when locked so user can always access unlock.
      // If pinned, just remove sidebar-hidden (no overlay needed).
      // If unpinned, show as overlay with auto-hide.
      if (this.isPinned) {
        document.body.classList.remove('sidebar-hidden');
      } else {
        this.revealSidebar();
      }
    } else {
      // When unlocking, dismiss overlay and re-hide sidebar if only shown for lock access
      if (this.isOverlayVisible) this.dismissOverlay();
      if (this.dashMenuEnabled === false) {
        document.body.classList.add('sidebar-hidden');
        document.body.classList.remove('sidebar-overlay');
      }
    }
  }

  // ============================================
  // Item Click Handling
  // ============================================

  handleItemClick(itemId) {
    // Reset overlay auto-hide on any interaction
    if (this.isOverlayVisible) {
      this.resetOverlayAutoHide();
    }

    switch (itemId) {
      case 'music':
        try {
          DashieNative.toggleMusicPlayer();
        } catch (e) {
          console.warn('[DashMenu] toggleMusicPlayer failed:', e);
        }
        break;

      case 'camera':
        this.togglePopout('camera', () => createCameraPopout(
          () => this.resetAutoHide(),
          (paused) => this.renderer.setCameraPaused(paused)
        ));
        break;

      case 'volume':
        this.togglePopout('volume', () => createVolumePopout(
          () => this.resetAutoHide(),
          (muted) => this.renderer.updateVolumeIcon(muted)
        ));
        break;

      case 'brightness':
        if (this._isTvDevice()) {
          // TV: directly toggle dark/light mode, no popout
          try {
            const isDark = DashieNative.isSystemDarkMode?.() ?? true;
            DashieNative.setDarkMode?.(!isDark);
          } catch (e) { console.warn('[DashMenu] setDarkMode failed:', e); }
        } else {
          this.togglePopout('brightness', () => createBrightnessPopout(() => this.resetAutoHide()));
        }
        break;

      case 'hamburger':
        // Always show popout — locked shows Unlock/Pin/Reload/Sleep,
        // unlocked shows Settings/Pin/Reload/Sleep.
        // This ensures user is never locked out if isLocked detection fails.
        this.togglePopout('hamburger', () =>
          createHamburgerPopout(this.isLocked, this.isPinned, {
            onDismiss: () => this.dismissPopout(),
            onPin: () => this.togglePin(),
            onSettings: () => {
              // Dismiss sidebar overlay before opening CC
              this.dismissOverlay();
              if (window.DashieNative?.openControlCenter) {
                // Native Control Center on Android
                DashieNative.openControlCenter();
              } else if (window.Settings) {
                // window.openSettingsToPage died with the kiosk settings bundle (2026-03) —
                // browser-dev fallback goes straight to the webapp Settings module if present.
                window.Settings.show();
              } else {
                try { DashieNative.openDrawer(); } catch (e) {
                  console.warn('[DashMenu] openDrawer failed:', e);
                }
              }
            },
          })
        );
        break;
    }
  }

  // ============================================
  // Popout Management
  // ============================================

  togglePopout(id, factory) {
    if (this.renderer.activePopoutId === id) {
      this.dismissPopout();
      return;
    }
    this.clearAutoHide();
    const popout = factory();
    this.renderer.showPopout(id, popout);
    this.startAutoHide();
  }

  dismissPopout() {
    this.clearAutoHide();
    this.renderer.hidePopout();
    this.refreshVolumeIcon();
    // If d-pad was targeting the popout, return focus to sidebar
    if (this._dpadTarget === 'popout') {
      this._dpadTarget = 'sidebar';
      this._updateDpadHighlight();
    }
  }

  startAutoHide() {
    this.clearAutoHide();
    this.autoHideTimeout = setTimeout(() => {
      this.dismissPopout();
    }, AUTO_HIDE_MS);
  }

  resetAutoHide() {
    if (this.autoHideTimeout) {
      this.startAutoHide();
    }
  }

  clearAutoHide() {
    if (this.autoHideTimeout) {
      clearTimeout(this.autoHideTimeout);
      this.autoHideTimeout = null;
    }
  }

  // ============================================
  // Lock Polling, Volume & Music Visibility
  // ============================================

  startLockPolling() {
    this.lockPollInterval = setInterval(() => {
      try {
        const locked = DashieNative.isAppLocked();
        if (locked !== this.isLocked) {
          this.setLockState(locked);
          console.log('[DashMenu] Lock state changed:', locked);
        }
      } catch (e) {
        // Bridge unavailable — ignore
      }
    }, LOCK_POLL_INTERVAL_MS);
  }

  refreshVolumeIcon() {
    try {
      const muted = DashieNative.isMuted();
      this.renderer.updateVolumeIcon(muted);
    } catch (e) { /* bridge unavailable */ }
  }

  refreshMusicVisibility() {
    try {
      const enabled = typeof DashieNative !== 'undefined' &&
        typeof DashieNative.isMusicPlayerEnabled === 'function' &&
        DashieNative.isMusicPlayerEnabled();
      this.renderer.setMusicVisible(enabled);
      console.log('[DashMenu] Music player visible:', enabled);
    } catch (e) {
      // Bridge unavailable — hide music by default
      this.renderer.setMusicVisible(false);
    }
  }

  refreshCameraVisibility() {
    try {
      const enabled = typeof DashieNative !== 'undefined' &&
        typeof DashieNative.isVideoFeedsEnabled === 'function' &&
        DashieNative.isVideoFeedsEnabled();
      this.renderer.setCameraVisible(enabled);
      // Also set initial paused state on the sidebar icon
      if (enabled && typeof DashieNative.areVideoFeedsPaused === 'function') {
        this.renderer.setCameraPaused(DashieNative.areVideoFeedsPaused());
      }
      console.log('[DashMenu] Camera button visible:', enabled);
    } catch (e) {
      // Bridge unavailable — hide camera by default
      this.renderer.setCameraVisible(false);
    }
  }

  // ============================================
  // D-Pad Navigation
  // ============================================

  /** Activate d-pad navigation on the sidebar. Called when sidebar is revealed. */
  activateDpad() {
    this._dpadFocusIndex = 0;
    this._dpadTarget = 'sidebar'; // 'sidebar' | 'popout'
    window.dashieDashBarDpad = (keyCode) => this.handleDpad(keyCode);
    // Tell Kotlin to forward ALL d-pad keys (not just LEFT) by setting overlay focus flag.
    // JS routing checks dashieDashBarDpad before overlayHasKeyboardFocus, so keys reach us.
    window.overlayHasKeyboardFocus = true;
    try { DashieNative.setOverlayKeyboardFocus(true); } catch (e) { /* bridge unavailable */ }
    requestAnimationFrame(() => this._updateDpadHighlight());
  }

  /** Deactivate d-pad navigation. Called when sidebar is dismissed. */
  deactivateDpad() {
    this._clearDpadHighlight();
    delete window.dashieDashBarDpad;
    // Only release keyboard focus if settings doesn't have d-pad control
    if (!window._shellSettingsDpad) {
      window.overlayHasKeyboardFocus = false;
      try { DashieNative.setOverlayKeyboardFocus(false); } catch (e) { /* bridge unavailable */ }
    }
  }

  /** Get visible sidebar item buttons. */
  _getVisibleSidebarItems() {
    if (!this.renderer.container) return [];
    return Array.from(this.renderer.container.querySelectorAll('.dashboard-menu__item'))
      .filter(el => el.style.display !== 'none' && el.offsetParent !== null);
  }

  /** Get visible popout menu item buttons (hamburger items + theme toggle). */
  _getVisiblePopoutItems() {
    const popout = this.renderer._activePopout?.element;
    if (!popout) return [];
    return Array.from(popout.querySelectorAll('.hamburger-menu-item, .dm-theme-toggle-btn'))
      .filter(el => el.offsetParent !== null);
  }

  /** Apply d-pad focus highlight to the currently focused item. */
  _updateDpadHighlight() {
    this._clearDpadHighlight();
    const items = this._dpadTarget === 'popout'
      ? this._getVisiblePopoutItems()
      : this._getVisibleSidebarItems();
    if (this._dpadFocusIndex >= items.length) this._dpadFocusIndex = items.length - 1;
    if (this._dpadFocusIndex < 0) this._dpadFocusIndex = 0;
    const focused = items[this._dpadFocusIndex];
    if (focused) focused.classList.add('dm-dpad-focus');
  }

  /** Clear all d-pad focus highlights. */
  _clearDpadHighlight() {
    document.querySelectorAll('.dm-dpad-focus').forEach(el => el.classList.remove('dm-dpad-focus'));
  }

  /**
   * Handle d-pad input for sidebar and popout navigation.
   * @param {number} keyCode - Android keycode
   */
  handleDpad(keyCode) {
    // Reset auto-hide on any d-pad interaction
    if (this.isOverlayVisible) this.resetOverlayAutoHide();
    if (this.autoHideTimeout) this.resetAutoHide();

    // If targeting popout but it's gone (e.g. auto-dismissed), fall back to sidebar
    if (this._dpadTarget === 'popout' && !this.renderer.activePopoutId) {
      this._dpadTarget = 'sidebar';
    }

    const items = this._dpadTarget === 'popout'
      ? this._getVisiblePopoutItems()
      : this._getVisibleSidebarItems();

    console.log(`[DashMenu] handleDpad: key=${keyCode} target=${this._dpadTarget} items=${items.length} focusIdx=${this._dpadFocusIndex}`);

    if (!items.length) return;

    switch (keyCode) {
      case 19: // UP
        if (this._dpadFocusIndex > 0) {
          this._dpadFocusIndex--;
          this._updateDpadHighlight();
        }
        break;
      case 20: // DOWN
        if (this._dpadFocusIndex < items.length - 1) {
          this._dpadFocusIndex++;
          this._updateDpadHighlight();
        }
        break;
      case 23: // CENTER
      case 66: // ENTER
        items[this._dpadFocusIndex]?.click();
        // After clicking a sidebar item, check if a popout opened
        if (this._dpadTarget === 'sidebar') {
          setTimeout(() => {
            if (this.renderer.activePopoutId) {
              this._dpadTarget = 'popout';
              this._dpadFocusIndex = this._getInitialPopoutIndex();
              this._updateDpadHighlight();
            }
          }, 100);
        }
        break;
      case 22: // RIGHT — enter popout from sidebar
        if (this._dpadTarget === 'sidebar' && this.renderer.activePopoutId) {
          this._dpadTarget = 'popout';
          this._dpadFocusIndex = this._getInitialPopoutIndex();
          this._updateDpadHighlight();
        }
        break;
      case 21: // LEFT — back from popout to sidebar, or dismiss sidebar
        if (this._dpadTarget === 'popout') {
          this.dismissPopout(); // Switches _dpadTarget to 'sidebar' + updates highlight
        } else {
          this.dismissOverlay(); // Also deactivates d-pad
        }
        break;
      case 4: // BACK — close popout or dismiss sidebar
        if (this._dpadTarget === 'popout') {
          this.dismissPopout(); // Switches _dpadTarget to 'sidebar' + updates highlight
        } else {
          this.dismissOverlay(); // Also deactivates d-pad
        }
        break;
    }
  }

  /**
   * Get initial d-pad focus index for a popout.
   * On TV, hamburger popout starts focused on the last item (Control Center).
   */
  _getInitialPopoutIndex() {
    if (this._isTvDevice() && this.renderer.activePopoutId === 'hamburger') {
      const items = this._getVisiblePopoutItems();
      return items.length > 0 ? items.length - 1 : 0;
    }
    return 0;
  }

  /** Detect whether the current device is a TV (cached). */
  _isTvDevice() {
    if (this._isTvCached !== undefined) return this._isTvCached;
    try {
      if (typeof DashieNative !== 'undefined' && DashieNative.getDeviceInfo) {
        const info = JSON.parse(DashieNative.getDeviceInfo());
        this._isTvCached = info.isTv || info.isFireTV || false;
        return this._isTvCached;
      }
    } catch (e) { /* */ }
    this._isTvCached = false;
    return false;
  }
}

// Self-initialize when loaded as standalone entry point (dash-menu.bundle.js).
// When imported by kiosk-shell.js, this module is loaded as ESM dependency
// and the auto-init below won't run (no script element with that src).
if (typeof document !== 'undefined') {
  const scripts = document.querySelectorAll('script[src*="dash-menu.bundle"]');
  if (scripts.length > 0) {
    const controller = new DashMenuController();
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', () => controller.initialize());
    } else {
      controller.initialize();
    }
  }
}
