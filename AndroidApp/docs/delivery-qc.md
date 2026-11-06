# Delivered lots and QC

The warehouse server exposes incoming PZ lots for a QC application. Each **PZ delivery + material + known lot number** has its own QC result. Receiving the same material and lot in another PZ creates a new, untested record. A result never carries over to a later delivery.

Multiple physical entries in the same delivery/lot share that result. Mixed units do not require separate QC tests; their quantities remain separate. Unknown lots (`lot: null`) never merge: each physical entry gets its own lot record. Material IDs define catalogue materials; manual materials use their exact confirmed names. Known lot numbers preserve case, punctuation, and leading zeros. Names from the current catalogue do not rewrite earlier deliveries.

## SQL storage

The existing warehouse SQLite database (default `data/warehouse.sqlite`, configurable through `DATABASE_PATH`) now contains:

| Table | Row represents | Main columns |
| --- | --- | --- |
| `pz_deliveries` | One approved PZ | `document_id`, `number`, `supplier`, `receiver`, `invoice_number`, `created_at`, `approved_at`, `stored_at`, `delivery_matches`, `delivery_comment`, `phone_number`, `installation_id` |
| `pz_lots` | One QC lot within one PZ | `id`, `document_id`, `material_id`, `material_name`, `lot`, `qcpass`, `qccomment`; `group_key` is internal grouping data |
| `pz_entries` | One physical pallet/package | `document_id`, `entry_id`, `lot_id`, `position`, `material_id`, `material_name`, `lot`, `quantity`, `unit`, `condition`, `damage_comment`, `unknown_lot_comment`, `scanned_at`, `source_code`, `barcode_format` |

Join `pz_lots.document_id` to `pz_deliveries.document_id`, and `pz_entries.lot_id` to `pz_lots.id`. Entry UUIDs are unique within their PZ. Use document UUIDs rather than printed document numbers as keys: different documents may share a visible number.

`quantity` is TEXT containing the original positive decimal string. Use decimal arithmetic for totals and keep units separate. The API returns exact totals by unit; do not use floating-point arithmetic for quantity accounting.

`qcpass` is nullable INTEGER in SQLite: `NULL` = untested, `1` = passed, `0` = failed. `qccomment` is nullable TEXT. The API converts the result to JSON `null`/`true`/`false`. A packaging `condition` of `DAMAGED` does not automatically fail QC.

Example SQL for the pending QC list:

```sql
SELECT l.id, d.document_id, d.number, d.supplier, d.approved_at,
       l.material_id, l.material_name, l.lot, l.qcpass, l.qccomment
FROM pz_lots AS l
JOIN pz_deliveries AS d ON d.document_id = l.document_id
WHERE l.qcpass IS NULL
ORDER BY l.id;
```

To read the individual packages for a lot:

```sql
SELECT entry_id, position, quantity, unit, condition, damage_comment,
       unknown_lot_comment, scanned_at
FROM pz_entries
WHERE lot_id = :lot_id
ORDER BY position;
```

Startup creates these tables and fills them from already stored PZ backups in one transaction. Each new upload stores its original backup and SQL records in the same transaction before acknowledgement. Upload retries do not duplicate records or reset QC. Restarting the server preserves lot IDs and results. The existing SQLite backup command includes these tables and their results.

Approved document JSON, original requests, OCR evidence, and PDF bytes remain unchanged in `backups`. QC is separate server data; it does not amend the phone's approved PZ, its PDF, or label suggestions. No Android update is required for these endpoints. These are receipt records, not remaining stock balances.

## List lots

`GET /materials/lots`

Optional query parameters:

| Parameter | Meaning |
| --- | --- |
| `qcpass=pending` | Only untested lots |
| `qcpass=true` / `qcpass=false` | Only passed / failed lots |
| `documentId=<UUID>` | One PZ, matched by UUID |
| `materialId=<ID>` | Exact catalogue material ID |
| `lot=<text>` | Exact known lot number |
| `afterId=<integer>` | IDs strictly greater than this cursor; default `0` |
| `limit=<integer>` | Page size, from 1 to 1000; default `100` |

Results sort by increasing lot ID. Response HTTP 200 (`deliveryLotPage` in [the JSON Schema](server-api.schema.json)):

```json
{
  "lots": [
    {
      "id": 41,
      "documentId": "5b44dd9b-7188-4cfb-b832-f8859ad95257",
      "documentNumber": "PZ/2026/03/0001",
      "supplier": "Torimex",
      "receiver": "Jan Kowalski",
      "invoiceNumber": "",
      "createdAt": "2026-10-27T09:00:00+01:00",
      "approvedAt": "2026-10-27T09:30:00+01:00",
      "storedAt": "2026-10-27T08:31:00Z",
      "deliveryMatches": true,
      "deliveryComment": "",
      "phoneNumber": 3,
      "installationId": "4f03bb6f-b204-419e-887b-d6419db80a25",
      "materialId": "1",
      "materialName": "AOX 1010",
      "lot": "000128A",
      "packageCount": 2,
      "damagedPackageCount": 0,
      "quantities": [{ "unit": "kg", "quantity": "51" }],
      "qcpass": null,
      "qccomment": null
    }
  ],
  "nextAfterId": null
}
```

`nextAfterId` is the last returned ID when another page exists, otherwise `null`. For incremental imports, persist the last lot ID actually received, even when `nextAfterId` is null; send it as `afterId` on the next poll. An empty page leaves the saved cursor unchanged. IDs follow server receipt order, so offline documents uploaded later are not missed because of an old scan/approval date.

This cursor discovers new lots, not changes to existing QC results. For a current pending-work queue, refresh `?qcpass=pending` starting at `afterId=0` and follow its pages. A result reset to pending then appears again.

## Read one lot

`GET /materials/lots/{id}`

Returns the same lot fields plus `entries`, an array of physical entries in original PZ order. Each entry contains the confirmed fields listed for `pz_entries`, using camelCase JSON names (`id` is the entry UUID), and its one-based `position` in the PZ. Quantities here are individual package quantities. The schema is `deliveryLotDetail`.

Unknown lot IDs return 404. Invalid identifiers/filters return 422. Reads return `Cache-Control: no-store` so QC changes are visible immediately.

## Set or amend QC

`PATCH /materials/lots/{id}/qc`

Send UTF-8 JSON with `Content-Type: application/json` and `X-Content-SHA256` containing the lowercase SHA-256 of the exact body bytes, as for other warehouse writes. The body (`qcUpdate`) accepts only:

```json
{"qcpass": true, "qccomment": "Próbka zgodna ze specyfikacją"}
```

- `qcpass`: `true`, `false`, or `null` to reset to untested.
- `qccomment`: optional string; `null` clears it. Empty strings are also accepted.
- Omitted fields retain their current values. At least one field is required. A comment-only update does not change the result.

Example with curl, run from an allowed network:

```sh
body='{"qcpass":true,"qccomment":"Próbka zgodna ze specyfikacją"}'
checksum=$(printf '%s' "$body" | sha256sum | cut -d ' ' -f 1)
curl -X PATCH 'http://localhost:3000/materials/lots/41/qc' \
  -H 'Content-Type: application/json' \
  -H "X-Content-SHA256: $checksum" \
  --data-binary "$body"
```

HTTP 200 returns the updated `deliveryLotDetail`. Unknown lots return 404, invalid fields/types return 422, and invalid JSON/content type/checksum returns 400. Updates affect only that lot in that delivery. There is no QC history or operator attribution yet; the latest submitted result/comment is stored.

These endpoints use the same private LAN/Tailscale access rules as the existing warehouse API. The body checksum checks integrity, not caller identity. No phone registration or app signing key is needed for the QC consumer.
