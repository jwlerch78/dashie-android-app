package com.dashieapp.Dashie.halite.settings.schema

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R

/**
 * Renders the Dashie Console's LOCAL / CLOUD tag next to a settings row's value, so a glance at
 * the tablet answers the same question the console's colored legend does: is this stage running
 * on my own hardware, or is it billing credits?
 *
 * The badge is a span rather than a view because a picker's value is already a CharSequence
 * (see [buildCustomValueLabel]) — no row-layout or renderer plumbing is needed to carry it.
 *
 * Values mirror the console's vocabulary; anything unrecognized renders no badge rather than a
 * wrong one, since a mislabeled cost signal is worse than none.
 */
enum class ValueBadge(val text: String, val colorRes: Int) {
    LOCAL("LOCAL", R.color.success_green),
    CLOUD("CLOUD", R.color.dashie_orange);

    companion object {
        fun from(raw: String?): ValueBadge? = when (raw?.trim()?.lowercase()) {
            "local" -> LOCAL
            "cloud" -> CLOUD
            else -> null
        }
    }
}

/**
 * Appends [badge] to [base] as a filled pill (white on the badge's color), matching the console.
 */
fun buildBadgedValueLabel(ctx: Context, base: CharSequence, badge: ValueBadge): CharSequence {
    val builder = SpannableStringBuilder(base).append("  ").append(badge.text)
    val start = builder.length - badge.text.length
    val end = builder.length
    val density = ctx.resources.displayMetrics.density
    val valuePx = ctx.resources.getDimensionPixelSize(R.dimen.settings_label_text_size).toFloat()
    builder.setSpan(
        BadgeChipSpan(
            bgColor = ContextCompat.getColor(ctx, badge.colorRes),
            textSizePx = valuePx * 0.72f,
            horizontalPadPx = 6f * density,
            verticalPadPx = 2.5f * density,
            cornerRadiusPx = 4f * density
        ),
        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    return builder
}
