# Changelog

All notable fork changes on top of upstream [xnotes](https://github.com/shardulvs/xnotes-android).
Versions read `<upstream version>+NNN`, where `NNN` counts our builds on that upstream base.

## 0.8.11+001 — 2026-08-16

First build on upstream **v0.8.11** (`versionCode` 48), rebased off **v0.8.10** (47). All nine fork
commits replay onto the new base; the work below is what the new base required of them.

### The pressure band follows the ink into the wet ribbon

- **Upstream now draws a live stroke twice over.** `WetRibbon` grows a ribbon sample by sample under
  the pen and `WetInkCache` bakes its settled part into a raster, while `StrokeEngine.build` still
  rebuilds the whole stroke at pen-up. The two must agree point for point — that parity is what the
  raster and mesh caches are built on — and the fork's input band reached only the rebuild. A pen
  calibrated to a measured band would have written one width and left another behind at lift.
- **The band is carried into the wet path** like every other style field: `WetRibbon` takes
  `pressureLow` / `pressureHigh` / `pressureCurve` and hands them to `StrokeEngine.halfWidth`, and
  `Stroke` copies them in at pen-down for the live ribbon exactly as it already did for the rebuild.
- **The three parameters moved onto `build`'s primitive-channel overload** — the one `Stroke` calls
  now that upstream packs its samples into float arrays off a double origin; the `Sample` overload
  forwards them on. Defaults remain the identity, so an uncalibrated pen is byte-for-byte unchanged.
- **`WetRibbonTest` holds the ribbon against the batch engine at every single length**, so threading
  the band through its two helpers puts every existing parity test on a banded pen the moment one is
  configured. `matchesWithACalibratedPressureBand` supplies a measured-looking band (0.05–0.45,
  `k` = 12) for the pen, calligraphy and speed tools; removing the threading fails that test alone.

### Kept intact across the rebase

- **The unsaved-changes prompt is per-pane now** (upstream routes it to the pane being acted on) and
  still opens as a `SokkiAlertDialog`, so the outlined-surface sweep survives the split view.
- **`FakeRenderer` records both sets of primitives** — upstream's ribbon, dash and raster runs
  alongside the fork's polyline geometry, which the 速記 ruling tests assert against.
- **`DIR_ALPHA` retired with upstream's calligraphy nib rewrite**; the fork's pressure constants
  moved up beside `PRESSURE_CURVE_K` in its place.
- Verified after the rebase: install identity, ABI filter, fork version block, the traced icon and
  its 24 launch frames, the 白い熊 速記 UI page and its cog long-press, `SokkiUi.applyTo` at the end
  of `buildPalette`, the backup engine and the 保存復元 receiver, and the de-branding sweep.

### What the new upstream base brings (upstream's work)

- **Split view** — two notes side by side, the focused pane marked and its dialogs, shortcuts and
  messages routed to it; a process-wide `LiveSettings` so both panes share one settings copy.
- **Wet-ink cache** — live strokes grown as rails and baked into a raster rather than rebuilt per
  frame, anchored to the old pixel grid as the surface grows.
- **Calligraphy nib rebuilt** on a head chord and a slew limiter, with its widening capped over the
  last 8 px at pen-up.
- Stroke samples packed into float arrays off a double origin; sample reduction back on.
- Image rotation by a grip on the selection instead of a menu button; press-select and hold-grab on
  the canvas; a dragged item handed to the page it lands on; the shape a stroke snapped into comes
  up selected; long thin rectangles recognised; a cut clipboard spent on one paste; the colour picker
  opened from the canvas swatches; copy/front/duplicate/bring-to-front icons redrawn; the minimap
  button drawn as an inset panel; pane touches no longer falling through to the backstage.

## 0.8.10+010 — 2026-08-04

On upstream **v0.8.10** (`versionCode` 47), same base as `+005`.

### 速記 paper — the shorthand ruling as a page background

- **New `PagePattern.SOKKI`**, chip labelled **速記**, beside Lines / Dots / Grid in both the paged
  styles popup and the infinite-canvas styles popup. Serializes as `"sokki"`, so notes using it
  reopen correctly in `.xnote` and `.xcanvas` alike.
- **Geometry measured from the Samsung Notes 速記 template**, not approximated: a 2 px heavy rule
  every 64 px, with 1 px hairlines 25 px and 49 px below it. Held as fractions of the period in
  `PageStyle.SOKKI_LINES`, so the spacing slider scales the whole band and the paper keeps its
  proportions at any size. 64 px is already `DEFAULT_SPACING`, so the default is the template at 1:1.
- **Pattern-specific default colour.** `PagePattern.defaultColor` gives 速記 the template's opaque
  blue while every upstream ruling keeps its 25%-grey; the styles popup resolves the pattern down the
  same page → document → default chain, so the "Default" swatch shows what the page actually draws.
- **The painter walks whole bands, not single lines**, so a sub-rect handed to the sharp-viewport
  pass lands its rules exactly where the full-page pass puts them. A test pins that seam agreement
  along with the band geometry, the two line weights, spacing scaling and the page-edge skip.
- **Infinite canvas support**: `BackgroundShader` gains `MODE_SOKKI`, computing the three offsets per
  period in the fragment shader. The zoom subdivision is suppressed for this mode alone — 速記 paper
  already subdivides itself, at a ratio that is not a half, so fading a half-period level in would
  put a rule where the paper has none.

### A written X — new launcher icon and launch animation

- **The mark is now written rather than constructed.** Two brush strokes, the first dominant, both
  bowed, crossing above centre and carrying on past the end; filled outlines rather than a stroked
  path, because the width varies along each leg. Chosen off rendered comparison sheets (brush nib,
  lifted blue ruling, three bands), inked in `#FFFF00`.
- **The adaptive icon's background layer is the 速記 paper**, not flat black — deliberately in step
  with `PagePattern.SOKKI`. Separating paper from mark is also what makes launcher parallax read
  correctly. Ruling colours `#2E4EFF` / `#192A8C`.
- **The monochrome layer drops the ruling** on purpose: Material You flattens it to one tint, which
  would fuse paper and ink into a striped tile with the X lost inside it.
- **All 24 launch frames re-cut from the same geometry**, and they now *write* the mark: stroke one
  over frames 0–7, stroke two crossing it 8–14, held 15–20, dimmed 21–23. Each rule fades out
  towards both ends of the frame, so the 540 px square does not betray itself against the fullscreen
  stage, and the paper does not fade with the ink, which is what makes the loop wrap seamlessly.
- **Splash no longer cut off mid-stroke.** `MIN_LOADER_MS` was upstream's flat 600 ms, tuned for a
  glitch loader whose X completed almost immediately; ours is not written until 1005 ms, so a fast
  session restore tore the splash away with the second stroke half-drawn. It is now derived from the
  frame timing (`LOADER_FRAME_MS`, `LOADER_WRITTEN_FRAME`), so re-cutting the frames cannot silently
  desynchronise the two. The 280 ms fade-out lands inside the hold window, so the mark stays complete
  and undimmed while it dissolves.
- **Assets are generated, not drawn.** `tools/icon/` holds the stroke model (`gen.py`) and the two
  emitters; re-running them reproduces every shipped byte of the launcher set and the frames.

### Floating surfaces you can see

- **`ui/SokkiSurfaces.kt`** — `SokkiAlertDialog`, `SokkiDropdownMenu` and `SokkiSnackbar`, thin
  wrappers that add a border, the container colour and the shape. Material separates a dialog from
  the page with a lighter surface and a shadow; the house palette makes `bg`, `surface` and `menuBg`
  all black, so that separation collapsed and a confirmation prompt arrived as black text on black
  with no edge anywhere.
- **10 dialogs and 29 dropdowns/popups converted**, upstream's and the fork's alike — delete
  note/folder, share, unsaved changes, presentation, add-to-panel, clear page, every tool config,
  styles, view, zoom, colour picker, page jump, waypoints, canvas context menu, backstage menus.
- **Three hand-rolled border blocks removed** from `SokkiExportImport` and `SokkiPickers`, so there
  is one definition rather than four.
- **Border colour and width come from the 白い熊 速記 UI page's own slots**, so those controls now
  drive every floating surface rather than only dividers.
- **Snackbar fixed the other way.** The theme never set Material's `inverse*` roles, so it fell back
  to the baseline and drew as a light grey card in a black app; those roles are now palette-derived
  in both the dark and light schemes.

### Fixes

- `FakeRenderer` records polyline geometry and pen alongside the op name, so ruling tests can assert
  *where* a line landed rather than only that one was drawn.
- `__pycache__/` and `*.pyc` gitignored for the new `tools/` scripts.

## 0.8.10+005 — 2026-08-04

First published release, on upstream **v0.8.10** (`versionCode` 47). Everything below is what this
fork adds to, or changes from, stock xnotes.

### Pen pressure — a measured input band and a settable response

- **Input band.** `StrokeEngine.normalizePressure(p, low, high)` stretches the slice of the stylus's
  reported 0–1 range the hand actually uses back across the full range, **before** the response
  logistic sees it. Previously the raw value went straight into a curve centred on 0.5, so a pen
  reporting (say) 0.05–0.45 while writing sat on the curve's flat lower rail — where the response
  compresses the swings it exists to open up.
- **Settable curve steepness.** The logistic's `k`, previously the fixed constant `PRESSURE_CURVE_K
  = 8.0`, is now per-tool. Higher steepens the middle and clips the rails nearer thin and thick.
- **Three new `ToolConfig` fields** — `pressureLow`, `pressureHigh`, `pressureCurve` — defaulting to
  `0.0` / `1.0` / `8.0`, which is the exact pre-band behaviour. An upgrade changes nothing until the
  pen is calibrated.
- **Round-trips everywhere**: settings JSON, `.xnote` (`DocumentCodec`) and `.xcanvas`
  (`CanvasCodec`). Both codecs write the three keys **only when off-default**, so an untouched note's
  manifest stays byte-stable, and strokes written before this reload with the identity band.
- **The band is style, not global state** — copied into the stroke at pen-down like every other
  `ToolConfig` field, so a note reopens as drawn even after the pen is recalibrated.
- **Calibration pad** (`ui/SokkiPressure.kt`) in the settings page's new *Pen pressure* section: it
  draws through the app's own `StrokeEngine` and `AndroidRenderer` with the very config being edited,
  so the ink in the pad is the ink on the page, and moving a slider rebuilds the strokes already
  drawn. It reports current / min / max / p5 / p95 and a sample count from a fixed 1000-bucket
  histogram, so percentiles cost a constant sweep rather than a growing sort.
- **Stylus-only measurement.** A finger can draw in the pad but contributes no statistics — it
  reports a constant 1.0 or a contact-area proxy on most devices, which would poison the percentiles
  the band is set from.
- **One-tap calibration**: *Use the measured band* sets Light and Hard from p5–p95 across all four
  pressure tools (pen, calligraphy, speed, taper), since the band belongs to the pencil and the hand.
- **Per-tool overrides**: LIGHT, HARD and CURVE sliders added to every pressure tool's own config
  popup, beneath PRESSURE and SENSITIVITY. The two band sliders shove each other along rather than
  jamming, keeping a minimum usable span.
- **Symptom-to-slider reference** closing the section, under the controls it describes.
- Measured effect at the pen's defaults: a hard press reached 46% of the configured WIDTH, for a
  thick:thin ratio of 3.8:1; banded to the measured span it reaches the full WIDTH at 7.8:1.

### The 白い熊 速記 UI page

- New backstage page (`BackstageView.SOKKI_UI`), reached from the sidebar or by **long-pressing the
  Preferences cog**, in the kxkb page format: headings underlined only as wide as their own text, a
  thin hairline opening each section, rows indented 24dp per level, and row padding that is itself a
  setting — so the page's density previews itself.
- **Eleven colour slots** (background, panel, surface, surface-high, menu background, paper, paper
  border, text, dim text, accent, border), each picked with four RGBA sliders over a live swatch,
  alpha included, with one-click boxes prefilled from the recent-colour store the ink palette already
  kept. The theme keeps its own 8-digit hex round-trip rather than widening the 6-digit one the
  `.xnote` files depend on.
- **Typography**: font, text size and weight. Fonts reuse the existing `FontCatalog` — bundled
  families, generic tokens and user-imported files alike, so there is no second font store — each
  option drawn in its own glyphs, and the choice drives `MaterialTheme.typography` app-wide.
- **Shape & lines**: border thickness (down to 0) and corner roundness (down to 0).
- **Icons & density**: icon size and row spacing.
- `applyTo()` repaints upstream's computed `Palette` rather than replacing it, so the appearance
  modes and Material You paths still work underneath; a master switch returns the stock chrome
  without losing the edits.
- Every section carries a live preview drawn with the values being edited.

### Export / Import, and the 保存復元 contract

- **Nine backup categories**: UI theme, preferences, tools & toolbars, view defaults, new-note text
  and page defaults, explorer, presentation server, imported fonts, imported code theme.
- **One export engine** (`SokkiBackup`) with `manifest.json` plus a per-category JSON and the
  font/code-theme file stores. Backups are `shiroikuma-sokki_<stamp>.zip`, written `.part` and
  renamed only once closed, so a killed export cannot leave something a restore would trust.
- **Kōjiki-format panel**: one bordered rounded box, the backup folder as its own box (warn-red until
  set, including on the page row), the folder queried for its newest backup on open, a flat checklist
  of the nine categories, and an ArcaneChat button bar — Cancel alone left, Import and Export grouped
  right, fully round pills. Success closes dialog, panel and page; failure leaves it open to fix.
- **保存復元 automation** per jiyusagyoban's spec: an exported receiver for `EXPORT_STATE` /
  `LIST_CATEGORIES` / `CANCEL_EXPORT` with no `android:permission` — the token is the gate — handing
  off to a `dataSync` foreground service, because a manifest receiver that overruns the broadcast
  window is an ANR against us, killed mid-write.
- Fresh-broadcast replies with `FLAG_INCLUDE_STOPPED_PACKAGES`, exactly one terminal reply behind an
  `AtomicBoolean`, and progress carrying the category id and real counts.
- **Token**: 24 `SecureRandom` bytes, compared constant-time, in its own preferences file that no
  export category touches. Copy and regenerate from the settings page.
- **SAF-only**: `MANAGE_EXTERNAL_STORAGE` is deliberately not declared, since the app advertises
  needing no broad storage permission. An automation `path` override is honoured only when all-files
  access happens to be held, else the configured SAF folder is used, else `ERROR:no-storage-access` —
  the fallback the contract itself specifies.

### Identity & branding

- **Launcher icon**: a clean, even X — two identical bars crossing, mirrored on both axes. Upstream's
  glitch X is irregular by design (its right side carries a ghost bar merged into the leg, its
  top-right arm a step), so it is rebuilt from the one clean limb, the bottom-left leg: 9.0 units
  thick perpendicular to its own axis, edges at slope 0.66, shared by all four legs. Vector adaptive
  foreground plus a monochrome variant for themed icons, with raster fallbacks regenerated at all
  five densities.
- **Launch animation**: all 24 frames redrawn — the mark writes itself on over 16 frames, holds, then
  fades, so the loop restarts without a cut.
- **De-branding sweep**: About pane (our repo/issues/licence links; Sponsor and F-Droid links
  dropped), both sidebar wordmarks, the presentation page title, the clipboard labels, and the "not a
  … document/canvas" errors. The name is read from `app_name` wherever possible, so no second literal
  can drift from the label.
- Kept deliberately as infrastructure rather than branding: the `com.xnotes` namespace, the `.xnote`
  format and its XML namespace, `libxnotests`, the `xnotes.gl` log tags and the `Xnotes*` Kotlin
  symbols. Upstream's fastlane and F-Droid metadata is left untouched.

### Packaging & build

- **applicationId** `shiroikuma.sokki` (namespace `com.xnotes` kept, so rebases stay small); app
  label 白い熊 速記. Installs side-by-side with upstream.
- **Fork version tail**: `versionName "<upstream>+NNN"`, `versionCode <upstream code> * 10000 + N`,
  both derived from upstream's own literals so a rebase brings the new base in by itself. The counter
  is zero-padded to three digits so builds sort in order.
- **`BUILD_NUMBER`** in `gradle.properties`, bumped by the new `buildFork` task, reset to 1 on every
  upstream sync.
- **arm64-v8a only** instead of upstream's three ABIs — the vendored tree-sitter libraries dominate
  the APK and only this one runs on the target device.
- **Release signing** reuses upstream's own `keystore.properties` mechanism; `buildFork` assembles
  the signed release, copies it to `~/tmp` as `shiroikuma-sokki_<version>_arm64-v8a.apk` and bumps
  the counter.
- Upstream tracked by **release tag**, not branch tip, so every base is a state upstream itself
  called finished. `custom` is the default branch and carries all fork work; `master` mirrors the
  newest upstream tag.
- `CLAUDE.md` and `.claude/` un-ignored, so the fork's documentation travels with the repo.

### Fixes

- `DocumentCodecTest`'s golden manifest asserted `writer: 43` after the 0.8.10 base bump moved
  `DocumentCodec.WRITER` to 47, leaving the unit suite red. Corrected — 769 tests pass.
