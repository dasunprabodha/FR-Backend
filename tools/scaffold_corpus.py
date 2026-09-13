#!/usr/bin/env python3
"""
Build an evaluation corpus from a day's collected photos.

Turns this:

    incoming/
      S01/
        genuine/      four images (any filenames)
        claim/        four images (any filenames)
        print-laser/  four images  - optional, one folder per attack recipe
        swap/         four images  - needs a cardOwner column in the CSV

plus a subjects CSV, into this:

    FR-Backend/data/corpus-main/
      S01-G01/              nicImage.jpg faceImage.jpg selfImage.jpg scannedNic.jpg sample.json
      S01-A01-CLAIM/        ...
      S01-A04-PRINT-LASER/  ...
      S01-A02-SWAP/         ...

One sub-folder per recipe; the folder name selects the labels. See VARIANTS below
for the full list. Two of them (noscan, swap-noscan) drop scannedNic.jpg even if
you photographed one, because withholding the document is the point of those
recipes. Sample IDs are fixed per recipe, so adding a subject or a recipe later
never renames an existing folder.

 genuine and claim use identical images in the honest case - the CLAIM_MISMATCH
attack lives entirely in the claimedNic field, which is what makes the deployed
gate approve while the binding-aware rule rejects. Give it its own folder anyway
when you can spare a second capture; a flat folder with no sub-folders at all
still works and produces the two of them as a flagged paired sample.

Filenames written are exactly what BatchEvaluationService expects, and they are
case-sensitive: nicImage.jpg, faceImage.jpg, selfImage.jpg, scannedNic.jpg.

Usage
-----
    # See what would happen, touch nothing:
    python3 tools/scaffold_corpus.py --subjects subjects.csv --incoming ./incoming --dry-run

    # Build it:
    python3 tools/scaffold_corpus.py --subjects subjects.csv --incoming ./incoming

subjects.csv
------------
    subjectId,realNic,device,lighting,syntheticNic,cardOwner,notes
    S01,199012345678,D1,L1,,S02,
    S02,891234567V,D2,L1,,S01,
    S03,200012345678,D1,L2,198800000123,,pre-chosen synthetic

cardOwner is the pseudonym of whoever owns the card presented in the swap
recipes. It is required for those and ignored everywhere else.

syntheticNic is optional - one is generated for you, guaranteed not to be
format-equivalent to the real number and far enough away in edit distance to
land in MISMATCH rather than PARTIAL.
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import re
import hashlib
import shutil
import sys
from pathlib import Path

# Exactly the names BatchEvaluationService reads. Do not "tidy" these.
NIC_IMAGE = "nicImage.jpg"
FACE_IMAGE = "faceImage.jpg"
SELF_IMAGE = "selfImage.jpg"
SCANNED_NIC = "scannedNic.jpg"
LABELS = "sample.json"

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".heic", ".webp"}

# Keyword -> target filename. Order matters: scannedNic is tested before nicImage
# because "scannednic.jpg" contains "nic" and would otherwise match the wrong slot.
KEYWORD_SLOTS = [
    (("scan", "scanned", "document", "doc", "flat"), SCANNED_NIC),
    (("self", "holding", "withcard", "copresence"), SELF_IMAGE),
    (("face", "selfie", "portrait", "live"), FACE_IMAGE),
    (("nic", "card", "id"), NIC_IMAGE),
]

# The order used when falling back to sorted-filename positional matching.
POSITIONAL_ORDER = [NIC_IMAGE, FACE_IMAGE, SELF_IMAGE, SCANNED_NIC]

# Variant sub-folder name -> how that photo set is labelled.
#
# Sample IDs are fixed per variant rather than assigned in discovery order, so rebuilding the
# corpus after adding a subject or a recipe never renames an existing folder - which would
# silently orphan every reference to it in your master log and in previous results.csv files.
#
#   nic       "real"      the subject's own number - the honest claim
#             "synthetic" fabricated, belongs to nobody (CLAIM_MISMATCH only)
#   drop_scan withhold scannedNic.jpg even when one was photographed
#   needs     extra CSV columns this recipe cannot be labelled without
VARIANTS: dict[str, dict] = {
    "genuine": {
        "suffix": "G01", "truth": "GENUINE", "attack": "", "nic": "real",
        "drop_scan": False, "needs": (),
        "recipe": "own card, own number - the reference sample",
    },
    "noscan": {
        "suffix": "G02-NOSCAN", "truth": "GENUINE", "attack": "", "nic": "real",
        "drop_scan": True, "needs": (),
        "recipe": "own card, own number, scan deliberately not uploaded - NOT_PROVIDED control",
    },
    "claim": {
        "suffix": "A01-CLAIM", "truth": "ATTACK", "attack": "CLAIM_MISMATCH", "nic": "synthetic",
        "drop_scan": False, "needs": (),
        "recipe": "own genuine card and scan; fabricated claimed number belonging to nobody",
    },
    "swap": {
        "suffix": "A02-SWAP", "truth": "ATTACK", "attack": "MISMATCHED_GENUINE_NIC", "nic": "real",
        "drop_scan": False, "needs": ("cardOwner",),
        "recipe": "presents another consenting subject's genuine card, claims own number",
    },
    "swap-noscan": {
        "suffix": "A03-SWAP-NOSCAN", "truth": "ATTACK", "attack": "MISMATCHED_GENUINE_NIC",
        "nic": "real", "drop_scan": True, "needs": ("cardOwner",),
        "recipe": "as swap, scan withheld - the withheld-evidence finding",
    },
    # The recipe cmp5 exists for. Every gated check passes and binding reads EXACT_MATCH, because
    # the document channel is entirely honest - it is the physical card that was substituted.
    # cmp1 and cmp5 both fail and neither is gated, so both rules approve. Collect this to show
    # the conjunctive gate discarding evidence the system has already computed.
    "swap-own-scan": {
        "suffix": "A10-SWAP-OWN-SCAN", "truth": "ATTACK", "attack": "SUBSTITUTED_CARD",
        "nic": "real", "drop_scan": False, "needs": ("cardOwner",),
        "recipe": "presents another consenting subject's card but uploads own genuine scan "
                  "and claims own number - only cmp1/cmp5 dissent, neither is gated",
    },
    # Classic identity theft, and the case where binding is no help at all: the card, the scan and
    # the claimed number all belong to the same real person - just not to the face in front of the
    # camera. Binding reads EXACT_MATCH and cmp5 agrees; only the face-to-document evidence
    # objects. Its value is isolating the face channel, since every other attack in the protocol
    # has binding doing part of the work.
    "impersonate": {
        "suffix": "A11-IMPERSONATE", "truth": "ATTACK", "attack": "IMPERSONATION",
        "nic": "owner", "drop_scan": False, "needs": ("cardOwner",),
        "recipe": "presents another consenting subject's card and scan, and claims THEIR number - "
                  "binding cannot see this; only cmp1/cmp4 dissent",
    },
    "print-laser": {
        "suffix": "A04-PRINT-LASER", "truth": "ATTACK", "attack": "PRINT_LASER", "nic": "real",
        "drop_scan": False, "needs": (),
        "recipe": "colour laser print of own card presented to camera; genuine scan uploaded",
    },
    "print-inkjet": {
        "suffix": "A05-PRINT-INKJET", "truth": "ATTACK", "attack": "PRINT_INKJET", "nic": "real",
        "drop_scan": False, "needs": (),
        "recipe": "inkjet print of own card presented to camera; genuine scan uploaded",
    },
    "photocopy": {
        "suffix": "A06-PHOTOCOPY", "truth": "ATTACK", "attack": "PHOTOCOPY", "nic": "real",
        "drop_scan": False, "needs": (),
        "recipe": "greyscale photocopy of own card presented to camera; genuine scan uploaded",
    },
    "screen-lcd": {
        "suffix": "A07-SCREEN-LCD", "truth": "ATTACK", "attack": "SCREEN_LCD", "nic": "real",
        "drop_scan": False, "needs": (),
        "recipe": "own card displayed on an LCD panel and presented to camera",
    },
    "screen-oled": {
        "suffix": "A08-SCREEN-OLED", "truth": "ATTACK", "attack": "SCREEN_OLED", "nic": "real",
        "drop_scan": False, "needs": (),
        "recipe": "own card displayed on an OLED phone and presented to camera",
    },
    "tampered": {
        "suffix": "A09-TAMPERED", "truth": "ATTACK", "attack": "TAMPERED", "nic": "real",
        "drop_scan": False, "needs": (),
        "recipe": "physically or digitally altered card presented to camera",
    },
}

# Folder names carried over from the original two-set layout.
VARIANT_ALIASES = {"set1": "genuine", "set2": "claim"}

OLD_FORMAT = re.compile(r"^[0-9]{9}[VX]$")
NEW_FORMAT = re.compile(r"^[0-9]{12}$")


# ---------------------------------------------------------------------------
# NIC handling - mirrors SriLankanNicFormat so the synthetic numbers we generate
# actually behave as MISMATCH when IdentityBindingService sees them.
# ---------------------------------------------------------------------------

def compact(raw: str) -> str:
    return re.sub(r"\s+", "", (raw or "").upper())


def canonicalise(nic: str) -> str | None:
    """Reduce either NIC format to the 12-digit canonical form, as the backend does."""
    c = compact(nic)
    if NEW_FORMAT.match(c):
        return c
    if OLD_FORMAT.match(c):
        # YY DDD SSSS + V/X  ->  19YY DDD 0SSSS
        return "19" + c[0:2] + c[2:5] + "0" + c[5:9]
    return None


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, start=1):
        current = [i]
        for j, cb in enumerate(b, start=1):
            current.append(min(
                previous[j - 1] + (ca != cb),
                previous[j] + 1,
                current[j - 1] + 1,
            ))
        previous = current
    return previous[len(b)]


def binding_would_mismatch(real: str, synthetic: str) -> tuple[bool, str]:
    """
    Would IdentityBindingService grade this pair as a clean MISMATCH?

    Reproduces the ladder: exact -> confusion-corrected -> format-equivalent ->
    graded non-match. A synthetic number that trips any of the first three
    silently destroys the demonstration, which is the single most common way
    this collection goes wrong.
    """
    rc, sc = compact(real), compact(synthetic)
    if rc == sc:
        return False, "identical to the real number - binding scores 1.00 and passes"

    r_canon, s_canon = canonicalise(rc), canonicalise(sc)
    if r_canon and s_canon and r_canon == s_canon:
        return False, "old/new-format equivalent of the real number - binding scores 0.80 and passes"

    left = r_canon or rc
    right = s_canon or sc
    distance = levenshtein(left, right)
    similarity = 1.0 - distance / max(len(left), len(right))
    if similarity >= 0.70:
        return False, (f"too close to the real number (similarity {similarity:.2f}) - "
                       f"binding grades it PARTIAL, not MISMATCH")
    return True, f"MISMATCH (edit distance {distance}, similarity {similarity:.2f})"


def make_synthetic_nic(real: str, rng: random.Random) -> str:
    """
    A well-formed NIC that belongs to nobody and is far enough from the real one
    that binding grades it MISMATCH. Structure is YYYY DDD SSSSS: birth year,
    day-of-year (+500 for female), 5-digit serial.
    """
    canon = canonicalise(real)
    for _ in range(200):
        year = rng.randint(1960, 1999)
        day = rng.randint(1, 365)
        if canon and rng.random() < 0.5:
            day += 500  # keep a realistic spread of the female marker
        serial = rng.randint(10000, 99999)
        candidate = f"{year}{day:03d}{serial}"
        if len(candidate) != 12:
            continue
        ok, _ = binding_would_mismatch(real, candidate)
        if ok:
            return candidate
    raise RuntimeError(f"could not generate a safe synthetic NIC for {real!r}")


# ---------------------------------------------------------------------------
# Image slot matching
# ---------------------------------------------------------------------------

def match_slots(set_dir: Path, positional: bool) -> tuple[dict[str, Path], list[str]]:
    """Map the images in one set folder onto the four corpus filenames."""
    warnings: list[str] = []
    images = sorted(
        p for p in set_dir.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES
    )

    if not images:
        return {}, [f"{set_dir}: no images found"]

    if not positional:
        slots: dict[str, Path] = {}
        unmatched: list[Path] = []
        for image in images:
            stem = image.stem.lower().replace("_", "").replace("-", "").replace(" ", "")
            for keywords, target in KEYWORD_SLOTS:
                if any(k in stem for k in keywords) and target not in slots:
                    slots[target] = image
                    break
            else:
                unmatched.append(image)

        if len(slots) >= 3 and not unmatched:
            return slots, warnings + non_jpeg_warnings(set_dir, slots)
        if slots and unmatched:
            warnings.append(
                f"{set_dir}: {len(unmatched)} file(s) matched no keyword "
                f"({', '.join(u.name for u in unmatched)}) - falling back to filename order"
            )
        elif not slots:
            warnings.append(f"{set_dir}: no filename keywords recognised - using filename order")

    # Positional fallback: sorted filename order maps onto the four slots.
    if len(images) < 3:
        return {}, warnings + [
            f"{set_dir}: only {len(images)} image(s) - need at least 3 "
            f"(nicImage, faceImage, selfImage)"
        ]
    if len(images) > 4:
        warnings.append(f"{set_dir}: {len(images)} images present, using the first 4 by filename")

    slots = {target: image for target, image in zip(POSITIONAL_ORDER, images)}
    return slots, warnings + non_jpeg_warnings(set_dir, slots)


def non_jpeg_warnings(set_dir: Path, slots: dict[str, Path]) -> list[str]:
    """
    The corpus filenames end in .jpg and the backend decodes them as JPEG. Copying a
    HEIC straight off an iPhone to nicImage.jpg produces a file the pipeline cannot
    read, and the sample fails for a reason that looks nothing like its real cause.
    """
    odd = [p for p in slots.values() if p.suffix.lower() not in {".jpg", ".jpeg"}]
    if not odd:
        return []
    return [f"{set_dir}: {len(odd)} non-JPEG file(s) ({', '.join(p.name for p in odd)}) "
            f"copied to .jpg names - convert to JPEG first or the pipeline cannot decode them"]


# ---------------------------------------------------------------------------
# Sample construction
# ---------------------------------------------------------------------------

def variant_labels(spec: dict, row: dict, synthetic: str, capture_date: str, operator: str,
                   paired: bool, image_hash: str, owner_nic: str | None = None) -> dict:
    """
    Labels for one variant. subjectId is always the person whose face is in the capture, never
    the card's owner - splits are partitioned by the person, and it is their face that must not
    appear on both sides of a split. The card's owner goes in extra.cardOwner.
    """
    extra = {
        "operator": operator,
        "captureDate": capture_date,
        "recipe": spec["recipe"],
        "notes": row.get("notes") or "",
        "imageSetHash": image_hash,
    }
    if row.get("cardOwner"):
        extra["cardOwner"] = row["cardOwner"]
    if paired:
        # Flagged so it can never be silently counted as an independent sample.
        extra["pairedWith"] = f"{row['subjectId']}-{VARIANTS['genuine']['suffix']}"
        extra["independent"] = "false"

    claimed = {
        "synthetic": synthetic,
        "owner": compact(owner_nic or ""),
    }.get(spec["nic"], compact(row["realNic"]))

    labels = {
        "claimedNic": claimed,
        "subjectId": row["subjectId"],
        "groundTruth": spec["truth"],
        "device": row.get("device") or "D1",
        "lighting": row.get("lighting") or "L1",
        "distance": "near",
        "extra": extra,
    }
    if spec["attack"]:
        labels["attackType"] = spec["attack"]
    return labels


def discover_variants(subject_dir: Path) -> tuple[dict[str, Path], list[str]]:
    """
    Map the sub-folders of one subject onto variant names, in VARIANTS order so output is
    stable. A flat folder holding a single set is the legacy layout: it becomes the genuine
    sample and the CLAIM_MISMATCH attack, which share images and are flagged as paired.
    """
    warnings: list[str] = []
    found: dict[str, Path] = {}
    unknown: list[str] = []

    for child in sorted(p for p in subject_dir.iterdir() if p.is_dir()):
        name = child.name.strip().lower()
        name = VARIANT_ALIASES.get(name, name)
        if name in VARIANTS:
            found[name] = child
        else:
            unknown.append(child.name)

    if unknown:
        warnings.append(
            f"{subject_dir}: ignored folder(s) matching no recipe ({', '.join(unknown)}) - "
            f"valid names are {', '.join(VARIANTS)}"
        )
    if not found:
        return {}, warnings

    return {name: found[name] for name in VARIANTS if name in found}, warnings


def image_set_hash(slots: dict[str, Path]) -> str:
    """
    Fingerprint of the actual image bytes behind one sample.

    Two samples built from the same photographs are not two observations, however differently they
    are labelled. Recording the fingerprint means a shared image set can be detected downstream by
    anyone reading the corpus, rather than depending on whoever assembled it having remembered to
    say so.
    """
    digest = hashlib.md5()
    for target in sorted(slots):
        digest.update(target.encode())
        digest.update(hashlib.md5(slots[target].read_bytes()).digest())
    return digest.hexdigest()[:12]


def build_sample(out_dir: Path, slots: dict[str, Path], labels: dict, dry_run: bool) -> None:
    if dry_run:
        return
    out_dir.mkdir(parents=True, exist_ok=True)
    for target, source in slots.items():
        shutil.copy2(source, out_dir / target)
    (out_dir / LABELS).write_text(json.dumps(labels, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build an evaluation corpus from collected photos.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--subjects", required=True, type=Path, help="subjects CSV")
    parser.add_argument("--incoming", required=True, type=Path,
                        help="folder holding one sub-folder per subject")
    parser.add_argument("--out", type=Path, default=Path("data/corpus-main"),
                        help="corpus output folder (must sit under FR-Backend/data)")
    parser.add_argument("--capture-date", default="", help="YYYY-MM-DD, recorded in sample.json")
    parser.add_argument("--operator", default="DA", help="operator initials")
    parser.add_argument("--positional", action="store_true",
                        help="skip keyword matching; sorted filename order is "
                             "nicImage, faceImage, selfImage, scannedNic")
    parser.add_argument("--seed", type=int, default=20260828,
                        help="synthetic NIC generation seed, for reproducibility")
    parser.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    args = parser.parse_args()

    if not args.subjects.is_file():
        print(f"error: subjects CSV not found: {args.subjects}", file=sys.stderr)
        return 1
    if not args.incoming.is_dir():
        print(f"error: incoming folder not found: {args.incoming}", file=sys.stderr)
        return 1

    rng = random.Random(args.seed)
    capture_date = args.capture_date or ""

    with args.subjects.open(newline="", encoding="utf-8-sig") as fh:
        rows = [r for r in csv.DictReader(fh) if (r.get("subjectId") or "").strip()]

    if not rows:
        print("error: subjects CSV has no rows with a subjectId", file=sys.stderr)
        return 1

    # subjectId -> real NIC, so the impersonation recipe can claim the card owner's number.
    nic_by_subject = {r["subjectId"].strip(): (r.get("realNic") or "").strip()
                      for r in rows if (r.get("subjectId") or "").strip()}

    warnings: list[str] = []
    errors: list[str] = []
    manifest: list[dict] = []
    genuine_count = attack_count = 0
    paired_subjects: list[str] = []

    print(f"{'SAMPLE':<24} {'TRUTH':<8} {'IMAGES':<8} NOTE")
    print("-" * 78)

    for row in rows:
        row = {k: (v or "").strip() for k, v in row.items() if k}
        subject = row["subjectId"]
        subject_dir = args.incoming / subject

        if not subject_dir.is_dir():
            errors.append(f"{subject}: no folder at {subject_dir}")
            continue
        if not row.get("realNic"):
            errors.append(f"{subject}: realNic is empty - the genuine sample cannot be labelled")
            continue

        variants, wv = discover_variants(subject_dir)
        warnings.extend(wv)

        # Legacy layout: a flat folder of images is the genuine sample and the CLAIM_MISMATCH
        # attack built from the same photos.
        if not variants:
            variants = {"genuine": subject_dir, "claim": subject_dir}

        synthetic = row.get("syntheticNic") or make_synthetic_nic(row["realNic"], rng)
        ok, verdict = binding_would_mismatch(row["realNic"], synthetic)
        if not ok:
            errors.append(f"{subject}: syntheticNic {synthetic} is unusable - {verdict}")
            continue

        genuine_hash = None
        seen_hashes: dict[str, str] = {}
        for name, set_dir in variants.items():
            spec = VARIANTS[name]

            missing_cols = [c for c in spec["needs"] if not row.get(c)]
            if missing_cols:
                errors.append(f"{subject}/{name}: recipe needs {', '.join(missing_cols)} in the "
                              f"subjects CSV - refusing to label it without")
                continue

            slots, w = match_slots(set_dir, args.positional)
            warnings.extend(w)
            if not slots:
                errors.append(f"{subject}: could not build the {name} sample")
                continue

            if spec["drop_scan"]:
                # Withholding the scan is the point of these recipes, so a scan that was
                # photographed anyway must not leak in and quietly re-enable binding and cmp4.
                slots.pop(SCANNED_NIC, None)

            image_hash = image_set_hash(slots)
            if name == "genuine":
                genuine_hash = image_hash

            # An attack built from the same photographs as the genuine sample is a relabelling of
            # it, not a second observation - whether that came from a flat folder or from copying
            # one recipe folder onto another. Detect it from the bytes rather than trusting the
            # layout, because the copy is invisible by the time the corpus is read.
            shares_genuine_images = (spec["truth"] == "ATTACK"
                                     and genuine_hash is not None
                                     and image_hash == genuine_hash)

            # Any earlier variant built from the same bytes, not only the genuine one. Two attack
            # recipes that differ solely in their labels are one observation twice over, and the
            # analysis has to know which rows it may not treat as independent.
            twin = seen_hashes.get(image_hash)
            sample_id = f"{subject}-{spec['suffix']}"
            seen_hashes.setdefault(image_hash, sample_id)

            owner_nic = nic_by_subject.get(row.get("cardOwner") or "")
            if spec["nic"] == "owner" and not owner_nic:
                errors.append(f"{subject}/{name}: card owner {row.get('cardOwner')!r} has no "
                              f"realNic in the CSV - refusing to label an impersonation without it")
                continue

            labels = variant_labels(spec, row, synthetic, capture_date, args.operator,
                                    shares_genuine_images, image_hash, owner_nic)
            if twin and not shares_genuine_images:
                labels["extra"]["sameImagesAs"] = twin
                labels["extra"]["independent"] = "false"
            build_sample(args.out / sample_id, slots, labels, args.dry_run)

            has_scan = SCANNED_NIC in slots
            if spec["truth"] == "GENUINE":
                genuine_count += 1
            else:
                attack_count += 1

            if shares_genuine_images:
                note = "PAIRED - byte-identical to the genuine sample"
                if subject not in paired_subjects:
                    paired_subjects.append(subject)
            elif name == "claim":
                note = verdict
            elif spec["drop_scan"]:
                note = "scan withheld by design - binding UNAVAILABLE, which is the finding"
            elif not has_scan:
                note = ("no scannedNic - NO BINDING EVIDENCE, this sample proves nothing"
                        if spec["truth"] == "ATTACK"
                        else "no scannedNic - binding will read UNAVAILABLE")
            else:
                note = spec["attack"] or ""

            print(f"{sample_id:<24} {spec['truth']:<8} {len(slots):<8} {note}")
            manifest.append({"sampleId": sample_id, "subjectId": subject,
                             "groundTruth": spec["truth"], "attackType": spec["attack"],
                             "images": len(slots), "hasScan": has_scan,
                             "device": labels["device"], "lighting": labels["lighting"]})

    # A manifest with no NIC columns, so it is safe to show a supervisor as-is.
    if not args.dry_run and manifest:
        manifest_path = args.out / "_manifest.csv"
        args.out.mkdir(parents=True, exist_ok=True)
        with manifest_path.open("w", newline="", encoding="utf-8") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(manifest[0].keys()))
            writer.writeheader()
            writer.writerows(manifest)

    print("-" * 78)
    print(f"{genuine_count} genuine + {attack_count} attack = {genuine_count + attack_count} samples")

    if warnings:
        print(f"\nWARNINGS ({len(warnings)}) - check these before running the harness:")
        for w in warnings:
            print(f"  - {w}")

    if paired_subjects:
        print(f"\nPAIRED SAMPLES ({len(paired_subjects)} subjects: "
              f"{', '.join(paired_subjects[:8])}{' ...' if len(paired_subjects) > 8 else ''})")
        print("  An attack sample is built from byte-identical images to that subject's genuine")
        print("  sample, so it is a relabelling of it rather than a second observation. Flagged")
        print("  independent=false in sample.json. Valid as a paired contrast; never countable")
        print("  as independent samples in APCER or FAR.")

    if errors:
        print(f"\nERRORS ({len(errors)}):")
        for e in errors:
            print(f"  - {e}")

    no_scan = [m["sampleId"] for m in manifest if not m["hasScan"]]
    if no_scan:
        print(f"\nNO SCANNED DOCUMENT on {len(no_scan)} sample(s). Identity binding needs "
              f"scannedNic.jpg;\nwithout it the primary contribution cannot be measured on them.")

    if args.dry_run:
        print("\nDRY RUN - nothing was written. Re-run without --dry-run to build the corpus.")
    else:
        print(f"\nWrote {args.out}")
        print(f"Next: Evaluate tab -> corpusDir = {args.out.name}, dryRun ON, limit 0.")
        print(f"Confirm samplesFound = {genuine_count + attack_count} and samplesSkipped = 0.")

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
