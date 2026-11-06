# Label image stress test

The test uses all 21 photos in `temp_samplelabels` and the expected material, lot, and net quantity in `app/src/test/resources/label-photo-reads.json`. It creates nine versions per photo: a baseline, rotations by 90/180/270 degrees, a random tilt, Gaussian blur, additive noise, and two combinations of rotation, blur, noise, brightness, and JPEG compression.

Generation normalizes EXIF orientation and limits the source's longest edge to 2560 pixels for every variant, including the baseline. Rotations expand the canvas instead of cutting off the label. The originals remain unchanged. Seed `20261030` and each case's parameters are recorded in the manifest. Noise is generated from that seed, not from the operating system's random source.

The Android instrumentation test runs the app's `LabelVision` and bundled ML Kit barcode/OCR models on the generated images. It loads the APK's rule bundle directly, so an emulator's old cached rules cannot affect the result. It saves raw barcode/OCR evidence and parsed candidates for every case. Recognition misses do not stop collection; the test completes only after processing every case.

From `AndroidApp` in Windows PowerShell, with a disposable emulator running:

```powershell
..\GeneratorNaklejek\.venv\Scripts\python.exe tools\label_stress.py generate
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
..\GeneratorNaklejek\.venv\Scripts\python.exe tools\label_stress.py run --serial emulator-5580
```

The Python environment needs Pillow for generation. The runner uses the Windows Android SDK in `%LOCALAPPDATA%\Android\Sdk` and accepts emulator serials only. It installs the debug app and test APK on that emulator; it does not touch production phones.

Generated images, `manifest.json`, `reads.json`, `fields.csv`, `report.md`, and instrumentation output go into the ignored `build/label-stress` directory. Recreate the report without running OCR again:

```powershell
..\GeneratorNaklejek\.venv\Scripts\python.exe tools\label_stress.py report
```

After editing rules, replay the captured reads with the local server's RE2 engine from the workspace root in WSL:

```sh
bun AndroidApp/tools/replay_label_stress.ts /mnt/c/dev/serverAPI
python AndroidApp/tools/label_stress.py report --reads AndroidApp/build/label-stress/reads-reparsed.json --prefix after-
```

Replay keeps the original Android evidence and adds `reparsedValues` separately. It rejects any change that breaks a field which previously matched its expected value. `after-report.md` and `after-fields.csv` show the new candidates; they describe a rule replay, not a second OCR run. Gradle tracks the bundled rule JSON as an input to JVM tests, so rule-only edits rerun the bundle's tests.

The report distinguishes exact matches, missing suggestions, wrong single suggestions, and multiple conflicting suggestions. A field expected to be absent counts as correct only when the parser produces no candidate. Quantity and unit are checked separately. A read with one correct and one incorrect candidate is ambiguous, not correct. Every image requiring review links to its generated JPEG; the JSON retains its exact recognized text.

These synthetic results do not measure production accuracy. They cover the supplied label layouts, one emulator/model configuration, and a stated image size. They do not simulate glare, folds, occlusion, camera focus, motion blur, or all viewing angles. Keep uncertain lot characters unchanged and leave unreadable values for manual entry. Changes prompted by these tests must retain negative cases for shipping IDs, gross weight, and absent lots, and follow the [rule publishing procedure](label-rules.md).

## Results from 30 October 2026

The full 189-image collection completed on the disposable Android emulator with the bundled ML Kit models in 443 seconds. Its network was disabled after startup to stop unrelated Play Store updates. Replaying the same captured reads with the published rule fixes increased cases with every expected field correct from **126/189 to 173/189**. No field that previously matched its expected value regressed.

As a separate control, the final debug app processed all 21 untouched, full-resolution originals in 48 seconds. Every expected material, lot, and quantity matched exactly, including fields expected to remain empty. These reads used the final published bundle. They are stored in `build/label-review/label-recognition.json`. The 2560-pixel test baseline misses one Exxon lot, while its full-resolution original succeeds; label resolution therefore matters even without additional blur or noise.

The final debug build, 77 JVM tests (including the Android rule engine's 59 bundle self-tests), nine server rule tests, and Android lint passed. No release build was made.

| Variant | Before | After | Images |
| --- | ---: | ---: | ---: |
| Baseline at 2560 pixels / JPEG 92 | 15 | 20 | 21 |
| 90-degree rotation | 14 | 20 | 21 |
| 180-degree rotation | 16 | 21 | 21 |
| 270-degree rotation | 16 | 20 | 21 |
| Random tilt | 14 | 19 | 21 |
| Blur | 13 | 20 | 21 |
| Noise | 13 | 19 | 21 |
| Mild combination | 14 | 17 | 21 |
| Stronger combination | 11 | 17 | 21 |

The changes accept observed STANDARD series-heading errors (`seri`, `seii`, `serni`, `serik`, `Nume`, `umer`) and `Masa etto`. Chambroad rules recognize the printed Butyl Rubber 1953 grade when its QR is unreadable, the observed IIR heading errors, and bounded OCR characters attached to NET. Chambroad printed lots now require a complete six- or ten-digit field through the end of the line; `42406223 19` no longer suggests a shortened `42406223` beside the full QR lot. The parser leaves split digits for manual entry when no complete barcode lot exists.

Convent's net parser uses the net heading and the second of the two weight columns even when OCR damages the gross heading. It still requires the observed manufacturer/product context and both columns. The Exxon material-only fallback recognizes the intact `268S` grade in a damaged printed encoded string; it does not infer the rest of that string's lot or weight. Eight new negative cases protect shipping/order numbers, unrelated headings, incomplete weight columns, different grades, and corrupted lot characters. Thirty exact captured reads cover the corrected cases, bringing the bundle to 59 self-tests.

Sixteen altered images still need review: ten have missing values without a wrong candidate, four have a wrong single suggestion, and two have conflicting quantity suggestions. Examples:

- STANDARD lot `2604075` becomes `2504075` or `250407E`. The parser cannot establish the true digits from the recognized string alone.
- Exxon net `1200` becomes `1208`. With a decoded barcode, both candidates appear and require a choice; without it, the wrong OCR quantity can appear alone.
- Corrupted Exxon lot/grade characters and the most damaged Convent weight heading remain manual fields.

Rescanning closer and checking the printed lot/weight are still necessary. Fuzzy heading matching cannot validate a changed digit inside a lot or weight. No rule assumes that every pallet of a named material weighs the same amount.

The detailed local artifacts are [the original Android report](../build/label-stress/report.md), [the report after rule replay](../build/label-stress/after-report.md), and [the captured Android reads](../build/label-stress/reads.json). Generated images and raw reports stay in the ignored build folder. The test-run fixes were published in a 23-rule, 59-test bundle at `2026-10-30T10:07:28.099Z` (11:07 in Poland).

A later phone read used `Numer ser` before a correctly recognized number. A separate rule accepts that short heading with a complete seven-digit lot on the same or next line. It rejects truncated digits, delivery rows, and longer words such as `serwis`. Six new self-tests bring the bundle to 24 rules and 65 tests. The server serves it through both LAN and Tailscale; phones can refresh these rules without an APK update.

The English net-weight rule also accepts the observed `UNET: 1200KG` heading without requiring readable manufacturer text. The earlier Chambroad-specific rule already accepted that prefix, but its manufacturer check could fail after an OCR error in `CHAMBROAD`. Four more tests cover `UNET`, damaged manufacturer text, the Chinese `/NET` heading, and rejection of unrelated words ending in `NET`, bringing the bundle to 69 tests.

An exact phone read beginning `DR-1953`, followed by `Butyl Rubber`, already supplied lot `260502` and `1200 kg` from `#UNET: 1200KG`. A guarded grade rule now recognizes that observed `DR` OCR variant as `IIR` only when both `CHAMBROAD` and `Butyl Rubber` appear. Its exact read and three negative cases bring the bundle to 25 rules and 73 tests.

## OCR confidence check

The confidence-enabled app retains word/line scores and maps them to captured field values. The starting server setting is `ocrWarningThreshold: 0.8`; see [configuration and metadata](label-rules.md#ocr-confidence-warnings).

All 21 original full-resolution labels were processed again. Their 885 recognized words had scores from 0.0703 to 0.9570, with a median of 0.7961. The parser retained 54 OCR-derived field scores and flagged 10 of them at 0.80, although all expected extracted values were correct. Decoded barcode values retain unknown OCR confidence and do not get low-OCR warnings.

A separate run processed the 16 altered images needing review plus four controls, without changing the original 189-image artifacts. It recorded six wrong candidate values and 61 correct candidate values. The six wrong candidates were the three STANDARD lot errors and three `1208` quantity errors. Their scores ranged from 0.5557 to 0.7913.

| Warning threshold | Wrong candidates warned (of 6) | Correct candidates warned (of 61) |
| --- | ---: | ---: |
| 0.65 | 3 | 8 |
| 0.70 | 3 | 11 |
| 0.75 | 4 | 15 |
| 0.80 | 6 | 20 |
| 0.85 | 6 | 35 |
| 0.90 | 6 | 41 |

This small subset deliberately includes difficult reads, and its errors repeat variants of two source photos. It supports 0.80 as a conservative starting cutoff; it does not establish production error rates or guarantee detection of future wrong values. Missing fields still require manual input. Review warnings do not block saving or correct lot/weight values automatically.

The local [confidence evidence](../build/label-confidence/reads.json), [candidate analysis](../build/label-confidence/confidence-analysis.json), and [image report](../build/label-confidence/report.md) retain the measured results. The confidence update passed 88 JVM tests, 67 server tests, Android lint, three UI checks for warnings/manual overrides, and both real-image runs. The rule bundle has 78 self-tests. No release APK was built for these checks.
