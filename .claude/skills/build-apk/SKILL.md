---
name: build-apk
description: Build the signed release APK of shiroikuma-sokki (白い熊 速記 — a fork of shardulvs/xnotes-android, a stylus handwriting notebook) with the buildFork Gradle task, then deliver it automatically via the global /after-build skill (adb push if the phone is reachable, else scp to skhw — no prompt). Always build first without asking permission to build. Use whenever 白い熊 asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the 白い熊 速記 release APK and deliver it

> **Never ask whether to build — just build.** When this skill applies (白い熊 asked to build, or
> you've made changes ready to test), run the build immediately. Do **not** ask "shall I build?".
> There is **no** transfer question either: after a successful build, deliver the APK via the global
> **`/after-build`** skill — no prompts at all.

> **Never `git commit` or `git push` on your own.** Building does not include committing. After the
> build (and the delivery), 白い熊 tests it. **Only when they explicitly say "Push"** do you commit
> and `git push origin custom`. Their **"Push"** means *commit-and-push-to-the-fork* — unrelated to
> the `adb push` file copy.

> **Never run `adb install` / `pm install` / `adb uninstall`.** 白い熊 installs the APK themselves
> from the phone's file manager. Every `adb push` of the APK goes to `/sdcard/tmp/`, and older APKs
> already there are left exactly where they are.

## Build environment (this machine)

- The default `java` is **JDK 11**, which cannot run the Gradle 8.13 wrapper. Always export JDK 21.
- The Android SDK is not on a default env var; `local.properties` (gitignored) carries `sdk.dir`,
  but export `ANDROID_HOME` too so a background shell finds it.
- **Run Gradle UNSANDBOXED** (`dangerouslyDisableSandbox: true`). The sandbox mounts `~/.gradle`
  read-only and the wrapper dies on its own `.lck` file before the build even starts:
  `FileNotFoundException: …/gradle-8.13-bin.zip.lck (Read-only file system)`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/shiroikuma/android-sdk
```

## Steps

1. **Note the output filename / version.** The version is composed, not stored whole:
   - `grep -nE 'versionCode = |versionName = ' app/build.gradle.kts` — upstream's base literals
     (e.g. `47` / `0.8.10`). **Never hand-edit these**; a rebase moves them.
   - `grep -E '^BUILD_NUMBER' gradle.properties` — the `N` used for THIS build, **before** the task
     bumps it.
   - APK will be `shiroikuma-sokki_<upstream name>+<NNN>_arm64-v8a.apk` (N zero-padded to three
     digits — the global `/after-build` naming rule), e.g. `shiroikuma-sokki_0.8.10+001_arm64-v8a.apk`.
   - versionCode for this build = `<upstream code> * 10000 + N` (plain, unpadded), e.g. `470001`.

2. **Build** (release, signed) — from the repo root, **unsandboxed**:
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
   ./gradlew buildFork --console=plain < /dev/null
   ```
   - `buildFork` runs `assembleRelease` (R8 minify + resource shrink, signed from
     `keystore.properties`), copies the signed APK to `~/tmp/<apk name>`, and auto-increments
     `BUILD_NUMBER` in `gradle.properties`.
   - It prints `>>> <path>` and `>>> versionCode <n>` (cyan) — use those for the exact
     filename/code; confirm `BUILD SUCCESSFUL`.
   - **The first build is slow**: CMake compiles the vendored tree-sitter runtime and 8 grammars
     (~64 MB of C) for arm64. Run it with `run_in_background` if it may exceed the foreground
     timeout; later builds reuse the `.cxx` cache and are much faster.
   - **Fast dev iteration:** `./gradlew :app:assembleDebug` — debug-signed, no R8, `.debug`
     applicationId suffix so it installs alongside the release build.
   - **Unit tests:** `./gradlew :app:testReleaseUnitTest` (65 files, plain JUnit, no Robolectric —
     they run in seconds). Worth running after touching anything under `core/` or `format/`.

3. **At the end of every successful build, deliver via `/after-build`** — no exceptions, no asking.
   As soon as `BUILD SUCCESSFUL` appears and the signed APK is in `~/tmp/`, invoke the global
   **`/after-build`** skill; it picks adb-push (phone reachable) or scp-to-skhw on its own and
   announces what landed. Every `adb` call goes **unsandboxed**.

4. **What `/after-build` does** (for reference — don't run these by hand): `/adb-check` lists
   devices UNSANDBOXED and walks the wireless-connect chain; if the phone is reachable, `/adb-push`
   copies **this repo's** newest `~/tmp/shiroikuma-sokki_*.apk` to `/sdcard/tmp/`; otherwise `/scp`
   copies it to `skhw:~/tmp/`. `~/tmp/` is shared with parallel chats building sister apps — always
   deliver the `shiroikuma-sokki_*` one, never merely the newest file there.

## Signing

Release signing is non-interactive and is **upstream's own mechanism** — `app/build.gradle.kts`
reads `keystore.properties` (gitignored) at the repo root when it exists. This fork uses
`~/.android-keystores/shiroikuma-sokki.jks` (alias `sokki`, PKCS12/RSA-4096, 10000-day validity,
created 2026-08-04); the password is in `keystore.properties` and recorded in
`~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org`, with the `.jks` backed up alongside it.

If `keystore.properties` is missing, the release build silently comes out **unsigned** and will not
install (that path exists for F-Droid's reproducible builds). Restore it with `storeFile`,
`keyAlias`, `keyPassword`, `storePassword`.

## Versioning (how the numbers are formed)

- Upstream's `versionCode` / `versionName` literals in `app/build.gradle.kts` **are the base** and
  track upstream releases; our fork lines sit right after them and derive from them.
- `BUILD_NUMBER` (`gradle.properties`) is our increment, bumped on every `buildFork`, reset to `1`
  on each new upstream tag (see `/upstream-new-version`).
- `versionName = "<upstream>+<NNN>"`; `versionCode = <upstream code> * 10000 + N`. When upstream's
  code climbs (47 → 48), the new line's codes (`480001`, …) all exceed the previous line's
  (`470001`, …), keeping upgrades monotonic.
- **Consequence after a sync:** if upstream ever ships a release without bumping `versionCode`, the
  `BUILD_NUMBER` reset makes the new build's code *lower* than the installed one. 白い熊 installs by
  hand, so Android will refuse it as a downgrade — say so explicitly when handing over such a build.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line
of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
