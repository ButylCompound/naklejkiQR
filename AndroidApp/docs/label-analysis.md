# Supplied product-label findings

Examined all 21 photos in `temp_samplelabels`. Raw photos remain there; they are not bundled into the app. Barcode decoding and Android's bundled barcode/OCR models were both checked. [Recorded Android reads](../app/src/test/resources/label-photo-reads.json) retain the exact decoded strings and OCR text as regression fixtures.

The formats below describe these observed labels. They are not manufacturer-wide specifications. Never assume that every future label from the same company has this layout.

| Labels / photos | Encoded data | Printed data used by OCR | Fields left for the operator |
| --- | --- | --- | --- |
| STANDARD, PRS A1100: `1.jpeg`, `2.jpeg`, `105457`, `105506`, `105753` | GS1 Code 128 delivery identifier, e.g. `]C110260001773503`; not the manufacturer lot | Żywica węglowodorowa PRS A1100; Numer serii 2604075 (2604020 in 2.jpeg); net 700 kg | Review all fields; barcode was unreadable in 2.jpeg, but OCR read the fields |
| Chambroad blue Butyl Rubber 1953: `105303`, `105313`, `4.jpeg` | QR contains grade 1953 and lot 4240622319 (3240622319 in 4.jpeg), plus production time and plant data; no weight | NET WEIGHT 1200 kg | Review the OCR weight |
| Chambroad pale IIR-1953: `105331`, `105348`, `105704`, `3.jpeg` | QR is a report URL with traceid; it does not expose material, manufacturer lot, or net weight | IIR-1953; LOT NO. 241013 / 241012 / 250607 / 260502; NET 1200 KG | Review OCR fields; no automatic lookup of the URL |
| Convent / Biesterfeld: `105629` | GS1 Code 128 shipping/NVE identifier, not the manufacturer lot | BUTYL 268S 25 KG BAL; Charge T26013025H; gross/net 1.225 / 1.200 kg | Review the selected net value: 1200 kg, rather than 1225 kg, 25 kg per bale, or 48 bales |
| Exxon Butyl: `105634`, `105643` | Code 39 contains grade 268S, lot T26013025H, package serial, and 1200 kg net | Dot-matrix text independently prints lot and net weight, but some characters are misread | Review; use decoded data when the text is faint, and choose explicitly if candidates conflict |
| Transmec / Speciality Minerals: `105725`, `105738` | Code 39 shipping/pallet numbers 267453 and 267459, with trailing spaces | PRODUCT: CALOFORT SV800 HTCP3 | Lot and material quantity are absent: enter them manually or explain an unknown lot; PCS 1 and order E15741 are not substitutes |
| Braskem PIB32: `105831`, `105850` | QR contains material PIB32, lot SPXFPG005E, production date 20260721, and net 175,000 | Portuguese/English print confirms 175 kg net and 191.2 kg gross | Review; use the QR lot rather than guessing OCR O/0 substitutions |
| STANDARD PIB32 distributor overlabel: `105836`, `105843` | Delivery identifier, where readable | Poliizobuten PIB32; net 175 kg; Numer serii: na opakowaniu | The label points to the manufacturer package for the lot. Scan the Braskem label on that same physical drum, enter the lot manually, or explain an unknown lot |

Photo numbers in the table abbreviate `20261027_HHMMSS.jpg`, e.g. `105831` means `20261027_105831.jpg`.

## Exact encoded examples

### Braskem

```text
PIB32 / SPXFPG005E / 20260721 / 175,000
```

Slash-separated product / lot / production date / net kilograms. The comma is a decimal separator: 175,000 means 175 kg. The printed gross value must not replace it. Production date is retained in raw evidence; it does not replace the app's receiving timestamp or lot.

### Chambroad blue labels

```text
http://58.59.122.5:9999?4240622319&ph:1953&pc:4240622319&zxsj:2145-2200&dw:京博橡胶&bm:100044938
```

The QR contains the lot twice; the rule reads the explicit `pc:` field and product grade `ph:`. The URL-like string carries the data locally; the app does not need to contact the host. The weight comes from the printed NET WEIGHT field.

### Chambroad report labels

```text
https://kgwms.chambroad.com:12000/gemreport/a378fb790034440cb76502bf6b031aab.rpt?traceid=027821671
```

The same traceid appears on photos with different printed lots (250607 and 260502). This is direct evidence that traceid must not become the lot number. The app reads the printed lot instead.

### Exxon

```text
0BU268S0713T26013025H26013020051200
0BU268S0713T26013025H26013020031200
```

Android decoded these as Code 39. The observed structure is `0BU` + grade `268S` + `0713` + lot `T26013025H` + ten-digit package serial + four-digit net kilograms. This interpretation agrees with the human-readable code and the Convent label's lot and weight. Other Exxon formats need their own confirmed examples/rules. Do not infer material or lot from the top package serial.

### STANDARD / Convent

Android returns the GS1 AIM prefix, for example `]C110260001773503` and `]C1000000000000741102`. The independent decoder exposed raw bytes without that prefix. Both forms are preserved/supported. STANDARD's standalone AI 10 is a delivery identifier in these samples, so the generic GS1 reader does not offer it as a manufacturer lot. Convent's shipping ID is not a lot.

### Transmec

```text
267453          
267459          
```

The spaces are part of the decoded code. Neither payload identifies the manufacturer's lot or material weight. A single shipping pallet need not equal one unit of material.

## Recognition limits and workflow

A phone scan of the STANDARD PRS A1100 label returned the grade on the last line, `10.202 2604075` on a separate line, and `700 kg Masa netto.` in reverse heading/value order. The rule bundle includes that exact read as a self-test. The PRS name rule can find a separated grade; the net-weight rule accepts a value immediately before its heading. A separate PRS batch rule recognizes the observed expiry-date/seven-digit-series row only when the PRS name and `Numer serii` heading are present. It does not take the delivery barcode or an arbitrary seven-digit contact number as the lot.

The app's OCR formatter uses the recognized line angles and corner points to restore reading order before joining rows. This addresses rotated or tilted labels; it does not change recognized characters. See [ML Kit line geometry](https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.Line). Operator review and conflicting-value choices still apply.

The full `NET WEIGHT` heading also accepts one or two attached OCR characters of any kind before `NET`, such as `XNET WEIGHT` or `7QNET WEIGHT`, without requiring a readable manufacturer name. This covers the Chinese character/slash being recognized as stray letters, digits, or other glyphs. The complete `WEIGHT` heading and a numeric kilogram value remain required for that fallback; it does not match unrelated words such as `PLANET WEIGHT`, gross-weight headings, or piece counts. Existing manufacturer-specific rules still handle the observed longer prefixes and short `NET:` headings.

The supplied-photo regression checks expect material, lot, and net quantity on 17 photos. The two STANDARD PIB overlabels intentionally have no suggested lot. The two Transmec labels intentionally have neither a suggested lot nor a suggested material quantity. The [image stress test](label-stress.md) adds 189 altered images and records the remaining recognition limits, including wrong digits inside otherwise valid lots and weights.

OCR is fallible, especially on the Exxon dot-matrix print and with O/0 in Braskem lots. The app does not invent missing fields, automatically correct lot characters, fetch tracking URLs, or treat a shipping identifier as a lot. Each physical package gets its own entry even if another package has an identical code.

Use the capture button with one physical package's label in view. All candidates are suggestions. The operator can type replacements or select a catalogue material, rescan, inspect raw text/codes, and choose between conflicting candidates. Existing form values survive rescans. Condition and comments remain manual. Summaries and PDFs use only the confirmed fields.

The app keeps the latest raw codes, OCR text, rule timestamp/IDs, and suggestions in the entry's JSON backup. It does not retain or upload the captured image.

## Technical references

Earlier full-suite baseline: 72 JVM tests and 19 Android tests passed, including all 21 supplied photos, a real camera capture, inventory/RW preview frames, manual corrections and rotation, cache rejection, and scanned-PZ backup. The emulator used 16 KB pages; the debug APK's 64-bit native libraries also met 16 KB ELF alignment.

The latest image stress check completed 189 altered images with the Android barcode/OCR models. Rule replay improved exact complete results from 126 to 173, with no previously correct field regressing. A separate control through the final debug app matched all expected fields exactly on all 21 untouched originals. The final build, 77 JVM tests (including the 59 bundled rule self-tests), server rule checks, and Android lint passed. This also verifies the OCR geometry and rotation changes. See the [stress-test results and remaining errors](label-stress.md). Release builds and physical-phone upgrade checks remain deferred.

The later fuzzy-material update passes 81 JVM tests, 78 bundled self-tests, Android lint, and UI tests for warning persistence and manual overrides. It marks corrected material suggestions with ⚠️ and a pale yellow field, including the observed `DR-1953` read. The same 189 captured reads retain all previously correct fields under replay. Its server configuration and optional warning metadata are described in [label parsing rules](label-rules.md); lots and weights remain outside fuzzy correction.

The subsequent confidence update retains word and line scores, warns on low-confidence material/lot/quantity/unit fields, and exposes `ocrWarningThreshold` in the rule bundle. It passed 88 JVM tests, 67 server tests, lint, and three UI checks. The 21-original control and a 20-image difficult subset verified the actual model scores; [threshold measurements](label-stress.md#ocr-confidence-check) document both detected errors and correct values that still warn. The starting cutoff is 0.80 and can change through the server without another APK update once the confidence-enabled client is installed.

- [ML Kit barcode scanning on Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android) describes supported symbologies and image requirements. Symbol decoding alone does not interpret a manufacturer's payload.
- [ML Kit text recognition on Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) describes the bundled Latin model used here.
- [GS1 application identifiers](https://gs1.se/en/guides/how-to-guides/application-identifiers/) distinguishes product/lot, net weight, shipping-container IDs, and counts. The safeguards above follow the actual supplied-label evidence as well.
- [RE2/J](https://github.com/google/re2j) supplies the rule engine without backtracking patterns.

See [rule publishing](label-rules.md) for server updates without an APK release.

