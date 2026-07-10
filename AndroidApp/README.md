# Skaner Inwentaryzacji — Android

Android app for warehouse operators: scan pallet QR stickers (printed by `GeneratorNaklejek`) hands-free, collect them into a session, and deliver the results as a CSV file via e-mail or SharePoint — fully **offline**, no network needed during scanning.

## How it works (operator's view, UI is in Polish)

1. **Nowa sesja** — start a new inventory session (name defaults to `Inwentaryzacja <date>`).
2. Point the camera at a sticker. The app scans automatically — no button press:
   - 🟢 **OK: <product> (<weight> kg)** — recorded, with a beep + short vibration.
   - 🟠 **Już zeskanowano!** — this exact QR code is already in the session; it is not added again (error tone + long vibration). Note: stickers printed as *copies* share the same QR code, so each pallet needs its own individually printed sticker to be countable.
   - 🔴 **Nieprawidłowy kod QR** — QR found but not in the expected `Nazwa | 500kg | data` format.
   - After every read there is a **2-second cooldown** before the next scan.
3. **Zakończ** — shows the session summary (pallet list, count, total weight).
4. Export:
   - **Wyślij CSV** — opens the Android share sheet; pick Outlook/Gmail to e-mail it, or OneDrive/Teams to upload to SharePoint. The file is handed to the chosen app, which uploads whenever connectivity is available.
   - **Zapisz CSV** — save the file to a folder on the phone (e.g. a OneDrive-synced folder). A notification confirms the save; tapping it opens the file (on Android 13+ the first save asks for notification permission).

Sessions are stored on the device (app-private storage) and survive app restarts and offline periods. Data is only removed when you delete a session (long-press it on the list, or the **Usuń sesję** button).

### QR payload contract

Same format as the desktop tools: `ALBU T4D | 500kg | 2026-07-07 10:00:00 | XX` (name | weight | print date | operator initials). All four segments are required and the date must be a valid `yyyy-MM-dd HH:mm:ss` timestamp — anything else is rejected as **Nieprawidłowy kod QR**. Weight accepts `,` or `.` decimals.

### CSV format

Semicolon-separated, UTF-8 with BOM, decimal comma — opens correctly in Polish Excel:

```
Lp;Produkt;Waga (kg);Inicjały;Data naklejki;Data skanowania
1;ALBU T4D;500;XX;2026-07-07 10:00:00;2026-07-07 12:31:05
...
Liczba palet;12
Łączna waga (kg);6250
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

For internal use the **debug APK** is the simplest (no signing setup):

1. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
2. Result: `app/build/outputs/apk/debug/app-debug.apk`.
3. Send that file to each phone (e-mail, Teams, USB, SharePoint). Opening it on the phone prompts to install — allow "install from unknown sources" for the app you opened it with.

Command line alternative (from the `AndroidApp` folder, Windows): `gradlew.bat assembleDebug`

> For a Play-Store-quality signed release build you'd use **Build → Generate Signed App Bundle / APK** and create a keystore — not needed for internal distribution.

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

## 16 KB page size compatibility (Android 15+/16 devices)

The app bundles native libraries (ML Kit's QR decoder, CameraX JNI), so 16 KB alignment matters:

- **CameraX must stay ≥ 1.4.0** (1.3.x was 4 KB-aligned and crashes on 16 KB devices) — currently pinned to 1.4.2.
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
