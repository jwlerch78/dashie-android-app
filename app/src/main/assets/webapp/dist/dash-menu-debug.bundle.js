var __defProp = Object.defineProperty;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __esm = (fn, res) => function __init() {
  return fn && (res = (0, fn[__getOwnPropNames(fn)[0]])(fn = 0)), res;
};
var __commonJS = (cb, mod) => function __require() {
  return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
};
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};

// js/dash-menu/dash-menu-items.js
var MENU_ITEMS, POPOUT_ICONS;
var init_dash_menu_items = __esm({
  "js/dash-menu/dash-menu-items.js"() {
    MENU_ITEMS = [
      { id: "ha-dashboard", label: "Home Assistant", iconPath: "artwork/icon-homeassistant.svg", type: "view" },
      { id: "music", label: "Music", iconPath: "artwork/icon-box-speaker.svg", type: "control" },
      { id: "volume", label: "Volume", iconPath: "artwork/icon-volume-on.svg", type: "control", iconPathMuted: "artwork/icon-volume-mute.svg" },
      { id: "brightness", label: "Brightness", iconPath: "artwork/icon-sun.svg", type: "control" },
      { id: "hamburger", label: "Menu", iconPath: "artwork/icon-hamburger.svg", type: "control", lockAllowed: true }
    ];
    POPOUT_ICONS = {
      unlock: "artwork/icon-lock.svg",
      settings: "artwork/icon-settings.svg",
      sleep: "artwork/icon-sleep.svg",
      reload: "artwork/icon-reload.svg"
    };
  }
});

// js/dash-menu/dash-menu-renderer.js
var STRIP_WIDTH, POPOUT_GAP, DashMenuRenderer;
var init_dash_menu_renderer = __esm({
  "js/dash-menu/dash-menu-renderer.js"() {
    init_dash_menu_items();
    STRIP_WIDTH = 48;
    POPOUT_GAP = 8;
    DashMenuRenderer = class {
      constructor() {
        this.container = null;
        this.popoutContainer = null;
        this.backdrop = null;
        this.activePopoutId = null;
        this._activePopout = null;
        this.onItemClick = null;
        this.onPopoutDismiss = null;
      }
      /**
       * Build the sidebar strip and append to document.body.
       * Structure mirrors dom-builder.js createSidebar() / createControlStrip().
       */
      buildStrip() {
        this.container = document.createElement("aside");
        this.container.className = "dashboard-sidebar";
        const viewSection = document.createElement("div");
        viewSection.className = "dashboard-view-switcher-container";
        const controlSection = document.createElement("div");
        controlSection.className = "sidebar-control-strip";
        for (const item of MENU_ITEMS) {
          const btn = document.createElement("button");
          btn.className = "dashboard-menu__item";
          btn.dataset.id = item.id;
          btn.title = item.label;
          if (item.type === "view") {
            btn.classList.add("dashboard-menu__item--view");
          } else {
            btn.classList.add("dashboard-menu__item--system");
            btn.dataset.systemId = item.id;
          }
          const iconEl = document.createElement("img");
          iconEl.className = "dashboard-menu__icon";
          iconEl.src = item.iconPath;
          iconEl.alt = item.label;
          btn.appendChild(iconEl);
          if (item.type === "view") {
            btn.classList.add("dashboard-menu__item--active");
            viewSection.appendChild(btn);
          } else {
            controlSection.appendChild(btn);
          }
          btn.addEventListener("click", () => {
            this.onItemClick?.(item.id);
          });
        }
        this.container.appendChild(viewSection);
        this.container.appendChild(controlSection);
        this.popoutContainer = document.createElement("div");
        this.popoutContainer.className = "dm-popout-container";
        this.backdrop = document.createElement("div");
        this.backdrop.className = "dm-backdrop";
        this.backdrop.addEventListener("click", () => {
          this.hidePopout();
          this.onPopoutDismiss?.();
        });
        document.body.appendChild(this.backdrop);
        document.body.appendChild(this.container);
        document.body.appendChild(this.popoutContainer);
      }
      /**
       * Show a popout next to the anchor item.
       * @param {string} itemId - the menu item that triggered this popout
       * @param {{ element: HTMLElement, popoutWidth: number, destroy: Function }} popout
       */
      showPopout(itemId, popout) {
        this.hidePopout();
        this.activePopoutId = itemId;
        this._activePopout = popout;
        const anchor = this.container.querySelector(`[data-id="${itemId}"]`);
        if (anchor) {
          const rect = anchor.getBoundingClientRect();
          const popoutHeight = popout.element.offsetHeight || 80;
          let top = rect.top + rect.height / 2 - popoutHeight / 2;
          if (top < 8) top = 8;
          if (top + popoutHeight > window.innerHeight - 8) {
            top = window.innerHeight - popoutHeight - 8;
          }
          this.popoutContainer.style.top = `${top}px`;
        }
        this.popoutContainer.appendChild(popout.element);
        this.popoutContainer.classList.add("dm-popout-container--visible");
        this.backdrop.classList.add("dm-backdrop--visible");
        const totalWidth = STRIP_WIDTH + POPOUT_GAP + popout.popoutWidth;
        window.parent.postMessage({
          source: "dash-menu",
          type: "resize",
          width: totalWidth
        }, "*");
      }
      /**
       * Hide the current popout.
       */
      hidePopout() {
        if (!this._activePopout) return;
        this._activePopout.destroy?.();
        this.popoutContainer.innerHTML = "";
        this.popoutContainer.classList.remove("dm-popout-container--visible");
        this.backdrop.classList.remove("dm-backdrop--visible");
        this.activePopoutId = null;
        this._activePopout = null;
        window.parent.postMessage({
          source: "dash-menu",
          type: "resize",
          width: STRIP_WIDTH
        }, "*");
      }
      /**
       * Toggle lock visual state on all items.
       */
      setLockState(isLocked) {
        const items = this.container.querySelectorAll(".dashboard-menu__item");
        for (const item of items) {
          const config = MENU_ITEMS.find((m) => m.id === item.dataset.id);
          if (config?.lockAllowed) {
            item.classList.remove("dashboard-menu__item--disabled");
          } else {
            item.classList.toggle("dashboard-menu__item--disabled", isLocked);
          }
        }
      }
      /**
       * Update volume icon to reflect mute state.
       * Uses <img> src swap (same pattern as dom-builder.js).
       */
      updateVolumeIcon(isMuted2) {
        const volumeItem = MENU_ITEMS.find((m) => m.id === "volume");
        const btn = this.container?.querySelector('[data-id="volume"]');
        if (btn && volumeItem) {
          const iconEl = btn.querySelector(".dashboard-menu__icon");
          if (iconEl) {
            iconEl.src = isMuted2 ? volumeItem.iconPathMuted : volumeItem.iconPath;
          }
          btn.dataset.audioMuted = isMuted2 ? "true" : "false";
        }
      }
    };
  }
});

// js/shims/logger-shim.js
function createLogger(name) {
  const prefix = `[${name}]`;
  return {
    debug: (...args) => console.debug(prefix, ...args),
    info: (...args) => console.info(prefix, ...args),
    warn: (...args) => console.warn(prefix, ...args),
    error: (...args) => console.error(prefix, ...args),
    verbose: () => {
    },
    success: (...args) => console.log(prefix, ...args)
  };
}
var init_logger_shim = __esm({
  "js/shims/logger-shim.js"() {
  }
});

// ../js/utils/logger-config.js
var LoggerConfig;
var init_logger_config = __esm({
  "../js/utils/logger-config.js"() {
    LoggerConfig = {
      // Debug mode - controlled via localStorage
      // Set to false for production, true for development
      get enableDebugLogs() {
        return localStorage.getItem("dashie-debug") === "true";
      },
      // Verbose mode - show granular initialization details
      get enableVerboseLogs() {
        return localStorage.getItem("dashie-verbose") === "true";
      },
      /**
       * Enable debug logging
       * All logger.debug() calls will be visible after page reload
       */
      enableDebug() {
        localStorage.setItem("dashie-debug", "true");
        console.log("\u{1F41B} Debug logging ENABLED - reload page to see all debug logs");
      },
      /**
       * Disable debug logging (production mode)
       * Only INFO, SUCCESS, WARN, and ERROR logs will be visible after reload
       */
      disableDebug() {
        localStorage.removeItem("dashie-debug");
        console.log("\u{1F41B} Debug logging DISABLED - reload page for clean logs");
      },
      /**
       * Enable verbose logging
       * Shows granular initialization details (logger.verbose() calls)
       */
      enableVerbose() {
        localStorage.setItem("dashie-verbose", "true");
        console.log("\u{1F4CB} Verbose logging ENABLED - reload to see granular init details");
      },
      /**
       * Disable verbose logging
       * Hides granular initialization details for cleaner logs
       */
      disableVerbose() {
        localStorage.removeItem("dashie-verbose");
        console.log("\u{1F4CB} Verbose logging DISABLED - reload for clean logs");
      },
      /**
       * Check current debug and verbose status
       */
      getStatus() {
        const debug = this.enableDebugLogs;
        const verbose = this.enableVerboseLogs;
        console.log(`\u{1F41B} Debug mode: ${debug ? "ON \u2705" : "OFF \u274C"}`);
        console.log(`\u{1F4CB} Verbose mode: ${verbose ? "ON \u2705" : "OFF \u274C"}`);
        if (!debug && !verbose) {
          console.log(`   Only INFO/SUCCESS/WARN/ERROR visible`);
        }
        return { debug, verbose };
      }
    };
    if (typeof window !== "undefined") {
      window.dashieDebug = {
        enable: () => LoggerConfig.enableDebug(),
        disable: () => LoggerConfig.disableDebug(),
        verboseOn: () => LoggerConfig.enableVerbose(),
        verboseOff: () => LoggerConfig.disableVerbose(),
        status: () => LoggerConfig.getStatus(),
        help: () => {
          console.log(`
\u{1F41B} Dashie Debug Controls
\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501
  dashieDebug.enable()     - Turn on debug logs (all logs)
  dashieDebug.disable()    - Turn off debug logs
  dashieDebug.verboseOn()  - Turn on verbose logs (init details)
  dashieDebug.verboseOff() - Turn off verbose logs (clean init)
  dashieDebug.status()     - Check current mode

After enabling/disabling, reload the page to see changes.

\u{1F4A1} Recommended: Keep verbose OFF for clean logs showing only major milestones.
      `);
        }
      };
      if (LoggerConfig.enableDebugLogs) {
        console.log("\u{1F41B} Debug mode is ENABLED - use dashieDebug.disable() to reduce log noise");
      }
      if (LoggerConfig.enableVerboseLogs) {
        console.log("\u{1F4CB} Verbose mode is ENABLED - use dashieDebug.verboseOff() for cleaner init logs");
      }
    }
  }
});

// ../js/utils/logger.js
function getTimezoneFromZipPrefix(zipCode) {
  if (!zipCode || typeof zipCode !== "string") return void 0;
  const firstDigit = zipCode.charAt(0);
  return ZIP_TIMEZONE_MAP[firstDigit];
}
function addToLogBuffer(entry) {
  logBuffer.push(entry);
  if (logBuffer.length > MAX_LOG_BUFFER_SIZE) {
    logBuffer = logBuffer.slice(-MAX_LOG_BUFFER_SIZE);
  }
  if (bufferSaveTimeout) {
    clearTimeout(bufferSaveTimeout);
  }
  bufferSaveTimeout = setTimeout(() => {
    saveLogBuffer();
    bufferSaveTimeout = null;
  }, BUFFER_SAVE_DELAY);
}
function saveLogBuffer() {
  try {
    localStorage.setItem(LOG_BUFFER_STORAGE_KEY, JSON.stringify(logBuffer));
  } catch (error) {
    if (error.name === "QuotaExceededError") {
      logBuffer = logBuffer.slice(-100);
      try {
        localStorage.setItem(LOG_BUFFER_STORAGE_KEY, JSON.stringify(logBuffer));
        return;
      } catch (e) {
        logBuffer = logBuffer.slice(-50);
        try {
          localStorage.setItem(LOG_BUFFER_STORAGE_KEY, JSON.stringify(logBuffer));
          return;
        } catch (e2) {
          console.warn("Unable to save log buffer - localStorage quota exceeded. Buffer will only persist in memory.");
        }
      }
    }
  }
}
function loadLogBuffer() {
  try {
    const saved = localStorage.getItem(LOG_BUFFER_STORAGE_KEY);
    if (saved) {
      logBuffer = JSON.parse(saved);
    }
  } catch (error) {
    console.warn("Failed to load log buffer from localStorage:", error);
    logBuffer = [];
  }
}
function flushLogBuffer() {
  if (bufferSaveTimeout) {
    clearTimeout(bufferSaveTimeout);
    bufferSaveTimeout = null;
  }
  saveLogBuffer();
}
function loadLogConfig() {
  try {
    const saved = localStorage.getItem(LOG_CONFIG_STORAGE_KEY);
    if (saved) {
      const parsed = JSON.parse(saved);
      GLOBAL_LOG_CONFIG = { ...GLOBAL_LOG_CONFIG, ...parsed };
    }
    const logLevel = localStorage.getItem("dashie-log-level");
    if (logLevel) {
      const level = LOG_LEVELS[logLevel.toUpperCase()];
      if (level !== void 0) {
        GLOBAL_LOG_CONFIG.level = level;
      }
    }
  } catch (error) {
    console.warn("Failed to load log config from localStorage:", error);
  }
}
function createLogger2(moduleName, moduleConfig = {}) {
  return new Logger(moduleName, moduleConfig);
}
var ZIP_TIMEZONE_MAP, LOG_CONFIG_STORAGE_KEY, LOG_BUFFER_STORAGE_KEY, MAX_LOG_BUFFER_SIZE, logBuffer, bufferSaveTimeout, BUFFER_SAVE_DELAY, LOG_LEVELS, GLOBAL_LOG_CONFIG, Logger;
var init_logger = __esm({
  "../js/utils/logger.js"() {
    init_logger_config();
    ZIP_TIMEZONE_MAP = {
      // Eastern Time
      "0": "America/New_York",
      "1": "America/New_York",
      "2": "America/New_York",
      "3": "America/New_York",
      "4": "America/New_York",
      // Central Time
      "5": "America/Chicago",
      "6": "America/Chicago",
      "7": "America/Chicago",
      // Mountain Time
      "8": "America/Denver",
      // Pacific Time
      "9": "America/Los_Angeles"
    };
    LOG_CONFIG_STORAGE_KEY = "dashie-log-config";
    LOG_BUFFER_STORAGE_KEY = "dashie-log-buffer";
    MAX_LOG_BUFFER_SIZE = 200;
    logBuffer = [];
    bufferSaveTimeout = null;
    BUFFER_SAVE_DELAY = 2e3;
    LOG_LEVELS = {
      DEBUG: 0,
      VERBOSE: 0.5,
      // Granular initialization details (hidden by default)
      INFO: 1,
      WARN: 2,
      ERROR: 3,
      SILENT: 4
    };
    GLOBAL_LOG_CONFIG = {
      level: LOG_LEVELS.INFO,
      enableTimestamps: true,
      enableModuleNames: true,
      enableColors: true,
      enableApiLogging: false,
      enableAuthLogging: true,
      enableDataLogging: true,
      enableWidgetLogging: true,
      enableBuffering: false,
      // DISABLED by default - enable manually if needed
      // Modules to mute completely (no logs at any level)
      mutedModules: ["PhotosWidget", "EventProcessor", "ThemeOverlay", "ThemeOverlayContainer", "ThemeOverlayVisibility", "ThemeOverlayRegistry", "ThemeOverlayElement"]
    };
    Logger = class {
      constructor(moduleName, moduleConfig = {}) {
        this.moduleName = moduleName;
        this.moduleConfig = moduleConfig;
        this.isApiModule = moduleName.toLowerCase().includes("api");
        this.isAuthModule = moduleName.toLowerCase().includes("auth");
        this.isDataModule = moduleName.toLowerCase().includes("data") || moduleName.toLowerCase().includes("cache");
        this.isWidgetModule = moduleName.toLowerCase().includes("widget");
      }
      /**
       * Check if a log level should be output based on global and module settings
       * @param {number} level - Log level to check
       * @returns {boolean} Whether to log this level
       */
      shouldLog(level) {
        if (GLOBAL_LOG_CONFIG.mutedModules?.includes(this.moduleName)) {
          return false;
        }
        if (level < GLOBAL_LOG_CONFIG.level) {
          return false;
        }
        if (this.isApiModule && !GLOBAL_LOG_CONFIG.enableApiLogging) {
          return false;
        }
        if (this.isAuthModule && !GLOBAL_LOG_CONFIG.enableAuthLogging) {
          return false;
        }
        if (this.isDataModule && !GLOBAL_LOG_CONFIG.enableDataLogging) {
          return false;
        }
        if (this.isWidgetModule && !GLOBAL_LOG_CONFIG.enableWidgetLogging) {
          return false;
        }
        return true;
      }
      /**
       * Format log message with timestamps and module information
       * @param {number} level - Log level
       * @param {string} message - Log message
       * @param {any} data - Additional data to log
       * @returns {Array} Formatted arguments for console methods
       */
      formatMessage(level, message, data = null) {
        const parts = [];
        let now;
        try {
          const timeService = window.timeService || window.parent?.timeService;
          if (timeService && typeof timeService.getNow === "function") {
            now = timeService.getNow();
          } else {
            now = /* @__PURE__ */ new Date();
          }
        } catch (e) {
          now = /* @__PURE__ */ new Date();
        }
        const timestamp = now.toISOString();
        if (GLOBAL_LOG_CONFIG.enableTimestamps) {
          let timezone;
          try {
            const zipCode = window.settingsStore?.get("family.zipCode") || window.parent?.settingsStore?.get("family.zipCode");
            if (zipCode) {
              timezone = getTimezoneFromZipPrefix(zipCode);
            }
          } catch (e) {
          }
          const formatter = new Intl.DateTimeFormat("en-US", {
            hour: "numeric",
            minute: "2-digit",
            second: "2-digit",
            timeZone: timezone
          });
          const timeOnly = formatter.format(now);
          parts.push(`[${timeOnly}]`);
        }
        if (GLOBAL_LOG_CONFIG.enableModuleNames) {
          parts.push(`[${this.moduleName}]`);
        }
        parts.push(message);
        if (GLOBAL_LOG_CONFIG.enableBuffering) {
          const levelNames = ["DEBUG", "INFO", "WARN", "ERROR", "SILENT"];
          const levelName = levelNames[level] || "UNKNOWN";
          let dataToStore = void 0;
          if (data !== null && data !== void 0) {
            try {
              const dataStr = JSON.stringify(data);
              if (dataStr.length < 50) {
                dataToStore = data;
              } else {
                dataToStore = `[Large object: ${dataStr.length} chars]`;
              }
            } catch (e) {
              dataToStore = "[Unstringifiable data]";
            }
          }
          addToLogBuffer({
            timestamp,
            level: levelName,
            module: this.moduleName,
            message,
            data: dataToStore
          });
        }
        const formattedMessage = parts.join(" ");
        if (data !== null && data !== void 0) {
          return [formattedMessage, data];
        } else {
          return [formattedMessage];
        }
      }
      /**
       * Debug level logging - for detailed development information
       * Shows if either: 1) log level is DEBUG, OR 2) dashieDebug.enable() was called
       * @param {string} message - Log message
       * @param {any} data - Optional additional data
       */
      debug(message, data = null) {
        const debugEnabledViaConfig = LoggerConfig.enableDebugLogs;
        const debugEnabledViaLevel = this.shouldLog(LOG_LEVELS.DEBUG);
        if (!debugEnabledViaConfig && !debugEnabledViaLevel) {
          return;
        }
        const args = this.formatMessage(LOG_LEVELS.DEBUG, `\u{1F50D} ${message}`, data);
        console.log(...args);
      }
      /**
       * Verbose level logging - for granular initialization details
       * Hidden by default unless verbose mode is enabled
       * @param {string} message - Log message
       * @param {any} data - Optional additional data
       */
      verbose(message, data = null) {
        if (!LoggerConfig.enableVerboseLogs && !LoggerConfig.enableDebugLogs) {
          return;
        }
        if (!this.shouldLog(LOG_LEVELS.VERBOSE)) return;
        const args = this.formatMessage(LOG_LEVELS.VERBOSE, `\u{1F4CB} ${message}`, data);
        console.log(...args);
      }
      /**
       * Info level logging - for general application flow
       * @param {string} message - Log message
       * @param {any} data - Optional additional data
       */
      info(message, data = null) {
        if (!this.shouldLog(LOG_LEVELS.INFO)) return;
        const args = this.formatMessage(LOG_LEVELS.INFO, `\u2139\uFE0F ${message}`, data);
        console.log(...args);
      }
      /**
       * Warning level logging - for potential issues
       * @param {string} message - Log message
       * @param {any} data - Optional additional data
       */
      warn(message, data = null) {
        if (!this.shouldLog(LOG_LEVELS.WARN)) return;
        const args = this.formatMessage(LOG_LEVELS.WARN, `\u26A0\uFE0F ${message}`, data);
        console.warn(...args);
      }
      /**
       * Error level logging - for errors and exceptions
       * @param {string} message - Log message
       * @param {Error|any} error - Error object or additional data
       */
      error(message, error = null) {
        if (!this.shouldLog(LOG_LEVELS.ERROR)) return;
        const args = this.formatMessage(LOG_LEVELS.ERROR, `\u274C ${message}`, error);
        console.error(...args);
      }
      /**
       * Success logging - for positive outcomes
       * @param {string} message - Log message
       * @param {any} data - Optional additional data
       */
      success(message, data = null) {
        if (!this.shouldLog(LOG_LEVELS.INFO)) return;
        const args = this.formatMessage(LOG_LEVELS.INFO, `\u2705 ${message}`, data);
        console.log(...args);
      }
      /**
       * API-specific logging with request/response details
       * @param {string} method - HTTP method
       * @param {string} endpoint - API endpoint
       * @param {number} status - Response status
       * @param {number} duration - Request duration in ms
       */
      apiCall(method, endpoint, status, duration) {
        if (!this.shouldLog(LOG_LEVELS.INFO) || !GLOBAL_LOG_CONFIG.enableApiLogging) return;
        const statusIcon = status >= 200 && status < 300 ? "\u2705" : "\u274C";
        const message = `${statusIcon} API ${method} ${endpoint} - ${status} (${duration}ms)`;
        const args = this.formatMessage(LOG_LEVELS.INFO, message);
        console.log(...args);
      }
      /**
       * Authentication flow logging
       * @param {string} flow - Auth flow name (web, native, device, etc.)
       * @param {string} stage - Stage of authentication
       * @param {string} status - Status (success, error, pending)
       * @param {any} details - Additional details
       */
      auth(flow, stage, status, details = null) {
        if (!this.shouldLog(LOG_LEVELS.INFO) || !GLOBAL_LOG_CONFIG.enableAuthLogging) return;
        const statusIcon = status === "success" ? "\u2705" : status === "error" ? "\u274C" : status === "pending" ? "\u23F3" : "\u2139\uFE0F";
        const message = `${statusIcon} Auth [${flow}] ${stage}: ${status}`;
        const args = this.formatMessage(LOG_LEVELS.INFO, message, details);
        console.log(...args);
      }
      /**
       * Data operation logging
       * @param {string} operation - Operation type (fetch, cache, refresh)
       * @param {string} dataType - Type of data (calendar, photos, etc.)
       * @param {string} status - Operation status
       * @param {any} details - Additional details (count, duration, etc.)
       */
      data(operation, dataType, status, details = null) {
        if (!this.shouldLog(LOG_LEVELS.INFO) || !GLOBAL_LOG_CONFIG.enableDataLogging) return;
        const statusIcon = status === "success" ? "\u2705" : status === "error" ? "\u274C" : status === "cached" ? "\u{1F4BE}" : "\u2139\uFE0F";
        const message = `${statusIcon} Data [${dataType}] ${operation}: ${status}`;
        const args = this.formatMessage(LOG_LEVELS.INFO, message, details);
        console.log(...args);
      }
      /**
       * Widget communication logging
       * @param {string} direction - 'send' or 'receive'
       * @param {string} messageType - Type of message
       * @param {string} widgetName - Name or source of widget
       * @param {any} details - Message details
       */
      widget(direction, messageType, widgetName, details = null) {
        if (!this.shouldLog(LOG_LEVELS.INFO) || !GLOBAL_LOG_CONFIG.enableWidgetLogging) return;
        const directionIcon = direction === "send" ? "\u{1F4E4}" : "\u{1F4E5}";
        const message = `${directionIcon} Widget [${widgetName}] ${messageType}`;
        const args = this.formatMessage(LOG_LEVELS.INFO, message, details);
        console.log(...args);
      }
      /**
       * Performance timing logging
       * @param {string} operation - Operation being timed
       * @param {number} duration - Duration in milliseconds
       * @param {any} metadata - Additional metadata about the operation
       */
      perf(operation, duration, metadata = null) {
        if (!this.shouldLog(LOG_LEVELS.DEBUG)) return;
        const message = `\u23F1\uFE0F Performance [${operation}]: ${duration}ms`;
        const args = this.formatMessage(LOG_LEVELS.DEBUG, message, metadata);
        console.log(...args);
      }
      /**
       * Create a performance timer
       * @param {string} operation - Operation name
       * @returns {Function} Function to call when operation completes
       */
      startTimer(operation) {
        const startTime = performance.now();
        return (metadata = null) => {
          const duration = Math.round(performance.now() - startTime);
          this.perf(operation, duration, metadata);
          return duration;
        };
      }
    };
    loadLogConfig();
    if (GLOBAL_LOG_CONFIG.enableBuffering) {
      loadLogBuffer();
    }
    if (typeof window !== "undefined") {
      window.addEventListener("beforeunload", () => {
        flushLogBuffer();
      });
    }
  }
});

// ../js/utils/android-bridge.js
var logger, AndroidBridge, isAvailable, getDeviceId, getDeviceInfo, getDeviceType, getAppVersion, getAppVersionInfo, hasMicrophonePermission, speak, speakWithParams, stopSpeaking, isSpeaking, startListening, stopListening, cancelListening, isListening, startCloudSTTCapture, stopCloudSTTCapture, startManualRecording, setStreamingSTT, isStreamingSTT, setSilenceThreshold, getSilenceThreshold, startWakeWordDetection, stopWakeWordDetection, isWakeWordActive, setWakeWordThreshold, getWakeWordThreshold, setWakeWordConfig, getWakeWordConfig, enableDiagnosticToasts, disableDiagnosticToasts, isDiagnosticToastsEnabled, recordWakeWordSample, getWakeWordSampleStats, getVolume, setVolume, volumeUp, volumeDown, isMuted, mute, unmute, toggleMute, getVolumeInfo, onVolumeChange, getBrightness, setBrightness, brightnessUp, brightnessDown, getBrightnessInfo, setAutoBrightness, canWriteSettings, requestWriteSettingsPermission, getDeviceTimezone, openSystemSettings, openDateTimeSettings, openLocationSettings, isLocationEnabled, openAppSettings, hasLocationPermission, isConnectedToInternet, getWifiStatus, openWifiSetup, openWifiSettings, exitApp, restartApp, softRestartApp, clearCache, logDeviceInfo, getDeviceSummary, getVolumeIcon;
var init_android_bridge = __esm({
  "../js/utils/android-bridge.js"() {
    init_logger();
    logger = createLogger2("AndroidBridge");
    AndroidBridge = {
      // ========================================
      // Availability Check
      // ========================================
      /**
       * Check if running in Android WebView with DashieNative bridge
       * @returns {boolean} True if DashieNative is available
       */
      isAvailable() {
        return typeof window.DashieNative !== "undefined";
      },
      /**
       * Get the raw DashieNative interface (for advanced usage)
       * @returns {object|null} DashieNative object or null
       */
      getNative() {
        return this.isAvailable() ? window.DashieNative : null;
      },
      // ========================================
      // 1. App Lifecycle & Control
      // ========================================
      /**
       * Exit the app (calls finishAffinity)
       */
      exitApp() {
        if (!this.isAvailable()) return;
        logger.info("Exiting app");
        window.DashieNative.exitApp();
      },
      /**
       * Hard restart - Full process kill & relaunch
       * Note: May not work with debugger attached
       */
      restartApp() {
        if (!this.isAvailable()) return;
        logger.info("Hard restart requested");
        window.DashieNative.restartApp();
      },
      /**
       * Soft restart - Recreates activity without killing process
       * Works even with debugger attached
       */
      softRestartApp() {
        if (!this.isAvailable()) return;
        logger.info("Soft restart requested");
        window.DashieNative.softRestartApp();
      },
      /**
       * Clear WebView cache (HTML, CSS, JS, images)
       */
      clearCache() {
        if (!this.isAvailable()) return;
        logger.info("Clearing WebView cache");
        window.DashieNative.clearCache();
      },
      // ========================================
      // 2. Device Information
      // ========================================
      /**
       * Get device type
       * @returns {string|null} "tv" or "mobile", null if not on Android
       */
      getDeviceType() {
        if (!this.isAvailable()) return null;
        return window.DashieNative.getDeviceType();
      },
      /**
       * Get simple app version string
       * @returns {string|null} Version like "2.3", null if not on Android
       */
      getAppVersion() {
        if (!this.isAvailable()) return null;
        return window.DashieNative.getAppVersion();
      },
      /**
       * Get detailed app version info
       * @returns {object|null} {versionName, versionCode, packageName, environment, baseUrl}
       */
      getAppVersionInfo() {
        if (!this.isAvailable()) return null;
        try {
          return JSON.parse(window.DashieNative.getAppVersionInfo());
        } catch (e) {
          logger.error("Failed to parse app version info", e);
          return null;
        }
      },
      /**
       * Get Android device ID (64-bit unique identifier)
       * Persists across app reinstalls
       * @returns {string|null} Android ID, null if not on Android
       */
      getDeviceId() {
        if (!this.isAvailable()) return null;
        return window.DashieNative.getDeviceId();
      },
      /**
       * Get comprehensive device information
       * @returns {object|null} Device info object with androidId, manufacturer, model, etc.
       *
       * Schema:
       * {
       *   androidId: string,      // Unique device identifier
       *   manufacturer: string,   // e.g., "onn"
       *   brand: string,          // e.g., "onn"
       *   model: string,          // e.g., "onn. Full HD Streaming Device"
       *   device: string,         // e.g., "XNA"
       *   product: string,        // e.g., "onn_2k_gtv"
       *   hardware: string,       // e.g., "amlogic"
       *   board: string,          // e.g., "onn_2k_gtv"
       *   androidVersion: string, // e.g., "14"
       *   sdkInt: number,         // e.g., 34
       *   buildId: string,        // e.g., "URO1.250103.029.C1"
       *   screenWidth: number,    // e.g., 1920
       *   screenHeight: number,   // e.g., 1080
       *   densityDpi: number,     // e.g., 320
       *   density: number,        // e.g., 2.0
       *   cpu: {
       *     processorCount: number,
       *     processorName: string,
       *     hardware: string,
       *     availableProcessors: number
       *   },
       *   supportedAbis: string,  // e.g., "armeabi-v7a, armeabi"
       *   isFireTV: boolean,
       *   isTv: boolean,
       *   isTablet: boolean,
       *   memory: {
       *     maxMemoryMB: number,
       *     totalMemoryMB: number,
       *     freeMemoryMB: number
       *   },
       *   appVersionName: string,
       *   appVersionCode: number
       * }
       */
      getDeviceInfo() {
        if (!this.isAvailable()) return null;
        try {
          const infoJson = window.DashieNative.getDeviceInfo();
          if (!infoJson || infoJson === "{}") return null;
          return JSON.parse(infoJson);
        } catch (e) {
          logger.error("Failed to parse device info", e);
          return null;
        }
      },
      /**
       * Check if microphone permission is granted
       * @returns {boolean} True if RECORD_AUDIO permission granted
       */
      hasMicrophonePermission() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.hasMicrophonePermission();
      },
      // ========================================
      // 3. Text-to-Speech (TTS)
      // ========================================
      /**
       * Speak text with default rate/pitch
       * @param {string} text - Text to speak
       */
      speak(text) {
        if (!this.isAvailable()) return;
        window.DashieNative.speak(text);
      },
      /**
       * Speak text with custom rate and pitch
       * @param {string} text - Text to speak
       * @param {number} rate - Speech rate (0.5-2.0, default 1.0)
       * @param {number} pitch - Speech pitch (0.5-2.0, default 1.0)
       */
      speakWithParams(text, rate = 1, pitch = 1) {
        if (!this.isAvailable()) return;
        window.DashieNative.speakWithParams(text, rate, pitch);
      },
      /**
       * Stop current TTS playback
       */
      stopSpeaking() {
        if (!this.isAvailable()) return;
        window.DashieNative.stopSpeaking();
      },
      /**
       * Check if TTS is currently speaking
       * @returns {boolean} True if speaking
       */
      isSpeaking() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.isSpeaking();
      },
      // ========================================
      // 4. Speech Recognition - Local
      // ========================================
      /**
       * Start on-device speech recognition (Google SpeechRecognizer)
       */
      startListening() {
        if (!this.isAvailable()) return;
        window.DashieNative.startListening();
      },
      /**
       * Stop recognition and return result
       */
      stopListening() {
        if (!this.isAvailable()) return;
        window.DashieNative.stopListening();
      },
      /**
       * Cancel recognition without returning result
       */
      cancelListening() {
        if (!this.isAvailable()) return;
        window.DashieNative.cancelListening();
      },
      /**
       * Check if currently listening
       * @returns {boolean} True if listening
       */
      isListening() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.isListening();
      },
      // ========================================
      // 5. Speech Recognition - Cloud/Deepgram
      // ========================================
      /**
       * Start cloud STT via Deepgram
       * @param {number} durationSeconds - Max recording duration
       * @param {number} silenceThresholdSeconds - Silence to trigger stop
       */
      startCloudSTTCapture(durationSeconds = 15, silenceThresholdSeconds = 2) {
        if (!this.isAvailable()) return;
        window.DashieNative.startCloudSTTCapture(durationSeconds, silenceThresholdSeconds);
      },
      /**
       * Stop cloud STT recording
       */
      stopCloudSTTCapture() {
        if (!this.isAvailable()) return;
        window.DashieNative.stopCloudSTTCapture();
      },
      /**
       * Start manual recording without wake word restart
       * @param {number} maxDurationSeconds - Max recording duration
       * @param {number} silenceThresholdSeconds - Silence to trigger stop
       */
      startManualRecording(maxDurationSeconds = 15, silenceThresholdSeconds = 2) {
        if (!this.isAvailable()) return;
        window.DashieNative.startManualRecording(maxDurationSeconds, silenceThresholdSeconds);
      },
      /**
       * Enable/disable streaming STT mode
       * @param {boolean} enabled - True for streaming, false for batch
       */
      setStreamingSTT(enabled) {
        if (!this.isAvailable()) return;
        window.DashieNative.setStreamingSTT(enabled);
      },
      /**
       * Check if streaming STT mode is enabled
       * @returns {boolean} True if streaming mode
       */
      isStreamingSTT() {
        if (!this.isAvailable()) return true;
        return window.DashieNative.isStreamingSTT();
      },
      /**
       * Set VAD silence detection threshold (Deepgram cloud VAD)
       * @param {number} silenceMs - Silence duration in milliseconds (default: 1000)
       */
      setSilenceThreshold(silenceMs) {
        if (!this.isAvailable()) return;
        logger.debug("Setting silence threshold", { silenceMs });
        window.DashieNative.setSilenceThreshold(silenceMs);
      },
      /**
       * Get current VAD silence threshold
       * @returns {number} Silence threshold in milliseconds
       */
      getSilenceThreshold() {
        if (!this.isAvailable()) return 1e3;
        return window.DashieNative.getSilenceThreshold();
      },
      // ========================================
      // 6. Wake Word Detection (Edge Impulse)
      // ========================================
      /**
       * Start wake word detection ("Hey Dashie")
       */
      startWakeWordDetection() {
        if (!this.isAvailable()) return;
        logger.debug("Starting wake word detection");
        window.DashieNative.startWakeWordDetection();
      },
      /**
       * Stop wake word detection
       */
      stopWakeWordDetection() {
        if (!this.isAvailable()) return;
        logger.debug("Stopping wake word detection");
        window.DashieNative.stopWakeWordDetection();
      },
      /**
       * Check if wake word detection is active
       * @returns {boolean} True if detection is running
       */
      isWakeWordActive() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.isWakeWordActive();
      },
      /**
       * Set wake word detection threshold
       * @param {number} threshold - Detection confidence (0.0-1.0, default: 0.55)
       */
      setWakeWordThreshold(threshold) {
        if (!this.isAvailable()) return;
        logger.debug("Setting wake word threshold", { threshold });
        window.DashieNative.setWakeWordThreshold(threshold);
      },
      /**
       * Get current wake word detection threshold
       * @returns {number} Detection threshold (0.0-1.0)
       */
      getWakeWordThreshold() {
        if (!this.isAvailable()) return 0.55;
        return window.DashieNative.getWakeWordThreshold();
      },
      /**
       * Configure wake word behavior
       * @param {boolean} autoRecord - Auto-start recording after wake word
       * @param {number} duration - Recording duration in seconds
       */
      setWakeWordConfig(autoRecord, duration) {
        if (!this.isAvailable()) return;
        window.DashieNative.setWakeWordConfig(autoRecord, duration);
      },
      /**
       * Get wake word configuration
       * @returns {object|null} {autoRecord: boolean, duration: number}
       */
      getWakeWordConfig() {
        if (!this.isAvailable()) return null;
        try {
          return JSON.parse(window.DashieNative.getWakeWordConfig());
        } catch (e) {
          return null;
        }
      },
      // ========================================
      // 7. Diagnostic Toasts
      // ========================================
      /**
       * Enable on-screen diagnostic messages
       */
      enableDiagnosticToasts() {
        if (!this.isAvailable()) return;
        logger.info("Enabling diagnostic toasts");
        window.DashieNative.enableDiagnosticToasts();
      },
      /**
       * Disable on-screen diagnostic messages
       */
      disableDiagnosticToasts() {
        if (!this.isAvailable()) return;
        logger.info("Disabling diagnostic toasts");
        window.DashieNative.disableDiagnosticToasts();
      },
      /**
       * Check if diagnostic toasts are enabled
       * @returns {boolean} True if enabled
       */
      isDiagnosticToastsEnabled() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.isDiagnosticToastsEnabled();
      },
      // ========================================
      // 8. Volume Control
      // ========================================
      /**
       * Get current volume level (0-10 scale)
       * @returns {number} Volume level 0-10, or -1 if not on Android
       */
      getVolume() {
        if (!this.isAvailable()) return -1;
        return window.DashieNative.getVolume();
      },
      /**
       * Set volume level (0-10 scale)
       * @param {number} level - Volume level (0-10)
       */
      setVolume(level) {
        if (!this.isAvailable()) return;
        window.DashieNative.setVolume(level);
      },
      /**
       * Increase volume by amount (on 0-10 scale)
       * @param {number} amount - Steps to increase (default 1)
       * @returns {number} New volume level (0-10)
       */
      volumeUp(amount = 1) {
        if (!this.isAvailable()) return -1;
        return window.DashieNative.volumeUp(amount);
      },
      /**
       * Decrease volume by amount (on 0-10 scale)
       * @param {number} amount - Steps to decrease (default 1)
       * @returns {number} New volume level (0-10)
       */
      volumeDown(amount = 1) {
        if (!this.isAvailable()) return -1;
        return window.DashieNative.volumeDown(amount);
      },
      /**
       * Check if audio is muted
       * @returns {boolean} True if muted
       */
      isMuted() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.isMuted();
      },
      /**
       * Mute system audio
       */
      mute() {
        if (!this.isAvailable()) return;
        window.DashieNative.mute();
      },
      /**
       * Unmute system audio
       */
      unmute() {
        if (!this.isAvailable()) return;
        window.DashieNative.unmute();
      },
      /**
       * Toggle mute state
       * @returns {boolean} New mute state (true = muted)
       */
      toggleMute() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.toggleMute();
      },
      /**
       * Get detailed volume info
       * @returns {object|null} {volume, muted, currentStep, maxSteps}
       */
      getVolumeInfo() {
        if (!this.isAvailable()) return null;
        try {
          return JSON.parse(window.DashieNative.getVolumeInfo());
        } catch (e) {
          logger.error("Failed to parse volume info", e);
          return null;
        }
      },
      /**
       * Register a callback for hardware volume button changes
       * @param {function} callback - Called with (level: number, isMuted: boolean)
       */
      onVolumeChange(callback) {
        if (typeof callback !== "function") return;
        window.onNativeVolumeChange = callback;
        logger.debug("Registered volume change callback");
      },
      // ========================================
      // 9. Brightness Control
      // ========================================
      /**
       * Get current screen brightness on 0-10 scale
       * @returns {number} Brightness level 0-10
       */
      getBrightness() {
        if (!this.isAvailable()) return 5;
        return window.DashieNative.getBrightness();
      },
      /**
       * Set screen brightness on 0-10 scale
       * @param {number} level - Brightness level 0-10
       */
      setBrightness(level) {
        if (!this.isAvailable()) return;
        window.DashieNative.setBrightness(level);
      },
      /**
       * Increase brightness by amount
       * @param {number} amount - Amount to increase (default 1)
       * @returns {number} New brightness level 0-10
       */
      brightnessUp(amount = 1) {
        if (!this.isAvailable()) return 5;
        return window.DashieNative.brightnessUp(amount);
      },
      /**
       * Decrease brightness by amount
       * @param {number} amount - Amount to decrease (default 1)
       * @returns {number} New brightness level 0-10
       */
      brightnessDown(amount = 1) {
        if (!this.isAvailable()) return 5;
        return window.DashieNative.brightnessDown(amount);
      },
      /**
       * Get detailed brightness info
       * @returns {object|null} {brightness, isAuto, rawValue, maxValue}
       */
      getBrightnessInfo() {
        if (!this.isAvailable()) return null;
        try {
          return JSON.parse(window.DashieNative.getBrightnessInfo());
        } catch (e) {
          logger.error("Failed to parse brightness info", e);
          return null;
        }
      },
      /**
       * Enable or disable auto-brightness
       * @param {boolean} enabled - True to enable, false to disable
       */
      setAutoBrightness(enabled) {
        if (!this.isAvailable()) return;
        window.DashieNative.setAutoBrightness(enabled);
      },
      /**
       * Check if we have permission to modify system settings (required for brightness)
       * @returns {boolean} True if permission is granted
       */
      canWriteSettings() {
        if (!this.isAvailable()) return true;
        if (!window.DashieNative.canWriteSettings) return true;
        return window.DashieNative.canWriteSettings();
      },
      /**
       * Request permission to modify system settings (opens Android settings page)
       */
      requestWriteSettingsPermission() {
        if (!this.isAvailable()) return;
        if (!window.DashieNative.requestWriteSettingsPermission) return;
        logger.info("Requesting WRITE_SETTINGS permission");
        window.DashieNative.requestWriteSettingsPermission();
      },
      // ========================================
      // 10. Wake Word Samples
      // ========================================
      /**
       * Record a 5-second wake word sample
       * Sample is streamed to webapp via onWakeWordSampleRecorded callback
       */
      recordWakeWordSample() {
        if (!this.isAvailable()) return;
        logger.info("Recording wake word sample");
        window.DashieNative.recordWakeWordSample();
      },
      /**
       * Get wake word sample statistics
       * @returns {object|null} {count, sizeMB, note}
       */
      getWakeWordSampleStats() {
        if (!this.isAvailable()) return null;
        try {
          return JSON.parse(window.DashieNative.getWakeWordSampleStats());
        } catch (e) {
          return null;
        }
      },
      // ========================================
      // 11. Timezone
      // ========================================
      /**
       * Get the device's current timezone ID
       * @returns {string|null} Timezone ID like "America/New_York", "Asia/Shanghai", etc.
       */
      getDeviceTimezone() {
        if (!this.isAvailable()) return null;
        return window.DashieNative.getDeviceTimezone();
      },
      /**
       * Open the main Android System Settings
       * Useful as an escape hatch when the app is in kiosk-like mode
       */
      openSystemSettings() {
        if (!this.isAvailable()) return;
        logger.info("Opening System Settings (main)");
        window.DashieNative.openSystemSettings();
      },
      /**
       * Open the system Date & Time settings
       * User can enable "Automatic time zone" there
       */
      openDateTimeSettings() {
        if (!this.isAvailable()) return;
        logger.info("Opening Date & Time settings");
        window.DashieNative.openDateTimeSettings();
      },
      /**
       * Open the system Location settings
       * User can enable location services / GPS there
       */
      openLocationSettings() {
        if (!this.isAvailable()) return;
        logger.info("Opening Location settings");
        window.DashieNative.openLocationSettings();
      },
      /**
       * Check if location services are enabled
       * @returns {boolean} True if location is enabled
       */
      isLocationEnabled() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.isLocationEnabled();
      },
      /**
       * Open the app-specific settings page
       * User can grant permissions (location, microphone, etc.) there
       */
      openAppSettings() {
        if (!this.isAvailable()) return;
        logger.info("Opening App Settings");
        window.DashieNative.openAppSettings();
      },
      /**
       * Check if the app has location permission
       * @returns {boolean} True if location permission is granted
       */
      hasLocationPermission() {
        if (!this.isAvailable()) return false;
        return window.DashieNative.hasLocationPermission();
      },
      // ========================================
      // 11. WiFi
      // ========================================
      /**
       * Check if device has internet connectivity
       * @returns {boolean} True if connected to internet
       */
      isConnectedToInternet() {
        if (!this.isAvailable()) return true;
        return window.DashieNative.isConnectedToInternet();
      },
      /**
       * Get WiFi status information
       * @returns {object|null} {wifiEnabled, connected, ssid, signalStrength (0-4), rssi}
       */
      getWifiStatus() {
        if (!this.isAvailable()) return null;
        try {
          return JSON.parse(window.DashieNative.getWifiStatus());
        } catch (e) {
          logger.error("Failed to parse WiFi status", e);
          return null;
        }
      },
      /**
       * Open the native WiFi setup activity
       * Shows available networks and allows connecting
       */
      openWifiSetup() {
        if (!this.isAvailable()) return;
        logger.info("Opening WiFi setup");
        window.DashieNative.openWifiSetup();
      },
      /**
       * Open system WiFi settings
       */
      openWifiSettings() {
        if (!this.isAvailable()) return;
        logger.info("Opening system WiFi settings");
        window.DashieNative.openWifiSettings();
      },
      // ========================================
      // Convenience Methods
      // ========================================
      /**
       * Log device info to console (for debugging)
       */
      logDeviceInfo() {
        if (!this.isAvailable()) {
          logger.info("Not running on Android");
          return;
        }
        const info = this.getDeviceInfo();
        if (info) {
          logger.info("=== Android Device Info ===");
          logger.info(`Android ID: ${info.androidId}`);
          logger.info(`Device: ${info.manufacturer} ${info.model}`);
          logger.info(`Android: ${info.androidVersion} (SDK ${info.sdkInt})`);
          logger.info(`Type: ${info.isTv ? "TV" : info.isTablet ? "Tablet" : "Mobile"}`);
          logger.info(`Screen: ${info.screenWidth}x${info.screenHeight}`);
          logger.info(`App: v${info.appVersionName} (${info.appVersionCode})`);
          logger.info("===========================");
        }
      },
      /**
       * Get a summary string of the device
       * @returns {string|null} Summary like "onn onn. Full HD Streaming Device (Android 14)"
       */
      getDeviceSummary() {
        const info = this.getDeviceInfo();
        if (!info) return null;
        return `${info.manufacturer} ${info.model} (Android ${info.androidVersion})`;
      },
      /**
       * Get the appropriate volume icon path based on volume level and mute state
       * @param {number} level - Volume level (0-10)
       * @param {boolean} muted - Whether audio is muted
       * @returns {string} Icon path
       */
      getVolumeIcon(level, muted = false) {
        if (muted || level === 0) {
          return "/artwork/icon-volume-mute.svg";
        } else if (level <= 2) {
          return "/artwork/icon-volume-low.svg";
        } else if (level <= 6) {
          return "/artwork/icon-volume-mid.svg";
        } else {
          return "/artwork/icon-volume-high.svg";
        }
      }
    };
    ({
      isAvailable,
      getDeviceId,
      getDeviceInfo,
      getDeviceType,
      getAppVersion,
      getAppVersionInfo,
      hasMicrophonePermission,
      speak,
      speakWithParams,
      stopSpeaking,
      isSpeaking,
      startListening,
      stopListening,
      cancelListening,
      isListening,
      startCloudSTTCapture,
      stopCloudSTTCapture,
      startManualRecording,
      setStreamingSTT,
      isStreamingSTT,
      setSilenceThreshold,
      getSilenceThreshold,
      startWakeWordDetection,
      stopWakeWordDetection,
      isWakeWordActive,
      setWakeWordThreshold,
      getWakeWordThreshold,
      setWakeWordConfig,
      getWakeWordConfig,
      enableDiagnosticToasts,
      disableDiagnosticToasts,
      isDiagnosticToastsEnabled,
      recordWakeWordSample,
      getWakeWordSampleStats,
      getVolume,
      setVolume,
      volumeUp,
      volumeDown,
      isMuted,
      mute,
      unmute,
      toggleMute,
      getVolumeInfo,
      onVolumeChange,
      getBrightness,
      setBrightness,
      brightnessUp,
      brightnessDown,
      getBrightnessInfo,
      setAutoBrightness,
      canWriteSettings,
      requestWriteSettingsPermission,
      getDeviceTimezone,
      openSystemSettings,
      openDateTimeSettings,
      openLocationSettings,
      isLocationEnabled,
      openAppSettings,
      hasLocationPermission,
      isConnectedToInternet,
      getWifiStatus,
      openWifiSetup,
      openWifiSettings,
      exitApp,
      restartApp,
      softRestartApp,
      clearCache,
      logDeviceInfo,
      getDeviceSummary,
      getVolumeIcon
    } = AndroidBridge);
  }
});

// js/shims/cache-buster-shim.js
var cacheBuster, cache_buster_shim_default, bustUrl, getVersion;
var init_cache_buster_shim = __esm({
  "js/shims/cache-buster-shim.js"() {
    cacheBuster = {
      bustUrl: (url) => url,
      getVersion: () => "0",
      getLoadTimestamp: () => 0,
      hasCacheBusting: () => false,
      cleanUrl: (url) => url
    };
    cache_buster_shim_default = cacheBuster;
    bustUrl = cacheBuster.bustUrl;
    getVersion = cacheBuster.getVersion;
  }
});

// ../js/ui/volume-slider.js
function createVolumeSliderContent(options = {}) {
  const {
    onVolumeChange: onVolumeChange2,
    onActivity,
    resolveIconUrl = (path) => cache_buster_shim_default.bustUrl(path)
  } = options;
  let currentVolume = 5;
  let isMuted2 = false;
  try {
    if (AndroidBridge.isAvailable()) {
      currentVolume = AndroidBridge.getVolume() || 5;
      isMuted2 = AndroidBridge.isMuted() || false;
    }
  } catch (e) {
    logger2.warn("Failed to get volume state", e);
  }
  const container = document.createElement("div");
  container.className = "volume-slider-container";
  container.innerHTML = `
    <div class="volume-slider-row">
      <div class="volume-slider-track-column">
        <div class="volume-slider-track">
          <div class="volume-slider-fill" style="width: ${isMuted2 ? 0 : currentVolume * 10}%"></div>
          <input type="range"
                 class="volume-slider-input"
                 min="0"
                 max="10"
                 value="${isMuted2 ? 0 : currentVolume}"
                 ${isMuted2 ? "disabled" : ""}>
        </div>
        <div class="volume-slider-controls">
          <button class="volume-slider-step-btn volume-slider-minus-btn" aria-label="Decrease volume">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
          </button>
          <span class="volume-slider-level">${isMuted2 ? 0 : currentVolume}</span>
          <button class="volume-slider-step-btn volume-slider-plus-btn" aria-label="Increase volume">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
          </button>
        </div>
      </div>
      <button class="volume-slider-mute-btn" data-muted="${isMuted2}">
        <img src="${resolveIconUrl(getVolumeIcon(currentVolume, isMuted2))}"
             alt="${isMuted2 ? "Unmute" : "Mute"}"
             class="volume-slider-mute-icon">
      </button>
    </div>
  `;
  const slider = container.querySelector(".volume-slider-input");
  const fill = container.querySelector(".volume-slider-fill");
  const muteBtn = container.querySelector(".volume-slider-mute-btn");
  const muteIcon = container.querySelector(".volume-slider-mute-icon");
  const levelDisplay = container.querySelector(".volume-slider-level");
  const minusBtn = container.querySelector(".volume-slider-minus-btn");
  const plusBtn = container.querySelector(".volume-slider-plus-btn");
  let volumeBeforeMute = currentVolume > 0 ? currentVolume : 5;
  const updateVolumeUI = (value, muted) => {
    slider.value = value;
    fill.style.width = `${value * 10}%`;
    levelDisplay.textContent = value;
    muteIcon.src = resolveIconUrl(getVolumeIcon(value, muted));
    muteBtn.dataset.muted = muted ? "true" : "false";
    slider.disabled = muted;
  };
  const setVolumeAndNotify = (value) => {
    try {
      if (AndroidBridge.isAvailable()) {
        AndroidBridge.setVolume(value);
        if (value > 0 && isMuted2) {
          AndroidBridge.unmute();
          isMuted2 = false;
        }
        logger2.info("Volume set", { level: value });
      }
    } catch (err) {
      logger2.error("Failed to set volume", err);
    }
    if (value > 0) {
      volumeBeforeMute = value;
    }
    updateVolumeUI(value, isMuted2);
    onVolumeChange2?.(value, isMuted2);
  };
  const handleSliderInput = (e) => {
    onActivity?.();
    const value = parseInt(e.target.value, 10);
    fill.style.width = `${value * 10}%`;
    levelDisplay.textContent = value;
    if (value > 0 && isMuted2) {
      isMuted2 = false;
      muteBtn.dataset.muted = "false";
      slider.disabled = false;
    }
    muteIcon.src = resolveIconUrl(getVolumeIcon(value, isMuted2));
  };
  const handleSliderChange = (e) => {
    onActivity?.();
    const value = parseInt(e.target.value, 10);
    setVolumeAndNotify(value);
  };
  const handleMinusClick = () => {
    onActivity?.();
    const currentValue = parseInt(slider.value, 10);
    if (currentValue > 0) {
      setVolumeAndNotify(currentValue - 1);
    }
  };
  const handlePlusClick = () => {
    onActivity?.();
    const currentValue = parseInt(slider.value, 10);
    if (currentValue < 10) {
      if (isMuted2) {
        try {
          AndroidBridge.unmute();
          isMuted2 = false;
        } catch (err) {
          logger2.error("Failed to unmute", err);
        }
      }
      setVolumeAndNotify(currentValue + 1);
    }
  };
  const handleMuteClick = () => {
    onActivity?.();
    try {
      if (AndroidBridge.isAvailable()) {
        const newMuted = AndroidBridge.toggleMute();
        isMuted2 = newMuted;
        if (newMuted) {
          volumeBeforeMute = parseInt(slider.value, 10) || volumeBeforeMute;
          updateVolumeUI(0, true);
        } else {
          AndroidBridge.setVolume(volumeBeforeMute);
          updateVolumeUI(volumeBeforeMute, false);
        }
        logger2.info("Mute toggled", { muted: newMuted, volumeBeforeMute });
        onVolumeChange2?.(newMuted ? 0 : volumeBeforeMute, newMuted);
      }
    } catch (err) {
      logger2.error("Failed to toggle mute", err);
    }
  };
  slider.addEventListener("input", handleSliderInput);
  slider.addEventListener("change", handleSliderChange);
  muteBtn.addEventListener("click", handleMuteClick);
  minusBtn.addEventListener("click", handleMinusClick);
  plusBtn.addEventListener("click", handlePlusClick);
  return {
    element: container,
    updateVolume: updateVolumeUI,
    destroy() {
      slider.removeEventListener("input", handleSliderInput);
      slider.removeEventListener("change", handleSliderChange);
      muteBtn.removeEventListener("click", handleMuteClick);
      minusBtn.removeEventListener("click", handleMinusClick);
      plusBtn.removeEventListener("click", handlePlusClick);
    }
  };
}
var logger2;
var init_volume_slider = __esm({
  "../js/ui/volume-slider.js"() {
    init_logger_shim();
    init_android_bridge();
    init_cache_buster_shim();
    logger2 = createLogger("VolumeSlider");
  }
});

// js/dash-menu/dash-menu-volume-popout.js
function createVolumePopout(onActivity) {
  const result = createVolumeSliderContent({
    onActivity,
    resolveIconUrl: (path) => path.replace(/^\//, "")
  });
  const popup = document.createElement("div");
  popup.className = "volume-slider-popup";
  popup.appendChild(result.element);
  return {
    element: popup,
    popoutWidth: 280,
    destroy: () => result.destroy()
  };
}
var init_dash_menu_volume_popout = __esm({
  "js/dash-menu/dash-menu-volume-popout.js"() {
    init_volume_slider();
  }
});

// js/shims/theme-applier-shim.js
var theme_applier_shim_default;
var init_theme_applier_shim = __esm({
  "js/shims/theme-applier-shim.js"() {
    theme_applier_shim_default = {
      isDarkTheme: () => true,
      getCurrentTheme: () => "default-dark",
      applyTheme: () => {
      }
    };
  }
});

// ../js/utils/timezone-helper.js
function getTimezoneFromZipCode(zipCode) {
  if (!zipCode || typeof zipCode !== "string") {
    return null;
  }
  const cleanZip = zipCode.replace(/[\s-]/g, "");
  const prefix = cleanZip.substring(0, 3);
  const timezone = ZIP_PREFIX_TO_TIMEZONE[prefix];
  if (timezone) {
    logger3.debug("Timezone from zip code", { zipCode, prefix, timezone });
    return timezone;
  }
  logger3.warn("No timezone mapping for zip code", { zipCode, prefix });
  return null;
}
function getUserTimezone() {
  if (cachedUserTimezone && timezoneInitialized) {
    return cachedUserTimezone;
  }
  try {
    if (window.settingsStore) {
      const zipCode = window.settingsStore.get("family.zipCode");
      if (zipCode) {
        const timezone = getTimezoneFromZipCode(zipCode);
        if (timezone) {
          cachedUserTimezone = timezone;
          timezoneInitialized = true;
          logger3.debug("Timezone from settingsStore zip code", { zipCode, timezone });
          return timezone;
        }
      }
    }
  } catch (e) {
  }
  try {
    const parentSettings = window.parent?.settingsStore;
    if (parentSettings && parentSettings !== window.settingsStore) {
      const zipCode = parentSettings.get("family.zipCode");
      if (zipCode) {
        const timezone = getTimezoneFromZipCode(zipCode);
        if (timezone) {
          cachedUserTimezone = timezone;
          timezoneInitialized = true;
          logger3.debug("Timezone from parent settingsStore zip code", { zipCode, timezone });
          return timezone;
        }
      }
    }
  } catch (e) {
  }
  try {
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (timezone) {
      logger3.debug("Timezone from device (not cached - may be unreliable)", { timezone });
      return timezone;
    }
    logger3.warn("Intl API returned no timezone, using fallback");
    return "America/New_York";
  } catch (error) {
    logger3.error("Failed to detect timezone, using fallback", { error: error.message });
    return "America/New_York";
  }
}
function getMonthInTimezone(date = /* @__PURE__ */ new Date(), timezone = null) {
  const tz = timezone || getUserTimezone();
  const monthStr = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    month: "numeric"
  }).format(date);
  return parseInt(monthStr, 10) - 1;
}
var logger3, cachedUserTimezone, timezoneInitialized, ZIP_PREFIX_TO_TIMEZONE;
var init_timezone_helper = __esm({
  "../js/utils/timezone-helper.js"() {
    init_logger();
    logger3 = createLogger2("TimezoneHelper");
    cachedUserTimezone = null;
    timezoneInitialized = false;
    ZIP_PREFIX_TO_TIMEZONE = {
      // Eastern Time (ET)
      "006": "America/Puerto_Rico",
      // PR
      "007": "America/Puerto_Rico",
      "008": "America/Puerto_Rico",
      "009": "America/Puerto_Rico",
      "010": "America/New_York",
      "011": "America/New_York",
      "012": "America/New_York",
      "013": "America/New_York",
      "014": "America/New_York",
      "015": "America/New_York",
      "016": "America/New_York",
      "017": "America/New_York",
      "018": "America/New_York",
      "019": "America/New_York",
      "020": "America/New_York",
      "021": "America/New_York",
      "022": "America/New_York",
      "023": "America/New_York",
      "024": "America/New_York",
      "025": "America/New_York",
      "026": "America/New_York",
      "027": "America/New_York",
      "028": "America/New_York",
      "029": "America/New_York",
      "030": "America/New_York",
      "031": "America/New_York",
      "032": "America/New_York",
      "033": "America/New_York",
      "034": "America/New_York",
      "035": "America/New_York",
      "036": "America/New_York",
      "037": "America/New_York",
      "038": "America/New_York",
      "039": "America/New_York",
      "040": "America/New_York",
      "041": "America/New_York",
      "042": "America/New_York",
      "043": "America/New_York",
      "044": "America/New_York",
      "045": "America/New_York",
      "046": "America/New_York",
      "047": "America/New_York",
      "048": "America/New_York",
      "049": "America/New_York",
      "050": "America/New_York",
      "051": "America/New_York",
      "052": "America/New_York",
      "053": "America/New_York",
      "054": "America/New_York",
      "055": "America/New_York",
      "056": "America/New_York",
      "057": "America/New_York",
      "058": "America/New_York",
      "059": "America/New_York",
      "060": "America/New_York",
      "061": "America/New_York",
      "062": "America/New_York",
      "063": "America/New_York",
      "064": "America/New_York",
      "065": "America/New_York",
      "066": "America/New_York",
      "067": "America/New_York",
      "068": "America/New_York",
      "069": "America/New_York",
      "070": "America/New_York",
      "071": "America/New_York",
      "072": "America/New_York",
      "073": "America/New_York",
      "074": "America/New_York",
      "075": "America/New_York",
      "076": "America/New_York",
      "077": "America/New_York",
      "078": "America/New_York",
      "079": "America/New_York",
      "080": "America/New_York",
      "081": "America/New_York",
      "082": "America/New_York",
      "083": "America/New_York",
      "084": "America/New_York",
      "085": "America/New_York",
      "086": "America/New_York",
      "087": "America/New_York",
      "088": "America/New_York",
      "089": "America/New_York",
      "100": "America/New_York",
      "101": "America/New_York",
      "102": "America/New_York",
      "103": "America/New_York",
      "104": "America/New_York",
      "105": "America/New_York",
      "106": "America/New_York",
      "107": "America/New_York",
      "108": "America/New_York",
      "109": "America/New_York",
      "110": "America/New_York",
      "111": "America/New_York",
      "112": "America/New_York",
      "113": "America/New_York",
      "114": "America/New_York",
      "115": "America/New_York",
      "116": "America/New_York",
      "117": "America/New_York",
      "118": "America/New_York",
      "119": "America/New_York",
      "120": "America/New_York",
      "121": "America/New_York",
      "122": "America/New_York",
      "123": "America/New_York",
      "124": "America/New_York",
      "125": "America/New_York",
      "126": "America/New_York",
      "127": "America/New_York",
      "128": "America/New_York",
      "129": "America/New_York",
      "130": "America/New_York",
      "131": "America/New_York",
      "132": "America/New_York",
      "133": "America/New_York",
      "134": "America/New_York",
      "135": "America/New_York",
      "136": "America/New_York",
      "137": "America/New_York",
      "138": "America/New_York",
      "139": "America/New_York",
      "140": "America/New_York",
      "141": "America/New_York",
      "142": "America/New_York",
      "143": "America/New_York",
      "144": "America/New_York",
      "145": "America/New_York",
      "146": "America/New_York",
      "147": "America/New_York",
      "148": "America/New_York",
      "149": "America/New_York",
      "150": "America/New_York",
      "151": "America/New_York",
      "152": "America/New_York",
      "153": "America/New_York",
      "154": "America/New_York",
      "155": "America/New_York",
      "156": "America/New_York",
      "157": "America/New_York",
      "158": "America/New_York",
      "159": "America/New_York",
      "160": "America/New_York",
      "161": "America/New_York",
      "162": "America/New_York",
      "163": "America/New_York",
      "164": "America/New_York",
      "165": "America/New_York",
      "166": "America/New_York",
      "167": "America/New_York",
      "168": "America/New_York",
      "169": "America/New_York",
      "170": "America/New_York",
      "171": "America/New_York",
      "172": "America/New_York",
      "173": "America/New_York",
      "174": "America/New_York",
      "175": "America/New_York",
      "176": "America/New_York",
      "177": "America/New_York",
      "178": "America/New_York",
      "179": "America/New_York",
      "180": "America/New_York",
      "181": "America/New_York",
      "182": "America/New_York",
      "183": "America/New_York",
      "184": "America/New_York",
      "185": "America/New_York",
      "186": "America/New_York",
      "187": "America/New_York",
      "188": "America/New_York",
      "189": "America/New_York",
      "190": "America/New_York",
      "191": "America/New_York",
      "192": "America/New_York",
      "193": "America/New_York",
      "194": "America/New_York",
      "195": "America/New_York",
      "196": "America/New_York",
      "197": "America/New_York",
      "198": "America/New_York",
      "199": "America/New_York",
      "200": "America/New_York",
      "201": "America/New_York",
      "202": "America/New_York",
      "203": "America/New_York",
      "204": "America/New_York",
      "205": "America/New_York",
      "206": "America/New_York",
      "207": "America/New_York",
      "208": "America/New_York",
      "209": "America/New_York",
      "210": "America/New_York",
      "211": "America/New_York",
      "212": "America/New_York",
      "213": "America/New_York",
      "214": "America/New_York",
      "215": "America/New_York",
      "216": "America/New_York",
      "217": "America/New_York",
      "218": "America/New_York",
      "219": "America/New_York",
      "220": "America/New_York",
      "221": "America/New_York",
      "222": "America/New_York",
      "223": "America/New_York",
      "224": "America/New_York",
      "225": "America/New_York",
      "226": "America/New_York",
      "227": "America/New_York",
      "228": "America/New_York",
      "229": "America/New_York",
      "230": "America/New_York",
      "231": "America/New_York",
      "232": "America/New_York",
      "233": "America/New_York",
      "234": "America/New_York",
      "235": "America/New_York",
      "236": "America/New_York",
      "237": "America/New_York",
      "238": "America/New_York",
      "239": "America/New_York",
      "240": "America/New_York",
      "241": "America/New_York",
      "242": "America/New_York",
      "243": "America/New_York",
      "244": "America/New_York",
      "245": "America/New_York",
      "246": "America/New_York",
      "247": "America/New_York",
      "248": "America/New_York",
      "249": "America/New_York",
      "250": "America/New_York",
      "251": "America/New_York",
      "252": "America/New_York",
      "253": "America/New_York",
      "254": "America/New_York",
      "255": "America/New_York",
      "256": "America/New_York",
      "257": "America/New_York",
      "258": "America/New_York",
      "259": "America/New_York",
      "260": "America/New_York",
      "261": "America/New_York",
      "262": "America/New_York",
      "263": "America/New_York",
      "264": "America/New_York",
      "265": "America/New_York",
      "266": "America/New_York",
      "267": "America/New_York",
      "268": "America/New_York",
      "269": "America/New_York",
      "270": "America/New_York",
      "271": "America/New_York",
      "272": "America/New_York",
      "273": "America/New_York",
      "274": "America/New_York",
      "275": "America/New_York",
      "276": "America/New_York",
      "277": "America/New_York",
      "278": "America/New_York",
      "279": "America/New_York",
      "280": "America/New_York",
      "281": "America/New_York",
      "282": "America/New_York",
      "283": "America/New_York",
      "284": "America/New_York",
      "285": "America/New_York",
      "286": "America/New_York",
      "287": "America/New_York",
      "288": "America/New_York",
      "289": "America/New_York",
      "290": "America/New_York",
      "291": "America/New_York",
      "292": "America/New_York",
      "293": "America/New_York",
      "294": "America/New_York",
      "295": "America/New_York",
      "296": "America/New_York",
      "297": "America/New_York",
      "298": "America/New_York",
      "299": "America/New_York",
      "300": "America/New_York",
      "301": "America/New_York",
      "302": "America/New_York",
      "303": "America/New_York",
      "304": "America/New_York",
      "305": "America/New_York",
      "306": "America/New_York",
      "307": "America/New_York",
      "308": "America/New_York",
      "309": "America/New_York",
      "310": "America/New_York",
      "311": "America/New_York",
      "312": "America/New_York",
      "313": "America/New_York",
      "314": "America/New_York",
      "315": "America/New_York",
      "316": "America/New_York",
      "317": "America/New_York",
      "318": "America/New_York",
      "319": "America/New_York",
      "320": "America/New_York",
      "321": "America/New_York",
      "322": "America/New_York",
      "323": "America/New_York",
      "324": "America/New_York",
      "325": "America/New_York",
      "326": "America/New_York",
      "327": "America/New_York",
      "328": "America/New_York",
      "329": "America/New_York",
      "330": "America/New_York",
      "331": "America/New_York",
      "332": "America/New_York",
      "333": "America/New_York",
      "334": "America/New_York",
      "335": "America/New_York",
      "336": "America/New_York",
      "337": "America/New_York",
      "338": "America/New_York",
      "339": "America/New_York",
      "340": "America/New_York",
      "341": "America/New_York",
      "342": "America/New_York",
      "344": "America/New_York",
      "346": "America/New_York",
      "347": "America/New_York",
      "349": "America/New_York",
      "430": "America/New_York",
      "431": "America/New_York",
      "432": "America/New_York",
      "433": "America/New_York",
      "434": "America/New_York",
      "435": "America/New_York",
      "436": "America/New_York",
      "437": "America/New_York",
      "438": "America/New_York",
      "439": "America/New_York",
      "440": "America/New_York",
      "441": "America/New_York",
      "442": "America/New_York",
      "443": "America/New_York",
      "444": "America/New_York",
      "445": "America/New_York",
      "446": "America/New_York",
      "447": "America/New_York",
      "448": "America/New_York",
      "449": "America/New_York",
      "450": "America/New_York",
      "451": "America/New_York",
      "452": "America/New_York",
      "453": "America/New_York",
      "454": "America/New_York",
      "455": "America/New_York",
      "456": "America/New_York",
      "457": "America/New_York",
      "458": "America/New_York",
      "480": "America/New_York",
      "481": "America/New_York",
      "482": "America/New_York",
      "483": "America/New_York",
      "484": "America/New_York",
      "485": "America/New_York",
      "486": "America/New_York",
      "487": "America/New_York",
      "488": "America/New_York",
      "489": "America/New_York",
      "490": "America/New_York",
      "491": "America/New_York",
      "492": "America/New_York",
      "493": "America/New_York",
      "494": "America/New_York",
      "495": "America/New_York",
      "496": "America/New_York",
      "497": "America/New_York",
      "498": "America/New_York",
      "499": "America/New_York",
      // Central Time (CT)
      "350": "America/Chicago",
      "351": "America/Chicago",
      "352": "America/Chicago",
      "354": "America/Chicago",
      "355": "America/Chicago",
      "356": "America/Chicago",
      "357": "America/Chicago",
      "358": "America/Chicago",
      "359": "America/Chicago",
      "360": "America/Chicago",
      "361": "America/Chicago",
      "362": "America/Chicago",
      "363": "America/Chicago",
      "364": "America/Chicago",
      "365": "America/Chicago",
      "366": "America/Chicago",
      "367": "America/Chicago",
      "368": "America/Chicago",
      "369": "America/Chicago",
      "370": "America/Chicago",
      "371": "America/Chicago",
      "372": "America/Chicago",
      "373": "America/Chicago",
      "374": "America/Chicago",
      "375": "America/Chicago",
      "376": "America/Chicago",
      "377": "America/Chicago",
      "378": "America/Chicago",
      "379": "America/Chicago",
      "380": "America/Chicago",
      "381": "America/Chicago",
      "382": "America/Chicago",
      "383": "America/Chicago",
      "384": "America/Chicago",
      "385": "America/Chicago",
      "386": "America/Chicago",
      "387": "America/Chicago",
      "388": "America/Chicago",
      "389": "America/Chicago",
      "390": "America/Chicago",
      "391": "America/Chicago",
      "392": "America/Chicago",
      "393": "America/Chicago",
      "394": "America/Chicago",
      "395": "America/Chicago",
      "396": "America/Chicago",
      "397": "America/Chicago",
      "398": "America/Chicago",
      "399": "America/Chicago",
      "400": "America/Chicago",
      "401": "America/Chicago",
      "402": "America/Chicago",
      "403": "America/Chicago",
      "404": "America/Chicago",
      "405": "America/Chicago",
      "406": "America/Chicago",
      "407": "America/Chicago",
      "408": "America/Chicago",
      "409": "America/Chicago",
      "410": "America/Chicago",
      "411": "America/Chicago",
      "412": "America/Chicago",
      "413": "America/Chicago",
      "414": "America/Chicago",
      "415": "America/Chicago",
      "416": "America/Chicago",
      "417": "America/Chicago",
      "418": "America/Chicago",
      "420": "America/Chicago",
      "421": "America/Chicago",
      "422": "America/Chicago",
      "423": "America/Chicago",
      "424": "America/Chicago",
      "425": "America/Chicago",
      "426": "America/Chicago",
      "427": "America/Chicago",
      "459": "America/Chicago",
      "460": "America/Chicago",
      "461": "America/Chicago",
      "462": "America/Chicago",
      "463": "America/Chicago",
      "464": "America/Chicago",
      "465": "America/Chicago",
      "466": "America/Chicago",
      "467": "America/Chicago",
      "468": "America/Chicago",
      "469": "America/Chicago",
      "470": "America/Chicago",
      "471": "America/Chicago",
      "472": "America/Chicago",
      "473": "America/Chicago",
      "474": "America/Chicago",
      "475": "America/Chicago",
      "476": "America/Chicago",
      "477": "America/Chicago",
      "478": "America/Chicago",
      "479": "America/Chicago",
      "500": "America/Chicago",
      "501": "America/Chicago",
      "502": "America/Chicago",
      "503": "America/Chicago",
      "504": "America/Chicago",
      "505": "America/Chicago",
      "506": "America/Chicago",
      "507": "America/Chicago",
      "508": "America/Chicago",
      "509": "America/Chicago",
      "510": "America/Chicago",
      "511": "America/Chicago",
      "512": "America/Chicago",
      "513": "America/Chicago",
      "514": "America/Chicago",
      "515": "America/Chicago",
      "516": "America/Chicago",
      "520": "America/Chicago",
      "521": "America/Chicago",
      "522": "America/Chicago",
      "523": "America/Chicago",
      "524": "America/Chicago",
      "525": "America/Chicago",
      "526": "America/Chicago",
      "527": "America/Chicago",
      "528": "America/Chicago",
      "530": "America/Chicago",
      "531": "America/Chicago",
      "532": "America/Chicago",
      "534": "America/Chicago",
      "535": "America/Chicago",
      "537": "America/Chicago",
      "538": "America/Chicago",
      "539": "America/Chicago",
      "540": "America/Chicago",
      "541": "America/Chicago",
      "542": "America/Chicago",
      "543": "America/Chicago",
      "544": "America/Chicago",
      "545": "America/Chicago",
      "546": "America/Chicago",
      "547": "America/Chicago",
      "548": "America/Chicago",
      "549": "America/Chicago",
      "550": "America/Chicago",
      "551": "America/Chicago",
      "553": "America/Chicago",
      "554": "America/Chicago",
      "556": "America/Chicago",
      "557": "America/Chicago",
      "558": "America/Chicago",
      "559": "America/Chicago",
      "560": "America/Chicago",
      "561": "America/Chicago",
      "562": "America/Chicago",
      "563": "America/Chicago",
      "564": "America/Chicago",
      "565": "America/Chicago",
      "566": "America/Chicago",
      "567": "America/Chicago",
      "570": "America/Chicago",
      "571": "America/Chicago",
      "572": "America/Chicago",
      "573": "America/Chicago",
      "574": "America/Chicago",
      "575": "America/Chicago",
      "576": "America/Chicago",
      "577": "America/Chicago",
      "580": "America/Chicago",
      "581": "America/Chicago",
      "582": "America/Chicago",
      "583": "America/Chicago",
      "584": "America/Chicago",
      "585": "America/Chicago",
      "586": "America/Chicago",
      "587": "America/Chicago",
      "588": "America/Chicago",
      "600": "America/Chicago",
      "601": "America/Chicago",
      "602": "America/Chicago",
      "603": "America/Chicago",
      "604": "America/Chicago",
      "605": "America/Chicago",
      "606": "America/Chicago",
      "607": "America/Chicago",
      "608": "America/Chicago",
      "609": "America/Chicago",
      "610": "America/Chicago",
      "611": "America/Chicago",
      "612": "America/Chicago",
      "613": "America/Chicago",
      "614": "America/Chicago",
      "615": "America/Chicago",
      "616": "America/Chicago",
      "617": "America/Chicago",
      "618": "America/Chicago",
      "619": "America/Chicago",
      "620": "America/Chicago",
      "622": "America/Chicago",
      "623": "America/Chicago",
      "624": "America/Chicago",
      "625": "America/Chicago",
      "626": "America/Chicago",
      "627": "America/Chicago",
      "628": "America/Chicago",
      "629": "America/Chicago",
      "630": "America/Chicago",
      "631": "America/Chicago",
      "633": "America/Chicago",
      "634": "America/Chicago",
      "635": "America/Chicago",
      "636": "America/Chicago",
      "637": "America/Chicago",
      "638": "America/Chicago",
      "639": "America/Chicago",
      "640": "America/Chicago",
      "641": "America/Chicago",
      "644": "America/Chicago",
      "645": "America/Chicago",
      "646": "America/Chicago",
      "647": "America/Chicago",
      "648": "America/Chicago",
      "649": "America/Chicago",
      "650": "America/Chicago",
      "651": "America/Chicago",
      "652": "America/Chicago",
      "653": "America/Chicago",
      "654": "America/Chicago",
      "655": "America/Chicago",
      "656": "America/Chicago",
      "657": "America/Chicago",
      "658": "America/Chicago",
      "660": "America/Chicago",
      "661": "America/Chicago",
      "662": "America/Chicago",
      "664": "America/Chicago",
      "665": "America/Chicago",
      "666": "America/Chicago",
      "667": "America/Chicago",
      "668": "America/Chicago",
      "669": "America/Chicago",
      "670": "America/Chicago",
      "671": "America/Chicago",
      "672": "America/Chicago",
      "673": "America/Chicago",
      "674": "America/Chicago",
      "675": "America/Chicago",
      "676": "America/Chicago",
      "677": "America/Chicago",
      "678": "America/Chicago",
      "679": "America/Chicago",
      "680": "America/Chicago",
      "681": "America/Chicago",
      "683": "America/Chicago",
      "684": "America/Chicago",
      "685": "America/Chicago",
      "686": "America/Chicago",
      "687": "America/Chicago",
      "688": "America/Chicago",
      "689": "America/Chicago",
      "690": "America/Chicago",
      "691": "America/Chicago",
      "692": "America/Chicago",
      "693": "America/Chicago",
      "700": "America/Chicago",
      "701": "America/Chicago",
      "703": "America/Chicago",
      "704": "America/Chicago",
      "705": "America/Chicago",
      "706": "America/Chicago",
      "707": "America/Chicago",
      "708": "America/Chicago",
      "710": "America/Chicago",
      "711": "America/Chicago",
      "712": "America/Chicago",
      "713": "America/Chicago",
      "714": "America/Chicago",
      "716": "America/Chicago",
      "717": "America/Chicago",
      "718": "America/Chicago",
      "719": "America/Chicago",
      "720": "America/Chicago",
      "721": "America/Chicago",
      "722": "America/Chicago",
      "723": "America/Chicago",
      "724": "America/Chicago",
      "725": "America/Chicago",
      "726": "America/Chicago",
      "727": "America/Chicago",
      "728": "America/Chicago",
      "729": "America/Chicago",
      "730": "America/Chicago",
      "731": "America/Chicago",
      "733": "America/Chicago",
      "734": "America/Chicago",
      "735": "America/Chicago",
      "736": "America/Chicago",
      "737": "America/Chicago",
      "738": "America/Chicago",
      "739": "America/Chicago",
      "740": "America/Chicago",
      "741": "America/Chicago",
      "743": "America/Chicago",
      "744": "America/Chicago",
      "745": "America/Chicago",
      "746": "America/Chicago",
      "747": "America/Chicago",
      "748": "America/Chicago",
      "749": "America/Chicago",
      "750": "America/Chicago",
      "751": "America/Chicago",
      "752": "America/Chicago",
      "753": "America/Chicago",
      "754": "America/Chicago",
      "755": "America/Chicago",
      "756": "America/Chicago",
      "757": "America/Chicago",
      "758": "America/Chicago",
      "759": "America/Chicago",
      "760": "America/Chicago",
      "761": "America/Chicago",
      "762": "America/Chicago",
      "763": "America/Chicago",
      "764": "America/Chicago",
      "765": "America/Chicago",
      "766": "America/Chicago",
      "767": "America/Chicago",
      "768": "America/Chicago",
      "769": "America/Chicago",
      "770": "America/Chicago",
      "772": "America/Chicago",
      "773": "America/Chicago",
      "774": "America/Chicago",
      "775": "America/Chicago",
      "776": "America/Chicago",
      "777": "America/Chicago",
      "778": "America/Chicago",
      "779": "America/Chicago",
      "780": "America/Chicago",
      "781": "America/Chicago",
      "782": "America/Chicago",
      "783": "America/Chicago",
      "784": "America/Chicago",
      "785": "America/Chicago",
      "786": "America/Chicago",
      "787": "America/Chicago",
      "788": "America/Chicago",
      "789": "America/Chicago",
      "790": "America/Chicago",
      "791": "America/Chicago",
      "792": "America/Chicago",
      "793": "America/Chicago",
      "794": "America/Chicago",
      "795": "America/Chicago",
      "796": "America/Chicago",
      "797": "America/Chicago",
      "798": "America/Chicago",
      "799": "America/Chicago",
      // Mountain Time (MT)
      "590": "America/Denver",
      "591": "America/Denver",
      "592": "America/Denver",
      "593": "America/Denver",
      "594": "America/Denver",
      "595": "America/Denver",
      "596": "America/Denver",
      "597": "America/Denver",
      "598": "America/Denver",
      "599": "America/Denver",
      "800": "America/Denver",
      "801": "America/Denver",
      "802": "America/Denver",
      "803": "America/Denver",
      "804": "America/Denver",
      "805": "America/Denver",
      "806": "America/Denver",
      "807": "America/Denver",
      "808": "America/Denver",
      "809": "America/Denver",
      "810": "America/Denver",
      "811": "America/Denver",
      "812": "America/Denver",
      "813": "America/Denver",
      "814": "America/Denver",
      "815": "America/Denver",
      "816": "America/Denver",
      "820": "America/Denver",
      "821": "America/Denver",
      "822": "America/Denver",
      "823": "America/Denver",
      "824": "America/Denver",
      "825": "America/Denver",
      "826": "America/Denver",
      "827": "America/Denver",
      "828": "America/Denver",
      "829": "America/Denver",
      "830": "America/Denver",
      "831": "America/Denver",
      "832": "America/Denver",
      "833": "America/Denver",
      "834": "America/Denver",
      "835": "America/Denver",
      "836": "America/Denver",
      "837": "America/Denver",
      "838": "America/Denver",
      "840": "America/Denver",
      "841": "America/Denver",
      "842": "America/Denver",
      "843": "America/Denver",
      "844": "America/Denver",
      "845": "America/Denver",
      "846": "America/Denver",
      "847": "America/Denver",
      "850": "America/Phoenix",
      "851": "America/Phoenix",
      "852": "America/Phoenix",
      "853": "America/Phoenix",
      "855": "America/Phoenix",
      "856": "America/Phoenix",
      "857": "America/Phoenix",
      "858": "America/Phoenix",
      "859": "America/Phoenix",
      "860": "America/Phoenix",
      "863": "America/Phoenix",
      "864": "America/Phoenix",
      "865": "America/Phoenix",
      "870": "America/Denver",
      "871": "America/Denver",
      "873": "America/Denver",
      "874": "America/Denver",
      "875": "America/Denver",
      "877": "America/Denver",
      "878": "America/Denver",
      "879": "America/Denver",
      "880": "America/Denver",
      "881": "America/Denver",
      "882": "America/Denver",
      "883": "America/Denver",
      "884": "America/Denver",
      // Pacific Time (PT)
      "889": "America/Los_Angeles",
      "890": "America/Los_Angeles",
      "891": "America/Los_Angeles",
      "893": "America/Los_Angeles",
      "894": "America/Los_Angeles",
      "895": "America/Los_Angeles",
      "897": "America/Los_Angeles",
      "898": "America/Los_Angeles",
      "900": "America/Los_Angeles",
      "901": "America/Los_Angeles",
      "902": "America/Los_Angeles",
      "903": "America/Los_Angeles",
      "904": "America/Los_Angeles",
      "905": "America/Los_Angeles",
      "906": "America/Los_Angeles",
      "907": "America/Los_Angeles",
      "908": "America/Los_Angeles",
      "910": "America/Los_Angeles",
      "911": "America/Los_Angeles",
      "912": "America/Los_Angeles",
      "913": "America/Los_Angeles",
      "914": "America/Los_Angeles",
      "915": "America/Los_Angeles",
      "916": "America/Los_Angeles",
      "917": "America/Los_Angeles",
      "918": "America/Los_Angeles",
      "919": "America/Los_Angeles",
      "920": "America/Los_Angeles",
      "921": "America/Los_Angeles",
      "922": "America/Los_Angeles",
      "923": "America/Los_Angeles",
      "924": "America/Los_Angeles",
      "925": "America/Los_Angeles",
      "926": "America/Los_Angeles",
      "927": "America/Los_Angeles",
      "928": "America/Los_Angeles",
      "930": "America/Los_Angeles",
      "931": "America/Los_Angeles",
      "932": "America/Los_Angeles",
      "933": "America/Los_Angeles",
      "934": "America/Los_Angeles",
      "935": "America/Los_Angeles",
      "936": "America/Los_Angeles",
      "937": "America/Los_Angeles",
      "938": "America/Los_Angeles",
      "939": "America/Los_Angeles",
      "940": "America/Los_Angeles",
      "941": "America/Los_Angeles",
      "942": "America/Los_Angeles",
      "943": "America/Los_Angeles",
      "944": "America/Los_Angeles",
      "945": "America/Los_Angeles",
      "946": "America/Los_Angeles",
      "947": "America/Los_Angeles",
      "948": "America/Los_Angeles",
      "949": "America/Los_Angeles",
      "950": "America/Los_Angeles",
      "951": "America/Los_Angeles",
      "952": "America/Los_Angeles",
      "953": "America/Los_Angeles",
      "954": "America/Los_Angeles",
      "955": "America/Los_Angeles",
      "956": "America/Los_Angeles",
      "957": "America/Los_Angeles",
      "958": "America/Los_Angeles",
      "959": "America/Los_Angeles",
      "960": "America/Los_Angeles",
      "961": "America/Los_Angeles",
      "970": "America/Los_Angeles",
      "971": "America/Los_Angeles",
      "972": "America/Los_Angeles",
      "973": "America/Los_Angeles",
      "974": "America/Los_Angeles",
      "975": "America/Los_Angeles",
      "976": "America/Los_Angeles",
      "977": "America/Los_Angeles",
      "978": "America/Los_Angeles",
      "979": "America/Los_Angeles",
      "980": "America/Los_Angeles",
      "981": "America/Los_Angeles",
      "982": "America/Los_Angeles",
      "983": "America/Los_Angeles",
      "984": "America/Los_Angeles",
      "985": "America/Los_Angeles",
      "986": "America/Los_Angeles",
      "988": "America/Los_Angeles",
      "989": "America/Los_Angeles",
      "990": "America/Los_Angeles",
      "991": "America/Los_Angeles",
      "992": "America/Los_Angeles",
      "993": "America/Los_Angeles",
      "994": "America/Los_Angeles",
      // Alaska Time
      "995": "America/Anchorage",
      "996": "America/Anchorage",
      "997": "America/Anchorage",
      "998": "America/Anchorage",
      "999": "America/Anchorage",
      // Hawaii Time
      "967": "Pacific/Honolulu",
      "968": "Pacific/Honolulu"
    };
  }
});

// ../js/ui/themes/theme-registry.js
var theme_registry_exports = {};
__export(theme_registry_exports, {
  DEFAULT_THEME_FAMILY: () => DEFAULT_THEME_FAMILY,
  DEFAULT_THEME_ID: () => DEFAULT_THEME_ID,
  DEFAULT_THEME_MODE: () => DEFAULT_THEME_MODE,
  THEME_FAMILIES: () => THEME_FAMILIES,
  THEME_MODES: () => THEME_MODES,
  buildThemeId: () => buildThemeId,
  getAllThemeFamilies: () => getAllThemeFamilies,
  getAllThemes: () => getAllThemes,
  getDefaultTheme: () => getDefaultTheme,
  getSeasonalTheme: () => getSeasonalTheme,
  getSeasonalThemeFamily: () => getSeasonalThemeFamily,
  getTheme: () => getTheme,
  getThemeById: () => getThemeById,
  getThemeConfig: () => getThemeConfig,
  getThemeFamily: () => getThemeFamily,
  getThemeFamilyIds: () => getThemeFamilyIds,
  getThemeIds: () => getThemeIds,
  isValidTheme: () => isValidTheme,
  isValidThemeFamily: () => isValidThemeFamily,
  isValidThemeId: () => isValidThemeId,
  isValidThemeMode: () => isValidThemeMode,
  parseThemeId: () => parseThemeId
});
function getThemeFamilyIds() {
  return Object.keys(THEME_FAMILIES);
}
function getAllThemeFamilies() {
  return Object.values(THEME_FAMILIES);
}
function getThemeFamily(familyId) {
  return THEME_FAMILIES[familyId] || null;
}
function isValidThemeFamily(familyId) {
  return familyId in THEME_FAMILIES;
}
function isValidThemeMode(mode) {
  return THEME_MODES.includes(mode);
}
function buildThemeId(familyId, mode) {
  return `${familyId}-${mode}`;
}
function parseThemeId(themeId) {
  if (!themeId) return null;
  if (themeId === "light") {
    return { family: "default", mode: "light" };
  }
  if (themeId === "dark") {
    return { family: "default", mode: "dark" };
  }
  const lastDashIndex = themeId.lastIndexOf("-");
  if (lastDashIndex === -1) return null;
  const family = themeId.substring(0, lastDashIndex);
  const mode = themeId.substring(lastDashIndex + 1);
  if (!isValidThemeFamily(family) || !isValidThemeMode(mode)) {
    return null;
  }
  return { family, mode };
}
function getThemeConfig(familyId, mode) {
  const family = getThemeFamily(familyId);
  if (!family || !family.variants[mode]) {
    return null;
  }
  const variant = family.variants[mode];
  return {
    id: buildThemeId(familyId, mode),
    familyId,
    mode,
    name: `${family.name} ${mode.charAt(0).toUpperCase() + mode.slice(1)}`,
    familyName: family.name,
    cssClass: variant.cssClass,
    logoSrc: variant.logoSrc,
    seasonal: family.seasonal
  };
}
function getThemeById(themeId) {
  const parsed = parseThemeId(themeId);
  if (!parsed) return null;
  return getThemeConfig(parsed.family, parsed.mode);
}
function isValidThemeId(themeId) {
  return getThemeById(themeId) !== null;
}
function getSeasonalThemeFamily(month = null) {
  const timezone = getUserTimezone();
  const currentMonth = month ?? getMonthInTimezone(/* @__PURE__ */ new Date(), timezone) + 1;
  const seasonalFamily = getAllThemeFamilies().find(
    (family) => family.seasonal && family.seasonal.month === currentMonth
  );
  return seasonalFamily || null;
}
function getTheme(themeId) {
  return getThemeById(themeId);
}
function isValidTheme(themeId) {
  return isValidThemeId(themeId);
}
function getThemeIds() {
  const ids = [];
  getAllThemeFamilies().forEach((family) => {
    THEME_MODES.forEach((mode) => {
      ids.push(buildThemeId(family.id, mode));
    });
  });
  return ids;
}
function getAllThemes() {
  const themes = [];
  getAllThemeFamilies().forEach((family) => {
    THEME_MODES.forEach((mode) => {
      const theme = getThemeConfig(family.id, mode);
      if (theme) themes.push(theme);
    });
  });
  return themes;
}
function getDefaultTheme() {
  return getThemeConfig(DEFAULT_THEME_FAMILY, DEFAULT_THEME_MODE);
}
function getSeasonalTheme(month = null) {
  const seasonalFamily = getSeasonalThemeFamily(month);
  if (!seasonalFamily) return null;
  return getThemeConfig(seasonalFamily.id, "dark");
}
var THEME_FAMILIES, THEME_MODES, DEFAULT_THEME_FAMILY, DEFAULT_THEME_MODE, DEFAULT_THEME_ID;
var init_theme_registry = __esm({
  "../js/ui/themes/theme-registry.js"() {
    init_timezone_helper();
    THEME_FAMILIES = {
      default: {
        id: "default",
        name: "Default",
        variants: {
          light: {
            cssClass: "theme-light",
            logoSrc: "/artwork/Dashie_Full_Logo_Black_Transparent.png"
          },
          dark: {
            cssClass: "theme-dark",
            logoSrc: "/artwork/Dashie_Full_Logo_White_Transparent.png"
          }
        }
      },
      halloween: {
        id: "halloween",
        name: "Halloween",
        seasonal: { month: 10 },
        // Auto-activate in October
        variants: {
          light: {
            cssClass: "theme-halloween-light",
            logoSrc: "/artwork/Dashie_Full_Logo_Black_Transparent.png"
          },
          dark: {
            cssClass: "theme-halloween-dark",
            logoSrc: "/artwork/Dashie_Full_Logo_White_Transparent.png"
          }
        }
      },
      christmas: {
        id: "christmas",
        name: "Christmas",
        seasonal: { month: 12 },
        // Auto-activate in December
        variants: {
          light: {
            cssClass: "theme-christmas-light",
            logoSrc: "/artwork/Dashie_Full_Logo_Black_Transparent.png"
          },
          dark: {
            cssClass: "theme-christmas-dark",
            logoSrc: "/artwork/Dashie_Full_Logo_White_Transparent.png"
          }
        }
      }
    };
    THEME_MODES = ["light", "dark"];
    DEFAULT_THEME_FAMILY = "default";
    DEFAULT_THEME_MODE = "light";
    DEFAULT_THEME_ID = "default-light";
  }
});

// ../js/ui/brightness-slider.js
function createBrightnessSliderContent(options = {}) {
  const {
    onBrightnessChange,
    onActivity,
    showThemeToggle = true,
    resolveIconUrl = (path) => cache_buster_shim_default.bustUrl(path)
  } = options;
  const hasPermission = AndroidBridge.canWriteSettings();
  let currentBrightness = 5;
  try {
    if (AndroidBridge.isAvailable()) {
      currentBrightness = AndroidBridge.getBrightness() || 5;
    }
  } catch (e) {
    logger4.warn("Failed to get brightness state", e);
  }
  const isDarkMode = showThemeToggle ? theme_applier_shim_default.isDarkTheme() : true;
  const themeButtonText = isDarkMode ? "Light" : "Dark";
  const disabledClass = !hasPermission ? "brightness-slider--disabled" : "";
  const permissionMessage = !hasPermission ? `
    <div class="brightness-slider-permission-msg">
      <span>Permission required.</span>
      <button class="brightness-slider-grant-btn">Grant in Settings</button>
    </div>
  ` : "";
  let rightButtonHTML;
  if (showThemeToggle) {
    rightButtonHTML = `
      <button class="brightness-slider-theme-btn" data-dark="${isDarkMode}" title="Switch to ${themeButtonText} mode">
        <img src="${resolveIconUrl("/artwork/icon-light-dark-mode.png")}"
             alt="Toggle theme"
             class="brightness-slider-theme-icon">
        <span class="brightness-slider-theme-text">
          <span class="brightness-slider-theme-label">Switch to</span>
          <span class="brightness-slider-theme-mode">${themeButtonText}</span>
        </span>
      </button>
    `;
  } else {
    rightButtonHTML = `
      <div class="brightness-slider-icon-btn">
        <img class="brightness-slider-icon" src="${resolveIconUrl("/artwork/icon-sun.svg")}" alt="Brightness">
      </div>
    `;
  }
  const container = document.createElement("div");
  container.className = `brightness-slider-container ${disabledClass}`;
  container.innerHTML = `
    ${permissionMessage}
    <div class="brightness-slider-row">
      <div class="brightness-slider-track-column">
        <div class="brightness-slider-track">
          <div class="brightness-slider-fill" style="width: ${currentBrightness * 10}%"></div>
          <input type="range"
                 class="brightness-slider-input"
                 min="0"
                 max="10"
                 value="${currentBrightness}"
                 ${!hasPermission ? "disabled" : ""}>
        </div>
        <div class="brightness-slider-controls">
          <button class="brightness-slider-step-btn brightness-slider-minus-btn" aria-label="Decrease brightness" ${!hasPermission ? "disabled" : ""}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
          </button>
          <span class="brightness-slider-level">${currentBrightness}</span>
          <button class="brightness-slider-step-btn brightness-slider-plus-btn" aria-label="Increase brightness" ${!hasPermission ? "disabled" : ""}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
          </button>
        </div>
      </div>
      ${rightButtonHTML}
    </div>
  `;
  const slider = container.querySelector(".brightness-slider-input");
  const fill = container.querySelector(".brightness-slider-fill");
  const levelDisplay = container.querySelector(".brightness-slider-level");
  const minusBtn = container.querySelector(".brightness-slider-minus-btn");
  const plusBtn = container.querySelector(".brightness-slider-plus-btn");
  const themeBtn = container.querySelector(".brightness-slider-theme-btn");
  const themeModeText = container.querySelector(".brightness-slider-theme-mode");
  const grantBtn = container.querySelector(".brightness-slider-grant-btn");
  const updateBrightnessUI = (value) => {
    slider.value = value;
    fill.style.width = `${value * 10}%`;
    levelDisplay.textContent = value;
  };
  const setBrightnessAndNotify = (value) => {
    try {
      if (AndroidBridge.isAvailable()) {
        AndroidBridge.setBrightness(value);
        logger4.info("Brightness set", { level: value });
      }
    } catch (err) {
      logger4.error("Failed to set brightness", err);
    }
    updateBrightnessUI(value);
    onBrightnessChange?.(value);
  };
  const handleSliderInput = (e) => {
    onActivity?.();
    const value = parseInt(e.target.value, 10);
    fill.style.width = `${value * 10}%`;
    levelDisplay.textContent = value;
  };
  const handleSliderChange = (e) => {
    onActivity?.();
    setBrightnessAndNotify(parseInt(e.target.value, 10));
  };
  const handleMinusClick = () => {
    onActivity?.();
    const currentValue = parseInt(slider.value, 10);
    if (currentValue > 0) setBrightnessAndNotify(currentValue - 1);
  };
  const handlePlusClick = () => {
    onActivity?.();
    const currentValue = parseInt(slider.value, 10);
    if (currentValue < 10) setBrightnessAndNotify(currentValue + 1);
  };
  const handleThemeClick = async () => {
    onActivity?.();
    try {
      const currentTheme = theme_applier_shim_default.getCurrentTheme();
      const { parseThemeId: parseThemeId2, buildThemeId: buildThemeId2 } = await Promise.resolve().then(() => (init_theme_registry(), theme_registry_exports));
      const parsedCurrent = parseThemeId2(currentTheme);
      const currentFamily = parsedCurrent?.family || "default";
      const currentMode = parsedCurrent?.mode || "light";
      const newMode = currentMode === "light" ? "dark" : "light";
      const newTheme = buildThemeId2(currentFamily, newMode);
      const newIsDark = newMode === "dark";
      if (themeBtn) themeBtn.dataset.dark = newIsDark ? "true" : "false";
      if (themeModeText) themeModeText.textContent = newIsDark ? "Light" : "Dark";
      theme_applier_shim_default.applyTheme(newTheme, true);
      if (window.settingsStore) {
        window.settingsStore.set("interface.themeMode", newMode);
        window.settingsStore.set("interface.theme", newTheme);
        await window.settingsStore.save();
      }
      logger4.info("Theme toggled and saved", { currentTheme, newTheme, family: currentFamily });
    } catch (err) {
      logger4.error("Failed to toggle theme", err);
    }
  };
  const handleGrantClick = () => {
    onActivity?.();
    logger4.info("Opening settings to grant brightness permission");
    AndroidBridge.requestWriteSettingsPermission();
  };
  slider.addEventListener("input", handleSliderInput);
  slider.addEventListener("change", handleSliderChange);
  minusBtn.addEventListener("click", handleMinusClick);
  plusBtn.addEventListener("click", handlePlusClick);
  if (themeBtn) themeBtn.addEventListener("click", handleThemeClick);
  if (grantBtn) grantBtn.addEventListener("click", handleGrantClick);
  return {
    element: container,
    updateBrightness: updateBrightnessUI,
    destroy() {
      slider.removeEventListener("input", handleSliderInput);
      slider.removeEventListener("change", handleSliderChange);
      minusBtn.removeEventListener("click", handleMinusClick);
      plusBtn.removeEventListener("click", handlePlusClick);
      if (themeBtn) themeBtn.removeEventListener("click", handleThemeClick);
      if (grantBtn) grantBtn.removeEventListener("click", handleGrantClick);
    }
  };
}
var logger4;
var init_brightness_slider = __esm({
  "../js/ui/brightness-slider.js"() {
    init_logger_shim();
    init_android_bridge();
    init_theme_applier_shim();
    init_cache_buster_shim();
    logger4 = createLogger("BrightnessSlider");
  }
});

// js/dash-menu/dash-menu-brightness-popout.js
function createBrightnessPopout(onActivity) {
  const result = createBrightnessSliderContent({
    onActivity,
    showThemeToggle: false,
    resolveIconUrl: (path) => path.replace(/^\//, "")
  });
  const popup = document.createElement("div");
  popup.className = "brightness-slider-popup";
  popup.appendChild(result.element);
  return {
    element: popup,
    popoutWidth: 280,
    destroy: () => result.destroy()
  };
}
var init_dash_menu_brightness_popout = __esm({
  "js/dash-menu/dash-menu-brightness-popout.js"() {
    init_brightness_slider();
  }
});

// js/dash-menu/dash-menu-hamburger-popout.js
function createHamburgerPopout(isLocked, onDismiss) {
  const container = document.createElement("div");
  container.className = "hamburger-popout-menu";
  const items = isLocked ? [
    { id: "unlock", label: "Unlock", iconPath: POPOUT_ICONS.unlock }
  ] : [
    { id: "settings", label: "Settings", iconPath: POPOUT_ICONS.settings },
    { id: "sleep", label: "Sleep", iconPath: POPOUT_ICONS.sleep },
    { id: "reload", label: "Reload", iconPath: POPOUT_ICONS.reload }
  ];
  container.innerHTML = items.map((item, index) => `
    <button class="hamburger-menu-item" data-menu-id="${item.id}" data-menu-index="${index}">
      <img class="hamburger-menu-icon" src="${item.iconPath}" alt="${item.label}">
      <span class="hamburger-menu-label">${item.label}</span>
    </button>
  `).join("");
  container.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-menu-id]");
    if (!btn) return;
    const action = btn.dataset.menuId;
    try {
      switch (action) {
        case "unlock":
          DashieNative.showLockDialog();
          break;
        case "settings":
          DashieNative.openSettings();
          break;
        case "sleep":
          DashieNative.sleepNow();
          break;
        case "reload":
          DashieNative.reloadDashboard();
          break;
      }
    } catch (e2) {
      console.warn("[DashMenu] Bridge call failed:", action, e2);
    }
    onDismiss?.();
  });
  return {
    element: container,
    popoutWidth: 180,
    destroy() {
    }
  };
}
var init_dash_menu_hamburger_popout = __esm({
  "js/dash-menu/dash-menu-hamburger-popout.js"() {
    init_dash_menu_items();
  }
});

// js/dash-menu/dash-menu.js
var require_dash_menu = __commonJS({
  "js/dash-menu/dash-menu.js"() {
    init_dash_menu_renderer();
    init_dash_menu_volume_popout();
    init_dash_menu_brightness_popout();
    init_dash_menu_hamburger_popout();
    var LOCK_POLL_INTERVAL_MS = 2e3;
    var AUTO_HIDE_MS = 1e4;
    var DashMenuController = class {
      constructor() {
        this.renderer = new DashMenuRenderer();
        this.isLocked = false;
        this.lockPollInterval = null;
        this.autoHideTimeout = null;
      }
      initialize() {
        console.log("[DashMenu] Initializing...");
        try {
          this.isLocked = DashieNative.isAppLocked();
        } catch (e) {
          console.warn("[DashMenu] Could not read lock state:", e);
        }
        this.renderer.buildStrip();
        this.renderer.setLockState(this.isLocked);
        this.renderer.onItemClick = (itemId) => this.handleItemClick(itemId);
        this.renderer.onPopoutDismiss = () => this.clearAutoHide();
        this.startLockPolling();
        this.refreshVolumeIcon();
        window.parent.postMessage({
          source: "dash-menu",
          type: "ready"
        }, "*");
        console.log("[DashMenu] Initialized (locked:", this.isLocked, ")");
      }
      handleItemClick(itemId) {
        if (this.isLocked && itemId !== "hamburger") return;
        switch (itemId) {
          case "ha-dashboard":
            break;
          case "music":
            try {
              DashieNative.toggleMusicPlayer();
            } catch (e) {
              console.warn("[DashMenu] toggleMusicPlayer failed:", e);
            }
            break;
          case "volume":
            this.togglePopout("volume", () => createVolumePopout(() => this.resetAutoHide()));
            break;
          case "brightness":
            this.togglePopout("brightness", () => createBrightnessPopout(() => this.resetAutoHide()));
            break;
          case "hamburger":
            this.togglePopout(
              "hamburger",
              () => createHamburgerPopout(this.isLocked, () => this.dismissPopout())
            );
            break;
        }
      }
      /**
       * Toggle a popout: if already showing this one, close it. Otherwise open it.
       */
      togglePopout(id, factory) {
        if (this.renderer.activePopoutId === id) {
          this.dismissPopout();
          return;
        }
        this.clearAutoHide();
        const popout = factory();
        this.renderer.showPopout(id, popout);
        this.startAutoHide();
      }
      dismissPopout() {
        this.clearAutoHide();
        this.renderer.hidePopout();
      }
      startAutoHide() {
        this.clearAutoHide();
        this.autoHideTimeout = setTimeout(() => {
          this.dismissPopout();
        }, AUTO_HIDE_MS);
      }
      resetAutoHide() {
        if (this.autoHideTimeout) {
          this.startAutoHide();
        }
      }
      clearAutoHide() {
        if (this.autoHideTimeout) {
          clearTimeout(this.autoHideTimeout);
          this.autoHideTimeout = null;
        }
      }
      startLockPolling() {
        this.lockPollInterval = setInterval(() => {
          try {
            const locked = DashieNative.isAppLocked();
            if (locked !== this.isLocked) {
              this.isLocked = locked;
              this.renderer.setLockState(locked);
              if (locked) this.dismissPopout();
              console.log("[DashMenu] Lock state changed:", locked);
            }
          } catch (e) {
          }
        }, LOCK_POLL_INTERVAL_MS);
      }
      refreshVolumeIcon() {
        try {
          const muted = DashieNative.isMuted();
          this.renderer.updateVolumeIcon(muted);
        } catch (e) {
        }
      }
    };
    var controller = new DashMenuController();
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", () => controller.initialize());
    } else {
      controller.initialize();
    }
  }
});
export default require_dash_menu();
