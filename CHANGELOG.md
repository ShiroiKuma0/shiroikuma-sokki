# Changelog

All notable fork changes on top of upstream [xnotes](https://github.com/shardulvs/xnotes-android).
Versions read `<upstream version>+NNN`, where `NNN` counts our builds on that upstream base.

## 0.9.0+001 — 2026-10-02

First build on upstream **v0.9.0** (`versionCode` 61), rebased off **v0.8.23** (60). 64 upstream
commits, 1722 files, +747215/−1192 — but 1579 of those files are a newly vendored PDFium tree, so
the real surface is 143 changed files and 49 new Kotlin sources. Upstream's release is almost
entirely PDF work: rendering moved off PdfBox onto PDFium, PDF text became selectable, a text
markup tool arrived, notes gained an in-note search, and exported PDFs now carry real text, links
and bookmarks. All twenty-three fork commits replay onto it; the work below is what the new base
required of them.

### The pressure band now shares a data class with upstream's markup tool

- **`ToolConfig` gained three fields of upstream's own** — `markupMode`, `markupIntensity` and
  `eraseMarkups` — inserted immediately after `highlighterInverse`, which is exactly where the
  fork's `pressureLow` / `pressureHigh` / `pressureCurve` sit. Upstream also closed the data class
  with a new `companion object` holding the markup intensity constants, so the conflict covered the
  class's closing line as well as its parameter list.
- Both field sets are kept, upstream's first, and the companion object is upstream's unchanged.
- The same collision repeated in **`Settings`'s tool-config codec**, in both the writer and the
  reader: `markup_mode` / `markup_intensity` / `erase_markups` land beside `pressure_low` /
  `pressure_high` / `pressure_curve`. Both sets are written and both are read, each still absent
  from the JSON when it holds its default — so notes and configs saved on the old base reload
  byte-identical.
- **Pressure behaviour is unchanged**: the band's defaults are still the identity (`0.0` / `1.0` /
  `8.0`), so an uncalibrated pen draws exactly as it did on 0.8.23.

### One `<queries>` block, two reasons for it

- **Upstream added a `<queries>` element** declaring the `PROCESS_TEXT` intent, so the apps that act
  on selected text ("Translate", "Search") appear in a PDF text selection's menu.
- The fork already had a `<queries>` element, for an entirely different reason: without its
  `<package>` entries the 保存復元 reply broadcast's `setPackage()` fails *silently* on Android 11+.
- Android takes **one** `<queries>` block with all its children, so the two are merged into a single
  element carrying `shiroikuma.oyokanri`, `shiroikuma.jiyusagyoban` and upstream's `<intent>`. Both
  purposes are served and the comment records both.

### PDFium builds for one ABI, because the fork ships one ABI

- Upstream vendors **PDFium at chromium/8076** (revision `8ca5b73`) as 627 C/C++ sources and builds
  a second native library, `libxnotespdf`, beside `libxnotests`. Nothing is fetched at build time,
  keeping the F-Droid build reproducible.
- This arrived in `defaultConfig` as a new `externalNativeBuild { cmake { arguments +=
  "-DANDROID_STL=c++_static" } }` block, landing on the fork's version tail and `arm64-v8a` filter.
  Both are kept: upstream's flag applies, and PDFium compiles for **one** ABI instead of three.
- The shipped library is **4.98 MB** of arm64 code next to tree-sitter's 10.91 MB. With upstream's
  compressed `jniLibs` packaging the APK grows from **20.6 MB to 23.3 MB** — +2.6 MB, not the
  doubling the uncompressed figure suggests.
- `.gitignore` merged cleanly: upstream's `!app/src/main/cpp/pdfium/build/` (PDFium's `build/`
  directory holds vendored headers, not build output) sits alongside the fork's un-ignored
  `CLAUDE.md` and `.claude/`.

### De-branding keeps pace

- Upstream's one new user-visible mention of its own name, `err_pdf_password`, carries the fork's
  name **in both locales**: “That PDF is password-protected, which 白い熊 速記 can’t open.” and its
  Simplified Chinese counterpart.
- No new `github.com/shardulvs` links appeared, so the About pane needed nothing.

### Unchanged by this base

- **`Renderer` grew a `Mark` taxonomy and five new members** for the tagged-PDF export path — all
  of them defaulted, so `AndroidRenderer` compiles untouched and the UI page's pressure pad, which
  paints through it, needed no change.
- Upstream's lift of the toolbar palette from 7 swatches to 15 (`InkPalette.MAX_SWATCHES`) landed
  clear of the fork's hunks; the UI page's colour boxes read `Settings.recentColors`, which is
  untouched.
- The 速記 template, the UI page, the eight-category backup and the 保存復元 automation all replay
  without a conflict.

## 0.8.23+001 — 2026-09-27

First build on upstream **v0.8.23** (`versionCode` 60), rebased off **v0.8.17** (54) — six upstream
releases in one hop, 80 upstream commits, 230 files, +33116/−2668, the largest sync this fork has
taken. Upstream brought the redesigned explorer, Simplified Chinese, dual-tone Material themes,
tables and maths in inline text, page templates, a floating toolbar, a Corners setting, and a round
of canvas and PDF crash fixes. All twenty-two fork commits replay onto it; the work below is what
the new base required of them.

### 速記 is now a page template

- **Upstream replaced the ruling chips with a template system** — an `xtemplate` reader, evaluator
  and painter in 0.8.21, and in 0.8.22 the chip row itself gave way to a template strip. The 速記
  chip had nowhere left to stand.
- The ruling is **rewritten in upstream's own template format** (`assets/templates/sokki.xtemplate`)
  and registered as a fourth built-in beside Lines, Dots and Grid, so it sits in the template picker
  with the rest and a note carries it like any other template.
- **The geometry is unchanged** — a heavy rule at the band's head, hairlines at 25/64 and 49/64 of
  the period, the heavy rule at double weight — and it still persists as `pattern: "sokki"`, so
  every note already ruled this way reopens exactly as drawn.
- The blue arrives as the **template's own colour parameter** rather than a hardcoded pattern
  default: still blue out of the box, now adjustable per note.
- The GLES canvas path is untouched — the infinite canvas still draws 速記 procedurally in the
  shader.

### The UI theme follows upstream's rebuilt palette

- **Upstream dropped the classic palette**, made Material the only one, and added dual-tone themes
  and a contrast slider. `Palette.accentDim` and `ColorMath.dim`/`lighten` ceased to exist — a break
  that never surfaced as a rebase conflict, because the file it lives in is not one we patch.
- `SokkiUi.applyTo` **re-seeds `Palette.materialColors` from our own accent**. Upstream now *derives*
  `onAccent`, the selection pair, `disabled` and `danger` from that scheme instead of storing them,
  so without the re-seed those roles would have kept the stock hue and fought the chrome the UI page
  had just painted.
- `XnotesTheme` carries upstream's corner style and our UI layer together, so the Corners setting
  and the house typography both apply.

### Floating surfaces converge on upstream's own funnel

- **Upstream 0.8.20 arrived at this fork's own idea**: it now shadows material3's `DropdownMenu` and
  `AlertDialog` for every same-package caller, giving each the palette menu surface and a hairline.
- The fork's wrappers are **deleted** and roughly twenty call-site files are **un-patched**. Upstream's
  two wrappers simply draw `sokkiSurfaceBorder()` in place of their fixed 1 dp hairline, which puts
  the UI page's Border colour and Border width back in charge of every dialog, dropdown and popup.
- `SokkiSnackbar` stays, since upstream funnels no snackbar.
- Net effect: the same "one border, everything that floats" behaviour, with far less fork surface to
  carry through the next sync.

### De-branding moved down into string resources

- **Upstream moved its UI text into resources** and added a Simplified Chinese locale, superseding
  the fork's Kotlin-level de-branding.
- `app_name`, `err_not_xnotes`, `help_make_better` and `enjoying_xnotes` now carry the fork's name
  **in both locales**, so no second literal can drift from the label.
- The About pane is rebuilt on upstream's new structure: our repository, issues and licence links,
  with the Sponsor and F-Droid links — which point at upstream's own listings — dropped again.
- The sidebar's UI-page entry is built from `app_name` rather than written out, for the same reason.

### The UI page rejoins the redesigned explorer

- Upstream rewrote home and the file explorer wholesale (grid, gallery, list, columns and timeline
  views, pinned folders, a Trash pane). `BackstageView.SOKKI_UI` now sits alongside upstream's new
  `TRASH`, and the **Preferences cog's long-press shortcut works in both the expanded sidebar and
  the collapsed rail**.
- The fork's duplicate palette icon gave way to the one upstream now ships.

### Pen pressure

- Upstream moved the tool-popup captions into resources and into sentence case; the fork's **Light**,
  **Hard** and **Curve** rows follow, translated into Chinese alongside the rest.
- Upstream raised the default taper tip to 50% and pinned a separate legacy value; the fork's input
  band and response curve sit beside it unchanged.

## 0.8.17+001 — 2026-09-13

First build on upstream **v0.8.17** (`versionCode` 54), rebased off **v0.8.15** (52) — two upstream
releases in one hop (0.8.16 was a single fix), 16 upstream commits, 28 files, +1096/−173. All twenty
fork commits replay onto it; the work below is what the new base required of them, plus the one
commit it asked for.

### The new-canvas background rides along in 保存復元

- **Upstream let a canvas background become the default for every new canvas**, stored as a new
  **top-level** `Settings` key, `new_canvas_background`, beside `new_note_style` and
  `new_note_flow`.
- **`SokkiBackup` copies a category by naming its keys, so a key no category owns is neither
  exported nor imported** — silently, with no error anywhere to notice. The new key joins *TEXT*,
  the category that already holds the other new-document defaults, and the label now says what it
  covers: **New-note & new-canvas defaults (text · page · background)**. The category id stays
  `text`, so the 保存復元 contract's `LIST_CATEGORIES` answer is unchanged and callers need no edit.
- **The other two new settings reach the backup by themselves.** `disable_front_buffering` sits
  inside the `prefs` object, so *Preferences* already carries it; the PDF blend colours (`multiply`,
  `screen`) and the `sepia` strength are view settings inside `view_defaults`, so *View defaults*
  does. Eight categories remain eight.

### The explorer's create button arrives outlined

- **Upstream made the round create button ask what to create.** It used to jump straight to a new
  note; it now opens *New Note · New Canvas · Import PDF*, sharing one item list
  (`NewItemMenuItems`, taller rows with icons) with the toolbar's New menu so the two cannot drift.
- **That collided with the fork's outlined New menu**: upstream replaced the menu body our
  `SokkiDropdownMenu` wraps with a call to the shared helper. The resolution is the wrapper around
  the helper — upstream's rows, our border.
- **The button's own menu is a brand-new stock `DropdownMenu`**, which merged without a conflict and
  would have been the one un-outlined floating surface in the app: black rows on a black page, no
  edge. It takes `SokkiDropdownMenu` like every other, so **twenty-nine outlined dropdowns and popups
  become thirty**, still from the one definition.
- **The share dialog collided too**: upstream inserted a `remember(shareUri)` block — resolving the
  document kind so a canvas is shared under its own extension — on exactly the line where the fork
  swaps `AlertDialog` for `SokkiAlertDialog`. Both are kept: a canvas now leaves as a canvas, through
  the outlined dialog.

### What the new base brings the fork for free (upstream's work)

- **Export a canvas to PDF.** One page cut to fit the drawn content, ink still vector; a single
  place (`PdfItemRaster`) now decides what cannot stay vector and rasterises only that. Notes had
  this; canvases did not.
- **Screen and Multiply colour filters for PDF pages**, in the View menu beside Invert and a Sepia
  strength that keeps going past CSS's 100 — blend the page with a colour for dark reading without
  the photo-negative look. "Off" is the blend's identity colour, so no separate enable flag exists.
- **A "Disable front buffering" preference** for the few panels that do not hand the front buffer
  over cleanly and flicker or lag under the nib; with it on, every stroke takes the ordinary canvas
  path. The fork's pressure band is applied before either path and is unaffected.
- **"Default for new canvases"** in the canvas styles popup, stamping the current background onto
  every canvas made from then on.
- **Wet ink no longer lands away from the pen after the window changed size** (the whole of
  v0.8.16): the GL wet pad rebuilds its surface on resize and hands the in-flight stroke back when
  its pixels are lost instead of drawing it offset.
- **A side-button pan stops exactly where the pen lifts**, instead of gliding on.
- **A settled lasso selection can be grabbed with the pen** on the infinite canvas, instead of being
  dropped in favour of a new stroke.
- **Sharing or copying a canvas out keeps it a canvas**, and the export dialog now says what it is
  actually counting.

### Kept intact across the rebase

- Fork identity — `shiroikuma.sokki`, 白い熊 速記, the `+NNN` version tail on upstream's own
  literals (`versionCode = 54` / `versionName = "0.8.17"`, untouched), arm64-v8a only, our signing —
  and the `com.xnotes` namespace and `.xnote` format untouched.
- The 速記 ruling, the pressure band and its measuring pad, the 白い熊 速記 UI page and its cog
  long-press, `sokkiUi.applyTo` closing `buildPalette`, the outlined floating surfaces, the traced
  black-yellow icon and its 24 launch frames, the backup engine, the token-optional 保存復元
  receiver and the identity-checked data provider.
- **No new upstream branding arrived** — no new "xnotes" in user-visible text, no new
  `github.com/shardulvs` link.
- 1078 unit tests across 89 classes pass on the new base (up from 1054 across 88: upstream added
  `PdfColorFilterTest` and `CanvasPdfLayoutTest`).

## 0.8.15+004 — 2026-09-05

Same upstream base as `+001` (**v0.8.15**, `versionCode` 52). One defect, found by running the
contract against the installed app rather than by reading the code, and fixed in the four places it
lived.

### A refused foreground start was killing the app mid-request

- **`startForegroundService()` from a broadcast receiver is a BACKGROUND start on API 31+.** Unless
  the app happens to hold a foreground-start allowance the framework throws
  `ForegroundServiceStartNotAllowedException`, and an exception escaping `onReceive` **kills the
  process**. Nothing replies, nothing is written, and the caller waits out its whole timeout.
- **Reproduced on 白い熊's Mate XT against this app**, not inferred from a sister app:
  `startForegroundService() not allowed due to mAllowStartForeground false`, thrown at
  `StateExportReceiver.onReceive`, with `dumpsys activity services` giving the reason in the app's
  own record — `infoAllowStartForeground=[… uidState: RCVR; code:DENIED; tempAllowListReason:<null>]`.
- **Four start sites, not one.** The receiver's `startForegroundService` was the reported one; the
  other three were found by reproducing the crash and following the paths out of it:
  `StateExportService.onStartCommand`'s own `startForeground`, which sat **above** the code that
  reads `reply_action`, so a refusal there had nothing to answer with; the same call in
  `AutomationDataService`; and that service's existing guard, which closed the descriptor and
  stopped **silently** while the provider had already told the caller `OK:<job_id>`.
- **Catching it is only half the fix.** A swallowed refusal is a silent no-export, which looks
  exactly like an app that never implemented the contract. Every site now answers with the real
  `ERROR:` line, which 保存復元 renders verbatim on the failed row.

### What this does and does not do

- **It converts a silent hang into a diagnosable error.** That is the whole of it, and it is worth
  having: a red row carrying `ERROR:startForegroundService() not allowed due to
  mAllowStartForeground false` is the first honest output this path has produced.
- **It does not make a cold export work.** The foreground start is still refused; the app now
  reports that instead of dying from it. A backup of 速記 triggered while the app is cold still does
  not happen, and reading this entry as "the backup is fixed" would make the next red row look like
  a regression when it is the opposite.
- **What would actually make it work is not in this build**: a battery-optimisation exemption (速記
  is absent from `dumpsys deviceidle whitelist`), EMUI's 「アプリ起動管理」 → 手動管理 with
  バックグラウンドで実行 on, which no app can set for itself — or, better than either, the caller
  sending the request with `BroadcastOptions.setTemporaryAppAllowlist(…)`, which is one change in
  自由作業盤 rather than a permission grant in every sister app, and the only route that survives the
  clean phone the contract exists for.

### Two testing traps worth recording

- **A bare `am broadcast` after `am force-stop` tests nothing.** It does not set
  `FLAG_INCLUDE_STOPPED_PACKAGES` (flags come out `0x400000`), so a stopped app never receives it —
  producing a convincing silent nothing that looks like the bug and has a different cause. Use
  `am broadcast --include-stopped-packages`, and check the flags read `0x400020`.
- **Fire `LIST_CATEGORIES` first to isolate the fault.** That path starts no service anywhere, so if
  it answers and `EXPORT_STATE` does not, the problem is the foreground start; if neither answers,
  the service code is not where to look.

## 0.8.15+003 — 2026-09-04

Same upstream base as `+001` (**v0.8.15**, `versionCode` 52). This build implements **version 2 of
the sister-app 保存復元 contract**: the token stops being the gate, and a second, authenticated door
is added through which 白い熊 応用管理 can back this app up *with its data* and put it back on a
wiped phone.

### The token stopped being the gate

- **`automation_enabled` now defaults to ON, and the token became opt-in.** A new
  `automation_require_token` preference defaults to OFF, so the app answers the 保存復元 batch out of
  the box. The reason is the clean-phone case: a 48-character secret pasted from this app's settings
  into a caller's cannot survive a wipe, and a gate that only works once the phone is already set up
  is no gate for setting the phone up.
- **A token sent to the app while it is not asking for one is ignored, never refused.** Tokens
  outlive the setting they were pasted for, so refusing a stale one would turn "one switch was
  turned off" into "half the batch mysteriously fails".
- **Both checks now live in a single `AutomationAuth.refuse()`**, returning either `null` or the
  exact `ERROR:` line to answer with. Written once, because two checks spelled out separately in a
  receiver, a provider and a service is how `automation disabled` and `bad token` drift apart.
- **The UI page gained a 「Use authorization token?」 switch**, and the token row is now shown only
  when that switch is on — a secret sitting under an off switch only invites pasting it somewhere it
  will do nothing. The master switch stays, because it is the only way to close the app off
  entirely.

### A data door that knows who is calling

- **A `ContentProvider` at `shiroikuma.sokki.automation`**, exported with no permission, answering
  `describe`, `export`, `import` and `cancel`. A broadcast cannot tell you who sent it; a provider
  gets the caller's identity from the framework, which is what makes removing the token safe rather
  than merely convenient.
- **The caller is checked three ways**: an exact package name from a two-entry map — never a prefix,
  since any sideloaded app may call itself `shiroikuma.evil` and pass one — then the uid the kernel
  reports, then the SHA-256 of the signing certificate against a pin. The certificate check is the
  one that matters on a clean phone, where a caller package that is not installed yet is a name
  anyone can take.
- **The payload moves through a caller-supplied `ParcelFileDescriptor`**, not a path and not a URI.
  応用管理 writes into a temporary path and renames on commit, and encrypts and checksums per file it
  knows about, so a file this app dropped into that directory itself would be renamed out from under
  it and would sit in plaintext inside an otherwise encrypted backup. The descriptor is duplicated
  before it leaves the binder call and closed in a `finally`.
- **`import` exists only here.** It never gets a broadcast action: an import overwrites the app's
  data, and the export receiver is exported without a permission.
- **The work runs on a foreground service** (`AutomationDataService`, `specialUse`), and a restore
  spools the archive to the cache directory rather than reading it into the heap — this app's
  archive carries every imported font file, so a large one is measured in tens of megabytes.
- **Manifest capability discovery**: `shiroikuma.automation.contract` = 2, `.format` = 1 and
  `.min_format` = 1, readable without waking the app, so 応用管理 can answer "can this be backed up"
  for an app that is currently frozen.

### The reply that was never heard

- **The manifest had no `<queries>` element at all**, and now names both `shiroikuma.oyokanri` and
  `shiroikuma.jiyusagyoban`. Without it a reply broadcast's `setPackage()` fails **silently** on
  Android 11+: every 保存復元 reply this fork has ever sent was discarded, so the export ran, wrote
  its ZIP correctly, and was never heard of. The contract has been implemented here since
  `0.8.10+005`; this is the build in which it first actually answers.
- **Progress broadcasts now require both `progress_action` and `reply_package`** — in the new data
  service and in the pre-existing export service, which had the same defect. Since API 26 an
  implicit broadcast is not delivered to a manifest-declared receiver at all, so a progress line
  without `setPackage` does not arrive weakly; it does not arrive.

### What the archive still does not hold

- **The notes themselves are not in the backup, and this build does not add them.** The eight
  categories are settings, imported fonts and the code theme; the `.xnote` notebooks live under the
  SAF tree picked in the explorer. A clean-phone restore therefore returns a fully configured 速記
  with an empty library.
- **The `describe` header says so verbatim.** Its `contains` list ends with an explicit line stating
  that the handwritten notes are not included and where they live, because 応用管理 renders those
  strings unchanged and a row that implied otherwise would be the most expensive kind of wrong.
  Covering the notebooks needs its own design decision, not a wider export.

## 0.8.15+001 — 2026-09-03

First build on upstream **v0.8.15** (`versionCode` 52), rebased off **v0.8.13** (50) — two upstream
releases in one hop, and the largest base change the fork has taken: 68 upstream commits, 76 files,
+5554/−1449. All fourteen fork commits replay onto it; the work below is what the new base required
of them, plus the one commit it forced.

### Upstream removed live presenting, and the backup lost a category

- **The whole feature is gone from the base.** v0.8.14 deleted `PresentationServer`,
  `PresentationController`, `PresentationFrameSource`, `PresentationDialog`, the toolbar's
  `PRESENT` item and the `presentation` settings block, and dropped `INTERNET` with them. Upstream's
  own store copy now says the app has *no network access at all*.
- **Two fork patches went with the files rather than being ported.** The de-branded viewer page
  title (`白い熊 速記 presentation`) and the `SokkiAlertDialog` restyling of the presentation dialog
  were changes to a feature that no longer exists; both delete/modify conflicts resolve to the
  deletion. There is nothing left in the fork to de-brand there.
- **`SokkiBackup.Cat.PRESENTATION` is dropped, and nine categories become eight.** It owned exactly
  one key, `presentation`, which upstream deleted from `Settings`, so it would have exported an
  empty category and imported nothing.
- **This is a 保存復元 contract change, not a tidy-up.** The category list *is* the contract's
  `LIST_CATEGORIES` answer, so an automation caller asking for `presentation` now gets nothing back
  rather than an empty payload. Callers selecting categories explicitly should drop it from their
  list; the default set is unaffected apart from being one shorter.
- **The manifest keeps our three permissions and loses upstream's.** `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS` are still ours — the export service needs
  them — but `INTERNET` goes: the automation export is local broadcasts and SAF, and never wanted a
  socket. The fork is now as close to upstream's no-permissions claim as the contract allows.

### The pressure band came through a rewritten codec intact

- **Upstream rewrote both codecs for speed**, which is where the fork's three keys live. Samples now
  go out through a fixed-point fast path (`JsonWrite.samplePoint`) and come back through raw-array
  reads (`JsonPull`, `RawSamples`); deflate is pinned at level 1, the manifest is written last, asset
  checksums are remembered rather than re-read (`AssetCrc`), and a stroke added to a PDF note
  replaces its manifest entry in place instead of rewriting the container (`ZipTail`).
- **The rewrite moved the sample path but left the config object an ordinary key sequence**, so
  `pressure_low`, `pressure_high` and `pressure_curve` — and their three parse arms — port onto the
  new shape unchanged in both `DocumentCodec` and `CanvasCodec`. This was checked by reading the
  merged `writeStroke`, not inferred from a clean merge.
- **`Stroke` froze its samples into one immutable tuple** (+329 lines changed) and the band's
  pass-through to the wet ribbon follows it into the restructured methods. `StrokeEngine` and
  `ToolConfig` — where the band is actually applied — upstream never touched.
- **The byte-compatibility promise still holds.** All three keys are written only when off-default,
  so a note drawn before any calibration serialises exactly as it always did, on a codec that is
  otherwise substantially faster.

### What the new base brings the fork for free

- **Wet ink is drawn into the front buffer** the screen is scanning out, so the line stays under the
  nib instead of waiting a frame — the largest latency change the app has had, and it lands under
  the fork's own pressure band unmodified.
- **Ink no longer blinks between strokes**: a second stroke joins the wet pad rather than wiping it,
  and a stale release can no longer clear the pad under a new one.
- **Saving is off the main thread and far faster** on a large note, and **opening a big handwriting
  note is several times faster**.
- **Undo repairs only the regions its command touched** instead of hanging on a dense note, and the
  undo stack is bounded at 200 commands, which fixes an out-of-memory on save.
- **Fixes**: a crash closing a PDF note mid-render, a canvas autosave silently dropping a write made
  while it ran, a note being overwritten rather than forked when a sync app moved it underneath, and
  the caret snapping back to the last word on predictive keyboards.
- **Additions**: the lasso and every marquee drawn dashed, Redmi Smart Pen side buttons 1 and 2 with
  their own tap actions, and a set of redrawn toolbar glyphs.

### Kept intact across the rebase

- Fork identity — `shiroikuma.sokki`, 白い熊 速記, the `+NNN` version tail on upstream's own literals,
  arm64-v8a only, our signing — and the `com.xnotes` namespace and `.xnote` format untouched.
- The 速記 ruling, the pressure band and its measuring pad, the 白い熊 速記 UI page and its cog
  long-press, `sokkiUi.applyTo` closing `buildPalette`, the outlined floating surfaces, the traced
  black-yellow icon and its 24 launch frames, the backup engine and the 保存復元 receiver.
- **No new upstream branding arrived** — no new "xnotes" in user-visible text, no new
  `github.com/shardulvs` link, despite two releases' worth of new UI.
- 1054 unit tests across 88 classes pass on the new base.

## 0.8.13+001 — 2026-08-21

First build on upstream **v0.8.13** (`versionCode` 50), rebased off **v0.8.12** (49). All thirteen
fork commits replay onto the new base; the work below is what the new base required of them.

### The 速記 ruling follows the page into its margins

- **Upstream gave pages margins, and rewrote the pattern painter around them.** `paintPagePattern`
  no longer takes the page's width and height: it takes the **paper rect** the ruling fills — the
  whole margined footprint, whose top-left goes negative on a margined edge — plus the sub-rect
  actually being painted, and it anchors the pattern to page-space zero so growing a margin extends
  the ruling outward instead of sliding it under the ink already written on it.
- **The fork's `sokkiLines` is ported onto that shape rather than replayed.** It walks `(bounds,
  clip)`; the bands are still counted from page-space zero — which is exactly what keeps a
  sharp-viewport half-region landing its lines where the whole-page pass puts them — the rules run
  the full width of the grown paper, and the edge skip moves from `y > 0` to `y > bounds.top`.
- **That last move is the behaviour change, and it is the right one**: on a margined page `y = 0`
  stops being the paper's edge and becomes an ordinary band rule, drawn like any other, which is
  precisely how upstream's own `hLines` now treats it. A 速記 page grown at the top carries its
  band upward in step instead of starting a fresh one at the margin.
- `SokkiPatternTest` gains `aMarginIsRuledOutwardAndKeepsTheBandsInStep`: a page grown by one whole
  band on every edge rules −39, −15, 0 (heavy), 25 — the same phase the unmargined page has.
- **`FakeRenderer` collided textually.** Upstream's margin tests record polyline *segments*; the
  fork's ruling tests record whole *polylines with their pens*, because it is the pen width that
  tells a heavy band rule from a hairline. Both recordings are kept.

### A menu leaves the toolbar, a popup arrives on it

- **Upstream dropped the page menu** — Add page / Delete current page — giving its toolbar slot to
  the new Margins button and deleting the `PageMenu` composable outright. The fork's outline swap
  inside it goes with the function; there is nothing left to keep in step.
- **Upstream's new margins popup arrives on stock chrome**, the only floating surface in `Popups.kt`
  not going through the fork's outlined menu, so it takes `SokkiDropdownMenu` like its siblings and
  answers to the UI page's Border colour and Border width. One outlined dropdown leaves with
  `PageMenu` and one arrives with the margins menu, so the count holds at twenty-nine.
- **The long-press canvas menu gained upstream's lock and unlock entries** inside the fork's
  outlined menu, with the early return a locked item takes relabelled to it.

### Kept intact across the rebase

- Verified after the rebase: install identity, ABI filter, the fork version block (upstream's
  `versionCode = 50` / `versionName = "0.8.13"` literals untouched, our lines deriving from them),
  the traced icon and its 24 launch frames, the 白い熊 速記 UI page and its cog long-press,
  `SokkiUi.applyTo` at the end of `buildPalette`, the backup engine and the 保存復元 receiver, and
  the de-branding sweep. The new base brought no new branding with it — no new "xnotes" in
  user-visible text, no new `github.com/shardulvs` link.
- **The new upstream preferences reach the backup by themselves**: `custom_page_width_mm`,
  `custom_page_height_mm` and `hide_page_borders` all sit inside the `prefs` object, so the
  *Preferences* category already carries them. Unlike v0.8.12's `last_tool`, nothing new landed
  outside the nine categories.
- **Page margins are document state, not settings.** They ride the `.xnote` manifest and are written
  only when an edge is set, so a note with no margins still serialises byte-identically and the
  export categories are untouched by the feature.
- Upstream swapped in a new monochrome launcher layer of its own; ours has pointed `<monochrome>` at
  `drawable/ic_launcher_sk_monochrome.xml` since the icon was traced, so the change is inert here
  and resource shrinking drops the unreferenced file.

### What the new upstream base brings (upstream's work)

- **Page margins, per edge**, for all pages or just the current one — extra paper on any side, a page
  inheriting from the document unless it overrides it, applied live and never on the undo stack. The
  ruling extends into it, and thumbnails, screen captures, the presentation stream and PDF export all
  size themselves to the grown page, so ink written in a margin survives the export.
- **An SVG drawn as real vector art on the infinite canvas.** A new `core/vector` package parses,
  flattens, triangulates and meshes a document into GPU-resident triangles, so a placed SVG stays
  sharp at any zoom and a pinch costs it nothing. Gradients become per-vertex colour on a mesh
  subdivided until the interpolation error goes away, text becomes glyph outlines, and rectangular
  clips are applied at mesh time; filters, masks and group opacity are named where used rather than
  dropped in silence.
- **Heavy SVGs rasterize on the GPU, and only the slice on screen.** A `RenderNode`/`ImageReader`
  path on API 29+, chosen per document by timing both backends, with the render bounded by the
  viewport instead of the whole document.
- **Lock an item so nothing can select it** — out of reach of a tap, a band, a lasso, select-all and
  the eraser, with a held finger over it offering only to release it. The flag rides both file
  formats and is written only when set.
- **Disappearing ink on the infinite canvas toolbar.**
- **A stroke carries onto the page the pen walks onto** instead of being cut at the page edge.
- **Legal, and a custom default page size** (10–2000 mm a side) in Preferences, and **the page border
  can now be hidden**.
- Fixes: a tap inside a selection no longer clears it; a page no longer stays blank when a cache
  build is discarded mid-flight; a resized page blanks instead of stretching under the slider; an SVG
  rect's top-right and bottom-left corners no longer arrive with reversed tangents; and a lifted SVG
  no longer re-renders the whole document every few frames.

## 0.8.12+001 — 2026-08-16

First build on upstream **v0.8.12** (`versionCode` 49), rebased off **v0.8.11** (48). All eleven fork
commits replay onto the new base; the work below is what the new base required of them.

### The themed icon is the vector alone

- **Upstream moved its Material You layer to a vector** and deleted the five per-density
  `mipmap-*/ic_launcher_monochrome.png`. Ours has been a vector since the icon was first traced —
  the adaptive XML has always pointed `<monochrome>` at `drawable/ic_launcher_sk_monochrome.xml` —
  so the five PNGs the generator still cut were unreferenced bytes riding along in the APK.
- **The rebase takes upstream's deletion** (the same conflict lands twice, once in each of the
  fork's two icon commits), and `tools/icon/emit_launcher.py` stops re-cutting them, so the next
  icon regeneration cannot resurrect them.
- Upstream's own `drawable/ic_launcher_monochrome.xml` comes in with the base and stays unreferenced;
  resource shrinking drops it, and leaving it in place keeps future rebases small.

### The pressure band and the inverse highlighter share a line

- **Upstream's new inverse highlighter lands on exactly the fork's lines.** It adds a `ToolConfig`
  field and a `highlighter_inverse` key to `CanvasCodec`, `DocumentCodec` and the settings JSON —
  the same four files, and the same six insertion points, that carry `pressureLow` / `pressureHigh`
  / `pressureCurve`. The two are independent fields that merely collided textually, so both sides
  are kept everywhere.
- **The codec write path takes upstream's braced highlighter block** — it grew from a one-liner to a
  block when the inverse flag joined the alpha — **with the fork's off-default-only pressure writes
  after it**. A stroke drawn before any calibration therefore still serialises byte-identically,
  which is the guarantee the band shipped with.

### Kept intact across the rebase

- Verified after the rebase: install identity, ABI filter, fork version block, the traced icon and
  its 24 launch frames, the 白い熊 速記 UI page and its cog long-press, `SokkiUi.applyTo` at the end
  of `buildPalette`, the backup engine and the 保存復元 receiver, and the de-branding sweep. The
  README keeps the fork's story rather than upstream's new store badges.
- **The new upstream settings reach the backup by themselves**: `highlighter_inverse` and
  `fill_alpha` sit inside the tool config, so the *Tools & toolbars* category already carries them,
  and `new_note_name_template` sits in `prefs`, so *Preferences* does. Upstream's `last_tool` is a
  new **top-level** key that no category owns, and so is not exported — it is launch state rather
  than a setting, but it is the first upstream key to fall outside the nine categories.

### What the new upstream base brings (upstream's work)

- **Restyle a finished selection** — lasso strokes already drawn and change their colour and
  thickness in place, as one undoable command.
- **An inverse highlighter** that lightens instead of darkens, for marking up a dark page where a
  multiply has nothing to darken and tints the ink instead.
- **A fill-opacity slider for the shape tool**, so a shape can be filled at a chosen strength rather
  than solid or not at all.
- **New notes named from a template** you set in Preferences, with the field dismissing its focus on
  an outside tap.
- **The last used tool re-armed on launch**, instead of always coming back to the default pen.
- **Pull past the last page to add one** in horizontal scrolling, reachable by a two-finger pan as
  well as one finger.
- **A zoom lock on the infinite canvas**, and its styles menu brought in line with the paginated one.
- Stamps renamed to **stickers** throughout.
- Colour picker fixes: the spectrum square no longer steals touches meant for the hue ring at its
  corners, and the marker no longer jumps away when you pick black.
- Two pages no longer sit off-centre when zoomed out to fit.

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
