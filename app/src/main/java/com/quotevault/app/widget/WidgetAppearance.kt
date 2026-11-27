package com.quotevault.app.widget

import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import com.quotevault.app.R

/** Shared appearance preferences used by the app settings screen and widget renderer. */
object WidgetAppearance {
    const val PREFERENCES = "quote_widget_preferences"
    const val KEY_PALETTE = "widget_palette"
    const val KEY_OPACITY = "widget_opacity"
    const val KEY_REFRESH_INTERVAL_MINUTES = "widget_refresh_interval_minutes"
    const val DEFAULT_REFRESH_INTERVAL_MINUTES = 60

    data class Palette(
        val key: String,
        val label: String,
        val color: Int,
        val textColor: Int,
        val mutedTextColor: Int,
        val dividerColor: Int
    )

    val palettes = listOf(
        Palette("paper", "Warm paper", Color.rgb(255, 249, 241), Color.rgb(40, 35, 31), Color.rgb(118, 107, 96), Color.rgb(229, 220, 209)),
        Palette("white", "White", Color.WHITE, Color.rgb(38, 38, 38), Color.rgb(105, 105, 105), Color.rgb(225, 225, 225)),
        Palette("sage", "Sage", Color.rgb(228, 238, 230), Color.rgb(36, 53, 42), Color.rgb(83, 105, 89), Color.rgb(190, 209, 194)),
        Palette("rose", "Rose", Color.rgb(244, 229, 229), Color.rgb(65, 45, 50), Color.rgb(119, 87, 94), Color.rgb(220, 194, 199)),
        Palette("lavender", "Lavender", Color.rgb(236, 232, 246), Color.rgb(48, 42, 64), Color.rgb(100, 91, 124), Color.rgb(205, 197, 224)),
        Palette("charcoal", "Charcoal", Color.rgb(41, 45, 50), Color.rgb(246, 246, 246), Color.rgb(205, 209, 214), Color.rgb(88, 94, 101)),
        Palette("navy", "Midnight blue", Color.rgb(31, 49, 71), Color.rgb(245, 247, 250), Color.rgb(196, 207, 222), Color.rgb(77, 96, 120))
    )

    fun selected(context: Context): Palette {
        val key = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_PALETTE, "paper")
        return palettes.firstOrNull { it.key == key } ?: palettes.first()
    }

    fun refreshIntervalMinutes(context: Context): Int = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt(KEY_REFRESH_INTERVAL_MINUTES, DEFAULT_REFRESH_INTERVAL_MINUTES)

    fun opacity(context: Context): Int = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt(KEY_OPACITY, 100)
        .coerceIn(20, 100)

    fun apply(context: Context, views: RemoteViews) {
        val palette = selected(context)
        val alpha = (opacity(context) * 255 / 100).coerceIn(0, 255)
        val background = Color.argb(
            alpha,
            Color.red(palette.color),
            Color.green(palette.color),
            Color.blue(palette.color)
        )
        views.setInt(R.id.widget_root, "setBackgroundColor", background)
        views.setTextColor(R.id.widget_brand, palette.mutedTextColor)
        views.setTextColor(R.id.widget_attribution, palette.mutedTextColor)
        views.setInt(R.id.widget_divider, "setBackgroundColor", palette.dividerColor)
        views.setInt(R.id.widget_refresh, "setColorFilter", palette.textColor)
        views.setInt(R.id.widget_favourite, "setColorFilter", palette.textColor)
        views.setInt(R.id.widget_delete, "setColorFilter", palette.textColor)
    }
}
