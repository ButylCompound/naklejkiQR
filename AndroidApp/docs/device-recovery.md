# Device registration and recovery

Debug uses application ID `pl.almara.inwentaryzacja.debug` and label **(debug) Skaner Inwentaryzacji**. Release keeps `pl.almara.inwentaryzacja` and its existing label and signing setup. They can coexist and keep separate files, registrations, counters and histories.

The client sends `deviceKey = SHA-256(packageName + ":" + Settings.Secure.ANDROID_ID)`, encoded as 64 lowercase hex characters. Android scopes this ID to the signing key, Android user and device; the package name separates our build variants. Normal reinstalls with the same signing key retain the identifier. Factory reset, Android-user changes or signing-key changes may give a new identity. This identifier is not a password or access token; retain private LAN/Tailscale access controls. See [Android ANDROID_ID](https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID).

An existing installation must sync this updated app once before uninstalling it, so the server can bind its existing registration to `deviceKey`. Old registrations without that binding cannot be identified by model name. Debug switching no longer requires uninstalling Release.

After reinstall, save the server root in Settings. Background sync reuses the registered number, restores server counter maxima, then downloads approved PZs and finalized RWs belonging to that registration. PZs retain their original PDF and attached label-photo bytes; RW PDFs are generated from the restored document. Drafts and inventory sessions stay local. Documents and counter increments never sent to the server cannot be recovered. New offline documents use phone `00` until registration succeeds and keep that number after recovery.

## Registration

`POST /materials/devices/register`, with the existing raw-body `X-Content-SHA256` header:

```json
{
  "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
  "deviceName": "Xiaomi Mi 10",
  "deviceKey": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  "counters": {"PZ/2026": "12", "RW/2026": "7"}
}
```

Response (201 for a new registration, 200 for an existing or recovered one):

```json
{
  "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
  "phoneNumber": 3,
  "counters": {"PZ/2026": "12", "RW/2026": "7"},
  "recoveryRequired": false
}
```

A new random installation UUID with a known `deviceKey` returns the original canonical UUID/number and `recoveryRequired: true`, without allocating another number. A registration can bind an unused key once; conflicting bindings return 409. Old clients without `deviceKey` retain their two-field response and UUID-based registration.

Counters are decimal strings in `0..9223372036854775807`, keyed by `PZ/YYYY` or `RW/YYYY`. Merge using the greater value. Store them transactionally in `device_counters`; uploads also raise the relevant counter. The phone merges counter floors before adopting its recovered number. It sends a checkpoint each sync, once per used route. Preserve separate annual counters and do not renumber historical documents.

## Finalized RW backup

`PUT /materials/rw/{documentId}/backup`, with the existing raw-body SHA header:

```json
{
  "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
  "phoneNumber": 3,
  "document": {
    "id": "a9adcdf6-14ed-47d2-9daf-cb95bf559f11",
    "number": "RW/2026/03/0007",
    "createdAt": "2026-11-03 12:30:00",
    "requester": "Jan",
    "manager": "Ewa",
    "items": [{"product": "PA 6", "quantity": 20, "unit": "kg", "labelDate": "2026-11-01 10:30", "scannedAt": "2026-11-03 12:30", "initials": "JK", "raw": "original label", "alley": "1", "labelId": "abcdefgh", "lot": "0001A"}]
  }
}
```

Validate using `rwBackupRequest`. The optional `items[].lot` string preserves manufacturer lot numbers, including leading zeros and case. Older items omit it; new items omit it when empty. Different lots remain separate in the app. RW uses its existing local timestamp strings and numeric quantities; PZ's schema stays unchanged. Accept existing three-part RW numbers as well as new four-part numbers. Store immutable canonical JSON in `rw_backups`; do not add RW entries to delivered-lot/QC tables. Return the same `{documentId, sha256, storedAt}` receipt as PZ: 201 new, 200 equal retry, 409 conflicting contents. The hash acknowledges the current request bytes. Upload only finalized documents, retain local files, and retry failures in background.

## Recover history

`GET /materials/devices/{installationId}/documents?deviceKey={64-hex}&after={cursor}&limit=25`

The installation UUID and device key must identify the same registration. Return only documents uploaded by that registration, including documents originally approved offline with phone 00. A valid but mismatched key returns 403.

```json
{
  "documents": [{"module": "PZ", "documentId": "a9adcdf6-14ed-47d2-9daf-cb95bf559f11", "number": "PZ/2026/03/0012", "storedAt": "2026-11-03T12:00:00Z"}],
  "nextCursor": null
}
```

Pages sort by `module:documentId`. Omit `after` for the first page; otherwise URL-encode the last returned cursor. Default page size is 25, maximum 100. `nextCursor` is null on the last page, otherwise `PZ:{uuid}` or `RW:{uuid}`.

`GET /materials/devices/{installationId}/documents/{module}/{documentId}?deviceKey={64-hex}`

Return `{module, document, sha256, storedAt}`. For PZ, also include `pdf: {fileName, sha256, contentBase64}` containing the original stored PDF. The outer SHA is the original accepted upload's request hash, not a hash of this response. Unknown/unowned documents return 404. An incomplete stored PDF returns 503. Both GETs return `Cache-Control: no-store`.

The client validates identity, module, document number and PDF hash before writing. Restores do not overwrite a different local document. Save each document/PDF/receipt atomically and persist the page cursor only after the page completes. Retain pending recovery across retries/restarts, and avoid re-uploading recovered documents. Registration is safe to retry even after it returns the original UUID.

Photo-bearing PZs also download each photo using the [label photo endpoint](label-photos.md) before saving the recovered document.

## Storage and deployment

Startup adds a nullable unique `devices.device_key`, `device_counters` and `rw_backups` without replacing the registry. Existing PZ backups seed counter floors when `device_counters` is first created. Keep the SQLite database in server backups: it contains device bindings, counters, both document histories and PZ PDFs. Both configured network addresses must reach this same database.
