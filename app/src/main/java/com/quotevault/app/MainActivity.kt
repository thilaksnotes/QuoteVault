package com.quotevault.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quotevault.app.data.Quote
import com.quotevault.app.data.QuoteDatabase
import com.quotevault.app.importer.CsvParser
import com.quotevault.app.importer.ImportedQuote
import com.quotevault.app.widget.QuoteWidgetProvider
import com.quotevault.app.widget.WidgetAppearance
import com.quotevault.app.widget.WidgetRefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_WIDGET_DELETE_QUOTE_ID = "com.quotevault.app.EXTRA_WIDGET_DELETE_QUOTE_ID"
        const val EXTRA_WIDGET_OPEN_QUOTES = "com.quotevault.app.EXTRA_WIDGET_OPEN_QUOTES"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Schedule periodic rotation after Activity startup, not from Application.onCreate.
        // A scheduler initialization issue must never prevent the home-screen widget loading.
        runCatching { WidgetRefreshScheduler.ensureScheduled(applicationContext) }
        val deleteQuoteId = intent.getLongExtra(EXTRA_WIDGET_DELETE_QUOTE_ID, -1L)
            .takeIf { it > 0L }
        val openQuotes = intent.getBooleanExtra(EXTRA_WIDGET_OPEN_QUOTES, false)
        setContent {
            MaterialTheme {
                QuoteVaultApp(
                    initialDeleteQuoteId = deleteQuoteId,
                    initialShowAllQuotes = openQuotes
                )
            }
        }
    }
}

private data class PreviewState(
    val fileName: String,
    val rows: List<ImportedQuote>,
    val errors: List<String>,
    val duplicates: Int
)

private data class SourceGroup(
    val key: String,
    val title: String,
    val authors: String,
    val quotes: List<Quote>
)

private fun normalizeQuote(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun buildSourceGroups(quotes: List<Quote>): List<SourceGroup> {
    return quotes
        .groupBy { quote ->
            quote.source
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { normalizeQuote(it) }
                ?: "__unspecified_source__"
        }
        .map { (key, items) ->
            val title = items.firstNotNullOfOrNull {
                it.source?.trim()?.takeIf(String::isNotEmpty)
            } ?: "Unspecified source"

            val authors = items.mapNotNull {
                it.author?.trim()?.takeIf(String::isNotEmpty)
            }.distinctBy { it.lowercase(Locale.ROOT) }.joinToString(", ")

            SourceGroup(
                key = key,
                title = title,
                authors = authors,
                quotes = items.sortedByDescending { it.dateImported }
            )
        }
        .sortedWith(
            compareBy<SourceGroup> { it.title == "Unspecified source" }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuoteVaultApp(
    initialDeleteQuoteId: Long? = null,
    initialShowAllQuotes: Boolean = false
) {
    val context = LocalContext.current
    val dao = remember { QuoteDatabase.getInstance(context).quoteDao() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var library by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var preview by remember { mutableStateOf<PreviewState?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedSourceKey by remember { mutableStateOf<String?>(null) }
    var showAllQuotes by remember { mutableStateOf(initialShowAllQuotes) }
    var editingQuote by remember { mutableStateOf<Quote?>(null) }
    var deletingQuote by remember { mutableStateOf<Quote?>(null) }
    var directQuoteText by remember { mutableStateOf("") }
    var directQuoteSource by remember { mutableStateOf("") }
    var directQuoteAuthor by remember { mutableStateOf("") }

    suspend fun refreshLibrary() {
        library = withContext(Dispatchers.IO) { dao.getAll() }
    }

    LaunchedEffect(Unit) { refreshLibrary() }

    LaunchedEffect(initialDeleteQuoteId) {
        if (initialDeleteQuoteId != null) {
            deletingQuote = withContext(Dispatchers.IO) { dao.getById(initialDeleteQuoteId) }
            if (deletingQuote == null) {
                snackbarHostState.showSnackbar("This quote is no longer in your library.")
            }
        }
    }

    fun toggleFavourite(quote: Quote) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dao.toggleFavourite(quote.id)
                }
                refreshLibrary()
                QuoteWidgetProvider.refreshAll(context)
                snackbarHostState.showSnackbar(
                    if (!quote.isFavourite) "Added to favourites" else "Removed from favourites"
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "Unable to update favourite.")
            }
        }
    }

    fun saveEditedQuote(
        original: Quote,
        text: String,
        source: String,
        author: String,
        category: String,
        page: String,
        url: String
    ) {
        scope.launch {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) {
                snackbarHostState.showSnackbar("Quote text cannot be empty.")
                return@launch
            }

            busy = true
            try {
                val normalized = normalizeQuote(cleanText)
                val duplicate = withContext(Dispatchers.IO) {
                    dao.findByNormalizedText(normalized)
                }

                if (duplicate != null && duplicate.id != original.id) {
                    snackbarHostState.showSnackbar(
                        "Another quote with the same text already exists. The edit was not saved."
                    )
                    return@launch
                }

                val updated = original.copy(
                    text = cleanText,
                    normalizedText = normalized,
                    source = source.trim().takeIf { it.isNotEmpty() },
                    author = author.trim().takeIf { it.isNotEmpty() },
                    category = category.trim().takeIf { it.isNotEmpty() },
                    page = page.trim().takeIf { it.isNotEmpty() },
                    url = url.trim().takeIf { it.isNotEmpty() }
                )

                withContext(Dispatchers.IO) { dao.update(updated) }
                refreshLibrary()
                QuoteWidgetProvider.refreshAll(context)
                editingQuote = null
                snackbarHostState.showSnackbar("Quote updated.")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "Unable to update quote.")
            } finally {
                busy = false
            }
        }
    }

    fun confirmDelete(quote: Quote) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { dao.delete(quote) }
                refreshLibrary()
                QuoteWidgetProvider.refreshAll(context)
                deletingQuote = null
                snackbarHostState.showSnackbar("Quote deleted.")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "Unable to delete quote.")
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                try {
                    val displayName = uri.lastPathSegment ?: "Selected CSV"
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            ?: error("Could not read the selected file.")
                    }
                    val parsed = withContext(Dispatchers.Default) { CsvParser.parse(content) }
                    val existing = withContext(Dispatchers.IO) {
                        dao.getAllNormalizedTexts().toSet()
                    }

                    val seen = mutableSetOf<String>()
                    var duplicateCount = 0
                    val uniqueRows = parsed.quotes.filter { item ->
                        val key = normalizeQuote(item.text)
                        if (key in existing || !seen.add(key)) {
                            duplicateCount++
                            false
                        } else {
                            true
                        }
                    }

                    preview = PreviewState(
                        fileName = displayName,
                        rows = uniqueRows,
                        errors = parsed.errors.map {
                            "Row ${it.rowNumber}: ${it.reason}"
                        },
                        duplicates = duplicateCount
                    )
                    message = "CSV analysed: ${parsed.totalDataRows} data rows."
                } catch (e: Exception) {
                    preview = null
                    message = e.message ?: "Unable to process this CSV file."
                } finally {
                    busy = false
                }
            }
        }
    }

    val sourceGroups = remember(library) { buildSourceGroups(library) }
    val currentSource = sourceGroups.firstOrNull { it.key == selectedSourceKey }

    if (editingQuote != null) {
        EditQuoteDialog(
            quote = editingQuote!!,
            busy = busy,
            onDismiss = { if (!busy) editingQuote = null },
            onSave = { text, source, author, category, page, url ->
                saveEditedQuote(editingQuote!!, text, source, author, category, page, url)
            }
        )
    }

    if (deletingQuote != null) {
        AlertDialog(
            onDismissRequest = { deletingQuote = null },
            title = { Text("Delete quote?") },
            text = {
                Text("This quote will be permanently deleted from your library. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete(deletingQuote!!) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingQuote = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectedTab == 0 && (selectedSourceKey != null || showAllQuotes)) {
                        IconButton(onClick = {
                            selectedSourceKey = null
                            showAllQuotes = false
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back to library")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            when {
                                selectedTab == 1 -> "QuoteVault"
                                selectedTab == 2 -> "Widget appearance"
                                selectedTab == 3 -> "Add quotes"
                                showAllQuotes -> "All Quotes"
                                currentSource != null -> currentSource.title
                                else -> "QuoteVault"
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when {
                                selectedTab == 1 -> "Import your highlights"
                                selectedTab == 2 -> "Background and opacity"
                                selectedTab == 3 -> "One quote per line"
                                showAllQuotes -> "${library.size} highlights"
                                currentSource != null -> "${currentSource.quotes.size} highlights"
                                else -> "Your personal highlight library"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        selectedSourceKey = null
                        showAllQuotes = false
                    },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        selectedSourceKey = null
                        showAllQuotes = false
                    },
                    icon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                    label = { Text("Import") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        selectedSourceKey = null
                        showAllQuotes = false
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Widget") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = {
                        selectedTab = 3
                        selectedSourceKey = null
                        showAllQuotes = false
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text("Add") }
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (selectedTab == 1) {
                ImportContent(
                    busy = busy,
                    message = message,
                    preview = preview,
                    onChoose = {
                        picker.launch(
                            arrayOf(
                                "text/*",
                                "text/csv",
                                "application/csv",
                                "application/vnd.ms-excel"
                            )
                        )
                    },
                    onCancel = { preview = null },
                    onImport = {
                        val current = preview ?: return@ImportContent
                        scope.launch {
                            busy = true
                            try {
                                val entities = current.rows.map { item ->
                                    Quote(
                                        text = item.text,
                                        normalizedText = normalizeQuote(item.text),
                                        source = item.source,
                                        author = item.author,
                                        category = item.category,
                                        page = item.page,
                                        url = item.url
                                    )
                                }
                                val inserted = withContext(Dispatchers.IO) {
                                    dao.insertAll(entities)
                                }
                                val imported = inserted.count { it != -1L }
                                val skipped = entities.size - imported
                                refreshLibrary()
                                QuoteWidgetProvider.refreshAll(context)
                                preview = null
                                message =
                                    "Import complete: $imported imported, " +
                                            "${current.duplicates + skipped} duplicates skipped, " +
                                            "${current.errors.size} invalid rows."
                            } catch (e: Exception) {
                                message = e.message ?: "Import failed."
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
            } else if (selectedTab == 2) {
                WidgetSettingsScreen()
            } else if (selectedTab == 3) {
                AddQuotesScreen(
                    quoteText = directQuoteText,
                    onQuoteTextChange = { directQuoteText = it },
                    source = directQuoteSource,
                    onSourceChange = { directQuoteSource = it },
                    author = directQuoteAuthor,
                    onAuthorChange = { directQuoteAuthor = it },
                    busy = busy,
                    onAdd = {
                        val lines = directQuoteText.lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toList()
                        if (lines.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar("Enter at least one quote.") }
                        } else {
                            scope.launch {
                                busy = true
                                try {
                                    val existing = withContext(Dispatchers.IO) {
                                        dao.getAllNormalizedTexts().toMutableSet()
                                    }
                                    var duplicates = 0
                                    val newQuotes = lines.mapNotNull { line ->
                                        val normalized = normalizeQuote(line)
                                        if (!existing.add(normalized)) {
                                            duplicates++
                                            null
                                        } else {
                                            Quote(
                                                text = line,
                                                normalizedText = normalized,
                                                source = directQuoteSource.trim().takeIf { it.isNotEmpty() },
                                                author = directQuoteAuthor.trim().takeIf { it.isNotEmpty() }
                                            )
                                        }
                                    }
                                    val inserted = if (newQuotes.isNotEmpty()) {
                                        withContext(Dispatchers.IO) { dao.insertAll(newQuotes) }
                                    } else emptyList()
                                    val added = inserted.count { it != -1L }
                                    duplicates += newQuotes.size - added
                                    refreshLibrary()
                                    QuoteWidgetProvider.refreshAll(context)
                                    directQuoteText = ""
                                    snackbarHostState.showSnackbar(
                                        "$added quote(s) added${if (duplicates > 0) ", $duplicates duplicate(s) skipped" else ""}."
                                    )
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(e.message ?: "Unable to add quotes.")
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                )
            } else {
                when {
                    showAllQuotes -> AllQuotesScreen(
                        quotes = library,
                        onToggleFavourite = ::toggleFavourite,
                        onEdit = { editingQuote = it },
                        onDelete = { deletingQuote = it }
                    )
                    currentSource != null -> SourceDetailScreen(
                        source = currentSource,
                        onToggleFavourite = ::toggleFavourite,
                        onEdit = { editingQuote = it },
                        onDelete = { deletingQuote = it }
                    )
                    else -> LibraryScreen(
                        groups = sourceGroups,
                        totalQuotes = library.size,
                        onAllQuotesClick = { showAllQuotes = true },
                        onSourceClick = { selectedSourceKey = it }
                    )
                }
            }
        }
    }
}


@Composable
private fun AddQuotesScreen(
    quoteText: String,
    onQuoteTextChange: (String) -> Unit,
    source: String,
    onSourceChange: (String) -> Unit,
    author: String,
    onAuthorChange: (String) -> Unit,
    busy: Boolean,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Add quotes directly", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Enter or paste multiple quotes. Each non-empty line will be saved as a separate quote.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = quoteText,
            onValueChange = onQuoteTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Quotes") },
            placeholder = { Text("First quote\nSecond quote\nThird quote") },
            minLines = 8,
            maxLines = 16,
            enabled = !busy
        )
        Text(
            "${quoteText.lineSequence().count { it.isNotBlank() }} quote line(s)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = source,
            onValueChange = onSourceChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Source / Book (optional)") },
            singleLine = true,
            enabled = !busy
        )
        OutlinedTextField(
            value = author,
            onValueChange = onAuthorChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Author (optional)") },
            singleLine = true,
            enabled = !busy
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && quoteText.lineSequence().any { it.isNotBlank() }
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Adding..." else "Add quotes to library")
        }
        Text(
            "Blank lines are ignored. Existing quotes and repeated lines are skipped automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WidgetSettingsScreen() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(WidgetAppearance.PREFERENCES, android.content.Context.MODE_PRIVATE)
    }
    var selectedPalette by remember {
        mutableStateOf(WidgetAppearance.selected(context).key)
    }
    var opacity by remember {
        mutableFloatStateOf(WidgetAppearance.opacity(context).toFloat())
    }
    var refreshInterval by remember {
        mutableIntStateOf(WidgetAppearance.refreshIntervalMinutes(context))
    }
    val refreshOptions = listOf(
        15 to "15 min",
        30 to "30 min",
        60 to "1 hour",
        120 to "2 hours",
        180 to "3 hours",
        360 to "6 hours",
        720 to "12 hours"
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Widget background", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Choose a colour for the QuoteVault widget. The setting applies to every QuoteVault widget on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WidgetAppearance.palettes.forEach { palette ->
            FilterChip(
                selected = selectedPalette == palette.key,
                onClick = {
                    selectedPalette = palette.key
                    preferences.edit().putString(WidgetAppearance.KEY_PALETTE, palette.key).apply()
                    QuoteWidgetProvider.refreshAll(context)
                },
                label = { Text(palette.label) },
                leadingIcon = {
                    Surface(
                        modifier = Modifier.size(18.dp),
                        shape = MaterialTheme.shapes.small,
                        color = androidx.compose.ui.graphics.Color(palette.color),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline
                        )
                    ) {}
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        HorizontalDivider()
        Text("Background opacity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "${opacity.toInt()}%",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Slider(
            value = opacity,
            onValueChange = { opacity = it },
            valueRange = 20f..100f,
            steps = 15,
            onValueChangeFinished = {
                preferences.edit().putInt(WidgetAppearance.KEY_OPACITY, opacity.toInt()).apply()
                QuoteWidgetProvider.refreshAll(context)
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20% · More transparent", style = MaterialTheme.typography.labelSmall)
            Text("100% · Solid", style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider()
        Text("Automatic quote refresh", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Choose how often QuoteVault should rotate the widget quote. Android may delay background work to save battery; the interval is not an exact timer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Refresh interval", style = MaterialTheme.typography.titleMedium)
        refreshOptions.chunked(2).forEach { rowOptions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { (minutes, label) ->
                    FilterChip(
                        selected = refreshInterval == minutes,
                        onClick = {
                            refreshInterval = minutes
                            preferences.edit()
                                .putInt(WidgetAppearance.KEY_REFRESH_INTERVAL_MINUTES, minutes)
                                .apply()
                            WidgetRefreshScheduler.schedule(context, minutes.toLong())
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
        Text(
            "15 minutes is the shortest interval Android permits for periodic background work. For shorter intervals, a continuously running foreground service would be required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LibraryScreen(
    groups: List<SourceGroup>,
    totalQuotes: Int,
    onAllQuotesClick: () -> Unit,
    onSourceClick: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }

    val filteredGroups = remember(groups, search) {
        if (search.isBlank()) groups
        else groups.filter { group ->
            group.title.contains(search, ignoreCase = true) ||
                    group.authors.contains(search, ignoreCase = true)
        }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("My Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${groups.size} sources · $totalQuotes highlights",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onAllQuotesClick, enabled = totalQuotes > 0) {
                Text("All quotes")
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search books and articles") }
        )

        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Your library is empty", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Import a CSV to add your first highlights.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (filteredGroups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching books or articles.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 155.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    AllQuotesCard(count = totalQuotes, onClick = onAllQuotesClick)
                }
                gridItems(filteredGroups, key = { it.key }) { group ->
                    SourceCard(group = group, onClick = { onSourceClick(group.key) })
                }
            }
        }
    }
}

@Composable
private fun AllQuotesCard(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("All Quotes", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("$count highlights", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun SourceCard(group: SourceGroup, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    group.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleMedium
                )
                if (group.authors.isNotBlank()) {
                    Text(
                        group.authors,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${group.quotes.size} highlights",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SourceDetailScreen(
    source: SourceGroup,
    onToggleFavourite: (Quote) -> Unit,
    onEdit: (Quote) -> Unit,
    onDelete: (Quote) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "${source.quotes.size} highlights",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (source.authors.isNotBlank()) {
                Text(source.authors, style = MaterialTheme.typography.titleSmall)
            }
        }
        items(source.quotes, key = { it.id }) { quote ->
            QuoteCard(
                quote = quote,
                onToggleFavourite = { onToggleFavourite(quote) },
                onEdit = { onEdit(quote) },
                onDelete = { onDelete(quote) }
            )
        }
    }
}

@Composable
private fun AllQuotesScreen(
    quotes: List<Quote>,
    onToggleFavourite: (Quote) -> Unit,
    onEdit: (Quote) -> Unit,
    onDelete: (Quote) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selectedView by remember { mutableStateOf("All") }
    var selectedCategory by remember { mutableStateOf("All categories") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    val categories = remember(quotes) {
        quotes.mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }

    val filtered = remember(quotes, search, selectedView, selectedCategory) {
        var result = quotes

        when (selectedView) {
            "Favourites" -> result = result.filter { it.isFavourite }
            "Recently added" -> result = result.sortedByDescending { it.dateImported }.take(20)
        }

        if (selectedCategory != "All categories") {
            result = result.filter {
                it.category.equals(selectedCategory, ignoreCase = true)
            }
        }

        if (search.isNotBlank()) {
            result = result.filter {
                it.text.contains(search, ignoreCase = true) ||
                        it.source.orEmpty().contains(search, ignoreCase = true) ||
                        it.author.orEmpty().contains(search, ignoreCase = true) ||
                        it.category.orEmpty().contains(search, ignoreCase = true)
            }
        }

        result.sortedByDescending { it.dateImported }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search highlights") }
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Favourites", "Recently added").forEach { view ->
                FilterChip(
                    selected = selectedView == view,
                    onClick = { selectedView = view },
                    label = { Text(view) }
                )
            }
        }

        Box {
            OutlinedButton(onClick = { categoryMenuExpanded = true }) {
                Text(selectedCategory)
            }
            DropdownMenu(
                expanded = categoryMenuExpanded,
                onDismissRequest = { categoryMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("All categories") },
                    onClick = {
                        selectedCategory = "All categories"
                        categoryMenuExpanded = false
                    }
                )
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category) },
                        onClick = {
                            selectedCategory = category
                            categoryMenuExpanded = false
                        }
                    )
                }
            }
        }

        Text(
            when (selectedView) {
                "Favourites" -> "${filtered.size} favourite quotes"
                "Recently added" -> "${filtered.size} recently added quotes"
                else -> "${filtered.size} of ${quotes.size} highlights"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    when (selectedView) {
                        "Favourites" -> "You haven't favourited any quotes yet."
                        "Recently added" -> "No recent quotes found."
                        else -> "No quotes match these filters."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { quote ->
                    QuoteCard(
                        quote = quote,
                        onToggleFavourite = { onToggleFavourite(quote) },
                        onEdit = { onEdit(quote) },
                        onDelete = { onDelete(quote) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuoteCard(
    quote: Quote,
    onToggleFavourite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(quote.text, style = MaterialTheme.typography.bodyLarge)

            val attribution = listOfNotNull(
                quote.source?.takeIf { it.isNotBlank() },
                quote.author?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (attribution.isNotBlank()) {
                Text(
                    attribution,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val details = listOfNotNull(
                quote.category?.takeIf { it.isNotBlank() },
                quote.page?.takeIf { it.isNotBlank() }?.let { "Page $it" }
            ).joinToString(" · ")
            if (details.isNotBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleFavourite) {
                    Icon(
                        if (quote.isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (quote.isFavourite) "Remove from favourites" else "Add to favourites",
                        tint = if (quote.isFavourite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit quote")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete quote",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EditQuoteDialog(
    quote: Quote,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        text: String,
        source: String,
        author: String,
        category: String,
        page: String,
        url: String
    ) -> Unit
) {
    var text by remember(quote.id) { mutableStateOf(quote.text) }
    var source by remember(quote.id) { mutableStateOf(quote.source.orEmpty()) }
    var author by remember(quote.id) { mutableStateOf(quote.author.orEmpty()) }
    var category by remember(quote.id) { mutableStateOf(quote.category.orEmpty()) }
    var page by remember(quote.id) { mutableStateOf(quote.page.orEmpty()) }
    var url by remember(quote.id) { mutableStateOf(quote.url.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit quote") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Quote *") },
                    minLines = 3
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Book / Article") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Author") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Category") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = page,
                    onValueChange = { page = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Page") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = { onSave(text, source, author, category, page, url) }
            ) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportContent(
    busy: Boolean,
    message: String?,
    preview: PreviewState?,
    onChoose: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Import highlights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Choose a CSV using the QuoteVault template. Your highlights stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.FileUpload, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Select CSV file", fontWeight = FontWeight.SemiBold)
                Text("Required column: Quote", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onChoose, enabled = !busy) { Text("Browse files") }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        preview?.let { p ->
            Text("Preview: ${p.fileName}", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryChip("Ready", p.rows.size)
                SummaryChip("Duplicates", p.duplicates)
                SummaryChip("Invalid", p.errors.size)
            }
            Text(
                "First ${minOf(5, p.rows.size)} valid, non-duplicate highlights",
                style = MaterialTheme.typography.titleSmall
            )
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(p.rows.take(5)) { quote ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(quote.text, style = MaterialTheme.typography.bodyMedium)
                            val attribution = listOfNotNull(quote.source, quote.author).joinToString(" · ")
                            if (attribution.isNotBlank()) {
                                Text(
                                    attribution,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (p.errors.isNotEmpty()) {
                    item {
                        Text(
                            "Validation issues",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        p.errors.take(5).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) { Text("Cancel") }
                Button(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                    enabled = !busy && p.rows.isNotEmpty()
                ) {
                    Text("Import ${p.rows.size}")
                }
            }
        } ?: Spacer(Modifier.weight(1f))

        Text(
            "CSV columns: Quote, Source, Author, Category, Page, URL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryChip(label: String, count: Int) {
    AssistChip(onClick = {}, label = { Text("$label: $count") })
}
