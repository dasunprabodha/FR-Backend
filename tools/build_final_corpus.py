#!/usr/bin/env python3
"""
Assemble corpus-pilot and corpus-derived into one folder for a single final run.

WHY THIS NEEDS CARE

The two corpora are not the same kind of evidence. corpus-pilot was captured: every sample is a
session that happened, with a person in front of a camera. corpus-derived was assembled from
photographs collected for other samples, to give three checks a case they could fail when the
collected set contained none.

Pooling them is fine for *running* - identical pipeline, identical configuration, one file, one
set of column names. It is not fine for *reporting*. A headline accuracy figure over all 70 mixes
field measurement with mechanism check, and inflates the attack count with 18 constructed cases
that are all attacks.

So this script copies the samples unchanged and relies on two things to keep them separable:

  1. sample.json carries extra.constructed="true" on every derived sample, and the harness now
     emits that as a `constructed` column in results.csv. Filter on it before quoting any number.

  2. _provenance.csv is written alongside, listing every sample and which corpus it came from,
     so the split survives even if a label file is later edited.

Names do not collide: corpus-pilot uses subject-prefixed ids and corpus-derived uses A-CHANNEL /
A-SWAPMID / A-FACESWAP prefixes. The script refuses to run if that ever stops being true, rather
than silently overwriting one sample with another.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
from pathlib import Path

LABELS = "sample.json"


def samples(root: Path) -> list[Path]:
    return sorted(d for d in root.iterdir() if d.is_dir() and (d / LABELS).exists())


def truth_of(d: Path) -> tuple[str, str, bool]:
    with open(d / LABELS) as fh:
        data = json.load(fh)
    extra = data.get("extra") or {}
    return (data.get("groundTruth", ""),
            data.get("attackType", "") or "",
            str(extra.get("constructed", "")).lower() == "true")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pilot", default="data/corpus-pilot")
    ap.add_argument("--derived", default="data/corpus-derived")
    ap.add_argument("--out", default="data/corpus-final")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    pilot, derived, out = Path(args.pilot), Path(args.derived), Path(args.out)
    for p in (pilot, derived):
        if not p.is_dir():
            print(f"missing corpus: {p}", file=sys.stderr)
            return 2

    sources = [("collected", pilot), ("constructed", derived)]
    seen: dict[str, str] = {}
    plan: list[tuple[str, Path, str]] = []

    for origin, root in sources:
        for d in samples(root):
            if d.name in seen:
                print(f"name collision: '{d.name}' is in both {seen[d.name]} and {origin}. "
                      f"Refusing to merge - rename one first.", file=sys.stderr)
                return 1
            seen[d.name] = origin
            plan.append((origin, d, d.name))

    collected = sum(1 for o, _, _ in plan if o == "collected")
    constructed = len(plan) - collected
    print(f"{'DRY RUN - ' if args.dry_run else ''}merging into {out}")
    print(f"  {collected:3} collected   from {pilot}")
    print(f"  {constructed:3} constructed from {derived}")
    print(f"  {len(plan):3} total\n")

    tally: dict[tuple[str, str, bool], int] = {}
    for origin, src, name in plan:
        truth, attack, flagged = truth_of(src)
        tally[(truth, attack, flagged)] = tally.get((truth, attack, flagged), 0) + 1
        if origin == "constructed" and not flagged:
            print(f"  WARNING  {name} came from the derived corpus but is not marked "
                  f"extra.constructed=true", file=sys.stderr)

    print("  composition:")
    for (truth, attack, flagged), n in sorted(tally.items()):
        label = attack or "(genuine)"
        mark = " [constructed]" if flagged else ""
        print(f"    {truth:8} {label:24} {n:3}{mark}")

    if args.dry_run:
        print("\nnothing written")
        return 0

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    for _, src, name in plan:
        shutil.copytree(src, out / name)

    with open(out / "_provenance.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["sampleId", "origin", "groundTruth", "attackType", "constructed"])
        for origin, src, name in plan:
            truth, attack, flagged = truth_of(src)
            w.writerow([name, origin, truth, attack, str(flagged).lower()])

    print(f"\nwrote {len(plan)} samples to {out}")
    print("  _provenance.csv records which corpus each sample came from.")
    print("  results.csv will carry a `constructed` column - filter on it before quoting any figure.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
