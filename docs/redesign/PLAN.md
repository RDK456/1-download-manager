# Android redesign: Material 3 Expressive

Branch: `redesign` (never merged into `main` until Phase 8 passes). Design reference: the
"Download Hub Expressive" artifact (prototype, motion lab, 48 edge cases).

## Ground rules

1. **UI only.** No change to `:core`, `download/`, `data/local`, or the database. The
   `DownloadViewModel` API is the contract; where the new UI needs something the view model
   does not expose, add a small method, never rewrite behaviour.
2. **Strangler, not big bang.** The new UI lives in `ui/expressive/` beside the old one. Every
   commit builds and runs. `MainActivity` switches to the new root once Phase 3 reaches parity
   for the Downloads tab, behind a build-time flag (`BuildConfig.EXPRESSIVE_UI`, default on
   in this branch). The old files are deleted in Phase 8, not before.
3. **Beta pinned.** `androidx.compose.material3:material3:1.5.0-beta01`, exact. Read the release
   notes on every bump. `LoadingIndicator` and `MaterialShapes` stay behind
   `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`, confined to `ui/expressive/shape/`.
4. **Motion comes from the theme.** Never hard-code a spring, duration or easing. Every animation
   reads `MaterialTheme.motionScheme` (spatial for movement and shape, effects for colour,
   opacity and values).
5. **Pure logic gets a unit test.** State-to-shape mapping, banner priority, name shortening,
   window-class choice. Compose rendering is checked by `assembleDebug`, lint and a manual
   device pass.
6. **Commit per task**, message says what and why, `Co-Authored-By` trailer.

## Status (updated as work lands)

| Phase | State |
|---|---|
| 0 Toolchain and base | Done. Builds, tests and lint pass on the new stack. |
| 1 Theme, motion, type, shape | Done. 12 palettes x 3 modes contrast-tested. |
| 2 Components | Done. Pure models unit tested; components compile. |
| 3 Shell and Downloads/Torrents | Done in code: top bar, short bar, add menu, list and grid, notices, multi-select, skeleton, undo. |
| 4 Sheets | Add sheet and details sheet rebuilt. The torrent file list, peers, trackers, queue chips and per-link request fields are the existing ones, themed. |
| 5 Other root tabs | Discover rebuilt (carousel plus list). Search and YouTube keep their layouts but take the new theme, tonal text fields, the loading indicator and expressive empty and busy states. |
| 6 Sub-pages | Themes rebuilt. Settings, Download settings, Advanced, Queues, RSS and About keep their layouts on the new tonal section cards, fields and loader. Archive, TV, Books, Movies, Music and Player are themed only. |
| 7 Adaptive, accessibility, performance | Rail and side details pane by window class, predictive back, row semantics with actions, large-text and right-to-left handling, stalled and seeding states. No benchmark has been run. |
| 8 Cutover | **Replaced by a user option.** Both looks stay. Settings > Interface switches between New and Classic at once, and the choice is kept on the device (`UiGeneration`). The build flag is gone. |

Gaps closed since the first status: Undo for delete (12 second window, longer than the message it sits in), loading skeleton, low-storage notice,
grid layout, plain-language failure reasons, link checks and probe notes in the add sheet.

Closed in the last pass: **Add paused and Add to a queue** (split button in the add sheet; the view model's add functions take a paused flag and a queue), and **seed and peer counts on torrent rows** (read-only from the engine every three seconds while a list is showing, and used to say why a torrent is stuck).

Still open, with the reason:
- **Magnets and .torrent files cannot be added paused or to a queue from the sheet.** They go through their own preview, which has its own choices; the split button shows only for links and batches.
- **In-app reduce-motion switch.** The system animation setting is honoured; there is no separate in-app switch.
- **Restoring an open details view after the process is killed.** The list, selection and filters survive; the open details selection lives in the view model and does not.
- **Search, YouTube, Archive, TV, Player, Queues, RSS, Advanced and About** keep their layouts, on the new theme, tonal cards, fields and loader.

## Package layout (new)

```
ui/expressive/
  theme/    ExpressiveTheme.kt   MaterialExpressiveTheme wrapper, palette bridge, shapes, typography
            Motion.kt            thin helpers over MaterialTheme.motionScheme
            Typography.kt        Roboto Flex + mono numerals, emphasised styles
  shape/    StateShape.kt        download state -> MaterialShapes morph (opt-in confined here)
            Loaders.kt           LoadingIndicator wrappers
  component/ WavyBar.kt  DownloadRow.kt  Banner.kt  EmptyState.kt  FileName.kt
             FilterGroup.kt  AddMenu.kt  SelectionToolbar.kt  Sheets.kt
  shell/    AppShell.kt  Destinations.kt  WindowLayout.kt  TopBar.kt
  screen/   downloads/  torrents/  search/  youtube/  discover/  settings/  ...
  model/    Pure, testable: DownloadRowModel.kt  BannerQueue.kt  ShapeFor.kt
```

## Phases

Each phase has an exit check. Do not start the next until it passes.

### Phase 0 - Toolchain and base (no visual change)
- Install JDK 17 + Android SDK 35 (user level, `C:\dlm-tools`, `%LOCALAPPDATA%\Android\Sdk`).
- Add `local.properties`? No (ignored). Set `JAVA_HOME`/`ANDROID_HOME` per `docs/BUILD-ENVIRONMENT.md`.
- Baseline: `gradlew :app:assembleDebug :app:testDebugUnitTest` on the untouched branch. Record result.
- Versions: add `material3 = "1.5.0-beta01"` to the catalog and use it in `app`. Check what
  Compose BOM / compose-ui it needs (beta01 depends on Compose 1.12 betas) and bump the BOM or
  pin compose-ui explicitly. Re-run baseline.
- Remove `androidx.compose.material:material-icons-extended` if unused (grep first).
- Add `expressive` BuildConfig flag.
- **Done (toolchain forced by Compose 1.12):** material3 1.5.0-beta01 pulls Compose 1.12.1, which needs
  AGP 9.1+ and compileSdk 37. So the build moved to Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.20, KSP 2.2.20-2.0.4,
  Room 2.8.1, Compose Multiplatform 1.9.3 (desktop), BOM 2026.09.00, compileSdk 37 (targetSdk stays 35).
  `android.builtInKotlin=false` and `android.newDsl=false` are set temporarily; moving to AGP's built-in Kotlin is a
  follow-up. Desktop needed `compose.materialIconsExtended` added. No baseline was taken on the old stack
  (it would have meant downloading it just to throw it away); core tests, app unit tests and the desktop compile
  all pass on the new one.
- **Exit:** baseline green with the new versions; app launches unchanged.

### Phase 1 - Theme, motion, type, shape
- `ExpressiveTheme`: `MaterialExpressiveTheme(colorScheme = AppTheme.colorScheme(...), motionScheme =
  MotionScheme.expressive(), shapes, typography)`. Keep `ThemeMode` (system/light/dark/AMOLED) and the
  12 `AppTheme` palettes. Extend the palette bridge so `secondaryContainer` and `tertiaryContainer`
  are distinct tones derived from the accent (the old bridge reused the accent container for all).
- Typography: Roboto Flex variable font as a resource (OFL), emphasised styles set; JetBrains Mono
  kept for numbers only. Drop Space Grotesk/Doto from the text scale.
- Shapes: keep the Material scale but with expressive corner sizes (extra large 28 dp).
- `StateShape`: `DownloadStatus` + context -> `MaterialShapes` entry, and a composable that morphs
  between them with `Morph` driven by the spatial spring.
- **Tests:** `ShapeForTest` (every status maps to a shape; paused != running; failed is distinct).
- **Exit:** a debug-only preview screen renders theme in all 3 modes; unit tests pass.

### Phase 2 - Components
- `WavyBar` (linear wavy, falls back to flat bar when the height is too small, flat for paused/
  queued, amplitude spring-down at 100%).
- `DownloadRow` (segmented list item, leading `StateShape`, name with middle ellipsis, status line,
  speed/ETA in mono, selected state, press morph).
- `FileName` shortening (keeps extension, middle ellipsis; wraps at large font scale).
- `Banner` + `BannerQueue` (priority: storage, network, notifications, battery; one shown, "N more").
- `EmptyState`, `Loader` wrappers, `FilterGroup` (connected button group with counts).
- **Tests:** `FileNameTest`, `BannerQueueTest`, `DownloadRowModelTest` (status line text for every
  status including offline/low-storage overrides, ETA dash when speed 0, no infinity).
- **Exit:** all unit tests pass; component gallery preview renders.

### Phase 3 - Shell and Downloads tab
- `WindowLayout`: window size class -> ShortNavigationBar / collapsed rail / wide rail + list-detail.
- Reuse `NavStack` (already unit-tested) and `AppDestination` unchanged.
- Top bar (large flexible), FAB menu (Paste link, Batch range, Torrent file, Scan a page),
  `SelectionToolbar` (floating toolbar), snackbar host with Undo.
- Downloads screen wired to `DownloadViewModel` flows. All states: loading, empty, filtered-empty,
  error, banners.
- Switch `MainActivity` to the new root via the flag.
- **Exit:** Downloads tab usable end to end on a device: add, pause, resume, delete + undo, select.

### Phase 4 - Sheets
- Add sheet: states idle, clipboard, checking, invalid, duplicate, YouTube, magnet, range, FAT32 limit,
  split button (Add / Add paused / Choose queue). Reuses `LinkParser`, `FileChoiceCodec` etc.
- Details sheet: stats, actions, torrent files/peers/trackers, delete confirm + undo, no-resume warning.
- Filter/sort sheet if still needed after the inline group.
- **Exit:** every add path from the old app works from the new sheet.

### Phase 5 - Other root tabs
- Torrents (metadata fetching, no seeds, seeding/ratio), Search (loading, empty, partial error),
  YouTube (playlist select, per-item failure, outdated downloader), Discover (carousel + shape tiles).
- **Exit:** feature parity with the old tabs.

### Phase 6 - Sub-pages
- Settings, Download settings, Advanced, Queues, RSS, Themes (swatches), About + update card,
  Archive, Books, Movies, Music, TV, Player. Switches with icon thumbs, `SliderState` sliders,
  expressive text fields.
- **Exit:** every old destination reachable and equivalent.

### Phase 7 - Adaptive, accessibility, performance
- List-detail on expanded, rail on medium, tabletop posture.
- TalkBack: one node per row, custom actions, 10 % progress announcements; 200 % font; RTL.
- Reduce motion: honour animator-duration-scale and an in-app switch; infinite loops capped at 6.
- Predictive back for sheets, search, sub-pages.
- Benchmark: 200 rows / 20 active at 2 Hz, no dropped frames while scrolling.
- Migration flags in `ComposeMaterial3Flags`: decide each, document.
- **Exit:** edge-case checklist (below) fully ticked on a device.

### Phase 8 - Cutover
- Delete old UI (`inkPanel`, `keycap`, `screws`, `Lcd`, `UiStyle`, `UiEffects`, hardware nav) and the flag.
- Update README screenshots, release notes. Open PR `redesign` -> `main`.

## Edge-case traceability (artifact ids by area)

| Area | Cases | Phase |
|---|---|---|
| Network (7) | offline, Wi-Fi only, HTML instead of file, 403/404/410, no resume, stalled, proxy | 2 (row text), 3 (banners), 4 (details) |
| Storage (6) | low space, disk full, folder revoked, FAT32, name clash, file missing | 3, 4 |
| Input (7) | invalid, duplicate, clipboard, many links, huge range, login redirect | 4 |
| Torrent / YouTube (9) | metadata, no seeds, zero files, seeding, blocked videos, outdated yt-dlp, 500 videos, merging, live | 4, 5 |
| System (9) | notifications denied, battery, process death, FGS timeout, reboot, update, deep link, banner stack | 3, 6, 7 |
| Display / access (13) | 200 % text, RTL, long names, TalkBack, reduce motion, dark/AMOLED, adaptive, 10k rows, empty states, keyboard, predictive back, thin wave | 2, 7 |

## Risks

- Beta API churn (shapes, slider, carousel changed between alpha28 and beta01): isolate in
  `shape/` and one `Slider` wrapper; pin the version.
- Compose 1.12 beta pulled in by material3: watch Room/Media3/Coil compatibility and the
  `compose-stability.conf` setting; run the full unit suite after the bump.
- `compileSdk`: stays 35 unless the build says otherwise.
- Binary size: Roboto Flex is large; subset to the axes we use (`wght`, `wdth`).
- Fonts and icons: Lucide stays; do not mix in Material Symbols.

## Sub-pages pass

Every screen now has an expressive branch (the Classic look still draws the old layout):

- Search (rows, notes, picks empty state), YouTube (hint, loader, error, entry and format rows), Archive (rows, file rows, retry), TV (empty and retry), Books (cover shape, empty and retry), Player (floating mini player, library rows), Queues, RSS (rows, empty state), Advanced and About (tonal sections, whole-row toggles), media scan sheet and filter sheet (handle, wrapped chips, states).
- Shared pieces: `component/Lists.kt` (`BadgeRow`, `HintCard`, `SectionLabel`), plus the drop-ins in `DropIns.kt`.
- Checked on the emulator: Search, YouTube, Discover, TV (offline state), Archive, Books, Settings, Queues, RSS, Advanced, About, light and dark.
- Not checked on a device: Player, media scan sheet, filter sheet, torrent Content/Trackers/Peers tabs (only re-themed), large text and RTL on the new rows.
- The `Card` drop-in honours explicit `colors`, `shape`, `elevation` and `border`; the tonal card is only its default.

## Bug pass

Fixed: a queue deleted while a sheet is open no longer strands the download (falls back to Main with a message); quality rows get their own segmented shape; segmented lists sit 2 dp apart; Search and list rows announce one action; Advanced radio rows select across the row; Archive shows a retry row when the next page fails and restores the open item; downloads paused for a delete are resumed if the process dies inside the undo window; links carrying a login or signed token are not kept in saved state; torrent Content, Trackers and Peers sit on one tonal surface.

Restore and failure pass: the torrent preview is rebuilt after a kill (a magnet asks the swarm again, a .torrent file is re-read from the app's own copy; links naming a passkey are not kept), and the Player queue is saved to its own preferences and brought back paused at the same place (live TV is not restored; a missing file drops it). Books and torrent picks that fail to load stop asking by themselves and show a Try again row, or a full empty state when nothing loaded.

Still open: the ANR and the slow cold start seen on the emulator are unexplained (software rendering, needs a real device); the Books picks footer retry was not exercised on a device.
