package com.dashieapp.Dashie.halite

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native login activity for Home Assistant authentication.
 * Styled to match Home Assistant's native login page.
 */
class HaLoginActivity : Activity() {

    companion object {
        private const val TAG = "HaLoginActivity"
        const val EXTRA_HA_URL = "ha_url"
        const val EXTRA_AUTO_LOGIN = "auto_login"
        const val RESULT_AUTH_CODE = "auth_code"
        const val RESULT_ACCESS_TOKEN = "access_token"
        const val RESULT_REFRESH_TOKEN = "refresh_token"
        const val RESULT_TOKEN_EXPIRY = "token_expiry"
        const val RESULT_RECONFIGURE = "reconfigure"  // User wants to go back to setup

        // TEST ONLY: Set to true to simulate login failures for testing circuit breaker
        private const val FORCE_LOGIN_FAILURE_FOR_TEST = false
    }

    // Activity-scoped coroutines, cancelled in onDestroy — login/token-exchange
    // coroutines must not outlive the Activity (they update views on completion).
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    // Theme-aware colors (determined at runtime based on system dark mode)
    private val isDarkMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val COLOR_BACKGROUND: Int
        get() = if (isDarkMode) 0xFF1C1C1E.toInt() else 0xFFFAFAFA.toInt()
    private val COLOR_CARD: Int
        get() = if (isDarkMode) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    private val COLOR_PRIMARY: Int
        get() = 0xFF03A9F4.toInt()  // HA blue stays the same
    private val COLOR_TEXT_PRIMARY: Int
        get() = if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()
    private val COLOR_TEXT_SECONDARY: Int
        get() = if (isDarkMode) 0xFFABABAB.toInt() else 0xFF727272.toInt()
    private val COLOR_INPUT_BG: Int
        get() = if (isDarkMode) 0xFF3A3A3C.toInt() else 0xFFF5F5F5.toInt()
    private val COLOR_INPUT_BORDER: Int
        get() = if (isDarkMode) 0x61FFFFFF.toInt() else 0x61000000.toInt()
    private val COLOR_ERROR: Int
        get() = 0xFFDB4437.toInt()  // Error color stays the same
    private val COLOR_DIVIDER: Int
        get() = if (isDarkMode) 0x1FFFFFFF.toInt() else 0x1F000000.toInt()

    /**
     * Custom drawable for visibility toggle icon (eye)
     */
    private inner class VisibilityIconDrawable(private var isVisible: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            style = Paint.Style.FILL
        }

        fun setVisible(visible: Boolean) {
            isVisible = visible
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val cx = width / 2
            val cy = height / 2
            val size = minOf(width, height) * 0.8f

            // Draw eye outline
            val eyeWidth = size * 0.8f
            val eyeHeight = size * 0.4f
            val eyeRect = RectF(
                cx - eyeWidth / 2,
                cy - eyeHeight / 2,
                cx + eyeWidth / 2,
                cy + eyeHeight / 2
            )

            // Eye shape (almond)
            val path = Path()
            path.moveTo(cx - eyeWidth / 2, cy)
            path.quadTo(cx - eyeWidth / 4, cy - eyeHeight / 2, cx, cy - eyeHeight / 2)
            path.quadTo(cx + eyeWidth / 4, cy - eyeHeight / 2, cx + eyeWidth / 2, cy)
            path.quadTo(cx + eyeWidth / 4, cy + eyeHeight / 2, cx, cy + eyeHeight / 2)
            path.quadTo(cx - eyeWidth / 4, cy + eyeHeight / 2, cx - eyeWidth / 2, cy)
            path.close()

            canvas.drawPath(path, paint)

            // Pupil
            val pupilRadius = size * 0.12f
            canvas.drawCircle(cx, cy, pupilRadius, fillPaint)

            // If hidden (password not visible), draw strike-through line
            if (!isVisible) {
                val strikeStartX = cx - eyeWidth / 2 - size * 0.1f
                val strikeStartY = cy + eyeHeight / 2 + size * 0.1f
                val strikeEndX = cx + eyeWidth / 2 + size * 0.1f
                val strikeEndY = cy - eyeHeight / 2 - size * 0.1f
                canvas.drawLine(strikeStartX, strikeStartY, strikeEndX, strikeEndY, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Result of submitting credentials — either success or MFA challenge */
    private sealed class CredentialResult {
        data class Success(val authCode: String) : CredentialResult()
        data class MfaRequired(val flowId: String) : CredentialResult()
        data object Failed : CredentialResult()
    }

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var visibilityToggle: ImageView
    private lateinit var visibilityIcon: VisibilityIconDrawable
    private lateinit var loginButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var keepLoggedInCheckbox: CheckBox
    private lateinit var totpInput: EditText
    private lateinit var mfaInstructionText: TextView
    private lateinit var passwordContainer: FrameLayout
    private lateinit var backButton: ImageView
    private lateinit var prefs: HalitePreferences

    private var haUrl: String = ""
    private var clientId: String = ""
    private var redirectUri: String = ""
    private var isPasswordVisible = false
    private var isAutoLogin = false
    private var isMfaStep = false
    private var pendingMfaFlowId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = HalitePreferences(this)

        haUrl = intent.getStringExtra(EXTRA_HA_URL) ?: ""
        isAutoLogin = intent.getBooleanExtra(EXTRA_AUTO_LOGIN, false)

        if (haUrl.isEmpty()) {
            Log.e(TAG, "No HA URL provided")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        clientId = "$haUrl/"
        redirectUri = "$haUrl/?auth_callback=1"

        Log.i(TAG, "Starting HA login for: $haUrl (autoLogin=$isAutoLogin)")

        // Default: keep soft keyboard hidden on launch — d-pad/TV users
        // shouldn't have a keyboard pop up immediately after the API toggle
        // step on the kiosk HA config screen. CENTER on a focused text
        // field will explicitly show it (see onKeyDown handler below).
        // Amazon-specific override preserved with the same hidden-state
        // flag so Fire tablet behavior matches.
        val baseFlag = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        val panFlag = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        if (android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) {
            window.setSoftInputMode(baseFlag or panFlag)
        } else {
            window.setSoftInputMode(baseFlag or panFlag)
        }

        setupUI()

        // Pre-fill saved credentials
        if (prefs.connection.hasStoredCredentials()) {
            usernameInput.setText(prefs.connection.haUsername)
            passwordInput.setText(prefs.connection.haPassword)
        }

        // Set checkbox state from preferences
        keepLoggedInCheckbox.isChecked = prefs.connection.keepLoggedIn

        // Auto-login if requested and credentials exist
        if (isAutoLogin && prefs.connection.shouldAutoLogin()) {
            Log.i(TAG, "Auto-login enabled, attempting login with saved credentials")
            attemptLogin()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun setupUI() {
        // Root - light gray background like HA
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Back button at top-left
        backButton = ImageView(this).apply {
            val backArrow = object : Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = COLOR_TEXT_SECONDARY
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                override fun draw(canvas: Canvas) {
                    val w = bounds.width().toFloat()
                    val h = bounds.height().toFloat()
                    val cx = w / 2; val cy = h / 2
                    val size = minOf(w, h) * 0.35f
                    // Chevron left arrow
                    canvas.drawLine(cx + size * 0.3f, cy - size, cx - size * 0.3f, cy, paint)
                    canvas.drawLine(cx - size * 0.3f, cy, cx + size * 0.3f, cy + size, paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
            setImageDrawable(backArrow)
            val restColor = if (isDarkMode) 0x33FFFFFF.toInt() else 0x1A000000.toInt()
            val bgCircle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(restColor)
            }
            background = bgCircle
            setOnClickListener {
                val resultIntent = Intent().apply { putExtra(RESULT_RECONFIGURE, true) }
                setResult(RESULT_CANCELED, resultIntent)
                finish()
            }
            isClickable = true
            isFocusable = true
            // D-pad focus highlight so Fire TV users can see the back button
            // is selected as they cycle to it (D.5).
            setOnFocusChangeListener { _, hasFocus ->
                bgCircle.setColor(if (hasFocus) COLOR_PRIMARY else restColor)
            }
        }
        // backButton is added to a non-scrolling overlay frame at the end of
        // setupUI() — NOT to rootLayout. rootLayout lives inside the ScrollView,
        // so a back button placed here scrolls off-screen once the login card
        // is taller than the viewport (D.29).
        val backSize = dpToPx(36)

        // Content wrapper with padding
        val contentWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(16), dpToPx(32), dpToPx(16), dpToPx(32))
        }

        // HA Logo - use the actual drawable resource (68dp = 80dp * 0.85)
        val logoSize = dpToPx(68)
        val logoView = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@HaLoginActivity, R.drawable.icon_homeassistant_blue))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        contentWrapper.addView(logoView, LinearLayout.LayoutParams(logoSize, logoSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dpToPx(24)
        })

        // Card container
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))

            val cardBg = GradientDrawable().apply {
                setColor(COLOR_CARD)
                cornerRadius = dpToPx(12).toFloat()
                setStroke(1, COLOR_DIVIDER)
            }
            background = cardBg
            elevation = dpToPx(2).toFloat()
        }

        // Title - "Login to Home Assistant"
        val titleText = TextView(this).apply {
            text = "Login to Home Assistant"
            textSize = 22f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(8), 0, dpToPx(20))
        }
        card.addView(titleText)

        // Username field with filled style
        usernameInput = createFilledTextField("Username").apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        card.addView(usernameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(56)
        ).apply {
            bottomMargin = dpToPx(16)
        })

        // Password field container (password + visibility toggle)
        passwordContainer = FrameLayout(this)

        // Password field - explicitly set to password type for dots
        passwordInput = createFilledTextField("Password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Re-apply typeface after setting inputType (password mode overrides it to monospace)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            imeOptions = EditorInfo.IME_ACTION_DONE
            // Add right padding for visibility icon
            setPadding(dpToPx(16), dpToPx(20), dpToPx(48), dpToPx(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    attemptLogin()
                    true
                } else false
            }
        }
        passwordContainer.addView(passwordInput, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dpToPx(56)
        ))

        // Visibility toggle icon (eye in circle on right side)
        val toggleSize = dpToPx(36)
        visibilityIcon = VisibilityIconDrawable(false)
        visibilityToggle = ImageView(this).apply {
            setImageDrawable(visibilityIcon)
            // Background circle
            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x0F000000) // Light gray overlay
            }
            background = circleBg
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            setOnClickListener { togglePasswordVisibility() }
            isClickable = true
            isFocusable = true
        }
        passwordContainer.addView(visibilityToggle, FrameLayout.LayoutParams(
            toggleSize,
            toggleSize
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            marginEnd = dpToPx(8)
        })

        card.addView(passwordContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(56)
        ).apply {
            bottomMargin = dpToPx(16)
        })

        // MFA instruction text (hidden until MFA step)
        mfaInstructionText = TextView(this).apply {
            text = "Enter the code from your authenticator app"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 0, 0, dpToPx(12))
        }
        card.addView(mfaInstructionText)

        // TOTP code input (hidden until MFA step)
        totpInput = createFilledTextField("6-digit code").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            textSize = 24f
            letterSpacing = 0.3f
            visibility = View.GONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    attemptMfaSubmit()
                    true
                } else false
            }
        }
        card.addView(totpInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(56)
        ).apply {
            bottomMargin = dpToPx(16)
        })

        // Error text
        errorText = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_ERROR)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 0, 0, dpToPx(8))
        }
        card.addView(errorText)

        // Button container for Login and Cancel
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Login button - smaller, centered, rounded pill shape
        loginButton = Button(this).apply {
            text = "LOG IN"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = true
            stateListAnimator = null
            letterSpacing = 0.05f
            setPadding(dpToPx(32), dpToPx(12), dpToPx(32), dpToPx(12))

            val buttonBg = GradientDrawable().apply {
                setColor(COLOR_PRIMARY)
                cornerRadius = dpToPx(24).toFloat() // Pill shape
            }
            background = buttonBg

            setOnClickListener { attemptLogin() }
        }
        buttonContainer.addView(loginButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(48)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Cancel button - text style, below login
        // Returns to the setup screen so user can reconfigure dashboard settings
        cancelButton = Button(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAllCaps = false
            stateListAnimator = null
            background = null  // Text button, no background
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))

            setOnClickListener {
                // Tell MainActivity to return to setup screen
                val resultIntent = Intent().apply {
                    putExtra(RESULT_RECONFIGURE, true)
                }
                setResult(RESULT_CANCELED, resultIntent)
                finish()
            }
        }
        buttonContainer.addView(cancelButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(8)
        })

        card.addView(buttonContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(8)
        })

        // "Keep me logged in" checkbox
        keepLoggedInCheckbox = CheckBox(this).apply {
            text = "Keep me logged in"
            textSize = 14f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_PRIMARY)
            isChecked = true // Default to checked
            setOnCheckedChangeListener { _, isChecked ->
                prefs.connection.keepLoggedIn = isChecked
            }
        }
        card.addView(keepLoggedInCheckbox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START
            topMargin = dpToPx(16)
        })

        // Progress bar
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
        }
        card.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(16)
        })

        // Add card to content wrapper
        contentWrapper.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // Max width 400dp like HA
            width = minOf(dpToPx(400), resources.displayMetrics.widthPixels - dpToPx(32))
        })

        // Generate IDs for focus chain
        usernameInput.id = View.generateViewId()
        passwordInput.id = View.generateViewId()
        visibilityToggle.id = View.generateViewId()
        loginButton.id = View.generateViewId()
        cancelButton.id = View.generateViewId()
        keepLoggedInCheckbox.id = View.generateViewId()
        totpInput.id = View.generateViewId()

        // Set up complete D-pad focus chain:
        // Username -> Password -> Login -> Cancel -> Keep Logged In -> back to Username
        usernameInput.nextFocusDownId = passwordInput.id
        usernameInput.nextFocusUpId = keepLoggedInCheckbox.id

        passwordInput.nextFocusDownId = loginButton.id
        passwordInput.nextFocusUpId = usernameInput.id

        loginButton.nextFocusDownId = cancelButton.id
        loginButton.nextFocusUpId = passwordInput.id

        cancelButton.nextFocusDownId = keepLoggedInCheckbox.id
        cancelButton.nextFocusUpId = loginButton.id

        keepLoggedInCheckbox.nextFocusDownId = usernameInput.id
        keepLoggedInCheckbox.nextFocusUpId = cancelButton.id

        // Make buttons focusable with D-pad
        loginButton.isFocusable = true
        cancelButton.isFocusable = true

        // Add focus change listeners for visual feedback on buttons
        loginButton.setOnFocusChangeListener { v, hasFocus ->
            val button = v as Button
            val bg = button.background as? GradientDrawable
            if (hasFocus) {
                bg?.setStroke(dpToPx(3), 0xFFFFFFFF.toInt())  // White border when focused
            } else {
                bg?.setStroke(0, 0)  // No border
            }
        }

        cancelButton.setOnFocusChangeListener { v, hasFocus ->
            val button = v as Button
            if (hasFocus) {
                button.setTextColor(COLOR_PRIMARY)  // Highlight text when focused
                button.setBackgroundColor(0x20000000)  // Light background
            } else {
                button.setTextColor(COLOR_TEXT_SECONDARY)
                button.background = null
            }
        }

        keepLoggedInCheckbox.setOnFocusChangeListener { v, hasFocus ->
            val checkbox = v as CheckBox
            if (hasFocus) {
                checkbox.setBackgroundColor(0x20000000)  // Light background
            } else {
                checkbox.background = null
            }
        }

        // Wrap in ScrollView
        val scrollView = android.widget.ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFillViewport = true
        }

        rootLayout.addView(contentWrapper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        })

        // Dismiss keyboard on background tap
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideKeyboard()
                usernameInput.clearFocus()
                passwordInput.clearFocus()
            }
            false
        }

        scrollView.addView(rootLayout)

        // Floating back button: lives in a non-scrolling overlay frame so it
        // stays pinned to the top-left even when the login card (e.g. with the
        // 2FA / TOTP fields shown) is taller than the viewport and the content
        // scrolls. Previously the back button sat inside the ScrollView content
        // and scrolled out of view, making it look missing (D.29).
        val overlayFrame = FrameLayout(this)
        overlayFrame.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        overlayFrame.addView(backButton, FrameLayout.LayoutParams(backSize, backSize).apply {
            gravity = Gravity.START or Gravity.TOP
            leftMargin = dpToPx(16)
            topMargin = dpToPx(16)
        })
        setContentView(overlayFrame)

        // For D-pad navigation: Start with username field focused
        // This prevents the first D-pad press from scrolling
        usernameInput.requestFocus()
    }

    /**
     * Create a filled text field matching HA's Material Design style.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createFilledTextField(hint: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(COLOR_TEXT_PRIMARY)
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setPadding(dpToPx(16), dpToPx(20), dpToPx(16), dpToPx(12))
            setSingleLine(true)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            // Don't pop the soft keyboard automatically when this input gets
            // focus — d-pad users move focus through fields without wanting
            // the IME each time. CENTER on a focused EditText (handled in
            // onKeyDown) explicitly shows the keyboard for editing.
            showSoftInputOnFocus = false

            // Touch devices (e.g. Fire tablet) have no d-pad CENTER, so with
            // showSoftInputOnFocus=false a tap would never open the keyboard.
            // Show the IME explicitly on tap. showSoftInputOnFocus is flipped
            // back on first (some Android versions suppress showSoftInput
            // entirely while it's false — see commit b49997e3); from here on
            // the user has opted in to typing. Returns false so the EditText
            // still handles cursor placement / selection normally.
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val et = v as EditText
                    et.showSoftInputOnFocus = true
                    if (!et.hasFocus()) et.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                }
                false
            }

            // Store reference to background for focus changes
            val bgDrawable = GradientDrawable().apply {
                setColor(COLOR_INPUT_BG)
                cornerRadii = floatArrayOf(
                    dpToPx(4).toFloat(), dpToPx(4).toFloat(),
                    dpToPx(4).toFloat(), dpToPx(4).toFloat(),
                    0f, 0f,
                    0f, 0f
                )
            }

            val borderDrawable = GradientDrawable().apply {
                setColor(COLOR_INPUT_BORDER)
            }

            val layers = arrayOf<Drawable>(bgDrawable, borderDrawable)
            val layerDrawable = LayerDrawable(layers)
            layerDrawable.setLayerInset(0, 0, 0, 0, dpToPx(1))
            layerDrawable.setLayerInset(1, 0, dpToPx(55), 0, 0)

            background = layerDrawable

            // Focus change listener for D-pad navigation visual feedback
            setOnFocusChangeListener { v, hasFocus ->
                val editText = v as EditText
                val layerBg = editText.background as? LayerDrawable
                val topLayer = layerBg?.getDrawable(0) as? GradientDrawable
                val bottomBorder = layerBg?.getDrawable(1) as? GradientDrawable

                if (hasFocus) {
                    // Orange border when focused (Dashie orange)
                    topLayer?.setStroke(dpToPx(2), 0xFFFF9500.toInt())
                    bottomBorder?.setColor(0xFFFF9500.toInt())
                } else {
                    // Default border
                    topLayer?.setStroke(0, 0)
                    bottomBorder?.setColor(COLOR_INPUT_BORDER)
                }
            }
        }
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible

        if (isPasswordVisible) {
            passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // Re-apply typeface (password mode overrides to monospace)
        passwordInput.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        // Update icon
        visibilityIcon.setVisible(isPasswordVisible)
        visibilityToggle.invalidate()

        // Preserve cursor position
        passwordInput.setSelection(passwordInput.text.length)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun attemptLogin() {
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (username.isEmpty()) {
            showError("Please enter a username")
            return
        }
        if (password.isEmpty()) {
            showError("Please enter a password")
            return
        }

        setLoading(true)
        hideError()
        hideKeyboard()

        // TEST ONLY: Simulate login failure to test circuit breaker
        if (FORCE_LOGIN_FAILURE_FOR_TEST) {
            Log.w(TAG, "🧪 TEST MODE: Forcing login failure")
            activityScope.launch {
                kotlinx.coroutines.delay(1000) // Simulate network delay
                showError("TEST: Simulated login failure")
                setLoading(false)
            }
            return
        }

        activityScope.launch(Dispatchers.IO) {
            try {
                val flowId = initializeLoginFlow()
                if (flowId == null) {
                    withContext(Dispatchers.Main) {
                        showError("Could not connect to Home Assistant")
                        setLoading(false)
                    }
                    return@launch
                }
                Log.d(TAG, "Got flow_id: $flowId")

                val credResult = submitCredentials(flowId, username, password)
                when (credResult) {
                    is CredentialResult.Success -> {
                        val tokenResponse = exchangeCodeForToken(credResult.authCode)
                        withContext(Dispatchers.Main) {
                            if (tokenResponse != null) {
                                onLoginComplete(username, password, tokenResponse)
                            } else {
                                showError("Token exchange failed")
                                setLoading(false)
                            }
                        }
                    }
                    is CredentialResult.MfaRequired -> {
                        Log.i(TAG, "MFA required, showing TOTP input")
                        withContext(Dispatchers.Main) {
                            pendingMfaFlowId = credResult.flowId
                            showMfaStep()
                        }
                    }
                    is CredentialResult.Failed -> {
                        withContext(Dispatchers.Main) {
                            showError("Invalid username or password")
                            setLoading(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                withContext(Dispatchers.Main) {
                    showError("Connection error: ${e.message}")
                    setLoading(false)
                }
            }
        }
    }

    private fun onLoginComplete(username: String, password: String, tokenResponse: TokenResponse) {
        Log.i(TAG, "Login successful, saving credentials and tokens")
        prefs.connection.haUsername = username
        prefs.connection.haPassword = password
        prefs.connection.keepLoggedIn = keepLoggedInCheckbox.isChecked

        hideKeyboard()

        val resultIntent = Intent().apply {
            putExtra(RESULT_ACCESS_TOKEN, tokenResponse.accessToken)
            putExtra(RESULT_REFRESH_TOKEN, tokenResponse.refreshToken)
            putExtra(RESULT_TOKEN_EXPIRY, tokenResponse.expiresIn)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun showMfaStep() {
        isMfaStep = true
        setLoading(false)
        hideError()

        // Hide credential fields
        usernameInput.visibility = View.GONE
        passwordContainer.visibility = View.GONE

        // Show MFA fields
        mfaInstructionText.visibility = View.VISIBLE
        totpInput.visibility = View.VISIBLE
        totpInput.text.clear()
        totpInput.requestFocus()

        // Update button text
        loginButton.text = "VERIFY"
        loginButton.setOnClickListener { attemptMfaSubmit() }

        // Show keyboard for TOTP input
        totpInput.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(totpInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun attemptMfaSubmit() {
        val code = totpInput.text.toString().trim()
        if (code.isEmpty()) {
            showError("Please enter your verification code")
            return
        }

        val flowId = pendingMfaFlowId
        if (flowId == null) {
            showError("Authentication session expired. Please try again.")
            return
        }

        setLoading(true)
        hideError()
        hideKeyboard()

        activityScope.launch(Dispatchers.IO) {
            try {
                val authCode = submitMfaCode(flowId, code)
                if (authCode != null) {
                    val tokenResponse = exchangeCodeForToken(authCode)
                    withContext(Dispatchers.Main) {
                        if (tokenResponse != null) {
                            onLoginComplete(
                                usernameInput.text.toString().trim(),
                                passwordInput.text.toString(),
                                tokenResponse
                            )
                        } else {
                            showError("Token exchange failed")
                            setLoading(false)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showError("Invalid verification code")
                        setLoading(false)
                        totpInput.text.clear()
                        totpInput.requestFocus()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MFA submit error", e)
                withContext(Dispatchers.Main) {
                    showError("Connection error: ${e.message}")
                    setLoading(false)
                }
            }
        }
    }

    /**
     * Token response from HA's /auth/token endpoint
     */
    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long
    )

    private fun initializeLoginFlow(): String? {
        val url = URL("$haUrl/auth/login_flow")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val payload = JSONObject().apply {
                put("client_id", clientId)
                put("handler", org.json.JSONArray().apply {
                    put("homeassistant")
                    put(JSONObject.NULL)
                })
                put("redirect_uri", redirectUri)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "Login flow response: $response")
                return JSONObject(response).getString("flow_id")
            } else {
                Log.e(TAG, "login_flow error (${connection.responseCode}): ${connection.errorStream?.bufferedReader()?.readText()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in initializeLoginFlow", e)
        } finally {
            connection.disconnect()
        }
        return null
    }

    private fun submitCredentials(flowId: String, username: String, password: String): CredentialResult {
        val url = URL("$haUrl/auth/login_flow/$flowId")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val payload = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("client_id", clientId)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "Credentials response: $response")
                val json = JSONObject(response)
                if (json.has("result")) {
                    return CredentialResult.Success(json.getString("result"))
                }
                // MFA step: HA returns type=form with step_id=mfa when 2FA is enabled
                val stepId = json.optString("step_id", "")
                if (stepId == "mfa") {
                    val mfaFlowId = json.optString("flow_id", flowId)
                    Log.i(TAG, "MFA step detected (step_id=$stepId, flow_id=$mfaFlowId)")
                    return CredentialResult.MfaRequired(mfaFlowId)
                }
                // HA returns 200 with "errors" field when credentials are wrong
                if (json.has("errors")) {
                    Log.w(TAG, "Invalid credentials: ${json.optJSONObject("errors")}")
                } else {
                    Log.w(TAG, "No result in credentials response: $response")
                }
            } else {
                Log.e(TAG, "credentials error (${connection.responseCode}): ${connection.errorStream?.bufferedReader()?.readText()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in submitCredentials", e)
        } finally {
            connection.disconnect()
        }
        return CredentialResult.Failed
    }

    /**
     * Submit TOTP code for the MFA step.
     * Returns the auth code on success, null on failure.
     */
    private fun submitMfaCode(flowId: String, code: String): String? {
        val url = URL("$haUrl/auth/login_flow/$flowId")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val payload = JSONObject().apply {
                put("code", code)
                put("client_id", clientId)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "MFA response: $response")
                val json = JSONObject(response)
                if (json.has("result")) {
                    return json.getString("result")
                }
                if (json.has("errors")) {
                    Log.w(TAG, "Invalid MFA code: ${json.optJSONObject("errors")}")
                } else {
                    Log.w(TAG, "No result in MFA response: $response")
                }
            } else {
                Log.e(TAG, "MFA error (${connection.responseCode}): ${connection.errorStream?.bufferedReader()?.readText()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in submitMfaCode", e)
        } finally {
            connection.disconnect()
        }
        return null
    }

    /**
     * Exchange the auth code for access and refresh tokens
     * Uses HA's /auth/token endpoint with grant_type=authorization_code
     */
    private fun exchangeCodeForToken(authCode: String): TokenResponse? {
        return when (val result = com.dashieapp.Dashie.halite.auth.HaOAuthClient
            .exchangeAuthorizationCode(haUrl, authCode, clientId)) {
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.Success -> {
                Log.i(TAG, "Token exchange successful: expires_in=${result.expiresIn}s, " +
                    "access_token length=${result.accessToken.length}, " +
                    "refresh_token length=${result.refreshToken.length}")
                TokenResponse(result.accessToken, result.refreshToken, result.expiresIn)
            }
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.HttpError -> {
                Log.e(TAG, "Token exchange error: ${result.code} - ${result.body}")
                null
            }
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.NetworkError -> {
                Log.e(TAG, "Token exchange exception: ${result.message}")
                null
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        loginButton.isEnabled = !loading
        if (isMfaStep) {
            loginButton.text = if (loading) "VERIFYING..." else "VERIFY"
        } else {
            loginButton.text = if (loading) "LOGGING IN..." else "LOG IN"
        }
        cancelButton.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        usernameInput.isEnabled = !loading
        passwordInput.isEnabled = !loading
        visibilityToggle.isEnabled = !loading
        keepLoggedInCheckbox.isEnabled = !loading
        totpInput.isEnabled = !loading
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back button just cancels - doesn't request reconfigure
        // This prevents the app from going to setup screen when user presses back
        // Only the Cancel button explicitly requests reconfigure
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    /**
     * Handle D-pad navigation for Fire TV.
     * Fire TV sends D-pad events that we need to translate to focus changes.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // backButton is first in the cycle so a Fire TV user can always
        // d-pad to it and escape the HA login screen (D.5) — without it the
        // d-pad cycle had no exit affordance other than the in-card Cancel.
        val focusableViews = if (isMfaStep) {
            listOf(backButton, totpInput, loginButton, cancelButton)
        } else {
            listOf(backButton, usernameInput, passwordInput, loginButton, cancelButton, keepLoggedInCheckbox)
        }
        val currentFocus = currentFocus

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                val currentIndex = focusableViews.indexOf(currentFocus)
                val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % focusableViews.size else 0
                focusableViews[nextIndex].requestFocus()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                val currentIndex = focusableViews.indexOf(currentFocus)
                val prevIndex = if (currentIndex > 0) currentIndex - 1 else focusableViews.size - 1
                focusableViews[prevIndex].requestFocus()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER -> {
                // Activate the focused element
                when (currentFocus) {
                    backButton -> {
                        backButton.performClick()
                        return true
                    }
                    loginButton -> {
                        attemptLogin()
                        return true
                    }
                    cancelButton -> {
                        cancelButton.performClick()
                        return true
                    }
                    keepLoggedInCheckbox -> {
                        keepLoggedInCheckbox.toggle()
                        return true
                    }
                    visibilityToggle -> {
                        togglePasswordVisibility()
                        return true
                    }
                    is EditText -> {
                        // CENTER on a focused text field: open the keyboard.
                        // showSoftInputOnFocus=false suppresses the IME on
                        // focus events; flip it back on, re-trigger focus,
                        // and explicitly call showSoftInput so the keyboard
                        // reliably appears across Android versions.
                        // Subsequent focus changes will now show the IME —
                        // that's fine, the user has explicitly opted in
                        // by pressing CENTER on a text field.
                        currentFocus.showSoftInputOnFocus = true
                        currentFocus.clearFocus()
                        currentFocus.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(currentFocus, InputMethodManager.SHOW_IMPLICIT)
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
