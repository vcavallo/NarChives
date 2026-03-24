# Narchives

An Android app for browsing web page archives published via the [Nostr](https://nostr.com) protocol and stored on [Blossom](https://github.com/hzrd149/blossom) media servers.

Think of it like an RSS reader, but for WACZ web archives. Anyone with the [Nostr Web Archiver](github.com/fiatjaf/nostr-web-archiver) Chrome extension can archive a web page — the archive gets uploaded to a Blossom server and announced on Nostr. Narchives is the reading client for those archives.

**Check out [github.com/fiatjaf/nostr-web-archiver](github.com/fiatjaf/nostr-web-archiver) for creating web archives and uploading them to blossom/nostr!** The more archives on nostr, the better this app is (and the less we have to rely on centralized internet archives)

## Features

- **Global feed** — browse all recently archived web pages across your relays
- **Relay browsing** — see archives published to a specific relay
- **Profile browsing** — see all archives by a specific Nostr user (npub)
- **URL filtering** — filter archives by domain (e.g. show only nytimes.com)
- **Archive replay** — view the original web page as it was captured, rendered via [ReplayWeb.page](https://replayweb.page/)
- **Reader mode** — clean article view stripped down to just the text
- **Offline reading** — save archives locally for reading without internet
- **Bookmarking** — save archives to a local library

## How It Works

1. The app connects to Nostr relays and subscribes to **kind 30041** events (web archive announcements)
2. Each event contains metadata about an archived page: the original URL, title, and a SHA-256 hash pointing to a WACZ file on a Blossom server
3. When you tap an archive, the app resolves the WACZ file location, starts a local HTTP server, and renders the archived page in a WebView using ReplayWeb.page
4. Reader mode extracts the article text from the WACZ using Readability4J

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Quartz** (Amethyst's Nostr protocol library) — relay connections, event parsing, bech32, signature verification
- **Room** — local database for caching events, profiles, relays
- **NanoHTTPD** — embedded HTTP server for WACZ replay (proxies range requests to Blossom servers)
- **ReplayWeb.page** — renders WACZ archives in WebView
- **Readability4J** — article extraction for reader mode
- **OkHttp** — HTTP client for Blossom blob retrieval
- **WorkManager** — background WACZ downloads

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
- Android SDK (API 35, build-tools 35.0.0)
- Gradle 8.11.1
- [nak](https://github.com/fiatjaf/nak) (Nostr Army Knife) for testing

### Build

```bash
./gradlew assembleDebug
```

### Test keys (via nak)

```bash
# Generate a keypair
nak key generate                    # hex private key
nak key public <hex-privkey>        # hex public key
nak encode nsec <hex-privkey>       # bech32 nsec
nak encode npub <hex-pubkey>        # bech32 npub

# Query relays for kind 30041 events
nak req -k 30041 -l 5 wss://relay.nostr.band
```

### Project Structure

```
app/src/main/java/com/narchives/reader/
├── NarchivesApp.kt              # Application class
├── MainActivity.kt              # Single activity, hosts Compose
├── di/AppContainer.kt           # Manual dependency injection
├── data/
│   ├── local/                   # Room database, entities, DAOs
│   ├── remote/nostr/            # Quartz wrapper, event mapping
│   ├── remote/blossom/          # Blossom HTTP client
│   ├── repository/              # ArchiveRepository, ProfileRepository, RelayRepository
│   └── preferences/             # DataStore preferences
├── replay/                      # NanoHTTPD replay server, reader mode
└── ui/                          # Compose screens, navigation, theme
```

## Protocol References

- [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) — Nostr basic protocol
- [Kind 30041](https://github.com/nickevin/nostr-web-archiver) — Web archive announcement events
- [Blossom](https://github.com/hzrd149/blossom) — Blob storage protocol (BUD-01, BUD-02)
- [WACZ](https://specs.webrecorder.net/wacz/latest/) — Web Archive Collection Zipped format
- [ReplayWeb.page](https://replayweb.page/) — Client-side WACZ replay engine

## License

TBD
