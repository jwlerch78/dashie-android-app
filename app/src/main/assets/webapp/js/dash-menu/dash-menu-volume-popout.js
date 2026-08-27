/**
 * Volume popout for the Dash Menu.
 *
 * On tablets: shows the full volume slider (via shared createVolumeSliderContent).
 * On TV: shows simple Mute Speaker / Mute Mic toggle buttons (no slider).
 * Includes mic toggle with 3-state cycling (on → muted → show-pill → on).
 */

import { createVolumeSliderContent } from '@dashie/ui/volume-slider.js';

// Mic state: 'on' | 'muted' | 'show-pill'
let micState = 'on';

/**
 * Detect whether the current device is a TV.
 */
function isTvDevice() {
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.getDeviceInfo) {
      const info = JSON.parse(DashieNative.getDeviceInfo());
      return info.isTv || info.isFireTV || false;
    }
  } catch (e) { /* bridge unavailable */ }
  return false;
}

/** Initialize mic state from Kotlin bridge on first use */
function initMicState() {
  try {
    if (typeof window.DashieNative?.isMicMuted === 'function') {
      if (window.DashieNative.isMicMuted()) {
        micState = 'muted';
      }
    }
  } catch (e) {
    console.warn('[DashMenu] Failed to read mic state', e);
  }
}

/**
 * Cycle mic state: on → muted → show-pill → on
 * Syncs mute/unmute transitions to Kotlin bridge.
 */
function handleMicToggle() {
  const states = ['on', 'muted', 'show-pill'];
  const currentIndex = states.indexOf(micState);
  const prevState = micState;
  micState = states[(currentIndex + 1) % states.length];

  // Only sync to Kotlin on actual mute/unmute transitions
  if (typeof window.DashieNative !== 'undefined') {
    const shouldSyncMute = micState === 'muted' || prevState === 'muted';
    if (shouldSyncMute) {
      const muted = micState === 'muted';
      try {
        if (typeof window.DashieNative.setMicMuted === 'function') {
          window.DashieNative.setMicMuted(muted);
        } else if (muted) {
          window.DashieNative.stopWakeWordDetection?.();
        } else {
          window.DashieNative.startWakeWordDetection?.();
        }
      } catch (e) {
        console.warn('[DashMenu] Failed to sync mic state', e);
      }
    }
  }
}

/**
 * Create a simplified mute-toggle popout for TV devices.
 * Two buttons: Mute Speaker and Mute Mic.
 * Uses hamburger-menu-item class for d-pad navigation support.
 */
/**
 * Helper to build a menu-item button matching the hamburger popout pattern.
 * Uses img icon + span label so the dash-menu CSS applies correctly.
 */
function createMuteButton(iconPath, labelText) {
  const btn = document.createElement('button');
  btn.className = 'hamburger-menu-item';

  if (iconPath) {
    const icon = document.createElement('img');
    icon.className = 'hamburger-menu-icon';
    icon.src = iconPath;
    icon.alt = labelText;
    btn.appendChild(icon);
  }

  const label = document.createElement('span');
  label.className = 'hamburger-menu-label';
  label.textContent = labelText;
  btn.appendChild(label);

  return btn;
}

function createTvMutePopout(onActivity, onMuteChanged) {
  initMicState();

  // Track speaker mute state locally — more reliable on TV than bridge readback
  let isSpeakerMuted = false;
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.isMuted) {
      isSpeakerMuted = DashieNative.isMuted();
    }
  } catch (e) { /* */ }

  const popup = document.createElement('div');
  popup.className = 'volume-slider-popup dm-tv-mute-popout';

  // Speaker mute toggle — uses explicit mute()/unmute() instead of toggleMute()
  // because toggleMute() can be unreliable on some TV devices
  const speakerIcon = () => isSpeakerMuted ? 'artwork/icon-volume-mute.svg' : 'artwork/icon-volume-on.svg';
  const speakerLabel = () => isSpeakerMuted ? 'Unmute Speaker' : 'Mute Speaker';
  const speakerBtn = createMuteButton(speakerIcon(), speakerLabel());

  const updateSpeakerBtn = () => {
    speakerBtn.querySelector('.hamburger-menu-icon').src = speakerIcon();
    speakerBtn.querySelector('.hamburger-menu-label').textContent = speakerLabel();
  };

  speakerBtn.addEventListener('click', () => {
    onActivity?.();
    try {
      if (isSpeakerMuted) {
        DashieNative.unmute?.();
      } else {
        DashieNative.mute?.();
      }
    } catch (e) { /* */ }
    isSpeakerMuted = !isSpeakerMuted;
    updateSpeakerBtn();
    onMuteChanged?.(isSpeakerMuted);
  });

  // Mic mute toggle — uses inline SVG mic icon (same as volume-slider.js)
  const micLabel = () => micState === 'muted' ? 'Unmute Mic' : 'Mute Mic';
  const micBtn = document.createElement('button');
  micBtn.className = 'hamburger-menu-item';

  const micSvg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  micSvg.setAttribute('viewBox', '0 0 24 24');
  micSvg.setAttribute('fill', 'currentColor');
  micSvg.classList.add('hamburger-menu-icon');
  micSvg.style.width = '18px';
  micSvg.style.height = '18px';
  const micPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  micPath.setAttribute('d', 'M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.91-3c-.49 0-.9.36-.98.85C16.52 14.2 14.47 16 12 16s-4.52-1.8-4.93-4.15c-.08-.49-.49-.85-.98-.85-.61 0-1.09.54-1 1.14.49 3 2.89 5.35 5.91 5.78V20c0 .55.45 1 1 1s1-.45 1-1v-2.08c3.02-.43 5.42-2.78 5.91-5.78.1-.6-.39-1.14-1-1.14z');
  micSvg.appendChild(micPath);
  const micStrike = document.createElementNS('http://www.w3.org/2000/svg', 'line');
  micStrike.setAttribute('x1', '4'); micStrike.setAttribute('y1', '4');
  micStrike.setAttribute('x2', '20'); micStrike.setAttribute('y2', '20');
  micStrike.setAttribute('stroke', 'currentColor');
  micStrike.setAttribute('stroke-width', '2');
  micStrike.setAttribute('stroke-linecap', 'round');
  micStrike.style.display = micState === 'muted' ? 'block' : 'none';
  micSvg.appendChild(micStrike);
  micBtn.appendChild(micSvg);

  const micLabelSpan = document.createElement('span');
  micLabelSpan.className = 'hamburger-menu-label';
  micLabelSpan.textContent = micLabel();
  micBtn.appendChild(micLabelSpan);

  const updateMicBtn = () => {
    micLabelSpan.textContent = micLabel();
    micStrike.style.display = micState === 'muted' ? 'block' : 'none';
    micSvg.style.opacity = micState === 'muted' ? '0.5' : '1';
  };

  micBtn.addEventListener('click', () => {
    onActivity?.();
    handleMicToggle();
    updateMicBtn();
  });

  popup.appendChild(speakerBtn);
  popup.appendChild(micBtn);

  return {
    element: popup,
    popoutWidth: 220,
    destroy: () => {},
  };
}

/**
 * Creates the volume popout for the dash menu.
 * On TV: returns simplified mute toggles. On tablet: returns the volume slider.
 * @param {Function} onActivity - called on user interaction (resets auto-hide)
 * @returns {{ element: HTMLElement, popoutWidth: number, destroy: Function }}
 */
export function createVolumePopout(onActivity, onMuteChanged) {
  if (isTvDevice()) {
    return createTvMutePopout(onActivity, onMuteChanged);
  }

  initMicState();

  const result = createVolumeSliderContent({
    onActivity,
    resolveIconUrl: (path) => path.replace(/^\//, ''),
    onMicToggle: handleMicToggle,
    getMicState: () => micState,
    onVolumeChange: (_level, muted) => onMuteChanged?.(muted),
  });

  const popup = document.createElement('div');
  popup.className = 'volume-slider-popup';
  popup.appendChild(result.element);

  return {
    element: popup,
    popoutWidth: 280,
    updateVolume: (level, muted) => result.updateVolume(level, muted),
    destroy: () => result.destroy(),
  };
}
