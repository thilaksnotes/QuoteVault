package com.quotevault.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

/** Portable, versioned JSON backup stored as a single .qvbackup file. */
object QuoteBackupManager {
    private const val FORMAT = "quotevault-backup"
    private const val FORMAT_VERSION = 1

    fun writeBackup(context: Context, uri: Uri, quotes: List<Quote>) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("quoteCount", quotes.size)

        val rows = JSONArray()
        quotes.forEach { quote ->
            rows.put(
                JSONObject()
                    .put("id", quote.id)
                    .put("text", quote.text)
                    .put("normalizedText", quote.normalizedText)
                    .put("source", quote.source ?: JSONObject.NULL)
                    .put("author", quote.author ?: JSONObject.NULL)
                    .put("category", quote.category ?: JSONObject.NULL)
                    .put("page", quote.page ?: JSONObject.NULL)
                    .put("url", quote.url ?: JSONObject.NULL)
                    .put("isFavourite", quote.isFavourite)
                    .put("dateImported", quote.dateImported)
            )
        }
        root.put("quotes", rows)

        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(root.toString(2))
            it.flush()
        } ?: error("Could not create the backup file.")
    }

    fun readBackup(input: InputStream): List<Quote> {
        val content = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        require(content.isNotBlank()) { "The selected backup file is empty." }
        val root = try {
            JSONObject(content)
        } catch (_: Exception) {
            throw IllegalArgumentException("The selected file is not a valid QuoteVault backup.")
        }

        require(root.optString("format") == FORMAT) { "This file is not a QuoteVault backup." }
        require(root.optInt("formatVersion", -1) == FORMAT_VERSION) {
            "This backup version is not supported by this version of QuoteVault."
        }
        val rows = root.optJSONArray("quotes")
            ?: throw IllegalArgumentException("The backup does not contain a quotes list.")

        val quotes = ArrayList<Quote>(rows.length())
        val seenIds = mutableSetOf<Long>()
        val seenTexts = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index)
                ?: throw IllegalArgumentException("Backup entry ${index + 1} is invalid.")
            val text = item.optString("text", "").trim()
            require(text.isNotEmpty()) { "Backup entry ${index + 1} has empty quote text." }
            // Recompute the normalization from quote text instead of trusting backup metadata.
            val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            require(normalized.isNotBlank()) { "Backup entry ${index + 1} has invalid quote text." }
            val id = item.optLong("id", -1L)
            require(id > 0L && seenIds.add(id)) { "Backup contains a missing or duplicate quote ID." }
            require(seenTexts.add(normalized)) { "Backup contains duplicate quotes." }

            fun optionalString(key: String): String? =
                if (item.isNull(key)) null else item.optString(key).takeIf { it.isNotBlank() }

            quotes += Quote(
                id = id,
                text = text,
                normalizedText = normalized,
                source = optionalString("source"),
                author = optionalString("author"),
                category = optionalString("category"),
                page = optionalString("page"),
                url = optionalString("url"),
                isFavourite = item.optBoolean("isFavourite", false),
                dateImported = item.optLong("dateImported", System.currentTimeMillis())
            )
        }
        return quotes
    }
}
