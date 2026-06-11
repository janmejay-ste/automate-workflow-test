# Scoring Weights — Rationale and Calibration Baseline

## Why this document exists

The health-score formula in `HealthPolicy.java` applies penalty weights to
five contributor sources (test failures, JS errors, fallbacks, slow pages,
warnings) plus flow-type multipliers (CRITICAL × 2.0, CORE × 1.5,
SECONDARY × 1.0). Those numbers are codified but their **justification**
has lived in commit messages, PR comments, and verbal context.

Without a written baseline, future calibration becomes "tweak until it
looks right." With it, every proposed change is measured against a
documented reason.

This doc is the **pre-requisite** for E4 (weight calibration). It does
NOT propose new weights. It records the current state and the conditions
that would justify changing each one.

## Current weights (snapshot of `HealthPolicy.java`)

All values from `HealthPolicy.java` as of the current commit. Source of
truth is the code — if this doc and the code diverge, update the doc.

### Base penalties

| Source | Constant | Value | Per-event meaning |
|---|---|---:|---|
| Test failure | `BASE_TEST_FAILURE` | **15.0** | One failed test method |
| Fallback | `BASE_FALLBACK` | **5.0** | One locator-engine fallback retry event |
| JS error (unique) | `BASE_JS_ERROR` | **1.0** | One unique JS error event |
| Warning | `BASE_WARNING` | **0.5** | One warning-level signal |
| Slow page (≥20s) | `slowPagePenalty` tier 1 | **6.0** | Page load took 20+ seconds |
| Slow page (≥10s) | `slowPagePenalty` tier 2 | **3.0** | Page load took 10–20 seconds |
| Slow page (≥5s) | `slowPagePenalty` tier 3 | **1.0** | Page load took 5–10 seconds |

### Source-level caps

| Source | Constant | Value | Meaning |
|---|---|---:|---|
| JS error total | `TOTAL_JS_PENALTY_CAP` | **20.0** | Max contribution from JS errors per run |
| Warning total | `WARNING_CAP` | **10.0** | Max contribution from warnings per run |
| Aggregate penalty | `MAX_TOTAL_PENALTY` | **150.0** | Hard cap on rawPenalty before score calc |

### Flow multipliers

| Flow | Multiplier | Triggered by context substring |
|---|---:|---|
| `CRITICAL` | **2.0×** | "smoke", "login", "signup" |
| `CORE` | **1.5×** | "sanity", "homepage", "navigation" |
| `SECONDARY` | **1.0×** | default (any context not matching above) |

### Threshold bands

| Status | Min score | Constant |
|---|---:|---|
| EXCELLENT | 90 | `EXCELLENT_MIN` |
| HEALTHY | 75 | `HEALTHY_MIN` |
| WARNING | 50 | `WARNING_MIN` |
| POOR | 30 | `POOR_MIN` |
| CRITICAL | < 30 | (implicit) |

### Smoothing

| Constant | Value | Meaning |
|---|---:|---|
| `EMA_ALPHA` | **0.3** | Weight of current run vs previous smoothed score |

## Per-source rationale

### Test failure → 15.0

**Why this weight, not 5 or 50.** A failing test method is the strongest
single signal available: it's deterministic (TestNG decided FAIL), it
crosses a guard rail (the engineer wrote an assertion), and it usually
maps to one product or framework defect.

At 15 base × 2.0 CRITICAL multiplier, one critical-suite failure costs
30 points — drops a perfect 100 to a 70 (HEALTHY band, near WARNING).
That feels right: one critical smoke failure should put a release in
"review" territory but shouldn't auto-block on its own.

**Conditions that would justify changing it:**
- If `MAX_TOTAL_PENALTY=150` is consistently saturated by test failures
  alone (i.e. 10+ critical failures hit the floor before other signals
  contribute), the weight may be too high.
- If a single critical failure consistently fails to move the score out
  of EXCELLENT (90+), the weight is too low.

### Fallback → 5.0

**Why this weight, not 1 or 15.** A locator-engine fallback is weaker
than a test failure (the test still passed) but stronger than a JS error
(it indicates a real selector drift). The 5.0 chosen sits midway.

At 5.0 × 2.0 = 10 per critical-flow fallback, a flow with 3 fallbacks
contributes 30 points. The C4 replay surfaced a structural issue here:
multiple amplified fallback clusters today get aggregated and capped at
the source-level cap, hiding the multiplicity. Policy A in C4 surfaces
this. See `c4-replay-fixture.md`.

**Conditions that would justify changing it:**
- If fallback amplification is the dominant cause of penalty inflation
  (C4 replay will say) AND we deliberately want to dampen it, lower the
  base.
- If post-C4 the fallback bucket consistently contributes < 5 points
  per run, the weight could be raised — fallbacks should not be "free."

### JS error → 1.0

**Why this weight, not 0.5 or 5.** JS errors fire on any client-side
script issue and are noisier than test failures. Many JS errors come
from third-party scripts unrelated to the test surface. At 1.0 per
unique event with a 20.0 hard cap, even a JS-error storm contributes at
most 20 points — a single full band tier — to the score.

C3 added sample-size-aware suppression so a flaky cluster on a small
sample doesn't dominate. C4 adds cluster-level normalization
(eventually). Together they prevent JS noise from drowning out signal.

**Conditions that would justify changing it:**
- If `TOTAL_JS_PENALTY_CAP=20` is hit by < 10% of failing runs, the cap
  isn't binding and the weight could rise.
- If real JS regressions are routinely lost in noise even after
  clustering, the weight is too low or the clustering threshold needs
  tightening — but the fix is more likely the cluster boundary, not
  the weight.

### Warning → 0.5 (capped at 10.0)

**Why this weight, not 0 or 1.0.** Warnings are the weakest first-class
signal. They exist to record "something noteworthy" without claiming
"something failing." 0.5 keeps them visible-but-low.

The 10.0 source-level cap prevents a warning-spam suite from costing
more than two band tiers. This is intentional — warnings should
influence the score only as a tiebreaker between a "healthy" and "near
healthy" run.

**Conditions that would justify changing it:**
- If two runs in the EXCELLENT band consistently differ by their warning
  count and operators say "but one is materially worse," the weight is
  too low or warnings are wrongly classified.

### Slow page — 1.0 / 3.0 / 6.0 tiered by load time

**Why tiered, not linear.** Page-load time has a non-linear relationship
with user-perceived "broken-ness." A 5-second page is annoying; a
20-second page is broken. The tiers reflect that:

| Load time | Tier | Per-event penalty (× SECONDARY multiplier) |
|---:|---|---:|
| < 5s   | none | 0.0 |
| 5–10s  | mild | 1.0 |
| 10–20s | bad  | 3.0 |
| ≥ 20s  | broken | 6.0 |

**Conditions that would justify changing it:**
- If the SLA for the product specifies different thresholds (e.g. 3s
  becomes the "broken" line), the tier boundaries must move.
- If slow pages are routinely flagged but the source contributes < 2
  points per run, the per-event weight is too low.

## Flow multipliers — 2.0 / 1.5 / 1.0

**Why multiply, not add.** Multiplying preserves the relative weight
between sources (a fallback is still 1/3 the weight of a failure within
a single flow type) while elevating the absolute floor for critical
paths. Additive bonuses break that ratio.

**Why 2.0 for CRITICAL, not 3.0 or 5.0.** A critical-suite failure
already costs 30 points (15 × 2.0). 3.0 would push a single failure to
45 points — pulling EXCELLENT → POOR in one event. That's too binary.
2.0 lands in the "WARNING band on first failure, AT_RISK on second"
shape, which gives the release reviewer time to investigate.

**Conditions that would justify changing it:**
- If a single critical failure routinely fails to surface in dashboards
  (passes triage), the multiplier is too low.
- If a single critical failure consistently triggers BLOCKED outcomes
  before any other signal contributes, the multiplier is too high.

## Threshold band rationale

| Band | Range | Implied operator action |
|---|---|---|
| EXCELLENT | 90–100 | Ship without review |
| HEALTHY   | 75–89  | Ship; flag for next-cycle review |
| WARNING   | 50–74  | Investigate before ship |
| POOR      | 30–49  | Hold; root-cause required |
| CRITICAL  | < 30   | Do not ship |

The 25-point band width is deliberate: it matches the maximum penalty
from one critical-suite failure (30 pts) so a single failure can move
the score by exactly one band — never two. If band widths were 10 or 15,
a single critical failure would skip past WARNING into POOR, which is
too discontinuous.

**Conditions that would justify changing the bands:**
- If real runs cluster at the boundaries (e.g. dozens of runs at score
  74 just below HEALTHY), the boundary is mis-placed and operators are
  judging by score not band.
- If runs in the same band routinely disagree on "ship or not," the
  band is too wide.

## Smoothing — EMA_ALPHA = 0.3

A 0.3 EMA gives the current run 30% weight, the previous smoothed score
70%. Three runs of bad scores fully replace one good run; one bad run
moves the smoothed score by ~30% of the gap.

**Why 0.3, not 0.1 or 0.5.** 0.1 would make a single bad run nearly
invisible (smoothed barely budges). 0.5 makes smoothing pointless (the
smoothed score is just a moving average of the last two runs). 0.3 is
the band where one outlier is visible without overpowering.

**Conditions that would justify changing it:**
- If the smoothed score consistently lags real degradation by 3+ runs,
  alpha is too low.
- If the smoothed score is noisy enough that operators ignore it in
  favor of the raw score, alpha is too high — smoothing isn't doing its
  job.

## What change can break — invariants to preserve

When E4 weight calibration runs, these invariants must hold:

1. **Trend continuity.** Old scores stay reproducible. Any weight change
   ships behind a feature flag (default OFF) like C1's `penalty.curveCap`.
   The dashboard trend chart must annotate the flip date.

2. **No single source can dominate.** No source should regularly
   saturate `MAX_TOTAL_PENALTY=150` alone. If JS errors alone hit 150,
   the cap isn't doing its job.

3. **Critical multiplier preserved.** Smoke / login / signup failures
   must remain the strongest signal. Any change that puts non-critical
   failures above critical failures violates the model.

4. **Bands stay 25-wide.** Reducing band width would make single events
   skip bands; widening would make bands less actionable.

5. **Replay data exists before changes.** Per the C4 evidence-collection
   discipline — calibration changes against synthetic data risk
   optimizing for fixtures rather than reality. Real noisy-suite data
   first.

## Reference

- `src/test/java/utils/HealthPolicy.java` — source of truth for all
  constants in this document.
- `docs/scoring-system.md` — operational maturity, contributor decision
  flow, dormancy review procedure.
- `docs/c4-data-collection.md` — data-collection prerequisites for any
  weight-altering change.
- `docs/scoring-system-roadmap.md` — C/D/E-track work breakdown.
- `docs/evidence-first-analysis.md` — Rule 3 ("run the model before
  quantitative claims") makes this doc the obligatory checkpoint for
  any proposed score-impacting change. Don't propose a score delta
  without computing it through the constants here.

## When to revisit

After E4 calibration completes, this doc should be updated with:
- The proposed new weights (in a "Proposed" column alongside "Current").
- A summary of the replay data that justified each change.
- A rollback plan tied to the feature flag.

Until E4 runs, this doc represents the **as-of baseline**, not a target.
