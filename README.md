# ZAHRUS TV v0.7 — Preloaded ISP Movie Catalog

This development branch adds support for a **prebundled catalog** from the owner's existing ZAHRUS TV exporter. The currently working v0.6 branch remains unchanged.

## The user's catalog

- 203,724 video files; 34,331 movie files; 169,393 TV/series episode files.
- 14 media categories (no Documentaries, Games, Software, or PC files).
- 49,483 fully crawled folders; 10 folder timeouts still unresolved.
- 149,990 video rows with a poster URL. Poster images themselves are not bundled.
- Indexed at 2026-11-04 19:30 UTC. URL reachability must be checked on the ISP network.

## How to complete this branch

1. Upload the file `catalog_seed.db.gz` to **`app/src/main/assets/catalog_seed.db.gz`** in this GitHub branch using **Code → Add file → Upload files**. The file is ~20.5 MB (within GitHub's 25 MB browser-upload limit).
2. The push triggers **Build ZAHRUS TV Debug APK** for this branch. The workflow checks the SHA-256 hash of the seed and compiles the app.
3. Download the APK Artifact from GitHub Actions and install on Google TV. The first launch will unpack the seed in app-private storage; later starts are fast and never require a full scan.
4. Use **Update Library** only when new content has been uploaded. It performs a resumable scan of supported categories. The 10 timed-out folders can be retried.

Seed SHA-256: `526a9fad216cca49ecec9d61568491027347ffe38932d68432a66924ead77339`

### How it works

On a new install the bundled compressed SQLite database is copied directly to the app's private database directory and opened by the `LibraryStore` v4 helper. On an existing install the seed is merged using `INSERT OR IGNORE` into the existing database, retaining user-scanned items, local favorites and saved resume positions. A `seed_meta` marker prevents repeated imports. No custom server, PC process, or manual scan is needed **after the one-time first-launch import**.

The APK contains movie and series metadata, video URLs and poster URLs. Poster images and videos still require access to the ISP's private network. The first launch unpacks ~165 MB, so ensure ample free storage; backup existing app data before changing signing certificates. Debug APK signing may differ between GitHub Actions runs.

The preloaded source data are user-provided and must remain in a private repository. The scanner has no source for Documents/Games/Software and does not traverse unrelated servers.

## Testing

Build and an Android emulator startup smoke test can validate compiler/runtime startup, but only the user's ISP-connected Google TV can validate private link playback and poster retrieval.
## Side-by-side install — safer for existing users

This v0.7 application uses the new package ID `com.zahrus.tv`. It installs **beside** the older working `com.cinestream.tv` application, so there is no need to uninstall or erase the old application's favorites or data. Because Android separates the two apps' private storage, favorites/resume history from the old app are not automatically transferred. The new app starts with a fully preloaded catalog; the old app remains installed for fallback.
