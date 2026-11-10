# Skaner Inwentaryzacji — Android

Android app for warehouse operators: scan pallet QR stickers (printed by `GeneratorNaklejek`) hands-free, collect them into a session, and deliver the results as a CSV file via e-mail or SharePoint — fully **offline**, no network needed during scanning.

For new apps and UI work, use the [shared app style guide](../STYLE_GUIDE.md) for colors, components, screen patterns, and Android examples.

## How it works (operator's view, UI is in Polish)

1. **Nowa sesja** — start a new inventory session (name defaults to `Inwentaryzacja <date>`). Names must be unique across product and raw-material sessions; letter case and extra spaces do not distinguish names. A duplicate name leaves the dialog open with an error.
2. Point the camera at a sticker. The app scans automatically — no button press:
   - 🟢 **OK: <product> (<weight> kg)** — recorded, with a beep + short vibration.
   - 🟠 **Już zeskanowano!** — in product sessions, the same product, quantity, unit, label date, and initials are already recorded. The display ID does not distinguish duplicates.
   - 🔴 **Nieprawidłowy kod QR** — QR found but not in the expected `Nazwa | 500kg | data` format.
   - After every read there is a **2-second cooldown** before the next scan.
3. **Zakończ** — shows the session summary (pallet list, count, total weight).
4. Export:
   - **Wyślij CSV** — opens the Android share sheet; pick Outlook/Gmail to e-mail it, or OneDrive/Teams to upload to SharePoint. The file is handed to the chosen app, which uploads whenever connectivity is available.
   - **Zapisz CSV** — save the file to a folder on the phone (e.g. a OneDrive-synced folder). A notification confirms the save; tapping it opens the file (on Android 13+ the first save asks for notification permission).

Sessions are stored on the device (app-private storage) and survive app restarts and offline periods. Inventory and RW saves use Android's atomic file replacement, retaining the last complete file if a write is interrupted. A bulk pallet addition saves the whole batch once. Data is only removed when you delete a session (long-press it on the list, or the **Usuń sesję** button). Each shared CSV uses its own file path so later exports cannot overwrite an earlier attachment.

In raw-material sessions, each scan asks **Ile palet dodać (1–999)?** The dialog shows the label date, quantity per pallet, number already recorded for that delivery and packing quantity, and the total after adding. The input starts at 1, accepts up to three digits, and always means additional pallets: record 5, then enter 3 to reach 8. New pallets use the current alley; earlier entries keep their locations. Pallet numbering continues after the largest remaining number. Different quantities per pallet, units, label dates, or initials have separate counts; label IDs do not affect these counts.

Units `KG`/`kg` and `szt`/`szt.` use the common forms `kg` and `szt.`. The same material and date can have both `500 kg` and `20 szt.` entries. They remain separate in duplicate checks, totals, and CSV exports. Label quantity always means quantity per pallet: three pallets labelled `100 szt.` give `300 szt.`.

To delete scan entries, long-press a row to select it, tap other rows to select or deselect them, then tap the trash icon and confirm once. Back exits selection mode. Selection works in the plain and alley-grouped inventory lists and in editable RW documents; finalized RW documents stay locked. Outside selection mode, tapping an RW row edits its issued quantity.

RW PDFs show the full label date and time on separate lines and wrap product names without shortening them. Rows continue onto another A4 page when needed; the document and table headers repeat, and signatures appear on the last page. A product name that spans pages continues under the same row number, marked `cd.`, without repeating the issued quantity.

### PZ — receiving deliveries

Create a PZ, choose a supplier from the dropdown, select a receiving operator, and optionally enter an invoice number. Choose **Inny dostawca…** to enter a supplier in a popup; cancelling preserves the current choice. Configure the receiving-operator list in Settings. Add one entry per physical pallet or package, choosing a catalogue material or typing a manual material, lot, quantity/unit, and condition. Saving resolves a unique catalogue name, ignoring case and extra whitespace; entries without a catalogue match show a manual-material warning. An explicit catalogue selection keeps its identity when its name is edited and restored. Damage requires a comment. An explicitly unknown lot requires its own comment and always stays separate in the grouped summary. Use the highlighted Yes/No bar to confirm whether the whole delivery matches its shipping document; No requires a separate explanation. PZ uses the same totals card as inventory, with separate quantities for each unit.

Draft fields and confirmed entries are saved locally. Approval assigns the number and locks the PZ, including its entries and header. Approved PZs can be printed or shared as a PDF through the same Android share sheet used for inventory CSV reports; choose Gmail, Outlook, or another compatible app. The PDF and document subject are included. Approved PZs and finalized RWs are queued for server backup; drafts and inventory sessions remain local. The app retains local documents after upload. Approved documents cannot be deleted through the app.

PZ and RW creation and approval work offline from the start, using device number `00` until registration succeeds. Save the private server base URL in **Settings → Serwer i kopie zapasowe**; the app automatically registers the phone, downloads the catalogue, and uploads pending approved-PZ and finalized-RW backups in the background, retrying failed connections. New numbers use `PZ/YYYY/PP/NNNN` and `RW/YYYY/PP/NNNN`, with independent yearly counters for each module and assigned device number PP. After registration, only future documents use the assigned number; counters continue and approved documents/PDFs keep their original numbers. Existing RW numbers remain unchanged and the current-year RW sequence continues. Unregistered phones may have matching visible numbers; document UUIDs keep their server backups separate.

Configure a primary local-network address and an optional Tailscale fallback address for the same server. Enter only the server root, for example `http://192.168.10.20:8080`; the app adds `/materials` for all API requests, followed by `/catalogue` for catalogue sync and connection testing. Existing addresses ending in `/materials` normalize to the root. See the [server handoff](docs/server-implementation.md#routes-and-client-behavior) for all routes. Sync tries the primary first, switches on network failures or temporary server errors, and reuses the working route for that batch. The connection-test button checks both entered addresses separately. Either address can be used alone. Both routes must share the registry and backup store so phone numbers and document backups remain consistent.

The server has not been deployed by this change. Start with the [server implementation handoff](docs/server-implementation.md) and implement the [JSON API contract](docs/server-api.md), using the [JSON Schema](docs/server-api.schema.json) and [examples](docs/examples). Microsoft 365 credentials belong on that server. The app uses cached catalogue data and manual input when the server is unavailable; only a successful complete catalogue download replaces the cache.

New PZ entries open a label scanner with an explicit capture button and a manual-entry option. It reads barcodes and printed text offline, suggests material, manufacturer lot, and net quantity where supported, and opens the same review form. The final label photo is retained with its OCR/barcode read and operator-confirmed entry, then uploaded when the PZ is approved. See [photo storage and API](docs/label-photos.md). Scanned material names fill the form only when they match a unique cached catalogue entry (or its supplied ID). A description such as `POLIIZOBUTEN PIB32` matches catalogue grade `PIB32`; the form uses the catalogue name and ID. Unmatched or ambiguous names leave the field empty for manual input. Original OCR and parser suggestions remain attached to the label. Every suggested field can be edited. Rescans preserve existing values and offer replacements explicitly. The operator confirms condition and can enter **Liczba palet / opakowań** (1–999, default 1) after the unit to add several physical packages with the same lot, amount per package, unit, condition, and comments. Saving adds separate entries in one write, so 4 × 25 kg gives four packages and 100 kg. Extra entries retain the scan text and codes; only the scanned package keeps its photograph. Each saved package can then be edited separately. Identical codes on different packages are allowed. The existing inventory and RW scanner flows are unchanged.

Label rules refresh from `GET /materials/label-rules` alongside the catalogue, with `/materials` added automatically before the `/label-rules` suffix. The last validated bundle stays available offline, with bundled defaults on first use. Patterns, material mappings, and quantity formats can change on the server without an APK update. See [sample-label findings](docs/label-analysis.md) and [rule authoring/server contract](docs/label-rules.md). Recorded raw codes, OCR text, and suggestions accompany scanned entries in server backups; manual corrections remain authoritative.

### QR payload contract

New labels use `ALBU T4D | 500 kg | 2026-07-07 10:00 | XX | aB3dE_fG | 0001A` (name | quantity and unit | date | operator initials | ID | optional lot). Five-field labels without a lot are also accepted. The ID has 8 URL-safe characters (`A–Z`, `a–z`, `0–9`, `_`, `-`); the initials field may be empty. Older labels with three or four fields and no ID are also accepted. Dates must be valid `yyyy-MM-dd HH:mm:ss` or `yyyy-MM-dd HH:mm` timestamps and are stored/reported with seconds. Quantities must be finite and greater than zero. They accept `,` or `.` decimals, including `.5`, `12.`, and scientific notation such as `1e3`. Units accept any letter case of `kg`, `szt`, or `szt.` and normalize to `kg` or `szt.`; a missing unit means `kg`. Unknown units are rejected.

IDs persist with scans and appear in CSV reports. They are display-only: duplicate checks, RW item updates, raw-material pallet expansion keys, and desktop Excel comparisons ignore them. Older scans have a blank ID. Lot numbers retain their case and leading zeros; different lots stay separate in inventory duplicate checks, RW item updates, selection/deletion, and raw-material pallet grouping. Lots persist through local storage and RW backup/recovery, appear on scan lists and RW PDFs, and occupy the final `Nr partii` column in CSV exports. Labels without a lot keep a blank lot. The desktop inventory app shows IDs in both tabs and includes them in exported tables; grouped raw materials list their distinct scanned IDs together.

The desktop raw-material comparison keeps scanned quantities separate by unit. If one material has several units, it shows the amounts with their units, leaves the difference blank, and marks the row **Do sprawdzenia** rather than adding kilograms to pieces. This review state has its own count and filter. The Excel source does not supply a unit conversion, so these rows require review against Excel.

### CSV format

Semicolon-separated, UTF-8 with BOM, decimal comma — opens correctly in Polish Excel:

```
Lp;Alejka;Produkt;Ilość;Jednostka;Inicjały;Data naklejki;Data skanowania;ID;Nr partii
1;1;ALBU T4D;500;kg;XX;2026-07-07 10:00:00;2026-07-07 12:31:05;aB3dE_fG;0001A
...
Liczba palet;12
Łączna ilość (kg);6250
```

---

# Developer guide — from zero to running app

You need exactly **one** tool: [Android Studio](https://developer.android.com/studio) (free). It bundles the JDK, Android SDK, emulator and Gradle — nothing else to install.

> **⚠️ OneDrive warning (important!)**
> This project lives inside a OneDrive-synced folder. Gradle builds create thousands of small files and OneDrive's file locking regularly breaks Android builds ("file in use", endless sync churn).
> **Recommended:** copy `AndroidApp/` to a local path like `C:\dev\AndroidApp` and work there; copy source changes back when done. At minimum, pause OneDrive sync while building.

## 1. First-time setup

1. Install Android Studio (default options are fine). First launch downloads the Android SDK — accept the licenses when asked.
2. **File → Open…** and select the `AndroidApp` folder (the one containing `settings.gradle.kts`). Don't open the repo root.
3. Wait for **Gradle sync** to finish (bottom status bar; first time takes several minutes — it downloads Gradle 8.9 and all libraries; internet required *once*).
4. If Studio complains about a missing SDK version, click the suggested **Install/Fix** link in the error — it installs it automatically.

## 2. Running on the emulator (no phone needed)

1. **Device Manager** (right-hand sidebar) → **Create Virtual Device** → pick e.g. *Pixel 8* → pick a recent system image (API 34/35, it will download) → Finish.
2. Press the green **Run ▶** button (or `Shift+F10`). The app installs and launches in the emulator.

### Scanning QR codes in the emulator (yes, this works!)

The emulator has a *virtual scene* camera you can put an image into:

1. In the emulator toolbar click **⋯ (Extended controls) → Camera → Virtual scene images** and set **Wall** to a QR code image (see "Generating test QR codes" below).
2. In the app, start a session so the camera opens.
3. Click into the emulator window, hold **Alt** and move with **W/A/S/D** + mouse to "walk" toward the wall with the poster until the QR fills the view. The app should beep and show **OK**.

Alternative without the virtual scene: run the app on a real phone and scan a QR displayed on your monitor — works fine straight from the screen.

## 3. Running on a real phone

1. On the phone: **Settings → About phone → tap "Build number" 7×** to unlock developer options, then **Settings → Developer options → enable "USB debugging"**.
2. Connect via USB, accept the "Allow USB debugging?" prompt on the phone.
3. The phone appears in Android Studio's device dropdown — select it and press **Run ▶**.
   - *No cable?* **Developer options → Wireless debugging**, then in Studio: device dropdown → **Pair Devices Using Wi-Fi** and scan the pairing QR code.

## 4. Building an APK for the operators' phones

### Debug build (quick testing)

1. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or from the command line: `gradlew.bat assembleDebug`.
2. Result: `app/build/outputs/apk/debug/app-debug.apk`.
3. Send that file to each phone (e-mail, Teams, USB, SharePoint). Opening it on the phone prompts to install — allow "install from unknown sources" for the app you opened it with.

### Production build (signed release — what to actually hand out to operators)

Phones with the updater installed can fetch later signed builds through **Ustawienia → Sprawdź aktualizacje**. See [server publishing and update behavior](docs/app-updates.md). Keep the same signing key/application ID and increase `versionCode` for every release.

The project is wired up for a **signed release build**, using a real keystore instead of the debug signature. This matters because Android checks the signature on every install: as long as every release is signed with the *same* key, operators can update the app by simply installing the new APK over the old one — their sessions stay intact. Switch signing keys later and every phone would need a manual uninstall first, wiping any unsent sessions.

**One-time setup (already done in this repo, documented here in case it needs to be redone):**

```bash
keytool -genkeypair -v -keystore keystore/almara-skaner-release.jks \
  -alias skaner_inwentaryzacji -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <choose-a-strong-password> \
  -dname "CN=Almara, OU=IT, O=Almara sp. z o.o. sp. k., L=Warszawa, ST=Mazowieckie, C=PL"
```

(Run via the JDK bundled with Android Studio, e.g. `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"` on Windows.) Then create `AndroidApp/keystore.properties` (already gitignored, alongside `keystore/`):

```properties
storeFile=keystore/almara-skaner-release.jks
storePassword=<the password you chose>
keyAlias=skaner_inwentaryzacji
keyPassword=<same password — modern keytool's PKCS12 format requires store and key password to match>
```

`app/build.gradle.kts` reads this file automatically and signs the `release` build type with it. If `keystore.properties` is missing, release builds still compile but come out **unsigned** (fine for CI checks, not installable as-is).

> **⚠️ Back up `keystore/almara-skaner-release.jks` and `keystore.properties` somewhere outside this checkout** (a password manager attachment, a company secrets vault) — both are gitignored on purpose (a signing key must never sit in source control) but that also means a lost laptop or a wiped repo clone loses them for good. Losing the keystore means every operator's phone needs a fresh uninstall + reinstall to receive any future update, and old scan sessions on their phones would be lost in the process.

**Build it with the production release script:**

```bash
build_release.bat
```

The script uses `keystore.properties` and enables v1, v2, and v3 signatures. The v1 signature is needed by some MIUI installers and is not enabled by the plain Gradle release build at this minimum SDK.

Result: `app/build/outputs/apk/release/app-release.apk` — this is the file to distribute to operators going forward. Install it over the existing release without uninstalling the app or clearing its data. Each update must keep the application ID and signing key and increase `versionCode`. Verify it's actually signed with your key any time via:

```bash
"%ANDROID_HOME%\build-tools\<version>\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

**If a phone already has the debug build installed**, Android will refuse to install the release build over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` — different signing key) until the debug copy is uninstalled first. From then on, release-over-release updates install cleanly.

## 5. Debugging

- **Logcat** (bottom tab in Studio) shows the live device log. Filter by `package:pl.almara.inwentaryzacja` to see only this app, including stack traces of any crash.
- Set breakpoints in the Kotlin code and use **Debug 🐞** instead of Run — execution pauses like in any IDE.
- Scanning logic lives in `ScanActivity.kt` (`handleQr()` is the single entry point for every decoded QR — a good first breakpoint).
- App data location (for inspection): **View → Tool Windows → Device Explorer** → `/data/data/pl.almara.inwentaryzacja/files/sessions/` — one JSON file per session.

## 6. Generating test QR codes

Use the existing generator's venv (has `qrcode` installed), or any Python with `pip install qrcode[pil]`:

```bash
python -c "import qrcode; qrcode.make('ALBU T4D | 500kg | 2026-07-07 10:00:00 | KK').save('test_qr.png')"
```

Make a few variants (different names/weights), plus useful edge cases:

```bash
python -c "import qrcode; qrcode.make('Produkt z polskimi znakami ĄĘŻŹ | 123,5kg | 2026-07-07 11:00:00 | KK').save('test_qr2.png')"
python -c "import qrcode; qrcode.make('to nie jest naklejka').save('test_qr_bad.png')"
python -c "import qrcode; qrcode.make('Produkt | 500kg | 2026-07-07 10:00:00').save('test_qr_no_initials.png')"  # rejected: no initials
python -c "import qrcode; qrcode.make('Produkt | 500kg | 2026-13-45 10:00:00 | KK').save('test_qr_bad_date.png')"  # rejected: invalid date
python -c "import qrcode; qrcode.make('Produkt | 500kg | 2026-07-07 10:00 | KK').save('test_qr_no_seconds.png')"  # accepted: normalized to 2026-07-07 10:00:00
```

You can also print real stickers with `GeneratorNaklejek` — that's the true end-to-end test.

## 7. Test checklist

| # | Scenario | Expected |
|---|----------|----------|
| 1 | New session, scan a valid QR | Green **OK** banner, beep, counter +1 |
| 2 | Scan the **same** QR again | Orange **Już zeskanowano!**, error tone, counter unchanged |
| 3 | Two QRs shown within 2 s | Second one ignored (cooldown) |
| 4 | Scan a non-sticker QR (e.g. a URL) | Red **Nieprawidłowy kod QR** |
| 5 | Polish characters + comma weight (`123,5kg`) | Parsed correctly, weight shows as 123.5 |
| 6 | Kill the app mid-session, reopen | Session and items still there |
| 7 | Airplane mode ON, scan several stickers | Everything works (offline) |
| 8 | **Wyślij CSV** → pick Outlook/OneDrive | File attaches/uploads; open in Excel: Polish chars OK, columns split on `;`, sum row correct |
| 9 | **Zapisz CSV** | File saved to the chosen folder |
| 10 | Long-press a session on the main list | Delete confirmation dialog |

## 8. Troubleshooting

| Problem | Fix |
|---|---|
| Gradle sync fails with license errors | **Tools → SDK Manager**, install the requested SDK; or click the "Accept licenses" link in the error |
| `gradle-wrapper.jar` missing / corrupt | Android Studio: **File → Sync Project with Gradle Files** re-downloads it; or reinstall from any Gradle 8.9 distribution |
| Build errors mentioning file locks / "cannot delete" | You're building inside OneDrive — see the warning above |
| Camera is black in emulator | Extended controls → Camera → make sure *Virtual scene* is the back camera; cold-boot the emulator |
| App installs but camera permission dialog never appears | Uninstall + reinstall, or grant Camera manually in phone Settings → Apps |
| Share sheet has no SharePoint option | Install the OneDrive or Teams app on the phone and sign in with the company account |

## Debug and reinstall recovery

Debug installs beside Release as **(debug) Skaner Inwentaryzacji** with application ID `pl.almara.inwentaryzacja.debug`. Each build has separate registration and history. After reinstall, save the server address to recover that build’s phone number, server counters, approved PZs (including original PDFs) and finalized RWs. Existing installations must sync the updated app once before uninstalling to bind their device identity. Unsynced data, drafts and inventory sessions cannot be recovered. See [device recovery](docs/device-recovery.md).

## 16 KB page size compatibility (Android 15+/16 devices)

The app bundles native libraries (ML Kit's QR decoder, CameraX JNI), so 16 KB alignment matters:

- **CameraX must stay ≥ 1.4.0** (1.3.x was 4 KB-aligned and crashes on 16 KB devices) — currently pinned to 1.5.3. This also includes the Android 17 dynamic-range profile fix required by the updated emulator; see [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera#1.5.2).
- **ML Kit barcode-scanning ≥ 17.3.0** — 16 KB-ready per Google's release notes; don't downgrade.
- AGP ≥ 8.5.1 (we use 8.7.3) handles the required zip alignment automatically.

To verify: build the APK, then in Android Studio open it via **Build → Analyze APK** — the alignment check flags any non-16 KB `.so`. Or from the command line: `zipalign -c -P 16 -v 4 app-debug.apk`. To test at runtime, create an emulator with a system image whose name ends in **"16 KB Page Size"** (SDK Manager → system images, Android 15+) and run the full test checklist on it.

## Project structure

```
AndroidApp/
├── app/src/main/java/pl/almara/inwentaryzacja/
│   ├── MainActivity.kt           # session list, new session dialog
│   ├── ScanActivity.kt           # camera + ML Kit scanning, cooldown, status banner
│   ├── SessionDetailActivity.kt  # item list, CSV export buttons
│   ├── SessionStore.kt           # JSON-file persistence (one file per session)
│   ├── QrParser.kt               # "Nazwa | 500kg | data" payload parser
│   ├── CsvExporter.kt            # Excel-PL CSV + share intent
│   └── Models.kt                 # Session / ScanItem data classes
├── app/src/main/res/             # layouts, Polish strings, icon
└── build files (Gradle 8.9, AGP 8.7, Kotlin 2.0, minSdk 26 = Android 8.0+)
```

Key tech: **CameraX** (camera preview + frame analysis) and **ML Kit barcode-scanning** with the *bundled* model — QR decoding runs entirely on-device, no Google Play Services download or network required.
