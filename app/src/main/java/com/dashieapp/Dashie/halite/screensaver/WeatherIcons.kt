package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R

/**
 * Maps Home Assistant weather condition strings to icons for native display.
 * HA conditions: https://www.home-assistant.io/integrations/weather/
 */
object WeatherIcons {

    fun getEmoji(condition: String, isNight: Boolean = false): String {
        return when (condition) {
            "clear-night" -> "🌙"
            "sunny" -> if (isNight) "🌙" else "☀️"
            "partlycloudy" -> "⛅"
            "cloudy" -> "☁️"
            "rainy" -> "🌧️"
            "pouring" -> "🌧️"
            "snowy" -> "❄️"
            // "snowy-rainy" (sleet/wintry mix) and "hail" are NOT snow — the
            // cloud-with-snow glyph 🌨️ made a 92°F hail day read as snow. Map
            // them to rain/storm glyphs, consistent with getDrawableRes()
            // (rainy / heavy_rain) and getLabel() ("Sleet" / "Hail"). Real
            // snow keeps ❄️ above.
            "snowy-rainy" -> "🌧️"
            "lightning" -> "⚡"
            "lightning-rainy" -> "⛈️"
            "hail" -> "⛈️"
            "fog" -> "☁️"
            "windy" -> "💨"
            "windy-variant" -> "💨"
            "exceptional" -> "⚠️"
            else -> "🌡️"
        }
    }

    /**
     * Returns the drawable resource ID for a weather condition.
     * Used for screensaver inline display where white vector icons are needed.
     */
    fun getDrawableRes(condition: String, isNight: Boolean = false): Int {
        return when (condition) {
            "clear-night" -> R.drawable.ic_weather_moon
            "sunny" -> if (isNight) R.drawable.ic_weather_moon else R.drawable.ic_weather_sunny
            "partlycloudy" -> if (isNight) R.drawable.ic_weather_moon_cloud else R.drawable.ic_weather_partly_cloudy
            "cloudy" -> R.drawable.ic_weather_cloudy
            "rainy" -> R.drawable.ic_weather_rainy
            "pouring" -> R.drawable.ic_weather_heavy_rain
            "snowy" -> R.drawable.ic_weather_snow
            "snowy-rainy" -> R.drawable.ic_weather_rainy
            "lightning" -> R.drawable.ic_weather_thunderstorm
            "lightning-rainy" -> R.drawable.ic_weather_thunderstorm
            "hail" -> R.drawable.ic_weather_heavy_rain
            "fog" -> R.drawable.ic_weather_cloudy
            "windy" -> R.drawable.ic_weather_cloudy
            "windy-variant" -> R.drawable.ic_weather_cloudy
            "exceptional" -> R.drawable.ic_weather_cloudy
            else -> R.drawable.ic_weather_cloudy
        }
    }

    /**
     * Build a SpannableString with a vector drawable icon + temperature text.
     * The icon is sized to match the surrounding text.
     */
    fun buildWeatherSpan(context: Context, condition: String, tempText: String, isNight: Boolean, textSizePx: Float): CharSequence {
        val drawableRes = getDrawableRes(condition, isNight)
        val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return "$condition $tempText"

        val iconSize = (textSizePx * 1.3f).toInt()
        drawable.setBounds(0, 0, iconSize, iconSize)

        // Use placeholder char for the icon, then append temp text
        val text = "\u0000 $tempText"
        val spannable = SpannableString(text)
        spannable.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    fun getLabel(condition: String, isNight: Boolean = false): String {
        return when (condition) {
            "clear-night" -> "Clear"
            // At night a "sunny" condition renders the moon glyph, so the
            // label must read "Clear" to match — not "Sunny".
            "sunny" -> if (isNight) "Clear" else "Sunny"
            "partlycloudy" -> "Partly Cloudy"
            "cloudy" -> "Cloudy"
            "rainy" -> "Rain"
            "pouring" -> "Heavy Rain"
            "snowy" -> "Snow"
            "snowy-rainy" -> "Sleet"
            "lightning" -> "Lightning"
            "lightning-rainy" -> "Thunderstorm"
            "hail" -> "Hail"
            "fog" -> "Fog"
            "windy" -> "Windy"
            "windy-variant" -> "Windy"
            "exceptional" -> "Exceptional"
            else -> condition.replaceFirstChar { it.uppercase() }
        }
    }
}
