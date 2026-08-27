/**
 * Timer Renderer for Dashie Lite
 *
 * DOM creation and update functions for timer display.
 * Synced with Dashie Standard timer-overlay-renderer.js
 */

/**
 * Format seconds as display string (M:SS or H:MM:SS)
 */
function formatTime(seconds) {
  if (seconds >= 3600) {
    const hours = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    return `${hours}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  }
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

/**
 * Get play icon SVG
 */
function getPlayIcon() {
  return `
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
      <polygon points="5,3 19,12 5,21"/>
    </svg>
  `;
}

/**
 * Get pause icon SVG
 */
function getPauseIcon() {
  return `
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
      <rect x="6" y="4" width="4" height="16"/>
      <rect x="14" y="4" width="4" height="16"/>
    </svg>
  `;
}

/**
 * Render a full timer card (normal/expanded view)
 * @param {object} timer - Timer object
 * @param {object} handlers - Event handlers { onPauseResume, onCancel, onMinimize }
 * @returns {HTMLElement} Timer card element
 */
export function renderTimerCard(timer, handlers = {}) {
  const card = document.createElement('div');
  card.className = 'timer-card';
  card.dataset.timerId = timer.id;

  if (timer.state === 'paused') {
    card.classList.add('timer-card--paused');
  } else if (timer.state === 'completed') {
    card.classList.add('timer-card--completed');
  }

  const isPaused = timer.state === 'paused';
  const isCompleted = timer.state === 'completed';

  // Split label: "Timer 1 (5 min)" -> "Timer 1" + "5 min"
  const labelMatch = timer.label.match(/^(.+?)\s*\((.+)\)$/);
  const timerName = labelMatch ? labelMatch[1] : timer.label;
  const timerDuration = labelMatch ? labelMatch[2] : '';
  const timerDescription = timer.description || '';

  card.innerHTML = `
    <div class="timer-card__header">
      <div class="timer-card__header-content">
        <span class="timer-card__label">
          <span class="timer-card__name">${escapeHtml(timerName)}</span>
          ${timerDuration ? `<span class="timer-card__duration">(${escapeHtml(timerDuration)})</span>` : ''}
        </span>
        ${timerDescription ? `<div class="timer-card__description">${escapeHtml(timerDescription)}</div>` : ''}
      </div>
      <div class="timer-card__controls">
        <button class="timer-card__btn timer-card__btn--minimize" aria-label="Minimize timer" title="Minimize">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
        </button>
      </div>
    </div>
    <div class="timer-card__body">
      <span class="timer-card__time">${formatTime(timer.remainingSeconds)}</span>
      <div class="timer-card__body-controls">
        ${!isCompleted ? `
          <button class="timer-card__btn timer-card__btn--playpause" aria-label="${isPaused ? 'Resume timer' : 'Pause timer'}" title="${isPaused ? 'Resume' : 'Pause'}">
            ${isPaused ? getPlayIcon() : getPauseIcon()}
          </button>
        ` : ''}
        <button class="timer-card__btn timer-card__btn--close" aria-label="Cancel timer" title="Cancel">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
    </div>
  `;

  // Attach event handlers
  const minimizeBtn = card.querySelector('.timer-card__btn--minimize');
  const playPauseBtn = card.querySelector('.timer-card__btn--playpause');
  const closeBtn = card.querySelector('.timer-card__btn--close');

  if (minimizeBtn && handlers.onMinimize) {
    minimizeBtn.addEventListener('click', (e) => {
      console.log('[TimerRenderer] Minimize button clicked');
      e.stopPropagation();
      handlers.onMinimize();
    });
  }

  if (playPauseBtn && handlers.onPauseResume) {
    playPauseBtn.addEventListener('click', (e) => {
      console.log('[TimerRenderer] Play/Pause button clicked');
      e.stopPropagation();
      handlers.onPauseResume();
    });
  }

  if (closeBtn && handlers.onCancel) {
    closeBtn.addEventListener('click', (e) => {
      console.log('[TimerRenderer] Close button clicked');
      e.stopPropagation();
      handlers.onCancel();
    });
  }

  return card;
}

/**
 * Render a minimized timer
 * @param {object} timer - Timer object
 * @param {number} totalTimers - Total number of active timers (used to decide if badge is shown)
 * @param {object} handlers - Event handlers { onExpand }
 * @returns {HTMLElement} Minimized timer element
 */
export function renderMinimizedTimer(timer, totalTimers = 1, handlers = {}) {
  const mini = document.createElement('div');
  mini.className = 'timer-mini';
  mini.dataset.timerId = timer.id;
  mini.setAttribute('role', 'button');
  mini.setAttribute('tabindex', '0');
  mini.setAttribute('aria-label', `${timer.label}: ${formatTime(timer.remainingSeconds)} remaining. Click to expand.`);
  mini.title = `${timer.label} - Click to expand`;

  if (timer.state === 'completed') {
    mini.classList.add('timer-mini--completed');
  } else if (timer.state === 'paused') {
    mini.classList.add('timer-mini--paused');
  }

  // Show slot badge only when there are multiple timers
  const badgeHtml = totalTimers > 1 ? `<span class="timer-mini__badge">${timer.slot}</span>` : '';

  mini.innerHTML = `
    <span class="timer-mini__time">${formatTime(timer.remainingSeconds)}</span>
    ${badgeHtml}
  `;

  // Click to expand
  if (handlers.onExpand) {
    mini.addEventListener('click', handlers.onExpand);
  }

  // Keyboard support
  mini.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      mini.click();
    }
  });

  return mini;
}

/**
 * Update the time display on a timer card
 */
export function updateTimerCardTime(card, timer) {
  const timeEl = card.querySelector('.timer-card__time');
  if (timeEl) {
    timeEl.textContent = formatTime(timer.remainingSeconds);
  }
}

/**
 * Update the time display on a minimized timer
 */
export function updateMinimizedTimerTime(mini, timer) {
  const timeEl = mini.querySelector('.timer-mini__time');
  if (timeEl) {
    timeEl.textContent = formatTime(timer.remainingSeconds);
  }

  // Update aria-label
  mini.setAttribute('aria-label',
    `${timer.label}: ${formatTime(timer.remainingSeconds)} remaining. Click to expand.`
  );
}

/**
 * Set the visual state of a timer card
 */
export function setTimerCardState(card, state) {
  card.classList.remove('timer-card--paused', 'timer-card--completed', 'timer-card--running');

  if (state === 'paused') {
    card.classList.add('timer-card--paused');
  } else if (state === 'completed') {
    card.classList.add('timer-card--completed');
  } else if (state === 'running') {
    card.classList.add('timer-card--running');
  }

  // Update play/pause button
  const playPauseBtn = card.querySelector('.timer-card__btn--playpause');
  if (playPauseBtn) {
    if (state === 'completed') {
      playPauseBtn.style.display = 'none';
    } else {
      playPauseBtn.style.display = '';
      playPauseBtn.innerHTML = state === 'paused' ? getPlayIcon() : getPauseIcon();
      playPauseBtn.setAttribute('aria-label', state === 'paused' ? 'Resume timer' : 'Pause timer');
      playPauseBtn.title = state === 'paused' ? 'Resume' : 'Pause';
    }
  }
}

/**
 * Set the visual state of a minimized timer
 */
export function setMinimizedTimerState(mini, state) {
  mini.classList.remove('timer-mini--paused', 'timer-mini--completed');

  if (state === 'paused') {
    mini.classList.add('timer-mini--paused');
  } else if (state === 'completed') {
    mini.classList.add('timer-mini--completed');
  }
}
