/**
 * Lightweight logger shim for kiosk bundle.
 * Replaces the main app's heavyweight logger (localStorage buffering, etc.)
 * with simple console forwarding.
 */
export function createLogger(name) {
  const prefix = `[${name}]`;
  return {
    debug: (...args) => console.debug(prefix, ...args),
    info: (...args) => console.info(prefix, ...args),
    warn: (...args) => console.warn(prefix, ...args),
    error: (...args) => console.error(prefix, ...args),
    verbose: () => {},
    success: (...args) => console.log(prefix, ...args),
  };
}
