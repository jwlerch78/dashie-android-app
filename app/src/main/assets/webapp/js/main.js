/**
 * Dashie Lite - Main Application
 *
 * Lightweight dashboard for HA Addon with timer support.
 * Uses shared @dashieapp/timer-service for core functionality.
 */

import { TimerService, BrowserStorage } from '@dashieapp/timer-service';
import { getBrand } from './brand.js';
import { renderTimerCard, updateTimerCardTime, setTimerCardState } from './timer-renderer.js';

// Initialize timer service with browser storage
const timerService = new TimerService({
  storage: new BrowserStorage('dashie-lite'),
  maxTimers: 5,
  deviceId: 'dashie-lite'
});

// Expose for console testing
window.timerService = timerService;

// DOM Elements
const timerList = document.getElementById('timerList');
const timerEmpty = document.getElementById('timerEmpty');
const addTimerBtn = document.getElementById('addTimerBtn');
const addTimerModal = document.getElementById('addTimerModal');
const cancelTimerBtn = document.getElementById('cancelTimerBtn');
const startTimerBtn = document.getElementById('startTimerBtn');
const timerMinutes = document.getElementById('timerMinutes');
const timerSeconds = document.getElementById('timerSeconds');
const timerDescription = document.getElementById('timerDescription');
const connectionStatus = document.getElementById('connectionStatus');
const actionBtns = document.querySelectorAll('.action-btn');

// State
let timerCards = new Map();

/**
 * Initialize the application
 */
function init() {
  setupEventListeners();
  setupTimerEventHandlers();
  renderAllTimers();
  connectWebSocket();

  // Header + document title come from the brand table, not the HTML — index.html is static and
  // shared by both editions, so a literal there ships a Dashie string to every Chickadee device.
  const brand = getBrand();
  document.title = brand.name;
  const titleEl = document.getElementById('app-title');
  if (titleEl) titleEl.textContent = brand.name;
  console.log(brand.name + ' overlay initialized');
  console.log('Timer service available at window.timerService');
}

/**
 * Setup DOM event listeners
 */
function setupEventListeners() {
  // Add Timer Modal
  addTimerBtn.addEventListener('click', () => openModal());
  cancelTimerBtn.addEventListener('click', () => closeModal());
  startTimerBtn.addEventListener('click', () => createTimerFromModal());

  // Close modal on backdrop click
  addTimerModal.querySelector('.modal__backdrop').addEventListener('click', () => closeModal());

  // Quick action buttons
  actionBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      if (action.startsWith('timer-')) {
        const minutes = parseInt(action.split('-')[1], 10);
        timerService.createTimer(minutes * 60);
      }
    });
  });

  // Keyboard shortcuts
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !addTimerModal.hidden) {
      closeModal();
    }
  });
}

/**
 * Setup timer service event handlers
 */
function setupTimerEventHandlers() {
  timerService.on('TIMER_CREATED', ({ timer }) => {
    console.log('Timer created:', timer);
    addTimerCard(timer);
    updateEmptyState();
  });

  timerService.on('TIMER_UPDATED', ({ timer, action }) => {
    console.log('Timer updated:', timer.id, action);
    const card = timerCards.get(timer.id);
    if (card) {
      setTimerCardState(card, timer.state);
      updateTimerCardTime(card, timer);
    }
  });

  timerService.on('TIMER_COMPLETED', ({ timer }) => {
    console.log('Timer completed:', timer);
    const card = timerCards.get(timer.id);
    if (card) {
      setTimerCardState(card, 'completed');
    }
    // Play alarm sound (simple beep)
    playAlarm();
  });

  timerService.on('TIMER_CANCELLED', ({ timer }) => {
    console.log('Timer cancelled:', timer.id);
    removeTimerCard(timer.id);
    updateEmptyState();
  });

  timerService.on('TIMERS_TICK', ({ timers }) => {
    for (const timer of timers) {
      const card = timerCards.get(timer.id);
      if (card) {
        updateTimerCardTime(card, timer);
      }
    }
  });
}

/**
 * Render all existing timers (on page load)
 */
function renderAllTimers() {
  const timers = timerService.getAllTimers();
  for (const timer of timers) {
    addTimerCard(timer);
  }
  updateEmptyState();
}

/**
 * Add a timer card to the DOM
 */
function addTimerCard(timer) {
  const card = renderTimerCard(timer, {
    onPauseResume: () => {
      if (timer.state === 'running') {
        timerService.pauseTimer(timer.id);
      } else if (timer.state === 'paused') {
        timerService.resumeTimer(timer.id);
      }
    },
    onCancel: () => {
      timerService.cancelTimer(timer.id);
    }
  });

  timerCards.set(timer.id, card);
  timerList.insertBefore(card, timerEmpty);
}

/**
 * Remove a timer card from the DOM
 */
function removeTimerCard(timerId) {
  const card = timerCards.get(timerId);
  if (card) {
    card.classList.add('timer-card--removing');
    card.addEventListener('animationend', () => {
      card.remove();
      timerCards.delete(timerId);
    });
  }
}

/**
 * Update empty state visibility
 */
function updateEmptyState() {
  const hasTimers = timerCards.size > 0;
  timerEmpty.style.display = hasTimers ? 'none' : 'block';
}

/**
 * Open add timer modal
 */
function openModal() {
  addTimerModal.hidden = false;
  timerMinutes.focus();
  timerMinutes.select();
}

/**
 * Close add timer modal
 */
function closeModal() {
  addTimerModal.hidden = true;
  timerMinutes.value = '5';
  timerSeconds.value = '0';
  timerDescription.value = '';
}

/**
 * Create timer from modal inputs
 */
function createTimerFromModal() {
  const minutes = parseInt(timerMinutes.value, 10) || 0;
  const seconds = parseInt(timerSeconds.value, 10) || 0;
  const description = timerDescription.value.trim() || null;

  const totalSeconds = (minutes * 60) + seconds;

  if (totalSeconds > 0) {
    timerService.createTimer(totalSeconds, description);
    closeModal();
  }
}

/**
 * Play alarm sound
 */
function playAlarm() {
  try {
    const audioContext = new (window.AudioContext || window.webkitAudioContext)();
    const oscillator = audioContext.createOscillator();
    const gainNode = audioContext.createGain();

    oscillator.connect(gainNode);
    gainNode.connect(audioContext.destination);

    oscillator.frequency.value = 880; // A5
    oscillator.type = 'sine';
    gainNode.gain.value = 0.3;

    oscillator.start();

    // Beep pattern: 3 short beeps
    setTimeout(() => gainNode.gain.value = 0, 200);
    setTimeout(() => gainNode.gain.value = 0.3, 400);
    setTimeout(() => gainNode.gain.value = 0, 600);
    setTimeout(() => gainNode.gain.value = 0.3, 800);
    setTimeout(() => gainNode.gain.value = 0, 1000);
    setTimeout(() => {
      oscillator.stop();
      audioContext.close();
    }, 1100);
  } catch (e) {
    console.warn('Could not play alarm:', e);
  }
}

/**
 * Connect to WebSocket for real-time updates
 */
function connectWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${window.location.host}/ws?device=dashie-lite`;

  try {
    const ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      console.log('WebSocket connected');
      connectionStatus.textContent = 'Connected';
      connectionStatus.classList.add('header__status--connected');
      connectionStatus.classList.remove('header__status--error');
    };

    ws.onclose = () => {
      console.log('WebSocket disconnected');
      connectionStatus.textContent = 'Disconnected';
      connectionStatus.classList.remove('header__status--connected');
      connectionStatus.classList.add('header__status--error');

      // Reconnect after delay
      setTimeout(connectWebSocket, 5000);
    };

    ws.onerror = (err) => {
      console.error('WebSocket error:', err);
    };

    ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        handleWebSocketMessage(message);
      } catch (e) {
        console.warn('Invalid WebSocket message:', event.data);
      }
    };
  } catch (e) {
    console.warn('Could not connect WebSocket:', e);
    connectionStatus.textContent = 'Offline';
    connectionStatus.classList.add('header__status--error');
  }
}

/**
 * Handle WebSocket messages
 */
function handleWebSocketMessage(message) {
  console.log('WS message:', message);

  // Handle timer sync from server
  if (message.type === 'timer:created' || message.type === 'timer:updated') {
    // Server-side timer change - refresh our local state
    // For now, just log it
    console.log('Server timer event:', message);
  }
}

// Initialize on DOM ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
