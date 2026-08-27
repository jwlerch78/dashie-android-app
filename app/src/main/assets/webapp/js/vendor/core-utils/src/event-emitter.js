/**
 * @dashieapp/core-utils - EventEmitter
 *
 * Lightweight event emitter that works across all platforms.
 * Replaces AppComms for shared packages.
 */

export class EventEmitter {
  constructor() {
    this._listeners = new Map();
    this._onceListeners = new Map();
  }

  /**
   * Subscribe to an event
   * @param {string} event - Event name
   * @param {Function} callback - Handler function
   * @returns {Function} Unsubscribe function
   */
  on(event, callback) {
    if (!this._listeners.has(event)) {
      this._listeners.set(event, new Set());
    }
    this._listeners.get(event).add(callback);

    // Return unsubscribe function
    return () => this.off(event, callback);
  }

  /**
   * Subscribe to an event (fires once then unsubscribes)
   * @param {string} event - Event name
   * @param {Function} callback - Handler function
   * @returns {Function} Unsubscribe function
   */
  once(event, callback) {
    if (!this._onceListeners.has(event)) {
      this._onceListeners.set(event, new Set());
    }
    this._onceListeners.get(event).add(callback);

    return () => {
      const listeners = this._onceListeners.get(event);
      if (listeners) {
        listeners.delete(callback);
      }
    };
  }

  /**
   * Unsubscribe from an event
   * @param {string} event - Event name
   * @param {Function} callback - Handler function to remove
   */
  off(event, callback) {
    const listeners = this._listeners.get(event);
    if (listeners) {
      listeners.delete(callback);
    }

    const onceListeners = this._onceListeners.get(event);
    if (onceListeners) {
      onceListeners.delete(callback);
    }
  }

  /**
   * Emit an event to all subscribers
   * @param {string} event - Event name
   * @param {*} data - Data to pass to handlers
   */
  emit(event, data) {
    // Regular listeners
    const listeners = this._listeners.get(event);
    if (listeners) {
      for (const callback of listeners) {
        try {
          callback(data);
        } catch (error) {
          console.error(`[EventEmitter] Error in listener for "${event}":`, error);
        }
      }
    }

    // Once listeners (fire and remove)
    const onceListeners = this._onceListeners.get(event);
    if (onceListeners) {
      for (const callback of onceListeners) {
        try {
          callback(data);
        } catch (error) {
          console.error(`[EventEmitter] Error in once listener for "${event}":`, error);
        }
      }
      this._onceListeners.delete(event);
    }
  }

  /**
   * Remove all listeners for an event (or all events)
   * @param {string} [event] - Event name (optional, clears all if not provided)
   */
  removeAllListeners(event) {
    if (event) {
      this._listeners.delete(event);
      this._onceListeners.delete(event);
    } else {
      this._listeners.clear();
      this._onceListeners.clear();
    }
  }

  /**
   * Get count of listeners for an event
   * @param {string} event - Event name
   * @returns {number} Number of listeners
   */
  listenerCount(event) {
    const regular = this._listeners.get(event)?.size || 0;
    const once = this._onceListeners.get(event)?.size || 0;
    return regular + once;
  }
}

// Singleton instance for global events (optional use)
export const globalEvents = new EventEmitter();
