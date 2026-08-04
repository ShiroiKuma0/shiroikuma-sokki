<h1 align="center">白い熊 速記</h1>

<p align="center">
  A handwriting-first notebook for Android, built for pen and stylus
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

---

白い熊's fork of [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh.
Repackaged as `shiroikuma.sokki` so it installs **side-by-side** with upstream, re-branded to the
black-and-yellow house style, and built for arm64 only.

## What differs from upstream

| | |
|---|---|
| Application id | `shiroikuma.sokki` (upstream: `com.xnotes`) — both can be installed at once |
| Label | 白い熊 速記 |
| Icon | a clean, even traced X — yellow on black, vector, with a themed-icon variant |
| Launch animation | the mark writes itself on, 24 frames |
| Branding | upstream's name and links replaced throughout the UI: About pane, sidebar wordmark, presentation page |
| ABI | `arm64-v8a` only — upstream ships three, and the vendored tree-sitter libs dominate the APK |
| Version | `<upstream version>+NNN`, where `NNN` counts our builds on that upstream base |

The code namespace stays `com.xnotes` and the `.xnote` document format is untouched, so notes written
in either build open in the other, and rebases onto upstream stay small.

## Branches

| Branch | Role |
|---|---|
| `custom` | all fork work — the default branch |
| `master` | mirrors the newest upstream **release tag**, no fork work |

Upstream is followed by release tag rather than by branch tip, so every base is a state upstream
itself called finished.

## Build

Requires JDK 21 to run Gradle (the project itself compiles to Java 17), the Android SDK, NDK
`27.0.12077973` and CMake `3.22.1` for the vendored tree-sitter build.

```bash
./gradlew buildFork          # signed release -> ~/tmp, bumps the build counter
./gradlew :app:assembleDebug # fast, debug-signed, installs alongside the release build
./gradlew :app:testReleaseUnitTest
```

Release signing reads a gitignored `keystore.properties` at the repo root; without it the release
build comes out unsigned.

## Credit & licence

All of the app is [Shardul Vikram Singh's work](https://github.com/shardulvs/xnotes-android); this
fork only re-brands and repackages it. MIT, same as upstream — see [LICENSE](LICENSE).
