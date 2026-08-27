/**
 * Camera popout for the Dash Menu.
 *
 * Shows:
 * 1. "Video Feeds" header
 * 2. Three circular action buttons: Grid, Pause/Resume, Mute alerts
 * 3. Stream list with +/- buttons to show/dismiss feeds
 */

import { getEnabledVideoFeeds } from '../../../js/utils/video-feed-config.js';

const resolveIconUrl = (path) => path.replace(/^\//, '');

/**
 * Creates the camera popout panel.
 * @param {Function} onActivity - called on user interaction (resets auto-hide)
 * @param {Function} onPauseChanged - called when pause state changes (updates sidebar icon)
 * @returns {{ element: HTMLElement, popoutWidth: number, destroy: Function }}
 */
export function createCameraPopout(onActivity, onPauseChanged) {
  let isPaused = false;
  let isMuted = false;

  // Notify Kotlin that camera popout is open (shifts grid feeds to preview mode)
  try {
    if (typeof DashieNative !== 'undefined' && typeof DashieNative.setVideoFeedMenuOpen === 'function') {
      DashieNative.setVideoFeedMenuOpen(true);
    }
  } catch (e) { /* ignore */ }

  // Read initial states from Kotlin bridge
  try {
    if (typeof DashieNative !== 'undefined') {
      if (typeof DashieNative.areVideoFeedsPaused === 'function') {
        isPaused = DashieNative.areVideoFeedsPaused();
      }
      if (typeof DashieNative.areVideoFeedAlertsMuted === 'function') {
        isMuted = DashieNative.areVideoFeedAlertsMuted();
      }
    }
  } catch (e) {
    console.warn('[DashMenu] Could not read video feed states:', e);
  }

  // Read active feed rule IDs
  const activeFeedIds = getActiveFeedIds();

  const popup = document.createElement('div');
  popup.className = 'volume-slider-popup dm-camera-popout';

  // Header
  const header = document.createElement('div');
  header.className = 'dm-camera-header';
  header.textContent = 'Video Feeds';
  popup.appendChild(header);

  // Action buttons row
  const actions = document.createElement('div');
  actions.className = 'dm-camera-actions';

  // Layout toggle button (Sidebar ↔ Float)
  const layoutIcons = {
    sidebar: 'artwork/icon-sidebar.svg',
    float: 'artwork/icon-float.svg',
  };
  const layoutLabels = { sidebar: 'Sidebar', float: 'Float' };
  let currentLayout = 'sidebar';
  try {
    if (typeof DashieNative !== 'undefined' && typeof DashieNative.getVideoFeedLayout === 'function') {
      currentLayout = DashieNative.getVideoFeedLayout() || 'sidebar';
    }
  } catch (e) { /* ignore */ }

  const layoutBtn = createActionButton(layoutIcons[currentLayout] || layoutIcons.sidebar, 'Layout', layoutLabels[currentLayout] || 'Sidebar');
  layoutBtn.addEventListener('click', () => {
    onActivity?.();
    try {
      if (typeof DashieNative !== 'undefined' && typeof DashieNative.cycleVideoFeedLayout === 'function') {
        const nextLayout = DashieNative.cycleVideoFeedLayout();
        currentLayout = nextLayout || 'sidebar';
      }
    } catch (e) {
      console.warn('[DashMenu] Failed to cycle layout:', e);
    }
    const icon = layoutBtn.querySelector('.dm-camera-action-icon');
    if (icon) icon.src = layoutIcons[currentLayout] || layoutIcons.sidebar;
    const label = layoutBtn.querySelector('.dm-camera-action-label');
    if (label) label.textContent = layoutLabels[currentLayout] || 'Sidebar';
  });
  actions.appendChild(layoutBtn);

  // Pause/Resume button
  const pauseBtn = createActionButton(
    'artwork/icon-video-play.svg',
    'Pause',
    isPaused ? 'Feeds Off' : 'Feeds On'
  );
  if (isPaused) pauseBtn.classList.add('dm-camera-action-btn--struck');

  pauseBtn.addEventListener('click', () => {
    onActivity?.();
    isPaused = !isPaused;
    try {
      if (typeof DashieNative !== 'undefined') {
        if (isPaused) DashieNative.pauseVideoFeeds();
        else DashieNative.resumeVideoFeeds();
      }
    } catch (e) {
      console.warn('[DashMenu] Failed to toggle video feeds pause:', e);
    }
    pauseBtn.classList.toggle('dm-camera-action-btn--struck', isPaused);
    pauseBtn.title = isPaused ? 'Resume' : 'Pause';
    const label = pauseBtn.querySelector('.dm-camera-action-label');
    if (label) label.textContent = isPaused ? 'Feeds Off' : 'Feeds On';
    onPauseChanged?.(isPaused);
    if (isPaused) {
      activeFeedIds.clear();
      updateStreamButtons();
    }
  });
  actions.appendChild(pauseBtn);

  // Mute alerts button
  const muteBtn = createActionButton(
    'artwork/icon-alert-bell.svg',
    'Mute Alerts',
    isMuted ? 'Alerts Off' : 'Alerts On'
  );
  if (isMuted) muteBtn.classList.add('dm-camera-action-btn--struck');

  muteBtn.addEventListener('click', () => {
    onActivity?.();
    isMuted = !isMuted;
    muteBtn.classList.toggle('dm-camera-action-btn--struck', isMuted);
    muteBtn.title = isMuted ? 'Unmute Alerts' : 'Mute Alerts';
    const label = muteBtn.querySelector('.dm-camera-action-label');
    if (label) label.textContent = isMuted ? 'Alerts Off' : 'Alerts On';
    try {
      if (typeof DashieNative !== 'undefined' && typeof DashieNative.setVideoFeedAlertsMuted === 'function') {
        DashieNative.setVideoFeedAlertsMuted(isMuted);
      }
    } catch (e) {
      console.warn('[DashMenu] Failed to toggle alerts mute:', e);
    }
  });
  actions.appendChild(muteBtn);

  popup.appendChild(actions);

  // Stream list
  const streamList = document.createElement('div');
  streamList.className = 'dm-camera-stream-list';
  const streamEntries = [];

  const streams = getConfiguredStreams();
  // Sort: online cameras first, offline at the bottom
  streams.sort((a, b) => (a.available === false ? 1 : 0) - (b.available === false ? 1 : 0));
  if (streams.length > 0) {
    for (const stream of streams) {
      const isOffline = stream.available === false;
      const item = document.createElement('div');
      item.className = 'dm-camera-stream-item';
      if (isOffline) item.classList.add('dm-camera-stream-item--offline');
      const isActive = activeFeedIds.has(stream.id);

      const toggleBtn = document.createElement('button');
      toggleBtn.className = 'dm-camera-stream-toggle-btn';
      toggleBtn.textContent = isActive ? '−' : '+';
      toggleBtn.title = isActive ? 'Dismiss feed' : 'Show feed';
      if (isActive) toggleBtn.classList.add('dm-camera-stream-toggle-btn--active');
      item.appendChild(toggleBtn);

      const name = document.createElement('span');
      name.className = 'dm-camera-stream-name';
      if (isActive) name.classList.add('dm-camera-stream-name--active');
      name.textContent = stream.name || stream.cameraName || stream.cameraEntityId || 'Unnamed';
      item.appendChild(name);

      if (isOffline) {
        const badge = document.createElement('span');
        badge.className = 'dm-offline-badge';
        badge.textContent = 'offline';
        item.appendChild(badge);
      }

      toggleBtn.addEventListener('click', () => {
        onActivity?.();
        const currentlyActive = activeFeedIds.has(stream.id);
        try {
          if (typeof DashieNative !== 'undefined') {
            if (currentlyActive) {
              DashieNative.dismissVideoFeedByRuleId(stream.id);
              activeFeedIds.delete(stream.id);
            } else {
              DashieNative.showVideoFeedByRuleId(stream.id);
              activeFeedIds.add(stream.id);
            }
          }
        } catch (e) {
          console.warn('[DashMenu] show/dismiss video feed failed:', e);
        }
        const nowActive = activeFeedIds.has(stream.id);
        toggleBtn.textContent = nowActive ? '−' : '+';
        toggleBtn.title = nowActive ? 'Dismiss feed' : 'Show feed';
        toggleBtn.classList.toggle('dm-camera-stream-toggle-btn--active', nowActive);
        name.classList.toggle('dm-camera-stream-name--active', nowActive);
      });

      streamList.appendChild(item);
      streamEntries.push({ id: stream.id, btn: toggleBtn, name });
    }
  } else {
    const empty = document.createElement('div');
    empty.className = 'dm-camera-stream-empty';
    empty.textContent = 'No streams configured';
    streamList.appendChild(empty);
  }

  popup.appendChild(streamList);

  /** Update all stream buttons to reflect current active state */
  function updateStreamButtons() {
    for (const { id, btn, name } of streamEntries) {
      const isActive = activeFeedIds.has(id);
      btn.textContent = isActive ? '−' : '+';
      btn.title = isActive ? 'Dismiss feed' : 'Show feed';
      btn.classList.toggle('dm-camera-stream-toggle-btn--active', isActive);
      name.classList.toggle('dm-camera-stream-name--active', isActive);
    }
  }

  return {
    element: popup,
    popoutWidth: 260,
    destroy: () => {
      try {
        if (typeof DashieNative !== 'undefined' && typeof DashieNative.setVideoFeedMenuOpen === 'function') {
          DashieNative.setVideoFeedMenuOpen(false);
        }
      } catch (e) { /* ignore */ }
    },
  };
}

/**
 * Create a circular action button with an icon image and text label below.
 */
function createActionButton(iconPath, title, labelText) {
  const wrapper = document.createElement('div');
  wrapper.className = 'dm-camera-action-wrapper';
  wrapper.title = title;

  const btn = document.createElement('button');
  btn.className = 'dm-camera-action-btn';

  const img = document.createElement('img');
  img.className = 'dm-camera-action-icon';
  img.src = iconPath;
  img.alt = title;
  btn.appendChild(img);

  wrapper.appendChild(btn);

  const label = document.createElement('span');
  label.className = 'dm-camera-action-label';
  label.textContent = labelText || title;
  wrapper.appendChild(label);

  // Forward classList operations to the button only (not wrapper)
  // so the struck ::after line only renders on the circular button
  wrapper.classList.add = (...args) => btn.classList.add(...args);
  wrapper.classList.remove = (...args) => btn.classList.remove(...args);
  wrapper.classList.toggle = (...args) => btn.classList.toggle(...args);

  return wrapper;
}

/**
 * Read configured video feed streams — native-first (Kotlin owns the config on
 * Android; localStorage is only a mirror). See js/utils/video-feed-config.js.
 */
function getConfiguredStreams() {
  try {
    return getEnabledVideoFeeds();
  } catch (e) {
    return [];
  }
}

/**
 * Get set of currently active video feed rule IDs from Kotlin bridge.
 */
function getActiveFeedIds() {
  try {
    if (typeof DashieNative !== 'undefined' && typeof DashieNative.getActiveVideoFeedRuleIds === 'function') {
      const json = DashieNative.getActiveVideoFeedRuleIds();
      return new Set(JSON.parse(json));
    }
  } catch (e) {
    console.warn('[DashMenu] Could not read active feed IDs:', e);
  }
  return new Set();
}
