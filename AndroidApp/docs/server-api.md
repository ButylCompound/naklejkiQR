# Warehouse app JSON API

See [server implementation handoff](server-implementation.md) for access controls, storage/retry rules, SharePoint refresh setup, and acceptance checks. The routes below define the server layout under `/materials`; the message formats describe the Android payloads.

The Android app uses configurable primary and fallback base URLs, for example a local `http://192.168.10.20:8080` and a Tailscale `https://warehouse.example-tailnet.ts.net`. Configure them in **Ustawienia → Serwer i kopie zapasowe**. Both must reach the same device registry, catalogue, label rules, and document backup store. HTTP is also supported for a private LAN/Tailscale deployment. Restrict the service to the intended warehouse devices; the app embeds no Microsoft 365 client secret or shared API key. Microsoft 365 authentication and SharePoint refreshes belong on the server.

Paths shown below are complete server paths. The configured base URL contains only the server root (scheme, host, and optional port). The Android API client adds `/materials` centrally, followed by the client suffix from this table:

| Method | Complete server path | Client suffix |
| --- | --- | --- |
| POST | `/materials/devices/register` | `/devices/register` |
| PUT | `/materials/rw/{documentId}/backup` | `/rw/{documentId}/backup` |
| GET | `/materials/devices/{installationId}/documents` | `/devices/{installationId}/documents` |
| GET | `/materials/devices/{installationId}/documents/{module}/{documentId}` | `/devices/{installationId}/documents/{module}/{documentId}` |
| GET | `/materials/catalogue` | `/catalogue` |
| GET | `/materials/label-rules` | `/label-rules` |
| PUT | `/materials/pz/{documentId}/entries/{entryId}/photo` | `/pz/{documentId}/entries/{entryId}/photo` |
| GET | `/materials/devices/{installationId}/documents/PZ/{documentId}/entries/{entryId}/photo` | `/devices/{installationId}/documents/PZ/{documentId}/entries/{entryId}/photo` |
| PUT | `/materials/pz/{documentId}/backup` | `/pz/{documentId}/backup` |
| GET | `/materials/app-updates/latest` | `/app-updates/latest` |
| GET | `/materials/app-updates/{versionCode}/{sha256}.apk` | `/app-updates/{versionCode}/{sha256}.apk` |
| GET | `/materials/lots` | `/lots` |
| GET | `/materials/lots/{id}` | `/lots/{id}` |
| PATCH | `/materials/lots/{id}/qc` | `/lots/{id}/qc` |

For example, base `http://192.168.10.20:8080` plus the automatic prefix `/materials` and suffix `/catalogue` produces `http://192.168.10.20:8080/materials/catalogue`. Include the prefix once. The JSON catalogue response still uses the `materials` array.

Android adds `/materials` for every API request, including sync and connection testing. Enter only the server root in both address fields. Previously saved addresses ending in `/materials` normalize to the same root.

All request and response bodies are UTF-8 JSON except the binary APK download. Use the exact routes below, without a trailing slash or redirects. The client rejects redirects. Successful JSON responses must contain JSON rather than an empty HTTP 204 body.

See [app updates](app-updates.md) for the update manifest, APK download contract, and release publishing command. Update checks use the same server roots and fallback policy; they do not require phone registration. No update check runs during catalogue/rule sync.

See [label photos](label-photos.md) for JPEG upload, photo/read/confirmed-value pairing and recovery. Photos upload before their approved PZ backup commits.

See [delivered lots and QC](delivery-qc.md) for relational SQL storage, delivery-specific lot grouping, read filters/pagination, and the nullable `qcpass` and optional `qccomment` fields. QC changes do not alter approved PZ backups or their PDFs.

The app retries approved PZ backups independently. A failed PDF, photo, or document upload leaves an error on that PZ and does not block later receipts or finalized RWs. A successful retry replaces the error with the normal acknowledgment. Finalized RWs stay locked against edits and deletion even before their first server backup.


The associated [JSON Schema](server-api.schema.json) defines the objects under `$defs`. Validate the appropriate definition for each request/response. JSON property order is not significant, except when computing the checksum of the raw HTTP body described below.

The current client sends no API authentication header. The connection-test route is `GET /materials/catalogue` for each entered address; it validates each returned catalogue without saving settings, registering a phone, updating its cache, or uploading documents. No separate health route is required by the app.

Sync tries the primary address first and switches routes on transport failures, HTTP 408, or HTTP 5xx. It reuses the working route for that batch. Other HTTP errors and invalid responses remain errors. Repeated registration and backup calls through different addresses must use the same shared registry/storage and preserve idempotency.

## 1. Register a phone

`POST /materials/devices/register`

Request (`registrationRequest`):

```json
{
  "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
  "deviceName": "Xiaomi Mi 10",
  "deviceKey": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  "counters": {"PZ/2026": "12", "RW/2026": "7"}
}
```

Response, HTTP 200 or 201 (`registrationResponse`):

```json
{
  "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
  "phoneNumber": 3,
  "counters": {"PZ/2026": "12", "RW/2026": "7"},
  "recoveryRequired": false
}
```

`phoneNumber` is an allocated device number, not a SIM telephone number. Allocate a positive integer atomically. Retrying with the same installationId must return the same number. Different installations must receive different numbers. Never recycle a number. Bind registration and backup access to the permitted device/network identity on the server.

The client saves its installation UUID, assigned number, and yearly counters in Android no-backup storage. Updates retain them. After reinstall and saving a server address, an app-scoped device identifier recovers the canonical installation UUID, number, server counters, approved PZs and finalized RWs. Debug and Release have separate identities and histories. The client accepts a changed UUID only for an unregistered installation explicitly recovering its identity; an assigned number cannot change. See [device recovery and RW backup](device-recovery.md) for binding, full routes, payloads, retry rules and limits. Moving the API must preserve the registry and backups.

Document creation and approval always work offline, including before registration. Until registration succeeds, PP is 0, displayed as `00`, for example `PZ/2026/00/0001`. Saving the server base URL starts automatic background registration, catalogue refresh, and pending backups; failed attempts retry without blocking operators. The server must assign a positive number; 0 is reserved for unregistered documents.

Numbers use `PZ/YYYY/PP/NNNN` and `RW/YYYY/PP/NNNN`, with independent local counters for each module/year. PP is the phone number, padded to at least two digits. NNNN is the sequence, padded to at least four digits. Padding does not impose a maximum. Registration changes only the device component of future numbers; counters continue and approved documents/PDFs retain their original numbers. For example, `PZ/2026/00/0001` is followed by `PZ/2026/03/0002` after registration. Existing legacy RW numbers remain unchanged; the current-year RW sequence continues from its previous maximum.

Different unregistered phones may issue the same visible number. Use document UUIDs as identifiers for storage and backups, including for documents approved with device number 0.

## 2. Materials catalogue

`GET /materials/catalogue`

Response, HTTP 200 (`catalogue`):

```json
{
  "updatedAt": "2026-10-27T08:00:00Z",
  "materials": [
    {
      "id": "1",
      "name": "AOX 1010",
      "type": "dodatek",
      "group": "Dodatki",
      "suppliers": ["Torimex"]
    },
    {
      "id": "2",
      "name": "Materiał bez przypisanego dostawcy",
      "type": "",
      "group": "",
      "suppliers": []
    }
  ]
}
```

Return the complete catalogue in one response, not a page or delta. All five material properties are required. IDs are non-empty strings, unique within the catalogue, and stable across refreshes. Do not reuse an ID for a different material. `updatedAt` is an ISO 8601 timestamp with an offset, indicating the last successful source refresh. Retain the last complete snapshot if SharePoint is unavailable; do not publish a partial refresh as a successful catalogue. An intentionally empty materials array is valid.

Mapping from [Surowce](https://almarapolymers.sharepoint.com/sites/Magazyn/Lists/Surowce/AllItems.aspx):

| API | SharePoint |
| --- | --- |
| id | String form of the list item ID, within this fixed source list |
| name | Title (displayed as Surowiec) |
| type | field_1 (Rodzaj) |
| group | field_2 (Grupa) |
| suppliers | Cleaned Dostawcy text converted into an array; empty for missing values |

Trim surrounding whitespace. Dostawcy is plain text, not a lookup. Do not split company names at arbitrary punctuation; if multiple suppliers are stored in one text value, establish that source format before converting it. The current list does not contain barcodes, lots, units, or weights. Do not fabricate these fields from a material name.

The phone caches a fully validated snapshot atomically. Invalid or incomplete responses leave the previous cache intact. Supplier suggestions come from this catalogue, but operators can enter a different supplier. Receiving-operator lists are configured locally in the app.

## 3. Label parsing rules

`GET /materials/label-rules`

Return HTTP 200 with the complete rule bundle described in [label parsing rules](label-rules.md), validated against [label-rules.schema.json](label-rules.schema.json). Start with [the bundled sample rules](../app/src/main/assets/label-rules.json). Both server addresses must serve the same published bundle.

Rules contain an offset-bearing `updatedAt`, `gs1`, `rules`, and `tests`. The phone checks the structure, compiles the RE2 patterns, and runs every included test before atomically replacing its cache. Failed downloads or rejected rules retain the last good cache; before the first successful download the app uses its bundled rules. Rules refresh with the catalogue during background sync. A rule-refresh error does not block approved-PZ uploads. The connection-test button tests only `/materials/catalogue`.

Publish a complete, tested bundle atomically. A replacement affects subsequent captures only. It never changes saved entries or approved documents. The rules are data: no executable scripts, automatic visits to manufacturer URLs, or downloaded app code.

OCR material-name fields may use the optional `fuzzy` configuration described in [label parsing rules](label-rules.md). Keep the server validator and replay engine aligned with that schema. Choices and edit limits remain server data; the matching engine and yellow-warning UI require the corresponding Android update once.

The same bundle carries optional `ocrWarningThreshold`, a number from 0 to 1 (default 0.80). A higher threshold warns on more OCR-derived values. Publish changes through the existing rule endpoint; it applies after the phone refreshes its rules. This needs no extra route or phone-side configuration.

## 4. Approved PZ backup

`PUT /materials/pz/{documentId}/backup`

The request body follows `backupRequest`:

```json
{
  "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
  "phoneNumber": 3,
  "document": {
    "id": "5b44dd9b-7188-4cfb-b832-f8859ad95257",
    "createdAt": "2026-10-27T09:00:00+01:00",
    "supplier": "Torimex",
    "receiver": "Jan Kowalski",
    "invoiceNumber": "",
    "deliveryMatches": true,
    "deliveryComment": "",
    "items": [
      {
        "id": "c2dd2c77-b739-4350-bb01-f2a7d4c3ea99",
        "materialId": "1",
        "materialName": "AOX 1010",
        "lot": "000128A",
        "unknownLotComment": "",
        "quantity": "25.5",
        "unit": "kg",
        "condition": "OK",
        "damageComment": "",
        "scannedAt": "2026-10-27T09:05:00+01:00",
        "sourceCode": null,
        "barcodeFormat": null
      }
    ],
    "number": "PZ/2026/03/0001",
    "phoneNumber": 3,
    "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
    "approvedAt": "2026-10-27T09:30:00+01:00"
  },
  "pdf": {
    "fileName": "PZ_2026_03_0001.pdf",
    "sha256": "<lowercase SHA-256 of decoded PDF bytes>",
    "contentBase64": "<complete standard Base64 of the PDF>"
  }
}
```

The PDF hash/content above are placeholders; actual requests contain the full PDF. The actor at the top level identifies the registered uploading installation, with a positive phoneNumber. The document retains its original approving installation and phone number, including 0 for documents approved before registration, or if historical files have been restored elsewhere. Uploading these older documents must not renumber them.

The app sends `X-Content-SHA256` with the lowercase SHA-256 of the **exact UTF-8 request-body bytes**. Compute this before parsing/re-serializing the JSON. The header is also present on the registration POST, where it may be validated in the same way. It is an integrity checksum, not authentication.

For a backup, verify:

- The path documentId equals document.id, and the document is approved.
- Physical-entry UUIDs are unique within the document. Each entry remains separate even when its material, lot, weight, or source code matches another entry.
- Quantities are positive decimal strings. Use decimal arithmetic; do not parse them as binary floating point. Units are exactly `kg` or `szt.`.
- A known lot is a non-empty string. Preserve case, punctuation, and leading zeros. Null means an explicitly unknown lot, requiring unknownLotComment. Never merge unknown-lot entries.
- DAMAGED requires damageComment. A false deliveryMatches requires deliveryComment. These are independent checks.
- Material IDs can be null for manual materials. Such entries retain their complete materialName. A later catalogue change must not rewrite document history.
- Scanned entries may include an optional `label` object with raw barcode contents, OCR text, rule timestamp/IDs, and suggested values; see [the snapshot contract](label-rules.md#recorded-label-data). Manual entries omit it. Store this evidence unchanged. The entry's material, lot, quantity, and unit contain the operator's confirmed values and may differ from suggestions. Do not reparse a received document or validate its confirmed fields against a suggestion.
- A suggested material can contain optional `approximate: true`; an entry can contain optional `approximateMaterial: true` while that suggestion remains applied. A manual correction clears the entry flag while retaining the raw suggestion metadata. Store both flags unchanged; they affect the entry form's warning display, not grouping or quantities. Missing flags mean false.
- `label.ocrWords` and `label.ocrLines` optionally retain confidence spans `{start, end, confidence}` in the OCR text (UTF-16 offsets, end exclusive, score 0–1). Suggestions can contain optional `confidence` and `lowConfidence: true`. Entries can contain optional `lowConfidenceFields` listing the applied warnings for `materialName`, `lot`, `quantity`, or `unit`. Preserve these alongside the raw evidence. Manual edits can clear entry warnings while retaining the original suggestions and scores. Missing scores mean unknown; do not infer them or re-evaluate saved documents against a newer threshold.
- The decoded PDF is complete and its SHA-256 equals pdf.sha256.

Store the structured data and PDF durably before acknowledging. Use document.id as the idempotency key: retries, including a lost acknowledgment, must not create another PZ. Do not use the uploaded filename as a filesystem path without validation. Keep one coherent backup for each immutable approved document; if an existing ID has conflicting business contents, return HTTP 409 rather than silently overwriting it. Equivalent PDF generation/JSON ordering on retries must not create another document.

Response, HTTP 200 or 201 (`backupResponse`):

```json
{
  "documentId": "5b44dd9b-7188-4cfb-b832-f8859ad95257",
  "sha256": "<SHA-256 of this exact request body>",
  "storedAt": "2026-10-27T08:31:00Z"
}
```

Even for an already stored document, acknowledge the current validated request body after confirming the complete backup is durably present. The client marks a backup complete only when documentId and sha256 match its request and storedAt is a valid timestamp. An empty response, mismatched checksum, lost connection, or non-2xx response keeps it pending.

Drafts are not uploaded. The client retries approved documents with persistent background work, on app launch, and through the settings connection action. Background retry uses exponential backoff; catalogue refresh/backup is also scheduled hourly when connected. Local documents are retained after upload. Reinstallation recovery downloads this registration’s own approved PZs and finalized RWs; see [device recovery](device-recovery.md). Server backups are not deleted automatically.

## Failure responses

Use non-2xx HTTP codes for errors (for example 400 for invalid data, 409 for conflicts, 503 for temporary unavailability). A JSON error body is useful for the server logs, but the current app reports the HTTP status rather than displaying arbitrary server text. Registration, catalogue refresh, label-rule refresh, and backup failures are displayed separately in Settings. The app keeps cached materials, cached label rules, and local documents when the service is unavailable.
