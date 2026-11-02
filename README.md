# CineStream TV — Google TV APK (Hybrid)

Native Android application with bundled offline Web UI, Kotlin h5ai directory scanner, SQLite catalog, and Media3 ExoPlayer. **Movies and TV/Web Series only**; Games/Software are never indexed.

## Build APK
1. Visit **Actions → Build CineStream TV Debug APK**.
2. If no build is running, click **Run workflow**.
3. After a successful run, download the **CineStreamTV-debug-APK** artifact. Inside it is **app-debug.apk**.
4. Install the APK on Android TV using ADB or a trusted TV file transfer tool.

The application has no external backend or computer requirement. Your Google TV must be connected to the ISP network to reach private 172.16.50.x servers. The online GitHub Actions builder cannot connect to those private addresses, so live video and catalog must be tested on your TV.

The scanner reads h5ai `#fallback` tables, recurses up to 24 directories per source and keeps its remaining queue for later scans. The app attempts automatic refresh every six hours while running, and the user can select **Scan** for a manual refresh. Existing indexed videos are cached in SQLite. Media codecs and seeking compatibility depend on TV hardware and server HTTP Range handling.

**Important:** This is a development/debug build, not a verified production release. Build and TV playback may require additional fixes. Only access media that you are authorized to use. No media is redistributed or hosted by this app.
