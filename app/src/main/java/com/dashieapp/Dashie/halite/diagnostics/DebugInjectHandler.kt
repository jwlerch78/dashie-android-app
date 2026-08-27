package com.dashieapp.Dashie.halite.diagnostics

import android.content.Intent
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.DashboardHealthCoordinator
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * DEBUG-only handler for [DebugTestInjector] events. Extracted from MainActivity
 * to keep the activity within its file-size budget (the "keep MainActivity
 * small" rule). No-op in release (the injector is never
 * registered there).
 *
 * Events:
 * - iframe_error / iframe_healthy / screen_sleep / screen_wake — drive the
 *   DashboardHealthCoordinator recovery state machine.
 * - recreate — force a WebView recreation (memory-pressure path).
 * - dump — log current health state.
 * - storage_probe — write a synthetic key into the HA iframe localStorage,
 *   reload in place, read it back (proves storage persists across reload).
 * - dump_haauth — read HA's in-memory auth (home-assistant.hass.auth.data) vs
 *   localStorage.hassTokens. Reveals the "authenticated but not persisted" case
 *   (HA keeps tokens in memory when writeEnabled=false) and the mint origin.
 * - set_hasstokens — write a caller-supplied (or buildHassTokensJson-derived)
 *   hassTokens blob into the iframe and reload, isolating "is the token/origin
 *   valid?" from "does the login flow inject?". JSON via `--es json '...'`.
 * - install_stt_model / uninstall_stt_model — drive the on-demand STT model
 *   download headlessly, with optional url/sha256 overrides so the 404 and
 *   digest-mismatch failure paths are reachable without editing the registry.
 */
object DebugInjectHandler {
    private const val TAG = "DebugInjectHandler"

    fun handle(
        event: String,
        intent: Intent,
        webView: WebView,
        halitePrefs: HalitePreferences?,
        coordinator: DashboardHealthCoordinator?,
        onRecreate: () -> Unit,
        screensaver: ((active: Boolean) -> Unit)? = null,
        paintCheck: ((label: String) -> Unit)? = null,
        onStealthReload: (() -> Unit)? = null
    ) {
        when (event) {
            // Simulate a SILENT shell death — the fault class no other inject covers.
            // Deleting the kiosk-shell global makes evalInHaContext fall through to
            // 'no-ha' (the main frame has no <home-assistant> either), with NO
            // navigation and therefore NO liveness-verify — which is exactly how the
            // field case presents: diagnostics report e4403639 has 2488 no-ha drops
            // and not one PAGELOAD or HealthCoordinator line.
            //
            // Must be evaluateJavascript, NOT loadUrl("javascript:…"): the latter is
            // ignored by modern WebView (measured on SM-X200, 2026-08-17), which is
            // why the first repro attempt silently did nothing.
            //
            // Recovery is a page reload, so this is self-healing: shell_break →
            // screenOff → the keepalive streak should trip and the coordinator should
            // reload the page, restoring the global.
            "shell_break" -> {
                webView.evaluateJavascript(
                    "(function(){try{delete window.evalInHaIframe;return 'deleted:'+(typeof window.evalInHaIframe);}catch(e){return 'err:'+e.message}})()"
                ) { result ->
                    PersistentLog.warn("RIG", "shell_break: evalInHaIframe now $result")
                }
            }
            "iframe_error" -> coordinator?.onIframeError("test://inject")
            "iframe_healthy" -> coordinator?.onIframeHealthy()
            "screen_sleep" -> coordinator?.onScreenSleepChanged(true)
            "screen_wake" -> coordinator?.onScreenSleepChanged(false)
            "recreate" -> onRecreate()
            // Fire the REAL stealth-reload path on demand (the "scheduled_refresh"
            // CRITICAL recreate MainMemoryManager runs from the alarm) — bypasses the
            // 15-min interval floor + 10-min throttle so the rig can drive the asleep
            // recreate in seconds. Drive screensaver_on FIRST so it recreates while
            // "asleep" (the Teclast overnight scenario). Same reason string his log shows.
            "stealth_reload_now" -> {
                if (onStealthReload != null) { onStealthReload(); PersistentLog.info("RIG", "stealth_reload_now") }
                else PersistentLog.warn("RIG", "stealth_reload_now: no handler wired")
            }
            "dump" -> PersistentLog.info("HEALTH", "dump: state=${coordinator?.currentHealth()}")
            // Real screensaver drive (the actual ScreenDimmer, not the logical
            // coordinator state) — for deterministic blank-on-reveal rig tests.
            "screensaver_on" -> { screensaver?.invoke(true); PersistentLog.info("RIG", "screensaver_on") }
            "screensaver_off" -> { screensaver?.invoke(false); PersistentLog.info("RIG", "screensaver_off") }
            // One-shot paint oracle: logs PAINTED/BLANK for the current dashboard.
            "paint_check" -> paintCheck?.invoke(intent.getStringExtra("label") ?: "manual")
            // Settings test-rig primitive (DEBUG only): write a synced setting through
            // the REAL schema value-provider setter — the exact path the native UI uses —
            // WITHOUT firing the item's onChanged. That's the census one-sided-write shape:
            // it proves the Phase 2 SettingsSyncNotifier alone carries the value to the
            // cloud. The household simulator (test-rig/household-sim.sh) drives it via:
            //   am broadcast -a com.dashieapp.Dashie.TEST_INJECT --es event set_setting \
            //     --es key screensaver.showDate --es type bool --es value true
            "set_setting" -> {
                val key = intent.getStringExtra("key")
                val type = intent.getStringExtra("type") ?: "string"
                val value = intent.getStringExtra("value")
                if (key == null || value == null) {
                    PersistentLog.warn("RIG", "set_setting: missing key/value extra")
                } else {
                    try {
                        val vp = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring
                            .create(webView.context).valueProvider
                        when (type) {
                            "bool" -> vp.setBoolean(key, value.toBoolean())
                            "int"  -> vp.setInt(key, value.toInt())
                            else   -> vp.setString(key, value)
                        }
                        PersistentLog.info("RIG", "set_setting $key=$value ($type)")
                    } catch (e: Exception) {
                        PersistentLog.warn("RIG", "set_setting $key failed: ${e.message}")
                    }
                }
            }
            // Promo/screenshot mode for the voice conversation transcript
            // (VoicePromoMode): chat order (newest at bottom) + no dimming of prior
            // turns. Set on the promo tablet, clear after the screenshot:
            //   am broadcast -a com.dashieapp.Dashie.TEST_INJECT --es event promo_mode --es value true
            "promo_mode" -> {
                val enabled = intent.getStringExtra("value")?.toBoolean() ?: false
                com.dashieapp.Dashie.halite.voice.VoicePromoMode.set(webView.context, enabled)
                PersistentLog.info("RIG", "promo_mode=$enabled")
            }
            // Relay an app-internal broadcast from INSIDE the app process (DEBUG
            // rig primitive): runtime receivers are RECEIVER_NOT_EXPORTED on
            // API 33+, so `adb shell am broadcast -a <action>` never reaches
            // them — this relays with setPackage, exactly how the settings
            // callbacks send. Restricted to our own action namespace.
            //   am broadcast -a com.dashieapp.Dashie.TEST_INJECT --es event send_broadcast \
            //     --es action com.dashieapp.Dashie.ACTION_HA_DISPLAY_CHANGED
            "send_broadcast" -> {
                val action = intent.getStringExtra("action")
                if (action.isNullOrBlank() || !action.startsWith("com.dashieapp.Dashie.")) {
                    PersistentLog.warn("RIG", "send_broadcast: missing/foreign action '$action'")
                } else {
                    webView.context.sendBroadcast(Intent(action).apply {
                        setPackage(webView.context.packageName)
                    })
                    PersistentLog.info("RIG", "send_broadcast $action")
                }
            }
            // ── On-demand STT model install (rig primitive, T cont.44) ──────────────────
            // Until now SttModelInstaller.install had exactly ONE caller — the settings
            // dialog — so every download probe meant blind d-pad driving of native UI.
            // This makes the lane headless and repeatable, per standing rule 3's
            // "drive it via the API" pattern.
            //
            //   am broadcast -a com.dashieapp.Dashie.TEST_INJECT --es event install_stt_model \
            //     --es family moonshine-base
            //
            // ⭐ The url/sha256 OVERRIDES are the point, not a convenience: T's three
            // remaining probes are mid-download interrupt, upstream-404 and live digest
            // mismatch, and the last two CANNOT be induced by a family id alone — the
            // registry's real url and pin always succeed. So:
            //   404            → --es url https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/nope.tar.bz2
            //   digest mismatch→ --es sha256 0000000000000000000000000000000000000000000000000000000000000000
            //   interrupt      → start it, then force-stop / drop wifi mid-transfer
            // A hook that could only drive the happy path would have left two of the
            // three probes exactly where they were.
            //
            // Reset between runs with `uninstall_stt_model`; read installed state from
            // `?cmd=voiceEngines` (which reports modelUsable, i.e. bundled OR downloaded)
            // rather than duplicating a status event here.
            "install_stt_model", "uninstall_stt_model" -> {
                val raw = intent.getStringExtra("family") ?: ""
                // Forgiving on purpose — a rig should accept the id the log prints, the id
                // with underscores, or the settings provider value.
                val family = com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry
                    .byId(raw.replace('_', '-'))
                    ?: com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry.byProviderValue(raw)
                if (family == null) {
                    val known = com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry
                        .FAMILIES.joinToString { it.id }
                    PersistentLog.warn("RIG", "DROP: [unexpected] stt_install: unknown family " +
                        "'$raw' — no download started. Known: $known")
                } else if (event == "uninstall_stt_model") {
                    val gone = com.dashieapp.Dashie.halite.voice.stt.SttModelInstaller
                        .uninstall(webView.context, family)
                    PersistentLog.info("RIG", "stt_uninstall ${family.id} removed=$gone")
                } else {
                    // Overrides applied to a COPY: the registry itself is never mutated, so a
                    // negative probe cannot leave a poisoned pin behind for the next test.
                    val target = family.copy(
                        url = intent.getStringExtra("url") ?: family.url,
                        archiveSha256 = intent.getStringExtra("sha256") ?: family.archiveSha256,
                    )
                    val overridden = (target.url != family.url) || (target.archiveSha256 != family.archiveSha256)
                    val force = intent.getStringExtra("force")?.toBoolean() ?: false
                    val already = com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry
                        .isInstalled(webView.context, family)
                    // 🔴 Caught by driving this on the Fire, not by reading it: install() returns
                    // Result.Installed IMMEDIATELY when the family is already present, so a
                    // negative probe against an installed family "passes" in 40 ms without ever
                    // fetching a byte — the override is silently never exercised. Correct API
                    // semantics, ruinous as a test signal, because Installed-because-already-there
                    // and Installed-because-downloaded are indistinguishable in the log.
                    // So the rig says which, and `--es force true` clears first.
                    if (already && !force) {
                        PersistentLog.warn("RIG", "DROP: [expected] stt_install ${target.id} " +
                            "NO-OP — already installed, so nothing was downloaded and any " +
                            "url/sha override was NOT exercised. Re-run with --es force true, " +
                            "or send uninstall_stt_model first.")
                        return
                    }
                    if (force && already) {
                        com.dashieapp.Dashie.halite.voice.stt.SttModelInstaller
                            .uninstall(webView.context, family)
                        PersistentLog.info("RIG", "stt_install ${target.id} force: cleared existing install")
                    }
                    PersistentLog.info("RIG", "stt_install ${target.id} start " +
                        "(~${target.approxMb}MB${if (overridden) ", OVERRIDDEN url/sha" else ""})")
                    // Network on the main thread would throw; the broadcast arrives on it.
                    Thread({
                        var lastDecile = -1
                        val r = com.dashieapp.Dashie.halite.voice.stt.SttModelInstaller
                            .install(webView.context.applicationContext, target) { soFar, total ->
                                if (total <= 0) return@install
                                val decile = ((soFar * 10) / total).toInt()
                                if (decile == lastDecile) return@install
                                lastDecile = decile
                                // Deciles, not percents: enough to time an interrupt, few
                                // enough that the log stays readable for a 135 MB fetch.
                                PersistentLog.info("RIG", "stt_install ${target.id} ${decile * 10}%")
                            }
                        PersistentLog.info("RIG", "stt_install ${target.id} RESULT=$r")
                    }, "rig-stt-install-${target.id}").start()
                }
            }
            // Force a real HA WebSocket drop / staleness via the in-iframe debug fns.
            "ws_close" -> {
                webView.evaluateJavascript(
                    "window.evalInHaIframe && window.evalInHaIframe('window.forceWebSocketClose&&window.forceWebSocketClose()')", null)
                PersistentLog.info("RIG", "ws_close injected")
            }
            "ws_stale" -> {
                webView.evaluateJavascript(
                    "window.evalInHaIframe && window.evalInHaIframe('window.simulateStaleConnection&&window.simulateStaleConnection(31)')", null)
                PersistentLog.info("RIG", "ws_stale injected (31min)")
            }
            // Reproduce the Cloudflare "WS proxy missed interception" recovery bug:
            // promoted to 'connected' with NO proxied socket (state=UNKNOWN), then HA
            // frontend goes disconnected. Pre-fix this stalls forever; post-fix the
            // monitor must auto-reload off HA's frontend state. (websocket-monitor.js
            // checkStaleForAutoReload; harness test in test-rig/js/.)
            "ws_stale_nosocket" -> {
                // On a healthy device WS interception SUCCEEDS and HA is up, so the
                // bug can't occur naturally — we synthesize its exact state. _simulationFrozen
                // stops live HA messages/pongs (whose handlers are still attached to the real
                // socket) from resetting the staleness timer, and a getter forces the frontend
                // to read 'disconnected' so the no-socket recovery branch must act.
                val inner = "(function(){var h=window._dashieWsHealth,p=window._dashiePingKeepAlive;" +
                    "if(!h||!p){console.log(\"[RIG] ws monitor absent\");return \"no-monitor\";}" +
                    "window._dashieHaWebSocket=null;" +              // interception missed (Cloudflare)
                    "h.currentState=\"connected\";" +                // promoted off frontend state
                    "h._simulationFrozen=true;" +                    // freeze live msg/pong tracking
                    "p.enabled=true;" +                              // canDetectStale
                    "var a=window._dashieAutoReload;if(a){a._60sReported=false;a.softReconnectAttempted=false;a.lastReloadAttempt=0;}" +
                    "h.lastMessageTime=Date.now()-300000;" +         // 5 min stale (past 3-min threshold)
                    "var e=document.querySelector(\"home-assistant\");" +
                    "if(e&&e.hass&&e.hass.connection){" +
                    "try{Object.defineProperty(e.hass.connection,\"connected\",{get:function(){return false;},configurable:true});}" +
                    "catch(x){try{e.hass.connection.connected=false;}catch(y){}}}" +  // frontend reads disconnected
                    "console.log(\"[RIG] ws_stale_nosocket primed: socket=null state=connected frozen frontend=disconnected stale=300s\");" +
                    "return \"primed\";})()"
                webView.evaluateJavascript(
                    "window.evalInHaIframe && window.evalInHaIframe(${org.json.JSONObject.quote(inner)})", null)
                PersistentLog.info("RIG", "ws_stale_nosocket injected (null socket + frontend down + 5min stale)")
            }
            "storage_probe" -> {
                // Does the HA iframe's localStorage persist across an in-place
                // reload? Write a synthetic key, reload, read it back.
                val writeJs = """(function(){try{
                    var ifr=document.getElementById('ha-content');
                    var ls=ifr&&ifr.contentWindow&&ifr.contentWindow.localStorage;
                    if(!ls){console.log('[PROBE] NO iframe localStorage access (cross-origin)');return;}
                    var v='probe_'+Date.now();
                    ls.setItem('__dashie_probe',v);
                    console.log('[PROBE] wrote='+v+' readback='+ls.getItem('__dashie_probe')+' hassTokens='+(ls.getItem('hassTokens')?'present':'empty')+' keyCount='+ls.length);
                }catch(e){console.log('[PROBE] write EXCEPTION: '+e);}})();""".trimIndent()
                val readJs = """(function(){try{
                    var ifr=document.getElementById('ha-content');
                    var ls=ifr&&ifr.contentWindow&&ifr.contentWindow.localStorage;
                    if(!ls){console.log('[PROBE] NO iframe localStorage access post-reload (cross-origin)');return;}
                    console.log('[PROBE] after-reload __dashie_probe='+ls.getItem('__dashie_probe')+' hassTokens='+(ls.getItem('hassTokens')?'present':'empty')+' keyCount='+ls.length);
                }catch(e){console.log('[PROBE] read EXCEPTION: '+e);}})();""".trimIndent()
                webView.evaluateJavascript(writeJs, null)
                webView.postDelayed({
                    webView.evaluateJavascript("window.dashieReloadHaIframe && window.dashieReloadHaIframe()", null)
                }, 700)
                webView.postDelayed({ webView.evaluateJavascript(readJs, null) }, 4500)
            }
            "dump_haauth" -> {
                val js = """(function(){try{
                    var ifr=document.getElementById('ha-content');
                    var doc=ifr&&ifr.contentWindow&&ifr.contentWindow.document;
                    var ha=doc&&doc.querySelector('home-assistant');
                    var auth=ha&&ha.hass&&ha.hass.auth;
                    var d=auth&&auth.data;
                    var ls=ifr&&ifr.contentWindow&&ifr.contentWindow.localStorage;
                    if(!d){console.log('[HAAUTH] no hass.auth.data (ha='+(!!ha)+' hass='+(!!(ha&&ha.hass))+' auth='+(!!auth)+')');return;}
                    console.log('[HAAUTH] access='+(d.access_token?'present':'none')+' refresh='+(d.refresh_token?'present':'none')+' expires='+d.expires+' hassUrl='+d.hassUrl+' clientId='+d.clientId+' lsHassTokens='+((ls&&ls.getItem('hassTokens'))?'present':'empty'));
                }catch(e){console.log('[HAAUTH] EXCEPTION: '+e);}})();""".trimIndent()
                webView.evaluateJavascript(js, null)
            }
            "set_hasstokens" -> {
                val supplied = intent.getStringExtra("json")
                val tokenJson = if (!supplied.isNullOrBlank()) supplied
                    else halitePrefs?.connection?.buildHassTokensJson()
                if (tokenJson.isNullOrBlank()) {
                    Log.w(TAG, "set_hasstokens: no json and buildHassTokensJson() null")
                    PersistentLog.warn("AUTH-DIAG", "set_hasstokens: no token JSON available")
                } else {
                    val escaped = tokenJson.replace("\\", "\\\\").replace("'", "\\'")
                    val writeJs = """(function(){try{
                        var ifr=document.getElementById('ha-content');
                        var ls=ifr&&ifr.contentWindow&&ifr.contentWindow.localStorage;
                        if(!ls){console.log('[SETTOK] NO iframe localStorage access (cross-origin)');return;}
                        ls.setItem('hassTokens','$escaped');
                        console.log('[SETTOK] wrote hassTokens readback='+(ls.getItem('hassTokens')?'present':'empty')+' keyCount='+ls.length);
                    }catch(e){console.log('[SETTOK] EXCEPTION: '+e);}})();""".trimIndent()
                    webView.evaluateJavascript(writeJs, null)
                    webView.postDelayed({
                        webView.evaluateJavascript("window.dashieReloadHaIframe && window.dashieReloadHaIframe()", null)
                    }, 600)
                    PersistentLog.info("AUTH-DIAG", "set_hasstokens: injected ${tokenJson.length} chars, reloading iframe")
                }
            }
            else -> Log.w(TAG, "DebugTestInjector: unknown event '$event'")
        }
    }
}
