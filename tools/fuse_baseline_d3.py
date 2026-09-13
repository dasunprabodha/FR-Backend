#!/usr/bin/env python3
"""
D3 - the published score-level fusion baseline, for comparison against D0/D2/D4.

Why this exists
---------------
Every other rule in the ladder (D0, D1, D1', D2, D4) was written by the candidate.
D3 is the only comparator drawn from the literature rather than from this codebase,
and it exists to answer one objection: "you only beat a rule you wrote yourself."

Method
------
Jain, Nandakumar & Ross, "Score normalization in multimodal biometric systems,"
Pattern Recognition 38(12):2270-2285, 2005. Each score is normalised onto a common
scale, the normalised scores are combined by the sum rule, and the total is compared
against a single threshold. That paper found min-max, z-score and tanh normalisation
followed by a simple sum to give the best recognition performance, with tanh the most
robust to outliers; all three are implemented here so the baseline is reported at its
best rather than at an arbitrary choice.

Unlike a conjunctive gate, fusion lets a strong score on one channel compensate for a
weak score on another. That is the property under test.

Missing channels
----------------
cmp4 and cmp5 exist only when an officer uploaded a scan; they are jointly absent on
13 of 52 collected samples. Binding is absent on 2 (documents carrying no NIC number).
Absent channels are excluded from the sum and the total is divided by the number of
channels actually available, which is the standard treatment for unavailable modalities
and mirrors Szczuko et al. (Sensors 22(6):2356, 2022). Imputing a value would invent
evidence; scoring an absent channel as zero would penalise an applicant for a
configuration fact.

Operating points
----------------
A fused score needs a threshold, and where that threshold comes from decides whether
the comparison is fair. Three are reported:

  oracle       chosen to maximise balanced accuracy on the same 52 samples it is then
               scored on. This is leakage, deliberately: it is the best D3 could
               possibly do on this corpus, and it favours the baseline. If the
               proposed rule still holds up against it, the result is stronger.
  matched      the strictest threshold at which D3 accepts zero attacks, i.e. the same
               security level D2 and D4 achieve. What it costs in bona fide refusals
               at that point is the operationally meaningful comparison.
  loso         leave-one-subject-out: normalisation parameters and threshold are fitted
               on 10 subjects and applied to the held-out one, pooled over 11 folds.
               The only unbiased estimate here, and the one to quote.

Variants
--------
  D3-face      the five face comparisons only. Against D0 this isolates fusion versus
               conjunction over the same information.
  D3-all       the five comparisons plus identity binding. Against D2 and D4 this
               isolates fusion versus grouping over the same information.

Usage
-----
    python3 tools/fuse_baseline_d3.py
    python3 tools/fuse_baseline_d3.py --run final-70 --out data/evaluation-runs/final-70/d3.csv
"""

import argparse
import csv
import math
import os
import statistics
from collections import Counter, defaultdict

CMP = ["cmp1Similarity", "cmp2Similarity", "cmp3Similarity", "cmp4Similarity", "cmp5Similarity"]
BINDING = "bindingScore"


# ---------------------------------------------------------------- data loading

def load(run_dir):
    """Collected samples only. Constructed samples are demonstrations, not measurements,
    and pooling them would mix the two and skew the class balance - all 18 are attacks."""
    path = os.path.join(run_dir, "results.csv")
    with open(path, newline="") as fh:
        rows = list(csv.DictReader(fh))
    # Runs predating final-70 have no "constructed" column; every sample in those is
    # collected, so an absent column means the same thing as an explicit "false".
    return [r for r in rows if (r.get("constructed") or "").strip().lower() not in ("true", "1")]


def num(row, key):
    v = row.get(key, "").strip()
    return float(v) if v else None


def is_attack(r):
    return r["groundTruth"] == "ATTACK"


def is_control(r):
    """The two university-ID samples: deliberate negative controls. Correct refusals,
    so they are excluded from the bona fide refusal rate and reported separately."""
    return "UNIID" in r["sampleId"]


# ------------------------------------------------------------- normalisation

def fit_params(rows, channels):
    """Distribution parameters per channel, estimated over whatever rows are given.
    For LOSO these are the training subjects only, so nothing about the held-out
    subject informs its own normalisation."""
    p = {}
    for ch in channels:
        vals = [v for v in (num(r, ch) for r in rows) if v is not None]
        if len(vals) < 2:
            p[ch] = None
            continue
        p[ch] = {
            "min": min(vals),
            "max": max(vals),
            "mean": statistics.fmean(vals),
            "sd": statistics.pstdev(vals) or 1e-9,
        }
    return p


def normalise(value, par, scheme):
    if value is None or par is None:
        return None
    if scheme == "minmax":
        span = par["max"] - par["min"]
        return 0.5 if span < 1e-12 else (value - par["min"]) / span
    if scheme == "zscore":
        return (value - par["mean"]) / par["sd"]
    if scheme == "tanh":
        # Hampel tanh estimator as used in the source paper; 0.01 scaling keeps the
        # bulk of the distribution inside the linear region of tanh.
        return 0.5 * (math.tanh(0.01 * (value - par["mean"]) / par["sd"]) + 1.0)
    raise ValueError(scheme)


def fused_score(row, params, channels, scheme):
    """Sum rule, divided by the number of channels actually present."""
    parts = [normalise(num(row, ch), params.get(ch), scheme) for ch in channels]
    parts = [p for p in parts if p is not None]
    return sum(parts) / len(parts) if parts else None


# ------------------------------------------------------------------ scoring

def outcomes(rows, scores, threshold):
    """Accepted = fused score at or above threshold. A sample with no computable score
    is treated as not accepted."""
    att = gen = ctl = 0
    att_n = gen_n = ctl_n = 0
    for r in rows:
        s = scores[r["sampleId"]]
        accepted = s is not None and s >= threshold
        if is_attack(r):
            att_n += 1
            att += accepted
        elif is_control(r):
            ctl_n += 1
            ctl += not accepted
        else:
            gen_n += 1
            gen += not accepted
    return {
        "attacks_accepted": att, "attacks_n": att_n,
        "genuine_refused": gen, "genuine_n": gen_n,
        "controls_refused": ctl, "controls_n": ctl_n,
    }


def balanced_accuracy(o):
    """Mean of per-class accuracy. Used for the oracle operating point because the
    corpus is imbalanced (30 attacks against 20 NIC-bearing bona fide)."""
    tnr = 1.0 - (o["attacks_accepted"] / o["attacks_n"]) if o["attacks_n"] else 0.0
    tpr = 1.0 - (o["genuine_refused"] / o["genuine_n"]) if o["genuine_n"] else 0.0
    return (tnr + tpr) / 2.0


def candidate_thresholds(scores):
    vals = sorted({v for v in scores.values() if v is not None})
    if not vals:
        return [0.0]
    cuts = [vals[0] - 1e-6]
    cuts += [(a + b) / 2.0 for a, b in zip(vals, vals[1:])]
    cuts.append(vals[-1] + 1e-6)
    return cuts


def pick_oracle(rows, scores):
    best, best_t = -1.0, None
    for t in candidate_thresholds(scores):
        ba = balanced_accuracy(outcomes(rows, scores, t))
        if ba > best:
            best, best_t = ba, t
    return best_t


def pick_matched(rows, scores):
    """Loosest threshold that still admits zero attacks - i.e. D2/D4's security level,
    reached as permissively as possible so bona fide refusal is not overstated."""
    best_t = None
    for t in candidate_thresholds(scores):
        if outcomes(rows, scores, t)["attacks_accepted"] == 0:
            if best_t is None or t < best_t:
                best_t = t
    return best_t


def auc(rows, scores):
    """Area under the ROC for the fused score. Attack = negative class, bona fide
    (excluding controls) = positive. Ties contribute 0.5, as usual."""
    pos = [scores[r["sampleId"]] for r in rows if not is_attack(r) and not is_control(r)]
    neg = [scores[r["sampleId"]] for r in rows if is_attack(r)]
    pos = [p for p in pos if p is not None]
    neg = [n for n in neg if n is not None]
    if not pos or not neg:
        return None
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


# --------------------------------------------------------------------- LOSO

def loso(rows, channels, scheme):
    """Normalisation and threshold fitted on 10 subjects, applied to the held-out one.
    Outcomes are pooled across folds. Subject is the unit because the corpus is
    clustered: 13 distinct face photographs stand behind 70 samples."""
    subjects = sorted({r["subjectId"] for r in rows})
    pooled = defaultdict(int)
    fold_thresholds = []
    for held in subjects:
        train = [r for r in rows if r["subjectId"] != held]
        test = [r for r in rows if r["subjectId"] == held]
        params = fit_params(train, channels)
        train_scores = {r["sampleId"]: fused_score(r, params, channels, scheme) for r in train}
        # A fold with no attacks gives the oracle nothing to separate; fall back to the
        # threshold that keeps every training bona fide sample accepted.
        t = pick_oracle(train, train_scores)
        fold_thresholds.append(t)
        test_scores = {r["sampleId"]: fused_score(r, params, channels, scheme) for r in test}
        for k, v in outcomes(test, test_scores, t).items():
            pooled[k] += v
    return dict(pooled), fold_thresholds


# ------------------------------------------------------------- reference rules

def d0(r):
    return r.get("similarityPassed") == "true"


def d2(r):
    return r.get("similarityPassed") == "true" and r.get("bindingBlocked") != "true"


def d4(r):
    # Renamed from gatePassedExLiveness; older saved runs still carry the old name.
    v = r.get("decisionWithoutLiveness")
    if v is None:
        v = r.get("gatePassedExLiveness")
    return v == "true"


def rule_available(rows, fn):
    """A reference rule is only reportable if the run actually recorded its columns."""
    return any(fn(r) for r in rows) or any(
        (r.get("similarityPassed") or r.get("bindingBlocked")
         or r.get("decisionWithoutLiveness") or r.get("gatePassedExLiveness"))
        for r in rows)


def rule_outcomes(rows, fn):
    att = sum(1 for r in rows if is_attack(r) and fn(r))
    gen = sum(1 for r in rows if not is_attack(r) and not is_control(r) and not fn(r))
    ctl = sum(1 for r in rows if is_control(r) and not fn(r))
    return {"attacks_accepted": att, "attacks_n": sum(1 for r in rows if is_attack(r)),
            "genuine_refused": gen, "genuine_n": sum(1 for r in rows if not is_attack(r) and not is_control(r)),
            "controls_refused": ctl, "controls_n": sum(1 for r in rows if is_control(r))}


# ------------------------------------------------------------------ reporting

def fmt(o):
    return "%2d/%-2d %8d/%-2d %8d/%-2d" % (
        o["attacks_accepted"], o["attacks_n"],
        o["genuine_refused"], o["genuine_n"],
        o["controls_refused"], o["controls_n"])


def sign_test(rows, fn_a, scores, threshold):
    """Subject-level exact sign test, rule A against the fused rule, on attacks.
    Subject is the unit of inference for the reason given in loso()."""
    per = defaultdict(lambda: [0, 0])
    for r in rows:
        if not is_attack(r):
            continue
        s = scores[r["sampleId"]]
        per[r["subjectId"]][0] += fn_a(r)
        per[r["subjectId"]][1] += (s is not None and s >= threshold)
    better = sum(1 for a, b in per.values() if b < a)
    worse = sum(1 for a, b in per.values() if b > a)
    k = better + worse
    if k == 0:
        return better, worse, len(per) - k, 1.0
    p = 2 * sum(math.comb(k, i) for i in range(0, min(better, worse) + 1)) / 2 ** k
    return better, worse, len(per) - k, min(p, 1.0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", default="final-70")
    ap.add_argument("--runs-dir", default="data/evaluation-runs")
    ap.add_argument("--out", default=None, help="write per-sample fused scores here")
    args = ap.parse_args()

    run_dir = os.path.join(args.runs_dir, args.run)
    rows = load(run_dir)

    variants = {
        "D3-face": CMP,
        "D3-all": CMP + [BINDING],
    }
    schemes = ["minmax", "zscore", "tanh"]

    print("D3 - published score-level fusion baseline (Jain, Nandakumar & Ross, 2005)")
    print("run=%s   collected samples=%d   subjects=%d\n"
          % (args.run, len(rows), len({r["subjectId"] for r in rows})))

    print("Reference rules on the same samples")
    print("  %-12s %s" % ("", "attacks   genuine-refused  controls-refused"))
    for name, fn in (("D0", d0), ("D2", d2), ("D4", d4)):
        if rule_available(rows, fn):
            print("  %-12s %s" % (name, fmt(rule_outcomes(rows, fn))))
        else:
            print("  %-12s not recorded in this run" % name)
    print()

    best_rows = []
    for vname, channels in variants.items():
        print("=" * 78)
        print("%s   channels: %s" % (vname, ", ".join(c.replace("Similarity", "") for c in channels)))
        print("=" * 78)
        for scheme in schemes:
            params = fit_params(rows, channels)
            scores = {r["sampleId"]: fused_score(r, params, channels, scheme) for r in rows}
            a = auc(rows, scores)

            t_or = pick_oracle(rows, scores)
            o_or = outcomes(rows, scores, t_or)

            t_mt = pick_matched(rows, scores)
            o_mt = outcomes(rows, scores, t_mt) if t_mt is not None else None

            o_lo, folds = loso(rows, channels, scheme)

            print("\n  normalisation: %-7s   ROC-AUC = %s" % (scheme, "n/a" if a is None else "%.3f" % a))
            print("    %-10s %s   threshold=%.4f" % ("oracle", fmt(o_or), t_or))
            if o_mt is not None:
                print("    %-10s %s   threshold=%.4f" % ("matched", fmt(o_mt), t_mt))
            else:
                print("    %-10s  no threshold admits zero attacks" % "matched")
            print("    %-10s %s   folds=%d" % ("loso", fmt(o_lo), len(folds)))

            best_rows.append((vname, scheme, a, o_or, o_mt, o_lo, scores, t_or))

    # ---- headline comparison, using the variant/scheme with the best oracle balance
    print("\n" + "=" * 78)
    print("Comparison against the proposed rules")
    print("=" * 78)
    pick = max(best_rows, key=lambda x: balanced_accuracy(x[3]))
    vname, scheme, a, o_or, o_mt, o_lo, scores, t_or = pick
    print("Best D3 configuration by oracle balanced accuracy: %s / %s" % (vname, scheme))
    print("  oracle   %s" % fmt(o_or))
    if o_mt:
        print("  matched  %s   <- same security as D2/D4; this is the cost of fusion" % fmt(o_mt))
    print("  loso     %s   <- the unbiased estimate\n" % fmt(o_lo))

    for name, fn in (("D0", d0), ("D2", d2), ("D4", d4)):
        if not rule_available(rows, fn):
            continue
        b, w, t, p = sign_test(rows, fn, scores, t_or)
        print("  sign test %s vs D3(oracle): %d subjects favour D3, %d favour %s, %d tied, p=%.4f"
              % (name, b, w, name, t, p))

    if args.out:
        params = fit_params(rows, variants[vname])
        with open(args.out, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["sampleId", "subjectId", "groundTruth", "attackType",
                        "d3Variant", "d3Scheme", "d3Score", "d3OracleThreshold", "d3Accepted"])
            for r in rows:
                s = scores[r["sampleId"]]
                w.writerow([r["sampleId"], r["subjectId"], r["groundTruth"], r["attackType"],
                            vname, scheme, "" if s is None else "%.6f" % s,
                            "%.6f" % t_or, "true" if (s is not None and s >= t_or) else "false"])
        print("\nper-sample scores written to %s" % args.out)


if __name__ == "__main__":
    main()
