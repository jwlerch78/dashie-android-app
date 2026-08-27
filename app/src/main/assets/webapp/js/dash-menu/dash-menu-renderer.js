/**
 * Dash Menu Renderer.
 * Uses shared sidebar-strip.js for DOM creation — identical structure to
 * the standard Dashie sidebar (dom-builder.js / sidebar-view-switcher.js).
 *
 * In the kiosk shell architecture, the sidebar and popouts are native DOM
 * in the shell page (no iframe constraints).
 */

import { createSidebarContainer, createSidebarButton } from '@dashie/ui/sidebar-strip.js';
import { MENU_ITEMS } from './dash-menu-items.js';

const STRIP_WIDTH = 60;
const POPOUT_GAP = 8;

const resolveIconUrl = (path) => path.replace(/^\//, '');

export class DashMenuRenderer {
  constructor() {
    this.container = null;
    this.popoutContainer = null;
    this.backdrop = null;
    this.overlayBackdrop = null;
    this.activePopoutId = null;
    this._activePopout = null;
    this.onItemClick = null;
    this.onPopoutDismiss = null;
    this.onOverlayDismiss = null;
  }

  /**
   * Build the sidebar strip and append to document.body.
   * Uses shared createSidebarContainer() + createSidebarButton().
   */
  buildStrip() {
    const { element: sidebar, viewSection, controlSection } = createSidebarContainer();
    this.container = sidebar;

    for (const item of MENU_ITEMS) {
      const btn = createSidebarButton({
        id: item.id,
        label: item.label,
        iconPath: item.iconPath,
        type: 'system',
        resolveIconUrl,
      });

      // Prevent Android WebView d-pad from giving native focus (orange highlight)
      // to sidebar buttons. Our JS d-pad system manages visual focus via CSS classes.
      btn.tabIndex = -1;

      controlSection.appendChild(btn);

      btn.addEventListener('click', () => {
        this.onItemClick?.(item.id);
      });
    }

    // Popout container (positioned to the right of the strip)
    this.popoutContainer = document.createElement('div');
    this.popoutContainer.className = 'dm-popout-container';

    // Backdrop for dismissing popouts (covers entire viewport)
    this.backdrop = document.createElement('div');
    this.backdrop.className = 'dm-backdrop';
    this.backdrop.addEventListener('click', () => {
      this.hidePopout();
      this.onPopoutDismiss?.();
    });

    // Overlay backdrop (for dismissing revealed sidebar in overlay mode)
    this.overlayBackdrop = document.createElement('div');
    this.overlayBackdrop.className = 'dm-overlay-backdrop';
    this.overlayBackdrop.addEventListener('click', () => {
      this.onOverlayDismiss?.();
    });

    document.body.appendChild(this.overlayBackdrop);
    document.body.appendChild(this.backdrop);
    document.body.appendChild(this.container);
    document.body.appendChild(this.popoutContainer);
  }

  // ============================================
  // Overlay Backdrop
  // ============================================

  showOverlayBackdrop() {
    this.overlayBackdrop?.classList.add('dm-overlay-backdrop--visible');
  }

  hideOverlayBackdrop() {
    this.overlayBackdrop?.classList.remove('dm-overlay-backdrop--visible');
  }

  // ============================================
  // Popouts
  // ============================================

  /**
   * Show a popout next to the anchor item.
   * For items near the bottom, the popout opens upward to stay on screen.
   * @param {string} itemId - the menu item that triggered this popout
   * @param {{ element: HTMLElement, popoutWidth: number, destroy: Function }} popout
   */
  showPopout(itemId, popout) {
    this.hidePopout();

    this.activePopoutId = itemId;
    this._activePopout = popout;

    // Add to DOM and temporarily show for measurement
    this.popoutContainer.appendChild(popout.element);
    this.popoutContainer.style.visibility = 'hidden';
    this.popoutContainer.classList.add('dm-popout-container--visible');

    // Force layout to get accurate height
    const popoutHeight = popout.element.offsetHeight || 80;

    this.popoutContainer.style.visibility = '';

    // Position the popout vertically centered on the anchor button.
    // Both sidebar and popout use transform: scale() from top-left origin.
    // getBoundingClientRect() returns viewport coords. The popout container's
    // CSS top maps directly to viewport position, but content inside scales,
    // so the visual height is popoutHeight * scale.
    const anchor = this.container.querySelector(`[data-id="${itemId}"]`);
    if (anchor) {
      const style = getComputedStyle(document.documentElement);
      const scale = parseFloat(style.getPropertyValue('--shell-scale')) || 1;
      const boost = parseFloat(style.getPropertyValue('--sidebar-boost')) || 1.15;
      const menuSize = parseFloat(style.getPropertyValue('--menu-size')) || 1;
      const popoutScale = scale * boost * menuSize;
      const rect = anchor.getBoundingClientRect();
      const anchorCenter = rect.top + rect.height / 2;
      const visualPopoutHeight = popoutHeight * popoutScale;
      let top = anchorCenter - visualPopoutHeight / 2;

      // Clamp to viewport bounds
      const maxTop = window.innerHeight - visualPopoutHeight - 8;
      if (top < 8) top = 8;
      if (top > maxTop) top = maxTop;
      this.popoutContainer.style.top = `${top}px`;
    }

    this.popoutContainer.classList.add('dm-popout-container--visible');
    this.backdrop.classList.add('dm-backdrop--visible');
  }

  /**
   * Hide the current popout.
   */
  hidePopout() {
    if (!this._activePopout) return;

    this._activePopout.destroy?.();
    this.popoutContainer.innerHTML = '';
    this.popoutContainer.classList.remove('dm-popout-container--visible');
    this.backdrop.classList.remove('dm-backdrop--visible');

    this.activePopoutId = null;
    this._activePopout = null;
  }

  /**
   * Lock visual state — hamburger icon stays the same; lock behavior
   * is handled by the popout menu content.
   */
  setLockState(isLocked) {
    // No visual change — popout shows different items based on lock state
  }

  /**
   * Show or hide the music button based on settings.
   */
  setMusicVisible(visible) {
    const btn = this.container?.querySelector('[data-id="music"]');
    if (btn) {
      btn.style.display = visible ? '' : 'none';
    }
  }

  /**
   * Show or hide the camera button based on video feed settings.
   */
  setCameraVisible(visible) {
    const btn = this.container?.querySelector('[data-id="camera"]');
    if (btn) {
      btn.style.display = visible ? '' : 'none';
    }
  }

  /**
   * Update camera button paused state (strikethrough + dimmed icon).
   */
  setCameraPaused(paused) {
    const btn = this.container?.querySelector('[data-id="camera"]');
    if (btn) {
      btn.dataset.feedsPaused = paused ? 'true' : 'false';
    }
  }

  /**
   * Update volume icon to reflect mute state.
   */
  updateVolumeIcon(isMuted) {
    const volumeItem = MENU_ITEMS.find(m => m.id === 'volume');
    const btn = this.container?.querySelector('[data-id="volume"]');
    if (btn && volumeItem) {
      const iconEl = btn.querySelector('.dashboard-menu__icon');
      if (iconEl) {
        iconEl.src = resolveIconUrl(isMuted ? volumeItem.iconPathMuted : volumeItem.iconPath);
      }
      btn.dataset.audioMuted = isMuted ? 'true' : 'false';
    }
  }
}
