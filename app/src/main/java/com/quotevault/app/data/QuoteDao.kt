package com.quotevault.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes ORDER BY dateImported DESC, id DESC")
    suspend fun getAll(): List<Quote>

    @Query("SELECT * FROM quotes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Quote?

    @Query("SELECT normalizedText FROM quotes")
    suspend fun getAllNormalizedTexts(): List<String>

    @Query("SELECT * FROM quotes WHERE normalizedText = :normalizedText LIMIT 1")
    suspend fun findByNormalizedText(normalizedText: String): Quote?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(quotes: List<Quote>): List<Long>

    @Update
    suspend fun update(quote: Quote): Int

    @Query("UPDATE quotes SET isFavourite = CASE WHEN isFavourite = 1 THEN 0 ELSE 1 END WHERE id = :id")
    suspend fun toggleFavourite(id: Long): Int

    @Delete
    suspend fun delete(quote: Quote): Int

    @Query("SELECT COUNT(*) FROM quotes")
    suspend fun count(): Int
}
