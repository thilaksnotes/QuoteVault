# QuoteVault

**Your Daily Dose of Inspiration.**

QuoteVault is a native Android app for collecting, organizing, revisiting and displaying reading highlights and favourite quotes. Import highlights from a CSV file, add quotes directly, browse them in your personal library, and keep a rotating quote on your Android home screen with the QuoteVault widget.

> **Repository:** [github.com/thilaksnotes/QuoteVault](https://github.com/thilaksnotes/QuoteVault)

---

## Features

### Quote library

- Organize highlights by source, such as a book, article or other reading material.
- Browse source-grouped cards and open a source to view its highlights.
- View all quotes in one list.
- Search quotes and their available metadata.
- Filter the library by:
  - **All**
  - **Favourites**
  - **Recently added** (the latest 20 quotes)
- Filter by category.
- Edit quote details.
- Favourite or unfavourite a quote.
- Delete quotes from the library.

### Import highlights from CSV

Import reading highlights using Android's file picker.

The CSV header must use these column names:

```csv
Quote,Source,Author,Category,Page,URL
```

Only `Quote` is mandatory. The remaining columns may be blank.

Example:

```csv
Quote,Source,Author,Category,Page,URL
"Great things are done by a series of small things brought together.",Book title,Author,Inspiration,12,
"Knowing yourself is the beginning of all wisdom.",Book title,Author,Philosophy,28,
```

Import support includes:

- UTF-8 and UTF-8 with BOM.
- Quoted fields containing commas.
- Escaped double quotes.
- Multiline quoted fields.
- Import preview and invalid-row reporting.
- Duplicate detection against both the selected file and the existing library.

Duplicate detection is based on normalized quote text: leading/trailing whitespace is trimmed, text is compared without case sensitivity, and repeated whitespace is collapsed. Duplicate quotes are skipped.

### Add quotes directly

Quotes can also be entered or pasted directly in the app.

- Each non-empty line is treated as a separate quote.
- Blank lines are ignored.
- Duplicate quotes are skipped.
- Optional source/book and author values can be applied to all quotes in the submission.

### Home-screen widget

Add the QuoteVault widget from your Android launcher's widget picker.

The widget supports:

- Random quote display.
- Quote text with available source and author information.
- Scrolling for longer quotes.
- Manual refresh.
- Favourite and delete actions that update the local quote database.
- Tapping a quote to open the app's quote library.
- Empty-state guidance when the library has no quotes.

The widget can be resized using the launcher controls. Widget availability and resizing behaviour can vary by launcher.

### Widget appearance and automatic refresh

The **Widget** section in the app provides appearance and refresh settings.

Background choices:

- Warm paper
- White
- Sage
- Rose
- Lavender
- Charcoal
- Midnight blue

Other settings:

- Background opacity: 20%–100%.
- Automatic quote refresh interval:
  - 15 minutes
  - 30 minutes
  - 1 hour
  - 2 hours
  - 3 hours
  - 6 hours
  - 12 hours
- Manual refresh remains available.

**Android background-work limitation:** automatic refresh uses periodic background work. Android enforces a 15-minute minimum for periodic WorkManager jobs, and execution is inexact. Battery optimisation, Doze mode, device idle state and manufacturer-specific background restrictions may delay a refresh. The selected interval is therefore a target cadence, not an exact timer. Quote rotation on every device unlock is not supported.

### Backup and restore

Create a portable backup of the quote library as a single `.qvbackup` file using Android's file picker.

The backup includes:

- Quote IDs and text.
- Source and author.
- Category, page and URL.
- Favourite status.
- Import timestamp.

The backup uses a versioned JSON-based format. It is a logical export of the quote records, not a raw copy of the live SQLite database.

Restore workflow:

1. Select a `.qvbackup` file.
2. QuoteVault validates its format and version.
3. Review the number of quotes in the backup.
4. Confirm the restore operation.

**Important:** restoring replaces the current quote library with the contents of the selected backup. Save a fresh backup of the current library first if you may need it later. The restore is performed transactionally. Widget appearance and refresh preferences are stored separately and are not replaced by a library restore.

Backups are user-initiated and are not uploaded to cloud storage automatically. Keep a copy somewhere safe, such as a computer or your own cloud drive.

---

## Data and privacy

- Quotes are stored locally on the device in a Room database backed by SQLite.
- The database name is `quotevault.db`.
- QuoteVault does not require an account or login.
- The app does not automatically upload your quote library to a server.
- CSV import and backup/restore use Android's document picker.
- Backup files remain wherever you choose to save them.

If you uninstall the app or clear its storage, Android may remove the app's private database. Create and safely store a backup before changing devices, uninstalling the app or clearing app data.

---

## Technical details

| Component | Configuration |
|---|---|
| Application ID / namespace | `com.quotevault.app` |
| Language | Kotlin |
| UI | Jetpack Compose / Material 3 |
| Local persistence | Room 2.6.1 (SQLite) |
| Background refresh | WorkManager |
| Minimum Android SDK | 29 (Android 10) |
| Compile / target SDK | 35 |
| Java / Kotlin JVM target | 17 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose Compiler plugin | 2.0.21 |

The app uses a local-first architecture. No cloud backend is required for the core library, widget, import or backup features.

---

## Build from source

### Requirements

- Android Studio with support for the project's Android Gradle Plugin.
- JDK 17.
- Android SDK Platform 35.
- Android SDK Build Tools compatible with SDK 35.
- Internet access for the first Gradle dependency download.

### Open and run

1. Clone the repository:

   ```bash
   git clone https://github.com/thilaksnotes/QuoteVault.git
   ```

2. Open the `QuoteVault` project directory in Android Studio (the directory containing `settings.gradle.kts`).
3. Allow Gradle sync to complete.
4. Confirm that Gradle is using JDK 17.
5. Connect an Android 10+ device or create an emulator.
6. Run the `app` configuration.

On Windows, the project can also be built from the project root using:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is normally generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

### Build a signed release APK

In Android Studio:

1. Select **Build → Generate Signed Bundle / APK**.
2. Choose **APK** and select the `app` module.
3. Select or create the release keystore.
4. Choose the `release` build variant.
5. Generate the signed APK.

Keep the release keystore and its passwords in a secure backup. **Do not commit keystore files or passwords to GitHub.** Every future update intended to install over an existing release must be signed with the same signing key and use a higher `versionCode`.

---

## Installing updates

- Keep the same application ID: `com.quotevault.app`.
- Increase `versionCode` for every published update.
- Use the same release signing key for all release APKs.
- Do not uninstall the existing app merely to install an update; uninstalling can delete the local database.
- If moving from a debug build to a separately signed release build, Android may reject the update because the signing certificates differ. Back up your quote library before switching signing keys.

---

## Backup recommendation

Before installing a major update, changing phones, or switching from a debug build to a release-signed build:

1. Open QuoteVault.
2. Go to **Backup**.
3. Create a `.qvbackup` file.
4. Copy the file to a safe location outside the app's private storage.
5. Verify that the backup file is available before proceeding.

---

## License

No license has been specified yet. Unless a license is added to this repository, the source code is not automatically granted an open-source reuse license.

---

**QuoteVault — Your Daily Dose of Inspiration.**
