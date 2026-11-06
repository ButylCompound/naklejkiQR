"""Check the PDFs exported by PzPdfFormTest with Poppler's text extractor."""

from collections import defaultdict
from decimal import Decimal
import json
from pathlib import Path
import re
import subprocess
import sys
import unicodedata


folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "build/pz-form-review"


def normalized(value):
    return " ".join(unicodedata.normalize("NFKC", value).split())


for name in ["pz-form-sample", "pz-form-materials", "pz-form-many-lots", "pz-form-long-notes"]:
    document = json.loads((folder / (name + ".json")).read_text(encoding="utf-8"))
    text = subprocess.check_output(["pdftotext", "-layout", str(folder / (name + ".pdf")), "-"], text=True)
    flat = normalized(text)
    required = ["Formularz przyjęcia dostawy", "A. Dane dostawy", "B. Pozycje dostawy", "C. Kontrola zgodności", "D. Podpisy",
                document["number"], document["supplier"], document["receiver"], document["invoiceNumber"], document["deliveryComment"],
                "Opakowań: " + str(len(document["items"]))]
    for entry in document["items"]:
        required += [entry["materialName"], entry["lot"] or "", entry["unknownLotComment"]]
        if name != "pz-form-long-notes":
            required.append(entry["damageComment"])
    for value in required:
        assert normalized(value) in flat, (name, "Missing content", value)
    totals = defaultdict(Decimal)
    for entry in document["items"]:
        totals[entry["unit"]] += Decimal(entry["quantity"])
    for unit, total in totals.items():
        number = format(total, "f")
        if "." in number:
            number = number.rstrip("0").rstrip(".")
        assert number + " " + unit in flat, (name, "Missing unit total", number, unit)
    materials = defaultdict(list)
    for entry in document["items"]:
        materials[entry["materialId"] or entry["materialName"]].append(entry)
    assert flat.count("Razem dla surowca") == len(materials), (name, "Wrong number of material subtotals")
    end = 0
    for entries in materials.values():
        subtotal = "Razem dla surowca · Opakowań: " + str(len(entries)) + " · Łącznie: "
        start = flat.index(subtotal, end)
        end = start + len(subtotal)
        next_total = flat.find("Razem dla surowca", end)
        segment = flat[end:next_total if next_total >= 0 else flat.index("Cała dostawa", end)]
        material_totals = defaultdict(Decimal)
        for entry in entries:
            material_totals[entry["unit"]] += Decimal(entry["quantity"])
        for unit, total in material_totals.items():
            number = format(total, "f").rstrip("0").rstrip(".") if total % 1 else str(int(total))
            assert number + " " + unit in segment, (name, "Missing material subtotal", number, unit)
    overall = "Cała dostawa · Opakowań: " + str(len(document["items"]))
    assert flat.count("Cała dostawa") == 1 and flat.index(overall) > end, (name, "Overall total must follow all subtotals")
    pages = [page for page in text.split("\f") if page.strip()]
    for page in pages:
        if "D. Podpisy" in page:
            assert "Magazyn" in page, (name, "Signature heading separated from signature row")
        if "LOT-" in page:
            assert "Nr partii" in normalized(page) and "Stan opakowań" in normalized(page), (name, "Lot table header missing")
    if name in ["pz-form-sample", "pz-form-materials"]:
        assert len(pages) == 1
    if name == "pz-form-many-lots":
        assert "OK: 1" in flat and "uszkodz.: 1" in flat
    if name == "pz-form-long-notes":
        markers = re.findall(r"oznaczenie-\s*(\d+)", text)
        assert len(markers) == 600 and set(markers) == set(map(str, range(1, 601)))
    print(name + ": " + str(len(pages)) + " A4 page(s), expected content and totals present")
