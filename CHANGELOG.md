# Changelog

All notable fork changes on top of upstream [xnotes](https://github.com/shardulvs/xnotes-android).
Versions read `<upstream version>+NNN`, where `NNN` counts our builds on that upstream base.

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
