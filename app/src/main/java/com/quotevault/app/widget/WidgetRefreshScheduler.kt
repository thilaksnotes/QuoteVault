package com.quotevault.app.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules best-effort periodic rotation. Android enforces a 15-minute minimum. */
object WidgetRefreshScheduler {
    const val MIN_INTERVAL_MINUTES = 15L
    private const val UNIQUE_WORK_NAME = "quotevault_periodic_quote_rotation"

    fun ensureScheduled(context: Context) {
        val interval = WidgetAppearance.refreshIntervalMinutes(context).toLong()
        if (interval >= MIN_INTERVAL_MINUTES) schedule(context, interval)
        else schedule(context, WidgetAppearance.DEFAULT_REFRESH_INTERVAL_MINUTES.toLong())
    }

    fun schedule(context: Context, intervalMinutes: Long) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (intervalMinutes <= 0L) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val safeInterval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<QuoteRotationWorker>(
            safeInterval,
            TimeUnit.MINUTES
        ).build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
