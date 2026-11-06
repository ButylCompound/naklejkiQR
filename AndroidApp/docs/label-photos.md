# PZ label photographs

The app retains the final photograph attached to each saved PZ entry. Only approved PZs upload images. Cancelling an entry, replacing its scan or deleting a draft removes the discarded image. Earlier entries without a photo and manual entries still work; previous captures cannot be recovered because older app builds did not retain them.

CameraX saves a JPEG at its captured resolution, quality 90, with orientation metadata. ML Kit reads that same saved JPEG. The file is not resized or recompressed for upload. Preserve EXIF orientation when loading images for later OCR experiments. See [CameraX file capture](https://developer.android.com/media/camera/camerax/take-photo) and [ML Kit file input](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

`entry.label.photo` optionally contains:

```json
{"id":"917235c4-1c49-435b-80a5-f4a6ddbf130b","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
```

The photo belongs to the existing `label` snapshot: capture time, raw barcode contents/formats, OCR text/confidence, parsing-rule timestamp/IDs and suggested values. The enclosing PZ entry holds the final values confirmed or corrected by the operator. Manual edits do not rewrite the original read. Use document and entry UUIDs to link the photo/read/confirmed-value pair, rather than material names or lot numbers.

## Upload

`PUT /materials/pz/{documentId}/entries/{entryId}/photo`

Use the existing UTF-8 JSON and `X-Content-SHA256` raw-body checksum, primary/fallback routes and registered uploader identity:

```json
{
  "installationId":"4f03bb6f-b204-419e-887b-d6419db80a25",
  "phoneNumber":3,
  "photo":{
    "id":"917235c4-1c49-435b-80a5-f4a6ddbf130b",
    "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "contentBase64":"...complete JPEG bytes..."
  }
}
```

Validate `photoUpload`. Maximum decoded image size is 16 MiB; the normal HTTP request limit is 25 MiB. Validate UUIDs, canonical Base64, JPEG boundary markers and SHA-256. Persist bytes unchanged. The response (`photoReceipt`) is:

```json
{"photoId":"917235c4-1c49-435b-80a5-f4a6ddbf130b","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","storedAt":"2026-11-03T12:00:00Z"}
```

Return 201 for a new image, 200 for an identical retry, 409 for conflicting image identity/content/ownership, 403 for an invalid uploader, and 422 for invalid input. Check the stored bytes before acknowledging a retry; corruption returns 503.

Images upload individually before the document/PDF backup. `pz_label_photos` stages them by document UUID, entry UUID and uploader. A committed PZ backup must have all referenced images with the expected hashes and ownership, or the server returns 503 without committing the PZ. This keeps a completed PZ backup complete. Individual upload receipts let the phone retry a failed batch without re-uploading accepted images. Replaced scans and drafts never enter the upload queue.

A staged image becomes part of the dataset when its approved PZ commits. A failed or interrupted PZ upload can leave staged image rows until retry. Select rows joined to `backups` when reading the approved dataset. Uploading a different image after approval cannot alter the immutable entry or its image.

## Recovery

`GET /materials/devices/{installationId}/documents/PZ/{documentId}/entries/{entryId}/photo?deviceKey={64-hex}`

The device identity must own the committed PZ, as for document recovery. Return `photoDownload`: the receipt fields plus `contentBase64`. Return 404 for an unknown image or an image whose PZ has not committed; 403 for an invalid device key; 503 for corrupt stored bytes. Set `Cache-Control: no-store`.

The phone downloads and verifies each image before saving its recovered PZ, and keeps the recovery cursor pending if a download fails. Recovering a document does not generate a new image or re-upload it.

## SQL and backups

JPEG bytes live in `pz_label_photos.image` in the warehouse SQLite database. The primary key is `photo_id`; `(document_id, entry_id)` is unique. Other columns are `uploader_id`, `sha256` and `stored_at`. Existing PZ JSON continues to hold reads and confirmed values. For example:

```sql
SELECT p.photo_id, p.document_id, p.entry_id, p.sha256, p.image,
       json_extract(e.value, '$.label') AS original_read,
       e.value AS confirmed_entry
FROM pz_label_photos AS p
JOIN backups AS b ON b.document_id = p.document_id
JOIN json_each(b.document, '$.items') AS e
  ON lower(json_extract(e.value, '$.id')) = p.entry_id
WHERE lower(json_extract(e.value, '$.label.photo.id')) = p.photo_id;
```

The existing SQLite backup now includes photographs. Size and retention planning must account for JPEG bytes as well as document JSON/PDFs. Backups remain the source for recovering photos; the app keeps its saved local copies too.
