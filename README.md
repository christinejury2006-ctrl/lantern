# Lore

Native Android reader **and** a browser demo. Version **3.5.0**.

## What it does

The app is **The Reading Nook**: a warm room with glass panels, in a daylight and a night voice.

- **Home** — the nook. Your shelf as covers with your place under each one, Editor's Choice
  standing in coloured light, and the book club.
- **Explore** — search Google Books with a public-domain fallback, plus trending and recent
  searches; public-domain titles download straight into the reader.
- **Library** — every book you own, three across, with progress. Long-press to remove.
- **Reader** — EPUB and PDF with a page turn, bookmarks, page themes and reading fonts.
- **You** — account and sync, appearance, and reading interests.
- **Studio** — paste one web page and turn a serialised story into a proper EPUB you can keep.
  Reached from the sparkle button in the nook's masthead.

Sync is optional and two-part: Firestore holds the library, reading progress, bookmarks and
settings, and Google Drive's private application folder (`drive.appdata`) holds the EPUB and PDF
files, so a reinstall can restore and open your books. Nothing leaves the phone until you sign in.

## Test in the browser (GitHub Pages)

Open:

**https://christinejury2006-ctrl.github.io/lantern/**

Library, reader, Search/Explore (Project Gutenberg), Light/Dark. No install.
The browser demo is a cut-down preview: PDFs, Studio and Drive sync are Android only.

> Maintainer note: this link serves `/docs` and needs GitHub Pages switched on once —
> **Settings → Pages → Source: Deploy from a branch → `main` / `/docs` → Save**.

## Test on a phone

1. Open the **Actions** tab → **Build debug APK** → latest successful run.
2. Download the **Lore-debug** artifact.
3. Install `lore-debug.apk` (Android 8+) — you may need to allow installs from your browser or file manager.

Or open this folder in Android Studio and Run.

GitHub itself cannot launch an APK. The Pages link is the web test.

## Updating without uninstalling

New builds install **over** the existing app and keep your library, progress and settings,
just like a store update. That needs two things, both handled by this repo:

- **the same signing key every time** — set up once with `./tools/make-update-key.sh`, which
  creates a keystore and stores it as GitHub secrets (`LORE_KEYSTORE_BASE64`,
  `LORE_KEYSTORE_PASSWORD`, `LORE_KEY_ALIAS`, `LORE_KEY_PASSWORD`). Without those secrets
  CI builds are signed with a throwaway key and Android refuses the update with
  *"App not installed"*.
- **a higher `versionCode`** — CI sets it from the run number, so every build supersedes
  the previous one.

Tagging releases gives a permanent download: `git tag v3.5.1 && git push origin v3.5.1`
publishes `lore-v3.5.1.apk` on the Releases page, already signed for in-place updates.

### Make the signing key once

The build **refuses to run without a persistent key** (`.github/workflows/build-apk.yml`,
step *Require persistent update signing key*). That is deliberate: an ephemeral key changes the
APK fingerprint on every run, so Android refuses the update *and* no SHA-1 is worth registering
with Google.

On your own machine, once, from the repository root:

```sh
./tools/make-update-key.sh
```

It generates a PKCS#12 keystore, stores the four secrets (`LORE_KEYSTORE_BASE64`,
`LORE_KEYSTORE_PASSWORD`, `LORE_KEY_ALIAS`, `LORE_KEY_PASSWORD`) with `gh`, and prints the
certificate's SHA-1. Keep the keystore and password private — they are gitignored, and losing
them means the installed app can only be replaced after an uninstall.

Then paste that SHA-1 into Firebase (Project settings → Your apps → `com.lantern.library` →
Add fingerprint) so Google Sign-In accepts the build, and download `google-services.json`
again. Every later APK carries the same signature and installs straight over the last one.

### Why Google Sign-In says "error 10"

Google Sign-In needs two OAuth clients in the Firebase/Cloud project. `app/google-services.json`
in this branch carries both — two Android clients (`client_type: 1`, one per registered
fingerprint) and a Web client (`client_type: 3`):

| Client | Type | What creates it | What it is used for |
| --- | --- | --- | --- |
| Android | `client_type: 1` | Firebase → Project settings → Your apps → Add fingerprint | Lets Google trust this APK's package + signing certificate. **Missing it is error 10.** |
| Web | `client_type: 3` | Enabling Google in Firebase Authentication | Mints the ID token that `signInWithCredential` trades for a Firebase session |

Both are console actions; neither can be created from code or from CI. After adding a fingerprint,
download `google-services.json` again and commit it — the build reads the Web client ID straight
out of that file (an explicit `FIREBASE_WEB_CLIENT_ID` secret or `local.properties` entry still
wins), so no extra configuration has to be kept in step.

Until then the app does two things instead of failing silently: sign-in opens a dialog naming the
exact setting that is missing with a Copy button for the package and SHA-1, and the build prints
both clients, the Web client id and the APK's signer in its annotations.

## Building locally

```bash
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
```

A Google Books API key is optional (daily *For You* recommendations). Put it in
`local.properties` as `GOOGLE_BOOKS_API_KEY=...`, or pass `-PGOOGLE_BOOKS_API_KEY=...`.
Restrict the key to this app's package in Google Cloud Console — it ships inside the APK.
Never commit a real key. See `local.properties.example`.
