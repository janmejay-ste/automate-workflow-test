# E1 — Reproducibility Stability Audit (Baseline)

**Scope:** characterize sources of nondeterminism in `health_snapshot.json` between
consecutive identical-input runs.

**Status:** Pass 1 + Pass 2 complete. This file is the **baseline characterization**.
Future scope additions (parallel-fork stability, cluster-producing fixtures,
cross-OS) are listed under Limitations and left for follow-up work.

**Audit posture:** treated as a stability audit, not an implementation task.
Goal was to *discover* sources of nondeterminism, not to *eliminate* them.
Several legitimate sources were expected; the question was whether *internal
logic* contributes any beyond them.

---

## Methodology

For each pass:

1. Run the target test → save `health_snapshot.json` as snapshot X
2. Run the same target test again with identical flags → save as snapshot Y
3. Byte-level diff (sanity floor — should be non-zero because of `timestamp`)
4. Structured tree-walk diff with categorization:
   - `TIMESTAMP` — `timestamp` field only
   - `LEGITIMATE_HISTORY_SHIFT` — fields whose value depends on prior runs
     (the second run sees one more historical entry than the first)
   - `TOLERABLE_FLOAT_VARIANCE` — measurements that are environment-driven
     by definition (network latency, browser timing)
   - `EXTERNAL_INPUT_VARIANCE` — third-party-controlled inputs the system
     captures verbatim (e.g. analytics-script URLs with per-request payloads)
   - `REAL_NONDETERMINISM` — anything else; flagged for investigation

**Sanity floor:** if the byte-level diff returned zero positions, the diff
methodology itself would be broken (because `timestamp` always advances).
Both passes confirmed methodology integrity.

---

## Pass 1 — HomepageSmokeTest

Clean smoke run: no JS errors, no test failures, no clusters, no contributor
buckets, no URL findings, no business outcomes. Minimum surface area.

| Category | Count | Notes |
|---|---|---|
| TIMESTAMP | 1 | `timestamp` field |
| LEGITIMATE_HISTORY_SHIFT | 6 | `smoothedScore`, `confidence.sampleN`, `confidence.ratio`, `trendStats.{sampleSize, mean, stdDev, currentZScore}` |
| TOLERABLE_FLOAT_VARIANCE | 0 | (nothing to time) |
| EXTERNAL_INPUT_VARIANCE | 0 | (nothing externally captured) |
| **REAL_NONDETERMINISM** | **0** | None detected in tested scope |

**Methodology sanity:** byte-diff 28 positions across 7 regions, structured-diff
7 leaves — exact match. The diff tools are seeing what they should.

---

## Pass 2 — HomepageExhaustiveTest

Richer fixture: 185 URLs validated, 12 findings, 1 business transaction,
URL-validation timing data per finding.

| Category | Count | Notes |
|---|---|---|
| TIMESTAMP | 1 | Same |
| LEGITIMATE_HISTORY_SHIFT | 6 | Same 5 as Pass 1 + `semantic.businessOutcomes.transactions[0].startedAt` (per-transaction time) |
| TOLERABLE_FLOAT_VARIANCE | 12 | Network-driven `durationMs` for 11 URL findings + 1 business-transaction duration. Range observed: 16ms → 24099ms |
| EXTERNAL_INPUT_VARIANCE | 4 | Cloudflare Zaraz analytics URLs (`/cdn-cgi/zaraz/s.js?z=<base64>`) whose `z` query param encodes a per-page-load random-looking decimal — captured verbatim into `url` and `finalUrl` for findings[8] and findings[11] |
| **REAL_NONDETERMINISM** | **0** | None detected in tested scope |

**Methodology sanity:** byte-diff 1295 positions across the snapshot, structured-diff
23 leaves. The high byte/leaf ratio is explained by the two 535-character base64
Zaraz URLs contributing hundreds of byte-positions each when their payload shifts.

**Order-stability check (the strongest evidence in this pass):** all 12 URL findings
appear at *identical indices* in both snapshots, with matching `type` field.
URL-collection iteration order is deterministic.

---

## Interpretation

The system has been characterised as:

> Internally reproducible. Externally variable.

The four sources of difference between consecutive identical-input runs are all
either by-design (`timestamp`, history shift) or externally caused (network
latency, third-party tracking-script payloads). **No internal logic produced
different output for the same input** within the tested scope.

The base64-encoded Cloudflare Zaraz URLs are the only finding worth a product
decision: they're recorded verbatim into the snapshot, so the snapshot is not
byte-reproducible across runs even though the system itself is deterministic.
Options if byte-reproducibility ever matters (e.g. for E1-style automated
regression tests):

- **Canonicalise** tracking URLs (strip `?z=` query params from known analytics
  hosts) before recording into the snapshot. Trade-off: loses diagnostic info
  if a Zaraz URL genuinely breaks.
- **Filter** `cdn-cgi/zaraz/*` URLs out of URL validation entirely. Trade-off:
  same. Plus they would no longer be visible in dashboard URL panels.
- **Exclude** from snapshot equality checks in the reproducibility comparator,
  not the snapshot itself. Trade-off: requires a dedicated comparator that
  knows what to ignore — extra machinery for an edge-case check.

Recommendation: **option 3** (exclude in comparator). Preserves diagnostic
data while making any future reproducibility-CI test feasible. Not in scope
to implement now.

---

## Limitations (paths NOT exercised this pass)

These are real gaps in the audit. None of them invalidate the negative result
above — they just narrow its scope:

| Gap | Why it matters | How to close it |
|---|---|---|
| Parallel Surefire forks | `RUN_WRITE_LOCK` is JVM-local (LIM #1 in `phase-a5-degradation-review.md`); two forks could race on `health_snapshot.json` | Set `-DforkCount=2`, rerun pass 2, compare |
| Contributor iteration order | `ConcurrentHashMap.entrySet()` iteration order is not specified. Both tested runs had `totalRaw=0` — no contributors to iterate. A failing-test run would exercise this | Run a fixture with deliberate test failures, then audit |
| Cluster ordering (`semantic.clusters`) | Order in JSON could differ if the underlying `LinkedHashMap` swaps to `HashMap`. Both tested runs had 0 clusters | Run a fixture with JS errors present (e.g. ExploreMenu suite when GHL fails) |
| Cross-OS / cross-JDK HashMap key ordering | Java's randomised hashing seed is per-JVM-instance; coincidentally stable within these two consecutive forks but not guaranteed cross-machine | Run on Linux JDK 21 + on Mac JDK 21, compare snapshot key orders |
| `gitCommit` / branch metadata | Not present in snapshot directly (lives in `history.json` runs[]) — if it ever surfaces in the snapshot, it'll vary per commit | Re-audit if schema changes |

---

## What this audit closes vs leaves open

**Closes:**
- Whether the scoring math itself is deterministic for fixed inputs → **YES, confirmed across two passes**
- Whether iteration order of evidence/URL findings/business outcomes is stable within a JVM → **YES, confirmed via order-stability check in Pass 2**
- Whether the snapshot writer produces stable JSON for stable inputs → **YES, modulo the 4 source categories above**

**Leaves open:**
- Cross-JVM / cross-machine reproducibility — not tested
- Parallel-fork reproducibility — not tested
- Reproducibility when contributors / clusters are non-empty — not tested

**The audit's negative result is bounded by what was tested. It is not a
guarantee for production scenarios that exercise the un-tested paths.** It
*is* a credible baseline because each of the un-tested paths is independent
of what was tested — they would surface their own diffs separately.

---

## Recommendation

1. Accept this characterisation as the E1 baseline. Future scoring code
   changes can re-run the same two-pass audit and compare REAL_NONDETERMINISM
   count — anything > 0 in that bucket is a regression.
2. **Do not** prematurely build the full E1 reproducibility CI gate (the
   one envisioned in `scoring-system-roadmap.md` §4 E1). The gate needs
   the comparator infrastructure (option 3 above) before it's useful.
3. When B-track lands (B2 verifiers, B5 coverage-weighted score, B7 flake
   governance) — re-run this audit. Those changes touch contributor and
   cluster iteration paths that this baseline could not exercise.

---

## Provenance

- Snapshots captured under `automate-workflow-test/` directory
- Pass 1: `HomepageSmokeTest`, default flags (`-DrecordVideo=true`), 1 test class, 6 test methods
- Pass 2: `HomepageExhaustiveTest`, default flags, 1 test class, 1 test method
- JDK: 21.0.10 on Windows 11, single Maven Surefire fork (`forkCount=1`)
- Diff tool: structured tree-walk + byte-position checksum, custom Python script (not committed)
