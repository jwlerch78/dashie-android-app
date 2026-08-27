/**
 * @dashieapp/core-utils - Logger
 *
 * Platform-agnostic logging utility with configurable output.
 * Works in browser, Node.js, and embedded environments.
 */

const LOG_LEVELS = {
  verbose: 0,
  debug: 1,
  info: 2,
  success: 3,
  warn: 4,
  error: 5,
  silent: 6
};

// Default configuration
let globalConfig = {
  level: 'info',
  enabled: true,
  timestamps: false,
  output: null  // Custom output handler
};

/**
 * Configure global logger settings
 * @param {Object} config - Configuration options
 * @param {string} [config.level] - Minimum log level ('verbose'|'debug'|'info'|'success'|'warn'|'error'|'silent')
 * @param {boolean} [config.enabled] - Enable/disable all logging
 * @param {boolean} [config.timestamps] - Include timestamps in output
 * @param {Function} [config.output] - Custom output handler (receives {level, module, message, args})
 */
export function configureLogger(config) {
  globalConfig = { ...globalConfig, ...config };
}

/**
 * Create a logger instance for a specific module
 * @param {string} moduleName - Name of the module (shown in log output)
 * @returns {Object} Logger instance with log methods
 */
export function createLogger(moduleName) {
  const log = (level, message, ...args) => {
    if (!globalConfig.enabled) return;
    if (LOG_LEVELS[level] < LOG_LEVELS[globalConfig.level]) return;

    const prefix = globalConfig.timestamps
      ? `[${new Date().toISOString()}] [${moduleName}]`
      : `[${moduleName}]`;

    // Custom output handler
    if (globalConfig.output) {
      globalConfig.output({ level, module: moduleName, message, args, prefix });
      return;
    }

    // Default console output
    const formattedMessage = `${prefix} ${message}`;

    switch (level) {
      case 'verbose':
      case 'debug':
        console.debug(formattedMessage, ...args);
        break;
      case 'info':
        console.info(formattedMessage, ...args);
        break;
      case 'success':
        console.log(formattedMessage, ...args);
        break;
      case 'warn':
        console.warn(formattedMessage, ...args);
        break;
      case 'error':
        console.error(formattedMessage, ...args);
        break;
    }
  };

  return {
    verbose: (message, ...args) => log('verbose', message, ...args),
    debug: (message, ...args) => log('debug', message, ...args),
    info: (message, ...args) => log('info', message, ...args),
    success: (message, ...args) => log('success', message, ...args),
    warn: (message, ...args) => log('warn', message, ...args),
    error: (message, ...args) => log('error', message, ...args)
  };
}

export { LOG_LEVELS };
