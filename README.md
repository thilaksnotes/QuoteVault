# QuoteVault – Phase 1

A native Android starter project for importing highlights from a CSV file and storing them locally.

## Current features

- CSV file picker using Android's Storage Access Framework.
- CSV columns: `Quote, Source, Author, Category, Page, URL`.
- UTF-8 / UTF-8 BOM support.
- Quoted commas, escaped double-quotes and multiline quote fields.
- Preview of up to five valid, non-duplicate records.
- Duplicate detection based on normalized quote text (case-insensitive; repeated whitespace collapsed).
- Duplicate records are skipped both within the selected file and against the existing library.
- Invalid rows are reported.
- Local persistence using Room (SQLite).
- Source-based card library: quotes are grouped by book/article title.
- Re-importing highlights for the same source adds to the existing source card.
- All Quotes view with text/source/author/category search.
- Source detail view showing all highlights for that source.
- No login, network access or cloud storage.

## Open in Android Studio

1. Extract the ZIP file.
2. Open Android Studio.
3. Choose **File → Open** and select the extracted `QuoteVault` folder (the folder containing `settings.gradle.kts`).
4. Allow Gradle sync to complete.
5. If prompted, install Android SDK Platform 35 and use JDK 17.
6. Run the `app` configuration on an Android 10+ device or emulator.

The project uses Android Gradle Plugin 8.7.3 and Gradle 8.13. Android Studio may offer to download the Gradle distribution during sync.

## CSV format

The first row must contain these exact headers:

`Quote,Source,Author,Category,Page,URL`

Only `Quote` is mandatory. Other fields may be blank. Extra columns are ignored.

Example:

```csv
Quote,Source,Author,Category,Page,URL
"Life is like being at the dentist. You always think the worst is yet to come.",Book title,Author,Life,12,
"Emotion, which is suffering, ceases to be suffering as soon as we form a clear and precise picture of it.",Book title,Author,Philosophy,25,
```

## Import behaviour

- Empty quote rows are not imported.
- Malformed CSV (such as an unclosed quoted field) is rejected.
- Duplicate quote text is skipped, even if capitalization or whitespace differs.
- Source, author, category, page and URL are retained as provided.
- Imported data remains on the device in `quotevault.db`.

## Current scope / known limitations

This is Phase 1 plus the source-grouped library view. Editing, deleting, favourites, widget support and unlock-triggered rotation are not implemented yet.

The current parser expects the QuoteVault column names. It intentionally does not try to guess Notion property names.
