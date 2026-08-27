package com.dashieapp.Dashie.halite.settings.schema

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R

/**
 * Build a label like "Custom (120pt) ✎" where the trailing character is replaced
 * with an inline edit-pencil drawable, signaling that the value is editable.
 * Falls back to plain text if the drawable can't be loaded.
 */
fun buildCustomValueLabel(
    ctx: Context,
    base: String,
    value: String,
    unit: String?
): CharSequence {
    val text = "$base ($value${unit ?: ""})  ​"
    val builder = SpannableStringBuilder(text)
    val drawable = ContextCompat.getDrawable(ctx, R.drawable.ic_edit) ?: return text
    val labelPx = ctx.resources.getDimensionPixelSize(R.dimen.settings_label_text_size)
    val iconSize = (labelPx * 0.9f).toInt()
    drawable.setBounds(0, 0, iconSize, iconSize)
    drawable.setTint(ContextCompat.getColor(ctx, R.color.settings_text_value))
    val iconIndex = text.length - 1
    builder.setSpan(
        ImageSpan(drawable, ImageSpan.ALIGN_CENTER),
        iconIndex,
        iconIndex + 1,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    return builder
}
