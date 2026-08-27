/**
 * @dashieapp/timer-service
 *
 * Core timer state management with configurable persistence.
 * Supports up to 5 concurrent timers with full lifecycle control.
 *
 * Platform-agnostic: works in browsers, Node.js, and embedded environments.
 *
 * Events emitted:
 * - TIMER_CREATED: New timer started
 * - TIMER_UPDATED: Timer state changed (pause/resume/time modified)
 * - TIMER_COMPLETED: Timer reached zero
 * - TIMER_CANCELLED: Timer removed
 * - TIMERS_TICK: Every second update for all timers
 */

import { createLogger, EventEmitter, MemoryStorage } from '@dashieapp/core-utils';

const STORAGE_KEY = 'dashie-timers';
const DEFAULT_MAX_TIMERS = 5;
// Upper bound on a single timer's duration (48h). Guards against absurd inputs
// whose digits overflow the timer card and push the close button off-screen.
const MAX_TIMER_SECONDS = 48 * 60 * 60; // 172800
const TICK_INTERVAL_MS = 1000;

export class TimerService extends EventEmitter {
  /**
   * Create a new TimerService instance
   * @param {Object} options - Configuration options
   * @param {IStorage} [options.storage] - Storage implementation (defaults to MemoryStorage)
   * @param {number} [options.maxTimers] - Maximum concurrent timers (default: 5)
   * @param {Object} [options.logger] - Logger instance (defaults to createLogger)
   * @param {string} [options.deviceId] - Device identifier for multi-device setups
   */
  constructor(options = {}) {
    super();

    this.storage = options.storage || new MemoryStorage();
    this.maxTimers = options.maxTimers || DEFAULT_MAX_TIMERS;
    this.deviceId = options.deviceId || 'default';
    this.logger = options.logger || createLogger('TimerService');

    this.timers = new Map();
    this.tickInterval = null;

    this._loadFromStorage();
    this._startTicking();
  }

  /**
   * Create a new timer
   * @param {number} durationSeconds - Timer duration in seconds
   * @param {string} [description] - Optional description/purpose for the timer
   * @returns {{ success: boolean, timer?: object, slot?: number, error?: string }}
   */
  createTimer(durationSeconds, description = null) {
    // ⚠️ DUPLICATED LOGIC — timer-service is copy-pasted across 3 trees: js/core,
    // js/vendor/dashie-shared, and this kiosk-overlay/js/vendor copy. A behavior
    // change here (e.g. the MAX_TIMER_SECONDS cap, createTimer/addTime) MUST be
    // mirrored in all copies, or web app and kiosk timers diverge.
    // See .reference/_TECHNICAL_DEBT.md → "Timer & duration-parse duplication".
    if (this.timers.size >= this.maxTimers) {
      this.logger.warn('Maximum timers reached', { max: this.maxTimers });
      return {
        success: false,
        error: `Maximum ${this.maxTimers} timers allowed. Cancel one first.`
      };
    }

    if (durationSeconds <= 0) {
      this.logger.warn('Invalid duration', { durationSeconds });
      return { success: false, error: 'Duration must be greater than 0' };
    }

    if (durationSeconds > MAX_TIMER_SECONDS) {
      this.logger.warn('Duration exceeds maximum', { durationSeconds, max: MAX_TIMER_SECONDS });
      return { success: false, error: 'Timers can be at most 48 hours.' };
    }

    const slot = this._getNextAvailableSlot();
    const now = Date.now();
    const durationLabel = this.formatDurationLabel(durationSeconds);

    const timer = {
      id: `timer-${now}-${Math.random().toString(36).substr(2, 9)}`,
      slot,
      label: `Timer ${slot} (${durationLabel})`,
      description: description || null,
      durationSeconds,
      remainingSeconds: durationSeconds,
      state: 'running',
      isMinimized: false,
      deviceId: this.deviceId,
      createdAt: now,
      startedAt: now,
      pausedAt: null,
      accumulatedPauseTime: 0
    };

    this.timers.set(timer.id, timer);
    this._saveToStorage();

    this.emit('TIMER_CREATED', { timer: { ...timer } });

    const descPart = description ? ` for "${description}"` : '';
    this.logger.success(`Created ${timer.label}${descPart} for ${this.formatTime(durationSeconds)}`);

    return { success: true, timer: { ...timer }, slot };
  }

  /**
   * Pause a running timer
   * @param {string} timerId - Timer ID
   * @returns {boolean} Success
   */
  pauseTimer(timerId) {
    const timer = this.timers.get(timerId);
    if (!timer) {
      this.logger.warn('Timer not found for pause', { timerId });
      return false;
    }

    if (timer.state !== 'running') {
      this.logger.debug('Timer not running, cannot pause', { timerId, state: timer.state });
      return false;
    }

    const now = Date.now();
    const elapsedSinceStart = now - timer.startedAt;
    timer.remainingSeconds = Math.max(0,
      timer.durationSeconds - Math.floor((elapsedSinceStart - timer.accumulatedPauseTime) / 1000)
    );
    timer.state = 'paused';
    timer.pausedAt = now;

    this._saveToStorage();
    this.emit('TIMER_UPDATED', { timer: { ...timer }, action: 'paused' });
    this.logger.info(`Paused ${timer.label} at ${this.formatTime(timer.remainingSeconds)}`);

    return true;
  }

  /**
   * Resume a paused timer
   * @param {string} timerId - Timer ID
   * @returns {boolean} Success
   */
  resumeTimer(timerId) {
    const timer = this.timers.get(timerId);
    if (!timer) {
      this.logger.warn('Timer not found for resume', { timerId });
      return false;
    }

    if (timer.state !== 'paused') {
      this.logger.debug('Timer not paused, cannot resume', { timerId, state: timer.state });
      return false;
    }

    const now = Date.now();
    timer.accumulatedPauseTime += (now - timer.pausedAt);
    timer.state = 'running';
    timer.pausedAt = null;

    this._saveToStorage();
    this.emit('TIMER_UPDATED', { timer: { ...timer }, action: 'resumed' });
    this.logger.info(`Resumed ${timer.label} with ${this.formatTime(timer.remainingSeconds)} remaining`);

    return true;
  }

  /**
   * Cancel and remove a timer
   * @param {string} timerId - Timer ID
   * @returns {boolean} Success
   */
  cancelTimer(timerId) {
    const timer = this.timers.get(timerId);
    if (!timer) {
      this.logger.warn('Timer not found for cancel', { timerId });
      return false;
    }

    const timerCopy = { ...timer };
    this.timers.delete(timerId);
    this._saveToStorage();

    this.emit('TIMER_CANCELLED', { timer: timerCopy });
    this.logger.info(`Cancelled ${timerCopy.label}`);

    return true;
  }

  /**
   * Add time to a timer
   * @param {string} timerId - Timer ID
   * @param {number} seconds - Seconds to add
   * @returns {number|null} New remaining seconds or null if failed
   */
  addTime(timerId, seconds) {
    const timer = this.timers.get(timerId);
    if (!timer) {
      this.logger.warn('Timer not found for addTime', { timerId });
      return null;
    }

    if (timer.state === 'completed') {
      this.logger.warn('Cannot add time to completed timer', { timerId });
      return null;
    }

    // Bound the extended duration too, so repeated "add an hour" can't blow
    // past the 48h cap that createTimer enforces.
    const cappedDuration = Math.min(timer.durationSeconds + seconds, MAX_TIMER_SECONDS);
    const appliedSeconds = cappedDuration - timer.durationSeconds;
    timer.durationSeconds = cappedDuration;
    timer.remainingSeconds += appliedSeconds;

    this._saveToStorage();
    this.emit('TIMER_UPDATED', { timer: { ...timer }, action: 'time_added', seconds: appliedSeconds });
    this.logger.info(`Added ${appliedSeconds}s to ${timer.label}, now ${this.formatTime(timer.remainingSeconds)}`);

    return timer.remainingSeconds;
  }

  /**
   * Subtract time from a timer
   * @param {string} timerId - Timer ID
   * @param {number} seconds - Seconds to subtract
   * @returns {number|null} New remaining seconds or null if failed
   */
  subtractTime(timerId, seconds) {
    const timer = this.timers.get(timerId);
    if (!timer) {
      this.logger.warn('Timer not found for subtractTime', { timerId });
      return null;
    }

    if (timer.state === 'completed') {
      this.logger.warn('Cannot subtract time from completed timer', { timerId });
      return null;
    }

    timer.durationSeconds = Math.max(0, timer.durationSeconds - seconds);
    timer.remainingSeconds = Math.max(0, timer.remainingSeconds - seconds);

    if (timer.remainingSeconds === 0 && timer.state === 'running') {
      timer.state = 'completed';
      this.emit('TIMER_COMPLETED', { timer: { ...timer } });
    }

    this._saveToStorage();
    this.emit('TIMER_UPDATED', { timer: { ...timer }, action: 'time_subtracted', seconds });
    this.logger.info(`Subtracted ${seconds}s from ${timer.label}, now ${this.formatTime(timer.remainingSeconds)}`);

    return timer.remainingSeconds;
  }

  /**
   * Minimize a timer (UI hint)
   * @param {string} timerId - Timer ID
   * @returns {boolean} Success
   */
  minimizeTimer(timerId) {
    const timer = this.timers.get(timerId);
    if (!timer) {
      this.logger.warn('Timer not found for minimize', { timerId });
      return false;
    }

    timer.isMinimized = true;
    this._saveToStorage();
    this.emit('TIMER_UPDATED', { timer: { ...timer }, action: 'minimized' });
    this.logger.debug(`Minimized ${timer.label}`);

    return true;
  }

  /**
   * Expand a minimized timer
   * @param {string} timerId - Timer ID
   * @returns {boolean} Success
   */
  expandTimer(timerId) {
    const timer = this.timers.get(timerId);
    if (!timer) {
      this.logger.warn('Timer not found for expand', { timerId });
      return false;
    }

    timer.isMinimized = false;
    this._saveToStorage();
    this.emit('TIMER_UPDATED', { timer: { ...timer }, action: 'expanded' });
    this.logger.debug(`Expanded ${timer.label}`);

    return true;
  }

  /**
   * Get a timer by ID
   * @param {string} timerId - Timer ID
   * @returns {object|null} Timer object or null
   */
  getTimer(timerId) {
    const timer = this.timers.get(timerId);
    return timer ? { ...timer } : null;
  }

  /**
   * Get a timer by slot number
   * @param {number} slot - Slot number (1-based)
   * @returns {object|null} Timer object or null
   */
  getTimerBySlot(slot) {
    for (const timer of this.timers.values()) {
      if (timer.slot === slot) {
        return { ...timer };
      }
    }
    return null;
  }

  /**
   * Get a timer by original duration
   * @param {number} seconds - Original duration in seconds
   * @returns {object|null} Timer object or null (returns first match)
   */
  getTimerByDuration(seconds) {
    for (const timer of this.timers.values()) {
      if (timer.durationSeconds === seconds) {
        return { ...timer };
      }
    }
    return null;
  }

  /**
   * Get all timers matching a duration (for disambiguation)
   * @param {number} seconds - Original duration in seconds
   * @returns {object[]} Array of matching timers
   */
  getTimersByDuration(seconds) {
    const matches = [];
    for (const timer of this.timers.values()) {
      if (timer.durationSeconds === seconds) {
        matches.push({ ...timer });
      }
    }
    return matches;
  }

  /**
   * Get a timer by description keyword
   * @param {string} keyword - Keyword to search for in description
   * @returns {object|null} Timer object or null
   */
  getTimerByDescriptionKeyword(keyword) {
    if (!keyword) return null;
    const lowerKeyword = keyword.toLowerCase();

    for (const timer of this.timers.values()) {
      if (timer.description && timer.description.toLowerCase().includes(lowerKeyword)) {
        return { ...timer };
      }
    }
    return null;
  }

  /**
   * Get all timers matching a description keyword
   * @param {string} keyword - Keyword to search for
   * @returns {object[]} Array of matching timers
   */
  getTimersByDescriptionKeyword(keyword) {
    if (!keyword) return [];
    const lowerKeyword = keyword.toLowerCase();
    const matches = [];

    for (const timer of this.timers.values()) {
      if (timer.description && timer.description.toLowerCase().includes(lowerKeyword)) {
        matches.push({ ...timer });
      }
    }
    return matches;
  }

  /**
   * Get all active timers
   * @returns {object[]} Array of all timers
   */
  getAllTimers() {
    return Array.from(this.timers.values()).map(t => ({ ...t }));
  }

  /**
   * Get count of active timers
   * @returns {number} Timer count
   */
  getActiveTimerCount() {
    return this.timers.size;
  }

  /**
   * Get remaining time for a timer
   * @param {string} timerId - Timer ID
   * @returns {number|null} Remaining seconds or null
   */
  getRemainingTime(timerId) {
    const timer = this.timers.get(timerId);
    if (!timer) return null;

    if (timer.state === 'paused' || timer.state === 'completed') {
      return timer.remainingSeconds;
    }

    const now = Date.now();
    const elapsedSinceStart = now - timer.startedAt;
    const activeTime = elapsedSinceStart - timer.accumulatedPauseTime;
    return Math.max(0, timer.durationSeconds - Math.floor(activeTime / 1000));
  }

  /**
   * Format seconds as display string (MM:SS or H:MM:SS)
   * @param {number} seconds - Seconds to format
   * @returns {string} Formatted time string
   */
  formatTime(seconds) {
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
   * Format seconds as compact label string
   * @param {number} seconds - Seconds to format
   * @returns {string} Compact time string (e.g., "5 min", "1 hr 30 min")
   */
  formatDurationLabel(seconds) {
    const hours = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;

    const parts = [];
    if (hours > 0) parts.push(`${hours} hr`);
    if (mins > 0) parts.push(`${mins} min`);
    if (secs > 0 && hours === 0 && mins === 0) parts.push(`${secs} sec`);

    return parts.join(' ') || '0 sec';
  }

  /**
   * Format seconds as spoken string (for TTS)
   * @param {number} seconds - Seconds to format
   * @returns {string} Spoken time string
   */
  formatTimeSpoken(seconds) {
    if (seconds >= 3600) {
      const hours = Math.floor(seconds / 3600);
      const mins = Math.floor((seconds % 3600) / 60);
      const secs = seconds % 60;

      const parts = [];
      if (hours > 0) parts.push(`${hours} hour${hours !== 1 ? 's' : ''}`);
      if (mins > 0) parts.push(`${mins} minute${mins !== 1 ? 's' : ''}`);
      if (secs > 0 && hours === 0) parts.push(`${secs} second${secs !== 1 ? 's' : ''}`);

      return parts.join(' and ');
    }

    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;

    const parts = [];
    if (mins > 0) parts.push(`${mins} minute${mins !== 1 ? 's' : ''}`);
    if (secs > 0 || mins === 0) parts.push(`${secs} second${secs !== 1 ? 's' : ''}`);

    return parts.join(' and ');
  }

  /**
   * Resolve a timer reference to a specific timer
   * Handles: "timer 1", "the 5 minute timer", "the pasta timer", etc.
   * @param {object} reference - Reference object with potential fields
   * @returns {{ timer: object|null, ambiguous: boolean, options: object[] }}
   */
  resolveTimerReference(reference = {}) {
    const timers = this.getAllTimers();

    if (timers.length === 0) {
      return { timer: null, ambiguous: false, options: [] };
    }

    if (timers.length === 1) {
      return { timer: timers[0], ambiguous: false, options: timers };
    }

    // Check for slot/number reference
    if (reference.slot || reference.timer_number) {
      const slot = reference.slot || reference.timer_number;
      const timer = this.getTimerBySlot(parseInt(slot, 10));
      return { timer, ambiguous: false, options: timer ? [timer] : [] };
    }

    // Check for description keyword reference
    if (reference.keyword) {
      const matches = this.getTimersByDescriptionKeyword(reference.keyword);

      if (matches.length === 1) {
        return { timer: matches[0], ambiguous: false, options: matches };
      } else if (matches.length > 1) {
        return { timer: null, ambiguous: true, options: matches };
      }
    }

    // Check for duration reference
    if (reference.duration_seconds || reference.duration_minutes || reference.duration_hours) {
      let targetSeconds = 0;
      if (reference.duration_hours) targetSeconds += reference.duration_hours * 3600;
      if (reference.duration_minutes) targetSeconds += reference.duration_minutes * 60;
      if (reference.duration_seconds) targetSeconds += reference.duration_seconds;

      const matches = this.getTimersByDuration(targetSeconds);

      if (matches.length === 1) {
        return { timer: matches[0], ambiguous: false, options: matches };
      } else if (matches.length > 1) {
        if (reference.ordinal) {
          const index = reference.ordinal - 1;
          if (index >= 0 && index < matches.length) {
            return { timer: matches[index], ambiguous: false, options: matches };
          }
        }
        return { timer: null, ambiguous: true, options: matches };
      }
    }

    return { timer: null, ambiguous: true, options: timers };
  }

  // Private methods

  _getNextAvailableSlot() {
    const usedSlots = new Set();
    for (const timer of this.timers.values()) {
      usedSlots.add(timer.slot);
    }
    for (let slot = 1; slot <= this.maxTimers; slot++) {
      if (!usedSlots.has(slot)) {
        return slot;
      }
    }
    return 1;
  }

  _startTicking() {
    if (this.tickInterval) return;

    this.tickInterval = setInterval(() => this._tick(), TICK_INTERVAL_MS);
    this.logger.debug('Started tick interval');
  }

  _stopTicking() {
    if (this.tickInterval) {
      clearInterval(this.tickInterval);
      this.tickInterval = null;
      this.logger.debug('Stopped tick interval');
    }
  }

  _tick() {
    const now = Date.now();
    let anyUpdated = false;

    for (const timer of this.timers.values()) {
      if (timer.state === 'running') {
        const elapsedSinceStart = now - timer.startedAt;
        const activeTime = elapsedSinceStart - timer.accumulatedPauseTime;
        const newRemaining = Math.max(0, timer.durationSeconds - Math.floor(activeTime / 1000));

        if (newRemaining !== timer.remainingSeconds) {
          timer.remainingSeconds = newRemaining;
          anyUpdated = true;

          if (newRemaining === 0) {
            timer.state = 'completed';
            this.emit('TIMER_COMPLETED', { timer: { ...timer } });
            this.logger.info(`${timer.label} completed!`);
          }
        }
      }
    }

    if (anyUpdated) {
      this._saveToStorage();
    }

    if (this.timers.size > 0) {
      this.emit('TIMERS_TICK', {
        timers: Array.from(this.timers.values()).map(t => ({ ...t }))
      });
    }
  }

  _saveToStorage() {
    try {
      const data = JSON.stringify(Array.from(this.timers.values()));
      this.storage.setItem(STORAGE_KEY, data);
    } catch (error) {
      this.logger.error('Failed to save timers to storage', error);
    }
  }

  _loadFromStorage() {
    try {
      const data = this.storage.getItem(STORAGE_KEY);
      if (!data) return;

      const timers = JSON.parse(data);
      const now = Date.now();

      for (const timer of timers) {
        // Filter by deviceId if set
        if (timer.deviceId && timer.deviceId !== this.deviceId) {
          continue;
        }

        if (timer.state === 'running') {
          const elapsedSinceStart = now - timer.startedAt;
          const activeTime = elapsedSinceStart - (timer.accumulatedPauseTime || 0);
          timer.remainingSeconds = Math.max(0, timer.durationSeconds - Math.floor(activeTime / 1000));

          if (timer.remainingSeconds === 0) {
            timer.state = 'completed';
          }
        }

        this.timers.set(timer.id, timer);
      }

      this.logger.info(`Loaded ${this.timers.size} timer(s) from storage`);

      // Emit completion events for timers that completed while away
      for (const timer of this.timers.values()) {
        if (timer.state === 'completed') {
          setTimeout(() => {
            this.emit('TIMER_COMPLETED', { timer: { ...timer } });
          }, 500);
        }
      }
    } catch (error) {
      this.logger.error('Failed to load timers from storage', error);
    }
  }

  /**
   * Clean up resources
   */
  destroy() {
    this._stopTicking();
    this.timers.clear();
    this.removeAllListeners();
  }
}
