/**
 * Music Player Service for Dashie Kiosk
 *
 * Manages Music Assistant integration via Home Assistant websocket.
 * Includes idle-bounce detection and retry logic for unreliable playback.
 *
 * Architecture:
 * - Subscribes to media_player entity state via HA websocket
 * - Forwards state updates to Kotlin native UI
 * - Handles playback commands with retry on failure
 *
 * @see .reference/build-plans/20260206_DASHIE_KIOSK_MUSIC_PLAYER.md
 */

// ==================== Configuration ====================

const DEFAULT_CONFIG = {
  // Retry settings for idle-bounce detection
  maxRetries: 2,
  retryDelayMs: 1500,
  idleBounceWindowMs: 4000,  // Consider it a bounce if idle within 4s of play command

  // State update settings
  positionUpdateIntervalMs: 1000,  // Update position every second when playing

  // Debug logging
  debug: true,  // Set to false to reduce log verbosity
};

// ==================== Logging ====================

const LOG_PREFIX = '[MusicPlayer]';
const LOG_BOUNCE = '[MusicPlayer:BOUNCE]';  // Special prefix for bounce detection logs

function log(...args) {
  console.log(LOG_PREFIX, ...args);
}

function logBounce(...args) {
  // Always log bounce detection regardless of debug flag - this is what we're testing
  console.log(LOG_BOUNCE, '🔄', ...args);
}

function logDebug(...args) {
  if (DEFAULT_CONFIG.debug) {
    console.log(LOG_PREFIX, '🔍', ...args);
  }
}

// ==================== Music Player Service ====================

class MusicPlayerService {
  constructor() {
    // Configuration
    this.config = { ...DEFAULT_CONFIG };
    this.entityId = null;
    this.haBaseUrl = null;

    // HA Websocket connection
    this.ws = null;
    this.wsConnected = false;
    this.messageId = 1;
    this.pendingRequests = new Map();

    // Current state
    this.currentState = 'idle';  // idle, playing, paused
    this.lastKnownTrack = null;
    this.lastKnownPosition = 0;
    this.lastPositionUpdate = null;

    // Idle-bounce detection
    this.playCommandSentAt = null;
    this.retryCount = 0;
    this.isRetrying = false;

    // Auth failure tracking - prevents reconnect loop with bad token (causes HA IP ban)
    this.authFailed = false;

    // Position tracking interval
    this.positionInterval = null;

    // Callbacks
    this.onStateChange = null;
    this.onError = null;
  }

  // ==================== Initialization ====================

  /**
   * Initialize the music player service
   * @param {Object} options - Configuration options
   * @param {string} options.entityId - The media_player entity ID to control
   * @param {string} options.haBaseUrl - Home Assistant base URL (e.g., http://homeassistant.local:8123)
   * @param {string} options.haToken - Long-lived access token for HA
   * @param {Function} options.onStateChange - Callback when player state changes
   * @param {Function} options.onError - Callback for errors
   */
  async initialize(options) {
    log('🎵 Initializing Music Player Service...');
    log('Config:', {
      entityId: options.entityId,
      haBaseUrl: options.haBaseUrl,
      hasToken: !!options.haToken,
      maxRetries: this.config.maxRetries,
      retryDelayMs: this.config.retryDelayMs,
      idleBounceWindowMs: this.config.idleBounceWindowMs
    });

    this.entityId = options.entityId;
    this.haBaseUrl = options.haBaseUrl?.replace(/\/$/, '');  // Remove trailing slash
    this.haToken = options.haToken;
    this.onStateChange = options.onStateChange;
    this.onError = options.onError;

    if (!this.entityId) {
      console.warn(LOG_PREFIX, '⚠️ No entity ID configured - service disabled');
      return false;
    }

    if (!this.haBaseUrl || !this.haToken) {
      console.warn(LOG_PREFIX, '⚠️ Missing HA connection details - service disabled');
      return false;
    }

    // Connect to HA websocket
    try {
      await this.connectWebSocket();
      log('✅ Music Player Service initialized successfully');
      return true;
    } catch (error) {
      console.error(LOG_PREFIX, '❌ Failed to connect:', error);
      this.onError?.({ type: 'connection_failed', error });
      return false;
    }
  }

  /**
   * Clean up resources
   */
  destroy() {
    log('🛑 Destroying Music Player Service...');
    this.stopPositionTracking();
    this.disconnectWebSocket();
  }

  // ==================== HA Websocket Connection ====================

  async connectWebSocket() {
    return new Promise((resolve, reject) => {
      const wsUrl = this.haBaseUrl.replace(/^http/, 'ws') + '/api/websocket';
      log('🔌 Connecting to HA WebSocket:', wsUrl);

      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        log('🔌 WebSocket connection opened');
      };

      this.ws.onclose = () => {
        log('🔌 WebSocket connection closed');
        this.wsConnected = false;
        this.stopPositionTracking();

        // Don't reconnect if auth failed - would just spam HA with bad tokens and cause IP ban
        if (this.authFailed) {
          log('🔌 Auth failed - NOT reconnecting (waiting for fresh token from Kotlin)');
          return;
        }

        // Attempt reconnect after delay
        log('🔌 Will attempt reconnect in 5 seconds...');
        setTimeout(() => {
          if (this.entityId) {
            this.connectWebSocket().catch(console.error);
          }
        }, 5000);
      };

      this.ws.onerror = (error) => {
        console.error(LOG_PREFIX, '❌ WebSocket error:', error);
        reject(error);
      };

      this.ws.onmessage = async (event) => {
        try {
          const message = JSON.parse(event.data);
          await this.handleWebSocketMessage(message, resolve, reject);
        } catch (error) {
          console.error(LOG_PREFIX, '❌ Failed to parse message:', error);
        }
      };
    });
  }

  disconnectWebSocket() {
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.wsConnected = false;
  }

  async handleWebSocketMessage(message, resolve, reject) {
    switch (message.type) {
      case 'auth_required':
        logDebug('Auth required, sending token...');
        this.ws.send(JSON.stringify({
          type: 'auth',
          access_token: this.haToken
        }));
        break;

      case 'auth_ok':
        log('🔐 Authenticated with Home Assistant');
        this.wsConnected = true;
        this.authFailed = false;  // Reset auth failure flag on successful auth
        await this.subscribeToEntity();
        resolve();
        break;

      case 'auth_invalid':
        console.error(LOG_PREFIX, '❌ Authentication failed - stopping reconnect to prevent HA IP ban');
        this.authFailed = true;  // Prevents onclose from reconnecting with same bad token
        reject(new Error('Authentication failed'));
        break;

      case 'event':
        if (message.event?.event_type === 'state_changed') {
          this.handleStateChange(message.event.data);
        }
        break;

      case 'result':
        // Handle pending request responses
        const pending = this.pendingRequests.get(message.id);
        if (pending) {
          this.pendingRequests.delete(message.id);
          if (message.success) {
            pending.resolve(message.result);
          } else {
            pending.reject(new Error(message.error?.message || 'Request failed'));
          }
        }
        break;
    }
  }

  async subscribeToEntity() {
    log(`📡 Subscribing to state changes for: ${this.entityId}`);

    const id = this.messageId++;

    this.ws.send(JSON.stringify({
      id,
      type: 'subscribe_events',
      event_type: 'state_changed'
    }));

    // Also fetch current state
    await this.fetchCurrentState();
    log('📡 Subscription active - listening for state changes');
  }

  async fetchCurrentState() {
    const id = this.messageId++;

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(id, { resolve, reject });

      this.ws.send(JSON.stringify({
        id,
        type: 'get_states'
      }));
    }).then(states => {
      const entity = states.find(s => s.entity_id === this.entityId);
      if (entity) {
        this.processStateUpdate(entity);
      }
    });
  }

  // ==================== State Management ====================

  handleStateChange(data) {
    if (data.entity_id !== this.entityId) return;

    const newState = data.new_state;
    if (!newState) return;

    this.processStateUpdate(newState);
  }

  processStateUpdate(entity) {
    const oldState = this.currentState;
    const newState = entity.state;
    const attrs = entity.attributes || {};

    log(`📻 State update received: ${oldState} → ${newState}`);
    logDebug('Entity attributes:', {
      title: attrs.media_title,
      artist: attrs.media_artist,
      position: attrs.media_position,
      duration: attrs.media_duration
    });

    // Update current state
    this.currentState = newState;

    // Store track info for potential restart
    if (newState === 'playing') {
      this.lastKnownTrack = {
        content_id: attrs.media_content_id,
        title: attrs.media_title,
        artist: attrs.media_artist,
        album: attrs.media_album_name,
        duration: attrs.media_duration
      };
      this.lastKnownPosition = attrs.media_position || 0;
      this.lastPositionUpdate = new Date(attrs.media_position_updated_at);

      log(`▶️ Now playing: "${attrs.media_title}" by ${attrs.media_artist}`);

      // Start position tracking
      this.startPositionTracking();
    } else {
      this.stopPositionTracking();
    }

    // Idle-bounce detection
    if (this.detectIdleBounce(oldState, newState)) {
      this.handleIdleBounce();
      return;  // Don't notify UI yet, we're retrying
    }

    // Clear retry state on successful play
    if (newState === 'playing' && this.isRetrying) {
      logBounce(`✅ Retry SUCCESSFUL after ${this.retryCount} attempt(s)!`);
      this.isRetrying = false;
      this.retryCount = 0;
      this.playCommandSentAt = null;
    }

    // Build state object for UI
    const playerState = {
      state: newState,
      trackName: attrs.media_title || '',
      artistName: attrs.media_artist || '',
      albumName: attrs.media_album_name || '',
      albumArtUrl: attrs.entity_picture ? `${this.haBaseUrl}${attrs.entity_picture}` : null,
      duration: attrs.media_duration || 0,
      position: this.calculateCurrentPosition(attrs),
      isPlaying: newState === 'playing',
      isPaused: newState === 'paused',
      volumeLevel: attrs.volume_level,
      isMuted: attrs.is_volume_muted
    };

    // Notify callback
    this.onStateChange?.(playerState);

    // Forward to Kotlin
    this.forwardToKotlin(playerState);
  }

  calculateCurrentPosition(attrs) {
    if (!attrs.media_position_updated_at) {
      return attrs.media_position || 0;
    }

    const positionUpdatedAt = new Date(attrs.media_position_updated_at);
    const basePosition = attrs.media_position || 0;

    if (this.currentState === 'playing') {
      const elapsed = (Date.now() - positionUpdatedAt.getTime()) / 1000;
      return Math.min(basePosition + elapsed, attrs.media_duration || Infinity);
    }

    return basePosition;
  }

  startPositionTracking() {
    this.stopPositionTracking();

    this.positionInterval = setInterval(() => {
      if (this.currentState === 'playing' && this.lastKnownTrack) {
        // Calculate interpolated position
        const elapsed = this.lastPositionUpdate
          ? (Date.now() - this.lastPositionUpdate.getTime()) / 1000
          : 0;
        const position = Math.min(
          this.lastKnownPosition + elapsed,
          this.lastKnownTrack.duration || Infinity
        );

        // Forward position update to Kotlin
        if (typeof DashieNative !== 'undefined' && DashieNative.onMusicPositionUpdate) {
          DashieNative.onMusicPositionUpdate(Math.floor(position));
        }
      }
    }, this.config.positionUpdateIntervalMs);
  }

  stopPositionTracking() {
    if (this.positionInterval) {
      clearInterval(this.positionInterval);
      this.positionInterval = null;
    }
  }

  forwardToKotlin(playerState) {
    if (typeof DashieNative !== 'undefined') {
      if (DashieNative.onMusicPlayerStateChange) {
        try {
          DashieNative.onMusicPlayerStateChange(JSON.stringify(playerState));
        } catch (e) {
          console.error('[MusicPlayer] Error forwarding to Kotlin:', e);
        }
      }
    }
  }

  // ==================== Idle-Bounce Detection & Retry ====================

  /**
   * Detect if we just experienced an idle-bounce:
   * We sent a play command, it briefly went to playing, then back to idle
   */
  detectIdleBounce(oldState, newState) {
    // Only check if we recently sent a play command
    if (!this.playCommandSentAt) {
      logDebug('No play command pending, skip bounce check');
      return false;
    }

    const timeSincePlayCommand = Date.now() - this.playCommandSentAt;

    logBounce(`State change: ${oldState} → ${newState} (${timeSincePlayCommand}ms since play command)`);

    // If we went from playing to idle within the bounce window
    if (oldState === 'playing' && newState === 'idle') {
      if (timeSincePlayCommand < this.config.idleBounceWindowMs) {
        logBounce(`🚨 BOUNCE DETECTED! playing→idle in ${timeSincePlayCommand}ms (window: ${this.config.idleBounceWindowMs}ms)`);
        return true;
      } else {
        logBounce(`Not a bounce - ${timeSincePlayCommand}ms exceeds window`);
      }
    }

    // Also detect: we sent play, but state immediately went back to idle
    if (newState === 'idle' && timeSincePlayCommand < this.config.idleBounceWindowMs) {
      // Check if we ever got to playing state
      if (this.retryCount === 0 || this.isRetrying) {
        logBounce(`🚨 BOUNCE DETECTED! Never reached stable playing state (retry=${this.retryCount})`);
        return true;
      }
    }

    return false;
  }

  /**
   * Handle an idle-bounce by retrying the play command
   */
  async handleIdleBounce() {
    if (this.retryCount >= this.config.maxRetries) {
      logBounce(`❌ Max retries (${this.config.maxRetries}) reached, giving up`);
      this.isRetrying = false;
      this.retryCount = 0;
      this.playCommandSentAt = null;

      // Notify of failure
      this.onError?.({
        type: 'playback_failed',
        message: 'Playback failed after retries',
        retriesAttempted: this.config.maxRetries
      });
      return;
    }

    this.isRetrying = true;
    this.retryCount++;

    logBounce(`⏳ Retry attempt ${this.retryCount}/${this.config.maxRetries} - waiting ${this.config.retryDelayMs}ms...`);

    // Wait before retrying
    await this.sleep(this.config.retryDelayMs);

    logBounce(`🔁 Sending retry play command (attempt ${this.retryCount})`);

    // Try play command again
    await this.sendPlayCommand();
  }

  // ==================== Playback Commands ====================

  /**
   * Play - with idle-bounce detection and retry
   */
  async play() {
    log('▶️ Play requested (current state:', this.currentState, ')');

    // Reset retry state for fresh attempt
    this.retryCount = 0;
    this.isRetrying = false;

    await this.sendPlayCommand();
  }

  /**
   * Send the actual play command to HA
   */
  async sendPlayCommand() {
    this.playCommandSentAt = Date.now();

    logBounce(`📤 Sending play command at ${new Date().toISOString()}`);

    try {
      await this.callService('media_player', 'media_play', {
        entity_id: this.entityId
      });
      logBounce('📤 Play command sent successfully - watching for state change...');
    } catch (error) {
      console.error(LOG_PREFIX, '❌ Play command failed:', error);
      this.onError?.({ type: 'command_failed', command: 'play', error });
    }
  }

  /**
   * Pause
   */
  async pause() {
    log('⏸️ Pause requested');
    this.playCommandSentAt = null;  // Clear bounce detection on explicit pause

    try {
      await this.callService('media_player', 'media_pause', {
        entity_id: this.entityId
      });
      log('⏸️ Pause command sent');
    } catch (error) {
      console.error(LOG_PREFIX, '❌ Pause command failed:', error);
      this.onError?.({ type: 'command_failed', command: 'pause', error });
    }
  }

  /**
   * Toggle play/pause
   */
  async playPause() {
    log(`🔀 PlayPause toggle (current: ${this.currentState})`);
    if (this.currentState === 'playing') {
      await this.pause();
    } else {
      await this.play();
    }
  }

  /**
   * Next track
   */
  async next() {
    console.log('[MusicPlayer] Next requested');
    try {
      await this.callService('media_player', 'media_next_track', {
        entity_id: this.entityId
      });
    } catch (error) {
      console.error('[MusicPlayer] Next command failed:', error);
    }
  }

  /**
   * Previous track
   */
  async previous() {
    console.log('[MusicPlayer] Previous requested');
    try {
      await this.callService('media_player', 'media_previous_track', {
        entity_id: this.entityId
      });
    } catch (error) {
      console.error('[MusicPlayer] Previous command failed:', error);
    }
  }

  /**
   * Set volume (0.0 - 1.0)
   */
  async setVolume(level) {
    console.log('[MusicPlayer] Volume set:', level);
    try {
      await this.callService('media_player', 'volume_set', {
        entity_id: this.entityId,
        volume_level: Math.max(0, Math.min(1, level))
      });
    } catch (error) {
      console.error('[MusicPlayer] Volume command failed:', error);
    }
  }

  // ==================== HA Service Calls ====================

  async callService(domain, service, data) {
    if (!this.wsConnected) {
      throw new Error('Not connected to Home Assistant');
    }

    const id = this.messageId++;

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(id, { resolve, reject });

      this.ws.send(JSON.stringify({
        id,
        type: 'call_service',
        domain,
        service,
        service_data: data,
        target: data.entity_id ? { entity_id: data.entity_id } : undefined
      }));

      // Timeout after 10 seconds
      setTimeout(() => {
        if (this.pendingRequests.has(id)) {
          this.pendingRequests.delete(id);
          reject(new Error('Request timeout'));
        }
      }, 10000);
    });
  }

  // ==================== Utilities ====================

  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  /**
   * Get current player state for external queries
   */
  getState() {
    return {
      state: this.currentState,
      track: this.lastKnownTrack,
      position: this.lastKnownPosition,
      entityId: this.entityId,
      connected: this.wsConnected
    };
  }

  /**
   * Update the HA token and reconnect if auth had previously failed.
   * Called by Kotlin bridge after token refresh to resume music player.
   * @param {string} newToken - Fresh HA access token
   */
  updateToken(newToken) {
    if (!newToken) return;
    log('🔑 Token updated' + (this.authFailed ? ' - reconnecting after auth failure' : ''));
    this.haToken = newToken;

    if (this.authFailed) {
      this.authFailed = false;
      if (this.entityId && this.haBaseUrl) {
        this.connectWebSocket().catch(console.error);
      }
    }
  }

  /**
   * Update configuration
   */
  configure(options) {
    if (options.maxRetries !== undefined) {
      this.config.maxRetries = options.maxRetries;
    }
    if (options.retryDelayMs !== undefined) {
      this.config.retryDelayMs = options.retryDelayMs;
    }
    if (options.idleBounceWindowMs !== undefined) {
      this.config.idleBounceWindowMs = options.idleBounceWindowMs;
    }
  }
}

// ==================== Singleton Instance ====================

const musicPlayerService = new MusicPlayerService();

// Expose to window for Kotlin bridge access
window.dashieMusicPlayer = {
  initialize: (options) => musicPlayerService.initialize(options),
  destroy: () => musicPlayerService.destroy(),
  play: () => musicPlayerService.play(),
  pause: () => musicPlayerService.pause(),
  playPause: () => musicPlayerService.playPause(),
  next: () => musicPlayerService.next(),
  previous: () => musicPlayerService.previous(),
  setVolume: (level) => musicPlayerService.setVolume(level),
  getState: () => musicPlayerService.getState(),
  configure: (options) => musicPlayerService.configure(options),
  updateToken: (token) => musicPlayerService.updateToken(token)
};

export { musicPlayerService };
export default musicPlayerService;
