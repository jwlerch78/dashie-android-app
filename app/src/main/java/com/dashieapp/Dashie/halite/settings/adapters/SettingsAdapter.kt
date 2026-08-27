package com.dashieapp.Dashie.halite.settings.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsProfiler
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import androidx.appcompat.widget.SwitchCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation

/**
 * RecyclerView adapter for settings items.
 * Handles multiple item types with different ViewHolders.
 * Applies iOS-style grouped section backgrounds with rounded corners.
 */
class SettingsAdapter(
    private val onItemClick: (SettingsItem) -> Unit,
    private val onToggleChange: (SettingsItem.Toggle, Boolean) -> Unit,
    private val onSliderChange: (SettingsItem.Slider, Int) -> Unit = { _, _ -> },
    private val onTextChange: (SettingsItem.TextInput, String) -> Unit = { _, _ -> },
    private val onPreviewClick: (SettingsItem.Checkmark) -> Unit = { }
) : ListAdapter<SettingsItem, RecyclerView.ViewHolder>(SettingsDiffCallback()) {

    companion object {
        private const val TYPE_NAVIGATION = 0
        private const val TYPE_TOGGLE = 1
        private const val TYPE_ACTION = 2
        private const val TYPE_SECTION_HEADER = 3
        private const val TYPE_INFO = 4
        private const val TYPE_VALUE_DISPLAY = 5
        private const val TYPE_CHECKMARK = 6
        private const val TYPE_SLIDER = 7
        private const val TYPE_TEXT_INPUT = 8
        private const val TYPE_PICKER = 9
        private const val TYPE_CONFIDENCE_BAR = 10
    }

    /**
     * Position of item within its section for determining corner styling.
     */
    enum class SectionPosition {
        SINGLE,  // Only item in section - all corners rounded
        TOP,     // First item - top corners rounded
        MIDDLE,  // Middle item - no corners rounded
        BOTTOM   // Last item - bottom corners rounded
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SettingsItem.Navigation -> TYPE_NAVIGATION
            is SettingsItem.Toggle -> TYPE_TOGGLE
            is SettingsItem.Action -> TYPE_ACTION
            is SettingsItem.SectionHeader -> TYPE_SECTION_HEADER
            is SettingsItem.Info -> TYPE_INFO
            is SettingsItem.ValueDisplay -> TYPE_VALUE_DISPLAY
            is SettingsItem.Checkmark -> TYPE_CHECKMARK
            is SettingsItem.Slider -> TYPE_SLIDER
            is SettingsItem.TextInput -> TYPE_TEXT_INPUT
            is SettingsItem.Picker -> TYPE_PICKER
            is SettingsItem.ConfidenceBar -> TYPE_CONFIDENCE_BAR
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return SettingsProfiler.measure("Adapter.onCreateViewHolder(type=$viewType)") { when (viewType) {
            TYPE_NAVIGATION -> NavigationViewHolder(
                inflater.inflate(R.layout.item_settings_menu, parent, false)
            )
            TYPE_TOGGLE -> ToggleViewHolder(
                inflater.inflate(R.layout.item_settings_toggle, parent, false)
            )
            TYPE_ACTION -> ActionViewHolder(
                inflater.inflate(R.layout.item_settings_action, parent, false)
            )
            TYPE_SECTION_HEADER -> SectionHeaderViewHolder(
                inflater.inflate(R.layout.item_settings_section_header, parent, false)
            )
            TYPE_INFO -> InfoViewHolder(
                inflater.inflate(R.layout.item_settings_info, parent, false)
            )
            TYPE_VALUE_DISPLAY -> ValueDisplayViewHolder(
                inflater.inflate(R.layout.item_settings_menu, parent, false)
            )
            TYPE_CHECKMARK -> CheckmarkViewHolder(
                inflater.inflate(R.layout.item_settings_menu, parent, false)
            )
            TYPE_SLIDER -> SliderViewHolder(
                inflater.inflate(R.layout.item_settings_slider, parent, false)
            )
            TYPE_TEXT_INPUT -> TextInputViewHolder(
                inflater.inflate(R.layout.item_settings_text_input, parent, false)
            )
            TYPE_PICKER -> PickerViewHolder(
                inflater.inflate(R.layout.item_settings_menu, parent, false)
            )
            TYPE_CONFIDENCE_BAR -> ConfidenceBarViewHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        } }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val token = SettingsProfiler.start("Adapter.onBind(pos=$position, ${item.id})")
        val sectionPosition = getSectionPosition(position)
        when (item) {
            is SettingsItem.Navigation -> (holder as NavigationViewHolder).bind(item, sectionPosition)
            is SettingsItem.Toggle -> (holder as ToggleViewHolder).bind(item, sectionPosition)
            is SettingsItem.Action -> (holder as ActionViewHolder).bind(item, sectionPosition)
            is SettingsItem.SectionHeader -> (holder as SectionHeaderViewHolder).bind(item)
            is SettingsItem.Info -> (holder as InfoViewHolder).bind(item)
            is SettingsItem.ValueDisplay -> (holder as ValueDisplayViewHolder).bind(item, sectionPosition)
            is SettingsItem.Checkmark -> (holder as CheckmarkViewHolder).bind(item, sectionPosition)
            is SettingsItem.Slider -> (holder as SliderViewHolder).bind(item, sectionPosition)
            is SettingsItem.TextInput -> (holder as TextInputViewHolder).bind(item, sectionPosition)
            is SettingsItem.Picker -> (holder as PickerViewHolder).bind(item, sectionPosition)
            is SettingsItem.ConfidenceBar -> (holder as ConfidenceBarViewHolder).bind(item, sectionPosition)
        }
        SettingsProfiler.end(token)
    }

    /**
     * Determines the position of an item within its section.
     * Used to apply correct corner styling.
     */
    private fun getSectionPosition(position: Int): SectionPosition {
        val item = getItem(position)

        // Section headers and info items don't need position styling
        if (item is SettingsItem.SectionHeader || item is SettingsItem.Info) {
            return SectionPosition.SINGLE
        }

        // Find the start of this section (previous section header or start of list)
        var sectionStart = position
        for (i in position - 1 downTo 0) {
            val prev = getItem(i)
            if (prev is SettingsItem.SectionHeader) {
                sectionStart = i + 1
                break
            }
            if (i == 0) {
                sectionStart = 0
            }
        }

        // Find the end of this section (next section header or end of list)
        var sectionEnd = position
        for (i in position + 1 until itemCount) {
            val next = getItem(i)
            if (next is SettingsItem.SectionHeader) {
                sectionEnd = i - 1
                break
            }
            if (i == itemCount - 1) {
                sectionEnd = i
            }
        }

        // Determine position
        val isFirst = position == sectionStart
        val isLast = position == sectionEnd

        return when {
            isFirst && isLast -> SectionPosition.SINGLE
            isFirst -> SectionPosition.TOP
            isLast -> SectionPosition.BOTTOM
            else -> SectionPosition.MIDDLE
        }
    }

    /**
     * Gets the appropriate background drawable for the section position.
     */
    private fun getBackgroundForPosition(position: SectionPosition): Int {
        return when (position) {
            SectionPosition.SINGLE -> R.drawable.settings_cell_bg_single
            SectionPosition.TOP -> R.drawable.settings_cell_bg_top
            SectionPosition.MIDDLE -> R.drawable.settings_cell_bg_middle
            SectionPosition.BOTTOM -> R.drawable.settings_cell_bg_bottom
        }
    }

    /**
     * Applies section styling to an item view (background and margins).
     */
    private fun applySectionStyling(itemView: View, position: SectionPosition) {
        itemView.setBackgroundResource(getBackgroundForPosition(position))

        // Apply horizontal margins for grouped appearance
        val marginHorizontal = itemView.context.resources
            .getDimensionPixelSize(R.dimen.settings_section_margin_horizontal)

        (itemView.layoutParams as? RecyclerView.LayoutParams)?.let { params ->
            params.marginStart = marginHorizontal
            params.marginEnd = marginHorizontal
            itemView.layoutParams = params
        }
    }

    // --- ViewHolders ---

    inner class NavigationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvSublabel: TextView = view.findViewById(R.id.tvSublabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val ivChevron: ImageView = view.findViewById(R.id.ivChevron)
        private val vColorDot: View = view.findViewById(R.id.vColorDot)
        private val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        private val defaultSublabelColor = tvSublabel.currentTextColor

        fun bind(item: SettingsItem.Navigation, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label

            if (item.sublabel != null) {
                tvSublabel.text = item.sublabel
                tvSublabel.setTextColor(
                    if (item.sublabel.startsWith("\""))
                        androidx.core.content.ContextCompat.getColor(itemView.context, R.color.dashie_orange)
                    else defaultSublabelColor
                )
                tvSublabel.visibility = View.VISIBLE
            } else {
                tvSublabel.visibility = View.GONE
            }

            if (item.value != null) {
                tvValue.text = item.value
                tvValue.visibility = View.VISIBLE
            } else {
                tvValue.visibility = View.GONE
            }
            // Chevron vs checkmark
            if (item.checked != null) {
                ivChevron.visibility = View.GONE
                if (item.checked) {
                    tvValue.text = "✓"
                    tvValue.setTextColor(0xFFFF8C00.toInt()) // Orange checkmark
                    tvValue.visibility = View.VISIBLE
                } else {
                    tvValue.visibility = View.GONE
                }
                // All items at full opacity — the checkmark alone signals selection,
                // matching the Checkmark item style used elsewhere.
                tvLabel.alpha = 1.0f
                tvSublabel?.alpha = 1.0f
            } else {
                ivChevron.visibility = View.VISIBLE
                // Reset alpha and value color for non-checkmark items (prevent recycled ViewHolder bleed)
                tvLabel.alpha = 1.0f
                tvSublabel?.alpha = 1.0f
                tvValue.setTextColor(tvValue.context.getColor(R.color.settings_text_value))
            }

            // Avatar image (circular, takes precedence over color dot)
            if (item.avatarUrl != null) {
                ivAvatar.visibility = View.VISIBLE
                vColorDot.visibility = View.GONE
                loadAvatar(item.avatarUrl, item.colorDot, item.label)
            } else if (item.colorDot != null) {
                // Color dot fallback (no avatar URL)
                ivAvatar.visibility = View.GONE
                val colorStr = item.colorDot?.takeIf { it.isNotEmpty() }
                if (colorStr != null) {
                    vColorDot.visibility = View.VISIBLE
                    val parsedColor = try {
                        android.graphics.Color.parseColor(colorStr)
                    } catch (e: Exception) {
                        android.graphics.Color.parseColor("#607D8B") // fallback gray
                    }
                    val drawable = vColorDot.background?.mutate()
                    if (drawable is android.graphics.drawable.GradientDrawable) {
                        drawable.setColor(parsedColor)
                    } else {
                        val circle = android.graphics.drawable.GradientDrawable()
                        circle.shape = android.graphics.drawable.GradientDrawable.OVAL
                        circle.setColor(parsedColor)
                        vColorDot.background = circle
                    }
                } else {
                    vColorDot.visibility = View.GONE
                }
            } else {
                ivAvatar.visibility = View.GONE
                vColorDot.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun loadAvatar(url: String, fallbackColor: String?, label: String) {
            val context = itemView.context
            val request = ImageRequest.Builder(context)
                .data(url)
                .target(ivAvatar)
                .transformations(CircleCropTransformation())
                .listener(
                    onError = { _, _ ->
                        // On load failure: show color-initial circle fallback
                        ivAvatar.visibility = View.GONE
                        if (fallbackColor != null) {
                            vColorDot.visibility = View.VISIBLE
                            val circle = android.graphics.drawable.GradientDrawable()
                            circle.shape = android.graphics.drawable.GradientDrawable.OVAL
                            circle.setColor(android.graphics.Color.parseColor(fallbackColor))
                            vColorDot.background = circle
                        }
                    }
                )
                .build()
            ImageLoader(context).enqueue(request)
        }
    }

    inner class ToggleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvSublabel: TextView = view.findViewById(R.id.tvSublabel)
        private val toggleSwitch: SwitchCompat = view.findViewById(R.id.toggleSwitch)

        fun bind(item: SettingsItem.Toggle, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label

            if (item.sublabel != null) {
                tvSublabel.text = item.sublabel
                tvSublabel.visibility = View.VISIBLE
            } else {
                tvSublabel.visibility = View.GONE
            }

            toggleSwitch.isChecked = item.isChecked
            itemView.isEnabled = item.isEnabled
            itemView.alpha = if (item.isEnabled) 1.0f else 0.5f

            itemView.setOnClickListener {
                if (item.isEnabled) {
                    val newState = !toggleSwitch.isChecked
                    toggleSwitch.isChecked = newState
                    onToggleChange(item, newState)
                }
            }
        }
    }

    inner class ActionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)

        fun bind(item: SettingsItem.Action, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label

            val density = itemView.resources.displayMetrics.density
            val marginLp = itemView.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            if (item.isPrimary) {
                // Filled orange block, centered white bold text, inset so it reads
                // as a stand-alone CTA rather than a full-bleed accent-text row.
                itemView.setBackgroundResource(R.drawable.button_primary)
                tvLabel.setTextColor(android.graphics.Color.WHITE)
                tvLabel.gravity = android.view.Gravity.CENTER
                tvLabel.setTypeface(tvLabel.typeface, android.graphics.Typeface.BOLD)
                marginLp?.let {
                    it.setMargins((12 * density).toInt(), (6 * density).toInt(),
                        (12 * density).toInt(), (6 * density).toInt())
                    itemView.layoutParams = it
                }
            } else {
                // Reset in case this recycled holder last rendered a primary block.
                val textColor = if (item.isDanger) {
                    ContextCompat.getColor(itemView.context, R.color.settings_text_danger)
                } else {
                    ContextCompat.getColor(itemView.context, R.color.settings_accent)
                }
                tvLabel.setTextColor(textColor)
                tvLabel.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                tvLabel.setTypeface(android.graphics.Typeface.DEFAULT)
                marginLp?.let { it.setMargins(0, 0, 0, 0); itemView.layoutParams = it }
            }

            itemView.isEnabled = item.isEnabled
            itemView.alpha = if (item.isEnabled) 1.0f else 0.5f

            itemView.setOnClickListener {
                if (item.isEnabled) {
                    onItemClick(item)
                }
            }
        }
    }

    inner class SectionHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvHeader: TextView = view.findViewById(R.id.tvHeader)

        fun bind(item: SettingsItem.SectionHeader) {
            tvHeader.text = item.title
        }
    }

    inner class InfoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvInfo: TextView = view.findViewById(R.id.tvInfo)

        fun bind(item: SettingsItem.Info) {
            tvInfo.text = item.text
            if (item.textColor != null) {
                tvInfo.setTextColor(item.textColor)
            }
            // Style as italic cell for "coming soon" items
            if (item.text.contains("coming soon", ignoreCase = true)) {
                tvInfo.setTypeface(tvInfo.typeface, android.graphics.Typeface.ITALIC)
                tvInfo.setTextColor(ContextCompat.getColor(itemView.context, R.color.settings_text_label))
                tvInfo.textSize = 16f
                tvInfo.setPadding(
                    itemView.context.resources.getDimensionPixelSize(R.dimen.settings_item_padding_horizontal),
                    itemView.context.resources.getDimensionPixelSize(R.dimen.settings_item_padding_vertical),
                    itemView.context.resources.getDimensionPixelSize(R.dimen.settings_item_padding_horizontal),
                    itemView.context.resources.getDimensionPixelSize(R.dimen.settings_item_padding_vertical)
                )
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.settings_cell_bg))
            } else {
                tvInfo.setTypeface(tvInfo.typeface, android.graphics.Typeface.NORMAL)
            }
        }
    }

    inner class ValueDisplayViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val tvSublabel: TextView = view.findViewById(R.id.tvSublabel)
        private val ivChevron: ImageView = view.findViewById(R.id.ivChevron)

        fun bind(item: SettingsItem.ValueDisplay, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label
            tvValue.text = item.value
            tvValue.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE

            if (item.sublabel != null) {
                tvSublabel.text = item.sublabel
                tvSublabel.visibility = View.VISIBLE
            } else {
                tvSublabel.visibility = View.GONE
            }

            // Apply custom value color (e.g. orange for warnings)
            if (item.valueColor != null) {
                tvValue.setTextColor(item.valueColor)
            } else {
                tvValue.setTextColor(ContextCompat.getColor(itemView.context, R.color.settings_text_value))
            }

            // Value display items are not clickable
            itemView.isClickable = false
            itemView.isFocusable = false
        }
    }

    /**
     * Render a circular color swatch into [vColorDot], or hide it when the
     * color is null/blank. Used by checkmark/picker rows (D.38).
     */
    private fun renderColorDot(vColorDot: View, colorHex: String?) {
        val colorStr = colorHex?.takeIf { it.isNotEmpty() }
        if (colorStr == null) {
            vColorDot.visibility = View.GONE
            return
        }
        vColorDot.visibility = View.VISIBLE
        val parsed = try {
            android.graphics.Color.parseColor(colorStr)
        } catch (e: Exception) {
            android.graphics.Color.parseColor("#607D8B") // fallback gray
        }
        val drawable = vColorDot.background?.mutate()
        if (drawable is android.graphics.drawable.GradientDrawable) {
            drawable.setColor(parsed)
        } else {
            val circle = android.graphics.drawable.GradientDrawable()
            circle.shape = android.graphics.drawable.GradientDrawable.OVAL
            circle.setColor(parsed)
            vColorDot.background = circle
        }
    }

    inner class CheckmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvSublabel: TextView = view.findViewById(R.id.tvSublabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val ivChevron: ImageView = view.findViewById(R.id.ivChevron)
        private val vColorDot: View = view.findViewById(R.id.vColorDot)
        private var previewButton: View? = null
        private var actionButton: android.widget.Button? = null

        fun bind(item: SettingsItem.Checkmark, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label
            if (item.sublabel != null) {
                tvSublabel.text = item.sublabel
                tvSublabel.visibility = View.VISIBLE
            } else {
                tvSublabel.visibility = View.GONE
            }

            // Color swatch left of the label (e.g. the family color picker — D.38)
            renderColorDot(vColorDot, item.colorDot)

            // Apply extra left padding for indented items
            if (item.isIndented) {
                val extraPadding = itemView.context.resources
                    .getDimensionPixelSize(R.dimen.settings_item_padding_horizontal)
                itemView.setPaddingRelative(
                    itemView.paddingStart + extraPadding,
                    itemView.paddingTop,
                    itemView.paddingEnd,
                    itemView.paddingBottom
                )
            }

            // Gray out disabled items — but a row carrying onDisabledTap stays
            // fully lit and tappable (tapping it explains why it's unavailable
            // and offers a path to enable it, rather than being an inert no-op).
            // With an actionLabel too, the row content grays like any disabled
            // row and the lit part is the BUTTON — "this isn't available yet,
            // and here is the verb that makes it available" (model downloads).
            val tappable = item.enabled || item.onDisabledTap != null
            val hasActionButton = !item.enabled && item.actionLabel != null
            val contentAlpha = when {
                item.enabled -> 1.0f
                hasActionButton -> 0.45f
                tappable -> 1.0f
                else -> 0.4f
            }
            tvLabel.alpha = contentAlpha
            tvSublabel.alpha = contentAlpha
            itemView.isClickable = tappable

            // Show checkmark if checked, otherwise hide
            if (item.isChecked) {
                tvValue.text = "✓"
                tvValue.visibility = View.VISIBLE
                // item_settings_menu gives tvValue weight=1 so a long DISPLAY VALUE
                // (e.g. an AI-model id on nav rows) ellipsizes in its half. A
                // checkmark needs only its glyph — with weight=1 the ✓ steals half
                // the row and wraps the sublabel on the SELECTED option (HA Voice
                // Assist wrapped while longer, unselected rows didn't). This
                // ViewHolder's tvValue is only ever a ✓, so drop the weight here.
                (tvValue.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                    it.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    it.weight = 0f
                    tvValue.layoutParams = it
                }
            } else {
                tvValue.visibility = View.GONE
            }

            ivChevron.visibility = View.GONE

            // Disabled state (grayed out, not clickable) — reuses `tappable`
            // above so an onDisabledTap explainer row keeps focus + clicks.
            tvLabel.alpha = contentAlpha
            tvSublabel.alpha = contentAlpha
            itemView.isClickable = tappable
            itemView.isFocusable = tappable

            // Preview play button for sound items
            val container = itemView as? ViewGroup
            if (item.hasPreview && container != null) {
                if (previewButton == null) {
                    val density = itemView.resources.displayMetrics.density
                    val size = (36 * density).toInt()
                    val btn = View(itemView.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = (12 * density).toInt()
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(0xFFEE9828.toInt())
                        }
                        // Draw play triangle via foreground
                        foreground = PlayTriangleDrawable()
                        isClickable = true
                        isFocusable = true
                    }
                    container.addView(btn, 0)
                    previewButton = btn
                }
                previewButton?.visibility = View.VISIBLE
                previewButton?.setOnClickListener { onPreviewClick(item) }
            } else {
                previewButton?.visibility = View.GONE
            }

            // Right-aligned action button (e.g. "Download" on a not-installed
            // on-device model). Lazily created like previewButton; hidden on
            // recycle when the bound item carries no actionLabel. Routes through
            // the same onItemClick as the row — one tap path, two affordances.
            if (hasActionButton && container != null) {
                if (actionButton == null) {
                    val density = itemView.resources.displayMetrics.density
                    val btn = android.widget.Button(itemView.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = (8 * density).toInt() }
                        minHeight = 0
                        minWidth = 0
                        setPadding((14 * density).toInt(), (5 * density).toInt(),
                            (14 * density).toInt(), (5 * density).toInt())
                        textSize = 13f
                        isAllCaps = false
                        setTextColor(0xFFFFFFFF.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 14 * density
                            setColor(0xFFEE9828.toInt())  // dashie orange, matches previewButton
                        }
                    }
                    container.addView(btn)
                    actionButton = btn
                }
                actionButton?.text = item.actionLabel
                actionButton?.visibility = View.VISIBLE
                actionButton?.alpha = 1.0f
                actionButton?.setOnClickListener { onItemClick(item) }
            } else {
                actionButton?.visibility = View.GONE
            }

            itemView.setOnClickListener { if (tappable) onItemClick(item) }
        }
    }

    inner class SliderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val seekBar: SeekBar = view.findViewById(R.id.seekBar)

        fun bind(item: SettingsItem.Slider, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label
            updateValueText(item.value, item.unit)

            // SeekBar uses 0-based range, so offset by min
            val range = item.max - item.min
            seekBar.max = range / item.step
            seekBar.progress = (item.value - item.min) / item.step

            itemView.isEnabled = item.isEnabled
            itemView.alpha = if (item.isEnabled) 1.0f else 0.5f
            seekBar.isEnabled = item.isEnabled

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val actualValue = item.min + (progress * item.step)
                        updateValueText(actualValue, item.unit)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val actualValue = item.min + ((sb?.progress ?: 0) * item.step)
                    onSliderChange(item, actualValue)
                }
            })
        }

        private fun updateValueText(value: Int, unit: String?) {
            tvValue.text = if (unit != null) "$value$unit" else "$value"
        }
    }

    inner class TextInputViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val editText: EditText = view.findViewById(R.id.editText)
        private val ivTogglePassword: android.widget.ImageView = view.findViewById(R.id.ivTogglePassword)
        private val tvSublabel: TextView? = view.findViewById(R.id.tvSublabel)
        private val textInputRow: android.widget.LinearLayout? = view.findViewById(R.id.textInputRow)

        fun bind(item: SettingsItem.TextInput, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label
            if (item.labelColor != null) {
                tvLabel.setTextColor(item.labelColor)
            } else {
                tvLabel.setTextColor(ContextCompat.getColor(itemView.context, R.color.settings_text_label))
            }
            editText.setText(item.value)
            editText.hint = item.placeholder
            editText.inputType = item.inputType
            editText.isEnabled = item.isEnabled
            itemView.alpha = if (item.isEnabled) 1.0f else 0.5f

            // Optional hint text beneath the input row.
            if (item.sublabel.isNullOrBlank()) {
                tvSublabel?.visibility = View.GONE
            } else {
                tvSublabel?.text = item.sublabel
                tvSublabel?.visibility = View.VISIBLE
            }

            // Multiline handling: stack label above text area, increase size.
            // Target the inner textInputRow so the outer vertical container
            // can still host the sublabel underneath.
            val isMultiline = (item.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            val container = textInputRow
            if (isMultiline) {
                container?.orientation = android.widget.LinearLayout.VERTICAL
                container?.gravity = android.view.Gravity.START
                tvLabel.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * itemView.resources.displayMetrics.density).toInt() }
                editText.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                editText.minLines = 3
                editText.maxLines = 6
                editText.gravity = android.view.Gravity.START or android.view.Gravity.TOP
                editText.textSize = 13f
                editText.isSingleLine = false
            } else {
                container?.orientation = android.widget.LinearLayout.HORIZONTAL
                container?.gravity = android.view.Gravity.CENTER_VERTICAL
                tvLabel.layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = (12 * itemView.resources.displayMetrics.density).toInt() }
                editText.layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                editText.minLines = 1
                editText.maxLines = 1
                editText.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                editText.isSingleLine = true
            }

            // Force IME action to DONE so the keyboard shows a dismiss button
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.setOnEditorActionListener { v, actionId, event ->
                // Accept DONE, NEXT, GO, UNSPECIFIED, or Enter key press
                val isActionKey = actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_UNSPECIFIED ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                if (isActionKey) {
                    onTextChange(item, editText.text.toString())
                    editText.clearFocus()
                    val imm = v.context.getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else false
            }

            // SHOW_FORCED (not SHOW_IMPLICIT) so the on-screen keyboard appears
            // even when Android has detected a "hardware keyboard" — e.g. the
            // Fire TV phone-remote app or a paired BT keyboard, which register
            // as KeyboardType: 2 (full alphabetic) and otherwise suppress IME.
            // Deprecated in API 33 but still the only reliable bypass for the
            // hardware-keyboard suppression.
            fun forceShowIme() {
                val imm = itemView.context.getSystemService(
                    android.content.Context.INPUT_METHOD_SERVICE
                ) as android.view.inputmethod.InputMethodManager
                @Suppress("DEPRECATION")
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
            }

            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // On Fire TV / D-pad devices, D-pad navigation focuses the
                    // EditText directly (skipping the row's click handler), so
                    // we must show the IME on focus-gain too.
                    editText.setSelection(editText.text.length)
                    forceShowIme()
                } else {
                    onTextChange(item, editText.text.toString())
                }
            }

            // Touch / row-click path (phones, tablets): tapping anywhere on
            // the row transfers focus to the EditText, which then triggers
            // the focus listener above.
            itemView.setOnClickListener {
                editText.requestFocus()
                editText.setSelection(editText.text.length)
                forceShowIme()
            }

            // D-pad CENTER (OK / Enter) pressed while the EditText itself
            // has focus — on Fire TV this is the normal "start editing"
            // gesture, but the EditText doesn't auto-show IME under
            // hardware-keyboard suppression. Force it here.
            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    forceShowIme()
                }
                false
            }

            // Password show/hide toggle. Applied last so later inputType/isSingleLine
            // mutations above don't reset the transformation method. Icon reflects
            // current state: ic_eye_off = masked, ic_eye = visible. Mask state
            // tracked on the EditText tag (R.id.ivTogglePassword) to survive view
            // recycling without querying transformationMethod (unreliable on some
            // OEMs after setInputType).
            val isPassword = (item.inputType and android.text.InputType.TYPE_MASK_VARIATION) ==
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (isPassword) {
                fun applyMasked(masked: Boolean) {
                    val cursor = editText.selectionEnd.coerceAtLeast(0)
                    val text = editText.text?.toString() ?: ""
                    editText.transformationMethod = if (masked)
                        android.text.method.PasswordTransformationMethod.getInstance()
                    else
                        null
                    // Force re-render: re-setting text flushes the transformation
                    // to the display on OEM ROMs that otherwise render stale text.
                    editText.setText(text)
                    editText.setSelection(cursor.coerceAtMost(editText.text.length))
                    editText.setTag(R.id.ivTogglePassword, masked)
                    ivTogglePassword.setImageResource(
                        if (masked) R.drawable.ic_eye_off else R.drawable.ic_eye
                    )
                    ivTogglePassword.contentDescription =
                        if (masked) "Show password" else "Hide password"
                }

                ivTogglePassword.visibility = View.VISIBLE
                // Always start masked when the row is bound
                applyMasked(true)
                ivTogglePassword.setOnClickListener {
                    val wasMasked = (editText.getTag(R.id.ivTogglePassword) as? Boolean) ?: true
                    applyMasked(!wasMasked)
                }
            } else {
                ivTogglePassword.visibility = View.GONE
                ivTogglePassword.setOnClickListener(null)
                editText.setTag(R.id.ivTogglePassword, null)
                // Clear lingering password transformation from view recycling
                if (editText.transformationMethod is android.text.method.PasswordTransformationMethod) {
                    editText.transformationMethod = null
                }
            }
        }
    }

    inner class PickerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvSublabel: TextView = view.findViewById(R.id.tvSublabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val ivChevron: ImageView = view.findViewById(R.id.ivChevron)

        fun bind(item: SettingsItem.Picker, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            tvLabel.text = item.label
            if (item.sublabel != null) {
                tvSublabel.text = item.sublabel
                tvSublabel.visibility = View.VISIBLE
            } else {
                tvSublabel.visibility = View.GONE
            }
            tvValue.text = item.displayValue
            tvValue.visibility = View.VISIBLE
            ivChevron.visibility = View.VISIBLE

            itemView.isEnabled = item.isEnabled
            itemView.alpha = if (item.isEnabled) 1.0f else 0.5f

            itemView.setOnClickListener {
                if (item.isEnabled) onItemClick(item)
            }
        }
    }

    /**
     * Confidence bar: horizontal bar with gradient fill and threshold marker.
     * Built programmatically — no XML layout needed.
     */
    inner class ConfidenceBarViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        android.widget.FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
    ) {
        private val ctx = parent.context
        private val dp = ctx.resources.displayMetrics.density

        // Colors matching the JS CSS: orange → green gradient
        private val colorOrange = 0xFFFF6B1A.toInt()
        private val colorGreen = 0xFF34C759.toInt()
        private val colorBarBg = 0xFFCCCCCC.toInt().let { lightGray ->
            // Use a gray that works on both themes
            val nightMode = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                0xFF2A2A2E.toInt() else lightGray
        }
        private val colorThreshold = 0xFFFFFFFF.toInt()
        private val colorText = 0xAAFFFFFF.toInt()

        private val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        private val labelView = TextView(ctx).apply {
            setTextColor(colorText)
            textSize = 12f
        }

        private val barContainer = android.widget.FrameLayout(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (12 * dp).toInt()
            ).apply { topMargin = (4 * dp).toInt() }
        }

        private val barBg = View(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(colorBarBg)
        }

        private val barFill = View(ctx)

        private val thresholdLine = View(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (2 * dp).toInt(),
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(colorThreshold)
        }

        init {
            barContainer.addView(barBg)
            barContainer.addView(barFill)
            barContainer.addView(thresholdLine)
            container.addView(labelView)
            container.addView(barContainer)
            (itemView as android.widget.FrameLayout).addView(container)
        }

        fun bind(item: SettingsItem.ConfidenceBar, sectionPosition: SectionPosition) {
            applySectionStyling(itemView, sectionPosition)

            val pct = item.value.coerceIn(0, 100)

            // Orange below threshold, green when threshold is reached
            val aboveThreshold = pct >= item.threshold
            val fillColor = if (aboveThreshold) colorGreen else colorOrange

            barFill.setBackgroundColor(fillColor)
            barFill.layoutParams = android.widget.FrameLayout.LayoutParams(
                0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )

            // Set fill width after layout
            barContainer.post {
                val totalWidth = barContainer.width
                if (totalWidth > 0) {
                    barFill.layoutParams = android.widget.FrameLayout.LayoutParams(
                        (totalWidth * pct / 100f).toInt(),
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )

                    // Position threshold marker
                    val thresholdX = (totalWidth * item.threshold / 100f).toInt()
                    (thresholdLine.layoutParams as android.widget.FrameLayout.LayoutParams).leftMargin = thresholdX
                    thresholdLine.requestLayout()
                }
            }

            labelView.text = item.label ?: "${pct}%"
        }
    }
}

/**
 * Simple drawable that draws a white play triangle (▶) centered in the view.
 */
private class PlayTriangleDrawable : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = android.graphics.Paint.Style.FILL
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val cx = b.centerX().toFloat()
        val cy = b.centerY().toFloat()
        val r = b.width() * 0.28f
        val path = android.graphics.Path().apply {
            // Slight right offset so triangle looks centered
            moveTo(cx + r, cy)
            lineTo(cx - r * 0.6f, cy - r * 0.9f)
            lineTo(cx - r * 0.6f, cy + r * 0.9f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * DiffUtil callback for efficient list updates.
 */
class SettingsDiffCallback : DiffUtil.ItemCallback<SettingsItem>() {
    override fun areItemsTheSame(oldItem: SettingsItem, newItem: SettingsItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: SettingsItem, newItem: SettingsItem): Boolean {
        return oldItem == newItem
    }
}
