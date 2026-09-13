#!/usr/bin/env python3
"""
Build the two attack classes that can be derived from images already collected.

Both classes exist because cmp5 and cmp2 are currently unmeasurable: every sample in
corpus-pilot uploads a scan *of the card it presents*, and no sample changes the card between
the document capture and the co-presence capture. So neither comparison has ever been given a
case it could fail, and their separation scores (0.52 and 0.38) describe the corpus, not the
checks.

    A-CHANNEL   presenter uploads a scan of their OWN card while presenting SOMEONE ELSE'S
                -> cmp5 (scan vs presented card) must disagree

    A-SWAPMID   presenter shows their OWN card for the document capture, then holds SOMEONE
                ELSE'S in the co-presence capture
                -> cmp2 (presented card vs card in the held photo) must disagree

    A-FACESWAP  the enrolment portrait is one person and the co-presence photo is a different
                person - a coached impostor pair, one doing the face capture and the other
                holding the card
                -> cmp3 (live face vs face in the held photo) must disagree

EVIDENTIAL STATUS - the two are not equally strong, and the thesis must say so.

  A-CHANNEL is a faithful reconstruction. In the deployed system the scan is a file the officer
  uploads, not a camera capture. Substituting that file *is* the attack, performed exactly as a
  real attacker would perform it. Nothing about the imagery is synthetic.

  A-SWAPMID is a constructed composite. The document capture and the co-presence capture are
  genuinely separate events in the live flow, so splicing them is closer to honest than it
  sounds - but the two frames were not recorded as one uninterrupted session, so session-level
  continuity (lighting drift, camera position, elapsed time) is not evidenced. Report it as
  constructed, and treat it as a mechanism check rather than a field measurement.

Every sample written here carries extra.constructed=true and extra.derivedFrom, so a reader of
results.csv can always separate them from collected samples.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

NIC_IMAGE = "nicImage.jpg"
FACE_IMAGE = "faceImage.jpg"
SELF_IMAGE = "selfImage.jpg"
SCANNED_NIC = "scannedNic.jpg"
LABELS = "sample.json"

# (sample id, presenter, swap sample supplying the imagery, genuine sample supplying the scan)
A_CHANNEL = [
    ("A-CHANNEL-01-dasun",   "dasun",   "dasun-A02-SWAP",   "dasun-G01"),
    ("A-CHANNEL-02-dasun",   "dasun",   "dasun-A06-SWAP",   "dasun-G01"),
    ("A-CHANNEL-03-dasun",   "dasun",   "dasun-A09-SWAP",   "dasun-G01"),
    ("A-CHANNEL-04-dilshan", "dilshan", "dilshan-A02-SWAP", "dilshan-G01"),
    ("A-CHANNEL-05-kavinda", "kavinda", "kavinda-A02-SWAP", "kavinda-G01"),
    ("A-CHANNEL-06-nuwan",   "nuwan",   "nuwan-A02-SWAP",   "nuwan-G01"),
]

# (sample id, face subject, genuine sample supplying face+card+scan, sample supplying a DIFFERENT
#  person's co-presence photo)
A_FACESWAP = [
    ("A-FACESWAP-01-dasun-x-nuwan",     "dasun",   "dasun-G01",   "nuwan-G01"),
    ("A-FACESWAP-02-nuwan-x-dasun",     "nuwan",   "nuwan-G01",   "dasun-G01"),
    ("A-FACESWAP-03-kavinda-x-dilshan", "kavinda", "kavinda-G01", "dilshan-G01"),
    ("A-FACESWAP-04-dilshan-x-kavinda", "dilshan", "dilshan-G01", "kavinda-G01"),
    ("A-FACESWAP-05-sumudu-x-milan",    "sumudu",  "sumudu-G01",  "milan-G01"),
    ("A-FACESWAP-06-milan-x-sumudu",    "milan",   "milan-G01",   "sumudu-G01"),
]

# (sample id, presenter, genuine sample supplying own card+face+scan, swap sample supplying selfImage)
A_SWAPMID = [
    ("A-SWAPMID-01-dasun",   "dasun",   "dasun-G01",   "dasun-A02-SWAP"),
    ("A-SWAPMID-02-dasun",   "dasun",   "dasun-G01",   "dasun-A06-SWAP"),
    ("A-SWAPMID-03-dilshan", "dilshan", "dilshan-G01", "dilshan-A02-SWAP"),
    ("A-SWAPMID-04-kavinda", "kavinda", "kavinda-G01", "kavinda-A02-SWAP"),
    ("A-SWAPMID-05-nuwan",   "nuwan",   "nuwan-G01",   "nuwan-A02-SWAP"),
    ("A-SWAPMID-06-nuwan",   "nuwan",   "nuwan-G01",   "nuwan-A03-SWAP"),
]


def load(src: Path, name: str) -> dict:
    with open(src / name / LABELS) as fh:
        return json.load(fh)


def copy(src: Path, dst: Path, name: str) -> None:
    if not src.exists():
        raise FileNotFoundError(src)
    shutil.copy2(src, dst / name)


def write_labels(dst: Path, payload: dict) -> None:
    with open(dst / LABELS, "w") as fh:
        json.dump(payload, fh, indent=2)
        fh.write("\n")


def build(src: Path, out: Path, dry: bool) -> int:
    made = 0

    for sample_id, subject, swap, genuine in A_CHANNEL:
        own = load(src, genuine)
        dst = out / sample_id
        print(f"  {sample_id:24} {swap} imagery + {genuine} scan   claim={own['claimedNic']}")
        if dry:
            continue
        dst.mkdir(parents=True, exist_ok=True)
        # Everything the camera saw comes from the swap attempt, unaltered.
        for f in (NIC_IMAGE, FACE_IMAGE, SELF_IMAGE):
            copy(src / swap / f, dst, f)
        # The one substitution: the officer uploads the presenter's own paperwork.
        copy(src / genuine / SCANNED_NIC, dst, SCANNED_NIC)
        write_labels(dst, {
            "claimedNic": own["claimedNic"],
            "subjectId": subject,
            "groundTruth": "ATTACK",
            "attackType": "CHANNEL_MISMATCH",
            "device": "unspecified",
            "lighting": load(src, swap).get("lighting", "L1"),
            "distance": "near",
            "extra": {
                "constructed": "true",
                "derivedFrom": f"imagery={swap}; scan={genuine}",
                "recipe": "own genuine scan uploaded while presenting another person's card",
                "targets": "cmp5 (scan vs presented card) must disagree",
                "evidentialStatus": "faithful reconstruction - the scan is a file upload in the "
                                    "live flow, so substituting it is the attack itself",
            },
        })
        made += 1

    for sample_id, subject, genuine, swap in A_SWAPMID:
        own = load(src, genuine)
        dst = out / sample_id
        print(f"  {sample_id:24} {genuine} card/face/scan + {swap} selfImage   claim={own['claimedNic']}")
        if dry:
            continue
        dst.mkdir(parents=True, exist_ok=True)
        # Document capture and face: the presenter's own, from their genuine attempt.
        copy(src / genuine / NIC_IMAGE, dst, NIC_IMAGE)
        copy(src / genuine / FACE_IMAGE, dst, FACE_IMAGE)
        copy(src / genuine / SCANNED_NIC, dst, SCANNED_NIC)
        # Co-presence capture: the same person holding somebody else's card.
        copy(src / swap / SELF_IMAGE, dst, SELF_IMAGE)
        write_labels(dst, {
            "claimedNic": own["claimedNic"],
            "subjectId": subject,
            "groundTruth": "ATTACK",
            "attackType": "MIDCAPTURE_SWAP",
            "device": "unspecified",
            "lighting": own.get("lighting", "L1"),
            "distance": "near",
            "extra": {
                "constructed": "true",
                "derivedFrom": f"card/face/scan={genuine}; selfImage={swap}",
                "recipe": "own card shown for the document capture, another person's card held "
                          "for the co-presence capture",
                "targets": "cmp2 (presented card vs card in the held photo) must disagree",
                "evidentialStatus": "constructed composite - the two captures are separate events "
                                    "in the live flow, but were not recorded as one session",
            },
        })
        made += 1

    for sample_id, subject, genuine, other in A_FACESWAP:
        own = load(src, genuine)
        dst = out / sample_id
        print(f"  {sample_id:30} {genuine} face/card/scan + {other} co-presence   claim={own['claimedNic']}")
        if dry:
            continue
        dst.mkdir(parents=True, exist_ok=True)
        # Everything except the co-presence frame belongs to the enrolling subject.
        for f in (NIC_IMAGE, FACE_IMAGE, SCANNED_NIC):
            copy(src / genuine / f, dst, f)
        # The co-presence frame is a different person entirely.
        copy(src / other / SELF_IMAGE, dst, SELF_IMAGE)
        write_labels(dst, {
            "claimedNic": own["claimedNic"],
            "subjectId": subject,
            "groundTruth": "ATTACK",
            "attackType": "FACE_SUBSTITUTION",
            "device": "unspecified",
            "lighting": own.get("lighting", "L1"),
            "distance": "near",
            "extra": {
                "constructed": "true",
                "derivedFrom": f"face/card/scan={genuine}; coPresence={other}",
                "coPresenceSubject": load(src, other)["subjectId"],
                "recipe": "one person supplies the enrolment portrait, a different person appears "
                          "in the co-presence photo holding the card",
                "targets": "cmp3 (live face vs face in the held photo) must disagree",
                "evidentialStatus": "constructed composite - the portrait and the co-presence photo "
                                    "are separate captures in the live flow, so a coached impostor "
                                    "pair is a real branch scenario, but these two frames were not "
                                    "recorded as one session",
            },
        })
        made += 1

    return made


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="data/corpus-pilot")
    ap.add_argument("--out", default="data/corpus-derived")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    src, out = Path(args.src), Path(args.out)
    if not src.is_dir():
        print(f"source corpus not found: {src}", file=sys.stderr)
        return 2

    print(f"{'DRY RUN - ' if args.dry_run else ''}building derived samples from {src} into {out}\n")
    try:
        made = build(src, out, args.dry_run)
    except FileNotFoundError as e:
        print(f"\nmissing required image: {e}", file=sys.stderr)
        return 1

    print(f"\n{'would write' if args.dry_run else 'wrote'} {made if not args.dry_run else len(A_CHANNEL) + len(A_SWAPMID) + len(A_FACESWAP)} samples")
    if not args.dry_run:
        print("Every sample carries extra.constructed=true - keep them reported separately.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
