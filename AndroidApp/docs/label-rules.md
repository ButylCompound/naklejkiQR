# Label parsing rules

The phone reads one physical label with its barcode decoder and bundled Latin text recognizer. These models run offline. The rule bundle interprets their output; it does not change the decoder or OCR model. The operator reviews the result and can edit material, lot, quantity, unit, and condition.

Publish the complete JSON file at `GET /materials/label-rules`. The app's configured addresses contain only the server root. The API client automatically appends `/materials` before the `/label-rules` suffix; for example, `https://warehouse.example` produces `https://warehouse.example/materials/label-rules`. Start with [the bundled rules](../app/src/main/assets/label-rules.json), which include sample inputs and expected results. See [the schema](label-rules.schema.json) and [observed manufacturer formats](label-analysis.md).

## Refresh and publishing

In this workspace, every rule change must update `/mnt/c/dev/serverAPI/assets/label-rules.json` and the Android bundled fallback. Validate the rules and regressions, then run `bun run rules:publish assets/label-rules.json` from `/mnt/c/dev/serverAPI`. Editing the server asset alone does not update its live SQLite snapshot. Verify the published bundle through `/materials/label-rules` after publishing.

The app downloads rules during normal background sync, after the catalogue refresh and before pending approved-PZ uploads. Sync runs on app launch, after saving server settings, on manual sync, and periodically when connected. Android decides when background jobs run. The existing connection-test button checks the catalogue only.

A bundle must pass structure checks, RE2 compilation, capture-group checks, and every included self-test before replacing the cached file atomically. A failed download or rejected bundle retains the last good cache. First use falls back to the rules shipped with the app. Rule errors do not prevent pending PZ backups.

Serve one complete file through both LAN and Tailscale routes. Validate a proposed replacement before publishing it atomically. Set `updatedAt` to its publication time with a UTC offset. The phone treats it as descriptive metadata, downloads the complete file, and does not require it to be newer; an intentional rollback works too. Settings shows the active rules' timestamp.

New rules affect new captures, including an explicit rescan of an editable entry. Saved entries retain the suggestions from their last captured label. Approved documents remain locked and never get reparsed.

## Bundle shape

`updatedAt`, `gs1`, `rules`, and `tests` are required. Optional `ocrWarningThreshold` sets the warning cutoff for OCR confidence (0–1, default 0.80):

```json
{
  "updatedAt": "2026-10-27T14:00:00+01:00",
  "gs1": true,
  "ocrWarningThreshold": 0.8,
  "rules": [],
  "tests": []
}
```

`gs1` enables the app's built-in GS1 reader. It recognizes AI 00, 01, 02, 10, 11, 15, 17, 21, 37, and 310n, preserving FNC1 (ASCII 29) separators. It offers AI 10 as a manufacturer lot only alongside a valid GTIN in AI 01/02. A standalone AI 10 on the supplied distributor labels contains a delivery identifier instead. AI 310n supplies net kilograms; it requires a valid GTIN or explicit GS1 notation/AIM prefix. AI 00, 21, and 37 never become a lot or material quantity. Unknown/truncated AI sequences are rejected rather than guessed.

## Rules

Every rule has a unique nonblank `id`, `source`, `formats`, `pattern`, and `fields`. The reserved ID `gs1` belongs to the built-in reader.

| Property | Meaning |
| --- | --- |
| `source` | `barcode` applies to each decoded code separately; `text` applies to OCR text from the captured label. |
| `formats` | Allowed barcode formats. An empty array allows all formats for barcode rules; text rules must use an empty array. |
| `pattern` | RE2 regular expression. Every match can supply candidate values. Captures are numbered from 1; group 0 is the whole match. |
| `contextPattern` | Optional RE2 expression that must match the OCR text before the rule runs, including for a barcode rule. |
| `fields` | One to five different output fields: `materialName`, `materialId`, `lot`, `quantity`, `unit`. |

Format names: `QR_CODE`, `CODE_128`, `CODE_39`, `CODE_93`, `DATA_MATRIX`, `PDF417`, `AZTEC`, `EAN_13`, `EAN_8`, `UPC_A`, `UPC_E`, `ITF`, `CODABAR`.

Patterns use [RE2/J](https://github.com/google/re2j), rather than Java's backtracking engine. Inline flags such as `(?i)`, `(?m)`, and `(?s)` are supported. Backreferences and lookaround are unsupported. JSON needs doubled backslashes: write `"\\s+"` for regex `\s+`. Patterns must not match an empty string.

Each field uses exactly one of `group` (integer) and `value` (literal string). Optional `prefix` and `suffix` wrap the trimmed source value. A `materialId` must accompany `materialName` in the same rule. Use a stable ID from the materials catalogue; the phone uses that catalogue name when the ID exists. Otherwise it keeps the suggested name. Without an ID it matches only an exact, case-insensitive catalogue name; ambiguous names stay manual.

An OCR `materialName` capture can include optional `fuzzy` options. They compare the captured text with a fixed list of canonical choices before applying the field's prefix and suffix:

```json
{
  "field": "materialName",
  "group": 1,
  "prefix": "BUTYL ",
  "fuzzy": {
    "choices": ["IIR-1953"],
    "maxDistance": 2
  }
}
```

Keep the rule's pattern and manufacturer/product context specific. Current fuzzy rules cover Chambroad IIR-1953, STANDARD PRS A1100, and the printed Exxon 268S grade. Examples include `DR-1953`, `A11OO`, and `Z68S`. Choices contain the canonical captured value, not the prefix. Add further known choices/rules on the server when new label examples are verified.

`maxDistance` is an integer from 1 to 3; `choices` contains 1–100 distinct normalized strings, each at most 512 characters and at least three normalized letters/digits. Matching ignores case, accents, spaces, and punctuation except `|`. It allows letter edits and known OCR digit/letter pairs (`0/O/Ø`, `1/I/L/|`, `2/Z`, `5/S`, `6/G`, `8/B`). It does not insert/delete an actual digit or replace one actual digit with a different digit. Lot numbers, quantities, units, IDs, and barcode rules cannot use fuzzy matching.

Only the nearest choices within the limit become suggestions. Equal nearest choices remain separate for the operator to choose; a unique result can fill an empty material field. An exact candidate wins over an approximate candidate for the same field/value/unit/material ID. Actual corrections carry `approximate: true` in the corresponding `label.values` item; an exact match omits it. OCR evidence keeps its original text.

The entry form marks an applied approximate material with ⚠️ and a pale yellow background. Its optional entry-level `approximateMaterial` flag survives draft save/reopen and rotation; manually editing or selecting a catalogue material clears it. Raw suggestion metadata remains available after manual correction. Neither flag changes quantities, grouping, comparisons, or PDF material names.

The matcher and warning display require the Android update that introduces `fuzzy`. Later edits to choices, contexts, patterns, and thresholds use the normal server refresh. Earlier clients reject the extended bundle and retain their last good cached rules. The server's [backup schema](server-api.schema.json) accepts both optional warning flags.

### OCR confidence warnings

Set `ocrWarningThreshold` in `/mnt/c/dev/serverAPI/assets/label-rules.json`, validate, then publish with `bun run rules:publish assets/label-rules.json`. The parameter is included in `GET /materials/label-rules` through both routes. Phones use it after the next rule refresh; no further APK update is needed for threshold changes once they have the confidence-enabled app.

A field warns when its score is **strictly below** the threshold. Raising the threshold produces more warnings. Setting it to 0 disables low-confidence warnings while retaining fuzzy-match warnings. The starting value 0.80 is a review setting, not a calibrated probability of correctness.

The phone retains ML Kit's word and line scores with their positions in the final OCR text, after rotation/layout ordering. Optional `label.ocrWords` and `label.ocrLines` arrays contain `{ "start": 0, "end": 10, "confidence": 0.73 }` spans. Positions use UTF-16 offsets, with an inclusive start and exclusive end. Scores range from 0 to 1; zero is a valid low score. Missing metadata means unknown confidence and is never converted into a made-up score.

An OCR suggestion's optional `confidence` is the minimum word score overlapping its captured value. If there are no word scores there, the app falls back to overlapping line scores. Literal units use the unit's location in the matched text where possible. Unrelated address/header words do not lower a captured lot or quantity score. Optional `lowConfidence: true` records that the score fell below the threshold. Equal suggestions use their strongest exact evidence; a decoded barcode providing the same value wins without an OCR score. Conflicting values still require an operator choice.

Low-confidence material, lot, quantity, and unit fields use ⚠️ and the existing pale yellow background. The helper explains whether the cause is fuzzy matching, low OCR confidence, or both. Entries retain applied low-confidence warnings in optional `lowConfidenceFields`, an array of `materialName`, `lot`, `quantity`, and/or `unit`. Draft saves and rotation preserve them. Manually editing or selecting a field clears only that field's warning; the original scan's scores and suggested values remain unchanged in its evidence.

Threshold changes affect subsequent captures/reparsing, not saved entries or locked documents. Old reads have no confidence data; scan again to obtain it. Confidence flags do not alter values, grouping, comparisons, or PDF contents. A high score does not rule out an incorrect digit. See [ML Kit word confidence](https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.Element#getConfidence()).

A `quantity` field must accompany a `unit` field in the same rule. Supported units are `kg`, `szt`, and `szt.`, case-insensitive; stored units are `kg` or `szt.`. Quantity must be positive. It uses exact decimal arithmetic and returns a decimal string:

| `numberFormat` | Example input | Result |
| --- | --- | --- |
| `decimal` (default) | `175,000` or `175.000` | `175` |
| `decimal-dot` | `175.250` | `175.25` |
| `decimal-comma` | `175,000` | `175` |
| `thousands-dot` | `1.200` or `1.200,5` | `1200` or `1200.5` |

Only quantity fields use `numberFormat` and `factor`. An optional positive decimal-string `factor` multiplies the parsed number, for example `"0.001"` for confirmed grams-to-kilograms conversion. Never apply a guessed package count or weight conversion.

Example for a new confirmed code layout:

```json
{
  "id": "supplier-x-bag",
  "source": "barcode",
  "formats": ["QR_CODE"],
  "pattern": "^MAT=ABC;LOT=([A-Z0-9-]+);NETG=([0-9]+)$",
  "fields": [
    {"field": "materialName", "value": "Materiał ABC"},
    {"field": "materialId", "value": "123"},
    {"field": "lot", "group": 1},
    {"field": "quantity", "group": 2, "factor": "0.001"},
    {"field": "unit", "value": "kg"}
  ]
}
```

OCR text preserves recognized characters. The app accounts for the dominant text angle, then joins lines at similar vertical positions from left to right, with newlines between rows. Allow normal whitespace and observed printing variants, including separated product grades and reversed heading/value order. Avoid broad patterns that can capture a heading, delivery ID, gross weight, or order number. OCR is fallible; do not silently replace lookalike lot characters such as O/0.

Rules accumulate distinct candidates; order does not pick a winner. Conflicting candidates require a choice. The form fills empty fields only when a candidate is unambiguous. Rescans preserve existing input and offer replacements. Condition, unknown-lot explanation, damage comment, and delivery verification always remain operator choices.

## Self-tests and limits

Each test contains `codes` (objects with `raw` and `format`), `text`, and `expected`; `name` is optional. An expected string requires exactly one distinct suggested value for that field. An expected null requires no suggested value. Unlisted fields are unchecked. `materialId` tests check the ID paired with a material suggestion.

```json
{
  "name": "Supplier X net grams",
  "codes": [{"raw": "MAT=ABC;LOT=0007;NETG=25000", "format": "QR_CODE"}],
  "text": "",
  "expected": {
    "materialName": "Materiał ABC",
    "materialId": "123",
    "lot": "0007",
    "quantity": "25",
    "unit": "kg"
  }
}
```

Keep negative tests for shipping IDs, tracking links, gross weights, missing fields, and known decimal ambiguities. Include both barcode-only and OCR inputs. The supplied bundle contains regression examples; keep them when extending it.

Limits: 200 rules and 200 tests; pattern/context length 2048; rule ID length 128; field literals 512; prefixes/suffixes 128. A field's capture group must exist. Each rule/input can match at most 32 times; a read may produce at most 128 distinct candidates. Inputs allow at most 10 codes, 8192 characters per code, and 65536 OCR characters. Factors allow up to 12 integer and 12 fractional digits. The schema checks the JSON shape; it cannot check RE2 syntax, group references, field uniqueness within a rule, paired fields, or execute self-tests. Those checks also run in the Android client.

## Recorded label data

A scanned PZ entry includes optional `label` in its JSON backup. A manual entry omits it. The label is the latest captured evidence, rather than a history of every rescan. Photos are processed on the phone and are not retained or uploaded.

```json
{
  "capturedAt": "2026-10-27T14:00:00+01:00",
  "codes": [{"raw": "PIB32 / SPXFPG005E / 20260721 / 175,000", "format": "QR_CODE"}],
  "text": "PIB32\nPESO LIQUIDO (KG) 175,000",
  "rulesUpdatedAt": "2026-10-27T14:00:00+01:00",
  "ruleIds": ["braskem-pib-qr", "braskem-printed-net"],
  "values": [
    {"field": "materialName", "value": "Poliizobuten PIB32", "unit": null, "materialId": null},
    {"field": "lot", "value": "SPXFPG005E", "unit": null, "materialId": null},
    {"field": "quantity", "value": "175", "unit": "kg", "materialId": null},
    {"field": "unit", "value": "kg", "unit": null, "materialId": null}
  ]
}
```

Store raw code strings, including spaces and separators, without treating them as commands or opening URLs. `sourceCode` and `barcodeFormat` hold the first decoded code from that capture, or null when OCR found text without a code. These values do not identify a unique physical entry.

The entry's top-level material, lot, quantity, and unit are the confirmed business values. They may differ from `label.values` after manual correction. Summaries and PDFs use those confirmed values; evidence is retained in JSON, not added as raw OCR to the PDF. Do not reparse or rewrite received approved documents when server rules change.

JSON rules can add layouts, patterns, catalogue mappings, and numeric conversions supported by this contract. A new barcode decoder, encrypted/binary format, or OCR model requires an app change. No remote app-update mechanism is needed for the supplied formats.

