# 1 download manager

**Free, open source, no ads.** One download manager for **Android** and **Windows**: direct links,
YouTube, torrents and streams in a single queue — with browser extensions for Chrome and Firefox.

[**Download the latest release**](https://github.com/RDK456/1-download-manager/releases/latest) ·
[Features](#features) · [Install](#install)

<img src="docs/screenshots/desktop.png" alt="Windows app" width="820">

<p>
  <img src="docs/screenshots/android-downloads.png" width="200" alt="Download queue">
  <img src="docs/screenshots/android-discover.png" width="200" alt="Discover">
  <img src="docs/screenshots/android-archive.png" width="200" alt="Internet Archive browser">
  <img src="docs/screenshots/android-youtube.png" width="200" alt="YouTube downloader">
</p>

## Why people use it

| | |
|---|---|
| **Fast** | Splits each file over up to 16 connections (8 by default) so a single link uses your whole line. |
| **Never starts over** | Pause, crash, sleep or lose Wi-Fi — downloads resume where they stopped, and retry on their own. |
| **Watch while it downloads** | Torrents can play in order, so you start the video before it finishes. |
| **Catches everything** | Chrome and Firefox extensions grab downloads and streams from the page. |
| **Yours** | No account and no ads. Open source. |

## Features

**Downloading**
- **Fast, resumable downloads** — segmented HTTP/HTTPS with pause, resume, retry, per-file speed limits and a one-click **slow mode**.
- **Self-healing** — retries after a dropped connection or when the PC wakes from sleep; swap an expired link for a fresh one and keep your progress.
- **Batch download** — paste many links at once, or a range like `file[001-120].jpg`. Duplicates are caught for you.
- **Queues and scheduler** — several queues, start and stop times, reorder with move up / down.
- **When done** — exit, sleep, hibernate, lock, shut down or open the folder once the queue finishes.

**Video, audio and torrents**
- **YouTube** — videos, whole playlists, albums and channels; pick the quality or save audio as M4A, MP3, Opus or WAV.
- **Stream grabber** — HLS (`.m3u8`) and DASH (`.mpd`) streams are merged into one file (DRM-protected streams are refused).
- **Torrents** — magnet links and `.torrent` files, file selection, and **Play now** to watch while downloading.
- **Internet Archive** — browse all of archive.org and download a whole item in one click.
- **Free books, movies, music and TV** — public-domain and freely licensed sources, plus a built-in player.

**After the download**
- **Checksums** — verify MD5, SHA-1, SHA-256 or SHA-512 on finished files.
- **Auto-unzip and virus scan** — optionally unzip archives and scan new files with Windows Defender.
- **Removed-file tracking** — files deleted outside the app stay in the list, struck through.

**Windows extras**
- **Per-download window** — live speed graph, connection map, open file, copy link, pause and resume, in an IDM-style dialog.
- **Control from your phone** — watch and pause downloads from any phone on your Wi-Fi, protected by a PIN.
- **Interface zoom**, system tray, start with Windows, and a glass-style download window.

**Everywhere**
- **Browser integration** — Chrome and Firefox extensions hand downloads and page media to the app, with a per-site exclude list.
- **Automatic updates** — the app checks GitHub and installs new versions for you.
- **Themes** — light, dark and AMOLED, with nine colour palettes.

## Install

Get the latest version from the [releases page](https://github.com/RDK456/1-download-manager/releases/latest):

| Platform | File |
|---|---|
| Android | `1-download-manager-<version>.apk` (or the `arm64-v8a` / `x86_64` build for a smaller download) |
| Windows | `1-download-manager-<version>.msi`, or the `-portable.zip` to run without installing |
| Chrome / Edge | `1-download-manager-extension-chrome-<version>.zip` |
| Firefox | `1-download-manager-extension-firefox-<version>.xpi` |

The Windows installer needs no administrator rights. For a silent install:
`msiexec /i 1-download-manager-<version>.msi /qn`

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35, build tools 35.0.0). Set `ANDROID_HOME`
or `sdk.dir` in `local.properties`, then:

```text
gradlew test                  # all unit tests
gradlew :app:assembleDebug    # Android APK
gradlew :desktop:run          # run the Windows app
```

The project has three modules: `core` (shared download, torrent and search engines in plain Kotlin),
`app` (Android, Jetpack Compose) and `desktop` (Windows, Compose Multiplatform).
More detail on the build machine setup is in [docs/BUILD-ENVIRONMENT.md](docs/BUILD-ENVIRONMENT.md).

## Releasing

`scripts/release.ps1` bumps the version, runs the tests and lint, builds every asset, commits,
tags, pushes and publishes the GitHub release:

```powershell
.\scripts\release.ps1                          # patch: 2.0.0 -> 2.0.1
.\scripts\release.ps1 -Bump Minor -NotesFile notes.md
```

Release builds are signed with `keystore/` (not in git). **Back up the keystore and its
passwords** — Android only updates an app signed with the same key.

## Legal

Only download content you own or have permission to download. See
[LICENSE-NOTICE.md](LICENSE-NOTICE.md) for third-party licences.
