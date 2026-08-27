package com.dashieapp.Dashie.halite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * One-stop helper for battery-optimization-exemption checks and the
 * intents that open the matching system settings screens. Five files
 * had nearly-identical inline blocks for these (PowerManager cast +
 * isIgnoringBatteryOptimizations check, plus the request/list intents
 * with the package-URI dance).
 *
 * minSdk=23 covers all APIs touched here, so no version guards inside
 * the helper. Callers that already wrap calls in `Build.VERSION.SDK_INT`
 * checks can keep them — they're now redundant but harmless.
 */
object BatteryOptimizationHelper {

    /** True iff Dashie is currently exempt from battery optimization. */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /**
     * Intent that opens the system dialog asking the user to exempt
     * Dashie from battery optimization. Targeted at our package.
     */
    fun buildRequestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Intent that opens the system list of all battery-optimized apps.
     * Use this as a fallback if [buildRequestExemptionIntent] fails or
     * when the user wants to *remove* a previous exemption.
     */
    fun buildSettingsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
