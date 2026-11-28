# Publishing to more places

The files are prepared here. Each submission needs your own account and your own decision,
so none of them has been sent.

## First: choose a licence

The repository calls itself open source but has no `LICENSE` file, so by law nobody else
may reuse the code, and the places below that require an open licence will refuse it.
The Android app includes `youtubedl-android`, which is GPL-3.0, so the app as a whole has
to be distributed under terms compatible with GPL-3.0. GPL-3.0 is the straightforward
choice; check it suits you, then add the file with GitHub's *Add file → LICENSE* template
and update `License` in the winget locale file.

## winget

Files: `packaging/winget/` (for v2.2.24). Check them with `winget validate packaging\winget`.

For a newer release, change the version in all three files, the installer address and
`InstallerSha256` (`gh release view <tag> --json assets` shows each file's `digest`).

Submit by copying the three files to `manifests/r/RDK456/1DownloadManager/<version>/` in a
fork of [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) and opening a
pull request. Microsoft's checks and a reviewer decide.

## IzzyOnDroid

Store text and pictures: `fastlane/metadata/android/en-US/`. Needs the licence above and
signed APKs on GitHub releases (already the case). Ask for inclusion by opening an issue in
the [IzzyOnDroid repository](https://gitlab.com/IzzyOnDroid/repo). It has a size limit per
app; the universal APK is about 120 MB and the per-processor APKs about 65 MB, so expect to
be asked about size, and it may be refused.

## Browser stores

Upload the two extension files from a release.

- Chrome Web Store: [developer dashboard](https://chrome.google.com/webstore/devconsole)
  (one-time fee). Stores often reject extensions that help download YouTube videos.
- Firefox: [addons.mozilla.org developer hub](https://addons.mozilla.org/developers/) (free).
  A Mozilla-signed file is also what lets Firefox keep the extension installed.

Both ask why each permission is needed and for a privacy statement: the extension talks
only to the app on this computer.

## Code signing for Windows

Unsigned builds show a SmartScreen warning. [SignPath Foundation](https://signpath.org/)
signs open-source projects for free if it accepts them; it needs the licence above and
builds made by a public CI service. Approval is not guaranteed. A paid certificate is the
other route. Neither is set up.
