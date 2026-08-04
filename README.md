<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 速記 icon" />

# 白い熊 速記

**A handwriting-first notebook for pen and stylus, tuned for shorthand.**

A fork of [xnotes](https://github.com/shardulvs/xnotes-android) with **major additions**: a
measurable pen-pressure response, a fully settable house theme, and a nine-category backup with
token-gated automation.

Installs **side-by-side** with upstream (app id `shiroikuma.sokki`).

**📥 Latest release: [`0.8.10+005`](https://github.com/ShiroiKuma0/shiroikuma-sokki/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-sokki/releases)

<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
<img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />

</div>

---

## 🖋 Pen pressure you can actually measure

A stylus reports only a slice of Android's 0–1 pressure range while writing, and the stroke engine's
response curve is centred on the middle of that range — so an uncalibrated pen spends its life on the
curve's flat lower rail, where the response *compresses* the very swings it exists to open up. That
is why pressing harder gives a line that never quite gets fat.

This fork adds an **input band**: the slice your hand really uses, stretched back across the full
range *before* the curve sees it. And it adds a **pad that measures it** — write on it as you
normally would, and it reports the 5th and 95th percentile of what your pen actually reported, then
sets the band from that in one tap. The pad draws with the app's own stroke engine and renderer using
the very settings you are editing, so the ink in the pad is the ink on the page; move a slider and
the strokes already drawn rebuild under it.

At the stock pen's defaults a hard press reached only 46% of the width you set, for a thick:thin
ratio of 3.8:1. Banded to the measured span it reaches the full width, at 7.8:1 — hairline to fat and
back over a comfortable change of press, which is what shorthand needs.

**Light**, **Hard** and **Curve** are sliders on both the settings page (writing all four pressure
pens at once, since the band belongs to the pencil and the hand) and each tool's own popup (as a
per-tool override). A symptom-to-slider reference sits under the controls, so a stroke that came out
wrong can be diagnosed without leaving the page.

---

## 🎨 The 白い熊 速記 UI page

A settings page for the whole house look, reached from the sidebar or by long-pressing the
Preferences cog. Eleven colour slots, font, text size and weight, border thickness (down to none),
corner roundness (down to square), icon size and row spacing — and **every section previews itself**,
with colour edits landing on the live chrome as you pick them.

Colours are picked with four RGBA sliders over a live swatch, alpha included, with one-click boxes
prefilled from the recent-colour store the ink palette already kept. Fonts reuse the existing font
catalogue — bundled families, generic tokens and your own imported files alike — each option drawn in
its own glyphs, and the choice drives the app's typography rather than this page alone.

It layers over upstream's computed palette rather than replacing it, so the appearance modes and
Material You paths still work underneath, and a master switch returns the stock chrome without losing
a single edit.

---

## 💾 Backup, restore, and 保存復元 automation

Nine logically split categories — theme, preferences, tools and toolbars, view defaults, text
defaults, explorer, presentation server, imported fonts, imported code theme — exported as one ZIP,
written `.part` and renamed only once complete, so a killed export can never leave something a
restore would trust.

The same engine is driven by a token-gated broadcast receiver implementing the 保存復元 contract, so
a backup can be triggered from outside the app. It hands off to a foreground service rather than
answering in the broadcast window, because this app can carry imported fonts and a code theme and is
not bounded by a few seconds. The token is 24 `SecureRandom` bytes, compared constant-time, kept in
its own preferences file that no export category touches.

The app stays **SAF-only** — `MANAGE_EXTERNAL_STORAGE` is deliberately not declared, since it
advertises needing no broad storage permission.

---

## ⬛ Black-yellow identity

The launcher icon is a clean, even X: two identical bars crossing, mirrored on both axes. Upstream's
glitch X is irregular by design, so this one is rebuilt from its single clean limb — 9.0 units thick
perpendicular to its own axis, edges at slope 0.66, shared by all four legs. Vector adaptive
foreground, a monochrome variant for themed icons, raster fallbacks at all five densities.

The launch animation redraws all 24 frames: the mark writes itself on over sixteen, holds, then
fades, so the loop restarts without a cut.

---

## 📦 Packaging

| | |
|---|---|
| Application id | `shiroikuma.sokki` (upstream: `com.xnotes`) — both installable at once |
| Label | 白い熊 速記 |
| ABI | `arm64-v8a` only — upstream ships three, and the vendored tree-sitter libraries dominate the APK |
| Version | `<upstream version>+NNN`, where `NNN` counts our builds on that upstream base |

The code namespace stays `com.xnotes` and the `.xnote` document format is untouched, so notes written
in either build open in the other, and rebases onto upstream stay small.

| Branch | Role |
|---|---|
| `custom` | all fork work — the default branch |
| `master` | mirrors the newest upstream **release tag**, no fork work |

Upstream is followed by release tag rather than by branch tip, so every base is a state upstream
itself called finished.

---

## Built on xnotes

A fork of [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh (app id
`shiroikuma.sokki`, so it coexists with the official build). All of the app — the GLES infinite
canvas, the stroke engine, the `.xnote` format, the vendored tree-sitter highlighting, the
presentation server — is upstream's work; this fork adds to it and repackages it. The code remains
under MIT, same as upstream — see [LICENSE](LICENSE).

## Building

Requires JDK 21 to run Gradle (the project itself compiles to Java 17), the Android SDK, NDK
`27.0.12077973` and CMake `3.22.1` for the vendored tree-sitter build.

```bash
git clone https://github.com/ShiroiKuma0/shiroikuma-sokki
cd shiroikuma-sokki
./gradlew buildFork           # signed release -> ~/tmp, bumps the build counter
./gradlew :app:assembleDebug  # fast, debug-signed, installs alongside the release build
./gradlew :app:testReleaseUnitTest
```

Release signing reads a gitignored `keystore.properties` at the repo root; without it the release
build comes out unsigned.
