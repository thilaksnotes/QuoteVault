package com.quotevault.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.quotevault.app.MainActivity
import com.quotevault.app.R
import com.quotevault.app.data.Quote
import com.quotevault.app.data.QuoteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class QuoteWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        QuoteRemoteViewsFactory(
            applicationContext,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        )
}

private class QuoteRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {
    private var quote: Quote? = null

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val quoteId = QuoteWidgetProvider.currentQuoteId(context, widgetId)
        quote = runBlocking(Dispatchers.IO) {
            if (quoteId > 0L) QuoteDatabase.getInstance(context).quoteDao().getById(quoteId)
            else null
        }
    }

    override fun onDestroy() { quote = null }

    override fun getCount(): Int = 1

    override fun getViewAt(position: Int): RemoteViews {
        val palette = WidgetAppearance.selected(context)
        val views = RemoteViews(context.packageName, R.layout.widget_quote_row)
        val text = quote?.text?.let { "“$it”" }
            ?: "Your quote collection is waiting. Import your highlights in QuoteVault."
        views.setTextViewText(R.id.widget_quote_text, text)
        views.setTextColor(R.id.widget_quote_text, palette.textColor)
        // The ListView forwards this fill-in intent through its PendingIntent template.
        // Opening the quote body navigates to the All Quotes screen in QuoteVault.
        views.setOnClickFillInIntent(
            R.id.widget_quote_text,
            Intent().putExtra(MainActivity.EXTRA_WIDGET_OPEN_QUOTES, true)
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = widgetId.toLong()
    override fun hasStableIds(): Boolean = false
}
