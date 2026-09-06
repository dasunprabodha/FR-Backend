#!/usr/bin/env python3
"""
Build an evaluation corpus from the numbered "Sample images MS25903874" folder.

Where scaffold_corpus.py assembles a corpus from photos collected per subject,
this one assembles it from a flat pool of numbered images plus a plan that says
which image fills which slot in which sample. Same output either way: the four
filenames BatchEvaluationService expects, plus sample.json.

    corpus_plan.csv    one row per sample; image numbers fill the four slots
    nic_numbers.csv    subject -> real NIC, read off the scans (you fill this)
    sample_index.csv   the image index, for reporting unused images only

Usage
-----
    python3 tools/build_corpus_from_index.py \
        --source "../Sample images MS25903874" \
        --out data/corpus-pilot --dry-run

    # then without --dry-run

claim column
------------
    SELF        the subject's own real NIC, from nic_numbers.csv. Used for
                genuine samples and for swaps, where the presenter truthfully
                claims their own number while presenting someone else's card.
    SYNTHETIC   a fabricated number generated here, checked against the real
                binding ladder so it cannot accidentally pass.
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from scaffold_corpus import (  # noqa: E402
    NIC_IMAGE, FACE_IMAGE, SELF_IMAGE, SCANNED_NIC, LABELS,
    compact, binding_would_mismatch, make_synthetic_nic,
)

SLOTS = [
    ("nicImage", NIC_IMAGE, True),
    ("faceImage", FACE_IMAGE, True),
    ("selfImage", SELF_IMAGE, True),
    ("scannedNic", SCANNED_NIC, False),
]


def resolve_image(source: Path, number: str) -> Path | None:
    """The pool mixes .jpg and .jpeg; the plan refers to images by number only."""
    number = (number or "").strip()
    if not number:
        return None
    for suffix in (".jpg", ".jpeg", ".JPG", ".JPEG", ".png"):
        candidate = source / f"{number}{suffix}"
        if candidate.is_file():
            return candidate
    return None


def main() -> int:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, type=Path,
                        help="folder holding the numbered images")
    parser.add_argument("--plan", type=Path, default=here / "corpus_plan.csv")
    parser.add_argument("--nics", type=Path, default=here / "nic_numbers.csv")
    parser.add_argument("--index", type=Path, default=here / "sample_index.csv")
    parser.add_argument("--out", type=Path, default=Path("data/corpus-pilot"))
    parser.add_argument("--capture-date", default="")
    parser.add_argument("--operator", default="DA")
    parser.add_argument("--seed", type=int, default=20260905)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if not args.source.is_dir():
        print(f"error: source folder not found: {args.source}", file=sys.stderr)
        return 1

    rng = random.Random(args.seed)

    with args.nics.open(newline="", encoding="utf-8-sig") as fh:
        nics = {r["subject"].strip(): (r.get("realNic") or "").strip()
                for r in csv.DictReader(fh) if (r.get("subject") or "").strip()}

    with args.plan.open(newline="", encoding="utf-8-sig") as fh:
        plan = [r for r in csv.DictReader(fh) if (r.get("sampleId") or "").strip()]

    index = {}
    if args.index.is_file():
        with args.index.open(newline="", encoding="utf-8-sig") as fh:
            index = {r["image"].strip(): r["description"].strip()
                     for r in csv.DictReader(fh) if (r.get("image") or "").strip()}

    errors: list[str] = []
    warnings: list[str] = []
    manifest: list[dict] = []
    used_images: set[str] = set()
    # Every synthetic number generated in this run, so two subjects can never
    # collide onto the same fabricated identity.
    minted: set[str] = set()

    print(f"{'SAMPLE':<26} {'TRUTH':<8} {'ATTACK TYPE':<24} {'SCAN':<5} CLAIM")
    print("-" * 92)

    for row in plan:
        row = {k: (v or "").strip() for k, v in row.items() if k}
        sample_id = row["sampleId"]
        subject = row["subject"]

        # --- resolve the four image slots ---
        resolved: dict[str, Path] = {}
        missing: list[str] = []
        for column, filename, required in SLOTS:
            number = row.get(column, "")
            if not number:
                if required:
                    missing.append(f"{column} not set in the plan")
                continue
            path = resolve_image(args.source, number)
            if path is None:
                missing.append(f"{column}={number} (no such image in {args.source.name})")
                continue
            resolved[filename] = path
            used_images.add(number)

        if missing:
            errors.append(f"{sample_id}: {'; '.join(missing)}")
            continue

        # --- resolve the claimed NIC ---
        claim_mode = (row.get("claim") or "SELF").upper()
        if claim_mode == "SYNTHETIC":
            real = nics.get(subject, "")
            if not real:
                errors.append(f"{sample_id}: no realNic for '{subject}' - a synthetic number "
                              f"cannot be checked against it, so it might silently pass")
                continue
            for _ in range(50):
                claimed = make_synthetic_nic(real, rng)
                if claimed not in minted:
                    break
            minted.add(claimed)
            ok, verdict = binding_would_mismatch(real, claimed)
            if not ok:
                errors.append(f"{sample_id}: generated number unusable - {verdict}")
                continue
            claim_note = f"synthetic, {verdict}"
        else:
            claimed = compact(nics.get(subject, ""))
            if not claimed:
                errors.append(f"{sample_id}: realNic for '{subject}' is blank in "
                              f"{args.nics.name} - binding cannot be computed")
                continue
            claim_note = f"{subject}'s own number"

        # --- labels ---
        extra = {
            "operator": args.operator,
            "captureDate": args.capture_date,
            "sourceImages": " ".join(
                f"{col}={row.get(col)}" for col, _, _ in SLOTS if row.get(col)
            ),
        }
        if row.get("notes"):
            extra["note"] = row["notes"]
        if claim_mode == "SYNTHETIC":
            extra["syntheticClaim"] = "true"
        if row.get("attackType") == "MISMATCHED_GENUINE_NIC":
            card = index.get(row.get("nicImage", ""), "")
            if card:
                extra["cardPresented"] = card

        labels = {
            "claimedNic": claimed,
            "subjectId": subject,
            "groundTruth": row["groundTruth"],
            "device": "unspecified",
            "lighting": row.get("lighting") or "L1",
            "distance": "near",
            "extra": extra,
        }
        if row.get("attackType"):
            labels["attackType"] = row["attackType"]

        if not args.dry_run:
            out_dir = args.out / sample_id
            out_dir.mkdir(parents=True, exist_ok=True)
            for filename, path in resolved.items():
                shutil.copy2(path, out_dir / filename)
            (out_dir / LABELS).write_text(json.dumps(labels, indent=2) + "\n", encoding="utf-8")

        has_scan = SCANNED_NIC in resolved
        print(f"{sample_id:<26} {row['groundTruth']:<8} "
              f"{(row.get('attackType') or '-'):<24} {('yes' if has_scan else 'NO'):<5} {claim_note}")

        manifest.append({
            "sampleId": sample_id,
            "subjectId": subject,
            "groundTruth": row["groundTruth"],
            "attackType": row.get("attackType", ""),
            "hasScan": has_scan,
            "lighting": labels["lighting"],
            "sourceImages": extra["sourceImages"],
            "notes": row.get("notes", ""),
        })

    if not args.dry_run and manifest:
        args.out.mkdir(parents=True, exist_ok=True)
        with (args.out / "_manifest.csv").open("w", newline="", encoding="utf-8") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(manifest[0].keys()))
            writer.writeheader()
            writer.writerows(manifest)

    # ---- report ----
    print("-" * 92)
    genuine = sum(1 for m in manifest if m["groundTruth"] == "GENUINE")
    attack = sum(1 for m in manifest if m["groundTruth"] == "ATTACK")
    with_binding = sum(1 for m in manifest if m["hasScan"])
    print(f"{len(manifest)} samples: {genuine} genuine, {attack} attack "
          f"({with_binding} carry a scan, so {len(manifest) - with_binding} produce no binding evidence)")

    by_type: dict[str, int] = {}
    for m in manifest:
        if m["groundTruth"] == "ATTACK":
            by_type[m["attackType"] or "-"] = by_type.get(m["attackType"] or "-", 0) + 1
    if by_type:
        print("attack types: " + ", ".join(f"{k} x{v}" for k, v in sorted(by_type.items())))

    if index:
        unused = sorted((n for n in index if n not in used_images), key=lambda x: int(x))
        if unused:
            print(f"\nUNUSED SOURCE IMAGES ({len(unused)} of {len(index)}):")
            for n in unused:
                print(f"  {n:>3}  {index[n]}")

    if warnings:
        print(f"\nWARNINGS ({len(warnings)}):")
        for w in warnings:
            print(f"  - {w}")

    if errors:
        print(f"\nERRORS ({len(errors)}) - these samples were NOT built:")
        for e in errors:
            print(f"  - {e}")

    if args.dry_run:
        print("\nDRY RUN - nothing written. Re-run without --dry-run to build.")
    else:
        print(f"\nWrote {args.out}")
        print(f"Next: Evaluate tab -> corpusDir = {args.out.name}, dryRun ON, limit 0.")
        print(f"Expect samplesFound = {len(manifest)}, samplesSkipped = 0.")

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
