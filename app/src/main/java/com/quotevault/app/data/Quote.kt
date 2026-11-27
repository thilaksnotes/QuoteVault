package com.quotevault.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "quotes",
    indices = [Index(value = ["normalizedText"], unique = true)]
)
data class Quote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val normalizedText: String,
    val source: String? = null,
    val author: String? = null,
    val category: String? = null,
    val page: String? = null,
    val url: String? = null,
    val isFavourite: Boolean = false,
    val dateImported: Long = System.currentTimeMillis()
)
