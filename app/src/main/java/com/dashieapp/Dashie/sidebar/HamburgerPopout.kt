package com.dashieapp.Dashie.sidebar

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Handles hamburger menu popout logic.
 *
 * Two mutually exclusive sections:
 * - Unlocked: Pin/Unpin, Lock, Sleep, Reload, Exit, Control Center
 * - Locked: Reload, Sleep, Unlock App
 */
class HamburgerPopout(
    private val onAction: (HamburgerAction) -> Unit
) {
    enum class HamburgerAction {
        PIN, EDIT_LAYOUT, LOCK, SLEEP, RELOAD, EXIT, CONTROL_CENTER, UNLOCK, PIN_RECOVERY,
        // Show a QR pointing the user's phone at the public Console URL so
        // they can manage their account from their phone (§9c).
        CONNECT_MOBILE,
    }

    /**
     * Bind click handlers and update UI for lock/pin state.
     */
    companion object {
        private const val TAG = "HamburgerPopout"
    }

    fun bind(contentView: View, isLocked: Boolean, isPinned: Boolean, layoutMode: String = "widgets") {
        val unlockedSection = contentView.findViewById<LinearLayout>(R.id.popoutUnlockedSection)
        val lockedSection = contentView.findViewById<LinearLayout>(R.id.popoutLockedSection)

        // Pin/Unpin and Lock are advanced controls — visible when HA is enabled
        // OR the user has opted into Advanced Tablet Controls. For everyone
        // else the sidebar stays pinned and the lock action is hidden.
        //
        // 🔴 A custom-URL ("host your own dashboard") device counts too. It has
        // haEnabled=false by design, but it is every bit as much a wall-mounted
        // kiosk as an HA one — so gating on haEnabled alone silently removed
        // Unpin Sidebar and Lock Tablet from exactly the devices that need them
        // (found on a Fire tablet, 2026-08-18). haEnabled answers "does this
        // device talk to Home Assistant", not "is this an advanced device".
        val conn = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(
            contentView.context
        )
        val advancedItemsVisible =
            conn.haEnabled || conn.advancedTabletControlsEnabled || conn.isCustomUrlKiosk

        // Log touch events on the root to see what's arriving
        contentView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                Log.w(TAG, "🔍 ROOT touch DOWN at screen(${event.rawX.toInt()},${event.rawY.toInt()}) " +
                    "view(${event.x.toInt()},${event.y.toInt()}) " +
                    "rootBounds=[${loc[0]},${loc[1]} → ${loc[0]+v.width},${loc[1]+v.height}]")
            }
            false
        }

        // Logo: 5-second long press triggers PIN recovery
        attachLogoPinRecovery(contentView.findViewById(R.id.popoutLogoWrapper))

        if (isLocked) {
            unlockedSection.visibility = View.GONE
            lockedSection.visibility = View.VISIBLE
            bindLockedSection(contentView)
        } else {
            unlockedSection.visibility = View.VISIBLE
            lockedSection.visibility = View.GONE
            bindUnlockedSection(contentView, isPinned, layoutMode, advancedItemsVisible)
        }
    }

    private fun bindUnlockedSection(view: View, isPinned: Boolean, layoutMode: String, advancedItemsVisible: Boolean) {
        // Pin/Unpin (advanced control — sidebar is always pinned otherwise)
        val pinItem = view.findViewById<View>(R.id.popoutItemPin)
        if (advancedItemsVisible) {
            val pinLabel = view.findViewById<TextView>(R.id.popoutLabelPin)
            val pinIcon = view.findViewById<ImageView>(R.id.popoutIconPin)
            pinLabel.text = if (isPinned) "Unpin Sidebar" else "Pin Sidebar"
            pinIcon.setImageResource(if (isPinned) R.drawable.ic_unpin else R.drawable.ic_pin)
            pinItem.visibility = View.VISIBLE
            pinItem.setOnClickListener { onAction(HamburgerAction.PIN) }
        } else {
            pinItem.visibility = View.GONE
        }

        // Connect to Mobile (TV-side QR launcher for the public Console).
        // Visible whenever the user is signed in to a Dashie account — i.e.,
        // not visible in HA-only kiosk mode where there's no Dashie account
        // to manage. Mirrors the same gating logic used elsewhere for
        // Dashie-account-only surfaces.
        //
        // Amazon Appstore IAP compliance: the QR resolves to /console which
        // is a payment-capable page (subscription management). Per Amazon's
        // Developer Services Agreement, in-app surfaces cannot direct users
        // to external payment methods — so the entry is hidden entirely on
        // the amazon flavor (Spotify-style: app works, but no in-app path
        // to web billing).
        val connectMobileItem = view.findViewById<View>(R.id.popoutItemConnectMobile)
        val isAmazonFlavor = com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon"
        if (isSignedInToDashieAccount(view) && !isAmazonFlavor) {
            connectMobileItem.visibility = View.VISIBLE
            connectMobileItem.setOnClickListener { onAction(HamburgerAction.CONNECT_MOBILE) }
        } else {
            connectMobileItem.visibility = View.GONE
        }

        // Edit Layout (only visible on debug builds AND canvas dev mode)
        val editLayoutItem = view.findViewById<View>(R.id.popoutItemEditLayout)
        if (com.dashieapp.Dashie.BuildConfig.DEBUG && layoutMode == "canvas") {
            editLayoutItem.visibility = View.VISIBLE
            editLayoutItem.setOnClickListener {
                onAction(HamburgerAction.EDIT_LAYOUT)
            }
        } else {
            editLayoutItem.visibility = View.GONE
        }

        // Lock (advanced control — hidden for users without HA or Advanced Tablet Controls)
        val lockItem = view.findViewById<View>(R.id.popoutItemLock)
        if (advancedItemsVisible) {
            lockItem.visibility = View.VISIBLE
            lockItem.setOnClickListener { onAction(HamburgerAction.LOCK) }
        } else {
            lockItem.visibility = View.GONE
        }

        // Sleep
        view.findViewById<View>(R.id.popoutItemSleep).setOnClickListener {
            onAction(HamburgerAction.SLEEP)
        }

        // Reload
        view.findViewById<View>(R.id.popoutItemReload).setOnClickListener {
            onAction(HamburgerAction.RELOAD)
        }

        // Exit
        view.findViewById<View>(R.id.popoutItemExit).setOnClickListener {
            onAction(HamburgerAction.EXIT)
        }

        // Control Center
        val ccItem = view.findViewById<View>(R.id.popoutItemControlCenter)
        ccItem.setOnClickListener {
            onAction(HamburgerAction.CONTROL_CENTER)
        }
        addTouchLogger(ccItem, "ControlCenter")
        // Default focus to Control Center (most common action)
        ccItem.post { ccItem.requestFocus() }

        // Also log Exit (second to last) for comparison
        addTouchLogger(view.findViewById(R.id.popoutItemExit), "Exit")
    }

    private fun bindLockedSection(view: View) {
        view.findViewById<View>(R.id.popoutItemReloadLocked).setOnClickListener {
            onAction(HamburgerAction.RELOAD)
        }
        view.findViewById<View>(R.id.popoutItemSleepLocked).setOnClickListener {
            onAction(HamburgerAction.SLEEP)
        }
        val unlockItem = view.findViewById<View>(R.id.popoutItemUnlock)
        unlockItem.setOnClickListener {
            onAction(HamburgerAction.UNLOCK)
        }
        addTouchLogger(unlockItem, "Unlock")
        // Default focus to Unlock (primary action)
        unlockItem.post { unlockItem.requestFocus() }
    }

    /**
     * Whether a Dashie account is linked on this device. The "Connect to
     * Mobile" entry only makes sense when there's an account to manage —
     * pure HA-only kiosk-mode users don't have a dashieapp.com account, so
     * we hide the entry for them.
     */
    private fun isSignedInToDashieAccount(view: View): Boolean {
        return com.dashieapp.Dashie.halite.preferences.AccountPreferences(view.context).isLinked
    }

    /**
     * Attach a 5-second long press on the logo that triggers PIN recovery.
     * Matches JS attachLongPress(logoWrapper, () => DashieNative.showPinRecoveryDialog(), 5000).
     */
    private fun attachLogoPinRecovery(view: View) {
        var timer: android.os.Handler? = null
        var runnable: Runnable? = null

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val h = android.os.Handler(android.os.Looper.getMainLooper())
                    val r = Runnable { onAction(HamburgerAction.PIN_RECOVERY) }
                    timer = h
                    runnable = r
                    h.postDelayed(r, 5000L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE -> {
                    runnable?.let { timer?.removeCallbacks(it) }
                    timer = null
                    runnable = null
                }
            }
            false
        }
    }

    /**
     * Add touch event logging to a view for debugging bottom-edge touch issues.
     */
    private fun addTouchLogger(view: View, label: String) {
        view.setOnTouchListener { v, event ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val screenBottom = v.resources.displayMetrics.heightPixels
            val viewBottom = loc[1] + v.height
            val distFromScreenBottom = screenBottom - event.rawY.toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> Log.w(TAG, "🔍 $label DOWN raw(${event.rawX.toInt()},${event.rawY.toInt()}) " +
                    "viewBounds=[${loc[0]},${loc[1]}→${loc[0]+v.width},${viewBottom}] " +
                    "screenH=$screenBottom distFromBottom=$distFromScreenBottom")
                MotionEvent.ACTION_UP -> Log.w(TAG, "🔍 $label UP raw(${event.rawX.toInt()},${event.rawY.toInt()}) distFromBottom=$distFromScreenBottom")
                MotionEvent.ACTION_CANCEL -> Log.w(TAG, "🔍 $label CANCEL — touch was intercepted!")
            }
            false // don't consume
        }
    }
}
