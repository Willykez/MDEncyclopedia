# Android UI Encyclopedia — Kotlin rebuild

A ground-up Kotlin rewrite of the original Sketchware/Java markdown viewer.
Same visual language (dark/light "MD" theme, macOS-style code cards, accent
underlines), rebuilt as a real Gradle/Android-Studio project with:

- **Local-storage library** — markdown files live in the app's own storage
  (`getExternalFilesDir("md")`, falling back to internal `filesDir/md`), not
  in `assets/`. They're seeded once from `assets/md/*.md` on first launch,
  then fully owned by local storage from then on.
- **Editing** — `EditorActivity` gives you a monospace editor, a formatting
  toolbar (H1/H2, bold, italic, strike, inline code, fenced code block,
  links, bullet/numbered/checkbox lists, blockquote, table, HR), and a
  live **Preview** toggle that renders through the same engine as the
  reader.
- **Full view** — `ReaderActivity` is a distraction-free reading mode with
  a scroll progress bar, scroll-to-top FAB, and font-size/theme toggles.
- **Upgraded renderer** (`Markdown.kt`) — real inline spans instead of
  stripped text: **bold**, *italic*, ~~strike~~, `code`, tappable links,
  task-list checkboxes (`- [ ]` / `- [x]`), nested lists, images rendered
  as labelled placeholders, striped tables, syntax-highlighted code blocks
  with a working copy button.
- **Import / export** — pull `.md` files in from anywhere via the system
  file picker (Storage Access Framework), or share a file back out via a
  `FileProvider`.
- **Release hardening** — R8 code shrinking + resource shrinking
  (`isMinifyEnabled` / `isShrinkResources`), a tuned `proguard-rules.pro`,
  and a signing config wired to environment variables rather than any
  committed keystore.

## Project layout

```
app/
  build.gradle.kts        ← signing config, minify/shrink, dependencies
  proguard-rules.pro
  src/main/
    AndroidManifest.xml
    assets/md/00_welcome.md   ← one-time seed content only
    kotlin/com/willykez/md/
      AppTheme.kt            ← dark/light palette + font size, persisted
      Views.kt               ← small view-builder helpers (dp, mkTv, …)
      Markdown.kt            ← parser + block/inline renderer
      SyntaxHighlighter.kt   ← keyword/string/comment/number coloring
      MarkdownStore.kt       ← local-storage read/write/import/export
      MainActivity.kt        ← library: list, search, new, import
      ReaderActivity.kt      ← full-view distraction-free reader
      EditorActivity.kt      ← edit / live preview / formatting toolbar
      model/MdFile.kt
.github/workflows/release.yml
```

## Signing — GitHub Actions

The release build is signed entirely from **repository secrets**, decoded
at build time and never committed:

| Secret            | What it holds                                   |
|--------------------|--------------------------------------------------|
| `KEYSTORE_B64`     | Your `.jks`/`.keystore` file, base64-encoded      |
| `STORE_PASSWORD`   | Keystore password                                 |
| `KEY_ALIAS`        | Key alias inside the keystore (commonly `upload`) |
| `KEY_PASSWORD`     | Password for that specific key                    |

To generate the base64 secret from an existing keystore:

```bash
base64 -w0 my-release-key.jks > keystore.b64.txt   # Linux
base64 -i my-release-key.jks -o keystore.b64.txt   # macOS
```

Paste the contents of `keystore.b64.txt` into the `KEYSTORE_B64` secret in
**Settings → Secrets and variables → Actions** on GitHub, then add the
other three as plain text secrets.

Pushing to `main`, pushing a `v*` tag, or running the workflow manually
(`workflow_dispatch`) triggers `.github/workflows/release.yml`, which:

1. Decodes `KEYSTORE_B64` to `app/release.keystore`
2. Runs `gradle :app:assembleRelease :app:bundleRelease` with the four
   secrets exported as env vars (read by `app/build.gradle.kts`)
3. Deletes the decoded keystore file
4. Uploads the signed, shrunk APK and AAB as workflow artifacts, and — on a
   `v*` tag — attaches them to a GitHub Release

Building **without** those secrets set (e.g. a local `debug` build, or a CI
run without the four env vars) still works — `signingConfig` is only
attached when all four are present, so unsigned/local builds don't break.

## Local signed build

```bash
export KEYSTORE_PATH=/path/to/release.keystore
export STORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

(Open the project in Android Studio once first so it can generate the
Gradle wrapper, or run `gradle wrapper` yourself — the wrapper jar isn't
committed to this export.)

## Where files actually live on a device

```
/storage/emulated/0/Android/data/com.willykez.md/files/md/
```

(or the app's internal `files/md/` on devices without shared storage).
Nothing here requires a runtime storage permission on API 24+.
