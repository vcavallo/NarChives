# Narchives

An Android app for browsing web page archives published via the [Nostr](https://nostr.com) protocol and stored on [Blossom](https://github.com/hzrd149/blossom) media servers.

Think of it like an RSS reader, but for WACZ web archives. Anyone with the [Nostr Web Archiver](https://github.com/fiatjaf/nostr-web-archiver) Chrome extension can archive a web page -- the archive gets uploaded to a Blossom server and announced on Nostr. Narchives is the reading client for those archives.

## Features

- **Global feed** -- browse all recently archived web pages across your connected relays
- **Combinatory filters** -- filter by WACZ-only (default), relay, domain, and author simultaneously
- **Infinite scroll** -- automatically loads older archives as you scroll
- **Full-page archive replay** -- view the original web page as it was captured, rendered natively in a full-screen WebView via a custom WACZ replay engine
- **Reader mode** -- clean article view extracted from the archived page via Readability4J
- **Save for offline** -- bookmark archives and download WACZ files for offline reading
- **Relay management** -- add/remove/toggle relays, see per-relay archive counts
- **Profile browsing** -- see all archives by a specific Nostr user
- **Search** -- search archives by URL or title

## How It Works

1. The app connects to Nostr relays and subscribes to **kind 4554** events ([nostr-web-archiver](https://github.com/fiatjaf/nostr-web-archiver) WACZ archives) and **kind 30041** events (text articles)
2. Each kind 4554 event contains: the original page URL (`page` tag), a Blossom URL to the WACZ file (`url` tag), and the archived domain (`r` tag)
3. When you tap an archive, the app downloads the WACZ file and replays it natively -- a custom Kotlin WACZ reader parses the ZIP structure, CDX index, and WARC records, serving every resource to the WebView via `shouldInterceptRequest()`. No service workers needed.
4. Reader mode opens the WACZ, extracts the HTML from the WARC data, and runs Readability4J on it for a clean article view

## Architecture

### WACZ Replay Engine

The replay engine bypasses the typical ReplayWeb.page service worker approach (which doesn't work reliably in Android WebView) with a native Kotlin implementation:

- **`WaczReader`** -- Opens the WACZ ZIP, parses the CDXJ index into a URL lookup map, and reads individual gzip-compressed WARC records at exact byte offsets using `RandomAccessFile`
- **`WaczReplayWebViewClient`** -- Custom `WebViewClient` that intercepts every request via `shouldInterceptRequest()` and serves content directly from the WACZ file. Handles redirect chains within the archive (Android WebView doesn't allow 3xx in `WebResourceResponse`).

This gives full request interception including navigations, iframes, and all sub-resources -- something service workers can't reliably do in Android WebView.

### Nostr Layer

Uses [Quartz](https://github.com/vitorpamplona/amethyst/tree/main/quartz) (from Amethyst) for relay connections, event parsing, and bech32 encoding. Thin wrapper in `NostrClient` exposes events as Kotlin `SharedFlow`.

### Data Layer

- **Room** database with entities for archive events, profiles, relays, and saved archives
- **DataStore** preferences for theme, reader font settings, cache limits
- Repositories for archives, profiles, relays, and saved archives

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Quartz** (Amethyst's Nostr protocol library) -- relay WebSocket connections, event parsing, bech32, signature verification
- **Room** -- local database for caching events, profiles, relays
- **Custom WACZ replay engine** -- native Kotlin ZIP/CDX/WARC parser + `WebViewClient.shouldInterceptRequest()`
- **Readability4J** -- article extraction for reader mode
- **OkHttp** -- HTTP client for Blossom blob retrieval
- **WorkManager** -- background WACZ downloads for offline reading
- **Coil** -- image loading for profile avatars

## Development

### Prerequisites

This project uses [Nix](https://nixos.org/) with [direnv](https://direnv.net/) for a reproducible dev environment. No global installs needed.

### Setup

```bash
git clone <repo-url> nostr-archive-reader
cd nostr-archive-reader
direnv allow    # Activates the Nix dev shell
```

This gives you:
- JDK 17
- Android SDK (API 34/35/36, build-tools 34/35/36)
- Gradle 8.11.1
- [nak](https://github.com/fiatjaf/nak) (Nostr Army Knife) for testing

### Build

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Test with nak

```bash
# Generate a keypair
nak key generate
nak key public <hex-privkey>
nak encode npub <hex-pubkey>

# Query relays for WACZ archive events (kind 4554)
nak req -k 4554 -l 5 wss://nostr.wine

# Query relays for text article events (kind 30041)
nak req -k 30041 -l 5 wss://relay.damus.io

# Decode a nevent
nak decode nevent1q...
```

### Project Structure

```
app/src/main/java/com/narchives/reader/
├── NarchivesApp.kt              # Application class, seeds relays
├── MainActivity.kt              # Single activity, hosts Compose
├── di/AppContainer.kt           # Manual dependency injection
├── data/
│   ├── local/                   # Room database, entities, DAOs
│   ├── remote/nostr/            # Quartz wrapper (NostrClient, EventMapper)
│   ├── remote/blossom/          # Blossom HTTP client
│   ├── repository/              # Archive, Profile, Relay, SavedArchive repositories
│   └── preferences/             # DataStore preferences
├── replay/
│   ├── WaczReader.kt            # Native WACZ/CDX/WARC parser
│   ├── WaczReplayWebViewClient.kt  # WebView request interceptor
│   ├── ReaderModeExtractor.kt   # WACZ -> Readability4J
│   └── WaczDownloadWorker.kt    # Background download via WorkManager
└── ui/
    ├── navigation/              # Bottom nav + full-screen routes
    ├── theme/                   # Material 3 theme (Nostr purple accent)
    ├── components/              # ArchiveCard, LoadingIndicator, etc.
    └── screen/
        ├── feed/                # Global feed with combinatory filters
        ├── relay/               # Relay management
        ├── profile/             # Author profile + their archives
        ├── viewer/              # Full-screen WACZ replay + text content
        ├── reader/              # Reader mode
        ├── saved/               # Saved/offline archives
        └── settings/            # App settings
```

## Default Relays

The app ships with these default relays, chosen for kind 4554 (WACZ archive) availability:

- `wss://relay.damus.io`
- `wss://relay.primal.net`
- `wss://nos.lol`
- `wss://relay.nostr.band`
- `wss://purplepag.es`
- `wss://relay.snort.social`
- `wss://nostr.wine`
- `wss://nostr.mom`
- `wss://relay.nostr.net`

## Protocol References

- [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) -- Nostr basic protocol
- [Kind 4554](https://github.com/fiatjaf/nostr-web-archiver) -- WACZ web archive events (nostr-web-archiver)
- [Kind 30041](https://github.com/nostr-protocol/nips) -- Addressable archive/article events
- [Blossom](https://github.com/hzrd149/blossom) -- Blob storage protocol (content-addressed by SHA-256)
- [WACZ](https://specs.webrecorder.net/wacz/latest/) -- Web Archive Collection Zipped format
- [WARC](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/) -- Web ARChive format (inside WACZ)

## License

TBD
