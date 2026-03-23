# Nostr Web Archive Reader — Android App Specification

## Document Purpose

This is a complete implementation specification for an Android app called **"Arkive"** — a reader/browser for web archives published via the Nostr protocol and stored on Blossom media servers. The app functions like an RSS reader, but instead of feeds, it displays web page archives (WACZ files) that users have published to Nostr relays.

Hand this document to Claude Code. It contains everything needed to build the app from scratch.

---

## Table of Contents

1. [Overview & Core Concepts](#1-overview--core-concepts)
2. [Protocol & Data Model Reference](#2-protocol--data-model-reference)
3. [Project Setup & Dependencies](#3-project-setup--dependencies)
4. [Architecture](#4-architecture)
5. [Data Layer](#5-data-layer)
6. [Nostr Client Layer](#6-nostr-client-layer)
7. [Blossom Client Layer](#7-blossom-client-layer)
8. [Replay Engine](#8-replay-engine)
9. [Reader Mode Engine](#9-reader-mode-engine)
10. [UI Screens & Navigation](#10-ui-screens--navigation)
11. [Screen Specifications](#11-screen-specifications)
12. [Settings & Configuration](#12-settings--configuration)
13. [Offline & Caching Strategy](#13-offline--caching-strategy)
14. [Key Management & Signing](#14-key-management--signing)
15. [Error Handling](#15-error-handling)
16. [Testing Strategy](#16-testing-strategy)
17. [Build & Release](#17-build--release)
18. [Future Considerations](#18-future-considerations)

---

## 1. Overview & Core Concepts

### What the app does

Arkive lets you browse, discover, and read web page archives that have been published to the Nostr network. Anyone with the Nostr Web Archiver Chrome extension (or any compatible tool) can archive a web page — the archive (a WACZ file) gets uploaded to a Blossom media server, and a Nostr event is published announcing it. Arkive is the consumption/reading client for those archives.

### Core user stories

1. **Global feed**: "Show me all recently archived web pages across all my relays."
2. **Relay browsing**: "Show me all archives published to relay X."
3. **Profile browsing**: "Show me all archives published by npub X."
4. **URL filtering**: "Show me all archived pages from nytimes.com."
5. **Reading an archive**: "I tapped an archive — show me the original web page as it was captured."
6. **Reader mode**: "Strip the page down to just the article text for comfortable reading."
7. **Offline reading**: "I saved this archive — let me read it without internet."
8. **Saving/bookmarking**: "Save this archive to my local library for later."

### Terminology

| Term | Meaning |
|------|---------|
| WACZ | Web Archive Collection Zipped — a ZIP file containing WARC data + index, supporting random-access via HTTP range requests |
| WARC | Web ARChive — the underlying format storing HTTP request/response pairs |
| Blossom | A protocol for storing blobs (binary data) on media servers, addressed by SHA-256 hash |
| BUD | Blossom Upgrade Document — individual specs within Blossom (like BUD-01, BUD-02, etc.) |
| Kind 30041 | The Nostr event kind used by the Nostr Web Archiver extension to announce web archives |
| Kind 10063 | The Nostr event kind where users publish their list of preferred Blossom servers |
| NIP | Nostr Implementation Possibility — protocol extension specifications |
| npub | Bech32-encoded Nostr public key (human-readable format) |
| nsec | Bech32-encoded Nostr private key |
| Replay | The process of rendering an archived web page from a WACZ file |

---

## 2. Protocol & Data Model Reference

### 2.1 Nostr Event: Web Archive Announcement (Kind 30041)

This is the primary event the app queries for. It is a **parameterized replaceable event** (kind in range 30000–39999), meaning each event is uniquely identified by `(author_pubkey, kind, d_tag)`.

Example event structure:

```json
{
  "id": "<event-id-sha256>",
  "pubkey": "<author-hex-pubkey>",
  "created_at": 1700000000,
  "kind": 30041,
  "tags": [
    ["d", "<unique-identifier-for-this-archive>"],
    ["r", "https://www.nytimes.com/2025/03/15/technology/ai-agents.html"],
    ["title", "AI Agents Are Changing How We Work"],
    ["x", "a1b2c3d4e5f6...64-char-hex-sha256-of-wacz"],
    ["url", "https://blossom.example.com/a1b2c3d4e5f6...64-char-hex.wacz"],
    ["m", "application/wacz"],
    ["size", "1234567"],
    ["alt", "Web archive of https://www.nytimes.com/..."],
    ["t", "technology"],
    ["t", "ai"],
    ["imeta", "url https://blossom.example.com/abc123...jpg", "m image/jpeg", "x abc123..."],
    ["ots", "<opentimestamps-proof-base64>"],
    ["archived_at", "1700000000"],
    ["mode", "personal"],
    ["client", "nostr-web-archiver"]
  ],
  "content": "Optional description or notes about this archive",
  "sig": "<schnorr-signature>"
}
```

**Required tags the app MUST parse:**
- `d` — unique identifier (used for addressability)
- `r` — the original URL that was archived (THE key field for display and filtering)
- `x` — SHA-256 hash of the WACZ blob (used for Blossom retrieval and verification)

**Optional tags the app SHOULD parse:**
- `url` — direct URL to the WACZ on a Blossom server
- `title` — page title at time of archiving
- `m` — MIME type (expect `application/wacz` or `application/warc`)
- `size` — file size in bytes
- `t` — topic/hashtag tags
- `ots` — OpenTimestamps proof (display verification status)
- `archived_at` — unix timestamp of when the archive was created
- `mode` — archive mode (forensic/verified/personal)
- `imeta` — media metadata per NIP-94
- `alt` — human-readable description for clients that don't understand this kind
- `client` — which tool created the archive

**IMPORTANT PARSING NOTES:**
- Not all events will have all tags. The app must gracefully handle missing tags.
- The `r` tag is the primary URL. Some events may have multiple `r` tags (e.g., if the archive captured multiple pages).
- If `title` tag is missing, extract the domain from the `r` tag URL as a display fallback.
- If `url` tag is missing, the WACZ must be located via the `x` hash + Blossom server discovery (see section 7).

### 2.2 Nostr Event: Blossom Server List (Kind 10063)

Users publish their preferred Blossom servers as a **replaceable event** (kind in 10000–19999 range). When you need to fetch a WACZ and the direct URL fails, look up the archive author's kind 10063 event and try their servers.

```json
{
  "kind": 10063,
  "tags": [
    ["server", "https://blossom.primal.net/"],
    ["server", "https://blossom.nostr.build/"],
    ["server", "https://cdn.satellite.earth/"]
  ],
  "content": ""
}
```

The app should query for `{"kinds": [10063], "authors": ["<pubkey>"]}` when needed.

### 2.3 Nostr Event: User Metadata (Kind 0)

Standard user profile metadata. Fetch this to display author names and avatars.

```json
{
  "kind": 0,
  "content": "{\"name\":\"alice\",\"display_name\":\"Alice\",\"about\":\"Archivist\",\"picture\":\"https://example.com/alice.jpg\",\"nip05\":\"alice@example.com\"}"
}
```

Parse `content` as JSON. Use `display_name` (fallback to `name`) and `picture` for the UI.

### 2.4 Blossom Protocol (HTTP Endpoints)

Blossom servers are plain HTTP. The app only needs these endpoints:

**GET `/<sha256>`** (BUD-01) — Retrieve a blob by its hash.
- Optionally append a file extension: `/<sha256>.wacz`
- Returns the raw blob data
- Supports standard HTTP range requests (critical for WACZ streaming)
- Response headers include `content-type`, `content-length`

**HEAD `/<sha256>`** (BUD-01) — Check if a blob exists without downloading it.
- Returns 200 if exists, 404 if not
- Use this to find which Blossom server has the file

**GET `/list/<pubkey>`** (BUD-02) — List blobs uploaded by a specific pubkey.
- Returns JSON array of blob descriptors
- Optional, and marked "unrecommended" in the spec — don't rely on this
- May require auth on some servers

**Blob descriptor format** (returned by various endpoints):

```json
{
  "url": "https://blossom.example.com/a1b2c3...hex",
  "sha256": "a1b2c3...hex",
  "size": 1234567,
  "type": "application/wacz",
  "uploaded": 1700000000
}
```

### 2.5 WACZ File Format

A WACZ file is a ZIP containing:

```
archive.wacz (ZIP)
├── datapackage.json          # Collection metadata (title, description, etc.)
├── pages/pages.jsonl         # List of archived pages (URL, title, timestamp)
├── indexes/index.cdx         # CDX index for URL lookups
├── archive/data.warc.gz      # The actual archived HTTP request/response pairs
└── datapackage-digest.json   # SHA-256 hashes for integrity verification
```

**Key file: `pages/pages.jsonl`** — Each line is a JSON object:
```json
{"url": "https://example.com/article", "title": "Article Title", "ts": "20250315120000"}
```

**Key file: `indexes/index.cdx`** — Tab-separated index mapping URLs to offsets in the WARC:
```
com,example)/article 20250315120000 {"url":"https://example.com/article","mime":"text/html","status":"200","offset":"1234","length":"5678"}
```

The app does NOT need to parse WACZ internals for basic functionality — ReplayWeb.page handles this. But for reader mode, the app may want to extract the main HTML from the WARC.

---

## 3. Project Setup & Dependencies

### 3.1 Project Configuration

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0) — needed for WebView service worker support
- **Target SDK**: 35 (latest stable)
- **Build system**: Gradle with Kotlin DSL
- **Package name**: `com.arkive.reader`

### 3.2 Key Dependencies

```kotlin
// build.gradle.kts (app module)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") // For Room annotation processing
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Room (local database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Nostr
    // Use nostr-sdk or implement minimal protocol directly
    // Option A: rust-nostr Kotlin bindings
    // implementation("io.github.nickevin:nostr-sdk-android:0.33.0")
    // Option B: Roll our own minimal implementation (RECOMMENDED for control)
    // We'll implement WebSocket + event parsing directly

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Readability (for reader mode)
    implementation("net.dankito.readability4j:readability4j:1.0.8")

    // WebView
    implementation("androidx.webkit:webkit:1.12.1")

    // DataStore (for preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // NanoHTTPD (embedded HTTP server for WACZ replay)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Hashing
    // Use java.security.MessageDigest for SHA-256 (built-in)

    // Nostr key encoding (bech32)
    // Implement bech32/bech32m encoding/decoding (small utility, include in project)
}
```

### 3.3 Project Structure

```
app/src/main/java/com/arkive/reader/
├── ArkiveApp.kt                     # Application class
├── MainActivity.kt                   # Single activity, hosts Compose nav
├── di/                               # Manual dependency injection
│   └── AppContainer.kt
├── data/
│   ├── local/
│   │   ├── ArkiveDatabase.kt        # Room database
│   │   ├── dao/
│   │   │   ├── ArchiveEventDao.kt
│   │   │   ├── RelayDao.kt
│   │   │   ├── ProfileDao.kt
│   │   │   └── SavedArchiveDao.kt
│   │   └── entity/
│   │       ├── ArchiveEventEntity.kt
│   │       ├── RelayEntity.kt
│   │       ├── ProfileEntity.kt
│   │       └── SavedArchiveEntity.kt
│   ├── remote/
│   │   ├── nostr/
│   │   │   ├── NostrClient.kt       # WebSocket relay connections
│   │   │   ├── NostrEvent.kt        # Event data class
│   │   │   ├── NostrFilter.kt       # Subscription filters
│   │   │   ├── NostrRelay.kt        # Single relay connection
│   │   │   ├── NostrSigner.kt       # Event signing
│   │   │   └── Bech32.kt            # npub/nsec encoding
│   │   └── blossom/
│   │       ├── BlossomClient.kt     # HTTP client for Blossom servers
│   │       └── BlobDescriptor.kt
│   ├── repository/
│   │   ├── ArchiveRepository.kt     # Main data orchestration
│   │   ├── ProfileRepository.kt
│   │   └── RelayRepository.kt
│   └── preferences/
│       └── UserPreferences.kt       # DataStore preferences
├── domain/
│   ├── model/
│   │   ├── Archive.kt               # Domain model for an archive
│   │   ├── ArchiveFilter.kt         # Filter criteria
│   │   ├── Profile.kt
│   │   └── Relay.kt
│   └── usecase/
│       ├── GetArchiveFeedUseCase.kt
│       ├── GetArchivesByRelayUseCase.kt
│       ├── GetArchivesByAuthorUseCase.kt
│       ├── FilterArchivesByUrlUseCase.kt
│       ├── FetchWaczUseCase.kt
│       ├── SaveArchiveOfflineUseCase.kt
│       └── ResolveBlossomUrlUseCase.kt
├── replay/
│   ├── ReplayServer.kt              # NanoHTTPD server for WACZ replay
│   ├── WaczFileHandler.kt           # Serves WACZ content via range requests
│   └── ReaderModeExtractor.kt       # Readability extraction
├── ui/
│   ├── navigation/
│   │   └── ArkiveNavGraph.kt
│   ├── theme/
│   │   ├── Theme.kt
│   │   ├── Color.kt
│   │   └── Type.kt
│   ├── components/
│   │   ├── ArchiveCard.kt           # List item for an archive
│   │   ├── ArchiveListContent.kt    # Shared list/grid layout
│   │   ├── RelayChip.kt
│   │   ├── ProfileHeader.kt
│   │   ├── UrlFilterBar.kt
│   │   ├── LoadingIndicator.kt
│   │   ├── ErrorState.kt
│   │   └── EmptyState.kt
│   ├── screen/
│   │   ├── feed/
│   │   │   ├── FeedScreen.kt
│   │   │   └── FeedViewModel.kt
│   │   ├── relay/
│   │   │   ├── RelayBrowserScreen.kt
│   │   │   └── RelayBrowserViewModel.kt
│   │   ├── profile/
│   │   │   ├── ProfileScreen.kt
│   │   │   └── ProfileViewModel.kt
│   │   ├── viewer/
│   │   │   ├── ArchiveViewerScreen.kt
│   │   │   └── ArchiveViewerViewModel.kt
│   │   ├── reader/
│   │   │   ├── ReaderModeScreen.kt
│   │   │   └── ReaderModeViewModel.kt
│   │   ├── saved/
│   │   │   ├── SavedScreen.kt
│   │   │   └── SavedViewModel.kt
│   │   └── settings/
│   │       ├── SettingsScreen.kt
│   │       └── SettingsViewModel.kt
│   └── util/
│       ├── DateFormatting.kt
│       └── UrlParsing.kt
└── assets/
    └── replay/                       # ReplayWeb.page static assets
        ├── ui.js
        ├── sw.js
        └── index.html               # Minimal HTML shell for WebView
```

### 3.4 Asset: ReplayWeb.page Files

Download and bundle these files into `app/src/main/assets/replay/`:

1. `ui.js` — from `https://cdn.jsdelivr.net/npm/replaywebpage@2.4.3/ui.js`
2. `sw.js` — from `https://cdn.jsdelivr.net/npm/replaywebpage@2.4.3/sw.js`

Create `index.html`:

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Arkive Viewer</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body { width: 100%; height: 100%; overflow: hidden; }
    replay-web-page {
      display: block;
      width: 100vw;
      height: 100vh;
    }
  </style>
  <script src="/replay/ui.js"></script>
</head>
<body>
  <replay-web-page
    id="replay-viewer"
    replayBase="/replay/"
    embed="replayonly"
  ></replay-web-page>
  <script>
    // Bridge: Android calls this to set the WACZ source and URL
    function loadArchive(waczUrl, pageUrl) {
      const viewer = document.getElementById('replay-viewer');
      viewer.setAttribute('source', waczUrl);
      if (pageUrl) {
        viewer.setAttribute('url', pageUrl);
      }
    }

    // Bridge: Android calls this to get the current page HTML (for reader mode)
    function getCurrentPageHtml() {
      try {
        const iframe = document.querySelector('replay-web-page')
          ?.shadowRoot?.querySelector('iframe');
        if (iframe?.contentDocument) {
          return iframe.contentDocument.documentElement.outerHTML;
        }
      } catch(e) {
        return null;
      }
      return null;
    }
  </script>
</body>
</html>
```

And create `replay/sw.js` (the local service worker loader):

```javascript
importScripts("/replay/sw-core.js");
```

Where `sw-core.js` is the actual ReplayWeb.page service worker file renamed from the CDN download.

**IMPORTANT:** The service worker approach may not work in Android WebView. The primary approach is to use NanoHTTPD (see section 8). The assets above are bundled as a fallback and for possible future WebView improvements.

---

## 4. Architecture

### 4.1 Overall Pattern

**MVVM + Repository + Use Cases**

```
UI (Compose Screens)
    ↕ (StateFlow)
ViewModels
    ↕ (suspend functions)
Use Cases
    ↕
Repositories
    ↕             ↕
Local DB      Remote (Nostr relays + Blossom servers)
```

### 4.2 Dependency Injection

Use manual DI via an `AppContainer` class (no Hilt/Dagger — keeps things simple for Claude Code).

```kotlin
class AppContainer(private val context: Context) {
    // Database
    val database: ArkiveDatabase by lazy {
        Room.databaseBuilder(context, ArkiveDatabase::class.java, "arkive.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    // Networking
    val okHttpClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    // Nostr
    val nostrClient: NostrClient by lazy { NostrClient() }

    // Blossom
    val blossomClient: BlossomClient by lazy { BlossomClient(okHttpClient) }

    // Preferences
    val userPreferences: UserPreferences by lazy { UserPreferences(context) }

    // Repositories
    val archiveRepository: ArchiveRepository by lazy {
        ArchiveRepository(
            nostrClient = nostrClient,
            blossomClient = blossomClient,
            archiveEventDao = database.archiveEventDao(),
            profileDao = database.profileDao(),
            userPreferences = userPreferences
        )
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(nostrClient, database.profileDao())
    }

    val relayRepository: RelayRepository by lazy {
        RelayRepository(database.relayDao(), userPreferences)
    }

    // Replay server
    val replayServer: ReplayServer by lazy {
        ReplayServer(context, blossomClient)
    }
}
```

### 4.3 Lifecycle Notes

- `NostrClient` manages a pool of WebSocket connections. It should be started when the app is in the foreground and stopped (connections closed) when backgrounded.
- `ReplayServer` (NanoHTTPD) starts when the user opens an archive viewer and stops when they leave.
- WACZ file downloads for offline use run in a `WorkManager` job so they survive process death.

---

## 5. Data Layer

### 5.1 Room Database

```kotlin
@Database(
    entities = [
        ArchiveEventEntity::class,
        RelayEntity::class,
        ProfileEntity::class,
        SavedArchiveEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ArkiveDatabase : RoomDatabase() {
    abstract fun archiveEventDao(): ArchiveEventDao
    abstract fun relayDao(): RelayDao
    abstract fun profileDao(): ProfileDao
    abstract fun savedArchiveDao(): SavedArchiveDao
}
```

### 5.2 Entities

```kotlin
@Entity(tableName = "archive_events")
data class ArchiveEventEntity(
    @PrimaryKey val eventId: String,           // Nostr event ID (hex)
    val authorPubkey: String,                   // Author hex pubkey
    val createdAt: Long,                        // Unix timestamp
    val dTag: String,                           // d-tag value
    val archivedUrl: String,                    // Primary URL from r-tag
    val allUrls: String,                        // JSON array of all r-tag URLs
    val title: String?,                         // From title tag or null
    val waczHash: String?,                      // SHA-256 from x-tag
    val waczUrl: String?,                       // Direct URL from url-tag
    val waczSize: Long?,                        // File size in bytes
    val mimeType: String?,                      // From m-tag
    val description: String?,                   // From event content field
    val archiveMode: String?,                   // forensic/verified/personal
    val hasOts: Boolean,                        // Whether ots tag is present
    val tags: String,                           // Full tags JSON (for anything we didn't parse)
    val sourceRelay: String,                    // Which relay we got this from
    val fetchedAt: Long                         // When we cached this locally
)

@Entity(tableName = "relays")
data class RelayEntity(
    @PrimaryKey val url: String,               // wss://relay.example.com
    val name: String?,                          // From NIP-11 info
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val lastConnected: Long? = null,
    val archiveEventCount: Int = 0             // Cached count of archives seen
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val pubkey: String,            // Hex pubkey
    val name: String?,
    val displayName: String?,
    val about: String?,
    val pictureUrl: String?,
    val nip05: String?,
    val blossomServers: String?,               // JSON array from kind 10063
    val lastUpdated: Long
)

@Entity(tableName = "saved_archives")
data class SavedArchiveEntity(
    @PrimaryKey val eventId: String,           // References archive_events
    val localWaczPath: String?,                // Path to downloaded WACZ file
    val downloadedAt: Long?,
    val downloadStatus: String,                // pending / downloading / complete / failed
    val savedAt: Long                          // When user bookmarked it
)
```

### 5.3 DAOs

```kotlin
@Dao
interface ArchiveEventDao {
    @Query("SELECT * FROM archive_events ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE sourceRelay = :relayUrl ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByRelay(relayUrl: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE authorPubkey = :pubkey ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByAuthor(pubkey: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE archivedUrl LIKE '%' || :domain || '%' ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByUrlDomain(domain: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE archivedUrl LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): ArchiveEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ArchiveEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ArchiveEventEntity)

    @Query("DELETE FROM archive_events WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM archive_events")
    suspend fun count(): Int

    @Query("SELECT DISTINCT authorPubkey FROM archive_events")
    suspend fun getDistinctAuthors(): List<String>

    @Query("SELECT DISTINCT sourceRelay FROM archive_events")
    suspend fun getDistinctRelays(): List<String>
}

@Dao
interface RelayDao {
    @Query("SELECT * FROM relays WHERE isEnabled = 1 ORDER BY isDefault DESC, url ASC")
    suspend fun getEnabled(): List<RelayEntity>

    @Query("SELECT * FROM relays ORDER BY isDefault DESC, url ASC")
    suspend fun getAll(): List<RelayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relay: RelayEntity)

    @Delete
    suspend fun delete(relay: RelayEntity)

    @Query("UPDATE relays SET archiveEventCount = :count WHERE url = :url")
    suspend fun updateCount(url: String, count: Int)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE pubkey = :pubkey")
    suspend fun getByPubkey(pubkey: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE pubkey IN (:pubkeys)")
    suspend fun getByPubkeys(pubkeys: List<String>): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ProfileEntity>)
}

@Dao
interface SavedArchiveDao {
    @Query("SELECT * FROM saved_archives ORDER BY savedAt DESC")
    suspend fun getAll(): List<SavedArchiveEntity>

    @Query("SELECT * FROM saved_archives WHERE eventId = :eventId")
    suspend fun getByEventId(eventId: String): SavedArchiveEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(saved: SavedArchiveEntity)

    @Delete
    suspend fun delete(saved: SavedArchiveEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_archives WHERE eventId = :eventId)")
    suspend fun isSaved(eventId: String): Boolean
}
```

### 5.4 Preferences (DataStore)

```kotlin
class UserPreferences(context: Context) {
    private val dataStore = context.dataStore // defined via delegate

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://relay.nostr.band"
        )
        val DEFAULT_BLOSSOM_SERVERS = listOf(
            "https://blossom.primal.net",
            "https://blossom.nostr.build",
            "https://cdn.satellite.earth"
        )

        private val NSEC_KEY = stringPreferencesKey("nsec")
        private val THEME_KEY = stringPreferencesKey("theme") // light / dark / system
        private val READER_FONT_SIZE_KEY = intPreferencesKey("reader_font_size")
        private val READER_FONT_KEY = stringPreferencesKey("reader_font")
        private val WACZ_CACHE_LIMIT_MB_KEY = intPreferencesKey("wacz_cache_limit_mb")
        private val AUTO_FETCH_PROFILES_KEY = booleanPreferencesKey("auto_fetch_profiles")
    }

    // Expose as Flows for Compose observation
    val theme: Flow<String>
    val readerFontSize: Flow<Int> // default 18
    val readerFont: Flow<String> // default "serif"
    val waczCacheLimitMb: Flow<Int> // default 500

    // Suspend functions for writes
    suspend fun setTheme(theme: String) { ... }
    suspend fun setReaderFontSize(size: Int) { ... }
    // etc.
}
```

---

## 6. Nostr Client Layer

### 6.1 NostrClient

This is the core networking component. It manages WebSocket connections to multiple relays and handles subscription multiplexing.

```kotlin
class NostrClient {
    private val relays = ConcurrentHashMap<String, NostrRelay>()
    private val _events = MutableSharedFlow<Pair<String, NostrEvent>>(
        extraBufferCapacity = 1000
    )
    val events: SharedFlow<Pair<String, NostrEvent>> = _events // Pair<relayUrl, event>

    // Connect to a set of relays
    suspend fun connect(relayUrls: List<String>) {
        relayUrls.forEach { url ->
            if (!relays.containsKey(url)) {
                val relay = NostrRelay(url) { relayUrl, event ->
                    _events.emit(Pair(relayUrl, event))
                }
                relays[url] = relay
                relay.connect()
            }
        }
    }

    // Subscribe to events matching a filter across all connected relays
    fun subscribe(subscriptionId: String, filters: List<NostrFilter>) {
        relays.values.forEach { relay ->
            relay.subscribe(subscriptionId, filters)
        }
    }

    // Subscribe to a specific relay only
    fun subscribeToRelay(relayUrl: String, subscriptionId: String, filters: List<NostrFilter>) {
        relays[relayUrl]?.subscribe(subscriptionId, filters)
    }

    // Close a subscription
    fun unsubscribe(subscriptionId: String) {
        relays.values.forEach { it.unsubscribe(subscriptionId) }
    }

    // Disconnect everything
    fun disconnectAll() {
        relays.values.forEach { it.disconnect() }
        relays.clear()
    }

    // Disconnect a single relay
    fun disconnectRelay(url: String) {
        relays.remove(url)?.disconnect()
    }
}
```

### 6.2 NostrRelay

Manages a single WebSocket connection.

```kotlin
class NostrRelay(
    private val url: String,
    private val onEvent: suspend (String, NostrEvent) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
        .build()
    private var isConnected = false
    private val pendingSubscriptions = mutableListOf<Pair<String, List<NostrFilter>>>()

    fun connect() {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                // Re-send any pending subscriptions
                pendingSubscriptions.forEach { (subId, filters) ->
                    sendSubscription(subId, filters)
                }
                pendingSubscriptions.clear()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // Parse Nostr message
                // Messages are JSON arrays: ["EVENT", subId, event] or ["EOSE", subId] or ["NOTICE", msg]
                CoroutineScope(Dispatchers.IO).launch {
                    handleMessage(text)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                // Implement reconnection with exponential backoff
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
            }
        })
    }

    fun subscribe(subscriptionId: String, filters: List<NostrFilter>) {
        if (isConnected) {
            sendSubscription(subscriptionId, filters)
        } else {
            pendingSubscriptions.add(subscriptionId to filters)
        }
    }

    private fun sendSubscription(subscriptionId: String, filters: List<NostrFilter>) {
        // ["REQ", <subscriptionId>, <filter1>, <filter2>, ...]
        val msg = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            filters.forEach { filter ->
                add(filter.toJsonObject())
            }
        }
        webSocket?.send(msg.toString())
    }

    fun unsubscribe(subscriptionId: String) {
        val msg = buildJsonArray {
            add("CLOSE")
            add(subscriptionId)
        }
        webSocket?.send(msg.toString())
    }

    private suspend fun handleMessage(text: String) {
        val json = Json.parseToJsonElement(text).jsonArray
        when (json[0].jsonPrimitive.content) {
            "EVENT" -> {
                val event = NostrEvent.fromJson(json[2].jsonObject)
                if (event.verify()) { // Verify signature
                    onEvent(url, event)
                }
            }
            "EOSE" -> {
                // End of stored events — subscription is now live
                // Could emit a signal for UI to stop showing "loading"
            }
            "NOTICE" -> {
                // Relay notice — log it
            }
            "OK" -> {
                // Event publish confirmation
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        isConnected = false
    }

    private fun scheduleReconnect() {
        // Exponential backoff: 1s, 2s, 4s, 8s, max 60s
        // Use CoroutineScope + delay
    }
}
```

### 6.3 NostrEvent

```kotlin
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    // Helper to get the first value of a specific tag
    fun getTagValue(tagName: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == tagName }?.get(1)

    // Helper to get all values of a specific tag type
    fun getTagValues(tagName: String): List<String> =
        tags.filter { it.size >= 2 && it[0] == tagName }.map { it[1] }

    // Verify the event signature
    fun verify(): Boolean {
        // 1. Compute event ID: SHA-256 of [0, pubkey, created_at, kind, tags, content]
        // 2. Verify schnorr signature over the ID using the pubkey
        // Use secp256k1 schnorr verification
        // For simplicity, can use a library or implement basic verification
        return true // TODO: Implement proper verification
    }

    // Convert to ArchiveEventEntity for Room storage
    fun toArchiveEntity(sourceRelay: String): ArchiveEventEntity? {
        if (kind != 30041) return null
        val archivedUrl = getTagValue("r") ?: return null

        return ArchiveEventEntity(
            eventId = id,
            authorPubkey = pubkey,
            createdAt = createdAt,
            dTag = getTagValue("d") ?: id,
            archivedUrl = archivedUrl,
            allUrls = Json.encodeToString(getTagValues("r")),
            title = getTagValue("title"),
            waczHash = getTagValue("x"),
            waczUrl = getTagValue("url"),
            waczSize = getTagValue("size")?.toLongOrNull(),
            mimeType = getTagValue("m"),
            description = content.takeIf { it.isNotBlank() },
            archiveMode = getTagValue("mode"),
            hasOts = tags.any { it.firstOrNull() == "ots" },
            tags = Json.encodeToString(tags),
            sourceRelay = sourceRelay,
            fetchedAt = System.currentTimeMillis() / 1000
        )
    }

    companion object {
        fun fromJson(json: JsonObject): NostrEvent {
            return Json.decodeFromJsonElement(json)
        }
    }
}
```

### 6.4 NostrFilter

```kotlin
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>>? = null, // e.g., "#r" -> ["https://..."]
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        ids?.let { put("ids", JsonArray(it.map { JsonPrimitive(it) })) }
        authors?.let { put("authors", JsonArray(it.map { JsonPrimitive(it) })) }
        kinds?.let { put("kinds", JsonArray(it.map { JsonPrimitive(it) })) }
        tags?.forEach { (key, values) ->
            put("#${key.removePrefix("#")}", JsonArray(values.map { JsonPrimitive(it) }))
        }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
    }
}
```

### 6.5 Bech32 Utility

Implement bech32 encoding/decoding for npub/nsec. This is a well-documented algorithm — port from any reference implementation. Key functions:

```kotlin
object Bech32 {
    fun npubToHex(npub: String): String { ... }
    fun hexToNpub(hex: String): String { ... }
    fun nsecToHex(nsec: String): String { ... }
    fun hexToNsec(hex: String): String { ... }
    fun isValidNpub(input: String): Boolean { ... }
    fun isValidNsec(input: String): Boolean { ... }
}
```

---

## 7. Blossom Client Layer

```kotlin
class BlossomClient(private val httpClient: OkHttpClient) {

    // Check if a blob exists on a server
    suspend fun hasBlob(serverUrl: String, sha256: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/$sha256"
        val request = Request.Builder().url(url).head().build()
        try {
            val response = httpClient.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            false
        }
    }

    // Get blob info (without downloading)
    suspend fun getBlobInfo(serverUrl: String, sha256: String): BlobInfo? = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/$sha256"
        val request = Request.Builder().url(url).head().build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.code == 200) {
                BlobInfo(
                    size = response.header("content-length")?.toLongOrNull() ?: 0,
                    contentType = response.header("content-type") ?: "application/octet-stream",
                    supportsRangeRequests = response.header("accept-ranges") == "bytes"
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Download a blob to a local file
    suspend fun downloadBlob(
        serverUrl: String,
        sha256: String,
        destinationFile: File,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/$sha256.wacz"
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
            val totalBytes = body.contentLength()
            var bytesDownloaded = 0L

            destinationFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        onProgress?.invoke(bytesDownloaded, totalBytes)
                    }
                }
            }

            // Verify SHA-256
            val actualHash = sha256Hash(destinationFile)
            if (actualHash != sha256) {
                destinationFile.delete()
                return@withContext Result.failure(Exception("SHA-256 mismatch: expected $sha256, got $actualHash"))
            }

            Result.success(destinationFile)
        } catch (e: Exception) {
            destinationFile.delete()
            Result.failure(e)
        }
    }

    // Resolve: find a working Blossom URL for a given hash
    // Tries direct URL first, then falls back to the author's server list
    suspend fun resolveWaczUrl(
        directUrl: String?,
        sha256: String,
        authorBlossomServers: List<String>,
        fallbackServers: List<String>
    ): String? {
        // Try direct URL first
        if (directUrl != null) {
            val request = Request.Builder().url(directUrl).head().build()
            try {
                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }
                if (response.code == 200) return directUrl
            } catch (_: Exception) {}
        }

        // Try author's Blossom servers
        val allServers = (authorBlossomServers + fallbackServers).distinct()
        for (server in allServers) {
            if (hasBlob(server, sha256)) {
                return "${server.trimEnd('/')}/$sha256.wacz"
            }
        }

        return null
    }

    private fun sha256Hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class BlobInfo(
    val size: Long,
    val contentType: String,
    val supportsRangeRequests: Boolean
)
```

---

## 8. Replay Engine

### 8.1 ReplayServer (NanoHTTPD-based)

This is the critical component. It runs a local HTTP server that serves:
1. The ReplayWeb.page static assets (ui.js, sw.js, index.html)
2. WACZ files — either proxied from a remote Blossom server (with range request support) or served from local cache

```kotlin
class ReplayServer(
    private val context: Context,
    private val blossomClient: BlossomClient,
    port: Int = 0 // 0 = auto-assign available port
) : NanoHTTPD("127.0.0.1", port) {

    private var currentWaczUrl: String? = null
    private var currentLocalWaczFile: File? = null

    val serverUrl: String get() = "http://127.0.0.1:$listeningPort"

    fun setWaczSource(remoteUrl: String? = null, localFile: File? = null) {
        currentWaczUrl = remoteUrl
        currentLocalWaczFile = localFile
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            // Serve ReplayWeb.page assets from bundled app assets
            uri.startsWith("/replay/") -> serveAsset(uri)

            // Serve the main viewer page
            uri == "/" || uri == "/index.html" -> serveAsset("/replay/index.html")

            // Serve the WACZ file (the archive itself)
            uri == "/archive.wacz" || uri.endsWith(".wacz") -> serveWacz(session)

            // Service worker registration path
            uri == "/replay/sw.js" -> serveAsset("/replay/sw.js")

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun serveAsset(assetPath: String): Response {
        val path = assetPath.removePrefix("/")
        return try {
            val inputStream = context.assets.open(path)
            val mimeType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                else -> "application/octet-stream"
            }
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $path")
        }
    }

    private fun serveWacz(session: IHTTPSession): Response {
        // Serve from local file if available
        currentLocalWaczFile?.let { file ->
            if (file.exists()) {
                return serveLocalFile(file, session)
            }
        }

        // Otherwise proxy from remote Blossom URL
        currentWaczUrl?.let { url ->
            return proxyRemoteWacz(url, session)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No WACZ source configured")
    }

    private fun serveLocalFile(file: File, session: IHTTPSession): Response {
        val fileLength = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null) {
            // Handle HTTP Range request (critical for WACZ random access)
            val range = parseRange(rangeHeader, fileLength)
            if (range != null) {
                val (start, end) = range
                val contentLength = end - start + 1
                val inputStream = FileInputStream(file).apply { skip(start) }
                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    "application/wacz",
                    inputStream,
                    contentLength
                )
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())
                return response
            }
        }

        // Full file response
        val inputStream = FileInputStream(file)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/wacz",
            inputStream,
            fileLength
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", fileLength.toString())
        return response
    }

    private fun proxyRemoteWacz(url: String, session: IHTTPSession): Response {
        // Proxy the request to the remote Blossom server, preserving range headers
        val requestBuilder = Request.Builder().url(url)
        session.headers["range"]?.let { range ->
            requestBuilder.header("Range", range)
        }

        return try {
            val remoteResponse = OkHttpClient().newCall(requestBuilder.build()).execute()
            val body = remoteResponse.body ?: return newFixedLengthResponse(
                Response.Status.BAD_GATEWAY, "text/plain", "Empty response from Blossom server"
            )

            val status = if (remoteResponse.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = newFixedLengthResponse(
                status,
                remoteResponse.header("content-type") ?: "application/wacz",
                body.byteStream(),
                body.contentLength()
            )

            // Forward relevant headers
            remoteResponse.header("content-range")?.let { response.addHeader("Content-Range", it) }
            remoteResponse.header("accept-ranges")?.let { response.addHeader("Accept-Ranges", it) }
            response.addHeader("Content-Length", body.contentLength().toString())

            // CORS headers (needed for service worker in WebView)
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Headers", "Range")
            response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")

            response
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_GATEWAY, "text/plain", "Failed to fetch from Blossom: ${e.message}")
        }
    }

    private fun parseRange(rangeHeader: String, fileLength: Long): Pair<Long, Long>? {
        // Parse "bytes=START-END" or "bytes=START-" or "bytes=-SUFFIX"
        val match = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader) ?: return null
        val startStr = match.groupValues[1]
        val endStr = match.groupValues[2]

        return when {
            startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                Pair(startStr.toLong(), endStr.toLong())
            }
            startStr.isNotEmpty() -> {
                Pair(startStr.toLong(), fileLength - 1)
            }
            endStr.isNotEmpty() -> {
                val suffix = endStr.toLong()
                Pair(fileLength - suffix, fileLength - 1)
            }
            else -> null
        }
    }
}
```

### 8.2 WebView Configuration for Archive Viewer

```kotlin
@Composable
fun ArchiveWebView(
    serverUrl: String,
    waczSourceUrl: String,
    archivedPageUrl: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    // Critical: Enable service worker support
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        ServiceWorkerController.getInstance().apply {
                            serviceWorkerWebSettings.allowContentAccess = true
                            serviceWorkerWebSettings.allowFileAccess = true
                        }
                    }
                    // Allow mixed content (local server is HTTP)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // Disable zoom for cleaner UI
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    // User agent
                    userAgentString = "$userAgentString Arkive/1.0"
                }
                webViewClient = WebViewClient()

                // Load the viewer page, which will then load the WACZ
                // Pass the WACZ URL and page URL as query params
                val viewerUrl = "$serverUrl/?source=${
                    URLEncoder.encode("$serverUrl/archive.wacz", "UTF-8")
                }&url=${
                    URLEncoder.encode(archivedPageUrl, "UTF-8")
                }"
                loadUrl(viewerUrl)
            }
        }
    )
}
```

**ALTERNATIVE APPROACH (simpler, recommended to try first):**

Instead of relying on the service worker flow, have the `index.html` directly call `loadArchive()` from JavaScript with the correct URLs passed from the Kotlin side via `evaluateJavascript`:

```kotlin
webView.loadUrl("$serverUrl/index.html")
webView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        view?.evaluateJavascript(
            "loadArchive('$serverUrl/archive.wacz', '$archivedPageUrl')",
            null
        )
    }
}
```

---

## 9. Reader Mode Engine

### 9.1 HTML Extraction from WACZ

For reader mode, we need to extract the main article content from the WACZ. Two approaches:

**Approach A: Extract from the WACZ ZIP directly (preferred for offline)**

```kotlin
class ReaderModeExtractor {

    // Extract and clean article content from a WACZ file
    suspend fun extractArticle(waczFile: File, targetUrl: String): ReaderArticle? =
        withContext(Dispatchers.IO) {
            try {
                ZipFile(waczFile).use { zip ->
                    // 1. Find the main HTML in the WARC data
                    val html = extractHtmlFromWacz(zip, targetUrl)
                    if (html != null) {
                        // 2. Run readability extraction
                        parseArticle(html, targetUrl)
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }

    private fun extractHtmlFromWacz(zip: ZipFile, targetUrl: String): String? {
        // Look for the WARC file inside the WACZ
        val warcEntry = zip.entries().asSequence()
            .firstOrNull { it.name.endsWith(".warc") || it.name.endsWith(".warc.gz") }
            ?: return null

        // Parse WARC records looking for the HTML response for our target URL
        val inputStream = if (warcEntry.name.endsWith(".gz")) {
            GZIPInputStream(zip.getInputStream(warcEntry))
        } else {
            zip.getInputStream(warcEntry)
        }

        // Simple WARC parser: read records, find the response for targetUrl
        // WARC records are separated by \r\n\r\n and end with \r\n\r\n
        val content = inputStream.bufferedReader().readText()

        // Find the response record for our URL
        val records = content.split("WARC/1.0\r\n").drop(1) // Skip empty first split
        for (record in records) {
            if (record.contains("WARC-Type: response") &&
                record.contains("WARC-Target-URI: $targetUrl")) {
                // Extract HTML body (after the HTTP headers)
                val bodyStart = record.indexOf("\r\n\r\n", record.indexOf("HTTP/"))
                if (bodyStart != -1) {
                    val httpHeaderEnd = record.indexOf("\r\n\r\n", bodyStart + 4)
                    if (httpHeaderEnd != -1) {
                        return record.substring(httpHeaderEnd + 4)
                    }
                    return record.substring(bodyStart + 4)
                }
            }
        }
        return null
    }

    private fun parseArticle(html: String, url: String): ReaderArticle {
        val article = Readability4J(url, html).parse()
        return ReaderArticle(
            title = article.title ?: "",
            content = article.articleContent ?: article.textContent ?: "",
            textContent = article.textContent ?: "",
            excerpt = article.excerpt ?: "",
            byline = article.byline ?: "",
            siteName = article.siteName ?: extractDomain(url)
        )
    }

    private fun extractDomain(url: String): String {
        return try { URI(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }
    }
}

data class ReaderArticle(
    val title: String,
    val content: String,     // HTML content (cleaned)
    val textContent: String, // Plain text content
    val excerpt: String,
    val byline: String,
    val siteName: String
)
```

**Approach B: Extract from WebView (for streaming/remote archives)**

Use the JavaScript bridge in the WebView to call `getCurrentPageHtml()`, pass the HTML to the Kotlin side, and process with Readability4J.

### 9.2 Reader Mode Rendering

Render the extracted article in a Compose WebView (not the replay WebView) using a simple HTML template with customizable styles:

```kotlin
fun buildReaderHtml(article: ReaderArticle, fontSize: Int, fontFamily: String, isDarkMode: Boolean): String {
    val bgColor = if (isDarkMode) "#1a1a1a" else "#fafafa"
    val textColor = if (isDarkMode) "#e0e0e0" else "#1a1a1a"
    val metaColor = if (isDarkMode) "#888" else "#666"

    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=yes">
    <style>
        body {
            font-family: ${fontFamily}, Georgia, serif;
            font-size: ${fontSize}px;
            line-height: 1.7;
            color: $textColor;
            background: $bgColor;
            padding: 24px 20px 80px;
            max-width: 680px;
            margin: 0 auto;
            -webkit-font-smoothing: antialiased;
        }
        h1 {
            font-size: 1.6em;
            line-height: 1.3;
            margin: 0 0 12px;
            font-weight: 700;
        }
        .meta {
            color: $metaColor;
            font-size: 0.85em;
            margin-bottom: 32px;
            border-bottom: 1px solid ${if (isDarkMode) "#333" else "#ddd"};
            padding-bottom: 16px;
        }
        img {
            max-width: 100%;
            height: auto;
            border-radius: 4px;
            margin: 16px 0;
        }
        a { color: ${if (isDarkMode) "#6ba3d6" else "#1a5fa0"}; }
        blockquote {
            border-left: 3px solid ${if (isDarkMode) "#444" else "#ccc"};
            margin: 16px 0;
            padding: 8px 16px;
            color: $metaColor;
        }
        pre, code {
            background: ${if (isDarkMode) "#2a2a2a" else "#f0f0f0"};
            border-radius: 4px;
            padding: 2px 6px;
            font-size: 0.9em;
        }
        pre { padding: 12px; overflow-x: auto; }
        p { margin: 0 0 16px; }
    </style>
    </head>
    <body>
        <h1>${escapeHtml(article.title)}</h1>
        <div class="meta">
            ${if (article.byline.isNotBlank()) "<span>${escapeHtml(article.byline)}</span><br>" else ""}
            <span>${escapeHtml(article.siteName)}</span>
        </div>
        <div class="article-content">
            ${article.content}
        </div>
    </body>
    </html>
    """.trimIndent()
}
```

---

## 10. UI Screens & Navigation

### 10.1 Navigation Graph

```
Bottom Navigation:
├── Feed (global)          — FeedScreen
├── Relays                 — RelayBrowserScreen
│   └── Relay Detail       — (filtered FeedScreen)
├── Saved                  — SavedScreen
└── Settings               — SettingsScreen

Top-level actions:
├── Search/Filter bar      — present on Feed, Relay, Profile screens
├── Profile lookup (npub)  — ProfileScreen
│   └── tapping author     — navigates here

Modal / Full-screen:
├── Archive Viewer         — ArchiveViewerScreen (WebView with ReplayWeb.page)
│   └── Reader Mode toggle — ReaderModeScreen (clean article view)
└── Add Relay dialog
```

### 10.2 Navigation Implementation

```kotlin
// Navigation routes
sealed class Screen(val route: String) {
    object Feed : Screen("feed")
    object Relays : Screen("relays")
    object Saved : Screen("saved")
    object Settings : Screen("settings")
    data class RelayDetail(val relayUrl: String) : Screen("relay/{relayUrl}") {
        companion object { const val route = "relay/{relayUrl}" }
    }
    data class Profile(val pubkey: String) : Screen("profile/{pubkey}") {
        companion object { const val route = "profile/{pubkey}" }
    }
    data class Viewer(val eventId: String) : Screen("viewer/{eventId}") {
        companion object { const val route = "viewer/{eventId}" }
    }
    data class Reader(val eventId: String) : Screen("reader/{eventId}") {
        companion object { const val route = "reader/{eventId}" }
    }
}
```

### 10.3 Bottom Navigation Items

```
Feed     | Icons.Rounded.DynamicFeed  | "Feed"
Relays   | Icons.Rounded.Dns          | "Relays"
Saved    | Icons.Rounded.BookmarkBorder| "Saved"
Settings | Icons.Rounded.Settings      | "Settings"
```

---

## 11. Screen Specifications

### 11.1 Feed Screen (Global View)

**Purpose**: Show all web archives from all enabled relays, reverse chronological.

**State**:
```kotlin
data class FeedUiState(
    val archives: List<ArchiveWithProfile> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val urlFilter: String = "",           // Active URL domain filter
    val availableDomains: List<DomainCount> = emptyList(), // For filter suggestions
    val hasMore: Boolean = true
)

data class ArchiveWithProfile(
    val archive: ArchiveEventEntity,
    val profile: ProfileEntity?
)

data class DomainCount(
    val domain: String,
    val count: Int
)
```

**Layout**:
- Top: App bar with title "Arkive" and a search icon
- Below app bar: URL filter bar (horizontally scrollable chips for common domains, plus a text input)
- Body: LazyColumn of ArchiveCards
- Pull-to-refresh enabled
- Infinite scroll pagination (load 50 at a time)

**ArchiveCard layout**:
```
┌──────────────────────────────────────────┐
│ ┌──────┐                                 │
│ │Favicon│ nytimes.com           3h ago   │
│ └──────┘                                 │
│                                          │
│ AI Agents Are Changing How We Work       │  ← title (bold, 16sp)
│                                          │
│ https://www.nytimes.com/2025/03/15/...   │  ← URL (truncated, 12sp, muted)
│                                          │
│ ┌─────┐  Alice (npub1abc...)    ⋮ Menu   │  ← author avatar + name + overflow
│ └─────┘  🔒 Verified · 📎 1.2MB          │  ← badges: OTS, mode, size
└──────────────────────────────────────────┘
```

**Interactions**:
- Tap card → navigate to `Viewer(eventId)`
- Tap author name/avatar → navigate to `Profile(pubkey)`
- Long-press or ⋮ menu → Save for offline / Share / Copy URL / Open original URL in browser
- Pull down → refresh (re-fetch from relays)
- URL filter chips → filter list by domain
- Search icon → expand full search (searches title + URL)

**Data flow**:
1. On launch, ViewModel calls `archiveRepository.getGlobalFeed()`
2. Repository connects to all enabled relays, subscribes: `{"kinds": [30041], "limit": 50}`
3. Incoming events are parsed, validated, and cached to Room
4. Repository also fetches kind 0 profiles for all unique authors
5. UI observes Room queries via Flow

### 11.2 Relay Browser Screen

**Purpose**: List configured relays, tap one to see its archives.

**Layout**:
- Top: App bar "Relays" with + button to add relay
- Body: LazyColumn of relay items

**Relay item**:
```
┌──────────────────────────────────────────┐
│ wss://relay.damus.io                     │
│ 127 archives · Connected ✓               │
│                                    Toggle│
└──────────────────────────────────────────┘
```

- Tap → navigate to `RelayDetail(relayUrl)` which shows a FeedScreen filtered to that relay
- Toggle → enable/disable relay
- Swipe to delete (if not default)

**Add Relay dialog**: Text input for `wss://` URL, validate format, test connection before adding.

### 11.3 Relay Detail Screen

Same layout as Feed Screen but filtered to a single relay. Title shows the relay URL. Uses `archiveRepository.getArchivesByRelay(relayUrl)`.

### 11.4 Profile Screen

**Purpose**: Show archives by a specific author.

**Accessed via**: Tapping an author on any archive card, or entering an npub manually.

**Layout**:
- Top: Profile header (avatar, display name, npub, about, NIP-05)
- Below: URL filter bar (same as feed)
- Body: LazyColumn of ArchiveCards

**Profile header**:
```
┌──────────────────────────────────────────┐
│ ┌──────────┐                             │
│ │  Avatar   │  Alice                     │
│ │  (64dp)   │  alice@example.com ✓       │
│ └──────────┘  npub1abc...xyz             │
│                                          │
│ Web archivist and digital preservation   │
│ enthusiast.                              │
│                                          │
│ 42 archives                              │
└──────────────────────────────────────────┘
```

**Data flow**:
1. Subscribe: `{"kinds": [30041], "authors": ["<hex-pubkey>"]}`
2. Also fetch: `{"kinds": [0, 10063], "authors": ["<hex-pubkey>"]}` for profile + Blossom servers

### 11.5 Archive Viewer Screen

**Purpose**: Replay the archived web page in a WebView.

**This is a full-screen screen** (no bottom nav).

**Layout**:
```
┌──────────────────────────────────────────┐
│ ← Back   nytimes.com     📖 ⬇ ⋮        │  ← toolbar: back, domain, reader/save/menu
├──────────────────────────────────────────┤
│                                          │
│                                          │
│         [WebView - ReplayWeb.page]       │
│         Showing the archived page        │
│                                          │
│                                          │
├──────────────────────────────────────────┤
│ 🔒 Archived Mar 15 2025 · OTS ✓         │  ← info bar (collapsible)
└──────────────────────────────────────────┘
```

**Toolbar icons**:
- ← Back: navigate up
- 📖 Reader mode: navigate to `Reader(eventId)`
- ⬇ Save offline: download WACZ to local storage
- ⋮ Menu: Share, Open original URL, Archive info, Copy Blossom URL

**Loading sequence**:
1. ViewModel receives eventId, looks up ArchiveEventEntity from Room
2. Resolves the WACZ URL (direct URL → author's Blossom servers → fallback servers)
3. Starts ReplayServer, sets the WACZ source (remote URL or local file)
4. Creates the WebView pointed at `http://127.0.0.1:<port>/`
5. Calls `loadArchive()` JavaScript bridge with the WACZ URL and archived page URL
6. Shows loading spinner until WebView reports page loaded

**Error states**:
- WACZ not found on any Blossom server → show error with retry button
- Network error → offer to try offline if saved
- WACZ hash mismatch → show warning but allow viewing

### 11.6 Reader Mode Screen

**Purpose**: Clean article view, like Safari/Firefox reader mode.

**Full-screen screen** (no bottom nav).

**Layout**:
```
┌──────────────────────────────────────────┐
│ ← Back               Aa  🌙             │  ← toolbar: back, font settings, dark toggle
├──────────────────────────────────────────┤
│                                          │
│   AI Agents Are Changing How             │  ← title
│   We Work                                │
│                                          │
│   By Jane Smith · nytimes.com            │  ← byline + site
│   ─────────────────────────              │
│                                          │
│   Article body text in clean,            │
│   readable formatting. Images are        │
│   preserved from the archive.            │
│                                          │
│   ...                                    │
│                                          │
└──────────────────────────────────────────┘
```

**Aa menu (font settings popover)**:
- Font size: slider (14–28sp, default 18)
- Font family: Serif / Sans-serif / Monospace
- Line height: Normal / Relaxed / Compact

**Data flow**:
1. Get the WACZ file (from local cache or download)
2. Extract HTML from WACZ using `ReaderModeExtractor`
3. Process with Readability4J
4. Render in a simple WebView using the reader HTML template
5. If extraction fails, show fallback message: "Reader mode not available for this archive"

### 11.7 Saved Screen

**Purpose**: Show bookmarked/offline archives.

**Layout**: Same as Feed Screen but showing only saved items.

**Additional indicators**:
- Download status badge (downloading / downloaded / failed)
- Download progress bar for in-progress downloads
- Swipe to remove from saved

### 11.8 Settings Screen

**Purpose**: App configuration.

**Sections**:

```
ACCOUNT
  Nostr key: [not set] / [npub1abc...xyz]  →  (tap to enter nsec or connect via Amber)

RELAYS
  Manage relays  →  (navigates to Relay Browser)
  Default Blossom servers  →  (edit list)

READER MODE
  Font size: 18
  Font family: Serif
  Theme: System / Light / Dark

STORAGE
  WACZ cache: 127 MB / 500 MB limit
  Clear cache  →  (confirm dialog)
  Cache limit: 500 MB  →  (slider: 100–2000 MB)

ABOUT
  Version: 1.0.0
  Source code  →  (opens GitHub)
  Nostr Web Archiver (Chrome extension)  →  (opens Chrome Web Store)
```

---

## 12. Settings & Configuration

### 12.1 Default Relay List

```kotlin
val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://relay.primal.net",
    "wss://nos.lol",
    "wss://relay.nostr.band",
    "wss://purplepag.es",          // Profile-focused relay
    "wss://relay.snort.social"
)
```

### 12.2 Default Blossom Servers

```kotlin
val DEFAULT_BLOSSOM_SERVERS = listOf(
    "https://blossom.primal.net",
    "https://blossom.nostr.build",
    "https://cdn.satellite.earth"
)
```

### 12.3 NIP-55 (Amber) Integration

If the user has Amber (NIP-55 Android signer) installed, offer it as the preferred signing method. This avoids storing the nsec in the app at all.

```kotlin
// Check for Amber
fun isAmberInstalled(context: Context): Boolean {
    val intent = Intent("android.intent.action.VIEW", Uri.parse("nostrsigner:"))
    return intent.resolveActivity(context.packageManager) != null
}

// Sign event via Amber
fun signEventWithAmber(context: Context, eventJson: String): Intent {
    return Intent("android.intent.action.VIEW", Uri.parse("nostrsigner:$eventJson")).apply {
        putExtra("type", "sign_event")
    }
}
```

For the initial version, the app is read-only (no publishing), so signing is not needed. But if you later add "re-share" or "bookmark on Nostr" features, Amber integration is the right path.

---

## 13. Offline & Caching Strategy

### 13.1 Event Metadata Cache

- All fetched archive events are stored in Room immediately
- Profiles are cached with a 24-hour TTL (re-fetch if older)
- Old events are pruned if the total count exceeds 10,000 (delete oldest by `fetchedAt`)

### 13.2 WACZ File Cache

- Cached WACZ files go in `context.cacheDir/wacz/` (auto-cleared by OS if storage is low)
- Saved (bookmarked) WACZ files go in `context.filesDir/saved_wacz/` (persisted)
- Cache eviction: LRU by last access time, respect the user-configured limit
- File naming: `<sha256>.wacz`

### 13.3 Offline Reading Flow

1. User taps "Save offline" on an archive
2. `SavedArchiveEntity` is created with status `pending`
3. `WorkManager` enqueues a download job
4. Job uses `BlossomClient.downloadBlob()` with progress reporting
5. On success, updates entity with `localWaczPath` and status `complete`
6. On failure, updates status to `failed` (user can retry)
7. When viewing a saved archive, `ReplayServer` serves from local file (no network needed)

### 13.4 WorkManager Download Job

```kotlin
class WaczDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getString("eventId") ?: return Result.failure()
        val sha256 = inputData.getString("sha256") ?: return Result.failure()
        val directUrl = inputData.getString("directUrl")
        val blossomServers = inputData.getStringArray("blossomServers")?.toList() ?: emptyList()

        val container = (applicationContext as ArkiveApp).container
        val destFile = File(applicationContext.filesDir, "saved_wacz/$sha256.wacz")
        destFile.parentFile?.mkdirs()

        // Resolve URL
        val url = container.blossomClient.resolveWaczUrl(
            directUrl, sha256, blossomServers, UserPreferences.DEFAULT_BLOSSOM_SERVERS
        ) ?: return Result.failure()

        // Download
        val result = container.blossomClient.downloadBlob(url.substringBeforeLast("/"), sha256, destFile) { downloaded, total ->
            setProgress(workDataOf(
                "progress" to (downloaded.toFloat() / total * 100).toInt()
            ))
        }

        return if (result.isSuccess) {
            // Update Room
            container.database.savedArchiveDao().insert(
                SavedArchiveEntity(
                    eventId = eventId,
                    localWaczPath = destFile.absolutePath,
                    downloadedAt = System.currentTimeMillis() / 1000,
                    downloadStatus = "complete",
                    savedAt = System.currentTimeMillis() / 1000
                )
            )
            Result.success()
        } else {
            container.database.savedArchiveDao().insert(
                SavedArchiveEntity(
                    eventId = eventId,
                    localWaczPath = null,
                    downloadedAt = null,
                    downloadStatus = "failed",
                    savedAt = System.currentTimeMillis() / 1000
                )
            )
            Result.failure()
        }
    }
}
```

---

## 14. Key Management & Signing

For version 1.0, the app is **read-only** — it only consumes events from relays, it doesn't publish. No nsec is required for core functionality.

However, some future features (publishing bookmarks, zapping archivers) need signing. Plan for this:

1. **Amber (NIP-55)**: Preferred. External signer app, nsec never touches Arkive.
2. **nsec storage**: If user enters nsec directly, encrypt it with Android Keystore before storing in DataStore. Never log it. Never include it in crash reports.
3. **Read-only mode**: If no key is configured, all features work except publishing. UI should not nag about this.

---

## 15. Error Handling

### 15.1 Relay Connection Errors

- Show a subtle warning icon next to disconnected relays in the relay list
- Feed/profile screens should work with whatever relays ARE connected
- Implement exponential backoff reconnection (1s, 2s, 4s, 8s, 16s, 30s, max 60s)
- After 5 consecutive failures, mark relay as "temporarily unavailable" and stop retrying until user manually triggers

### 15.2 Blossom Fetch Errors

- If direct URL fails (404, timeout), try the author's Blossom server list
- If all servers fail, show: "Archive not available — the file may have been removed from all known servers"
- Offer "Try again" button
- If hash verification fails: show warning "This archive's content doesn't match its hash — it may have been modified" with option to view anyway

### 15.3 WACZ Replay Errors

- If ReplayWeb.page fails to load the WACZ: show "Unable to display this archive" with details
- Offer reader mode as a fallback (may work even if full replay fails)
- Offer "Open in browser" to try loading the original URL

### 15.4 General

- No unhandled exceptions — wrap all suspend functions with try/catch
- Use a sealed class Result pattern for all repository methods
- Log errors to Logcat but never include private keys or full event content

---

## 16. Testing Strategy

### 16.1 Unit Tests

- `NostrEvent.toArchiveEntity()` — test with various tag combinations, missing tags, malformed data
- `Bech32` — test encode/decode roundtrips, known test vectors
- `NostrFilter.toJsonObject()` — verify JSON output format
- `ReaderModeExtractor` — test with sample WACZ files
- `parseRange()` in ReplayServer — test various range header formats

### 16.2 Integration Tests

- Room DAO queries — verify filtering, pagination, search
- `BlossomClient.resolveWaczUrl()` — mock HTTP responses, test fallback chain
- `ReplayServer` — start server, make HTTP requests, verify range request handling

### 16.3 Manual Testing Scenarios

1. Fresh install → open app → see loading → see archives populate
2. Add a custom relay → verify its archives appear in feed
3. Tap an archive → see it load in the viewer
4. Toggle reader mode → see clean article text
5. Save an archive offline → airplane mode → open it from Saved
6. Enter an npub → see that user's archives
7. Filter by "nytimes.com" → only NYT archives shown
8. Kill app during WACZ download → reopen → download resumes/recovers

### 16.4 Test Data

For development, use these known relays and npubs that have web archive events:
- Check `wss://relay.nostr.band` with a filter for kind 30041
- Search for events from the Nostr Web Archiver extension users
- Create test archives yourself using the Chrome extension

---

## 17. Build & Release

### 17.1 Build Variants

```kotlin
// build.gradle.kts
android {
    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

### 17.2 ProGuard Rules

```
# Keep Nostr event serialization
-keep class com.arkive.reader.data.remote.nostr.** { *; }

# Keep NanoHTTPD
-keep class org.nanohttpd.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Readability4J
-keep class net.dankito.readability4j.** { *; }
```

### 17.3 Permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- No other permissions needed -->
```

### 17.4 App Icon

Use a simple geometric icon: an open book overlaid with a Nostr-purple (#8B5CF6) chain-link or archive symbol. Generate using Android Studio's Image Asset tool.

---

## 18. Future Considerations

These are NOT in scope for v1.0 but should be kept in mind during architecture decisions:

1. **Zapping archivers**: Send sats to people who archive useful content. Requires NIP-57 + Lightning integration.
2. **Publish bookmarks**: When you save an archive, publish a kind 39701 (NIP-B0 web bookmark) event to Nostr so others can see what you found interesting.
3. **Archive from the app**: Instead of requiring the Chrome extension, add the ability to archive a URL directly from the app (share intent → capture with a headless browser or web service).
4. **Web of Trust filtering**: Use your follow list and WoT scores to prioritize archives from people you trust.
5. **Notifications**: Get notified when someone you follow publishes a new archive.
6. **Multi-platform**: The Nostr client and Blossom client layers are pure Kotlin — they could be shared in a KMP project for iOS.
7. **Full-text search**: Parse the text content from WACZ files and index it locally for searching across saved archives.
8. **Archive verification UI**: Display detailed OTS proof status and allow verification against Bitcoin block headers.
9. **Custom archive kind**: If the community moves away from kind 30041 toward a dedicated web-archive event kind, the app should be able to subscribe to both during the transition.
10. **P2P archive sharing**: Use Nostr DMs or NIP-96 to request and share archives directly between users.

---

## Appendix A: Key Constants

```kotlin
object NostrKinds {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val RELAY_LIST = 10002
    const val BLOSSOM_SERVER_LIST = 10063
    const val WEB_ARCHIVE = 30041
    const val WEB_BOOKMARK = 39701
    const val BLOSSOM_AUTH = 24242
}

object Defaults {
    const val SUBSCRIPTION_LIMIT = 50
    const val PROFILE_CACHE_TTL_SECONDS = 86400L // 24 hours
    const val MAX_CACHED_EVENTS = 10_000
    const val WACZ_CACHE_DEFAULT_MB = 500
    const val READER_DEFAULT_FONT_SIZE = 18
    const val REPLAY_SERVER_PORT = 0 // Auto-assign
    const val RECONNECT_MAX_DELAY_MS = 60_000L
    const val RECONNECT_INITIAL_DELAY_MS = 1_000L
}
```

## Appendix B: Sample Nostr Subscription Queries

```kotlin
// Global feed: all web archives, most recent first
val globalFeed = NostrFilter(kinds = listOf(30041), limit = 50)

// Archives from a specific relay: same filter, but sent to one relay only
// (use NostrClient.subscribeToRelay())

// Archives by a specific author
val byAuthor = NostrFilter(kinds = listOf(30041), authors = listOf("<hex-pubkey>"), limit = 50)

// Fetch author profile + Blossom servers
val profileAndServers = NostrFilter(kinds = listOf(0, 10063), authors = listOf("<hex-pubkey>"))

// Fetch profiles for multiple authors at once (batch)
val batchProfiles = NostrFilter(kinds = listOf(0), authors = listOf("<hex1>", "<hex2>", "<hex3>"))

// Pagination: use "until" with the created_at of the last received event
val nextPage = NostrFilter(kinds = listOf(30041), limit = 50, until = lastEventCreatedAt)
```

## Appendix C: WACZ Content Extraction Pseudocode

```
function extractArticleFromWacz(waczFile, targetUrl):
    zip = openZip(waczFile)

    # Try reading pages.jsonl first to confirm URL exists
    pagesEntry = zip.getEntry("pages/pages.jsonl")
    if pagesEntry:
        for line in pagesEntry:
            page = parseJson(line)
            if page.url matches targetUrl:
                # URL confirmed in archive
                break

    # Find the WARC file
    warcEntry = zip.entries.find(entry => entry.name contains ".warc")

    # Parse WARC records
    for record in parseWarcRecords(warcEntry):
        if record.type == "response" and record.targetUri matches targetUrl:
            if record.httpStatus == 200 and record.contentType contains "text/html":
                htmlBody = record.payloadBody
                article = Readability4J.parse(htmlBody, targetUrl)
                return article

    return null  # Reader mode unavailable
```

---

**END OF SPECIFICATION**

This document should be sufficient to build the complete app. Start with the data layer and Nostr client, get events flowing into Room, then build the UI screens, and tackle the ReplayServer + WebView integration last (it's the most complex and benefits from having everything else working first).
