package com.dashieapp.Dashie.halite.billing

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences
import com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate

/**
 * Bottom-left "Trial: N days left" chip. Visible when:
 *  - subscription_status == 'trialing'
 *  - daysRemaining is in (0..5]
 * Otherwise hidden. Tapping fires the same SUBSCRIBE broadcast as the
 * trial-expired overlay so the same QR purchase flow opens.
 *
 * Registered with the visibility gate as OVERLAY_FULL_ONLY so the gate
 * force-hides it on page reload / OFF mode (splash, login, settings).
 */
class TrialCountdownChipManager(
    private val activity: Activity,
    private val visibilityGate: NativeWidgetVisibilityGate? = null
) {
    companion object {
        private const val TAG = "TrialCountdownChip"
        private const val DAYS_THRESHOLD = 3
    }

    private var chipView: View? = null
    private val subPrefs = SubscriptionPreferences(activity)

    fun refresh() {
        activity.runOnUiThread {
            // Amazon Appstore IAP compliance: tapping the chip fires the
            // Subscribe broadcast (historically routed to the in-app QR
            // purchase). Even though the trial-expired modal is itself
            // scrubbed of payment paths on amazon, the chip is still a
            // "Subscribe" surface in the user's mind — and Amazon's policy
            // bars *any* in-app surface that hints at external payment.
            // Drop the chip entirely on amazon.
            if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon") {
                hide()
                return@runOnUiThread
            }
            // Brand-split T4: the chip fires ACTION_DASHIE_TRIAL_EXPIRED_SUBSCRIBE, so
            // it is a subscribe surface. shouldShowFull() already excludes KIOSK, which
            // should make this redundant — but that gate answers "is the dashboard
            // painted right now?", not "is this device the free HA edition?", and the
            // two drift (e.g. forceKioskMode set while the gate still reads FULL).
            // Gate on the mode itself rather than relying on a proxy.
            if (com.dashieapp.Dashie.halite.HaEditionGate.isHaMode(activity)) {
                hide()
                return@runOnUiThread
            }
            val status = subPrefs.subscriptionStatus
            val days = subPrefs.daysRemaining
            // Don't show outside FULL mode — splash / login / kiosk have
            // no trial concept. shouldShowFull() returns false on KIOSK.
            val gateAllows = visibilityGate?.shouldShowFull() ?: true
            if (status == SubscriptionPreferences.STATUS_TRIALING
                && days in 1..DAYS_THRESHOLD
                && gateAllows
            ) {
                show(days)
            } else {
                hide()
            }
        }
    }

    private fun show(days: Int) {
        try {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val view = chipView ?: LayoutInflater.from(activity)
                .inflate(R.layout.view_trial_countdown_chip, root, false)
                .also { v ->
                    val body = v.findViewById<LinearLayout>(R.id.trialChipBody)
                    body.setOnClickListener {
                        Log.i(TAG, "Trial chip tapped — opening subscribe flow")
                        activity.sendBroadcast(
                            Intent("com.dashieapp.Dashie.ACTION_DASHIE_TRIAL_EXPIRED_SUBSCRIBE")
                                .apply { setPackage(activity.packageName) }
                        )
                    }
                }
            // Update label every refresh in case days have decremented.
            val label = view.findViewById<TextView>(R.id.textTrialChipDays)
            label.text = if (days == 1) "Trial: 1 day left" else "Trial: $days days left"

            if (chipView == null) {
                root.addView(view)
                visibilityGate?.register(view, NativeWidgetVisibilityGate.WidgetKind.OVERLAY_FULL_ONLY)
                chipView = view
                Log.i(TAG, "Trial countdown chip shown ($days days)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show chip: ${e.message}", e)
        }
    }

    private fun hide() {
        val view = chipView ?: return
        try {
            visibilityGate?.unregister(view)
            (view.parent as? ViewGroup)?.removeView(view)
            chipView = null
            Log.i(TAG, "Trial countdown chip hidden")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hide chip: ${e.message}")
        }
    }
}
