/**
 * @dashieapp/timer-service
 *
 * Timer service for Dashie ecosystem.
 * Works across browser, Node.js, and embedded environments.
 */

export { TimerService } from './timer-service.js';

// Re-export storage utilities for convenience
export {
  MemoryStorage,
  BrowserStorage,
  FileStorage,
  createStorage
} from '@dashieapp/core-utils';
