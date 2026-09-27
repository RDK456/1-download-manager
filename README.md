# 1 download manager

1 download manager is a native Android download manager built with Kotlin and Jetpack Compose. It keeps direct files, YouTube media, and BitTorrent transfers in one persistent queue.

## Included

- Resumable HTTP/HTTPS downloads with partial-file staging, byte-range resume, speed, ETA, pause, retry, cancel, and notifications.
- YouTube video and audio downloads through the Android yt-dlp/FFmpeg engine. Pick a video ceiling (best, 4K/2160p, 2K/1440p, 1080p, 720p, 480p, 360p) or switch to audio only and pick the saved type (M4A, MP3, Opus, or WAV). Video merges the best audio stream with FFmpeg. Settings offers a read-only **Check for update** action against the yt-dlp stable feed, and only reveals **Update yt-dlp to &lt;version&gt;** when a newer stable release actually exists. The app also refreshes yt-dlp at most once a day in the background when a download starts.
- Magnet links, remote `.torrent` URLs, and local `.torrent` files through libtorrent4j. `.torrent` files can be opened straight from any file manager ("Open with 1 download manager"), picked from the in-app button in the add sheet, or launched from the empty queue. Torrent payloads are staged privately and published to the selected/default destination after completion.
- Link import from other apps through Android Share and text-processing actions, plus clipboard paste.
- **Page media detection**: paste any web page link and tap "Check page for media" to see the video, audio, and downloadable files it exposes, then queue one or all of them. The scan understands `<video>`/`<audio>`/`<source>`, Open Graph and Twitter player metadata, JSON-LD `VideoObject`, HLS/DASH playlist links, anchor links to media, and embedded YouTube/Vimeo players. Relative URLs are resolved, duplicates dropped, and links that are already media files skip scanning entirely.
- Room-backed queue, WorkManager recovery after process death, prominent pause/resume controls, pause-all/resume-all actions, search, file open/open-with/share actions, and a two-tone theme picker.
- **Reliable background transfers**: the queue runs in a foreground service that holds a partial wake lock (so the screen may be off), posts an ongoing, non-dismissable progress notification, stops only once the database shows no queued or running rows, and restarts itself as `START_STICKY`. Finished and failed downloads alert on a high-importance channel.
- **Download settings page** (Settings -> Download settings): saved folder, simultaneous downloads (1-8), a global speed limit (Unlimited / 128 KB/s / 512 KB/s / 1 MB/s / 2 MB/s / 5 MB/s, applied across all transfers), Wi-Fi-only, automatic retries (0-5), and clearing finished downloads from the queue.
- **Theme page** (Settings -> Themes) with nine two-tone palettes (Mint, Forest, Ocean, Royal, Violet, Sunset, Rose, Slate, AMOLED), each with a light and dark variant, plus the System/Light/Dark/AMOLED mode switch.
- **Broad media support**: videos (mkv, mp4, webm, mov, avi, wmv, flv, m2ts, ogv, vob, mxf, 3gp, divx, …), audio (mp3, m4a, aac, flac, opus, wma, aiff, ape, mka, …), installers, subtitles and documents are all categorised correctly, and "Open with" resolves a handler or explains that nothing can open the file.
- Thumbnails for every download type: YouTube artwork, embedded cover art or a one-second video frame for finished media, the image file itself for pictures, and the largest media file inside a torrent folder. Missing artwork falls back to the category icon.
- Two separate tabs: **Downloads** holds direct files and YouTube media, while **Torrents** holds every magnet and `.torrent` transfer. Neither tab ever shows the other's items, and each has its own counters.
- One add action: the floating action button at the bottom opens the add sheet. The top bar shows the page title plus the filter, GitHub, and Settings icons.
- Completed downloads switch to a green **Completed** state with a check mark and the final file size instead of a full progress bar, in both the queue card and the details sheet.
- Status and category filters live behind a filter button in the top bar (badged while active), with removable chips summarising the current filter.
- Automatic type categories — Programs, Compressed, Files, Video, Audio, Documents, Images, Other — inferred from the file name, extension, and MIME type, with live counts in the filter sheet.
- Thumbnails in the queue: YouTube artwork is fetched as soon as the stream resolves and cached privately, finished image downloads show themselves as their own thumbnail, and media length is badged on the artwork. Anything without artwork falls back to its category icon.
- Breadcrumb navigation in the top bar (`1 download manager > Settings > About us`) with working back navigation.
- An **About us** page listing the app version, bundled yt-dlp version, and links to the source, releases, and issues, with a large GitHub logo that opens the repository.
- An in-app updater that reads the newest GitHub release, compares it with the installed version, downloads the release APK, and hands it to the system package installer.
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

## About page and app updates

The top bar shows the current page title. Settings is reached from the gear in the top bar (there is
no bottom-bar tab for it), the back arrow and the system back gesture both step out of Settings and
About, and **About us** sits under Settings. The About page shows the installed version, the
bundled yt-dlp version, and links to the source, the release list, and the issue tracker. The
GitHub logo on that page — and in the top bar — opens
<https://github.com/RDK456/1-download-manager> in your browser.

The updater calls the public GitHub releases API (no token needed):

1. On launch (throttled to once every 6 hours) and on demand from Settings or About.
2. The newest published release is compared with `versionName` using numeric version parts, so
   `1.10.0` correctly beats `1.9.9`. Pre-releases and older tags are ignored.
3. If a newer version exists, a dialog offers **Download update** or **Skip this version**
   (the skip is remembered per version).
4. The APK is streamed into app-private storage with progress, then handed to the system package
   installer. A download that was interrupted survives process death and is offered again.
5. Android asks for "install unknown apps" permission the first time; the app deep-links to the
   matching system screen and resumes the install when you return.

## Release signing

Release builds are signed with a key that lives **outside version control**, so a fresh clone still
builds (it falls back to the debug key with a warning):

```text
keystore/
  1-download-manager-release.jks   # the signing key
  keystore.properties              # storeFile / storePassword / keyAlias / keyPassword
```

`app/build.gradle.kts` reads `keystore/keystore.properties` and registers a `release` signing
config with v1+v2+v3 schemes. Both paths are ignored by `.gitignore`.

> Write the `storeFile` path with **forward slashes**. `Properties.load` treats a backslash as an
> escape character, which silently turns `E:\path\key.jks` into `E:pathkey.jks` and makes the build
> quietly fall back to the debug key.

To create a new key:

```powershell
keytool -genkeypair -v -keystore keystore\my-release.jks -storetype PKCS12 `
  -alias my-alias -keyalg RSA -keysize 4096 -validity 10950 `
  -storepass <store-password> -keypass <key-password> `
  -dname "CN=1 download manager, O=1 download manager, C=IN"
```

**Back up the keystore and its passwords somewhere safe.** Android only allows an update to
replace an app signed with the same key; losing them means existing installs can never be updated
again and every user has to uninstall and reinstall.

Verify what you are about to publish:

```powershell
apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk
```

`isMinifyEnabled` is deliberately `false`: the JNI engines (libtorrent4j, the yt-dlp/FFmpeg
wrapper) and Room's generated code are easier to keep correct without shrinking rules that have
been tested on a device.

## Releasing a new version

`scripts/release.ps1` performs the whole publish flow so every release is reproducible:

```powershell
.\scripts\release.ps1                 # 1.1.0 -> 1.1.1, signed release APK
.\scripts\release.ps1 -Bump Minor     # 1.1.1 -> 1.2.0
.\scripts\release.ps1 -Bump Major -Notes "Rewritten queue"
.\scripts\release.ps1 -DebugApk       # publish the debug-signed APK instead
```

It bumps `versionCode` and `versionName` in `app/build.gradle.kts`, runs `test lintDebug
assemble<Variant>`, copies the APK to the repository root, commits the whole tree, tags the
commit, pushes both, and creates the GitHub release with the APK attached (falling back to updating
the release if the tag already exists). It publishes the **signed release APK** whenever
`keystore/keystore.properties` exists, and warns and falls back to the debug APK when it does not.
Because the app's updater reads the newest published release, publishing here is exactly what
existing installs offer as an update.

> A release-signed APK cannot be installed over a debug-signed one — Android rejects it with
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The app detects the key mismatch before downloading and
> offers to open the app's system page so the old build can be uninstalled first.

## Opening .torrent files

Three entry points, all routed through the same import path:

1. File manager → tap a `.torrent` file → **Open with** → 1 download manager.
2. Any app → share sheet → 1 download manager (torrent payloads are detected by MIME type or file name).
3. In-app → the **Torrents** tab: "Open a .torrent file" on an empty queue, or the add sheet's
   "Choose .torrent file from device" button. Paste a magnet or `.torrent` URL from any tab.

Downloaded torrent content appears only under the Torrents tab.

Selected torrents are copied into app-private storage first, so the download keeps working after the content permission expires or the file is moved.

The Android wrapper artifact is versioned separately from yt-dlp itself. This build bundles yt-dlp
`2026.08.19` in `app/src/main/res/raw/ytdlp`, and keeps future updates in app-private storage.

`Check for update` in Settings queries `yt-dlp/yt-dlp`'s latest release — the same endpoint the
wrapper's STABLE channel uses — and compares the tag with the installed version using the same
numeric comparison as the app updater, so `2026.09.01` correctly beats `2026.08.19`. The check
installs nothing. If (and only if) the result is newer, a second button appears to install it;
otherwise the summary just reports that the installed version is current.

## Media and torrent notice

Only download media you own or have permission to download. YouTube availability and stream URLs change over time, and the bundled extractor can need an update when the site changes. Torrent clients should be used only with content you are legally permitted to access.
