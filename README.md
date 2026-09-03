<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 速記 icon" />

# 白い熊 速記

**A handwriting-first notebook for pen and stylus, tuned for shorthand.**

A fork of [xnotes](https://github.com/shardulvs/xnotes-android) with **major additions**: 速記
shorthand paper as a page ruling, a measurable pen-pressure response, a fully settable house theme,
and an eight-category backup with token-gated automation.

Installs **side-by-side** with upstream (app id `shiroikuma.sokki`).

**📥 Latest release: [`0.8.15+001`](https://github.com/ShiroiKuma0/shiroikuma-sokki/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-sokki/releases)

<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
<img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />

</div>

---

## 📐 速記 paper, ruled the way shorthand is written

Stock offers lines, dots and a grid — all of them evenly spaced, because none of them is trying to
be anything in particular. Shorthand paper is not evenly spaced: it is a **band**, opened by a heavy
rule and divided by two hairlines, and the unequal spacing is the point.

**速記** joins the ruling chips as a fourth pattern, reproducing that band from measurements of the
Samsung Notes 速記 template rather than an approximation of it — a heavy rule every 64 px with
hairlines 25 px and 49 px below it. The offsets are held as fractions of the period, so the spacing
slider **scales the whole band** instead of just moving lines apart, and the paper keeps its
proportions at any size. Since 64 px is already the default spacing, the default is the template at
1:1.

Steno paper is blue paper, so the pattern brings its own default colour instead of inheriting the
grey the other rulings use — grey hairlines would not read as the thing it copies. Set it once on
the "All pages" tab with *Use for new notes* ticked and every note you create opens already ruled.

Give a page margins — upstream's extra paper on any edge — and the band carries straight on into
them, counted from the same origin as the rest of the page, so a margined 速記 page gains paper
without the ruling restarting or falling out of step at the seam.

It works on the infinite canvas too, drawn procedurally in the GLES shader so it stays exact at any
zoom — with the zoom subdivision suppressed for this one pattern, because 速記 paper already
subdivides itself, at a ratio that is not a half.

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

Because the house palette makes every surface black, Material's usual way of lifting a dialog off the
page — a lighter surface and a shadow — collapses to nothing, and a confirmation prompt arrives as
black text on black with no edge anywhere. **Every floating surface is outlined instead**: all ten
dialogs, all twenty-nine dropdowns and popups, and the snackbar, from one definition, using this
page's own Border colour and Border width. Those two controls now shape the whole app, not just its
dividers.

---

## 💾 Backup, restore, and 保存復元 automation

Eight logically split categories — theme, preferences, tools and toolbars, view defaults, text
defaults, explorer, imported fonts, imported code theme — exported as one ZIP,
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

## ⬛ Black-yellow identity, written rather than constructed

The launcher icon is an X that was **actually written**: two brush strokes, the first dominant, both
bowed the way a hand bows them, crossing a little above centre and carrying on past the end. Filled
outlines rather than a stroked path, because the width has to vary along each leg.

It sits on the same 速記 paper the app now rules its pages with, so the icon says what the app is
for. Paper and mark are separate layers, which is also what makes the parallax on supporting
launchers read right — the mark slides over the paper, which is what it is. The monochrome layer for
themed icons drops the ruling on purpose: Material You flattens that layer to a single tint, which
would fuse paper and ink into a striped tile with the X lost inside it.

The 24-frame launch animation is cut from the same geometry and **writes** the mark rather than
tracing it — stroke one, then stroke two crossing it, held, then dimmed. Its rules fade out towards
both ends of the frame, so nothing gives away that the frames are a square on a fullscreen stage,
and the paper does not fade with the ink, which is what makes the loop wrap seamlessly.

None of it is drawn by hand: `tools/icon/` holds the stroke model and the two emitters, and
re-running them reproduces every shipped byte of the icon and the splash.

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
canvas, the stroke engine, the `.xnote` format, the vendored tree-sitter highlighting — is
upstream's work; this fork adds to it and repackages it. The code remains
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
