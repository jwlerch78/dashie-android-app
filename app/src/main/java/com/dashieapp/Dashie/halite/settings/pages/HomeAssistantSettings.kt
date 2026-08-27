package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity

internal fun SettingsActivity.createHomeAssistantFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val vp = schemaContext.valueProvider
    val initialHideSidebar = vp.getBoolean("connection.hideSidebar")
    val initialHideTabs = vp.getBoolean("connection.hideTabs")
    val initialHideSearch = vp.getBoolean("connection.hideSearch")
    val initialHideAssistant = vp.getBoolean("connection.hideAssistant")
    val initialShowFloatingBack = vp.getBoolean("connection.showFloatingBackButton")
    val initialUrl = com.dashieapp.Dashie.halite.HalitePreferences(this).connection.haUrl
    val initialUseCustomUrl = vp.getBoolean("connection.useCustomUrl")
    val initialCustomUrl = vp.getString("connection.customUrl") ?: ""

    schemaContext.callbackRegistry.register("signInToHa") {
        val conn = com.dashieapp.Dashie.halite.HalitePreferences(this).connection
        val haUrl = conn.haUrl
        if (haUrl.isEmpty() || haUrl == com.dashieapp.Dashie.halite.HalitePreferences.DEFAULT_HA_URL) {
            android.widget.Toast.makeText(
                this,
                "Set the Dashboard URL first.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return@register
        }
        conn.pendingHaLoginRequest = true
        setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
        finish()
    }

    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            val conn = com.dashieapp.Dashie.halite.HalitePreferences(this).connection
            val needsLogin = conn.haEnabled
                && conn.haUrl.isNotEmpty()
                && conn.haUrl != com.dashieapp.Dashie.halite.HalitePreferences.DEFAULT_HA_URL
                && !conn.hasHaAccessToken
            com.dashieapp.Dashie.halite.settings.schemas.HomeAssistantPageSchema.create(needsLogin)
        },
        valueProvider = vp,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:url_config" -> {
                    showFragment(
                        com.dashieapp.Dashie.halite.settings.fragments.SettingsHaUrlFragment(),
                        "ha_url_config"
                    )
                    true
                }
                else -> false
            }
        },
        backNavigationInterceptor = {
            val currentHideSidebar = vp.getBoolean("connection.hideSidebar")
            val currentHideTabs = vp.getBoolean("connection.hideTabs")
            val currentHideSearch = vp.getBoolean("connection.hideSearch")
            val currentHideAssistant = vp.getBoolean("connection.hideAssistant")
            val currentShowFloatingBack = vp.getBoolean("connection.showFloatingBackButton")
            val currentUrl = com.dashieapp.Dashie.halite.HalitePreferences(this).connection.haUrl
            val currentUseCustomUrl = vp.getBoolean("connection.useCustomUrl")
            val currentCustomUrl = vp.getString("connection.customUrl") ?: ""
            val urlChanged = currentUrl != initialUrl &&
                currentUrl.isNotEmpty() &&
                currentUrl != com.dashieapp.Dashie.halite.HalitePreferences.DEFAULT_HA_URL
            val customUrlChanged = currentUseCustomUrl != initialUseCustomUrl ||
                (currentUseCustomUrl && currentCustomUrl != initialCustomUrl)
            val displayChanged = currentHideSidebar != initialHideSidebar ||
                currentHideTabs != initialHideTabs ||
                currentHideSearch != initialHideSearch ||
                currentHideAssistant != initialHideAssistant ||
                currentShowFloatingBack != initialShowFloatingBack

            android.util.Log.i("SettingsHA", "🔍 Back interceptor: initialUrl='$initialUrl' currentUrl='$currentUrl' urlChanged=$urlChanged customUrlChanged=$customUrlChanged displayChanged=$displayChanged (sidebar: $initialHideSidebar→$currentHideSidebar, tabs: $initialHideTabs→$currentHideTabs, search: $initialHideSearch→$currentHideSearch, assistant: $initialHideAssistant→$currentHideAssistant, floatingBack: $initialShowFloatingBack→$currentShowFloatingBack)")

            if (urlChanged || displayChanged || customUrlChanged) {
                showDashboardReloadPrompt()
                true
            } else {
                false
            }
        }
    )
}

/**
 * Show a reload prompt when HA settings (URL, display) have changed.
 * Dashboard CSS injection (sidebar/tabs hiding) only applies during page load,
 * so a reload is required — there is no valid "apply later" path.
 */
internal fun SettingsActivity.showDashboardReloadPrompt() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)

    dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Reload Dashboard"
    dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
        "Dashboard settings have changed. The dashboard will reload to apply."

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(false)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).visibility =
        android.view.View.GONE

    dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).text = "Reload"
    dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).setOnClickListener {
        dialog.dismiss()
        val intent = android.content.Intent(
            this, com.dashieapp.Dashie.MainActivity::class.java
        )
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
}
