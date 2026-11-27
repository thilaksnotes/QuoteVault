package com.quotevault.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.quotevault.app.MainActivity
import com.quotevault.app.R
import com.quotevault.app.data.Quote
import com.quotevault.app.data.QuoteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgetsAsync(context, appWidgetIds, rotate = false, pendingResult = goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH_ONE -> {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: intArrayOf(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                val validIds = ids.filter { it >= 0 }.toIntArray()
                if (validIds.isNotEmpty()) {
                    updateWidgetsAsync(context, validIds, rotate = true, pendingResult = goAsync())
                }
                return
            }
            ACTION_SYNC_ALL, ACTION_ROTATE_ALL -> {
                val ids = widgetIds(context)
                if (ids.isNotEmpty()) {
                    updateWidgetsAsync(
                        context,
                        ids,
                        rotate = intent.action == ACTION_ROTATE_ALL,
                        pendingResult = goAsync()
                    )
                }
                return
            }
            ACTION_FAVOURITE -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val quoteId = intent.getLongExtra(EXTRA_QUOTE_ID, -1L)
                if (widgetId >= 0 && quoteId > 0L) {
                    toggleFavourite(context, widgetId, quoteId, goAsync())
                }
                return
            }
            ACTION_DELETE -> {
                val quoteId = intent.getLongExtra(EXTRA_QUOTE_ID, -1L)
                if (quoteId > 0L) deleteQuote(context, quoteId, goAsync())
                return
            }
        }
        super.onReceive(context, intent)
    }

    private fun toggleFavourite(
        context: Context,
        widgetId: Int,
        quoteId: Long,
        pendingResult: BroadcastReceiver.PendingResult
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = QuoteDatabase.getInstance(context).quoteDao()
                val changedRows = dao.toggleFavourite(quoteId)
                if (changedRows > 0) {
                    // Refresh all widgets so the star reflects the database state everywhere.
                    updateWidgets(context, widgetIds(context), rotate = false)
                }
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Unable to toggle quote favourite from widget", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun deleteQuote(
        context: Context,
        quoteId: Long,
        pendingResult: BroadcastReceiver.PendingResult
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = QuoteDatabase.getInstance(context).quoteDao()
                val quote = dao.getById(quoteId)
                if (quote != null) {
                    dao.delete(quote)
                    // The deleted quote is no longer available; update every widget immediately.
                    updateWidgets(context, widgetIds(context), rotate = false)
                }
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Unable to delete quote from widget", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun updateWidgetsAsync(
        context: Context,
        appWidgetIds: IntArray,
        rotate: Boolean,
        pendingResult: BroadcastReceiver.PendingResult
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgets(context, appWidgetIds, rotate)
            } catch (_: Exception) {
                // Keep the widget host responsive if the database or provider is unavailable.
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun updateWidgets(context: Context, appWidgetIds: IntArray, rotate: Boolean) {
        val dao = QuoteDatabase.getInstance(context).quoteDao()
        val quotes = dao.getAll()
        val manager = AppWidgetManager.getInstance(context)

        appWidgetIds.forEach { widgetId ->
            val quote = resolveQuote(context, widgetId, quotes, rotate)
            val views = buildRemoteViews(context, widgetId, quote)
            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_quote_list)
        }
    }

    private fun resolveQuote(
        context: Context,
        widgetId: Int,
        quotes: List<Quote>,
        rotate: Boolean
    ): Quote? {
        if (quotes.isEmpty()) {
            saveCurrentQuoteId(context, widgetId, -1L)
            return null
        }

        val lastId = currentQuoteId(context, widgetId)
        val current = quotes.firstOrNull { it.id == lastId }
        if (!rotate && current != null) return current

        val candidates = quotes.filterNot { it.id == lastId }
        val selected = (if (rotate && candidates.isNotEmpty()) candidates else quotes).random()
        saveCurrentQuoteId(context, widgetId, selected.id)
        return selected
    }

    private fun buildRemoteViews(context: Context, widgetId: Int, quote: Quote?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.quote_widget)

        if (quote == null) {
            views.setTextViewText(R.id.widget_attribution, "Import highlights in QuoteVault to get started")
        } else {
            val attribution = listOfNotNull(
                quote.source?.trim()?.takeIf { it.isNotEmpty() },
                quote.author?.trim()?.takeIf { it.isNotEmpty() }
            ).joinToString("  •  ")
            views.setTextViewText(
                R.id.widget_attribution,
                attribution.ifBlank { "Your QuoteVault collection" }
            )
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_WIDGET_OPEN_QUOTES, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Collection rows supply fill-in extras, so the template PendingIntent must be mutable.
        // It targets this app's explicit Activity; no implicit external intent can be filled in.
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            widgetId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        // ListView is an AdapterView: use a pending-intent template and row fill-in intent
        // for taps on the quote itself. Keep the brand as a direct app-opening target.
        views.setOnClickPendingIntent(R.id.widget_brand, openAppPendingIntent)
        views.setPendingIntentTemplate(R.id.widget_quote_list, openAppPendingIntent)

        val refreshIntent = actionIntent(context, ACTION_REFRESH_ONE, widgetId)
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            widgetId + REFRESH_REQUEST_CODE_OFFSET,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)

        if (quote != null) {
            val favouriteIntent = actionIntent(context, ACTION_FAVOURITE, widgetId).apply {
                putExtra(EXTRA_QUOTE_ID, quote.id)
                data = Uri.parse("quotevault://widget/favourite/$widgetId/${quote.id}")
            }
            val favouritePendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId + FAVOURITE_REQUEST_CODE_OFFSET,
                favouriteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_favourite, favouritePendingIntent)
            views.setImageViewResource(
                R.id.widget_favourite,
                if (quote.isFavourite) R.drawable.ic_quote_star_filled
                else R.drawable.ic_quote_star_outline
            )

            val deleteIntent = actionIntent(context, ACTION_DELETE, widgetId).apply {
                putExtra(EXTRA_QUOTE_ID, quote.id)
                data = Uri.parse("quotevault://widget/delete/$widgetId/${quote.id}")
            }
            val deletePendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId + DELETE_REQUEST_CODE_OFFSET,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_delete, deletePendingIntent)
        } else {
            views.setViewVisibility(R.id.widget_favourite, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_delete, android.view.View.GONE)
        }
        views.setRemoteAdapter(R.id.widget_quote_list, Intent(context, QuoteWidgetRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("quotevault://widget/$widgetId")
        })

        WidgetAppearance.apply(context, views)
        return views
    }

    private fun actionIntent(context: Context, action: String, widgetId: Int) =
        Intent(context, QuoteWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }

    /** Called by WorkManager; executes the rotation synchronously within the worker coroutine. */
    internal suspend fun rotateForScheduledWork(context: Context) {
        val ids = widgetIds(context)
        if (ids.isNotEmpty()) updateWidgets(context, ids, rotate = true)
    }

    companion object {
        private const val ACTION_REFRESH_ONE = "com.quotevault.app.widget.ACTION_REFRESH_ONE"
        private const val ACTION_SYNC_ALL = "com.quotevault.app.widget.ACTION_SYNC_ALL"
        private const val ACTION_ROTATE_ALL = "com.quotevault.app.widget.ACTION_ROTATE_ALL"
        private const val ACTION_FAVOURITE = "com.quotevault.app.widget.ACTION_FAVOURITE"
        private const val ACTION_DELETE = "com.quotevault.app.widget.ACTION_DELETE"
        const val EXTRA_QUOTE_ID = "com.quotevault.app.widget.EXTRA_QUOTE_ID"
        private const val TAG = "QuoteWidgetProvider"
        private const val PREFERENCES = WidgetAppearance.PREFERENCES
        private const val REFRESH_REQUEST_CODE_OFFSET = 10000
        private const val FAVOURITE_REQUEST_CODE_OFFSET = 20000
        private const val DELETE_REQUEST_CODE_OFFSET = 30000
        private const val CURRENT_QUOTE_PREFIX = "current_quote_"

        fun refreshAll(context: Context) = sendAllAction(context, ACTION_SYNC_ALL)

        fun rotateAll(context: Context) = sendAllAction(context, ACTION_ROTATE_ALL)

        private fun sendAllAction(context: Context, action: String) {
            if (widgetIds(context).isEmpty()) return
            context.sendBroadcast(Intent(context, QuoteWidgetProvider::class.java).apply {
                this.action = action
            })
        }

        internal fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, QuoteWidgetProvider::class.java))

        internal fun currentQuoteId(context: Context, widgetId: Int): Long =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getLong(CURRENT_QUOTE_PREFIX + widgetId, -1L)

        internal fun saveCurrentQuoteId(context: Context, widgetId: Int, quoteId: Long) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putLong(CURRENT_QUOTE_PREFIX + widgetId, quoteId).apply()
        }
    }
}
