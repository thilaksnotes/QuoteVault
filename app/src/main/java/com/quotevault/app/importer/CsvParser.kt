package com.quotevault.app.importer

data class ImportedQuote(
    val text: String,
    val source: String?,
    val author: String?,
    val category: String?,
    val page: String?,
    val url: String?
)

data class CsvRowError(val rowNumber: Int, val reason: String)

data class CsvParseResult(
    val quotes: List<ImportedQuote>,
    val errors: List<CsvRowError>,
    val totalDataRows: Int
)

object CsvParser {
    private val acceptedHeaders = mapOf(
        "quote" to setOf("quote"),
        "source" to setOf("source"),
        "author" to setOf("author"),
        "category" to setOf("category"),
        "page" to setOf("page"),
        "url" to setOf("url")
    )

    fun parse(csv: String): CsvParseResult {
        val records = parseRecords(csv.removePrefix("\uFEFF"))
        require(records.isNotEmpty()) { "The CSV file is empty." }

        val headers = records.first().map { it.trim().removeSurrounding("\"").trim().lowercase() }
        require(headers.contains("quote")) {
            "Required column 'Quote' was not found. Use the QuoteVault CSV template."
        }
        require(headers.distinct().size == headers.size) {
            "The CSV contains duplicate column names."
        }

        fun index(name: String): Int? =
            headers.indexOfFirst { it in acceptedHeaders.getValue(name) }.takeIf { it >= 0 }

        val quoteIndex = index("quote")!!
        val sourceIndex = index("source")
        val authorIndex = index("author")
        val categoryIndex = index("category")
        val pageIndex = index("page")
        val urlIndex = index("url")

        val quotes = mutableListOf<ImportedQuote>()
        val errors = mutableListOf<CsvRowError>()
        val data = records.drop(1)

        data.forEachIndexed { i, row ->
            val rowNumber = i + 2
            if (row.size > headers.size) {
                errors += CsvRowError(rowNumber, "This row has more values than the header.")
                return@forEachIndexed
            }

            fun cell(col: Int?): String? =
                col?.let { row.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }

            val text = cell(quoteIndex)
            if (text == null) {
                if (row.any { it.isNotBlank() }) errors += CsvRowError(rowNumber, "Quote is empty.")
                return@forEachIndexed
            }

            quotes += ImportedQuote(
                text = text,
                source = cell(sourceIndex),
                author = cell(authorIndex),
                category = cell(categoryIndex),
                page = cell(pageIndex),
                url = cell(urlIndex)
            )
        }

        return CsvParseResult(quotes, errors, data.size)
    }

    private fun parseRecords(input: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        while (i < input.length) {
            val c = input[i]
            when {
                c == '"' && quoted && i + 1 < input.length && input[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    row.add(field.toString())
                    field.clear()
                }
                (c == '\n' || c == '\r') && !quoted -> {
                    row.add(field.toString())
                    field.clear()
                    if (row.any { it.isNotBlank() }) records.add(row.toList())
                    row = mutableListOf()
                    if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                }
                else -> field.append(c)
            }
            i++
        }

        require(!quoted) { "Invalid CSV: an opening quotation mark has no matching closing mark." }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) records.add(row.toList())
        }
        return records
    }
}
