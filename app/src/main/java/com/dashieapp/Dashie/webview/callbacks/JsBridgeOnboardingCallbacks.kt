package com.dashieapp.Dashie.webview.callbacks

import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.MainPermissionDelegate
import com.dashieapp.Dashie.MainUrlHandler
import com.dashieapp.Dashie.halite.DashieWebViewClient
import com.dashieapp.Dashie.halite.HaliteAuthManager
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeOnboardingCallbacks(
    private val haliteAuthManagerProvider: () -> HaliteAuthManager?,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val webViewProvider: () -> WebView,
    private val dashieWebViewClientProvider: () -> DashieWebViewClient?,
    private val urlHandlerProvider: () -> MainUrlHandler?,
    private val permissionDelegateProvider: () -> MainPermissionDelegate,
    private val updateControllerProvider: () -> com.dashieapp.Dashie.halite.update.DashieUpdateController? = { null },
    /**
     * Assert the post-setup UI mode (kiosk surfaces on).
     *
     * 🔴 Needed because the gate's KIOSK decision is made at APP START from
     * isSetupComplete — false on a fresh install — and the only thing that
     * re-asserts it afterwards is DashieWebViewClient.onKioskShellLoaded. A
     * custom-URL device never loads the shell, so nothing ever moved it off OFF:
     * onboarding finished and the sidebar stayed gateSuppressed until the next
     * app restart (found re-testing on a Fire, 2026-08-18). Setup completing IS
     * the event that makes kiosk surfaces correct, so say so here.
     */
    private val assertKioskUiMode: () -> Unit = {},
    private val runOnUiThread: (Runnable) -> Unit
) : DashieJSBridge.OnboardingCallbacks {

    companion object {
        private const val TAG = "DashieJSBridge"
    }

    override fun onOnboardingComplete() {
        Log.i(TAG, "✅ Onboarding complete — loading HA with HTML-level token injection")
        runOnUiThread {
            // Mark auth as complete so shouldInterceptAuthPage doesn't trigger
            haliteAuthManagerProvider()?.markLoginComplete()
            haliteAuthManagerProvider()?.resetCancelledFlag()

            val prefs = halitePrefsProvider() ?: return@runOnUiThread
            val accessToken = prefs.connection.haAccessToken

            if (accessToken.isNotEmpty()) {
                // Single source of truth: clientId/hassUrl from the canonical auth
                // origin (proxy when configured), matching mint + refresh.
                val tokenJson = prefs.connection.buildHassTokensJson(
                    expiry = System.currentTimeMillis() + (1800 * 1000L)
                )
                if (tokenJson != null) {
                    // Set pending token injection for onPageFinished to pick up
                    haliteAuthManagerProvider()?.setIframeTokenInjection(tokenJson)
                } else {
                    Log.w(TAG, "✅ No auth origin configured — loading HA without injection")
                }
            } else {
                Log.w(TAG, "✅ No saved tokens — loading HA without injection")
            }

            // Reload at the URL this device is actually configured for.
            //
            // 🔴 This used to hardcode getShellPageUrl(), which is correct ONLY for
            // the HA path. A custom-URL ("host your own dashboard") device finished
            // onboarding and was then sent to the HA shell anyway — with no haUrl,
            // so it fell back to the 192.168.1.x placeholder and showed
            // "Home Assistant Unavailable" instead of the dashboard the user had
            // just configured (Fire tablet, 2026-08-18). determineInitialUrl()
            // already encodes the full precedence (linked account → custom URL →
            // kiosk shell → direct HA); ask it rather than re-deciding here, so the
            // post-onboarding load and every later boot agree.
            val target = urlHandlerProvider()?.determineInitialUrl()
                ?: prefs.connection.getShellPageUrl()
            Log.i(TAG, "✅ Reloading at configured URL: $target")
            dashieWebViewClientProvider()?.pendingOnboardingTips = true
            urlHandlerProvider()?.loadUrlWithWsProxyIfNeeded(target)

            // Setup is complete, so kiosk surfaces (sidebar) are now correct.
            // On the shell path onKioskShellLoaded also asserts this; on the
            // custom-URL path nothing else would, and asserting twice is a no-op.
            assertKioskUiMode()

            // Setup is now complete — surface an update banner that was held
            // back during onboarding.
            updateControllerProvider()?.onSetupCompleted()
        }
    }

    override fun onRequestPermissionByType(type: String) {
        Log.i(TAG, "🔐 Permission request from onboarding: $type")
        runOnUiThread {
            permissionDelegateProvider().requestOnboardingPermission(type)
        }
    }

    override fun onRequestAllOnboardingPermissions() {
        Log.i(TAG, "🔐 Requesting all onboarding permissions sequentially")
        runOnUiThread {
            val delegate = permissionDelegateProvider()
            delegate.onOnboardingPermissionsComplete = {
                Log.i(TAG, "🔐 All onboarding permissions complete — notifying JS")
                webViewProvider().evaluateJavascript(
                    // WEBAPP-EXEMPT: dashieOnPermissionsComplete — kiosk-overlay onboarding shell API
                    "if (window.dashieOnPermissionsComplete) window.dashieOnPermissionsComplete();",
                    null
                )
            }
            delegate.requestAllOnboardingPermissions()
        }
    }
}
