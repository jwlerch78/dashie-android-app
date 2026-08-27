package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.edition.brandName
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles all lock-related dialogs for Dashie Lite.
 * Includes lock settings, PIN setup, PIN entry, and unlock options dialogs.
 */
class LockDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    /**
     * The no-recovery-email warning, shown from three places in [showRecoveryEmailDialog]
     * (skip / save-empty / cancel).
     *
     * 🔴 BRAND-TRIAGED (A, 2026-08-05, sweep pass 2). This one is worse than a brand leak: it
     * tells the user to long-press "the Dashie logo", and the drawable that long-press is
     * actually attached to (`popoutLogoWrapper` → `@drawable/dashie_logo_orange`,
     * HamburgerPopout.attachLogoPinRecovery) **is overridden per edition** in
     * `src/chickadeeBrand/res`. So a Chickadee user was being told to press a logo that is not
     * on their screen — the mechanism is right, the landmark named was wrong. Sharing the
     * sentence rather than substituting three copies, per standing rule 1.
     */
    private fun pinRecoveryWarning(): String =
        "Since you did not set a recovery email, your PIN can be reset with a long press on " +
        "the ${activity.brandName()} logo in the pop-up menu (5 seconds)."
    // UI coroutines tied to the host activity's lifecycle (all real hosts are
    // ComponentActivity/AppCompatActivity). Fallback scope for non-lifecycle
    // hosts matches the old fire-and-forget behavior.
    private val uiScope: CoroutineScope =
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
            ?: CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "LockDialogs"
        private const val PIN_LENGTH = 4  // Fixed 4-digit PIN
        private const val MAX_PIN_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L  // 30 seconds
    }

    // Track failed PIN attempts and lockout
    private var failedPinAttempts = 0
    private var lockoutEndTime = 0L

    // Guard against TextWatcher firing multiple times for the same PIN
    private var isProcessingPin = false

    // Callbacks
    var onLockStateChanged: ((Boolean) -> Unit)? = null  // Called when lock state changes
    var onLockSettingsChanged: (() -> Unit)? = null      // Called when any lock setting changes

    /**
     * Show the simplified lock dialog - just choose unlock method and lock
     */
    fun showLockSettingsDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_lock_settings, null)

        // Get references to views
        val radioUnlockAnyone = dialogView.findViewById<RadioButton>(R.id.radioUnlockAnyone)
        val radioUnlockPin = dialogView.findViewById<RadioButton>(R.id.radioUnlockPin)
        val textPinStatus = dialogView.findViewById<TextView>(R.id.textPinStatus)
        val buttonSetChangePin = dialogView.findViewById<Button>(R.id.buttonSetChangePin)
        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonCancel)
        val buttonLock = dialogView.findViewById<Button>(R.id.buttonLock)

        // Initialize from current settings
        when (halitePrefs.lock.unlockMechanism) {
            HalitePreferences.UNLOCK_MECHANISM_ANYONE -> radioUnlockAnyone.isChecked = true
            HalitePreferences.UNLOCK_MECHANISM_PIN -> radioUnlockPin.isChecked = true
        }

        // Update PIN status display (always visible)
        fun updatePinStatusUI() {
            val hasPinSet = halitePrefs.lock.hasPinSet
            if (hasPinSet) {
                textPinStatus.text = "PIN = ****"
                textPinStatus.setTextColor(activity.getColor(R.color.text_primary))
                buttonSetChangePin.text = "Change PIN"
            } else {
                textPinStatus.text = "No PIN set"
                textPinStatus.setTextColor(activity.getColor(R.color.text_secondary))
                buttonSetChangePin.text = "Set PIN"
            }
        }
        updatePinStatusUI()

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Set/Change PIN button
        buttonSetChangePin.setOnClickListener {
            dialog.dismiss()
            startPinSetupFlow(isChange = halitePrefs.lock.hasPinSet) {
                // Re-show lock dialog after PIN setup completes
                showLockSettingsDialog()
            }
        }

        // Cancel button
        buttonCancel.setOnClickListener {
            dialog.dismiss()
        }

        // Lock button
        buttonLock.setOnClickListener {
            val unlockMechanism = if (radioUnlockPin.isChecked)
                HalitePreferences.UNLOCK_MECHANISM_PIN
            else
                HalitePreferences.UNLOCK_MECHANISM_ANYONE

            // If PIN required but no PIN set, offer to set PIN
            if (unlockMechanism == HalitePreferences.UNLOCK_MECHANISM_PIN && !halitePrefs.lock.hasPinSet) {
                dialog.dismiss()
                showNoPinSetConfirmationDialog {
                    // PIN is now set — apply lock directly instead of re-showing dialog
                    applyLock(HalitePreferences.UNLOCK_MECHANISM_PIN)
                }
                return@setOnClickListener
            }

            // If PIN mode selected, require PIN verification before locking
            if (unlockMechanism == HalitePreferences.UNLOCK_MECHANISM_PIN) {
                dialog.dismiss()
                showPinEntryDialog(
                    title = "Verify PIN",
                    subtitle = "Enter PIN to lock app",
                    showForgotPin = false
                ) { success ->
                    if (success) {
                        applyLock(unlockMechanism)
                    }
                }
                return@setOnClickListener
            }

            // No PIN - apply lock directly
            dialog.dismiss()
            applyLock(unlockMechanism)
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Show confirmation dialog when user tries to lock with PIN required but no PIN set
     */
    private fun showNoPinSetConfirmationDialog(onComplete: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle)?.text = "No PIN Set"
        dialogView.findViewById<TextView>(R.id.dialogMessage)?.text = "No PIN is set. Would you like to set one now?"

        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonNegative)
        val buttonSetPin = dialogView.findViewById<Button>(R.id.buttonPositive)
        buttonCancel?.text = "Cancel"
        buttonSetPin?.text = "Set PIN"

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        buttonCancel?.setOnClickListener {
            dialog.dismiss()
            onComplete()
        }

        buttonSetPin?.setOnClickListener {
            dialog.dismiss()
            startPinSetupFlow(isChange = false) {
                onComplete()
            }
        }

        dialog.setOnCancelListener {
            onComplete()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Show unlock confirmation dialog (for "anyone can unlock" mode)
     * Unlocks directly on confirmation - no options dialog
     */
    private fun showUnlockConfirmationDialog(onUnlock: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Unlock App?"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "This will unlock the app and restore access to settings."

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Unlock"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            performUnlock()
            onUnlock()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Show PIN entry dialog for unlocking
     */
    fun showPinEntryDialog(title: String = "Enter PIN", subtitle: String = "Enter your 4-digit PIN", showForgotPin: Boolean = true, onResult: (Boolean) -> Unit) {
        DiagnosticBuffer.info("PIN", "showPinEntryDialog: title='$title' failedAttempts=$failedPinAttempts")

        // Reset processing guard when opening new dialog
        isProcessingPin = false

        // Check if we're in lockout period
        val currentTime = System.currentTimeMillis()
        if (currentTime < lockoutEndTime) {
            val remainingSeconds = ((lockoutEndTime - currentTime) / 1000).toInt()
            DiagnosticBuffer.warn("PIN", "Dialog blocked - in lockout period (${remainingSeconds}s remaining)")
            showErrorDialog("Too many failed attempts.\n\nPlease wait $remainingSeconds seconds before trying again.")
            onResult(false)
            return
        }

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_pin_entry, null)

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val dialogSubtitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
        val pinDigit1 = dialogView.findViewById<TextView>(R.id.pinDigit1)
        val pinDigit2 = dialogView.findViewById<TextView>(R.id.pinDigit2)
        val pinDigit3 = dialogView.findViewById<TextView>(R.id.pinDigit3)
        val pinDigit4 = dialogView.findViewById<TextView>(R.id.pinDigit4)
        val textError = dialogView.findViewById<TextView>(R.id.textError)
        val textForgotPin = dialogView.findViewById<TextView>(R.id.textForgotPin)
        val hiddenPinInput = dialogView.findViewById<EditText>(R.id.hiddenPinInput)
        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonCancel)

        val pinDigits = listOf(pinDigit1, pinDigit2, pinDigit3, pinDigit4)

        dialogTitle.text = title
        dialogSubtitle.text = subtitle
        textForgotPin.visibility = if (showForgotPin) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Make the whole PIN area clickable to show keyboard
        dialogView.findViewById<LinearLayout>(R.id.pinContainer).setOnClickListener {
            hiddenPinInput.requestFocus()
            showKeyboard(hiddenPinInput)
        }

        // Focus hidden input and show keyboard
        dialog.setOnShowListener {
            hiddenPinInput.requestFocus()
            showKeyboard(hiddenPinInput)
        }

        // Track when we've just shown an error (to avoid hiding it when clearing PIN)
        var justShowedError = false

        // Watch for PIN input - auto-submit when 4 digits entered
        hiddenPinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""

                // Diagnostic logging for PIN entry debugging
                DiagnosticBuffer.debug("PIN", "afterTextChanged: len=${text.length} attempts=$failedPinAttempts isProcessing=$isProcessingPin justShowedError=$justShowedError")

                updatePinDisplay(pinDigits, text)

                // Auto-submit when 4 digits entered
                if (text.length == PIN_LENGTH) {
                    // Guard against duplicate processing (some keyboards fire multiple times)
                    if (isProcessingPin) {
                        DiagnosticBuffer.warn("PIN", "DUPLICATE PROCESSING BLOCKED: len=${text.length}")
                        return
                    }
                    isProcessingPin = true

                    if (verifyPin(text)) {
                        // Correct PIN - reset failed attempts and dismiss
                        DiagnosticBuffer.info("PIN", "PIN verified successfully")
                        failedPinAttempts = 0
                        isProcessingPin = false
                        hideKeyboard(hiddenPinInput)
                        dialog.dismiss()
                        onResult(true)
                    } else {
                        // Incorrect PIN - track attempt and show feedback
                        failedPinAttempts++
                        DiagnosticBuffer.warn("PIN", "Incorrect PIN attempt $failedPinAttempts of $MAX_PIN_ATTEMPTS")
                        Log.w(TAG, "Incorrect PIN attempt $failedPinAttempts of $MAX_PIN_ATTEMPTS")

                        if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
                            // Start lockout period
                            lockoutEndTime = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                            DiagnosticBuffer.error("PIN", "MAX ATTEMPTS REACHED - lockout triggered")
                            isProcessingPin = false
                            hideKeyboard(hiddenPinInput)
                            dialog.dismiss()
                            showErrorDialog("Too many failed attempts.\n\nPlease wait 30 seconds before trying again.")
                            onResult(false)
                        } else {
                            // Show error with remaining attempts
                            val attemptsRemaining = MAX_PIN_ATTEMPTS - failedPinAttempts
                            textError.text = "Incorrect PIN. $attemptsRemaining ${if (attemptsRemaining == 1) "attempt" else "attempts"} remaining."
                            textError.visibility = View.VISIBLE
                            // Mark that we just showed an error (so we don't hide it when clearing)
                            justShowedError = true
                            // Clear PIN digits immediately but leave error visible until user types
                            hiddenPinInput.text.clear()
                            updatePinDisplay(pinDigits, "")
                            isProcessingPin = false  // Allow next attempt after clearing
                        }
                    }
                } else if (text.isEmpty() && justShowedError) {
                    // PIN was just cleared after showing error - keep the error visible
                    justShowedError = false  // Reset flag so next digit hides the error
                    isProcessingPin = false
                } else if (text.isNotEmpty()) {
                    // User started typing - hide error
                    textError.visibility = View.GONE
                    isProcessingPin = false
                } else {
                    // Empty text, not from error clear - just reset processing flag
                    isProcessingPin = false
                }
            }
        })

        // Forgot PIN handler
        textForgotPin.setOnClickListener {
            dialog.dismiss()
            showForgotPinDialog()
        }

        // Cancel button
        buttonCancel.setOnClickListener {
            hideKeyboard(hiddenPinInput)
            dialog.dismiss()
            onResult(false)
        }

        dialog.setOnCancelListener {
            onResult(false)
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * iOS-style PIN setup flow:
     * 1. Enter 4-digit PIN
     * 2. Confirm PIN
     * 3. Recovery email (optional)
     * 4. Success message
     */
    fun startPinSetupFlow(isChange: Boolean = halitePrefs.lock.hasPinSet, onComplete: () -> Unit) {
        if (isChange && halitePrefs.lock.hasPinSet) {
            // First verify current PIN
            showPinEntryDialog(
                title = "Current PIN",
                subtitle = "Enter your current PIN",
                showForgotPin = true
            ) { success ->
                if (success) {
                    // Show choice: Change PIN or Clear PIN
                    showPinActionChoiceDialog(onComplete)
                }
            }
        } else {
            // Go straight to new PIN entry
            showEnterNewPinDialog(onComplete)
        }
    }

    /**
     * After verifying current PIN, let user choose to change or clear PIN
     */
    private fun showPinActionChoiceDialog(onComplete: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_simple_message, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle)?.text = "PIN Options"
        dialogView.findViewById<TextView>(R.id.dialogMessage)?.text = "What would you like to do?"

        // Hide both default buttons - we'll add our own
        dialogView.findViewById<Button>(R.id.buttonNegative)?.visibility = View.GONE
        dialogView.findViewById<Button>(R.id.buttonPositive)?.visibility = View.GONE

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Add custom buttons to the dialog
        val buttonContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }

        val changePinButton = Button(activity).apply {
            text = "Change PIN"
            setBackgroundResource(R.drawable.button_primary)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
                showEnterNewPinDialog(onComplete)
            }
        }

        val clearPinButton = Button(activity).apply {
            text = "Clear PIN"
            setBackgroundResource(R.drawable.button_border)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
                showClearPinConfirmationDialog(onComplete)
            }
        }

        val cancelButton = Button(activity).apply {
            text = "Cancel"
            setBackgroundResource(R.drawable.button_border)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
            }
        }

        // Add spacing between buttons
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }

        changePinButton.layoutParams = params
        clearPinButton.layoutParams = params

        buttonContainer.addView(changePinButton)
        buttonContainer.addView(clearPinButton)
        buttonContainer.addView(cancelButton)

        // Find the parent layout and add button container
        val rootLayout = dialogView as? LinearLayout
        rootLayout?.addView(buttonContainer)

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Confirm clearing the PIN
     */
    private fun showClearPinConfirmationDialog(onComplete: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle)?.text = "Clear PIN"
        dialogView.findViewById<TextView>(R.id.dialogMessage)?.text = "Are you sure you want to remove your PIN? The app will no longer require a PIN to unlock."

        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonNegative)
        val buttonConfirm = dialogView.findViewById<Button>(R.id.buttonPositive)
        buttonConfirm?.text = "Clear PIN"

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        buttonCancel?.setOnClickListener {
            dialog.dismiss()
        }

        buttonConfirm?.setOnClickListener {
            dialog.dismiss()
            clearPinAndComplete(onComplete)
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Clear the PIN and show success message
     */
    private fun clearPinAndComplete(onComplete: () -> Unit) {
        halitePrefs.lock.lockPin = ""
        halitePrefs.lock.lockRecoveryEmail = ""
        Log.i(TAG, "PIN cleared")

        onLockSettingsChanged?.invoke()

        showInfoDialog("PIN Cleared", "Your PIN has been removed.") {
            onComplete()
        }
    }

    /**
     * Step 1: Enter new PIN
     */
    private fun showEnterNewPinDialog(onComplete: () -> Unit) {
        showPinInputDialog(
            title = "Set PIN",
            subtitle = "Enter a 4-digit PIN"
        ) { enteredPin ->
            if (enteredPin != null) {
                // Continue to confirm PIN
                showConfirmPinDialog(enteredPin, onComplete)
            }
        }
    }

    /**
     * Step 2: Confirm PIN
     */
    private fun showConfirmPinDialog(originalPin: String, onComplete: () -> Unit) {
        showPinInputDialog(
            title = "Confirm PIN",
            subtitle = "Re-enter your PIN"
        ) { confirmedPin ->
            if (confirmedPin != null) {
                if (confirmedPin == originalPin) {
                    // PINs match - continue to recovery email
                    showRecoveryEmailDialog(originalPin, onComplete)
                } else {
                    // PINs don't match - show error and restart
                    showErrorDialog("PINs don't match") {
                        showEnterNewPinDialog(onComplete)
                    }
                }
            }
        }
    }

    /**
     * Step 3: Recovery email (optional)
     */
    private fun showRecoveryEmailDialog(pin: String, onComplete: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_recovery_email, null)

        val inputEmail = dialogView.findViewById<EditText>(R.id.inputEmail)
        val buttonSkip = dialogView.findViewById<Button>(R.id.buttonSkip)
        val buttonSave = dialogView.findViewById<Button>(R.id.buttonSave)

        // Pre-fill with existing email if any
        inputEmail.setText(halitePrefs.lock.lockRecoveryEmail)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        buttonSkip.setOnClickListener {
            hideKeyboard(inputEmail)
            dialog.dismiss()
            // Show warning about PIN reset, then save
            showInfoDialog(
                "PIN Recovery",
                pinRecoveryWarning()
            ) {
                savePinAndComplete(pin, "", onComplete)
            }
        }

        buttonSave.setOnClickListener {
            val email = inputEmail.text.toString().trim()

            // Validate email if provided
            if (email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                inputEmail.error = "Invalid email format"
                return@setOnClickListener
            }

            hideKeyboard(inputEmail)
            dialog.dismiss()

            // Show warning if saving with empty email
            if (email.isEmpty()) {
                showInfoDialog(
                    "PIN Recovery",
                    pinRecoveryWarning()
                ) {
                    savePinAndComplete(pin, "", onComplete)
                }
            } else {
                savePinAndComplete(pin, email, onComplete)
            }
        }

        dialog.setOnCancelListener {
            // Treat cancel as skip - but still show the warning
            showInfoDialog(
                "PIN Recovery",
                pinRecoveryWarning()
            ) {
                savePinAndComplete(pin, "", onComplete)
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)

        // Show keyboard
        inputEmail.requestFocus()
        showKeyboard(inputEmail)
    }

    /**
     * Step 4: Save PIN and show success
     */
    private fun savePinAndComplete(pin: String, email: String, onComplete: () -> Unit) {
        // Save PIN in plain text (simple dashboard access, not high security)
        halitePrefs.lock.lockPin = pin
        halitePrefs.lock.lockPinLength = PIN_LENGTH
        halitePrefs.lock.lockRecoveryEmail = email
        Log.i(TAG, "PIN saved (length=$PIN_LENGTH, hasEmail=${email.isNotEmpty()})")

        onLockSettingsChanged?.invoke()

        // Show success message including recovery email status
        val message = if (email.isNotEmpty()) {
            "Your PIN has been saved.\n\nRecovery email: ${maskEmail(email)}"
        } else {
            "Your PIN has been saved.\n\nNo recovery email set."
        }
        showInfoDialog("PIN Updated", message) {
            onComplete()
        }
    }

    /**
     * Generic PIN input dialog (reused for enter and confirm)
     */
    private fun showPinInputDialog(title: String, subtitle: String, onResult: (String?) -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_pin_entry, null)

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val dialogSubtitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
        val pinDigit1 = dialogView.findViewById<TextView>(R.id.pinDigit1)
        val pinDigit2 = dialogView.findViewById<TextView>(R.id.pinDigit2)
        val pinDigit3 = dialogView.findViewById<TextView>(R.id.pinDigit3)
        val pinDigit4 = dialogView.findViewById<TextView>(R.id.pinDigit4)
        val textError = dialogView.findViewById<TextView>(R.id.textError)
        val textForgotPin = dialogView.findViewById<TextView>(R.id.textForgotPin)
        val hiddenPinInput = dialogView.findViewById<EditText>(R.id.hiddenPinInput)
        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonCancel)

        val pinDigits = listOf(pinDigit1, pinDigit2, pinDigit3, pinDigit4)

        dialogTitle.text = title
        dialogSubtitle.text = subtitle
        textForgotPin.visibility = View.GONE  // Don't show forgot PIN during setup

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Make the whole PIN area clickable to show keyboard
        dialogView.findViewById<LinearLayout>(R.id.pinContainer).setOnClickListener {
            hiddenPinInput.requestFocus()
            showKeyboard(hiddenPinInput)
        }

        // Focus hidden input and show keyboard
        dialog.setOnShowListener {
            hiddenPinInput.requestFocus()
            showKeyboard(hiddenPinInput)
        }

        // Watch for PIN input - auto-continue when 4 digits entered
        hiddenPinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                updatePinDisplay(pinDigits, text)
                textError.visibility = View.GONE

                // Auto-continue when 4 digits entered
                if (text.length == PIN_LENGTH) {
                    hideKeyboard(hiddenPinInput)
                    dialog.dismiss()
                    // Delay before showing next dialog to let keyboard fully hide
                    // Use main looper handler since dialog view will be detached after dismiss
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        onResult(text)
                    }, 300)
                }
            }
        })

        // Cancel button
        buttonCancel.setOnClickListener {
            hideKeyboard(hiddenPinInput)
            dialog.dismiss()
            onResult(null)
        }

        dialog.setOnCancelListener {
            onResult(null)
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Show forgot PIN dialog
     */
    fun showForgotPinDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        val email = halitePrefs.lock.lockRecoveryEmail
        if (email.isNotEmpty()) {
            // Mask email for display
            val maskedEmail = maskEmail(email)
            dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Forgot PIN?"
            dialogView.findViewById<TextView>(R.id.dialogMessage).text =
                "Send PIN to $maskedEmail?"
            dialogView.findViewById<Button>(R.id.buttonPositive).text = "Send"
        } else {
            dialogView.findViewById<TextView>(R.id.dialogTitle).text = "No Recovery Email"
            dialogView.findViewById<TextView>(R.id.dialogMessage).text =
                "No recovery email was set.\n\nYou can unlock via API:\nGET /api/?cmd=unlockKiosk"
            dialogView.findViewById<Button>(R.id.buttonPositive).text = "OK"
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            if (email.isNotEmpty()) {
                sendPinRecoveryEmail(email, halitePrefs.lock.lockPin)
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    // ========== Helper Functions ==========

    /**
     * Apply lock - both app exit and settings menu are locked together
     */
    private fun applyLock(unlockMechanism: String) {
        halitePrefs.lock.lockAppExit = true
        halitePrefs.lock.lockSettings = true
        halitePrefs.lock.unlockMechanism = unlockMechanism
        halitePrefs.lock.unlockInterface = HalitePreferences.UNLOCK_INTERFACE_VISIBLE

        // Fresh lock must not inherit a settings-PIN grace window
        com.dashieapp.Dashie.halite.LockGate.clearGrace()

        Log.i(TAG, "App locked: mechanism=$unlockMechanism")

        onLockStateChanged?.invoke(true)
        onLockSettingsChanged?.invoke()
    }

    /**
     * Unlock app - clears both lock flags
     */
    private fun performUnlock() {
        halitePrefs.lock.lockAppExit = false
        halitePrefs.lock.lockSettings = false

        com.dashieapp.Dashie.halite.LockGate.clearGrace()

        Log.i(TAG, "App unlocked")

        onLockStateChanged?.invoke(false)
        onLockSettingsChanged?.invoke()
    }

    /**
     * Update PIN status text display
     */
    fun updatePinStatusText(textView: TextView) {
        if (halitePrefs.lock.hasPinSet) {
            val asterisks = "*".repeat(PIN_LENGTH)
            textView.text = asterisks
        } else {
            textView.text = "Not set"
        }
    }

    /**
     * Reset PIN - clears the PIN and shows confirmation
     */
    fun resetPin(onComplete: () -> Unit) {
        halitePrefs.lock.lockPin = ""
        halitePrefs.lock.lockRecoveryEmail = ""
        halitePrefs.lock.unlockMechanism = HalitePreferences.UNLOCK_MECHANISM_ANYONE
        Log.i(TAG, "PIN reset")
        onLockSettingsChanged?.invoke()
        showInfoDialog("PIN Reset", "Your PIN has been cleared.") {
            onComplete()
        }
    }

    /**
     * Check if PIN recovery via email is available
     */
    fun hasRecoveryEmail(): Boolean = halitePrefs.lock.lockRecoveryEmail.isNotEmpty()

    /**
     * Check if PIN is set
     */
    fun hasPinSet(): Boolean = halitePrefs.lock.hasPinSet

    /**
     * Show PIN recovery options dialog (from logo long-press)
     * - If recovery email set: offer to send PIN via email
     * - If no recovery email: offer to reset PIN
     */
    fun showPinRecoveryDialog(onComplete: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        if (hasRecoveryEmail()) {
            // Has recovery email - offer to send PIN
            val maskedEmail = maskEmail(halitePrefs.lock.lockRecoveryEmail)
            dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Recover PIN"
            dialogView.findViewById<TextView>(R.id.dialogMessage).text =
                "Send PIN recovery email to $maskedEmail?"
            dialogView.findViewById<Button>(R.id.buttonPositive).text = "Send Email"
        } else {
            // No recovery email - offer to reset PIN
            dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Reset PIN"
            dialogView.findViewById<TextView>(R.id.dialogMessage).text =
                "No recovery email is set. Do you want to reset your PIN?\n\nThis will clear your current PIN."
            dialogView.findViewById<Button>(R.id.buttonPositive).text = "Reset PIN"
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            if (hasRecoveryEmail()) {
                sendPinRecoveryEmail(halitePrefs.lock.lockRecoveryEmail, halitePrefs.lock.lockPin, onComplete)
            } else {
                // Reset PIN
                resetPin(onComplete)
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    private fun updatePinDisplay(digits: List<TextView>, text: String) {
        for (i in 0 until PIN_LENGTH) {
            val digit = digits.getOrNull(i) ?: continue
            digit.text = if (i < text.length) "●" else ""
        }
    }

    private fun verifyPin(enteredPin: String): Boolean {
        // Simple plain text comparison (dashboard access, not high security)
        return enteredPin == halitePrefs.lock.lockPin
    }

    private fun maskEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return email

        val name = parts[0]
        val domain = parts[1]

        val maskedName = if (name.length <= 2) {
            "${name[0]}***"
        } else {
            "${name.substring(0, 2)}***"
        }

        val domainParts = domain.split(".")
        val maskedDomain = if (domainParts.size >= 2) {
            "${domainParts[0].take(3)}***.${domainParts.last()}"
        } else {
            domain
        }

        return "$maskedName@$maskedDomain"
    }

    private fun showKeyboard(editText: EditText) {
        DiagnosticBuffer.debug("PIN", "showKeyboard called")
        // Single attempt with delay to ensure view is laid out
        // Using SHOW_FORCED to ensure keyboard appears on all devices including TV
        editText.postDelayed({
            editText.requestFocus()
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            val shown = imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
            DiagnosticBuffer.info("PIN", "showKeyboard result: shown=$shown hasFocus=${editText.hasFocus()}")
            Log.d(TAG, "showKeyboard: shown=$shown")
        }, 300)
    }

    private fun hideKeyboard(editText: EditText) {
        val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    private fun showErrorDialog(message: String, onDismiss: (() -> Unit)? = null) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_simple_message, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle)?.text = "Error"
        dialogView.findViewById<TextView>(R.id.dialogMessage)?.text = message
        dialogView.findViewById<Button>(R.id.buttonNegative)?.visibility = View.GONE

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonPositive)?.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }

        dialog.setOnCancelListener {
            onDismiss?.invoke()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnConfirm(dialogView)
    }

    private fun showInfoDialog(title: String, message: String, onDismiss: (() -> Unit)? = null) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_simple_message, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle)?.text = title
        dialogView.findViewById<TextView>(R.id.dialogMessage)?.text = message
        dialogView.findViewById<Button>(R.id.buttonNegative)?.visibility = View.GONE

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonPositive)?.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }

        dialog.setOnCancelListener {
            onDismiss?.invoke()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnConfirm(dialogView)
    }

    /**
     * Initiate unlock flow based on current settings.
     * - If PIN required: show PIN entry, then unlock directly
     * - If anyone can unlock: show confirmation, then unlock directly
     */
    fun initiateUnlock(onComplete: () -> Unit) {
        Log.d(TAG, "initiateUnlock: isLocked=${halitePrefs.lock.isLocked}, " +
                "lockSettings=${halitePrefs.lock.lockSettings}, " +
                "lockAppExit=${halitePrefs.lock.lockAppExit}, " +
                "isPinRequired=${halitePrefs.lock.isPinRequired}, " +
                "unlockMechanism=${halitePrefs.lock.unlockMechanism}, " +
                "hasPinSet=${halitePrefs.lock.hasPinSet}")

        if (!halitePrefs.lock.isLocked) {
            // Not locked, show lock settings
            Log.d(TAG, "Not locked - showing lock settings dialog")
            showLockSettingsDialog()
            return
        }

        if (halitePrefs.lock.isPinRequired) {
            // PIN required - show PIN entry, unlock directly on success
            Log.d(TAG, "PIN required - showing PIN entry dialog")
            showPinEntryDialog { success ->
                if (success) {
                    performUnlock()
                    onComplete()
                }
            }
        } else {
            // Anyone can unlock - show confirmation dialog
            Log.d(TAG, "Anyone can unlock - showing confirmation dialog")
            showUnlockConfirmationDialog(onComplete)
        }
    }

    /**
     * Send PIN recovery email via edge function
     */
    private fun sendPinRecoveryEmail(email: String, pin: String, onComplete: (() -> Unit)? = null) {
        val maskedEmail = maskEmail(email)

        uiScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    sendPinRecoveryRequest(email, pin)
                }

                if (success) {
                    showInfoDialog("PIN Sent", "Your PIN has been sent to $maskedEmail") {
                        onComplete?.invoke()
                    }
                } else {
                    showErrorDialog("Failed to send email. Please try again.") {
                        onComplete?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending PIN recovery email", e)
                showErrorDialog("Failed to send email: ${e.message}") {
                    onComplete?.invoke()
                }
            }
        }
    }

    /**
     * Make HTTP request to send-pin-recovery edge function
     */
    private fun sendPinRecoveryRequest(email: String, pin: String): Boolean {
        // Use BuildConfig values for endpoint and auth key
        val url = URL(BuildConfig.SUPABASE_URL + "/functions/v1/send-pin-recovery")
        val connection = url.openConnection() as HttpURLConnection
        val anonKey = BuildConfig.SUPABASE_ANON_KEY

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $anonKey")
            connection.setRequestProperty("apikey", anonKey)
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val payload = JSONObject().apply {
                put("email", email)
                put("pin", pin)
            }

            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            Log.i(TAG, "PIN recovery request: $responseCode")

            if (responseCode != 200) {
                try {
                    val errorStream = connection.errorStream
                    if (errorStream != null) {
                        val errorBody = errorStream.bufferedReader().readText()
                        Log.e(TAG, "PIN recovery error response: $errorBody")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not read error response", e)
                }
            }

            responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "PIN recovery request failed", e)
            false
        } finally {
            connection.disconnect()
        }
    }
}
