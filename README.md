# ZAHRUS TV v0.6.0 — Complete ISP library indexing

A lightweight Google TV / Android TV app with simple poster cards, local SQLite catalog, Media3 video player, movie/series categories and library-wide search.

## What's new

- **Scan All** continues through the available Movies & TV/Web Series category folder trees, instead of stopping after 16 folders per source.
- The scanner uses a persistent **crawl_queue** inside the existing SQLite database; it records completed, pending and unavailable directories.
- It scans one directory at a time, throttles requests, keeps the UI responsive, and continues after interruption by selecting **Scan All** again.
- Videos are indexed by stable URL ID. Existing catalog and favorites are preserved by a v2 → v3 database migration.
- Search and category filters query the **entire SQLite catalog**, not a limited selection of 1,800 items.
- Movie & Series category chooser, release-year filter, alphabetical and newest/oldest sorting.
- Home screen retains the simple grid layout and displays compact category sections; no Netflix-style UI.
- Series detail pages load episode results in batches of 100.
- Existing lightweight poster proxy limits concurrent image requests and shrinks image dimensions for low-RAM TVs.

## How to use

1. Connect Google TV to the same ISP network as the private 172.16.50.x servers.
2. Open ZAHRUS TV and select **Scan All** once. Keep the application open during the first full scan; it can take a while on a large library.
3. Watch progress: indexed files, scanned folders, remaining folders and unreachable folders.
4. If you must stop, choose **Pause Scan**. Selecting Scan All later resumes the recorded pending folders.
5. When the scan finishes, choose Movies or Series, then Category and Year; Search finds results throughout the indexed database.
6. If any directory is unreachable, press Scan All again to retry the failed paths. If all previously queued paths have completed, the next Scan All starts a fresh catalog crawl to discover new content.

## Important boundaries

- The scanner follows only the fifteen preconfigured media roots and their reachable links. It does not browse Games/Software or unrelated servers.
- A server that is offline, inaccessible, or does not expose files through its directory HTML cannot be exhaustively indexed. Inspect the unavailable count for such cases.
- The index runs while the application is open. It is not a perpetual background server-side crawler. It does not download the movie files.
- GitHub Actions can compile and emulator-test the app, but cannot test private ISP media URLs or actual TV playback.
- Installing debug APKs over an older APK may fail if GitHub Actions used a different signing certificate. Uninstalling clears on-device app data.

Repository: https://github.com/zahidislam5/CineStream-TV
