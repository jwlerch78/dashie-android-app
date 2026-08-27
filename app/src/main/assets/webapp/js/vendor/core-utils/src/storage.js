/**
 * @dashieapp/core-utils - Storage Interfaces
 *
 * Platform-agnostic storage interfaces and implementations.
 * Allows timer service and other packages to persist data
 * without coupling to a specific storage mechanism.
 */

/**
 * Storage interface that all implementations must follow
 * @interface IStorage
 */

/**
 * In-memory storage (for testing or ephemeral use)
 */
export class MemoryStorage {
  constructor() {
    this._data = new Map();
  }

  getItem(key) {
    return this._data.get(key) ?? null;
  }

  setItem(key, value) {
    this._data.set(key, value);
  }

  removeItem(key) {
    this._data.delete(key);
  }

  clear() {
    this._data.clear();
  }

  get length() {
    return this._data.size;
  }

  key(index) {
    return Array.from(this._data.keys())[index] ?? null;
  }
}

/**
 * Browser localStorage wrapper
 * Only use in browser environments
 */
export class BrowserStorage {
  constructor(prefix = 'dashie_') {
    this._prefix = prefix;
    this._checkAvailability();
  }

  _checkAvailability() {
    if (typeof localStorage === 'undefined') {
      throw new Error('BrowserStorage requires localStorage to be available');
    }
  }

  _key(key) {
    return `${this._prefix}${key}`;
  }

  getItem(key) {
    return localStorage.getItem(this._key(key));
  }

  setItem(key, value) {
    localStorage.setItem(this._key(key), value);
  }

  removeItem(key) {
    localStorage.removeItem(this._key(key));
  }

  clear() {
    // Only clear items with our prefix
    const keysToRemove = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(this._prefix)) {
        keysToRemove.push(key);
      }
    }
    keysToRemove.forEach(key => localStorage.removeItem(key));
  }
}

/**
 * File-based storage for Node.js environments
 * Stores data as JSON files
 */
export class FileStorage {
  constructor(filePath, options = {}) {
    this._filePath = filePath;
    this._data = {};
    this._writeDelay = options.writeDelay ?? 100; // Debounce writes
    this._writeTimeout = null;
    this._fs = null;
    this._initialized = false;
  }

  async _ensureInitialized() {
    if (this._initialized) return;

    // Dynamic import for Node.js fs module (ESM compatible)
    const fs = await import('node:fs');
    this._fs = fs.default || fs;

    // Load existing data
    try {
      if (this._fs.existsSync(this._filePath)) {
        const content = this._fs.readFileSync(this._filePath, 'utf8');
        this._data = JSON.parse(content);
      }
    } catch (error) {
      console.warn(`[FileStorage] Could not load ${this._filePath}:`, error.message);
      this._data = {};
    }

    this._initialized = true;
  }

  _scheduleWrite() {
    if (this._writeTimeout) {
      clearTimeout(this._writeTimeout);
    }

    this._writeTimeout = setTimeout(() => {
      this._writeSync();
    }, this._writeDelay);
  }

  _writeSync() {
    if (!this._fs) return;

    try {
      const dir = this._filePath.substring(0, this._filePath.lastIndexOf('/'));
      if (dir && !this._fs.existsSync(dir)) {
        this._fs.mkdirSync(dir, { recursive: true });
      }

      this._fs.writeFileSync(
        this._filePath,
        JSON.stringify(this._data, null, 2),
        'utf8'
      );
    } catch (error) {
      console.error(`[FileStorage] Could not write ${this._filePath}:`, error.message);
    }
  }

  getItem(key) {
    return this._data[key] ?? null;
  }

  setItem(key, value) {
    this._data[key] = value;
    this._scheduleWrite();
  }

  removeItem(key) {
    delete this._data[key];
    this._scheduleWrite();
  }

  clear() {
    this._data = {};
    this._scheduleWrite();
  }

  // Async initialization (preferred for ESM)
  async init() {
    await this._ensureInitialized();
    return this;
  }

  // Sync initialization - for ESM, just marks as initialized
  // Actual file loading happens lazily or via async init()
  initSync() {
    if (this._initialized) return;

    // In ESM, we can't synchronously import fs
    // The data will be empty until setItem is called or init() is awaited
    // This is a fallback for compatibility - prefer using init() in ESM
    console.warn(`[FileStorage] initSync() in ESM mode - data won't load until init() is called or first setItem`);
    this._data = {};
    this._initialized = true;
  }
}

/**
 * Create appropriate storage for the current environment
 * @param {Object} options - Configuration options
 * @param {string} [options.type] - Force storage type ('memory'|'browser'|'file')
 * @param {string} [options.filePath] - File path for file storage
 * @param {string} [options.prefix] - Prefix for browser storage keys
 * @returns {IStorage} Storage instance
 */
export function createStorage(options = {}) {
  if (options.type === 'memory') {
    return new MemoryStorage();
  }

  if (options.type === 'file' && options.filePath) {
    const storage = new FileStorage(options.filePath);
    storage.initSync();
    return storage;
  }

  if (options.type === 'browser' || typeof localStorage !== 'undefined') {
    return new BrowserStorage(options.prefix);
  }

  // Default to memory storage
  return new MemoryStorage();
}
