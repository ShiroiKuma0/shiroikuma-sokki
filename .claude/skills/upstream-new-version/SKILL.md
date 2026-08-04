---
name: upstream-new-version
description: Sync the shiroikuma-sokki fork onto a new upstream release tag of shardulvs/xnotes-android — advance master to the new tag, rebase custom, reset BUILD_NUMBER, build the new +001. Use when 白い熊 says a new upstream version is out, asks to check/update/sync to upstream, or to rebase custom onto the latest xnotes release. ALWAYS present the proceed-gated upstream-changes table BEFORE rebasing.
---

# Sync shiroikuma-sokki onto a new upstream xnotes release

This fork tracks [shardulvs/xnotes-android](https://github.com/shardulvs/xnotes-android) — a
handwriting-first notebook for pen and stylus. `master` mirrors the newest upstream **release tag**;
`custom` carries our patches and is rebased onto it.

**We follow release TAGS, not the branch tip** (白い熊, 2026-08-04). xnotes tags every release
(`v0.8.10`, `v0.8.9`, …) and bumps its `versionCode` with each, while `upstream/master` keeps moving
in between at ~130 commits/month. Basing on tags means every base is a state upstream itself called
finished, and the upstream version literal really moves on each sync. So a sync happens when a
**new tag** appears, not when commits land on `upstream/master`. The global `/git-versioning` skill
does **not** apply here — we use the plain `+NNN` versionName.

## Branch / remote model

| Branch | Role | Update mode |
| --- | --- | --- |
| `master` | Mirrors the newest upstream release tag. No fork work here. | reset to the new tag each sync |
| `custom` | Our patches; the working branch and the GitHub default branch. | rebased onto `master` each sync |

`origin` = `git@github.com:ShiroiKuma0/shiroikuma-sokki` (push). `upstream` =
`https://github.com/shardulvs/xnotes-android` (fetch only; push URL is `DISABLED`).

## Steps

1. **Fetch upstream and see whether a new release exists:**
   ```bash
   git fetch upstream --tags
   git tag --sort=-version:refname | head -5            # newest upstream tags
   git describe --tags --exact-match master 2>/dev/null # the tag master currently sits on
   git show <newtag>:app/build.gradle.kts | grep -E 'versionCode = |versionName = '
   ```
   If the newest tag is the one `master` already points at, stop and report "already current" — do
   **not** sync just because commits landed on `upstream/master`.

2. **PROCEED GATE — present the upstream changes as a table, then STOP.** 白い熊's standing
   requirement: **before** anything is rebased, show what the new upstream release actually brings.

   Gather the material from all of these — they complement each other:
   ```bash
   git log --oneline --no-merges <oldtag>..<newtag>      # what really landed
   git log --merges --format='%s' <oldtag>..<newtag>     # which PRs were merged
   git diff --stat <oldtag>..<newtag>                    # where the weight is
   gh release view <newtag> -R shardulvs/xnotes-android  # upstream's own release notes
   ls fastlane/metadata/android/en-US/changelogs/        # per-versionCode store notes
   ```
   Upstream writes descriptive, feature-shaped commit subjects and does almost no merge traffic, so
   `git log --oneline` is the primary source here — read it, don't skim it.

   Render **one markdown table**, ordered most-significant first, in this exact shape:

   | # | Change | Kind | What it means in the app | Touches our patches? |
   | --- | --- | --- | --- | --- |
   | 1 | … | Feature / Fix / UI / Perf / Refactor / Dependency | one clear sentence, in plain terms | No — or: yes, `<file>` (our icon / label / version block …) |

   Rules for the table:
   - **Every** notable upstream change gets a row — do not summarise into "various fixes". Group
     only genuinely trivial churn (typo fixes, dependency bumps, formatting) into a single final
     row, and say how many were folded in.
   - **The last column is the point**: flag every change landing in a file we patch — the launcher
     icon + loader resources, `values/strings.xml` (`app_name`), `app/build.gradle.kts`,
     `gradle.properties`, `.gitignore`, `ui/AboutPane.kt`, `ui/Backstage.kt` (the sidebar wordmark),
     `platform/PresentationServer.kt` (the served page title), and any other de-branded surface.
     Those are the rebase conflicts, predicted in advance.
   - Below the table, add the base line: old tag → new tag, old `versionCode`/`versionName` → new,
     and the resulting fork version (`<newVersionName>+001`, code `<newVersionCode>*10000+1`).

   **Then stop and wait for 白い熊's explicit go-ahead.** Do not move `master`, do not rebase, do not
   build until they say proceed. If they decline, nothing has been touched.

3. **Advance `master` to the new release tag** (mirror; no fork work lives here):
   ```bash
   git checkout -B master <newtag>
   git push --force-with-lease origin master
   ```
   (`--force-with-lease` because `master` is reset to a tag, which is not always a fast-forward.)

4. **Rebase `custom`:**
   ```bash
   git checkout custom
   git rebase master
   ```
   Resolve conflicts so **all** our customizations survive (table in step 6). Upstream's
   `versionCode` / `versionName` literals in `app/build.gradle.kts` flow in automatically — keep
   **upstream's** values for those two lines; our fork lines sit right after them and derive from
   them, so they are never edited by hand.

   If upstream restructured a screen we de-branded, port our change to the new structure rather than
   forcing the old diff. Re-check for **new** upstream branding the rebase introduced: new strings
   saying "xnotes", new `github.com/shardulvs` links, a new About entry, a new place the logo is
   drawn. **If conflicts are significant, stop and plan with 白い熊** before continuing.

5. **Reset the build tail:** in `gradle.properties`, set **`BUILD_NUMBER=1`** — a new upstream base
   starts its `+N` at 1.

   Consequence to expect: if upstream shipped a release *without* bumping `versionCode`, the reset
   lowers our `versionCode` below the installed build, and the on-device installer will refuse it as
   a downgrade. 白い熊 installs by hand from `/sdcard/tmp/`, so **say so explicitly** when handing
   such a build over. Never work around it by uninstalling — that destroys the app's notes storage.

6. **Verify our customizations are intact after the rebase:**

   | What | Expected | Where |
   | --- | --- | --- |
   | Installed app id | `shiroikuma.sokki` | `app/build.gradle.kts` → `defaultConfig.applicationId` |
   | Code namespace | `com.xnotes` (**unchanged** from upstream) | `app/build.gradle.kts` → `namespace` |
   | App label | `白い熊 速記` | `app_name` in `app/src/main/res/values/strings.xml` |
   | Fork version block | upstream literals + `forkVersionName` / `forkVersionCode` lines after them | `app/build.gradle.kts` → `defaultConfig` |
   | ABI filter | the second `ndk { }` block clearing upstream's three ABIs down to `arm64-v8a` | `app/build.gradle.kts` → `defaultConfig` |
   | `buildFork` task + `archivesName` | present at the end of the script | `app/build.gradle.kts` |
   | Build tail | `BUILD_NUMBER=1` | `gradle.properties` |
   | Black-yellow icon | yellow `#FFFF00` traced line-art on black | `mipmap-anydpi-v26/ic_launcher*.xml`, `drawable/ic_launcher_sk_foreground.xml`, `mipmap-*/ic_launcher*.png` |
   | Launch loader | our traced frames, not upstream's glitch-X | `drawable-nodpi/xnotes_frame_*.png` |
   | De-branding | no "xnotes" in user-visible text, our GitHub links in About | `ui/AboutPane.kt`, `ui/Backstage.kt`, `platform/PresentationServer.kt` |
   | 白い熊 速記 UI page | `BackstageView.SOKKI_UI` + the sidebar entry + the cog's long-press | `ui/SokkiUiPane.kt`, `ui/SokkiPickers.kt`, `settings/SokkiUi.kt`, `ui/theme/UiFonts.kt`, `ui/Backstage.kt` |
   | Theme hooks | `SokkiUi.applyTo` at the end of `buildPalette`; `XnotesTheme(palette, ui)` | `ui/Editor.kt`, `ui/theme/XnotesTheme.kt`, `MainActivity.kt` |
   | Backup + automation | the export engine, the panel, the token-gated receiver + service | `settings/SokkiBackup.kt`, `ui/SokkiExportImport.kt`, `automation/`, `AndroidManifest.xml` |
   | Kept as infrastructure | `com.xnotes` namespace, `.xnote` format, `libxnotests`, `xnotes.gl` log tags, `XnotesIcons`/`XnotesTheme` symbols | throughout |
   | Committed agent files | `CLAUDE.md`, `.claude/` un-ignored; signing material ignored | `.gitignore` |

   Sanity check that the build script still evaluates (**unsandboxed**):
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew :app:tasks --console=plain | head
   ```
   Then run the unit tests — they are fast and catch a botched `core/` conflict resolution:
   `./gradlew :app:testReleaseUnitTest`.

7. **Build the new `+001`** via the **build-apk** skill
   (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew buildFork < /dev/null`,
   unsandboxed), then deliver it via the global **/after-build** skill (no transfer prompt). This is
   the first build of the new upstream base. Expect a slow build: a new base usually invalidates the
   CMake cache, so the vendored tree-sitter C recompiles.

8. **Stop.** Let 白い熊 test. Commit/push only on their explicit **"Push"**. `custom` was rebased, so
   it needs `git push --force-with-lease origin custom`.

## Hard rules

- **Never rebase before the step-2 table has been shown and approved.**
- Sync on a **new tag**, not on commits landing in `upstream/master`.
- Never `adb install` / `adb uninstall` — 白い熊 installs manually from `/sdcard/tmp/`, and
  uninstalling would destroy the app's stored notes.
- Never commit/push unprompted; wait for "Push".
- `keystore.properties`, `*.jks` and `local.properties` are gitignored — never commit them.
- Never rename the `com.xnotes` namespace or change the `.xnote` format identifier.
- Run Gradle and `git status` **unsandboxed**.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line
of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
