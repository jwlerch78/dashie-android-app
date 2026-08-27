/**
 * @dashieapp/core-utils
 *
 * Core utilities shared across Dashie packages.
 * Platform-agnostic implementations for logging, events, and storage.
 */

// Logger
export { createLogger, configureLogger, LOG_LEVELS } from './logger.js';

// Event Emitter
export { EventEmitter, globalEvents } from './event-emitter.js';

// Storage
export {
  MemoryStorage,
  BrowserStorage,
  FileStorage,
  createStorage
} from './storage.js';

// Text utilities (voice/NLP)
export { normalizeWordNumbers } from './text-utils.js';
