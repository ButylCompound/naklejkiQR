# Printed PZ receiving form

The PZ PDF follows `temp_samplelabels/Formularz przyjęcia dostawy.pdf`, with the DOCX used to confirm the table structure. It uses A4 portrait pages, black text, thin table borders, pale grey column headings, and the same four sections. The left and right margins are both 24 points (about 8.5 mm); the title starts 32 points from the top on every page. The document number appears below the title and in numbered page footers.

| Section | App data |
| --- | --- |
| A. Dane dostawy | Scan date/time, supplier, material names, and optional invoice number. Multiple materials receive matching reference numbers in A and B. |
| B. Pozycje dostawy | One row per material/lot/unit group, total accepted quantity, unit, and count of physical pallets/packages. Unknown lots remain separate. Only scanned groups appear; empty filler rows are omitted to save space. |
| C. Kontrola zgodności | The document's checked Yes/No answer, full delivery discrepancy comment, damage comments, and explanations for unknown lots. Notes identify both the table row and physical scan position. |
| D. Podpisy | Receiving operator's name and space for a handwritten date and signature. |

For a group containing both OK and damaged packages, both condition boxes are checked with their counts. Other groups show the appropriate single checked condition. The package column reflects the app's physical-entry count; the app does not track a separate pallet-versus-bag type.

Each material table ends with its own package count and totals by unit. A 12-point gap separates that table from the next material's heading. The overall delivery total follows all material tables as a separate, unbordered line with its own spacing.

Long comments appear in section C instead of squeezing into B's narrow notes column. B points to that section. Text wraps and continues across pages; continuation pages repeat the section and relevant table headings. Material captions repeat for continued lot tables. Long repeated captions may use an ellipsis; the complete material names remain in A. Signature headings stay with their first row.

Printing, email attachments, and server backups use the same generated PDF. Previously issued PDFs keep their cached bytes; this layout applies when a PDF is first generated. Data grouping, numbering, approval locking, and backup payloads remain unchanged.

`PzPdfFormTest` exports an ordinary delivery, a two-material delivery, multiple materials with many lots and mixed units/conditions, and a long-comment case. Run it on a disposable emulator together with `PzStorageTest` to check PDF attachment/cache and backup behavior. Export the fixtures to `build/pz-form-review`, then check their text and totals in WSL with:

```sh
python AndroidApp/tools/check_pz_form_pdf.py
```

The checker requires Poppler's `pdftotext`. It checks all header fields, material/lot names, per-material and overall totals, mixed package-condition counts, repeated lot headings, signature placement, and all 600 long-comment test markers.

Local review artifacts: [filled form](../build/pz-form-review/pz-form-sample.pdf), [two materials](../build/pz-form-review/pz-form-materials.pdf), [many lots](../build/pz-form-review/pz-form-many-lots.pdf), and [long comments](../build/pz-form-review/pz-form-long-notes.pdf).
