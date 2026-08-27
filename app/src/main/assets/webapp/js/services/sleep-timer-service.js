/**
 * Sleep Timer Service for Kiosk Overlay
 *
 * Handles automatic sleep/wake based on configured schedule or inactivity timeout.
 * Uses Android bridge (DashieNative) for settings and screen control.
 *
 * Features:
 * - Time-based scheduling: Sleep at configured time, wake at configured time
 * - Inactivity-based: Sleep after period of no user interaction
 * - Re-sleep timer: When user wakes during sleep hours, re-sleep after delay
 * - Activity tracking: Resets inactivity timer on user interaction
 */

const LOG_TAG = '[SleepTimer]';

class KioskSleepTimerService {
  constructor() {
    this.checkInterval = null;
    this.resleepTimer = null;
    this.inactivityTimer = null;
    this.initialized = false;
    this.isSleeping = false;
    this.lastActivityTime = Date.now();

    // Bind methods for event listeners
    this._onUserActivity = this._onUserActivity.bind(this);
  }

  /**
   * Get the Android bridge
   */
  get bridge() {
    return window.DashieNative;
  }

  /**
   * Initialize the sleep timer service
   */
  initialize() {
    if (this.initialized) {
      console.warn(LOG_TAG, 'Already initialized');
      return;
    }

    console.log(LOG_TAG, '🌙 Initializing sleep timer service...');

    // Set up user activity tracking for inactivity timeout
    this._setupActivityTracking();

    // Check immediately on init
    this._checkSleepWake();

    // Check every 60 seconds for schedule-based sleep
    this.checkInterval = setInterval(() => {
      this._checkSleepWake();
    }, 60000);

    this.initialized = true;
    console.log(LOG_TAG, '✓ Sleep timer service initialized');
  }

  /**
   * Get current sleep settings from Android bridge
   */
  _getSettings() {
    try {
      const json = this.bridge?.getSleepSettings?.();
      if (json) {
        return JSON.parse(json);
      }
    } catch (e) {
      console.warn(LOG_TAG, 'Failed to get sleep settings:', e);
    }
    return {
      enabled: false,
      method: 'schedule',
      sleepTime: '22:00',
      wakeTime: '07:00',
      resleepTimeout: 5,
      inactivityTimeout: 30
    };
  }

  /**
   * Check if current time is within sleep period (for schedule method)
   */
  _isTimeInSleepPeriod() {
    const settings = this._getSettings();
    if (!settings.enabled || settings.method !== 'schedule') {
      return false;
    }

    const now = new Date();
    const currentMinutes = now.getHours() * 60 + now.getMinutes();

    // Parse sleep/wake times (format: "HH:MM")
    const [sleepHour, sleepMin] = settings.sleepTime.split(':').map(Number);
    const [wakeHour, wakeMin] = settings.wakeTime.split(':').map(Number);

    const sleepMinutes = sleepHour * 60 + sleepMin;
    const wakeMinutes = wakeHour * 60 + wakeMin;

    // Handle case where sleep period crosses midnight
    if (sleepMinutes > wakeMinutes) {
      // Sleep period crosses midnight (e.g., 22:00 to 07:00)
      return currentMinutes >= sleepMinutes || currentMinutes < wakeMinutes;
    } else {
      // Sleep period same day (e.g., 01:00 to 07:00)
      return currentMinutes >= sleepMinutes && currentMinutes < wakeMinutes;
    }
  }

  /**
   * Check sleep/wake conditions and act accordingly
   */
  _checkSleepWake() {
    const settings = this._getSettings();

    if (!settings.enabled) {
      return;
    }

    // Handle schedule-based sleep
    if (settings.method === 'schedule') {
      const isInSleepPeriod = this._isTimeInSleepPeriod();

      if (isInSleepPeriod && !this.isSleeping) {
        console.log(LOG_TAG, '🌙 Auto-sleep activated (scheduled)');
        this._sleep();
      } else if (!isInSleepPeriod && this.isSleeping) {
        console.log(LOG_TAG, '☀️ Auto-wake activated (scheduled)');
        this._wake();
      }
    }

    // Inactivity-based sleep is handled by the inactivity timer
  }

  /**
   * Set up user activity tracking for inactivity timeout
   */
  _setupActivityTracking() {
    // Track various user interactions
    const events = ['touchstart', 'mousedown', 'keydown', 'scroll'];

    events.forEach(event => {
      document.addEventListener(event, this._onUserActivity, { passive: true });
    });

    console.log(LOG_TAG, 'Activity tracking enabled');
  }

  /**
   * Handle user activity - reset inactivity timer
   */
  _onUserActivity() {
    this.lastActivityTime = Date.now();

    const settings = this._getSettings();

    // If using inactivity method and we're sleeping, wake up
    if (settings.enabled && settings.method === 'inactivity' && this.isSleeping) {
      console.log(LOG_TAG, '☀️ Wake on user activity');
      this._wake();
      // Start inactivity timer again
      this._startInactivityTimer();
      return;
    }

    // If using schedule method and we woke during sleep hours, reset re-sleep timer
    if (settings.enabled && settings.method === 'schedule' && !this.isSleeping) {
      if (this._isTimeInSleepPeriod() && this.resleepTimer) {
        // Reset re-sleep timer on activity
        this._startResleepTimer();
      }
    }

    // Reset inactivity timer if using inactivity method
    if (settings.enabled && settings.method === 'inactivity' && !this.isSleeping) {
      this._startInactivityTimer();
    }
  }

  /**
   * Start the inactivity timer
   */
  _startInactivityTimer() {
    const settings = this._getSettings();

    if (!settings.enabled || settings.method !== 'inactivity') {
      return;
    }

    // Clear existing timer
    this._cancelInactivityTimer();

    const delayMs = settings.inactivityTimeout * 60000;

    console.log(LOG_TAG, `Starting inactivity timer (${settings.inactivityTimeout} min)`);

    this.inactivityTimer = setTimeout(() => {
      const currentSettings = this._getSettings();
      if (currentSettings.enabled && currentSettings.method === 'inactivity' && !this.isSleeping) {
        console.log(LOG_TAG, '🌙 Inactivity timeout - going to sleep');
        this._sleep();
      }
    }, delayMs);
  }

  /**
   * Cancel the inactivity timer
   */
  _cancelInactivityTimer() {
    if (this.inactivityTimer) {
      clearTimeout(this.inactivityTimer);
      this.inactivityTimer = null;
    }
  }

  /**
   * Start the re-sleep timer (for schedule method, when user wakes during sleep hours)
   */
  _startResleepTimer() {
    const settings = this._getSettings();

    if (!settings.enabled || settings.method !== 'schedule') {
      return;
    }

    if (settings.resleepTimeout === 0) {
      console.log(LOG_TAG, 'Re-sleep disabled (timeout = 0)');
      return;
    }

    // Clear existing timer
    this._cancelResleepTimer();

    const delayMs = settings.resleepTimeout * 60000;

    console.log(LOG_TAG, `Starting re-sleep timer (${settings.resleepTimeout} min)`);

    this.resleepTimer = setTimeout(() => {
      const currentSettings = this._getSettings();
      if (currentSettings.enabled &&
          currentSettings.method === 'schedule' &&
          this._isTimeInSleepPeriod() &&
          !this.isSleeping) {
        console.log(LOG_TAG, '🌙 Re-sleep timer activated');
        this._sleep();
      }
    }, delayMs);
  }

  /**
   * Cancel the re-sleep timer
   */
  _cancelResleepTimer() {
    if (this.resleepTimer) {
      clearTimeout(this.resleepTimer);
      this.resleepTimer = null;
    }
  }

  /**
   * Enter sleep mode
   */
  _sleep() {
    if (this.isSleeping) {
      return;
    }

    console.log(LOG_TAG, '🌙 Entering sleep mode');
    this.isSleeping = true;

    // Cancel any pending timers
    this._cancelResleepTimer();
    this._cancelInactivityTimer();

    // Call Android bridge to turn off screen
    if (this.bridge?.sleepNow) {
      this.bridge.sleepNow();
    } else {
      console.warn(LOG_TAG, 'sleepNow() not available on bridge');
    }
  }

  /**
   * Exit sleep mode (wake up)
   */
  _wake() {
    if (!this.isSleeping) {
      return;
    }

    console.log(LOG_TAG, '☀️ Exiting sleep mode');
    this.isSleeping = false;

    // Call Android bridge to turn on screen
    if (this.bridge?.wakeNow) {
      this.bridge.wakeNow();
    } else {
      console.warn(LOG_TAG, 'wakeNow() not available on bridge');
    }

    // Check if we need to start re-sleep or inactivity timer
    const settings = this._getSettings();
    if (settings.enabled) {
      if (settings.method === 'schedule' && this._isTimeInSleepPeriod()) {
        // User woke during sleep hours - start re-sleep timer
        this._startResleepTimer();
      } else if (settings.method === 'inactivity') {
        // Start inactivity timer
        this._startInactivityTimer();
      }
    }
  }

  /**
   * Manual sleep trigger (from drawer button or API)
   */
  sleepNow() {
    console.log(LOG_TAG, '🌙 Manual sleep triggered');

    // Cancel any wake-related timers
    this._cancelResleepTimer();
    this._cancelInactivityTimer();

    this.isSleeping = true;

    // Call Android bridge
    if (this.bridge?.sleepNow) {
      this.bridge.sleepNow();
    }
  }

  /**
   * Manual wake trigger (from touch/motion or API)
   */
  wakeNow() {
    console.log(LOG_TAG, '☀️ Manual wake triggered');

    this._wake();
  }

  /**
   * Called by Android when the native screensaver deactivates.
   * This allows the JS sleep timer to know when the user woke the screen
   * via touch on the native screensaver (which doesn't trigger DOM events).
   */
  onNativeScreensaverDeactivated() {
    console.log(LOG_TAG, `onNativeScreensaverDeactivated called (isSleeping=${this.isSleeping})`);

    if (!this.isSleeping) {
      // Not in sleep mode, nothing to do
      return;
    }

    console.log(LOG_TAG, '☀️ Native screensaver deactivated - waking from sleep');
    this._wake();
  }

  /**
   * Handle settings change - restart timers as needed
   */
  onSettingsChanged() {
    console.log(LOG_TAG, 'Settings changed, rechecking state');

    const settings = this._getSettings();

    if (!settings.enabled) {
      // Sleep disabled - wake up if sleeping and cancel all timers
      if (this.isSleeping) {
        this._wake();
      }
      this._cancelResleepTimer();
      this._cancelInactivityTimer();
      return;
    }

    // Recheck current state
    this._checkSleepWake();

    // Start appropriate timer based on method
    if (settings.method === 'inactivity' && !this.isSleeping) {
      this._startInactivityTimer();
    }
  }

  /**
   * Cleanup
   */
  destroy() {
    console.log(LOG_TAG, 'Destroying sleep timer service');

    if (this.checkInterval) {
      clearInterval(this.checkInterval);
      this.checkInterval = null;
    }

    this._cancelResleepTimer();
    this._cancelInactivityTimer();

    // Remove activity tracking
    const events = ['touchstart', 'mousedown', 'keydown', 'scroll'];
    events.forEach(event => {
      document.removeEventListener(event, this._onUserActivity);
    });

    this.initialized = false;
  }

  /**
   * Get current state for debugging
   */
  getState() {
    const settings = this._getSettings();
    return {
      initialized: this.initialized,
      isSleeping: this.isSleeping,
      enabled: settings.enabled,
      method: settings.method,
      inSleepPeriod: this._isTimeInSleepPeriod(),
      hasResleepTimer: this.resleepTimer !== null,
      hasInactivityTimer: this.inactivityTimer !== null,
      lastActivityTime: new Date(this.lastActivityTime).toISOString()
    };
  }
}

// Create singleton instance
const kioskSleepTimerService = new KioskSleepTimerService();

// Expose globally for debugging
if (typeof window !== 'undefined') {
  window.kioskSleepTimerService = kioskSleepTimerService;
}

export default kioskSleepTimerService;
