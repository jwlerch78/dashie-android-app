package com.dashieapp.Dashie.controlcenter

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dashieapp.Dashie.R

/**
 * RecyclerView adapter for Control Center items.
 *
 * Displays a 2-column grid of feature cards, section headers, nav cards,
 * and bottom buttons — matching the JS control center layout.
 *
 * Item types:
 * - SECTION_HEADER: "Features" header (full width)
 * - COLUMN_HEADER: "Display", "General" headers (single column each)
 * - FEATURE_CARD: Status dot + label + summary + chevron
 * - NAV_CARD: Label + summary + chevron (no dot)
 * - SPACER: Empty cell to keep grid columns aligned
 */
class ControlCenterAdapter(
    private val onCardClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SECTION_HEADER = 0
        private const val TYPE_FEATURE_CARD = 1
        private const val TYPE_NAV_CARD = 2
        private const val TYPE_SPACER = 3
        private const val TYPE_COLUMN_HEADER = 4
        private const val TYPE_PROMO_TEXT = 5
        private const val TYPE_PROMO_BUTTON = 6
        private const val CARD_CORNER_RADIUS_DP = 8f
    }

    private val items = mutableListOf<CcListItem>()

    /** Track which flat index is focused for D-pad */
    var focusedIndex: Int = -1
        private set

    /** Whether D-pad focus highlighting is enabled (TV/remote devices only) */
    var dpadEnabled: Boolean = false

    /**
     * Pre-resolved theme colors from the activity context.
     * Set by ControlCenterOverlay.refreshTheme() BEFORE notifyDataSetChanged().
     * This avoids relying on itemView.context resource resolution which may
     * use a stale/different resources configuration than the activity.
     */
    var colorLabel: Int = 0xFF000000.toInt()
    var colorValue: Int = 0xFF8E8E93.toInt()
    var colorHeader: Int = 0xFF8E8E93.toInt()
    var colorCellBg: Int = 0xFFFFFFFF.toInt()
    var colorFocusBg: Int = 0x33FFFFFF.toInt()
    var colorHighlight: Int = 0x1FFFFFFF.toInt()

    // ── Data model ──────────────────────────────────────────────────

    sealed class CcListItem {
        data class SectionHeader(val title: String, val spaced: Boolean = false, val subtitle: String? = null, val subtitleAction: String? = null) : CcListItem()
        data class ColumnHeader(val title: String, val spaced: Boolean = false) : CcListItem()
        data class FeatureCard(val card: ControlCenterCard) : CcListItem()
        data class NavCard(val card: ControlCenterCard) : CcListItem()
        /** Full-width promo text with a clickable link portion.
         *  `highlighted=true` renders the leading text in orange (D.59 — trial
         *  countdown / trial-expired callouts). The inline link span keeps its
         *  own orange color regardless. */
        data class PromoText(
            val text: String,
            val linkText: String,
            val action: String,
            val highlighted: Boolean = false,
            /** When non-null, a dismiss "✕" is shown at the end of the row and
             *  fires this action on tap (used to dismiss the free-trial promo). */
            val dismissAction: String? = null
        ) : CcListItem()
        /** Full-width centered CTA button — d-pad-focusable, used on TV
         *  where inline ClickableSpan links can't be reached. Pair with a
         *  PromoText (or non-clickable text row) above for context. */
        data class PromoButton(val text: String, val action: String) : CcListItem()
        /** Empty spacer to keep grid columns aligned */
        data object Spacer : CcListItem()
    }

    // ── Public API ──────────────────────────────────────────────────

    fun getItems(): List<CcListItem> = items

    fun submitList(newItems: List<CcListItem>) {
        val diffResult = DiffUtil.calculateDiff(CcDiffCallback(items, newItems))
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    fun getClickableItems(): List<Int> {
        return items.indices.filter {
            items[it] !is CcListItem.SectionHeader &&
            items[it] !is CcListItem.ColumnHeader &&
            items[it] !is CcListItem.Spacer &&
            // PromoText carries an inline ClickableSpan that touch users tap
            // directly via LinkMovementMethod — it has no d-pad focus state
            // and was never a real d-pad target. Excluding from the clickable
            // list so d-pad cleanly skips past it to the PromoButton (TV) or
            // to nothing (touch is fine — touch ignores this list).
            items[it] !is CcListItem.PromoText
        }.toList()
    }

    fun getItemAction(position: Int): String? {
        return when (val item = items.getOrNull(position)) {
            is CcListItem.FeatureCard -> item.card.action
            is CcListItem.NavCard -> item.card.action
            is CcListItem.PromoButton -> item.action
            else -> null
        }
    }

    /**
     * Update D-pad focus to the given flat clickable index.
     */
    fun setFocus(clickableIndex: Int) {
        val clickablePositions = getClickableItems()
        // Clear old focus
        if (focusedIndex in clickablePositions.indices) {
            notifyItemChanged(clickablePositions[focusedIndex])
        }
        focusedIndex = clickableIndex
        // Set new focus
        if (focusedIndex in clickablePositions.indices) {
            notifyItemChanged(clickablePositions[focusedIndex])
        }
    }

    // ── Helper: create card background programmatically ──────────

    private fun makeCardBackground(focused: Boolean): RippleDrawable {
        val bg = GradientDrawable()
        bg.setColor(if (focused) colorFocusBg else colorCellBg)
        bg.cornerRadius = CARD_CORNER_RADIUS_DP *
            android.content.res.Resources.getSystem().displayMetrics.density
        // Use a subtle white/gray ripple instead of the slow orange one
        val rippleColor = ColorStateList.valueOf(0x20FFFFFF.toInt())
        return RippleDrawable(rippleColor, bg, null)
    }

    /** Quick iOS-style press: dim briefly then fire the action. */
    private fun animatePress(view: View, action: () -> Unit) {
        view.animate()
            .alpha(0.5f)
            .setDuration(60)
            .withEndAction {
                view.animate()
                    .alpha(1f)
                    .setDuration(100)
                    .start()
                action()
            }
            .start()
    }

    // ── ViewHolder creation ─────────────────────────────────────────

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is CcListItem.SectionHeader -> TYPE_SECTION_HEADER
            is CcListItem.ColumnHeader -> TYPE_COLUMN_HEADER
            is CcListItem.FeatureCard -> TYPE_FEATURE_CARD
            is CcListItem.NavCard -> TYPE_NAV_CARD
            is CcListItem.PromoText -> TYPE_PROMO_TEXT
            is CcListItem.PromoButton -> TYPE_PROMO_BUTTON
            is CcListItem.Spacer -> TYPE_SPACER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION_HEADER -> SectionHeaderVH(inflater.inflate(R.layout.item_cc_section_header, parent, false))
            TYPE_COLUMN_HEADER -> ColumnHeaderVH(inflater.inflate(R.layout.item_cc_section_header, parent, false))
            TYPE_FEATURE_CARD -> FeatureCardVH(inflater.inflate(R.layout.item_cc_feature_card, parent, false))
            TYPE_NAV_CARD -> NavCardVH(inflater.inflate(R.layout.item_cc_feature_card, parent, false))
            TYPE_PROMO_TEXT -> PromoTextVH(inflater.inflate(R.layout.item_cc_promo_text, parent, false))
            TYPE_PROMO_BUTTON -> PromoButtonVH(inflater.inflate(R.layout.item_cc_cta_button, parent, false))
            TYPE_SPACER -> SpacerVH(View(parent.context))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SectionHeaderVH -> holder.bind(items[position] as CcListItem.SectionHeader)
            is ColumnHeaderVH -> holder.bind(items[position] as CcListItem.ColumnHeader)
            is FeatureCardVH -> holder.bind(items[position] as CcListItem.FeatureCard, position)
            is NavCardVH -> holder.bind(items[position] as CcListItem.NavCard, position)
            is PromoTextVH -> holder.bind(items[position] as CcListItem.PromoText)
            is PromoButtonVH -> holder.bind(items[position] as CcListItem.PromoButton, position)
            is SpacerVH -> { /* nothing to bind */ }
        }
    }

    override fun getItemCount(): Int = items.size

    // ── ViewHolders ─────────────────────────────────────────────────

    inner class SectionHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.ccSectionHeader)
        fun bind(item: CcListItem.SectionHeader) {
            val topMargin = if (item.spaced) 18 else 0
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
                (topMargin * itemView.resources.displayMetrics.density).toInt()

            if (item.subtitle != null) {
                // Parse subtitle to find the clickable portion after " — "
                val dashIdx = item.subtitle.indexOf(" — ")
                val statusPart: String
                val linkPart: String?
                if (dashIdx >= 0) {
                    statusPart = item.subtitle.substring(0, dashIdx)
                    linkPart = item.subtitle.substring(dashIdx + 3)
                } else {
                    statusPart = item.subtitle
                    linkPart = null
                }

                // Build: "TITLE   (status — link)"
                val prefix = "${item.title}   ("
                val full = if (linkPart != null) {
                    "$prefix$statusPart — $linkPart)"
                } else {
                    "$prefix$statusPart)"
                }
                val spannable = android.text.SpannableString(full)

                // Title portion stays as-is (uppercase bold from XML)
                // Subtitle portion: normal weight, smaller, not uppercase
                val subStart = item.title.length + 3 // after "   "
                spannable.setSpan(
                    android.text.style.RelativeSizeSpan(0.75f),
                    subStart, full.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.NORMAL),
                    subStart, full.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // Status part in gray (same as header color)
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(colorHeader),
                    subStart, subStart + statusPart.length + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Link part in orange, underlined, clickable
                if (linkPart != null && item.subtitleAction != null) {
                    val linkStart = full.length - linkPart.length - 1 // before closing ")"
                    spannable.setSpan(
                        object : android.text.style.ClickableSpan() {
                            override fun onClick(widget: View) { onCardClick(item.subtitleAction) }
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                // CC stays default-themed (orange) regardless of webapp palette.
                                ds.color = 0xFFFF8C00.toInt()
                                ds.isUnderlineText = true
                            }
                        },
                        linkStart, linkStart + linkPart.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    text.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    text.highlightColor = android.graphics.Color.TRANSPARENT
                }

                // Disable allCaps for the spannable (title stays uppercase via content)
                text.isAllCaps = false
                text.text = spannable
                text.setTextColor(colorHeader)
            } else {
                text.isAllCaps = true
                text.text = item.title
                text.setTextColor(colorHeader)
                text.movementMethod = null
            }
        }
    }

    inner class ColumnHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.ccSectionHeader)
        fun bind(item: CcListItem.ColumnHeader) {
            text.text = item.title
            text.setTextColor(colorHeader)
            val topMargin = if (item.spaced) 18 else 0
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
                (topMargin * itemView.resources.displayMetrics.density).toInt()
        }
    }

    inner class FeatureCardVH(view: View) : RecyclerView.ViewHolder(view) {
        private val dot: View = view.findViewById(R.id.ccDot)
        private val label: TextView = view.findViewById(R.id.ccCardLabel)
        private val summary: TextView = view.findViewById(R.id.ccCardSummary)
        private val chevron: TextView = view.findViewById(R.id.ccChevron)

        fun bind(item: CcListItem.FeatureCard, position: Int) {
            val card = item.card
            label.text = card.label
            summary.text = card.summary
            summary.visibility = if (card.summary.isEmpty()) View.GONE else View.VISIBLE

            // Use pre-resolved colors (from activity context, not itemView context)
            label.setTextColor(colorLabel)
            chevron.setTextColor(colorValue)

            // Status dot
            dot.visibility = View.VISIBLE
            dot.setBackgroundResource(when (card.status) {
                CardStatus.ON -> R.drawable.bg_cc_dot_on
                CardStatus.OFF -> R.drawable.bg_cc_dot_off
                CardStatus.WARN -> R.drawable.bg_cc_dot_warn
                CardStatus.ERROR -> R.drawable.bg_cc_dot_error
            })

            // Special summary text coloring.
            //
            // The `voice` branch that used to live here tinted the summary amber (trial active) or
            // grey (trial expired). It went with the voice licence on 2026-08-02 — voice is free,
            // so there is no trial note to tint and the card takes the ordinary status colour.
            // NOTE the subscription's own trial UI is unaffected: that is the bottom-left chip
            // (TrialCountdownChipManager), a different surface fed by subscriptionStatus.
            when {
                card.id == "power" && card.status == CardStatus.WARN &&
                    (card.summary == "No switch configured" || card.summary == "Switch unreachable") -> {
                    summary.setTextColor(0xFFFF9500.toInt()) // dashie_orange
                }
                card.status == CardStatus.ERROR -> {
                    summary.setTextColor(0xFFFF3B30.toInt()) // red
                }
                else -> summary.setTextColor(colorValue)
            }

            // Card background — built programmatically to use pre-resolved colors
            if (dpadEnabled) {
                val clickableItems = getClickableItems()
                val clickableIdx = clickableItems.indexOf(position)
                val isFocused = clickableIdx == focusedIndex
                itemView.background = makeCardBackground(isFocused)
            } else {
                itemView.background = makeCardBackground(false)
            }

            itemView.setOnClickListener { animatePress(itemView) { onCardClick(card.action) } }
        }
    }

    inner class NavCardVH(view: View) : RecyclerView.ViewHolder(view) {
        private val dot: View = view.findViewById(R.id.ccDot)
        private val label: TextView = view.findViewById(R.id.ccCardLabel)
        private val summary: TextView = view.findViewById(R.id.ccCardSummary)
        private val chevron: TextView = view.findViewById(R.id.ccChevron)

        fun bind(item: CcListItem.NavCard, position: Int) {
            val card = item.card
            label.text = card.label
            summary.text = card.summary
            summary.visibility = if (card.summary.isEmpty()) View.GONE else View.VISIBLE
            dot.visibility = View.GONE

            // Use pre-resolved colors (from activity context, not itemView context)
            label.setTextColor(colorLabel)
            summary.setTextColor(colorValue)
            chevron.setTextColor(colorValue)

            // Card background — built programmatically to use pre-resolved colors
            if (dpadEnabled) {
                val clickableItems = getClickableItems()
                val clickableIdx = clickableItems.indexOf(position)
                val isFocused = clickableIdx == focusedIndex
                itemView.background = makeCardBackground(isFocused)
            } else {
                itemView.background = makeCardBackground(false)
            }

            itemView.setOnClickListener { animatePress(itemView) { onCardClick(card.action) } }
        }
    }

    inner class PromoTextVH(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.ccPromoText)
        private val dismissBtn: TextView = view.findViewById(R.id.ccPromoDismiss)
        private val promoBox: android.widget.LinearLayout = view.findViewById(R.id.ccPromoBox)

        fun bind(item: CcListItem.PromoText) {
            val fullText = "${item.text}${item.linkText}"
            val spannable = android.text.SpannableString(fullText)
            val linkStart = item.text.length
            val linkEnd = fullText.length

            spannable.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        onCardClick(item.action)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = 0xFFFF8C00.toInt()
                        ds.isUnderlineText = true
                    }
                },
                linkStart, linkEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            textView.text = spannable
            textView.setTextColor(
                if (item.highlighted) 0xFFFFA800.toInt()
                else textView.context.getColor(R.color.text_primary)
            )
            textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            textView.highlightColor = android.graphics.Color.TRANSPARENT

            // Dismiss "✕" + boxed message — only for promos that opt in with a
            // dismissAction (the free-trial notice). The box background is drawn
            // programmatically so it tracks the resolved (light/dark) theme.
            if (item.dismissAction != null) {
                dismissBtn.visibility = View.VISIBLE
                dismissBtn.setTextColor(colorValue)
                dismissBtn.setOnClickListener { onCardClick(item.dismissAction) }

                val density = itemView.resources.displayMetrics.density
                val box = GradientDrawable()
                box.setColor(colorCellBg)
                box.cornerRadius = 10f * density
                box.setStroke((1 * density).toInt(), (colorValue and 0x00FFFFFF) or (0x40 shl 24))
                promoBox.background = box
                val padH = (12 * density).toInt()
                val padV = (6 * density).toInt()
                promoBox.setPadding(padH, padV, padH, padV)
            } else {
                dismissBtn.visibility = View.GONE
                dismissBtn.setOnClickListener(null)
                promoBox.background = null
                promoBox.setPadding(0, 0, 0, 0)
            }
        }
    }

    inner class PromoButtonVH(view: View) : RecyclerView.ViewHolder(view) {
        private val button: TextView = view.findViewById(R.id.ccCtaButton)

        fun bind(item: CcListItem.PromoButton, position: Int) {
            button.text = item.text
            // Use the resolved theme label color rather than the XML's hardcoded
            // #000000 (which is invisible against themed dark cell backgrounds).
            button.setTextColor(colorLabel)
            // D-pad focus indication: route through makeCardBackground (themed
            // via colorFocusBg) instead of bg_cc_card_focused.xml whose
            // settings_focus_highlight color is static orange. Matches how
            // FeatureCard/NavCard highlight under d-pad focus.
            if (dpadEnabled) {
                val clickableItems = getClickableItems()
                val clickableIdx = clickableItems.indexOf(position)
                val isFocused = clickableIdx == focusedIndex
                button.background = makeCardBackground(isFocused)
            }
            button.setOnClickListener { animatePress(button) { onCardClick(item.action) } }
        }
    }

    inner class SpacerVH(view: View) : RecyclerView.ViewHolder(view)

    // ── DiffUtil ────────────────────────────────────────────────────

    private class CcDiffCallback(
        private val old: List<CcListItem>,
        private val new: List<CcListItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            return when {
                o is CcListItem.SectionHeader && n is CcListItem.SectionHeader -> o.title == n.title
                o is CcListItem.ColumnHeader && n is CcListItem.ColumnHeader -> o.title == n.title
                o is CcListItem.FeatureCard && n is CcListItem.FeatureCard -> o.card.id == n.card.id
                o is CcListItem.NavCard && n is CcListItem.NavCard -> o.card.id == n.card.id
                o is CcListItem.PromoText && n is CcListItem.PromoText -> o.action == n.action
                o is CcListItem.PromoButton && n is CcListItem.PromoButton -> o.action == n.action
                o is CcListItem.Spacer && n is CcListItem.Spacer -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            return old[oldPos] == new[newPos]
        }
    }
}

/**
 * Interface for the hosting Activity to provide state provider access.
 */
interface ControlCenterHost {
    fun getStateProvider(): ControlCenterStateProvider?
}
