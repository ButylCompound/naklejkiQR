"""Generate repeatable label distortions, run Android OCR, and summarize exact field matches."""

import argparse
import csv
import json
import os
from pathlib import Path
import random
import subprocess


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/label-stress"
PACKAGE = "pl.almara.inwentaryzacja"


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate(seed):
    from PIL import Image, ImageChops, ImageEnhance, ImageFilter, ImageOps

    OUTPUT.mkdir(parents=True, exist_ok=True)
    fixtures = json.loads((ROOT / "app/src/test/resources/label-photo-reads.json").read_text(encoding="utf-8"))
    cases = []
    variants = ["baseline", "rotate90", "rotate180", "rotate270", "tilt", "blur", "noise", "mixed_mild", "mixed_hard"]
    for index, fixture in enumerate(fixtures):
        with Image.open(ROOT.parent / "temp_samplelabels" / fixture["file"]) as source:
            original = ImageOps.exif_transpose(source).convert("RGB")
        original.thumbnail((2560, 2560), Image.Resampling.LANCZOS)
        for variant_index, variant in enumerate(variants):
            case_seed = seed + index * 100 + variant_index
            rng = random.Random(case_seed)
            image = original.copy()
            parameters = {}
            if variant.startswith("rotate"):
                parameters["angle"] = int(variant.removeprefix("rotate"))
            elif variant == "tilt":
                parameters["angle"] = round(rng.uniform(-12, 12), 2)
            elif variant.startswith("mixed"):
                parameters["angle"] = rng.choice([0, 90, 180, 270]) + round(rng.uniform(-10, 10), 2)
            if "angle" in parameters:
                image = image.rotate(parameters["angle"], Image.Resampling.BICUBIC, expand=True, fillcolor="white")
            if variant in ["blur", "mixed_mild", "mixed_hard"]:
                parameters["blur_radius"] = round(rng.uniform(0.4, 0.9) if variant != "mixed_hard" else rng.uniform(1.2, 1.8), 2)
                image = image.filter(ImageFilter.GaussianBlur(parameters["blur_radius"]))
            if variant in ["noise", "mixed_mild", "mixed_hard"]:
                amplitude = 8 if variant != "mixed_hard" else 18
                parameters["noise_amplitude"] = amplitude
                noise = Image.frombytes("L", image.size, rng.randbytes(image.width * image.height))
                noise = noise.point([128 + round((value - 127.5) * amplitude / 127.5) for value in range(256)])
                image = ImageChops.add(image, noise.convert("RGB"), offset=-128)
            if variant.startswith("mixed"):
                parameters["brightness"] = round(rng.uniform(0.8, 1.1) if variant == "mixed_mild" else rng.uniform(0.6, 0.8), 2)
                image = ImageEnhance.Brightness(image).enhance(parameters["brightness"])
            filename = f"{index:02d}_{variant}.jpg"
            quality = 75 if variant == "mixed_hard" else 92
            image.save(OUTPUT / filename, quality=quality)
            cases.append({"file": filename, "source": fixture["file"], "variant": variant,
                          "seed": case_seed, "parameters": {**parameters, "jpeg_quality": quality},
                          "size": list(image.size), "expected": {**fixture["expected"], "unit": "kg" if fixture["expected"]["quantity"] is not None else None}})
    save_json(OUTPUT / "manifest.json", {"seed": seed, "max_source_edge": 2560, "cases": cases})
    print(f"Generated {len(cases)} cases in {OUTPUT}", flush=True)


def run(serial):
    sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
    adb = [str(sdk / "platform-tools/adb.exe"), "-s", serial]

    def command(args, timeout=60):
        result = subprocess.run(adb + args, capture_output=True, encoding="utf-8", errors="replace", timeout=timeout, check=True)
        return result.stdout + result.stderr

    assert serial.startswith("emulator-"), "Use a disposable emulator, not a production phone"
    for apk in [ROOT / "app/build/outputs/apk/debug/app-debug.apk", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        print(command(["install", "-r", "-t", str(apk)]), flush=True)
    remote = "/data/local/tmp/label-stress"
    command(["shell", "mkdir", "-p", remote])
    manifest = json.loads((OUTPUT / "manifest.json").read_text(encoding="utf-8"))
    for filename in ["manifest.json"] + [case["file"] for case in manifest["cases"]]:
        command(["push", str(OUTPUT / filename), remote + "/" + filename])
    command(["shell", "run-as", PACKAGE, "mkdir", "-p", "files/label-stress"])
    command(["shell", "run-as", PACKAGE, "cp", "-r", remote + "/.", "files/label-stress/"])
    print("Running Android OCR on all generated cases...", flush=True)
    args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", PACKAGE + ".LabelStressTest",
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
    result = command(args, timeout=1800)
    (OUTPUT / "instrumentation.txt").write_text(result, encoding="utf-8")
    (OUTPUT / "reads.json").write_text(command(["exec-out", "run-as", PACKAGE, "cat", "files/label-stress/reads.json"]), encoding="utf-8")
    print(result[-1600:], flush=True)
    assert "OK (1 test)" in result and "FAILURES!!!" not in result
    report()


def report(reads_path=None, prefix=""):
    manifest = json.loads((OUTPUT / "manifest.json").read_text(encoding="utf-8"))
    reads = {item["file"]: item for item in json.loads((reads_path or OUTPUT / "reads.json").read_text(encoding="utf-8"))}
    fields = ["materialName", "lot", "quantity", "unit"]
    rows = []
    for case in manifest["cases"]:
        record = reads.get(case["file"])
        read = record["read"] if record else None
        for field in fields:
            expected = case["expected"][field]
            values = record.get("reparsedValues", read["values"]) if read else []
            actual = sorted(set(value["value"] for value in values if value["field"] == field))
            status = "unprocessed" if read is None else "correct" if actual == ([] if expected is None else [expected]) else "missing" if not actual else "ambiguous" if len(actual) > 1 else "wrong"
            rows.append({"file": case["file"], "source": case["source"], "variant": case["variant"], "field": field,
                         "expected": expected, "actual": "; ".join(actual), "status": status})
    with (OUTPUT / (prefix + "fields.csv")).open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    lines = ["# Label image stress test", "", f"Seed: {manifest['seed']}; {len(reads)}/{len(manifest['cases'])} images processed with the app's ML Kit models.",
             "", "`correct` includes expected empty fields. Missing, wrong, and ambiguous values are counted separately. These synthetic cases do not estimate field accuracy in production.",
             "", "| Variant | Images | All fields correct | Missing fields | Wrong fields | Ambiguous fields |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for variant in dict.fromkeys(case["variant"] for case in manifest["cases"]):
        subset = [row for row in rows if row["variant"] == variant]
        filenames = {row["file"] for row in subset}
        correct = sum(all(row["status"] == "correct" for row in subset if row["file"] == filename) for filename in filenames)
        counts = [sum(row["status"] == status for row in subset) for status in ["missing", "wrong", "ambiguous"]]
        lines.append(f"| {variant} | {len(filenames)} | {correct} | {' | '.join(map(str, counts))} |")
    lines += ["", "## Cases requiring review", "", "| Image | Source | Field | Expected | Actual | Status |", "| --- | --- | --- | --- | --- | --- |"]
    for row in rows:
        if row["status"] != "correct":
            lines.append(f"| [{row['file']}]({row['file']}) | {row['source']} | {row['field']} | {row['expected']} | {row['actual']} | {row['status']} |")
    (OUTPUT / (prefix + "report.md")).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[:17]), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["generate", "run", "report"])
    parser.add_argument("--seed", type=int, default=20261030)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--reads", type=Path)
    parser.add_argument("--prefix", default="")
    args = parser.parse_args()
    if args.action == "generate":
        generate(args.seed)
    elif args.action == "run":
        run(args.serial)
    else:
        report(args.reads, args.prefix)
