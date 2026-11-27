package com.quotevault.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rotates each installed QuoteVault widget when Android grants the scheduled work window. */
class QuoteRotationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) {
            QuoteWidgetProvider().rotateForScheduledWork(applicationContext)
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
