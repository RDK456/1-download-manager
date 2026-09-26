# 1 download manager

1 download manager is a native Android download manager built with Kotlin and Jetpack Compose. It keeps direct files, YouTube media, and BitTorrent transfers in one persistent queue.

## Included

- Resumable HTTP/HTTPS downloads with partial-file staging, byte-range resume, speed, ETA, pause, retry, cancel, and notifications.
- YouTube video and audio downloads through the Android yt-dlp/FFmpeg engine. Pick a video ceiling (best, 4K/2160p, 2K/1440p, 1080p, 720p, 480p, 360p) or switch to audio only and pick the saved type (M4A, MP3, Opus, or WAV). Video merges the best audio stream with FFmpeg. The app checks the stable yt-dlp channel daily and exposes a manual update action in Settings.
- Magnet links, remote `.torrent` URLs, and local `.torrent` files through libtorrent4j. `.torrent` files can be opened straight from any file manager ("Open with 1 download manager"), picked from the in-app button in the add sheet, or launched from the empty queue. Torrent payloads are staged privately and published to the selected/default destination after completion.
- Link import from other apps through Android Share and text-processing actions, plus clipboard paste.
- Room-backed queue, WorkManager recovery after process death, prominent pause/resume controls, pause-all/resume-all actions, search, status filters, file open/share/delete actions, and system/light/dark/AMOLED theme selection.
- Automatic type categories — Programs, Compressed, Files, Video, Audio, Documents, Images, Other — inferred from the file name, extension, and MIME type, with a category filter row and per-item category badges.
- Files are saved under the default `Download/DownloadHub` folder, or a folder selected with Android's persisted document-tree picker. Engines keep resumable data in private staging until completion.

## Build

1. Install JDK 17.
2. Install Android SDK Platform 35 and Build Tools 35.0.0 (or set `compileSdk` to another installed platform).
3. Set `ANDROID_HOME` or add `sdk.dir` to `local.properties`.
4. Run:

```text
./gradlew test
./gradlew assembleDebug
```

On Windows use `gradlew.bat test` and `gradlew.bat assembleDebug`.

## Sharing links

Use the Android share sheet and choose 1 download manager from any app. The app accepts shared `text/plain` links, selected text actions, `http`/`https` links, and `magnet:` links. Incoming links open the add sheet with the source detected automatically, so you can review the filename or choose YouTube quality/audio type before adding it.

## Opening .torrent files

Three entry points, all routed through the same import path:

1. File manager → tap a `.torrent` file → **Open with** → 1 download manager.
2. Any app → share sheet → 1 download manager (torrent payloads are detected by MIME type or file name).
3. In-app → the add sheet's "Choose .torrent file from device" button, the "Open a .torrent file" button on an empty queue, or paste a magnet/`.torrent` URL.

Selected torrents are copied into app-private storage first, so the download keeps working after the content permission expires or the file is moved.

The Android wrapper artifact is versioned separately from yt-dlp itself. This build bundles yt-dlp `2026.08.19` in `app/src/main/res/raw/ytdlp`, checks the stable release channel daily, and keeps future updates in app-private storage. Settings can force an update.

## Media and torrent notice

Only download media you own or have permission to download. YouTube availability and stream URLs change over time, and the bundled extractor can need an update when the site changes. Torrent clients should be used only with content you are legally permitted to access.
