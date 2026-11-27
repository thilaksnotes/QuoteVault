package com.quotevault.app

import android.app.Application

/** Keep Application startup lightweight; periodic work is scheduled from MainActivity. */
class QuoteVaultApplication : Application()
