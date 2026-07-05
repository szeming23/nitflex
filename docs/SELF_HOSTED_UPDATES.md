# Self-hosted in-app updates

This app has a built-in updater that can download and install a new APK from a
release feed. By default it is **disabled**, and it used to point at the
upstream project's GitHub releases. This guide shows how to turn it on and point
it at **your own** update source so you can ship updates to your users.

---

## How the updater works

At startup, `MainViewModel.checkUpdate()` calls `InAppUpdater.getNewReleases()`,
which:

1. Fetches the release list from a **GitHub-style REST API**:
   `GET {baseUrl}repos/{owner}/{repo}/releases`
   (base URL is in `GitHub.kt`, currently `https://api.github.com/`).
2. Keeps releases whose tag (`tag_name`, with a leading `v` stripped) is a
   **higher version** than the installed `versionName` (compared numerically,
   dot by dot — e.g. `v1.7.226` > `1.7.225`).
3. Picks the APK asset for this build's layout:
   - `content_type` must be `application/vnd.android.package-archive`, **and**
   - the asset **file name** must end with **`-mobile.apk`** (mobile build) or
     **`-tv.apk`** (TV build).
4. Shows the update dialog; on accept it downloads `browser_download_url` and
   launches the system installer.

Two switches control it:

| Switch | Where | Meaning |
|---|---|---|
| `InAppUpdater.UPDATES_ENABLED` | `utils/InAppUpdater.kt` | **Master** on/off (compile-time). `false` = updater fully off. |
| "Automatic update check" | Settings → per user (`UPDATE_CHECK_ENABLED`) | Per-user auto-check toggle. Only matters when the master switch is `true`. |

---

## Current state

`UPDATES_ENABLED = false` — the updater is off, so **no user is prompted to
update** (including toward the upstream project). Everything else is left in
place so you can enable it whenever your own release feed is ready.

---

## Option A — use your own GitHub repo (recommended, no server)

The simplest, most reliable path. GitHub's Releases API already returns exactly
the JSON the app expects.

1. Create a repo you control, e.g. `github.com/<you>/nitflex-releases` (public,
   or private if your users' devices can authenticate — public is simplest).
2. In **`app/src/main/java/com/nitflex/app/utils/InAppUpdater.kt`**, set:
   ```kotlin
   const val UPDATES_ENABLED = true
   private const val UPDATE_OWNER = "<you>"
   private const val UPDATE_REPO  = "nitflex-releases"
   ```
   Leave `GitHub.kt`'s base URL as `https://api.github.com/`.
3. Publish a release (see **Publishing a release** below).

That's it — no server to run.

---

## Option B — your own server (custom domain)

Use this only if you don't want to rely on GitHub. You host an endpoint that
returns the **same JSON shape** as GitHub's releases API.

1. In **`utils/GitHub.kt`**, change the base URL:
   ```kotlin
   .baseUrl("https://updates.example.com/")   // must be HTTPS
   ```
2. In **`utils/InAppUpdater.kt`**, set `UPDATES_ENABLED = true` and pick any
   `UPDATE_OWNER` / `UPDATE_REPO` strings you like (they just form the path).
3. Serve the release list at:
   ```
   GET https://updates.example.com/repos/<owner>/<repo>/releases
   ```
   returning a JSON **array** of releases. A minimal-but-valid entry (the app is
   strict about a few non-null fields, so keep them all) looks like:
   ```json
   [
     {
       "url": "https://updates.example.com/releases/1",
       "html_url": "https://updates.example.com/releases/1",
       "assets_url": "https://updates.example.com/releases/1/assets",
       "upload_url": "https://updates.example.com/releases/1/assets",
       "tarball_url": null,
       "zipball_url": null,
       "id": 1,
       "node_id": "R_1",
       "tag_name": "v1.7.226",
       "target_commitish": "main",
       "name": "1.7.226",
       "body": "What's new in this build.",
       "draft": false,
       "prerelease": false,
       "created_at": "2026-07-05T00:00:00Z",
       "published_at": "2026-07-05T00:00:00Z",
       "author": {
         "login": "you", "id": 1, "node_id": "U_1",
         "avatar_url": "https://updates.example.com/a.png",
         "url": "https://updates.example.com/u",
         "html_url": "https://updates.example.com/u",
         "followers_url": "", "following_url": "", "gists_url": "",
         "starred_url": "", "subscriptions_url": "", "organizations_url": "",
         "repos_url": "", "events_url": "", "received_events_url": "",
         "type": "User", "site_admin": false
       },
       "assets": [
         {
           "url": "https://updates.example.com/assets/1",
           "browser_download_url": "https://updates.example.com/apks/nitflex-1.7.226-mobile.apk",
           "id": 1, "node_id": "A_1",
           "name": "nitflex-1.7.226-mobile.apk",
           "label": null, "state": "uploaded",
           "content_type": "application/vnd.android.package-archive",
           "size": 42000000, "download_count": 0,
           "created_at": "2026-07-05T00:00:00Z",
           "updated_at": "2026-07-05T00:00:00Z",
           "uploader": {
             "login": "you", "id": 1, "node_id": "U_1",
             "avatar_url": "https://updates.example.com/a.png",
             "url": "", "html_url": "",
             "followers_url": "", "following_url": "", "gists_url": "",
             "starred_url": "", "subscriptions_url": "", "organizations_url": "",
             "repos_url": "", "events_url": "", "received_events_url": "",
             "type": "User", "site_admin": false
           }
         }
       ]
     }
   ]
   ```
4. Host the APK at the `browser_download_url` (HTTPS, direct download).

> Tip: You can generate this `releases` JSON as a static file and drop the APK
> next to it on any static host (S3, Cloudflare R2, nginx). No dynamic backend
> is required — just update the JSON each release.

---

## Publishing a release (both options)

For each update:

1. **Bump the version** in `app/build.gradle`:
   ```gradle
   versionCode 155          // must strictly increase every release
   versionName "1.7.226"    // must be higher than what users have installed
   ```
2. **Build a signed release APK** — see signing note below. Name it so it ends
   with the right suffix for the layout, e.g. `nitflex-1.7.226-mobile.apk`
   (or `-tv.apk` for the TV build).
3. **Tag the release** `v1.7.226` (leading `v` is fine — the app strips it).
4. **Attach the APK** as a release asset (Option A) or reference it via
   `browser_download_url` (Option B).

The next time a user opens the app (with auto-check on), they'll be offered the
update.

---

## ⚠️ Critical: sign every release with the SAME key

Android will **refuse to install an update** whose signature doesn't match the
currently-installed app. So:

- Create **one** release keystore and **reuse it for every build you ship.**
  If you lose it, users must uninstall/reinstall to move to a new key.
- This repo already supports it: create **`keystore.properties`** in the project
  root (it's gitignored — see `keystore.properties.example`) with:
  ```
  storeFile=release.keystore
  storePassword=********
  keyAlias=********
  keyPassword=********
  ```
  Then a command-line `assembleRelease` is signed automatically; otherwise use
  Android Studio → **Build → Generate Signed APK**.
- The very first build your users install and every later update must all use
  this same keystore.

Other requirements for the in-app install to work:
- `versionCode` must increase (Android blocks "downgrade" installs).
- The device must allow installing from this app (the app already declares
  `REQUEST_INSTALL_PACKAGES`; users grant "install unknown apps" once).
- All URLs must be **HTTPS**.

---

## Re-disabling

Set `InAppUpdater.UPDATES_ENABLED = false` again and rebuild. Users can also
turn off auto-checks themselves via **Settings → Automatic update check** (only
effective while the master switch is on).

---

## Quick checklist

- [ ] `UPDATES_ENABLED = true`
- [ ] `UPDATE_OWNER` / `UPDATE_REPO` point at your source (Option A) — or base
      URL changed (Option B)
- [ ] `versionCode` **and** `versionName` bumped
- [ ] APK signed with your **one** release keystore
- [ ] Asset name ends with `-mobile.apk` / `-tv.apk`, content type
      `application/vnd.android.package-archive`
- [ ] Tag is a higher version than installed builds
- [ ] Everything served over HTTPS
