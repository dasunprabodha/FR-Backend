#!/usr/bin/env python3
"""
Produce the four results Chapter 5 still marks RESULT REQUIRED.

Nothing here needs new data collection, new images, or any change to the corpus.
Every figure is computed from data already stored in an evaluation run's
results.csv. Altering the corpus would in fact be harmful: the reported numbers
are tied to run final-70, and changing its inputs would break that link.

What this produces
------------------
  5.7   check-by-check ablation, plus the 2^3 factorial over R3/R4/R5
  5.9   character-level reading accuracy, split by reading source
  5.10  FMR/FNMR, equal error rate and ROC area for cmp1 and cmp4
  5.11  error-versus-review characteristic

Why the decision rule is reimplemented here
-------------------------------------------
The live rule lives in Java (DependencyAwareDecisionService) and needs AWS calls
to re-run over images. A corpus re-run costs several hundred billed Rekognition
calls per configuration, and the ablations need fourteen configurations.

But the rule is a deterministic function of scores that are already persisted, so
it can be recomputed offline for free. The risk is that a reimplementation quietly
diverges from the Java. That risk is handled rather than accepted: before any
ablation runs, the reimplementation must reproduce the stored proposedDecision on
every sample. If a single sample disagrees, the script aborts and prints the
mismatches. No result below is reported unless that check passes.

Rules were derived from the stored data rather than read off the configuration, so
that what is modelled is what actually ran:

  banding        SUPPORTS if score >= threshold + margin
                 MARGINAL  if threshold - margin <= score < threshold + margin
                 CONTRADICTS otherwise            (threshold 80, margin 5)
  groups         document portrait = min(cmp1, cmp4)   co-presence = min(cmp2, cmp3)
                 channel agreement = cmp5             binding = the number check
  R3 on          binding reads its verdict label, not its score
  R4 on          document portrait reports MARGINAL when the two document
                 channels diverge and the better one clears the threshold
  R5 on          an absent scan makes channel agreement NOT_APPLICABLE, not UNAVAILABLE
  decision       any CONTRADICTS -> REJECT; else any MARGINAL or UNAVAILABLE -> REVIEW;
                 else APPROVE.  NOT_APPLICABLE and ABSENT are benign.

Usage
-----
    python3 tools/complete_missing_results.py
    python3 tools/complete_missing_results.py --run final-70 --out-dir data/evaluation-runs/final-70
"""

import argparse
import csv
import math
import os
import statistics
from collections import Counter, defaultdict

THRESHOLD = 80.0
MARGIN = 5.0
BIND_THRESHOLD = 0.80
BIND_MARGIN = 0.10

SUPPORTS, MARGINAL, CONTRADICTS, NA, ABSENT = "SUPPORTS", "MARGINAL", "CONTRADICTS", "NOT_APPLICABLE", "ABSENT"
POSITIVE_BINDING = {"EXACT_MATCH", "CONFUSION_CORRECTED_MATCH", "FORMAT_EQUIVALENT_MATCH"}


# ------------------------------------------------------------------ loading

def load(run_dir):
    with open(os.path.join(run_dir, "results.csv"), newline="") as fh:
        rows = list(csv.DictReader(fh))
    return [r for r in rows if (r.get("constructed") or "").strip().lower() not in ("true", "1")]


def num(row, key):
    v = (row.get(key) or "").strip()
    return float(v) if v else None


def is_attack(r):
    return r["groundTruth"] == "ATTACK"


def is_control(r):
    return "UNIID" in r["sampleId"]


# ------------------------------------------------------------- the rule

def band(score, threshold=None, margin=None):
    # Read the module globals at call time, not at definition time: the
    # error-versus-review sweep in 5.11 varies MARGIN, and a default argument
    # would be bound once at import and silently ignore every change.
    threshold = THRESHOLD if threshold is None else threshold
    margin = MARGIN if margin is None else margin
    if score is None:
        return None
    if score >= threshold + margin:
        return SUPPORTS
    if score >= threshold - margin:
        return MARGINAL
    return CONTRADICTS


def group_states(r, drop=None, r3=True, r4=True, r5=True):
    """Five group states for one attempt. `drop` removes one evidence channel."""
    drop = drop or ""
    c1 = None if drop == "cmp1" else num(r, "cmp1Similarity")
    c2 = None if drop == "cmp2" else num(r, "cmp2Similarity")
    c3 = None if drop == "cmp3" else num(r, "cmp3Similarity")
    c4 = None if drop == "cmp4" else num(r, "cmp4Similarity")
    c5 = None if drop == "cmp5" else num(r, "cmp5Similarity")

    # --- document portrait: min(cmp1, cmp4), with R4 abstaining on divergence
    present = [v for v in (c1, c4) if v is not None]
    if not present:
        doc = NA
    else:
        doc = band(min(present))
        if r4 and r["crossChannelStatus"] == "DIVERGENT" and len(present) == 2:
            if max(present) >= THRESHOLD:
                doc = MARGINAL

    # --- co-presence: min(cmp2, cmp3)
    present = [v for v in (c2, c3) if v is not None]
    co = band(min(present)) if present else NA

    # --- channel agreement: cmp5 only
    if drop == "cmp5":
        chan = NA
    elif c5 is None:
        chan = NA if r5 else "UNAVAILABLE"
    else:
        chan = band(c5)

    # --- identity binding
    if drop == "binding":
        bind = NA
    else:
        outcome = r["bindingOutcome"]
        if outcome in ("", "UNAVAILABLE"):
            bind = ABSENT
        elif r3:
            bind = SUPPORTS if outcome in POSITIVE_BINDING else CONTRADICTS
        else:
            bind = band(num(r, "bindingScore"), BIND_THRESHOLD, BIND_MARGIN) or ABSENT

    return doc, co, chan, bind, NA  # liveness never available in an offline replay


def decide(states):
    if CONTRADICTS in states:
        return "REJECT"
    if MARGINAL in states or "UNAVAILABLE" in states:
        return "REVIEW"
    return "APPROVE"


def rule(r, **kw):
    return decide(group_states(r, **kw))


# ------------------------------------------------------------- validation

def validate(rows):
    """The reimplementation must reproduce the stored decision on every sample."""
    bad = []
    for r in rows:
        got = rule(r)
        want = r["proposedDecision"]
        if got != want:
            bad.append((r["sampleId"], want, got, group_states(r)))
    return bad


# ------------------------------------------------------------- outcome counting

def tally(rows, decide_fn):
    att = [r for r in rows if is_attack(r)]
    gen = [r for r in rows if not is_attack(r) and not is_control(r)]
    ctl = [r for r in rows if is_control(r)]
    d = {r["sampleId"]: decide_fn(r) for r in rows}
    return {
        "attacks_accepted": sum(1 for r in att if d[r["sampleId"]] == "APPROVE"),
        "attacks_n": len(att),
        "genuine_refused": sum(1 for r in gen if d[r["sampleId"]] == "REJECT"),
        "genuine_reviewed": sum(1 for r in gen if d[r["sampleId"]] == "REVIEW"),
        "genuine_n": len(gen),
        "controls_refused": sum(1 for r in ctl if d[r["sampleId"]] == "REJECT"),
        "controls_n": len(ctl),
        "reviewed_all": sum(1 for r in rows if d[r["sampleId"]] == "REVIEW"),
        "n": len(rows),
    }


def fmt(o):
    return ("%2d/%-2d  %8d/%-2d %8d %10d/%-2d %8d"
            % (o["attacks_accepted"], o["attacks_n"],
               o["genuine_refused"], o["genuine_n"], o["genuine_reviewed"],
               o["controls_refused"], o["controls_n"], o["reviewed_all"]))


HDR = "%-26s %s" % ("", "attacks   genuine-ref  gen-rev   controls   reviewed")


# ---------------------------------------------------------- 5.9 reading accuracy

def levenshtein(a, b):
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def reading_accuracy(rows):
    """Ground truth is available only for bona fide attempts: there the declared
    number IS the number printed on the card. Attack rows are excluded because
    their declared number is deliberately wrong, so it is not a reading target.

    A raw character comparison is not the whole story here. Sri Lanka's two number
    formats are different renderings of the same identity, so a reading that differs
    from the declared string can still be a correct identification of the same person
    once the format is reconciled. Both quantities are therefore reported: the raw
    character error rate, and how many differences actually changed the verdict."""
    by_source = defaultdict(lambda: {"n": 0, "exact": 0, "chars": 0, "errs": 0,
                                     "unread": 0, "absorbed": 0, "wrong_verdict": 0})
    detail = []
    for r in rows:
        if is_attack(r) or is_control(r):
            continue
        truth = (r["claimedNic"] or "").strip().upper()
        got = (r["extractedNic"] or "").strip().upper()
        src = r["ocrSource"] or "NONE"
        b = by_source[src]
        b["n"] += 1
        if not got:
            b["unread"] += 1
            continue
        d = levenshtein(truth, got)
        b["chars"] += len(truth)
        b["errs"] += d
        b["exact"] += (truth == got)
        if d:
            # A difference that the grading ladder still resolved to a positive
            # match has not produced a wrong answer, only an imperfect reading.
            if r["bindingOutcome"] in POSITIVE_BINDING:
                b["absorbed"] += 1
            else:
                b["wrong_verdict"] += 1
            detail.append((r["sampleId"], truth, got, r["bindingOutcome"], src, d))
    return by_source, detail


# ------------------------------------------------- 5.10 comparison error rates

def auc_of(gen, imp):
    return sum((g > i) + 0.5 * (g == i) for g in gen for i in imp) / (len(gen) * len(imp))


def auc_cluster_ci(rows, field, iters=5000, seed=11):
    """Participant-level bootstrap for the ROC area. A perfect separation on a few
    dozen pairs is fragile, and an interval says so. Participants are resampled,
    not pairs, because the collection is clustered."""
    import random
    rnd = random.Random(seed)
    subs = sorted({r["subjectId"] for r in rows})
    pool = {sv: [r for r in rows if r["subjectId"] == sv] for sv in subs}
    est = []
    for _ in range(iters):
        draw = []
        for _ in range(len(subs)):
            draw += pool[rnd.choice(subs)]
        g = [num(r, field) for r in draw if not is_attack(r) and not is_control(r)]
        i = [num(r, field) for r in draw if is_attack(r) and r["attackType"] == "MISMATCHED_GENUINE_NIC"]
        g = [v for v in g if v is not None]
        i = [v for v in i if v is not None]
        if g and i:
            est.append(auc_of(g, i))
    if not est:
        return None
    est.sort()
    return est[int(0.025 * len(est))], est[int(0.975 * len(est))]


def comparison_rates(rows, field):
    """Genuine pairs: bona fide attempts. Impostor pairs: attempts presenting
    another person's document. Claim-mismatch attempts are EXCLUDED, because the
    card genuinely belongs to the presenter, so the pair is genuine by construction
    and scoring it as an impostor pair would understate the comparison."""
    gen = [num(r, field) for r in rows if not is_attack(r) and not is_control(r)]
    imp = [num(r, field) for r in rows if is_attack(r) and r["attackType"] == "MISMATCHED_GENUINE_NIC"]
    gen = [v for v in gen if v is not None]
    imp = [v for v in imp if v is not None]
    if not gen or not imp:
        return None
    cuts = sorted(set(gen + imp))
    curve = []
    for t in [c - 1e-9 for c in cuts] + [max(cuts) + 1e-9]:
        fmr = sum(1 for v in imp if v >= t) / len(imp)
        fnmr = sum(1 for v in gen if v < t) / len(gen)
        curve.append((t, fmr, fnmr))
    eer_t, eer = min(((t, (a + b) / 2) for t, a, b in curve), key=lambda x: abs(
        [c for c in curve if c[0] == x[0]][0][1] - [c for c in curve if c[0] == x[0]][0][2]))
    auc = auc_of(gen, imp)
    at80 = [c for c in curve if c[0] >= THRESHOLD][0] if any(c[0] >= THRESHOLD for c in curve) else None
    return {"n_gen": len(gen), "n_imp": len(imp), "auc": auc, "eer": eer, "eer_t": eer_t,
            "at80": at80, "curve": curve}


# ------------------------------------------------- 5.11 error vs review

def error_versus_review(rows, margins):
    out = []
    for m in margins:
        global MARGIN
        old, MARGIN = MARGIN, m
        o = tally(rows, lambda r: rule(r))
        MARGIN = old
        errors = o["attacks_accepted"] + o["genuine_refused"]
        out.append((m, o["reviewed_all"] / o["n"], o["attacks_accepted"],
                    o["genuine_refused"], errors, o["genuine_reviewed"]))
    return out


# --------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", default="final-70")
    ap.add_argument("--runs-dir", default="data/evaluation-runs")
    ap.add_argument("--out-dir", default=None)
    args = ap.parse_args()

    run_dir = os.path.join(args.runs_dir, args.run)
    rows = load(run_dir)
    out_dir = args.out_dir or run_dir

    print("Completing the results Chapter 5 marks RESULT REQUIRED")
    print("run=%s   collected samples=%d   participants=%d\n"
          % (args.run, len(rows), len({r["subjectId"] for r in rows})))

    # ---- validation gate
    bad = validate(rows)
    if bad:
        print("VALIDATION FAILED - the offline rule does not reproduce the stored decision.")
        print("No results are reported. Mismatching samples:\n")
        for sid, want, got, st in bad[:15]:
            print("   %-22s stored=%-8s recomputed=%-8s states=%s" % (sid, want, got, st))
        raise SystemExit(1)
    print("Validation: the offline rule reproduces proposedDecision on all %d samples.\n" % len(rows))

    base = tally(rows, lambda r: rule(r))
    print(HDR)
    print("  %-24s %s" % ("D4 as deployed", fmt(base)))

    # ================= 5.7 channel ablation =================
    print("\n" + "=" * 78)
    print("5.7  Check-by-check ablation - one evidence channel removed at a time")
    print("=" * 78)
    print(HDR)
    abl = []
    for ch, label in [("cmp1", "card portrait vs face"), ("cmp4", "scan vs face"),
                      ("cmp2", "card vs held card"), ("cmp3", "face vs held face"),
                      ("cmp5", "scan vs card"), ("binding", "number check")]:
        o = tally(rows, lambda r, c=ch: rule(r, drop=c))
        abl.append((ch, label, o))
        print("  minus %-18s %s" % (ch, fmt(o)))
    print("\n  Change against the deployed rule:")
    for ch, label, o in abl:
        print("    %-9s %-22s attacks %+d   genuine refused %+d   reviewed %+d"
              % (ch, label,
                 o["attacks_accepted"] - base["attacks_accepted"],
                 o["genuine_refused"] - base["genuine_refused"],
                 o["reviewed_all"] - base["reviewed_all"]))

    # ================= 5.7 factorial =================
    print("\n" + "=" * 78)
    print("5.7  Factorial over the three reasoning behaviours (R3, R4, R5)")
    print("=" * 78)
    print("  R3 categorical binding | R4 abstain on divergence | R5 absent scan = not applicable")
    print("\n  %-14s %s" % ("R3 R4 R5", HDR.strip()))
    fac = []
    for r3 in (False, True):
        for r4 in (False, True):
            for r5 in (False, True):
                o = tally(rows, lambda r: rule(r, r3=r3, r4=r4, r5=r5))
                fac.append((r3, r4, r5, o))
                print("  %-3s%-3s%-8s %s" % ("on " if r3 else "off", "on " if r4 else "off",
                                             "on" if r5 else "off", fmt(o)))
    print("\n  Main effect of each behaviour (mean change when switched on):")
    for idx, name in [(0, "R3 categorical binding"), (1, "R4 abstain on divergence"),
                      (2, "R5 absent scan benign")]:
        on = [o for c in fac if c[idx] for o in [c[3]]]
        off = [o for c in fac if not c[idx] for o in [c[3]]]
        print("    %-26s attacks %+.2f   genuine refused %+.2f   reviewed %+.2f"
              % (name,
                 statistics.fmean(x["attacks_accepted"] for x in on) - statistics.fmean(x["attacks_accepted"] for x in off),
                 statistics.fmean(x["genuine_refused"] for x in on) - statistics.fmean(x["genuine_refused"] for x in off),
                 statistics.fmean(x["reviewed_all"] for x in on) - statistics.fmean(x["reviewed_all"] for x in off)))

    # ================= 5.9 reading accuracy =================
    print("\n" + "=" * 78)
    print("5.9  Character-level reading accuracy, by reading source")
    print("=" * 78)
    print("  bona fide attempts only - the declared number is the printed number there\n")
    print("  %-16s %6s %8s %10s %12s %10s" % ("source", "n", "unread", "exact", "char errors", "CER"))
    acc, detail = reading_accuracy(rows)
    tot = {"n": 0, "exact": 0, "chars": 0, "errs": 0, "unread": 0,
           "absorbed": 0, "wrong_verdict": 0}
    for src, b in sorted(acc.items()):
        cer = b["errs"] / b["chars"] if b["chars"] else float("nan")
        print("  %-16s %6d %8d %10d %12d %9.3f%%"
              % (src, b["n"], b["unread"], b["exact"], b["errs"], 100 * cer))
        for k in tot:
            tot[k] += b[k]
    cer = tot["errs"] / tot["chars"] if tot["chars"] else float("nan")
    print("  %-16s %6d %8d %10d %12d %9.3f%%"
          % ("all", tot["n"], tot["unread"], tot["exact"], tot["errs"], 100 * cer))
    readable = tot["n"] - tot["unread"]
    print("\n  Field accuracy (exact whole-number match): %d/%d = %.1f%%"
          % (tot["exact"], readable, 100 * tot["exact"] / max(1, readable)))
    print("  Verdict accuracy (reading led to the correct identification): %d/%d = %.1f%%"
          % (readable - tot["wrong_verdict"], readable,
             100 * (readable - tot["wrong_verdict"]) / max(1, readable)))
    if detail:
        print("\n  Every reading that differed from the declared string:")
        for sid, truth, got, outcome, src, d in detail:
            print("    %-16s declared=%-13s read=%-13s edits=%d  %s  [%s]"
                  % (sid, truth, got, d, outcome, src))
        print("\n  %d of %d differences were resolved to a correct positive match by the"
              % (tot["absorbed"], tot["absorbed"] + tot["wrong_verdict"]))
        print("  format-reconciliation step, so they changed no verdict.")

    # ================= 5.10 comparison rates =================
    print("\n" + "=" * 78)
    print("5.10  Comparison-level error rates - cmp1 and cmp4 only")
    print("=" * 78)
    print("  claim-mismatch attempts excluded from the impostor set: those pairs genuinely match\n")
    for field, label in [("cmp1Similarity", "card portrait vs live face"),
                         ("cmp4Similarity", "uploaded scan vs live face")]:
        res = comparison_rates(rows, field)
        if not res:
            print("  %-28s not computable" % label)
            continue
        print("  %s" % label)
        print("    genuine pairs %d, impostor pairs %d" % (res["n_gen"], res["n_imp"]))
        ci = auc_cluster_ci(rows, field)
        if ci:
            print("    ROC area          %.4f   participant-bootstrap 95%% CI %.4f - %.4f"
                  % (res["auc"], ci[0], ci[1]))
        else:
            print("    ROC area          %.4f" % res["auc"])
        print("    equal error rate  %.4f  at threshold %.2f" % (res["eer"], res["eer_t"]))
        if res["at80"]:
            print("    at threshold 80   FMR %.4f   FNMR %.4f" % (res["at80"][1], res["at80"][2]))
        print()

    # ================= 5.11 error vs review =================
    print("=" * 78)
    print("5.11  Error-versus-review characteristic")
    print("=" * 78)
    print("  widening the borderline band sends more attempts to a reviewer\n")
    print("  %-8s %10s %10s %12s %9s %10s" % ("margin", "reviewed", "attacks", "gen refused",
                                              "errors", "gen rev"))
    evr = error_versus_review(rows, [0, 2.5, 5, 7.5, 10, 15, 20, 25, 30, 40])
    first_nonzero = None
    for m, rev, att, gref, err, grev in evr:
        flag = ""
        if att > 0 and first_nonzero is None:
            first_nonzero = m
            flag = "  <- attacks first accepted"
        print("  %-8.1f %9.1f%% %10d %12d %9d %10d%s" % (m, 100 * rev, att, gref, err, grev, flag))
    if first_nonzero is None:
        print("\n  Attack acceptance stayed at zero across the whole sweep.")
    else:
        print("\n  Attack acceptance first becomes non-zero at margin %.1f." % first_nonzero)

    # ================= 5.11 threshold sensitivity =================
    print("\n" + "=" * 78)
    print("5.11  Sensitivity to the similarity cut-off")
    print("=" * 78)
    print("  the deployed cut-off of 80 was inherited, not fitted; does the rule ordering hold?\n")
    print("  %-7s %-26s %-26s %s" % ("cut-off", "D0 conjunction", "D2 plus number check", "D4 grouped rule"))
    print("  %-7s %-26s %-26s %s" % ("", "attacks  genuine-ref", "attacks  genuine-ref", "attacks  genuine-ref  review"))
    global THRESHOLD
    sweep = []
    for t in (60, 65, 70, 75, 80, 85, 90, 95):
        old_t, THRESHOLD = THRESHOLD, t

        def d0(r):
            c2, c3, c4 = num(r, "cmp2Similarity"), num(r, "cmp3Similarity"), num(r, "cmp4Similarity")
            ok = (c2 is not None and c2 >= t) and (c3 is not None and c3 >= t)
            if c4 is not None:
                ok = ok and c4 >= t
            return "APPROVE" if ok else "REJECT"

        def d2(r):
            return "APPROVE" if (d0(r) == "APPROVE"
                                 and r["bindingOutcome"] in POSITIVE_BINDING) else "REJECT"

        o0, o2, o4 = tally(rows, d0), tally(rows, d2), tally(rows, lambda r: rule(r))
        THRESHOLD = old_t
        sweep.append((t, o0, o2, o4))
        print("  %-7d %2d/%-2d %8d/%-2d      %2d/%-2d %8d/%-2d      %2d/%-2d %8d/%-2d %6d"
              % (t, o0["attacks_accepted"], o0["attacks_n"], o0["genuine_refused"], o0["genuine_n"],
                 o2["attacks_accepted"], o2["attacks_n"], o2["genuine_refused"], o2["genuine_n"],
                 o4["attacks_accepted"], o4["attacks_n"], o4["genuine_refused"], o4["genuine_n"],
                 o4["reviewed_all"]))
    stable = all(x[2]["attacks_accepted"] <= x[1]["attacks_accepted"] for x in sweep)
    print("\n  Ordering D2 no worse than D0 on attacks at every cut-off tested: %s" % stable)

    # ---- write CSVs
    if out_dir:
        with open(os.path.join(out_dir, "ablation.csv"), "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["configuration", "attacks_accepted", "attacks_n", "genuine_refused",
                        "genuine_reviewed", "genuine_n", "controls_refused", "reviewed_all"])
            w.writerow(["deployed"] + [base[k] for k in ("attacks_accepted", "attacks_n",
                        "genuine_refused", "genuine_reviewed", "genuine_n", "controls_refused", "reviewed_all")])
            for ch, label, o in abl:
                w.writerow(["minus_" + ch] + [o[k] for k in ("attacks_accepted", "attacks_n",
                           "genuine_refused", "genuine_reviewed", "genuine_n", "controls_refused", "reviewed_all")])
            for r3, r4, r5, o in fac:
                name = "R3=%s_R4=%s_R5=%s" % (int(r3), int(r4), int(r5))
                w.writerow([name] + [o[k] for k in ("attacks_accepted", "attacks_n",
                           "genuine_refused", "genuine_reviewed", "genuine_n", "controls_refused", "reviewed_all")])
        with open(os.path.join(out_dir, "error_vs_review.csv"), "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["margin", "review_rate", "attacks_accepted", "genuine_refused",
                        "total_errors", "genuine_reviewed"])
            w.writerows(evr)
        with open(os.path.join(out_dir, "threshold_sweep.csv"), "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["threshold", "d0_attacks", "d0_genuine_refused", "d2_attacks",
                        "d2_genuine_refused", "d4_attacks", "d4_genuine_refused", "d4_reviewed"])
            for t, o0, o2, o4 in sweep:
                w.writerow([t, o0["attacks_accepted"], o0["genuine_refused"],
                            o2["attacks_accepted"], o2["genuine_refused"],
                            o4["attacks_accepted"], o4["genuine_refused"], o4["reviewed_all"]])
        print("\nwrote ablation.csv and error_vs_review.csv to %s" % out_dir)


if __name__ == "__main__":
    main()
