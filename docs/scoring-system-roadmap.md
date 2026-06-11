# Scoring System Roadmap — Phase B / C / D / E

**Companion to:** `docs/scoring-system.md` (current state, ops reference).
**Status of A-track:** Closed. A.1–A.6 all completed and either runtime-verified or code-verified with documented gaps.
**Audience:** Whoever picks up this work next.

---

## 0. Phase intent — what each phase adds

| Phase | Intent | Why it's needed |
|---|---|---|
| **B** | Business-correctness foundation | A-track made the *scoring math* honest. B-track makes the *thing being scored* honest. Today the system answers "did the harness run?" — B makes it answer "did the user-visible outcome actually happen?" |
| **C** | Scoring refinement | A-track has correctness gates (caps, suppression rules) that are statistically blunt. C replaces them with sample-size-aware math so 1-failure-of-1 doesn't score the same as 1-failure-of-100. |
| **D** | Dashboard parity | Backend now emits ~15 distinct signals (release decision, blockers, semantic clusters, business outcomes, platformHealth, integrity, evidence_collection, amplification, …). Most are still invisible on the rendered HTML. D wires every backend signal to a UI element. |
| **E** | Calibration | Final closure of the project: prove that the same input produces the same output (reproducibility), audit every override that bypassed a gate, and recalibrate weights against ground truth. Until E lands, the scoring system is operationally credible but not formally validated. |

**Sequencing principle:** B/C are mostly independent and can interleave. D depends on B/C being stable enough that the rendered fields stop changing. E is last because it audits everything.

---

## 1. Phase B — Business Correctness Foundation

### B1 — Coverage Catalog + parser ✅ (shipped — see implementation notes)

**What it is:** A declarative catalog (`config/coverage-catalog.json`) listing every business outcome the harness *claims* to test, with metadata about who owns each outcome and what verification covers it.

**Implementation note (deviation from initial plan):** spec said YAML; shipped as
JSON. Reason: every other config file in `config/` already uses JSON
(`complexity_budget.json`, `performance.baselines.json`, etc.) and adding
`snakeyaml` for one file would violate the project's consistent-config-format
rule. Zero behavioral difference; JSON parser was already on the classpath.

**Deliverable (as shipped):**
```json
{
  "schemaVersion": 1,
  "outcomes": [
    {
      "id": "connect.created.spreadsheet-to-gmail",
      "name": "Create Connect — Google Sheets → Gmail draft",
      "owner": "Connect Workflows",
      "severity": "CRITICAL",
      "verifier": "spreadsheetGmailVerifier",
      "test": "testing.CreateConnectWorkflowTest",
      "businessQuestion": "..."
    }
  ]
}
```
Plus `utils.coverage.CoverageCatalog` with:
- `static CoverageCatalog get()` — lazy singleton (mirrors `ComplexityBudget`)
- `Optional<OutcomeSpec> findById(String)`
- `Optional<OutcomeSpec> findByTest(String fqcn)`
- `List<OutcomeSpec> all()`
- `int size()` / `int schemaVersion()`
- `boolean isLoaded()` + `String getLoadError()` for degraded-mode reads
- Nested `OutcomeSpec` record + `severityWeight()` helper for B5

**Dependencies:** None. Was safe to do first; was.

**Risk:** None remaining — already shipped.

**Verification:**
- Compile clean
- `CoverageCatalog.load()` returns non-null on healthy file
- Catches malformed YAML, sets `isLoaded()=false` + readable `getLoadError()`
- A single round-trip unit test (or first invocation log line) proves the file parses

---

### B2 — Outcome Verifier registry + first 3 verifiers

**What it is:** A `Verifier` is a strategy object that decides whether a business outcome *actually happened*, beyond "the test threw no exceptions." Each entry in `coverage-catalog.yaml → verifier` resolves to a Verifier class via a registry.

**Deliverable:**
```java
public interface OutcomeVerifier {
    VerificationResult verify(WebDriver driver, OutcomeSpec spec, RunContext ctx);
}
```

Plus:
- `VerifierRegistry` — `register(String name, OutcomeVerifier)` + `lookup(String name)`
- Three first-cut verifiers:
  1. `spreadsheetGmailVerifier` — verifies a created Connect appears in `/connects` list with status active
  2. `homepageNavigationVerifier` — verifies critical anchors (Pricing, Explore, Login) all resolve to HTTP 200 + correct landing URL
  3. `loginFlowVerifier` — verifies post-login dashboard renders the user's email (proves session is genuine, not just URL match)

**Dependencies:** B1 (registry consumes catalog entries).

**Risk:** Each verifier is small but you accumulate them fast. Limit to 3 initially to validate the pattern; add more in subsequent commits with PR review.

**Verification:**
- Each verifier has at least one happy-path unit test against a recorded DOM (use saved DOMs from `reports/failures/*/dom.html` as fixtures)
- VerifierRegistry's `lookup(missing)` returns null cleanly, not exception
- A single integration test that loads the catalog, picks one outcome, and runs its verifier end-to-end against a known-good page

---

### B3 — TypedAssert + assertion classification

**What it is:** Replace ad-hoc `Assert.assertEquals` / `Assert.fail` with a typed assertion mechanism that classifies the assertion's *nature* into:
- `BUSINESS` — verifies user-visible behavior
- `STRUCTURAL` — verifies DOM/URL state (precondition, not outcome)
- `INVARIANT` — internal consistency check
- `SAFETY` — assertion-as-guardrail (e.g., "must not navigate away mid-test")

**Deliverable:**
```java
public final class TypedAssert {
    public static void business(String outcomeId, Predicate<?> p, ...) {...}
    public static void structural(String desc, boolean cond) {...}
    public static void invariant(String desc, boolean cond) {...}
    public static void safety(String desc, boolean cond) {...}
}
```

Each failure records its classification + outcome ID (for `BUSINESS`) into a new top-level snapshot block `assertions`. Allows the dashboard to answer:
- "How many BUSINESS assertions failed?" (real failures)
- "How many STRUCTURAL assertions failed?" (selector drift, not product bugs)

**Dependencies:** B1 (BUSINESS assertions reference outcome IDs from the catalog).

**Risk:** Migration. There are ~60 `Assert.fail` / `Assert.assertEquals` call sites across the codebase. Don't migrate them all in one commit. Strategy: ship TypedAssert as a new API; migrate the highest-value test (`CreateConnectWorkflowTest`, `HomepageExhaustiveTest`, `ExploreMenuTestBase`) in B3; defer the rest to follow-up commits.

**Verification:**
- Three test classes use TypedAssert and produce the expected `assertions` block in snapshot
- A migration linter (or a manual grep) flags remaining raw `Assert.fail` call sites for future work

---

### B4 — Schema versioning + SchemaMigrator

**What it is:** The `schemaVersion` field is already in the snapshot (currently `3`). B4 makes it *meaningful* — add a `SchemaMigrator` that knows how to upgrade older snapshot files at read time, and freezes the v3 contract.

**Deliverable:**
- `utils.health.semantic.SchemaMigrator`:
  - `JSONObject migrate(JSONObject snapshot)` — detects `schemaVersion`, applies migration steps to reach current
  - Migration steps registered as `(fromVersion, toVersion, Function<JSON, JSON>)` chains
  - For v0/v1/v2 → v3: add missing fields with sensible defaults (e.g., `platformHealth: {}`, `integrity: {status: "UNKNOWN"}`)
- Replace the existing `evidence_collection` and `schema_evolution` DEFERRED stub in `HealthTracker.buildPlatformHealth()` with real signal:
  - `schemaVersion`: int
  - `migrationsApplied`: list of `{from, to}` pairs if a prior snapshot was migrated this run
  - `unknownFieldsSkipped`: count of fields not in the v3 schema (forward-compat signal)

**Dependencies:** None code-side, but conceptually depends on a written schema spec. Suggest adding `docs/snapshot-schema-v3.md` as part of B4.

**Risk:** Migration is forever-forward. Once SchemaMigrator ships, every future schema bump must add a migration step OR be marked "no migration possible — start fresh." Be deliberate about the freeze.

**Verification:**
- Hand-craft a v2 snapshot JSON (without `integrity`, `platformHealth`, etc.) → run through SchemaMigrator → confirm output is structurally valid v3
- `migrationsApplied: [{from:2, to:3}]` appears in the *next* run's snapshot when prior was v2

---

### B5 — Coverage-weighted final score

**What it is:** The current `overallScore` weights every test failure equally (with flow-type multipliers). B5 adds a *coverage* dimension: a CRITICAL outcome failing carries more weight than a SECONDARY outcome failing.

**Formula:**
```
coverageWeight(outcome) = {CRITICAL: 3.0, HIGH: 2.0, MEDIUM: 1.0, LOW: 0.5}
weightedPenalty = Σ (basePenalty * coverageWeight(outcome))
```

The catalog (B1) provides the `severity` field; the verifier (B2) ties test results to outcomes. B5 plugs the two together.

**Deliverable:** Update `HealthPolicy.testFailurePenalty(String context)` to accept an OutcomeSpec (or null for unmapped tests), look up its severity, apply the coverage multiplier. Old multiplier (FlowType) becomes a fallback for unmapped tests.

**Dependencies:** B1 + B2 (need catalog + verifiers).

**Risk:** Scoring changes are *very* high impact. Ship behind a feature flag `-Dcoverage.weighted=true` for the first N runs so trend comparison stays apples-to-apples. Flip the flag default to true only after a calibration run.

**Verification:**
- Compute expected score by hand for a known run, compare to actual
- Trend chart shows a discontinuity at the flag flip — annotate it on the dashboard

---

### B6 — Business-tier release decision with confidence weighting

**What it is:** `RiskInterpreter.decide` currently looks at smoke pass rate, critical bugs, product health, cluster count, regression pass rate, confidence level. B6 adds a *business-tier* check: if any CRITICAL-severity outcome's Verifier returned FAIL, the release is BLOCKED regardless of other signals.

**Deliverable:**
- New `DecisionFactor` codes:
  - `BUSINESS_OUTCOME_CRITICAL_FAILED` — at least one CRITICAL outcome's verifier returned FAIL
  - `BUSINESS_OUTCOME_LOW_CONFIDENCE` — verifier returned PARTIAL or UNKNOWN
- New `DecisionInputs` field: `Map<String, VerificationResult> businessOutcomes`
- New blocker rule in `RiskInterpreter.decide`:
  ```java
  if (in.businessOutcomes.entrySet().stream()
        .anyMatch(e -> e.getValue() == VerificationResult.FAILED
                    && catalog.findById(e.getKey()).severity == CRITICAL)) {
      blockers.add(new DecisionFactor("BUSINESS_OUTCOME_CRITICAL_FAILED", ...));
  }
  ```

**Dependencies:** B1 (catalog), B2 (verifiers).

**Risk:** Decision-code budget — `complexity_budget.json → releaseDecisionCodes` is currently at 9, max 16. B6 adds 2 codes → 11/16. Still under cap but eats into headroom.

**Verification:**
- Inject a known-failing verifier on a CRITICAL outcome → release status = BLOCKED with `BUSINESS_OUTCOME_CRITICAL_FAILED` in blockers
- Inject a PARTIAL on the same → AT_RISK with `BUSINESS_OUTCOME_LOW_CONFIDENCE` in warnings

---

### B8 — KnownDefectClassifier (root-cause failure clustering)

**Discovered:** in the same Cliniko Integrations run. Cliniko + GoHighLevel
Integrations both fail for the same product reason: the "directory" pages
(`/integrate/apps/<app>/integrations` with no `/<pair>` suffix) ship an
incomplete prompt-builder UI. The test correctly diagnoses the gap, but each
failed iteration is recorded independently. Net effect: same defect counted
twice in `testFailures`, with no architectural link surfaced.

**Proposed:** a classifier that recognises known-product-defect patterns and
records them under a separate `audit.knownDefects` snapshot block, with
explicit B7-style expiry metadata to prevent the "known defects graveyard"
failure mode.

**Required metadata per registered defect** (refused if any are missing):
- `id` — stable identifier (e.g. `ProductDirectoryPageIncomplete`)
- `owner` — team name (e.g. `Marketing Frontend`)
- `ticket` — link to tracker entry
- `firstSeen` — date the pattern was first registered
- `lastSeen` — auto-updated by the classifier when matched
- `expires` — review-required-by date; matches expire if not renewed
- `pattern` — predicate the classifier applies to a failed test's context

**Classification model:** failures keep their existing `FailureType` (e.g.
`PRODUCT_BUG`). Classification is metadata, not a type change. A failure can
have both `type: PRODUCT_BUG` AND `knownDefectId: ProductDirectoryPageIncomplete`.
This separation keeps the type taxonomy clean.

**Snapshot output:**
```json
"audit": {
  "knownDefects": {
    "active": [{
      "id": "ProductDirectoryPageIncomplete",
      "owner": "Marketing Frontend",
      "ticket": "https://...",
      "firstSeen": "2026-05-15",
      "lastSeen": "2026-06-03",
      "expires": "2026-07-01",
      "ageDays": 19,
      "affectedThisRun": ["Cliniko Integrations", "GoHighLevel Integrations"]
    }],
    "expired": [{"id": "...", "expiredOn": "..."}]
  }
}
```

**Dashboard surfacing:** new "Known Product Defects" panel. Each entry shows:
defect id, owner, ticket link, age, affected iterations this run. Failures
matching a known-defect pattern are pulled out of the general failure list
into this panel. Expired entries trigger a WARN (and optionally fail the
build under `-DhealthGate.mode=blocking`).

**Anti-graveyard rules:**
- A known-defect entry without all 6 required fields fails compile (annotation processor)
  or runtime registration (whichever level enforces best)
- The dashboard sorts known defects by `ageDays` descending — oldest first
- Expired entries are flagged red, not hidden — the dashboard refuses to let
  the team forget about them
- Default `expires` clamp: 90 days from `firstSeen` — operators must
  consciously extend to keep a defect known

**Dependencies:** none code-side. Conceptually pairs well with C4 (defect-cluster
penalty normalization) but is independent — they solve different layers of the
same architectural problem.

**Effort:** **L** — not the classifier alone (~150 lines) but the surrounding
ecosystem: snapshot schema row, dashboard panel component, trend integration
(age-over-time view), persistence compatibility, scoring integration (do known
defects still penalize? at what rate?), historical migration for existing
PRODUCT_BUG-typed failures. Realistic estimate: 8+ hours plus follow-up
sessions for trend wiring.

**Why this matters:** the current state — N iterations of the same product
gap recorded as N independent failures — is exactly the "tracking symptoms
instead of finding systemic defects" pattern that erodes report credibility.
Was discovered during a real run; not theoretical.

**Critical risk to manage during implementation:** the known-defects bucket
can become a graveyard. The B7-style expiry pattern is the primary mitigation.
If a team starts accumulating known defects faster than fixing them, the
ratio `expired-defects / total-known-defects` becomes a leading indicator of
quality debt — surface it in `platformHealth.governance` alongside the other
self-observability signals.

---

### B7 — Flake Governance with expiry + audit

**What it is:** Today, flaky tests are de-facto handled by re-run heuristics scattered across the codebase. B7 makes flake handling a first-class governance concept:
- Tests can be marked `@FlakeQuarantine` with required expiry date + owner
- The system *tolerates* quarantined tests' failures but *records* them in an `audit` block
- Quarantine entries expire after their date → reverts to normal scoring
- Renewal requires explicit PR (no silent indefinite quarantine)

**Deliverable:**
- `@FlakeQuarantine(reason, ticket, expires="2026-09-01", owner="QA Automation")` annotation
- `FlakeRegistry` reads annotations + emits `audit.flakeQuarantine` JSON block:
  ```json
  "audit": {
    "flakeQuarantine": {
      "active": [{"test": "...", "expires": "2026-09-01", "daysToExpiry": 91, "owner": "..."}],
      "expired": [{"test": "...", "expiredOn": "2026-05-01"}],
      "tolerated": {"PASS": 0, "FAIL": 2, "SKIP": 0}
    }
  }
  ```
- HealthGate respects active quarantines (don't fail the build on quarantined-test failure)
- HealthGate FAILS the build on expired quarantine still in code (PR removal required)

**Dependencies:** None directly, but pairs well with B3 (TypedAssert) — assertions in quarantined tests are still classified, just not scored.

**Risk:** People will use this to mute genuine failures. Mitigate with the expiry-enforced renewal requirement + audit visibility on dashboard.

**Verification:**
- Mark one test `@FlakeQuarantine(expires="2099-01-01")` → its failure doesn't fail the build, appears in `audit.flakeQuarantine.active`
- Mark another `@FlakeQuarantine(expires="2020-01-01")` → build FAILS with "quarantine expired" message
- A test without annotation behaves identically to today (no regression)

---

## 2. Phase C — Scoring Refinement (3 tasks)

### C1 — Diminishing returns instead of hard caps

**Current state:** `HealthPolicy.MAX_TOTAL_PENALTY = 150` is a hard cliff — penalties above 150 are clipped to 150. Same for `TOTAL_JS_PENALTY_CAP = 20`. A test with 50 JS errors scores the same as one with 200.

**Proposed:** Replace hard caps with a curve `f(x) = cap * (1 - e^(-x/cap))` — asymptotic but smooth. A run with 200 JS errors scores worse than one with 50, but the gap closes as count rises.

**Deliverable:** Replace `Math.min(rawPenalty, MAX_TOTAL_PENALTY)` in `HealthTracker.getScore()` with the curve. Same for JS penalty cap.

**Dependencies:** None. Pure math change.

**Risk:** Scoring discontinuity at the change point. Annotate on dashboard trend chart.

**Verification:**
- Unit test the curve at known points (x=0 → 0, x=cap → cap*(1-1/e) ≈ 0.63*cap, x→∞ → cap)
- Trend chart shows a one-time score adjustment at deploy

---

### C2 — Variance + z-score in trendStats block

**What it is:** A new top-level `trendStats` block in the snapshot that computes statistical summaries over the last N runs:
```json
"trendStats": {
  "sampleSize": 30,
  "mean": 87.2,
  "stdDev": 6.8,
  "currentZScore": -1.4,   // current run is 1.4 σ below the mean
  "outlier": false           // |zScore| > 2.0 → outlier
}
```

**Deliverable:**
- `utils.history.trends.TrendStatsCalculator.compute(List<Integer> recentScores, int currentScore)`
- Wire into `HealthTracker.writeSnapshot` as new top-level `trendStats` field
- When `currentZScore < -2.0` → log WARN "current run is a statistical outlier" + add to `release.warnings`

**Dependencies:** None.

**Risk:** Low. Pure statistics over existing data.

**Verification:**
- Hand-calc mean + stdDev for a known recent-runs slice, compare to TrendStatsCalculator output
- After 5 healthy runs + 1 degraded run, the degraded one shows `outlier: true`

---

### C4 — Defect-cluster-level penalty normalization

**Discovered:** during a Cliniko Integrations failure run. Symptom: 1 broken
product page → test iteration retried within the 30s wait loop → 22 locator
fallbacks recorded → 220 penalty points → score 0. The underlying defect count
is 1, not 22, but the model treats each fallback occurrence independently.

**Root cause of the asymmetry:** the scoring system already deduplicates one
penalty source (JS errors) via `ErrorClusterer`, but treats other sources
(fallbacks, slow pages) as per-occurrence. 22 JS error events of one fingerprint
= ~1 cluster penalty. 22 fallback events of one fingerprint = 22 × 10 = 220
penalty. The system is internally inconsistent about whether amplification
should be absorbed.

**Proposed:** apply per-fingerprint diminishing-returns within each contributor
bucket. Fallback events sharing the same `{name, target}` fingerprint accumulate
into one logical "defect," and that defect's penalty follows the C1
diminishing-returns curve. A 22-fallback retry storm becomes ~10 points instead
of 220.

**Deliverable:**
- `ContributorBucket` gains an optional fingerprinting strategy (default: identity,
  so existing buckets behave unchanged)
- `Fallback` and `SlowPage` buckets register fingerprinters
  (`{name, target}` and `{url}` respectively)
- Score computation applies `applyDiminishingReturns(perFingerprint.appliedTotal, perFingerprintCap)`

**Dependencies:** none code-side. **Should validate against historical runs**
the same way C1 was validated: replay 30+ runs, count score changes per band,
verify no ranking inversions.

**Risk:** medium. Behavior change in scoring math affects every future run.
Ship behind `-Dpenalty.clusterNormalize=true` initially (default OFF) per
established C-track pattern.

**Effort:** **L** (revised from initial M estimate). The algorithm itself
is ~150 lines, but the *validation* infrastructure is where most of the work
is. Realistic breakdown:

| Component | LOC | Risk |
|---|---|---|
| Per-bucket fingerprint registration in `ContributorBucket` | ~80 | low |
| Per-fingerprint applyDiminishingReturns call | ~30 | low |
| Wiring in `HealthTracker.getScore` + integrity invariant #6 alignment | ~40 | medium |
| Historical replay validation (replay 30+ runs, count band transitions, prove no rank inversions) | ~200 | high — same shape as C1 validation |
| Before/after score distribution analysis | ~100 | medium |
| Documentation updates (`scoring-system.md` formula section, roadmap entry, ops doc) | ~50 | low |
| Regression check: does C4 interact with C3 sample-size suppression? | ~30 | medium |
| Total | ~530 | — |

The "M" sizing was the algorithm-only view. Real work is ~3x that once
validation is included.

**Anticipated side-effect:** **exposing the scoring weights to scrutiny.**
Once C4 ships and the score-drivers row (item #1 this turn) is visible,
stakeholders will see the actual penalty breakdown — and may ask things like:
*"Why is one fallback worth 10× a JS error?"* Those questions might not be
*wrong*. The current weight ratios (TEST_FAILURE=15, FALLBACK=5, JS_ERROR=1)
were calibrated against an earlier scoring model, not the post-C4 one. C4
implementation should anticipate a weights-recalibration conversation as a
follow-up — possibly the E4 weight calibration task earlier than the
30-stable-runs threshold currently requires.

**Why this matters:** without it, the scoring model can declare "score 0"
from a single broken page that triggers a retry storm. Stakeholders read
this as "1 failure → catastrophic" and lose trust in the scoring model.
Was discovered during a real run; not theoretical.

---

### C3 — Sample-size aware JS error repeat suppression

**Current state:** JS errors are deduplicated to clusters (ErrorClusterer). Each cluster's first 3 LOW-severity occurrences count for 1 penalty point each; everything beyond is suppressed.

**Problem:** Sample size matters. 3 LOW-severity occurrences in 1 test ≠ 3 in 100 tests. The current rule treats them identically.

**Proposed:** Adjust suppression by sample size:
```
allowedCount = max(3, ceil(totalTestCount * 0.1))
```
So in a 10-test run, 3 LOW occurrences are allowed before suppression. In a 100-test run, 10 are allowed. Suppression kicks in at proportionally meaningful counts.

**Deliverable:** Update `LayeredHealthScores.compute()` LOW-cluster handling + `ErrorClusterer` documentation.

**Dependencies:** None.

**Risk:** Behavior change. Same scoring-discontinuity risk as C1.

**Verification:**
- Run with 10 tests + 5 LOW errors → suppression at 4th occurrence (matches existing behavior approximately)
- Run with 100 tests + 5 LOW errors → no suppression (under new threshold of 10)

---

## 3. Phase D — Dashboard Parity (D1–D4)

**Intent:** The backend now emits ~15 distinct signal blocks. The dashboard renders maybe 8. D closes the gap.

**Inventory of unrendered (or under-rendered) backend signals:**

| Signal block | Backend status | Dashboard status | Phase |
|---|---|---|---|
| `release.blockers` / `release.warnings` codes | A4 emitted | Rendered (text translation) | ✅ |
| `enforcement` | A.5.1 emitted | Hidden | **D1** |
| `platformHealth.snapshot_pipeline` | A.5.3 emitted | Hidden | **D1** |
| `platformHealth.governance` | A.5.3 emitted | Hidden | **D1** |
| `platformHealth.evidence_collection` | A.6.1 emitted | Hidden | **D2** |
| `platformHealth.schema_evolution` | A.5.3 emitted (stub) | Hidden | **D1** (later B4) |
| `integrity` | A.5.4 emitted | Hidden | **D2** |
| `semantic.amplification` | A.6.2 emitted | Hidden | **D2** |
| `assertions` | B3 will emit | Hidden | **D3** |
| `audit.flakeQuarantine` | B7 will emit | Hidden | **D4** |
| `trendStats` | C2 will emit | Hidden | **D3** |

### D1 — Platform self-observability panel

**What it is:** New dashboard section "Platform Health" rendering `platformHealth` + `enforcement`. Three sub-panels (snapshot_pipeline / governance / schema_evolution).

**Trigger:** Any non-PASS signal (write errors > 0, complexityBudgetLoaded=false, etc.) lights up the panel border red/amber.

**Deliverable:** New `utils.dashboard.components.PlatformHealthComponent` (follows the SemanticPanelComponent pattern). Wire into `DashboardBuilder.buildHtml` template.

### D2 — Integrity + evidence + amplification panel

**What it is:** New dashboard section "Data Quality" rendering `integrity` + `evidence_collection` + `semantic.amplification`. The three signals all answer "can I trust this run's numbers?" so they belong together.

**Deliverable:** New `utils.dashboard.components.DataQualityComponent`.

### D3 — Assertion classification + trend statistics panel

**What it is:** Render `assertions` (B3) + `trendStats` (C2). Assertions broken down by classification, trend showing z-score and outlier flag.

**Deliverable:** Two new components (one per signal) wired into a single section.

### D4 — Audit + flake governance panel

**What it is:** Render `audit.flakeQuarantine`. Active quarantines listed with days-to-expiry; expired ones highlighted red as PR-action-required.

**Deliverable:** New `utils.dashboard.components.AuditComponent`.

**Cross-cutting D risk:** The dashboard HTML is already large (~2900 lines). Adding 4 more sections increases page weight. Mitigate with the collapsible-toggle pattern already established for the Recordings section (see existing `recordings-section collapsed` work) — each new panel ships collapsed by default.

---

## 4. Phase E — Calibration (E1–E4)

### E1 — Reproducibility check

**What it is:** A new build target `mvn verify -Pcalibrate-reproducibility` that:
1. Runs the suite twice with identical inputs (same env, same time)
2. Diffs the two snapshots
3. FAILS if any non-timestamp / non-duration field differs

**Goal:** Prove the scoring is deterministic. If E1 fails, there's hidden state somewhere we haven't captured.

**Risk:** Initial run will almost certainly fail — random ordering, network jitter, test-data interactions. Each diff is a separate bug to fix. Budget E1 as a multi-iteration effort, not a single fix.

### E2 — Override audit

**What it is:** A new top-level `audit.overrides` block listing every gate that was *skipped* this run via a system property:
- `healthGate.disabled=true` → kill switch overrides
- `recordVideo=false` → recording bypass
- `coverage.weighted=false` → scoring feature flag off
- etc.

**Goal:** "Who silenced what?" must be auditable. An operator running with `-DhealthGate.disabled=true` should see that prominently in the snapshot — and the dashboard should call it out.

### E3 — Override audit dashboard panel

**What it is:** Render `audit.overrides` on dashboard. Each active override gets a red banner at the top of the dashboard with the property name + a "this run is not gate-protected" warning.

### E4 — Weight calibration

**What it is:** Compare current scoring weights against ground truth (hand-labeled "this build SHOULD have shipped / SHOULD NOT have shipped" decisions across the last N runs). Adjust weights to maximize agreement with ground truth.

**Mechanics:**
- A new file `config/calibration-ground-truth.csv` with hand-labels for past runs
- A script `tools/calibrate-weights.py` that reads history + ground truth, runs a grid search over weight space, emits suggested adjustments
- Apply adjustments via PR, run calibration again, iterate until agreement stabilizes

**Risk:** Requires human labor (the ground-truth labeling). Don't start E4 until ≥30 runs of stable trend data exist post-B5 + post-C1.

---

## 5. Suggested execution order

```
Now ──────────────────────────────────────────────────────────► Later

┌─ B1 ─┬─ B2 ─┬─ B3 ──────────┐
│      │      │               │
│      │      └─ B5 ─ B6 ──┐  │
│      │                   │  │
│      └─ B7 ───────────┐  │  │
│                       │  │  │
└─ B4 ─ (independent) ──┤  │  │
                        │  │  │
            ┌─ C1 ──────┤  │  │
            │           │  │  │
            ├─ C2 ──────┤  │  │
            │           │  │  │
            └─ C3 ──────┘  │  │
                           │  │
        ┌──────────────────┴──┴── D1 ─ D2 ─ D3 ─ D4
        │
        └─ E1 (reproducibility) ─ E2 ─ E3 ─ E4
```

**Critical-path recommendation:**
1. **B1 + B4** first — both are independent foundations; B1 unlocks the rest of B, B4 unblocks long-term schema evolution.
2. **B2 + B3** in parallel — they share no code but B2 produces the data B5 needs.
3. **C1 + C2 + C3** can interleave at any time; they're isolated math changes.
4. **B5 + B6** after B1/B2 land — these are the consumers.
5. **B7** standalone — schedule when there's appetite for the audit work.
6. **D1 + D2** can start *in parallel* with C-track — render the new panels behind a dashboard feature flag (`-Ddashboard.experimental=true`) marked "EXPERIMENTAL — backend signal may still change." Once the corresponding B/C task stabilizes, drop the flag and remove the warning. (This was originally "wait for 5 stable runs" — too conservative; UI feedback should not be gated on backend stability.)
7. **D3 + D4** after B3/B7 land respectively.
8. **E1** as soon as B5 + C1 are stable (those are the biggest scoring deltas).
9. **E2 + E3** any time after D-track scaffold exists.
10. **E4** only after ≥30 stable runs post-B5 + C1.

**Anti-pattern:** Don't ship B5 before B1/B2 — coverage-weighted scoring without a coverage catalog is just a renamed multiplier with no semantics.

---

## 6. Cross-cutting risks

| Risk | Mitigation |
|---|---|
| **Scoring discontinuity at every change** | Annotate trend chart with deploy markers; document each change in `docs/scoring-system.md` §9 (replace "known limitations" with "version history"); never change multiple scoring rules in one commit. |
| **Complexity-budget creep** | Every new field added to a snapshot block respects the limits in `config/complexity_budget.json`. PR template requires "does this addition fit under existing caps? if not, what's being removed?" |
| **Dashboard becoming unreadable** | Continue the collapsible-toggle pattern (the Recordings section already established it). Each new D-track panel ships collapsed by default. Add an "Expand all" / "Collapse all" master toggle in D-track. |
| **Test-coverage migration paralysis** | Don't gate B5 (coverage-weighted score) on 100% of tests being in the catalog. Provide a default-severity fallback (MEDIUM) for unmapped tests; iterate. |
| **Ground-truth labeling labor (E4)** | Sample 30 runs; don't try to label every historical run. Iterate weights to convergence; lock the calibration; move on. **Quality precondition** (revisit at E4 entry): the 30 sampled runs must be (a) drawn from the production master branch, (b) representative of typical PR / nightly mix, (c) each independently spot-checked for "would a senior engineer agree with the ground-truth label here?" Thirty bad samples are still bad data. |
| **Ownership unassigned** | Each phase needs a *task owner* (who builds it) and a *signoff owner* (who decides done). Ownership table to be filled by stakeholder before B1 ships — placeholder below. |

### Ownership placeholder (to be filled before B1 ships)

| Surface | Task owner | Signoff owner |
|---|---|---|
| Scoring math (B5, C1, C3) | _TBD_ | _TBD_ |
| Catalog + verifiers (B1, B2) | _TBD_ | _TBD_ |
| Assertion machinery (B3) | _TBD_ | _TBD_ |
| Schema migration (B4) | _TBD_ | _TBD_ |
| Flake governance (B7) | _TBD_ | _TBD_ |
| Dashboard rendering (D1–D4) | _TBD_ | _TBD_ |
| Calibration (E1–E4) | _TBD_ | _TBD_ |

---

## 7. Done definition per phase

**B done when:**
- All 7 tasks merged
- A "vertical slice" works end-to-end: catalog → verifier → typed assert → coverage-weighted score → business-tier release decision → flake quarantine
- The Spreadsheet→Gmail connect workflow has all 6 dimensions wired (catalog entry, verifier, typed asserts, schema-versioned snapshot, coverage-weighted score, release decision contribution)

**C done when:**
- All 3 math changes deployed
- Trend chart's variance band visualizes std-dev shading around the mean
- At least one run has been classified as an outlier by C2 and surfaced in the warnings list

**D done when:**
- Every backend-emitted top-level snapshot field has a visible dashboard counterpart (or is documented as "internal-only")
- A new dashboard component takes ≤ 1 day of work to add (the pattern is mature)
- Page-weight budget: total dashboard.html ≤ 500 KB; if exceeded, lazy-load the collapsed panels

**E done when:**
- E1 reproducibility check is green on master
- E2 audit emits the override list for every run
- E3 makes overrides visible at the top of the dashboard
- E4 calibration round complete with agreement-rate documented in `docs/scoring-system.md`
- A snapshot taken any time produces the same release decision as a re-run with identical inputs

---

## 8. Suggested kick-off order for the next session

If picking this up cold, the smallest unblocking step is **B1**. It's:
- One file (`config/coverage-catalog.yaml`) + one parser class
- No dependencies on any pending work
- Mirrors a pattern already established (`ComplexityBudget`)
- ~2 hours of work, compile-verified, runtime-verified by first invocation log line
- Unblocks B2 / B5 / B6 immediately

Second-smallest is **C1** (one-line math change in `HealthTracker.getScore`) — no dependencies, instant value, but creates a trend discontinuity that has to be annotated.

If aiming for biggest impact per hour, **D1** is the highest-leverage — most of the A-track work is invisible on the dashboard today, and D1 surfaces ~6 currently-hidden signals in one panel.

---

## 9. Success metrics — how we measure phase completion

Done-definition (§7) tells you what's *built*. This section tells you what
*improved*. Each metric is observable from existing snapshot data unless
otherwise noted.

| Phase | Metric | Target | Measurement source |
|---|---|---|---|
| **B**  | Release-decision agreement rate (decision vs operator override over 30 runs) | ≥ 85 % | `enforcement.wouldHaveBlocked` vs historical override audit (E2) |
| **B**  | Fraction of test methods with a coverage-catalog entry | ≥ 60 % after B1+B2, ≥ 90 % after migration window | `assertions` block + catalog cross-ref |
| **C1** | Per-cluster penalty variance over consecutive runs of an unchanged build | reduced ≥ 30 % vs current hard-cap behaviour | `scoreContributors.*.items[].applied` deltas |
| **C2** | Outlier flagging precision (outliers raised by C2 that operators agreed were real) | ≥ 80 % | `trendStats.outlier` vs operator-tagged retrospective |
| **D**  | Mean time to triage (operator opens dashboard → identifies offending domain) | reduced ≥ 50 % vs current dashboard | one-off measurement before/after; not a continuous metric |
| **D**  | Backend-emitted signals with visible dashboard render | 100 % of top-level fields in v3 schema | manual audit; D-track exit criterion |
| **E1** | Reproducibility check pass rate on master | ≥ 95 % of consecutive paired runs | `reports/calibration/reproducibility.csv` (new) |
| **E4** | Decision-agreement uplift from weight calibration | ≥ 5 percentage points vs pre-calibration baseline | `audit.calibration` block (new under E2/E4) |

**Failure mode:** any metric tracked here that *worsens* from baseline after a
change should trigger the rollback criteria in §11.

---

## 10. Rollback criteria — when to flip a flag off

Every B/C feature ships behind a `-D` flag. Each flag has a quantitative trigger
for disabling it, captured at flag-flip time so the operator doesn't have to
relitigate the decision.

| Feature | Flag | Disable when |
|---|---|---|
| Coverage-weighted score | `-Dcoverage.weighted=true` | Score rankings of historical runs shift by **> 25 %** when re-scored with the flag on (apples-to-apples comparison must not invert any past PASS/FAIL decision). |
| Diminishing-returns curve | `-Dpenalty.curveCap=true` | Any individual cluster's applied penalty shifts by **> 40 %** vs the hard-cap baseline on a known-stable historical run. |
| Sample-size-aware JS suppression | `-Djs.suppression.sampleSizeAware=true` | Total suppressed-cluster count drops by **> 50 %** on a known-baseline 50-test run (suggests the threshold formula is admitting noise). |
| Business-tier blocker | `-Dbusiness.tier.gate=true` | The new decision-code (`BUSINESS_OUTCOME_CRITICAL_FAILED`) fires on **≥ 2 historically-PASS runs** in a row (false-positive rate too high — verifier needs work, not the gate). |
| Statistical-outlier warning | `-DtrendStats.outlierWarnings=true` | `outlier:true` raised on **> 30 % of runs** in a 20-run window (the cutoff is too tight; widen the z-score threshold rather than disabling). |
| Coverage-catalog enforcement | `-Dcoverage.catalogRequired=true` | Build fails on a healthy team-owned test because the catalog entry hasn't landed yet (always grant catalog-migration grace by reverting the enforcement flag, never by editing the catalog under pressure). |

**Standing rule:** rollback is via flag flip, never by reverting a commit on
master. Reverts are reserved for the rare case where the *code* is wrong, not
where the *math change is correct but business-disruptive*.

---

## 11. Effort sizing (rough T-shirt)

Used only for sequencing — not commitments. Sizes assume the implementer has read
`docs/scoring-system.md` + the A-track code that established the pattern they're
mirroring (e.g. `ComplexityBudget` is the template for `CoverageCatalog`).

| Task | Size | Pattern to mirror |
|---|---|---|
| B1 Coverage catalog YAML + parser | **S** | `utils.governance.ComplexityBudget` |
| B2 Verifier registry + 3 verifiers | **M** | `OutcomeRouter` pattern; verifiers are leaf strategies |
| B3 TypedAssert + 3-class migration | **M** | New API; migration is the bulk of the work |
| B4 Schema versioning + SchemaMigrator | **M** | Migration step chain (see Liquibase / Flyway conceptually) |
| B5 Coverage-weighted score | **S** | One math change in `HealthPolicy` + flag wiring |
| B6 Business-tier blocker decision | **S** | Two new `DecisionFactor` codes; rule already established in `RiskInterpreter.decide` |
| B7 Flake quarantine + audit | **L** | New annotation + registry + dashboard panel; touches many files |
| C1 Diminishing-returns curve | **S** | One formula change in `getScore` |
| C2 trendStats block | **M** | New calculator + top-level snapshot field + dashboard slot |
| C3 Sample-size-aware suppression | **S** | One formula change in `LayeredHealthScores.compute` |
| D1 Platform-health panel | **M** | `SemanticPanelComponent` template; ~250 lines |
| D2 Data-quality panel | **M** | Same template as D1 |
| D3 Assertions + trendStats panel | **M** | Two components in one section |
| D4 Audit + flake-governance panel | **M** | Same template; needs the B7 emission first |
| E1 Reproducibility check | **L** | Build target + diff harness + iterating on non-determinism — historically grows |
| E2 Override audit block | **S** | One emission method, mirrors `enforcement` |
| E3 Override banner on dashboard | **S** | Inline banner; small CSS + JS |
| E4 Weight calibration | **XL** | Multi-week iteration; needs ground-truth corpus first |

**Sequencing heuristic:** ship all **S** tasks first (they're cheap, low-risk,
unlock downstream work). **L/XL** tasks need explicit scheduling and reviewer
availability.

---

## 12. Out of scope for this roadmap

These were considered but deferred — flag for future planning:

- **F-track: Production deployment & alerting.** When the scoring is wired into CI/CD as a release gate, who gets paged on a BLOCKED build? What's the SLA for fixing a BLOCKED gate?
- **G-track: Multi-suite harmonization.** Smoke vs Sanity vs Regression vs Full currently each have their own thresholds. G would unify the threshold logic so a smoke-only run and a full-suite run produce comparable scores.
- **Migration of legacy `Assert.fail` call sites.** B3 ships the new API but doesn't migrate ~60 existing call sites. That's its own follow-up commit-train.
- **Performance budget.** No phase here addresses dashboard render time or snapshot write time. Worth measuring once D-track is complete.

These are real follow-ups but not part of B/C/D/E.
