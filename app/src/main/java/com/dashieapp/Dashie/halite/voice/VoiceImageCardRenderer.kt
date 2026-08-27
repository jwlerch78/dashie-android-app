package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.dashieapp.Dashie.R

/**
 * Renders an image result card (VoiceOverlayBridge.ImageCard) for the voice sidebar
 * and the conversation transcript. Inflates view_voice_image_card.xml and loads the
 * photo via Coil from the URL the {type:'image'} card carries — falling back to the
 * (more reliable) thumbnail if the hotlinked full image fails. Mirrors
 * VoiceSportsCardRenderer so both card types share the same shape.
 */
object VoiceImageCardRenderer {

    fun addCardToContainer(
        context: Context,
        container: LinearLayout,
        card: VoiceOverlayBridge.ImageCard,
        scale: Float = 1f,
        widthFraction: Float = 1f,
    ) {
        container.removeAllViews()
        val view = createCard(context, card, scale)
        val widthPx = if (widthFraction < 1f) {
            (context.resources.displayMetrics.widthPixels * widthFraction).toInt()
        } else {
            LinearLayout.LayoutParams.MATCH_PARENT
        }
        container.addView(view, LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
    }

    fun createCard(context: Context, card: VoiceOverlayBridge.ImageCard, scale: Float = 1f): View {
        val view = LayoutInflater.from(context).inflate(R.layout.view_voice_image_card, null)

        val image = view.findViewById<ImageView>(R.id.imageCardImage)
        val thumb = card.thumbnail
        image.load(card.url) {
            crossfade(true)
            // The full image hotlinks to a third-party host and can 403/die; fall back
            // to the reliable Google-CDN thumbnail.
            if (!thumb.isNullOrEmpty()) listener(onError = { _, _ -> image.load(thumb) })
        }
        image.contentDescription = card.description ?: "Image"

        view.findViewById<TextView>(R.id.imageCardCaption).apply {
            if (!card.description.isNullOrEmpty()) { text = card.description; visibility = View.VISIBLE }
            else visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.imageCardAttribution).apply {
            val attr = card.attribution
            if (!attr.isNullOrEmpty()) { text = attr; visibility = View.VISIBLE }
            else visibility = View.GONE
        }

        if (scale != 1f) scaleCard(view, scale)
        return view
    }

    /** Scale for full-screen: raise the image's max height (it stays fitCenter, so
     *  the photo just gets bigger without cropping) + grow the caption text. */
    private fun scaleCard(view: View, scale: Float) {
        view.findViewById<ImageView>(R.id.imageCardImage)?.let { iv ->
            iv.maxHeight = (iv.maxHeight * scale).toInt()
        }
        view.findViewById<TextView>(R.id.imageCardCaption)?.let {
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize * scale)
        }
    }
}
