# CLAUDE.md — guide for Claude Code in this repo

**shiroikuma-sokki** — 白い熊's fork of [xnotes](https://github.com/shardulvs/xnotes-android), a
handwriting-first notebook for pen and stylus (Kotlin + Jetpack Compose, a custom GLES 3.0 canvas,
vendored tree-sitter in C). Renamed to `shiroikuma.sokki` / **白い熊 速記** so it installs
side-by-side with upstream.

This repo (`ShiroiKuma0/shiroikuma-sokki`) is a real GitHub fork of `shardulvs/xnotes-android`. We
track upstream **release tags** on `master` and layer our customizations on `custom`.

## Read this first

Before any work, read **`.claude/skills/build-apk/SKILL.md`** (canonical build + delivery) and
**`.claude/skills/upstream-new-version/SKILL.md`** (upstream sync + rebase, with the mandatory
proceed-gated upstream-changes table). Publishing a release uses the **global** `/publish-version`
skill — this repo has no local copy.

## Fork workflow — READ THIS FIRST

### Git remotes & branches

- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-sokki` (push here).
- `upstream` → `https://github.com/shardulvs/xnotes-android` (fetch only; its push URL is `DISABLED`).
- `master` — mirrors the newest upstream **release tag**, no fork work. Named `master` to match
  upstream's own branch name (白い熊, 2026-08-04).
- `custom` — all our work, and the GitHub default branch so the repo page lands on the fork.

**Upstream tracking: release TAGS, not the branch tip** (白い熊, 2026-08-04). xnotes tags every
release (`v0.8.10`, `v0.8.9`, …) and bumps its `versionCode` with each one, while `master` keeps
moving in between at ~130 commits/month. Basing on tags means every base is a state upstream itself
called finished, and the upstream version literal really moves on each sync. So a sync happens when
a **new tag** appears, not when commits land on `upstream/master`. Because the base version
therefore always changes on a sync, the global **`/git-versioning`** skill does **not** apply here —
we use the plain `+NNN` versionName.

### Our customizations (install identity + build)

| What | Value | Where |
| --- | --- | --- |
| applicationId | `shiroikuma.sokki` | `app/build.gradle.kts` → `defaultConfig` |
| namespace (R/BuildConfig pkg) | `com.xnotes` (**never rename**) | `app/build.gradle.kts` |
| App label | `白い熊 速記` | `app_name` in `app/src/main/res/values/strings.xml` |
| App icon | black-yellow traced line-art (yellow `#FFFF00` on black) | `mipmap-anydpi-v26/ic_launcher*.xml`, `drawable/ic_launcher_sk_foreground.xml`, `mipmap-*/ic_launcher*.png` |
| Launch loader | our traced X animating on black, 24 frames | `drawable/xnotes_loader.xml`, `drawable-nodpi/xnotes_frame_*.png` |
| Version tail | `versionName = "<upstream>+NNN"`, `versionCode = <upstream code>*10000+N` | `app/build.gradle.kts` fork blocks |
| ABI | `arm64-v8a` only (upstream ships three) | `app/build.gradle.kts` → the second `ndk { }` block |
| Signing | gitignored `keystore.properties` → `~/.android-keystores/shiroikuma-sokki.jks` (alias `sokki`) | `app/build.gradle.kts` (upstream's own signing block reads it) |
| De-branding | our name + our GitHub links everywhere user-visible | `values/strings.xml`, `ui/AboutPane.kt`, `ui/Backstage.kt`, `platform/PresentationServer.kt` |
| 白い熊 速記 UI page | the whole house look, settable + live-previewed | `ui/SokkiUiPane.kt`, `ui/SokkiPickers.kt`, `settings/SokkiUi.kt`, `ui/theme/UiFonts.kt` |
| Export / Import | category ZIP + the Kōjiki-style panel | `ui/SokkiExportImport.kt`, `settings/SokkiBackup.kt` |
| Pen pressure | measured input band + response curve, per tool | `ui/SokkiPressure.kt`, `core/stroke/StrokeEngine.kt`, `core/tools/ToolConfig.kt` |
| 保存復元 automation | token-gated receiver + foreground service | `automation/` |

### The 白い熊 速記 UI page

`BackstageView.SOKKI_UI` — reached from the sidebar, or by **long-pressing the Preferences cog**.
Built in the kxkb page format: 20sp bold headings underlined only as wide as their own text, a thin
hairline opening each section, rows indented 24dp per level, and row padding that is itself a
setting (tight by default). Sections: Export/Import · Pen pressure · Theme · Colours · Typography ·
Shape & lines · Icons & density, each with a live preview.

- **`settings/SokkiUi.kt`** holds every attribute with its black-yellow default, and `applyTo()`
  repaints upstream's computed `Palette`. Upstream's appearance modes and Material You paths are
  untouched underneath — the master switch turns our layer off and stock chrome returns.
- **Colours** are picked with four RGBA sliders over a live swatch, with one-click boxes above them
  prefilled from `Settings.recentColors` (the store the ink palette already kept).
- **Fonts** come from the existing `FontCatalog` — bundled families, generic tokens and
  user-imported files alike — so there is no second font store. `UiFonts` only wraps the Android
  `Typeface` for Compose, and the choice drives `MaterialTheme.typography` app-wide.

### Pen pressure — the input band

A stylus reports only a slice of `MotionEvent.getPressure()`'s 0..1 while writing, and
`StrokeEngine`'s response logistic is centred on 0.5 — so an uncalibrated pen writes on the curve's
flat lower rail, where the response *compresses* the swings it exists to open up. `ToolConfig` therefore
carries `pressureLow` / `pressureHigh` (the band, stretched back over 0..1 by
`StrokeEngine.normalizePressure` **before** the curve) and `pressureCurve` (the logistic `k`).

- **Defaults are the identity** — `0.0` / `1.0` / `8.0`, exactly the pre-band behaviour. Both codecs
  write the three keys only when they are off-default, so notes drawn before this reload byte-identical.
- **The band is style, not global state**: copied into the stroke at pen-down like every other
  `ToolConfig` field, so a note reopens as drawn even after the pen is recalibrated.
- **Measure it, never guess it.** The UI page's pad (`ui/SokkiPressure.kt`) draws with the very config
  being edited — the app's own `StrokeEngine` and `AndroidRenderer`, not a lookalike — and reports
  p5–p95 from a 1000-bucket histogram of stylus samples. Finger samples are drawn but never measured.
- The page writes all four pressure tools (PEN, CALLIGRAPHY, SPEED, TAPER) at once, since the band is
  a property of the pen and the hand; each tool's own popup keeps a per-tool override.
- Ratio ceiling: thick:thin is capped at `1 / pressureMinFactor`, and `sensitivityToMinFactor` bottoms
  out at `m = 0.1` — so 10:1 is the most the current sliders can express. Widening it means changing
  the `0.9` constant in `ToolConversions`, not the band.

### Backup, and the 保存復元 contract

`SokkiBackup` is the one export engine: nine categories, `manifest.json` + `<id>.json` per category
plus the font/code-theme file stores, written `.part` and renamed only when complete. The panel and
`automation/StateExportReceiver` are both thin callers of it — **never duplicate export logic into
the receiver**. The receiver gates on `AutomationAuth` and hands off to `StateExportService`
(foreground, `dataSync`), because a manifest receiver that overruns the broadcast window is an ANR
against us, killed mid-write. The token lives in its own prefs file and is in **no** export category.

**We stay SAF-only.** The app advertises needing no broad storage permission, so `MANAGE_EXTERNAL_STORAGE`
is deliberately not declared: an automation `path` override is honoured only if all-files access
happens to be held, else the configured SAF folder is used, else `ERROR:no-storage-access` — which
is the fallback the contract itself specifies.

### Versioning & APK naming

- The upstream base lives in `app/build.gradle.kts` as upstream's own `versionCode = 47` /
  `versionName = "0.8.10"` literals inside `defaultConfig`. Our fork lines sit **immediately after**
  them and multiply/append, so a rebase brings the new base in automatically. **Never hand-edit
  those two literals.**
- `BUILD_NUMBER` (in `gradle.properties`) is our per-build `N`:
  `versionName = "<upstream name>+<N zero-padded to 3>"` (e.g. `0.8.10+001`),
  `versionCode = <upstream code> * 10000 + N` (plain integer, e.g. `470001`).
  The `buildFork` task bumps `BUILD_NUMBER` after every successful build; `/upstream-new-version`
  resets it to `1` on every sync, so `+N` always reads as "our Nth build on this upstream base".
- APK: `shiroikuma-sokki_<versionName>_arm64-v8a.apk`, copied to `~/tmp/`. The suffix is honest
  here — the app has native code and we ship arm64 only.

### Build commands

```bash
# Our build: signed release → ~/tmp + bump BUILD_NUMBER (use this)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew buildFork < /dev/null
# Release APK only (no copy / no bump)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew :app:assembleRelease
# Unit tests (65 files, pure JVM)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew :app:testReleaseUnitTest
```

The `debug` build type is upstream's (`.debug` applicationId suffix, so it installs alongside our
release) — we build and ship `release` only.

**Gradle must run unsandboxed** (`dangerouslyDisableSandbox: true`): the sandbox mounts
`~/.gradle` read-only, and the wrapper dies on its own lock file before the build starts.

### Toolchain

- Gradle **8.13** (wrapper) runs on JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`); the host default
  `java` is JDK 11 and cannot run it, so always set `JAVA_HOME`. The project compiles to Java 17
  (`sourceCompatibility` / `jvmTarget`).
- Android SDK at `~/android-sdk` via the gitignored `local.properties`; `compileSdk 36`, `minSdk 26`.
  AGP 8.12.3, Kotlin 2.2.21.
- **Native build**: `externalNativeBuild` CMake **3.22.1** over `app/src/main/cpp` (vendored
  tree-sitter + 8 grammars), NDK pinned to **27.0.12077973**. Both are installed here. The pins
  exist for F-Droid reproducibility — do not float them.
- Release is minified + resource-shrunk (upstream's setting; leave it). If a Compose class
  disappears at runtime, the fix is a keep rule in `app/proguard-rules.pro`, not disabling R8.
- `tasks.configureEach { if (name.contains("ArtProfile")) enabled = false }` is upstream's
  F-Droid-reproducibility workaround — leave it.

## Architecture (upstream xnotes)

Single `:app` module, `com.xnotes` namespace, no DI framework. Compose throughout for chrome, with
the drawing surfaces on custom Views (GLES + Android Canvas).

| Area | Where |
| --- | --- |
| Entry point, top-level app shell | `MainActivity.kt` |
| Engine-agnostic core: strokes, geometry, model, history, text flow, tools | `core/` (`stroke/`, `geometry/`, `model/`, `history/`, `text/`, `tools/`, `infinite/`, `pal/`, `util/`) |
| Paged notebook canvas (Android Canvas renderer) | `canvas/` |
| Infinite canvas (GLES 3.0: shaders, tessellation, LOD, glow) | `gl/` |
| Compose UI — backstage/explorer, editors, toolbars, popups, About | `ui/` |
| Android-side platform services: PDF (PdfBox), fonts, thumbnails, storage, tree-sitter JNI | `platform/` |
| `.xnote` / canvas / flow-XML codecs | `format/` |
| Live presentation server (streams the canvas over LAN) | `platform/PresentationServer.kt`, `presentation/` |
| Preferences + settings repository | `settings/` |
| Vendored tree-sitter + grammars (C), highlight queries | `app/src/main/cpp/`, `app/src/main/assets/scm/` |
| Bundled fonts (19 families) | `app/src/main/assets/fonts/` |
| Unit tests (65 files, plain JUnit — no Robolectric) | `app/src/test/` |

**`com.xnotes`, `.xnote`, `libxnotests` and the `xnotes.gl` log tags are infrastructure, not
branding.** The namespace is the R/BuildConfig package, `.xnote` is the on-disk document format
(files 白い熊 already has must keep opening), `libxnotests` is the JNI library name, and the log tags
are invisible to users. `XnotesIcons` / `XnotesTheme` / `XnotesLoader` are internal Kotlin symbols —
renaming any of these buys nothing and makes every rebase a mass-conflict. **Branding is what a user
reads or sees**: the app label, the sidebar wordmark, the About pane, the launch animation, the
presentation page title, and every `github.com/shardulvs` link.

## Hard rules

- **Build proactively** after any coherent code change — never ask "shall I build?" — and deliver via
  the global `/after-build` skill. Delivery goes to exactly ONE target.
- **Never commit/push unprompted.** Wait for 白い熊's explicit "Push". `custom` is rebased on every
  upstream sync, so it pushes with `git push --force-with-lease origin custom`.
- **`/upstream-new-version` must show the proceed-gated upstream-changes table before rebasing.**
  This is a standing requirement, not a nicety — see the skill.
- **Never rename the `com.xnotes` namespace**, and never change the `.xnote` format identifier.
  Renaming would make every rebase a mass-conflict; changing the format would orphan existing notes.
- `keystore.properties`, `*.jks` and `local.properties` are gitignored — never commit them.
- **Never run `adb` inside the sandbox** — always `dangerouslyDisableSandbox: true`, or `adb devices`
  reports no device. Disconnect wireless adb at the end of every delivery batch.
- **Never delete an APK from the phone.** Push the new one and leave every earlier one in
  `/sdcard/tmp/`.
- Upstream's F-Droid reproducibility machinery (pinned NDK/CMake, the dropped ART profile, the
  `dependenciesInfo` block) stays — it costs us nothing and removing it would conflict on rebase.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer, nor a "🤖 Generated with Claude Code" /
Anthropic-attribution line, to commit messages or PR bodies in this repo. End the message at the
last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
