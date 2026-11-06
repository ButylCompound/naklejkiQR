# Warehouse API — server implementation handoff

Implement a small server for the Android app in this workspace. The server registers phones, mirrors the SharePoint materials list, publishes label parsing rules, and stores approved PZ documents with their PDFs. The app implements the routes, payloads, and offline workflow described below.

Read these files together:

- [PZ label photos and photo/read pairs](label-photos.md)
- [Device recovery, debug identity and finalized RW backups](device-recovery.md)
- [Exact HTTP/JSON contract](server-api.md)
- [JSON Schema](server-api.schema.json), definitions under `$defs`
- [Complete request/response examples](examples/), including a real PDF backup
- [App update endpoints and publishing](app-updates.md)
- [Delivered lots, SQL storage, and QC endpoints](delivery-qc.md)
- [Label rule publishing and examples](label-rules.md), [rule schema](label-rules.schema.json), and [sample-label findings](label-analysis.md)

Implement the `/materials` routes below and keep the JSON property names. Framework, database, and deployment method are open implementation choices. Defaults proposed below are server settings, not new requirements for the Android client.

## Access and verification

Recommended initial deployment: a private API accessible through Tailscale, with access limited to the warehouse phones and an administrator's test device. Configure access to the specific server and API port. Tailscale grants support device/user sources, destinations, and protocol/port rules. Audit existing broad grants as well: a narrow rule does not cancel a broader allow rule. See [Tailscale grants](https://tailscale.com/docs/features/access-control/grants) and [grant rule semantics](https://tailscale.com/docs/reference/syntax/grants).

The current Android client sends no HTTP Basic credentials, bearer token, API key, or client certificate. It rejects base URLs containing credentials, query parameters, or fragments. It cannot complete a browser login or follow a redirect. Adding application-level API authentication requires a coordinated Android change; do not require an auth header that this client does not send.

Treat `installationId` and `phoneNumber` as identifiers. `X-Content-SHA256` checks the body bytes; it does not authenticate the sender. Restrict access separately at the network/gateway layer. For backup requests, also verify that the uploading installation/number pair exists in the device registry.

Tailscale Serve is one option for private HTTPS in front of a service listening on localhost. Its identity headers identify the requesting user, not necessarily a unique phone; tagged devices do not receive those user headers. Trust gateway-provided identity headers only through that gateway, with direct backend access blocked. See [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve).

HTTPS must use a certificate trusted by Android. The client has no custom CA configuration. A self-signed/private-CA certificate needs a coordinated Android trust configuration; installing a user CA alone is not sufficient for this app's target SDK. See [Android CA trust](https://developer.android.com/privacy-and-security/security-config). The client also supports HTTP for a private deployment.

If a public Internet API is chosen instead, decide the application authentication and Android credential setup before enabling public access. The compatible initial plan is private network access plus the request checks below.

## Routes and client behavior

Example base URL entered on a phone: `https://warehouse.example`. The table shows complete server paths and the suffix supplied to the Android API client. The client inserts `/materials` between the configured server root and that suffix. For example, base `https://warehouse.example` plus `/catalogue` produces `https://warehouse.example/materials/catalogue`.

| Method | Complete server path | Client suffix | Function | Schema definition |
| --- | --- | --- | --- | --- |
| POST | `/materials/devices/register` | `/devices/register` | Register or retrieve an installation's device number | `registrationRequest` → `registrationResponse` |
| GET | `/materials/catalogue` | `/catalogue` | Return the complete cached materials catalogue | `catalogue` |
| GET | `/materials/label-rules` | `/label-rules` | Return the complete published parsing bundle | `label-rules.schema.json` |
| PUT | `/materials/pz/{documentId}/backup` | `/pz/{documentId}/backup` | Store an approved PZ and its PDF | `backupRequest` → `backupResponse` |
| GET | `/materials/app-updates/latest` | `/app-updates/latest` | Return the published app update or null | `appUpdateResponse` |
| GET | `/materials/app-updates/{versionCode}/{sha256}.apk` | `/app-updates/{versionCode}/{sha256}.apk` | Download an immutable signed APK | Binary APK |
| GET | `/materials/lots` | `/lots` | Read delivered lots, optionally filtered by QC result | `deliveryLotPage` |
| GET | `/materials/lots/{id}` | `/lots/{id}` | Read one delivered lot and its physical entries | `deliveryLotDetail` |
| PATCH | `/materials/lots/{id}/qc` | `/lots/{id}/qc` | Set the delivery-specific QC result/comment | `qcUpdate` → `deliveryLotDetail` |

Android's sync and connection test use the `/catalogue` suffix. Configure both base URLs as server roots, without paths; the API client adds `/materials` to every request. Previously saved addresses ending in `/materials` normalize to the server root.

- Use UTF-8 JSON and `Content-Type: application/json`, except for binary APK downloads. Return HTTP 200/201 with the specified JSON for successful writes, and 200 for the catalogue. Do not return an empty 204, an HTML login page, or a redirect.
- Serve these exact paths without adding a required trailing slash. Configure the framework/reverse proxy accordingly.
- The phone uses a 10-second connection timeout and a 20-second read timeout (60 seconds for APK download reads). Serve the cached catalogue immediately; SharePoint refresh must run independently of phone requests.
- Saving the address queues registration, catalogue/rule refresh, and pending uploads in the background. Connection failures do not block local PZ/RW work. Background attempts retry with backoff, and a connected periodic job runs hourly. Scheduling is best effort.
- **Testuj połączenie** uses `GET /materials/catalogue` through each entered base URL and validates each catalogue separately. It shows the result for both routes. It does not register the phone, save the addresses, replace the phone's catalogue, or upload documents. This route must work for an allowed phone before app registration.
- A catalogue refresh failure must not prevent the server accepting valid backups. SharePoint availability must not gate registration or PZ storage.

## Local and Tailscale addresses

The app has two optional base-URL settings: a primary address, normally on the local warehouse network, and a fallback address, normally reached through Tailscale. One configured address is sufficient. Example settings:

```text
Primary:  http://192.168.10.20:8080
Fallback: https://warehouse.example-tailnet.ts.net
```

Both addresses must expose the same API, device registry, catalogue, and PZ backup store. They are two routes to one service. Do not deploy independent registries or number allocators behind them. Repeating registration through the other address must return the same number for the installation UUID. A backup sent through either route must be visible to the other route immediately after successful durable storage.

The fallback address requires an active Tailscale connection on the phone. The local address requires access to the warehouse network.

Each sync prefers the primary address. Transport failures (including connection/DNS/TLS errors and timeouts), HTTP 408, and HTTP 5xx trigger an attempt through the other address. A working route is reused for the rest of that sync, and the next sync tries the primary first again. If a route fails midway through a batch, the other route can continue it. If both fail, documents remain on the phone and retry later.

HTTP 4xx responses other than 408, redirects, invalid JSON, and business-validation failures do not trigger failover for that operation. In particular, an authorization denial or conflicting immutable document must be handled as such. Return proper status codes rather than using a success response for an error.

Repeated registration and backup calls can arrive through both addresses, including after a lost upload acknowledgment. Enforce idempotency in the shared storage, not in a process-local cache. A route switch preserves the phone's document counters, assigned number, and completed-backup status. Editing the configured addresses may requeue backups; document UUIDs must still prevent duplicates.

Allow each network path independently. The LAN route must remain usable when Tailscale is unavailable; the Tailscale route must remain usable when the phone is outside the LAN. Apply warehouse-network restrictions to LAN access and Tailscale access rules to the Tailscale route. A LAN request must not depend on a working Tailscale tunnel. Both routes must support the same methods/JSON without redirects.

## Device registration and numbering

Accept an installation UUID, device name, app-scoped `deviceKey` and counter checkpoint. Return the canonical UUID, positive `phoneNumber`, counter maxima and recovery flag. A known device key reuses its original registration after reinstall. Debug and Release use separate keys. See [device recovery](device-recovery.md) for the complete contract, finalized RW backups and paginated history downloads.

Persist the registry with a unique constraint on `installationId` and a separate unique constraint on `phoneNumber`. Allocate numbers in a transaction. Concurrent registration of the same UUID returns one stable assignment; concurrent registration of different UUIDs returns different numbers. Never recycle assigned numbers, including after removing/replacing a phone. Preserve the registry and allocation state across restarts, restores, and server URL changes.

Document numbers are allocated by each phone, not by the server. PZ and RW have separate yearly counters. Examples: `PZ/2026/03/0001` and `RW/2026/03/0001`.

Before registration succeeds, the phone can approve documents using device number 0, displayed as `00`. After registration, only future documents use the assigned positive number. Counters continue: `PZ/2026/00/0001` can be followed by `PZ/2026/03/0002`. Approved document numbers and PDFs remain unchanged.

Different unregistered phones can have the same visible document number. Use the document UUID as the backup key; do not make the visible PZ number globally unique. Accept older document years and late uploads. Do not renumber incoming documents based on the server's clock.

## SharePoint mirror

Source: [Magazyn / Surowce](https://almarapolymers.sharepoint.com/sites/Magazyn/Lists/Surowce/AllItems.aspx).

| API property | Source field previously inspected |
| --- | --- |
| `id` | String form of list item `ID` |
| `name` | `Title` |
| `type` | `field_1` (Rodzaj) |
| `group` | `field_2` (Grupa) |
| `suppliers` | Cleaned `Dostawcy` text converted to an array |

Confirm these internal field names during setup. `Dostawcy` was plain text, not a lookup. Trim surrounding whitespace, use `[]` for missing suppliers, and preserve company names. Do not split on punctuation without confirming the source convention. Use empty strings for missing type/group values. Material IDs must remain stable.

Run an unattended refresh on startup and periodically; a configurable 15-minute interval is a proposed default. Read all list items with their fields through Microsoft Graph, following every `@odata.nextLink` until complete. See [list items](https://learn.microsoft.com/en-us/graph/api/listitem-list?view=graph-rest-1.0) and [Graph pagination](https://learn.microsoft.com/en-us/graph/paging).

Keep Microsoft credentials on the server. Configure application read access to the selected list or site, for example Selected permissions plus an explicit read grant; admin consent alone does not grant resource access. The workspace's interactive M365 CLI login is not an unattended service identity. See [Microsoft Selected permissions](https://learn.microsoft.com/en-us/graph/permissions-selected-overview).

Build and validate a full snapshot before replacing the stored one atomically. `updatedAt` is the time of the last successful complete refresh, with an ISO 8601 offset. A failed, interrupted, or partial refresh retains both the previous snapshot and its previous timestamp. Serve that snapshot even while SharePoint is unavailable.

Before any successful refresh, return 503 unless a valid complete snapshot was explicitly seeded. An intentionally empty source list is valid; an upstream failure is not an empty catalogue. Do not invent barcode, lot, unit, or weight fields from names. Manual suppliers/materials remain allowed in PZ documents.

## Label rules

Serve the complete [bundled rules](../app/src/main/assets/label-rules.json) initially through `GET /materials/label-rules`. Follow [the rule contract](label-rules.md) when editing patterns, material mappings, or quantity formats. Validate the JSON schema, RE2 syntax, capture groups, and included tests before publishing a file atomically. Serve the same published file through both routes; store it independently of SharePoint refreshes. Retain the last good file if an edit fails validation. Include the published file in server backups.

Rule refresh failures must not block registration or PZ storage. Phones retain a working local cache and can scan offline. Rule changes affect future reads, while uploaded snapshots and operator corrections remain unchanged. No APK hosting or update endpoint is required for these rule changes.

## Approved PZ backup validation

Use the complete backup example and schema. The request contains the uploading installation and number, the immutable approved document, and a PDF encoded as standard Base64. All properties marked required in the schema must be present, including nullable fields. An optional invoice is represented by `""`.

Validate these rules in addition to JSON Schema:

1. Compute SHA-256 from the exact raw UTF-8 request-body bytes, before parsing or reformatting them. Require a matching `X-Content-SHA256`. The Android client sends this header on both registration POSTs and backup PUTs; GET has no body hash.
2. The URL UUID must equal `document.id`. The uploading installation/positive number pair must match its registration.
3. The approved document retains its own original installation and device number. Its device number may be 0, or differ from the uploader after a historical restore. Do not require the document origin to equal the uploader, and do not rewrite it. The device component in the document's number must match `document.phoneNumber`.
4. Supplier, receiving operator, and each material name must be nonblank. There must be at least one physical entry, with UUIDs unique within the document. Preserve entry order and store every entry, including identical barcode contents.
5. `quantity` is a positive decimal **string**; validate and calculate with exact decimal arithmetic. Units are exactly `kg` or `szt.`. Do not add different units together or apply an assumed conversion.
6. A known lot is a nonblank string. Preserve leading zeros, case, and punctuation. `lot: null` requires a nonblank `unknownLotComment`; each unknown-lot entry stays separate.
7. `condition` is `OK` or `DAMAGED`; `DAMAGED` requires a nonblank `damageComment`. `deliveryMatches` is a boolean; false requires a nonblank `deliveryComment`. These comments are independent.
8. `materialId` can be null. Even a nonnull historical ID may be absent from the latest catalogue. Do not reject or rewrite a historical material, supplier, or operator because a current choice list changed.
9. Require valid UUIDs and offset-bearing ISO 8601 timestamps. Keep client timestamps and separately record the server receive time. Accept offline/late uploads without requiring the phone clock to equal the server clock.
10. Decode the PDF explicitly, verify `pdf.sha256` against the decoded bytes, and check that it is a complete PDF. Derive its expected filename from the PZ number (`/` becomes `_`, then `.pdf`). Choose internal storage paths from validated UUIDs, not uploaded paths or filenames.
11. `sourceCode` and `barcodeFormat` are nullable, opaque recorded values. They do not define package uniqueness. Supplier barcode parsing remains on the phone.
12. An entry may include `label`, as defined in the message schema. Preserve its raw codes, OCR text, rule metadata, and suggestions. The operator can override all suggested business fields; do not require them to equal the recorded suggestions. Manual entries omit `label`. Do not execute code or follow URLs found inside the label data.

Enable JSON Schema format checks for UUID/date-time and separately enforce ID uniqueness, nonblank strings, decimal rules, Base64 decoding, and hashes. A schema containing only `$defs` must be evaluated through the relevant definition, for example `#/$defs/backupRequest`; validating against an unconstrained root is insufficient. JSON Schema's content annotations alone do not verify the PDF.

## Durable storage and safe retries

Persist structured document data, the decoded PDF, the original document identity, uploader information, PDF hash, request hash, and server storage time. A database plus persistent files is sufficient; storing PDF bytes in the database is also an option. Keep deployment/container restarts from discarding this data.

Return success only after both JSON and PDF are durably stored. Do not acknowledge an in-memory queue or an unfinished background write. Use transactional/atomic writes and serialize concurrent writes for the same document UUID.

Define retry behavior precisely:

- New UUID: validate and persist the complete backup, then return 201.
- Existing UUID with equivalent document JSON: validate the incoming PDF and checksums, confirm the stored complete backup exists, and return 200. Object key order is irrelevant; preserve array order and recorded field values when comparing documents. Keep the first accepted PDF if an equivalent document has a regenerated PDF with different bytes.
- Existing UUID with changed document contents: return 409 and retain the existing backup.

The response is `{"documentId":"<UUID>","sha256":"<SHA-256 of THIS raw request body>","storedAt":"<offset timestamp>"}`. For a retry, the response must acknowledge the current validated request's hash, even if its JSON key order or PDF bytes differ from a previous request. It may retain the original durable storage timestamp.

Do not use raw request hashes as the document conflict key. Do not use PDF byte differences alone as proof that the business document changed. The phone may retry after the server stored a backup but the response was lost.

Keep local/server retention independent. Phones upload approved PZs only. RW uses the device registration for numbering; RW backup is not part of this API. Printing and email composition use the PDF on the phone.

## Operations and error responses

- Return 400/422 for malformed or invalid input, 403 for denied access, 409 for conflicting immutable documents, 413 for a configured size limit, and 503 for temporary unavailability. The current client retries failed uploads and shows HTTP status; log useful validation/conflict details on the server.
- Set a configurable body-size limit large enough for complete Base64 PDFs; 25 MiB is a proposed starting limit for the HTTP JSON body, to confirm with actual documents. Apply a matching reverse-proxy limit. Do not silently truncate data.
- Back up the device registry, number-allocation state, last good catalogue, document database, and PDFs together. Test a restore. A fresh registry must not silently replace the existing assignments.
- Document the base URL, persistent data locations, access rules, refresh interval, and Microsoft identity configuration. Keep secrets outside source control and logs.

## Acceptance checks

1. Repeat registration, concurrent registration, restart, and restore preserve stable, distinct positive device numbers.
2. An allowed unregistered phone can call GET for the connection test, without a registration side effect. Requests outside the allowed network identity are denied.
3. Catalogue output validates against the schema. Failed later pages or a SharePoint outage preserve the entire previous snapshot and its timestamp.
4. A normal approved PZ and a PZ approved under device 00 both upload successfully after registration. Their visible numbers remain unchanged; matching visible numbers with different UUIDs are stored separately.
5. A lost acknowledgment and concurrent duplicate uploads produce one complete stored document and valid acknowledgments for each current request body.
6. Reordered JSON keys or an equivalent regenerated PDF do not cause a conflict. Changed document contents under the same UUID produce 409 without overwriting data.
7. Invalid hashes, invalid PDF/Base64, repeated entry UUIDs, and missing required comments are rejected. Manual/stale catalogue materials, duplicate barcodes, different units, and explained unknown lots remain valid.
8. An interrupted write never yields a successful acknowledgment for a partial backup; retry recovers safely. Storage remains intact after restart/restore.
9. Test with the Android app using both **Testuj połączenie** and **Zapisz adresy**, then approve offline and reconnect to upload. Verify local-only connectivity, Tailscale-only connectivity, both routes unavailable, and a route failing after storage but before acknowledgment. No APK release is needed during server implementation.
10. Serve a valid label-rule bundle through both routes. Publish a tested replacement and verify a phone receives it through sync. A rejected bundle leaves its previous rules usable offline and does not block backups. Accept scanned entries with recorded label evidence and manual corrections that differ from the suggestions.
11. Verify all four complete paths in the route table, including `/materials/catalogue` for both sync and connection testing. Combining the server root, the automatic `/materials` prefix, and each client suffix must produce the documented server path with the prefix included once.

## Prompt for the next session

> Implement the warehouse API described in `AndroidApp/docs/server-implementation.md`, keeping the exact contract in `server-api.md`, `server-api.schema.json`, `label-rules.md`, `label-rules.schema.json`, and `examples/`. Mount all endpoints under `/materials`, with the catalogue at `/materials/catalogue`. Expose independent local-network and Tailscale routes to the same registry and storage, with private access restrictions and server-side validation compatible with the existing Android headers. Build phone registration, the SharePoint catalogue mirror, label-rule publishing, and durable approved-PZ JSON/PDF backups including optional label evidence. Verify the acceptance checks, document deployment, and preserve device assignments and immutable document IDs. Do not require new Android auth headers or add unrelated modules. If choosing public API authentication instead, identify the required Android changes before making it mandatory.
