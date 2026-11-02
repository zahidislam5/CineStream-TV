# ZAHRUS TV v0.5

Native Android application with bundled offline Web UI, Kotlin h5ai directory scanner, SQLite catalog, and Media3 ExoPlayer. **Movies and TV/Web Series only**; Games/Software are never indexed.

## Build APK
1. Visit **Actions → Build ZAHRUS TV Debug APK**.
2. If no build is running, click **Run workflow**.
3. After a successful run, download the **ZAHRUS-TV-debug-APK** artifact. Inside it is **app-debug.apk**.
4. Install the APK on Android TV using ADB or a trusted TV file transfer tool.

The application has no external backend or computer requirement. Your Google TV must be connected to the ISP network to reach private 172.16.50.x servers. The online GitHub Actions builder cannot connect to those private addresses, so live video and catalog must be tested on your TV.

The scanner reads h5ai `#fallback` tables, recurses up to 24 directories per source and keeps its remaining queue for later scans. The app attempts automatic refresh every six hours while running, and the user can select **Scan** for a manual refresh. Existing indexed videos are cached in SQLite. Media codecs and seeking compatibility depend on TV hardware and server HTTP Range handling.

**Important:** This is a development/debug build, not a verified production release. Build and TV playback may require additional fixes. Only access media that you are authorized to use. No media is redistributed or hosted by this app.


## v0.5 improvements
- ZAHRUS TV branding in launcher and interface
- Cinematic poster-based shelves and featured hero; folder JPEG/PNG/WebP posters proxied through the native Android layer
- Filters: release year, category, genre where genuinely available, and sorting
- Genre from available NFO files, or grounded Animation/Documentary categories; no invented genres
- Settings: refresh interval, poster/genre/year visibility, card size, source toggles, playback fit/fill/zoom, local catalog/cache controls
- Internal Media3 video player with fullscreen toggle and fit/fill/zoom button
- Existing package identifier and SQLite database retained; DB version 2 adds genre and quality columns

Note: Previous debug builds may be signed with different temporary GitHub runner debug keys; installing v0.5 over v0.4 may fail due to signature mismatch. Preserve old install unless ready to reinstall and lose local app data.
