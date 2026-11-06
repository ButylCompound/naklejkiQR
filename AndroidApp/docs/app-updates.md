# Android app updates

Operators use **Ustawienia → Sprawdź aktualizacje**. The app checks the configured primary server and uses the fallback on transport errors, HTTP 408, or 5xx, just like the other API calls. There is no automatic installation or scheduled update check.

When a newer `versionCode` exists, the screen shows its `versionName` and **Pobierz i zainstaluj**. Download progress survives screen rotation and uses WorkManager. The APK is streamed to private cache storage, then checked for size, SHA-256, application ID, version code/name, Android compatibility, and the same signing certificates as the installed app. Only a complete, checked APK reaches Android's installer. Interrupted downloads can be retried; an already checked file can be reused.

Android requires user confirmation to install. On the first update, the app also guides the operator to allow installations from this source and resumes after they return. Cancelling either screen keeps the installed app and its data. Installation uses an ordinary update of the existing package; it does not uninstall the app, clear data, or change device numbering.

The updater itself must first reach each phone through a normal installation of this build. Subsequent versions can come from the server. Always retain the production signing key and application ID (`pl.almara.inwentaryzacja`) and increase `versionCode` for every published build. The human-facing version name alone does not determine update order. Debug APKs cannot update production installs.

## Manual phone download

For a short address that can be typed into a phone browser, use `http://192.168.1.75:3000/materials/app.apk` on LAN or `http://100.99.77.64:3000/materials/app.apk` through Tailscale. This endpoint serves the latest APK directly with `Cache-Control: no-store` and a fixed download filename. Install it over the existing app. It requires no new Android build.

## Server routes

Both addresses entered on the phone are server roots; the client adds `/materials`. No new address, credential, or external download URL is needed. Both addresses must expose the same release files. Redirects are rejected.

### `GET /materials/app-updates/latest`

Return HTTP 200, `Content-Type: application/json`, `Cache-Control: no-store`:

```json
{
  "update": {
    "versionCode": 14,
    "versionName": "1.13",
    "sizeBytes": 34567890,
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
```

The example size/hash are placeholders; compute them from the final signed APK. `versionCode` is an integer from 1 to 2147483647; `sizeBytes` is a positive integer; `sha256` contains 64 lowercase hexadecimal characters. The [API JSON Schema](server-api.schema.json) defines `appUpdate` and `appUpdateResponse`.

If no release is published, return `{"update": null}` with HTTP 200. The phone compares the code with its installed version and offers only higher codes.

### `GET /materials/app-updates/{versionCode}/{sha256}.apk`

Return HTTP 200, the exact APK bytes, `Content-Type: application/vnd.android.package-archive`, and the correct `Content-Length`. This URL identifies immutable bytes. Keep previous releases downloadable so publishing a new release during a download does not break it. The APK response is binary, not JSON or base64. Missing artifacts return 404; unavailable published storage returns 503.

## Publishing on the production server

Production now runs on `br-futroapi-01`, at `http://192.168.1.75:3000` on LAN and `http://100.99.77.64:3000` in the production phones' Tailscale network. Code lives in `/home/kamil/serverapi`; releases live in its `data/app-updates` directory. The workstation checkout `/mnt/c/dev/serverAPI` is the development source and no longer serves production. There is no HTTP upload/admin endpoint. Build the signed APK with `AndroidApp/build_release.bat`, upload it, then publish on the Debian host:

```sh
scp -i ~/.ssh/id_ed25519_futro4 /path/to/signed.apk kamil@192.168.1.75:/home/kamil/serverapi-deploy/next-release.apk
ssh -i ~/.ssh/id_ed25519_futro4 kamil@192.168.1.75 \
  'cd /home/kamil/serverapi && /home/kamil/.local/bin/bun run app:publish /home/kamil/serverapi-deploy/next-release.apk VERSION_CODE VERSION_NAME'
```

Supply the APK's actual `versionCode` and `versionName` from `app/build.gradle.kts`. The command computes size/hash, keeps each APK at its immutable address, and atomically replaces `latest.json` after storing the file. Publishing the same file and metadata again is safe. Reusing a code for different bytes/name or publishing a lower code is rejected. The publisher checks the file container; phones check Android package metadata and signatures. Verify release signing through the existing build script before publishing.

No server restart is needed to publish a new version. Restart a server running without file watching once to load the new routes. Back up the release directory alongside the production signing key and existing server storage. Do not put signing keys or passwords in this directory or the API response.

## Verification

`AppUpdateTest` checks manifest parsing. `AppUpdateInstallTest` exercises update detection, fallback downloads, corrupted/truncated payloads, package/version/signature rejection, and FileProvider access using disposable debug fixtures. Run these on a disposable emulator, never a production phone. Server tests cover publishing, exact binary responses, immutable old releases, version reuse, and unpublished state.

With a disposable Windows Android emulator already running, use Windows Python to build the higher-version and different-key debug fixtures, restore the regular debug build, and run the tests:

```powershell
python tools/check_app_updates.py --serial emulator-5580
```

This uses the installed Windows Android Studio JBR/SDK. It leaves normal debug build outputs in place and writes test results under `build/updater-review`. It does not build or publish a production release.

Android references: [installation permission](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()), [APK URI access](https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE).

## Android 10 archive certificate lookup

On the Mi 10 running Android 10 / MIUI 12, `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)` returns package/version metadata with null `signingInfo`. Requesting `GET_SIGNATURES | GET_SIGNING_CERTIFICATES` returns the expected certificate. The updater requests both flags while keeping the same nonempty, exact signing-certificate comparison and SHA-256 checks. See [Android PackageInfo](https://developer.android.com/reference/android/content/pm/PackageInfo).

Confirmed on the affected phone: genuine 1.13 APK accepted, a mismatched checksum rejected, and the same APK re-signed with a throwaway key rejected. An affected updater from an earlier release needs one normal manual APK/USB update to acquire the fix; no uninstall or data clear is needed. Subsequent downloads use the corrected validation.
