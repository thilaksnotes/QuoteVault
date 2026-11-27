package com.quotevault.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Quote::class], version = 1, exportSchema = false)
abstract class QuoteDatabase : RoomDatabase() {
    abstract fun quoteDao(): QuoteDao

    companion object {
        @Volatile private var INSTANCE: QuoteDatabase? = null

        fun getInstance(context: Context): QuoteDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuoteDatabase::class.java,
                    "quotevault.db"
                ).build().also { INSTANCE = it }
            }
    }
}
