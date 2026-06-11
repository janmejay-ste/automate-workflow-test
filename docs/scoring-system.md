# Scoring System — Ops Doc

**Audience**: anyone reading a dashboard, running a build, or triaging a red run.
**Source of truth**: the Java classes named in each section. This doc summarises behaviour; if the doc and the code disagree, the code wins — open a PR against this file.

---

## 1. What the score is

One number, 0–100, emitted at the end of every suite run. Stored as `overallScore` in `reports/analytics/test_analytics.json` and used downstream by:

- `reports/trend/dashboard.html` — the visible artifact
- `reports/trend/health_snapshot.json` — the machine-readable state
- The build gate (optional, off by default)

### Formula

```
effectivePenalty = HealthPolicy.applyDiminishingReturns(rawPenalty, MAX_TOTAL_PENALTY)
rawScore         = max(0, 100 − effectivePenalty)
smoothedScore    = EMA_ALPHA * rawScore + (1 − EMA_ALPHA) * previousSmoothedScore
                   // EMA_ALPHA = 0.3 — current run is 30% of the score
```

Source: `utils.HealthPolicy`, `utils.health.HealthTracker.getSmoothedScore()`.

`applyDiminishingReturns` has two behaviours selected by the C1 feature flag
`-Dpenalty.curveCap` (default `false`):

- **OFF** (legacy): `effectivePenalty = min(rawPenalty, 150)` — hard cap
- **ON**: `effectivePenalty = 150 * (1 − exp(−rawPenalty / 150))` — asymptotic
  curve. Differentiates penalties above ~90 that previously collapsed to
  score=0. See `docs/scoring-system-roadmap.md` §1 C1 + validation evidence
  for the 45-run replay showing 0 ranking inversions.

`previousSmoothedScore` is read from the prior `health_snapshot.json`. If that
file is missing or malformed, `smoothedScore == rawScore` for this run. The
source of the read (SNAPSHOT / CSV / MALFORMED / NO_DATA) is now surfaced in
`platformHealth.snapshot_pipeline.previousScoreSource` — see §9 LIM #3 closure.

### Bands

| Score | Band | Status (text) |
|---|---|---|
| 90–100 | EXCELLENT | Release Allowed |
| 75–89 | HEALTHY | Release Allowed |
| 50–74 | WARNING | Review Recommended |
| 30–49 | POOR | Decision Required |
| 0–29 | CRITICAL | typically BLOCKED |

Bands come from `HealthPolicy.EXCELLENT_MIN / HEALTHY_MIN / WARNING_MIN / POOR_MIN`. The score → release-status mapping is **not** a pure function of the band — see §3.

### Penalties (raw contributors)

| Signal | Base penalty | Multiplier (flow type) | Cap |
|---|---|---|---|
| Test failure | 15 | CRITICAL ×2 / CORE ×1.5 / SECONDARY ×1 | — |
| JS error (unique) | 1 | same | 20 (total JS budget) |
| Locator fallback | 5 | same | — |
| Warning | 0.5 | same | 10 |
| Slow page ≥20s | 6 | same | — |
| Slow page ≥10s | 3 | same | — |
| Slow page ≥5s | 1 | same | — |

Flow type is inferred from test context — see `HealthPolicy.flowTypeOf()`. "smoke", "login", "signup" → CRITICAL; "sanity", "homepage", "navigation" → CORE; else SECONDARY.

---

## 2. The four layered scores

The single `overallScore` collapses every signal into one penalty pool — useful as a headline, lossy for diagnosis. The dashboard also renders four independent domain scores:

| Layer | Source | What it measures |
|---|---|---|
| **Product Health** | `LayeredHealthScores.productHealth` | App-side defects — auth, editor, frontend JS errors, PRODUCT/INFRASTRUCTURE clusters |
| **Framework Health** | `LayeredHealthScores.frameworkHealth` | Automation defects — selectors, retries, WebDriver failures, FRAMEWORK clusters |
| **Telemetry Confidence** | `LayeredHealthScores.telemetryConfidence` | Observability — UNKNOWN-timing share, missing measurements (cap −50) |
| **Business Outcome** | `LayeredHealthScores.businessOutcome` | User-intent — workflows/integrations that actually completed (S×100 + P×50) / scorable, minus PRODUCT/INFRA cluster contamination (cap −30) |

Each layer is 0–100, with the same bands as the overall score. Business Outcome can be `NO DATA` (renders as "—") when no business transactions are recorded.

**Cross-contamination rule (Business Outcome):** PRODUCT/INFRASTRUCTURE CRITICAL clusters subtract 5 each, HIGH subtract 3 each, capped at −30. Rationale: 100% test pass rate on an app emitting JS errors is not the same as 100% on a clean app.

Source: `utils.health.semantic.LayeredHealthScores.compute()`.

---

## 3. Release decisions

The dashboard's release status is **not** derived from the score alone. It comes from `RiskInterpreter.decide(DecisionInputs)` which produces a `ReleaseDecision` with structured blocker/warning factors.

### Decision codes

| Code | Layer | Severity | Trigger |
|---|---|---|---|
| `INSUFFICIENT_DATA` | Confidence | BLOCKER | confidence level == NONE (sample size too small) |
| `SMOKE_FAIL` | Smoke | BLOCKER | smoke pass rate < 90% |
| `CRITICAL_BUGS_OPEN` | Product | BLOCKER | criticalBugs > 0 |
| `PRODUCT_HEALTH_CRITICAL` | Product | BLOCKER | productHealth < 20 |
| `CRITICAL_CLUSTER_LIMIT` | Semantic | BLOCKER | ≥3 CRITICAL clusters |
| `SCORE_BELOW_POOR_MIN` | Score | WARNING | smoothedScore < 30 |
| `SCORE_BELOW_HEALTHY_MIN` | Score | WARNING | smoothedScore < 75 |
| `REGRESSION_PASS_RATE_LOW` | Regression | WARNING | regression pass < 100% **AND** N ≥ 5 (sample-size-aware) |
| `LOW_CONFIDENCE` | Confidence | WARNING | confidence level == LOW |

Source: `utils.RiskInterpreter.decide()`. The full list is also enumerated in `config/complexity_budget.json → releaseDecisionCodes` (bound: max 16; additions require a v8 plan entry + UI mapping).

### Status resolution

```
if any blocker     → BLOCKED
else if any warning AND smoothedScore < HEALTHY_MIN → AT_RISK
else if any warning → WARNING
else               → READY
```

`wouldShip = (status == READY || status == WARNING)`. Codes are machine-readable; the dashboard maps each code to a user-facing sentence (no human-readable strings in `RiskInterpreter`).

### Legacy `interpret()` overload

For callers that don't have semantic data, `RiskInterpreter.interpret(score, smokePass, criticalBugs, regressionPass, locatorSamples, productHealth)` returns a bare `ReleaseStatus`. The 5-arg form bridges to the 6-arg with `productHealth=100` (safe default — missing data must not block release).

The Product Health gate in the legacy overload uses a **dual gate**: `productHealth < 20 AND score < POOR_MIN`. A lone product-health crash without overall degradation is a domain signal but not a release block.

---

## 4. The build gate

The build gate is a separate concern from the release decision. It's the only place the score can fail a Maven build.

### Default: advisory

```
mvn test                                    # advisory mode (default)
```

The gate **computes** the decision and emits an `enforcement` block in `health_snapshot.json`, but never throws. Misclassifications produce a log line, not a broken build.

```json
"enforcement": {
  "mode":                "advisory",
  "wouldHaveBlocked":    true | false,
  "actuallyBlocked":     false,
  "reasonIfNotEnforcing": "ADVISORY_MODE — set -DhealthGate.mode=blocking to enforce",
  "violations":          [ "..." ]
}
```

### Opt-in: blocking

```
mvn test -DhealthGate.mode=blocking
```

In blocking mode the gate calls `Assert.fail(...)` in `@AfterSuite` if any violation exists. Violations:
1. Any critical-flow failure
2. Critical runtime error (uncaught JS exception or fatal API failure)
3. `smoothedScore < BUILD_FAIL_THRESHOLD` (default 40, override with `-Dhealth.fail.score=N`)

**Don't switch to blocking until the advisory output has been stable for several runs.** Source: `utils.health.HealthGate.enforce()`.

### Invalid mode

`-DhealthGate.mode=blcoking` (typo) silently defaults to advisory. There is no validation log line for this yet — see Known Limitations.

---

## 5. Kill switches + feature flags

System properties fall into two categories — **kill switches** that turn parts of
the pipeline off, and **feature flags** that change scoring or data-collection
behaviour. Every set flag appears in the snapshot's `audit.overrides` block
(E2/E3) so an operator can see at a glance what was changed for any given run.

### Kill switches

Each is checked at entry to every public mutator and the snapshot writer.
Read is cheap; the goal is that every code path becomes a quiet no-op without
requiring callers to know.

| Flag | Effect |
|---|---|
| `-DhealthTracker.disabled=true` | All mutators (`recordJsError`, `recordTestFailure`, …) short-circuit. In-memory state from before the flag was set is preserved. |
| `-DhealthSnapshot.disabled=true` | `writeSnapshot()` early-returns. Stale prior-run file remains on disk untouched. |
| `-DhealthGate.disabled=true` | `enforce()` records `reasonIfNotEnforcing=KILL_SWITCH_ACTIVE` and never throws, regardless of mode. |

Sources: `HealthTracker.isTrackerKilled() / isSnapshotKilled()`,
`HealthGate.isKillSwitchActive()`.

### Feature flags (scoring behaviour)

These don't disable anything — they switch between alternative scoring formulas.
All default to OFF so historical trend comparison stays apples-to-apples until
an explicit flip.

| Flag | Default | Effect |
|---|---|---|
| `-Dpenalty.curveCap` | `false` | Replaces hard `Math.min(rawPenalty, 150)` with the diminishing-returns curve `150 × (1 − exp(−raw/150))`. See §1 Formula. C1. |
| `-Djs.suppression.sampleSizeAware` | `false` | Replaces the hard "first 3 LOW clusters count" threshold with `max(3, ceil(totalTestCount × 0.1))`. Below 30 tests the formula yields the legacy 3; above, scales linearly. C3. |
| `-DhealthGate.mode` | `advisory` | Set to `blocking` to make `enforce()` actually fail the build on violations. Unrecognised values (typos) log a WARN line and fall back to `advisory`. |

### Feature flags (recording / video)

| Flag | Default | Effect |
|---|---|---|
| `-DrecordVideo` | `true` | Per-test screen recording during the suite. Disable for short test cycles where the FFmpeg cost is unwanted. |
| `-DrecordPassedVideo` | `true` | Save recordings for PASS tests too (not just FAIL). Disable to keep only failure videos. |

### When to use them

- **Snapshot or tracker writes are corrupting data** → set the relevant
  `disabled` flag, ship the build, fix the root cause out-of-band.
- **Build is blocking on a known-false gate signal** →
  `-DhealthGate.disabled=true` until the signal is repaired. The override
  shows on the dashboard as a red banner (§6); a quiet override is worse
  than a loud one.
- **Trialling a scoring formula change** → flip the relevant feature flag for
  a few runs, compare against legacy via the trend chart, then make the
  default flip decision (or revert).
- **Local development, not interested in trend** → none of the kill switches
  are needed; advisory mode plus the default thresholds will not break your
  build.

---

## 6. The dashboard

`reports/trend/dashboard.html` is regenerated by `@AfterSuite`. Sections in
document order:

1. **Override banner** (top, only when any non-default flag is active) — red
   for kill-switch / video bypass overrides; blue for `healthGate.mode=blocking`
   intent-shift. Behaviorally verified for both branches. E3.
2. **30-Second Triage Box** (top, only when status ≠ READY) — WHAT BROKE /
   BLAST RADIUS / OWNER / ACTION. Owner routes on `productHealth < 50`:
   Frontend Engineering (Product) vs QA Automation (Framework).
3. **Executive Decision Panel** — release decision, confidence label, risk
   level, recommended action.
4. **Release Blockers** — itemised blockers and warnings with verbatim
   violation text.
5. **Semantic Health** — four layered scores + per-domain cluster panels +
   phase reliability table. `🆕 NEW` badge on clusters whose `title` did not
   appear in the previous run's snapshot. Per-cluster `amplification` field
   (A.6.2) classifies count into RETRY_STORM / BOOT_LOOP / EMBEDDED_REPEAT /
   NORMAL_REPEAT / NONE based on the count-vs-distinctContextCount ratio.
6. **Data Quality** (D2, open-by-default, collapsible) — 4 cards covering
   integrity / evidence-channels / amplification / trendStats. Answers
   "can I trust this run's numbers?" Browser-behaviorally verified.
7. **Platform Health** (D1, open-by-default, collapsible) — 4 cards covering
   build-gate enforcement / snapshot pipeline / governance / schema evolution.
   Surfaces the A.5.3 + B1 self-observability signals.
8. **Trend chart** — last 30 runs by score + penalty + JS error count.
9. **Recent Test Runs** — per-test rows with `📷 Screenshot`, `📋 Console`,
   `🔍 DOM`, `▶ Replay` links when artifacts exist.
10. **Recordings** (collapsed-by-default, collapsible) — replay videos for
    PASS/FAIL tests when `-DrecordVideo=true`.

Sources:
- `utils.DashboardBuilder` orchestrates
- `utils.dashboard.components.SemanticPanelComponent` — Semantic Health (§5)
- `utils.dashboard.components.PlatformHealthComponent` — D1 Platform Health (§7)
- `utils.dashboard.components.DataQualityComponent` — D2 Data Quality (§6)
- `utils.dashboard.components.UrlValidationComponent` — embedded in Semantic Health

### Confidence labels

The dashboard's confidence headline is qualitative — HIGH / MODERATE / LOW. Numeric value is in the tooltip only, to avoid false precision. The numeric is derived from `deriveConfidence(gov, trend)` in `DashboardBuilder` (governance violations, false-suppression count, trend regression spikes).

---

## 7. Complexity bounds

`config/complexity_budget.json` is a self-imposed bound on the scoring system's own complexity. The schema enforces an upper limit on:

- Evidence channels (max 8, current 6: testResults, businessOutcomes, jsErrors, telemetry, urlValidation, semanticClusters)
- Quality enum values (max 8, final set of 7)
- Reason enum values (max 8, final set of 5)
- Release decision codes (max 16, current 9 — listed in §3)
- platformHealth categories (max 4: snapshot_pipeline, evidence_collection, governance, schema_evolution)

**Additions require PR review + 4-week observation justification.** Removals follow a 30-day deprecation → 30-day grace → hard-remove cycle (`deprecationPolicy` in the JSON).

The budget is checked monthly. Override quota: 4 per quarter (`governanceBudget.budgetViolationOverridesPerQuarter`). Source: `config/complexity_budget.json`, `utils.governance.ComplexityBudget`.

A separate **coverage catalog** (B1) at `config/coverage-catalog.json` declares
the business outcomes the harness claims to test. Same pattern as
ComplexityBudget — read-only, lazy singleton, graceful degraded mode.
Source: `utils.coverage.CoverageCatalog`. Currently seeded with 2 outcomes;
expands as B2 verifiers come online.

**Closure of A.5.4 territory:** `ComplexityBudget.isLoaded()` and
`CoverageCatalog.isLoaded()` are now both read at every snapshot write and
surfaced in `platformHealth.governance.{complexityBudgetLoaded,
coverageCatalogLoaded}`. A corrupt or missing config file is visible
in every subsequent run, not just logged once at WARN.

---

## 8. Operating playbook

### "The build went red — what do I look at first?"

1. Open `reports/trend/dashboard.html`. The triage box at the top names the failing test, blast radius, owner team, and recommended action.
2. Cross-check the Executive Decision Panel — the structured blockers list tells you which decision code fired.
3. If Product Health < 50 → the issue is in the app (Frontend Engineering).
4. If Framework Health < 50 → the issue is in the automation (QA Automation).
5. Click `📋 Console` / `🔍 DOM` / `▶ Replay` on the failing row for evidence.

### "The dashboard says BLOCKED but I want to ship"

The dashboard's release decision is advisory. The Maven build is what actually fails — and only when `-DhealthGate.mode=blocking` is set. Default builds will not break.

If you genuinely need to ship despite a BLOCKED status, the conversation is human-to-human, not flag-to-flag. Document the override in the release notes.

### "I think the scoring is wrong"

Check, in order:
1. Did the smoke suite pass? Smoke < 90% always triggers a BLOCKER regardless of overall score.
2. Did any critical-flow test fail? Critical-flow failures override score with `hasCriticalFlowFailures()`.
3. Are there 3+ CRITICAL clusters in Semantic Health? That's a hard ceiling on release status.
4. Is Product Health < 20? Dual-gated with score < POOR_MIN → BLOCKED.

If none of those explain the verdict, dump `reports/trend/health_snapshot.json` and trace the `blockers` / `warnings` arrays against §3.

### "I want to disable the gate entirely for one run"

```
mvn test -DhealthGate.disabled=true
```

The dashboard still renders; nothing fails. The `enforcement.reasonIfNotEnforcing` field records that the kill switch was active, so trend history will reflect the override.

### "I want to disable telemetry collection because it's corrupting data"

```
mvn test -DhealthSnapshot.disabled=true -DhealthTracker.disabled=true
```

Stale prior snapshot remains on disk untouched. Next normal run will smooth against it as if nothing happened — which means the kill switch is a stopgap, not a fix. File a ticket for the underlying cause.

---

## 9. Known limitations (as of E1 baseline)

History: A.5.2 baseline documented 6 limitations. A.5.3 + A.5.4 + B1 closed
items #2 / #3 / #4 / #5. The current state:

| # | Limitation | Severity | State |
|---|---|---|---|
| 1 | `RUN_WRITE_LOCK` is JVM-local — parallel Surefire forks can race | HIGH | **OPEN** — production posture is `forkCount=1` until a file-level lock ships |
| 2 | Snapshot write failure was invisible to caller | HIGH | **CLOSED** (A.5.3) — visible in `platformHealth.snapshot_pipeline.{writeErrors, lastWriteError}` |
| 3 | Malformed prior snapshot was indistinguishable from first-run | MEDIUM | **CLOSED** (A.5.3 + A.5.4) — surfaced as `previousScoreSource: MALFORMED` plus `integrity.status: DEGRADED` |
| 4 | `ComplexityBudget.loaded == false` was silent | MEDIUM | **CLOSED** (A.5.3) — `platformHealth.governance.complexityBudgetLoaded`. B1 added the same wiring for `coverageCatalogLoaded` |
| 5 | Invalid `-DhealthGate.mode=garbage` silently defaulted to advisory | LOW | **CLOSED** (A.5.3) — WARN logged + captured in `platformHealth.governance.healthGateModeInputRaw` |
| 6 | Toggling `healthTracker.disabled` mid-run preserves earlier state | LOW | **OPEN, doc-only** — startup-time toggle is the supported posture |
| 7 | E1 audit scope is bounded (sequential single-fork, no contributor data, no clusters) | MEDIUM | **OPEN** — see `docs/e1-reproducibility-baseline.md` for the un-tested paths. Internal nondeterminism: 0 detected within tested scope |

LIM #1 remains the most consequential. The current operating posture is
single-fork (verified via `pom.xml` and observed Surefire output). When B-track
needs to enable parallel forks for speed, LIM #1 must be addressed first.

LIM #7 is the natural successor to the original LIM #2/#3/#4 — those limitations
were about *visibility* of failure modes; LIM #7 is about *reproducibility*
under broader test scope. Closing it requires the comparator infrastructure
described in `docs/e1-reproducibility-baseline.md`.

---

## 10. Source-of-truth pointers

| Concept | File |
|---|---|
| Thresholds, penalties, bands, diminishing-returns curve (C1) | `src/test/java/utils/HealthPolicy.java` |
| Score state + smoothing + kill switches + platformHealth + integrity + trendStats + audit | `src/test/java/utils/health/HealthTracker.java` |
| Advisory/blocking gate, enforcement outcome | `src/test/java/utils/health/HealthGate.java` |
| Release decision (codes + factors) | `src/test/java/utils/RiskInterpreter.java`, `src/test/java/utils/release/ReleaseDecision.java`, `src/test/java/utils/release/DecisionFactor.java` |
| Layered scores + sample-size-aware suppression (C3) | `src/test/java/utils/health/semantic/LayeredHealthScores.java` |
| Semantic snapshot composition + cluster `isNew` + amplification | `src/test/java/utils/health/semantic/SemanticHealthSnapshot.java` |
| Cluster classification + per-cluster amplification (A.6.2) | `src/test/java/utils/health/semantic/ErrorClusterer.java`, `src/test/java/utils/health/semantic/ErrorCluster.java` |
| Trend statistics calculator (C2) | `src/test/java/utils/history/trends/TrendStatsCalculator.java` |
| Dashboard HTML (template, override banner, video modal, toggles) | `src/test/java/utils/DashboardBuilder.java` |
| Semantic Health panel | `src/test/java/utils/dashboard/components/SemanticPanelComponent.java` |
| Platform Health panel (D1) | `src/test/java/utils/dashboard/components/PlatformHealthComponent.java` |
| Data Quality panel (D2) | `src/test/java/utils/dashboard/components/DataQualityComponent.java` |
| URL Validation panel | `src/test/java/utils/dashboard/components/UrlValidationComponent.java` |
| Complexity bounds | `config/complexity_budget.json`, `src/test/java/utils/governance/ComplexityBudget.java` |
| Coverage catalog (B1) | `config/coverage-catalog.json`, `src/test/java/utils/coverage/CoverageCatalog.java` |
| **Companion docs** | |
| B/C/D/E roadmap | `docs/scoring-system-roadmap.md` |
| Degraded-mode review | `docs/phase-a5-degradation-review.md` |
| E1 reproducibility baseline | `docs/e1-reproducibility-baseline.md` |
| Weight rationale (E4 baseline) | `docs/scoring-weights-rationale.md` |
| C4 data collection procedure | `docs/c4-data-collection.md` |
| Evidence-first analysis discipline | `docs/evidence-first-analysis.md` |

---

## 11. Snapshot top-level fields (v3 schema)

The complete list of top-level keys in `reports/trend/health_snapshot.json`,
in alphabetical order, with which phase introduced each:

| Field | Phase | Purpose |
|---|---|---|
| `audit` | E2 | List of currently-active overrides with `{property, currentValue, defaultValue, effect, kind}`. `kind` is `"bypass"` (kill switches, video disabled, scoring flag flipped) or `"intent-shift"` (gate mode escalated to blocking). Dashboard renders the override banner from this. |
| `confidence` | A3 | `{level, ratio, sampleN, windowDays}` — sample-size-derived trust in the smoothed score. NONE/LOW/MEDIUM/HIGH. |
| `enforcement` | A.5.1 | Build-gate decision: `{mode, wouldHaveBlocked, actuallyBlocked, reasonIfNotEnforcing, violations, killSwitchActive}`. |
| `integrity` | A.5.4 | Contributor math + trust invariants: `{status, checksRun, violations, trust}`. Status is PASS / DEGRADED (smoothing trust violated) / FAIL (math violated). |
| `penalty` | A1 | Raw penalty total before cap or curve. |
| `platformHealth` | A.5.3 / A.6.1 / B1 | Four sub-blocks (capped at 4 per `complexity_budget.json`): `snapshot_pipeline` (writeErrors, previousScoreSource), `evidence_collection` (6 channels with quality+reason, A.6.1), `governance` (config-file load state, gate-mode validity, coverage catalog state), `schema_evolution` (DEFERRED stub until B4). |
| `release` | A4 | Structured release decision: `{status, blockers[], warnings[], wouldShip}`. Codes from `RiskInterpreter`. |
| `schemaVersion` | B4 (pre-stub) | Currently `3`. Frozen pending B4's SchemaMigrator. |
| `score` | A1 | Final integer score 0-100 after curve/cap + rounding. |
| `scoreContributors` | A1 | Per-source breakdown with `{rawTotal, appliedTotal, suppressed, capReason, items[]}`. Reproducible explanation of the score. |
| `semantic` | A2/A.6.2 | Layered scores (Product/Framework/Telemetry/Business), clusters (with `isNew` + `amplification`), per-domain error counts, business outcomes, URL validation findings, telemetry summary, top-level amplification histogram. |
| `smoothedScore` | A1 | EMA-smoothed score (current run's `score` × 0.3 + previous smoothed × 0.7). |
| `status` | A1 | Band label: EXCELLENT / HEALTHY / WARNING / POOR / CRITICAL. |
| `testFailures` | A1 | Per-test failure records with `{test, reason, stackTrace, urlFindings?}`. |
| `timestamp` | A1 | `System.currentTimeMillis()` at write time. Always differs between consecutive runs — used as the methodology floor in the E1 reproducibility audit. |
| `trendStats` | C2 | `{sampleSize, mean, stdDev, currentZScore, outlier, outlierThreshold, window}` over the last 30 runs from `history.json`. Outlier flag fires at \|z\| ≥ 2.0 with sample ≥ 5. |

**Adding a top-level field:** requires a `complexity_budget.json` review since
each addition risks eroding the schema-evolution discipline. The current
discipline: top-level adds should fit a new logical purpose; otherwise nest
inside `platformHealth.<existing-category>` to stay under that 4-category cap.

---

## 12. Operational maturity

> **Why this section exists.** The earlier sections describe every subsystem as
> if they all carry equal weight in production decisions. They don't. This
> section is the honest classifier — what's actually consequential vs. what's
> scaffolding vs. what's vapor.
>
> **Maturity is verified, not assumed.** Each row's classification is backed by
> direct observation (snapshot inspection, runtime test, code-path tracing).
> Items I have *not* traced end-to-end are marked **UNVERIFIED** rather than
> guessed at — that's the only safe move when the doc author has built or
> verified some pieces but not others.

### Maturity model — explicit criteria

Tiers are ordered by strength of evidence. The table is meant to be operational:
a code reviewer should be able to hold a PR author to these criteria.

| Tier | Pass criteria — author must point at this evidence |
|---|---|
| **VERIFIED** | (a) Component produces observable output in `health_snapshot.json`, the rendered dashboard, or HealthGate log AND (b) at least one behavioral test or runtime inspection in the *current cycle* asserts on that output. Strongest tier. |
| **PRODUCTION** | (a) Component runs every suite and (b) its output has been observed in `health_snapshot.json` or another consumer this cycle. No fresh behavioral test required — but the observation must be recent enough to trust. |
| **EXPERIMENTAL** | Code path runs only when an off-by-default feature flag is set, OR the component is wired into the pipeline but its output has been observed but not consumed by a downstream reader. |
| **UNVERIFIED** | Component is referenced from another file's imports / method signatures but author has not observed it produce any output during this cycle. Could promote to any of the above once evidence is gathered. |
| **DORMANT** | A focused audit confirmed there is no production invocation path. Code exists, compiles, possibly has its own unit tests, but no production call site triggers it. Stronger than UNVERIFIED (a tier specifically reached by an audit, not by absence of investigation). Next step is "delete or wire" — not "investigate." |
| **DEFERRED** | Explicit stub. Code that intentionally emits a placeholder (e.g. `{status: "DEFERRED"}`) awaiting a future phase. Distinct from UNVERIFIED — the author *knows* it doesn't work yet. |

### Contributor decision flow

When introducing a new component or reclassifying one, walk the questions in order:

1. **Is the component code intentionally a placeholder (e.g. emits "DEFERRED",
   has a TODO referencing a future phase)?**
   → **DEFERRED**. Stop.

2. **Does it run only when a non-default flag is set, OR is its output ignored
   downstream?**
   → **EXPERIMENTAL**. Stop.

3. **Have you observed its output (in a snapshot, dashboard render, or log line)
   during this validation cycle?**
   - **No** → **UNVERIFIED**. Stop.
   - **Yes** → continue.

4. **Does at least one behavioral test or explicit runtime inspection from this
   cycle assert on that output?**
   - **No** → **PRODUCTION**.
   - **Yes** → **VERIFIED**.

The decision flow is intentionally biased against PRODUCTION/VERIFIED claims —
if the author has to think, the answer is probably UNVERIFIED.

### Anti-decay practice

This table will silently rot without a control. **Every PR that adds, renames,
or significantly modifies a scoring/dashboard component must update this table
in the same diff.** Acceptable outcomes:

- Add a new row with its tier + evidence pointer
- Move an existing row from UNVERIFIED → PRODUCTION/VERIFIED with new evidence
- Demote a row to UNVERIFIED if the previous evidence is stale

A PR that adds a new file but doesn't touch this table is incomplete. A PR
that adds a new file under DEFERRED needs a phase tag pointing at the work
that will close it.

**Review cadence:** the table should be re-audited every ~30 production runs
or every release cycle, whichever comes first. The audit signal worth watching:
**if UNVERIFIED grows faster than PRODUCTION+VERIFIED, the framework is
accumulating architectural debt regardless of how honest the documentation is.**

### Subsystem classification

Re-classified against the formal criteria above. `VERIFIED` requires a *fresh*
behavioral test from this cycle; `PRODUCTION` requires only recent observation.

| Component | Tier | Evidence |
|---|---|---|
| **Scoring pipeline** | | |
| `HealthTracker` (state, mutators, `writeSnapshot`) | **VERIFIED** | Snapshot inspected across A.5.3/A.5.4/A.6.1/B1/C2/E1/E3 runs this cycle; structure asserted |
| `HealthPolicy` (penalty + cap math) | **VERIFIED** | Cross-checked at penalty=0 (smoke) and penalty=30 (Pricing failure run); curve match confirmed |
| `HealthPolicy.applyDiminishingReturns` (C1 curve, flag ON) | **VERIFIED** | Component-level math + Pricing run with flag ON producing score=73 vs hardcap=70 |
| `HealthPolicy.applyDiminishingReturns` (default OFF branch) | PRODUCTION | Default code path; runs every score computation but routes through legacy `Math.min` |
| `LayeredHealthScores` | **VERIFIED** | C3 component test with 6 exact-integer assertions; output asserted in every snapshot |
| `LayeredHealthScores` sample-size LOW suppression (C3, flag ON) | **VERIFIED** | 6 component-test scenarios pass; production runs use legacy (flag OFF) |
| `TrendStatsCalculator` (C2) | **VERIFIED** | Snapshot inspection caught field-name bug during validation; post-fix output asserted |
| **Release decisioning** | | |
| `RiskInterpreter.decide` (structured) | PRODUCTION | `release` block observed in every snapshot; not behaviorally re-tested this cycle |
| `RiskInterpreter.interpret` (legacy 6-arg) | PRODUCTION | Dashboard status banner derives from this; observed at multiple band transitions |
| `HealthGate` advisory mode | **VERIFIED** | Default path; `enforcement` block asserted across snapshots |
| `HealthGate` blocking mode | **VERIFIED** | E3 intent-shift run: blue banner asserted, no false-fail observed |
| **Self-observability (A.5.x)** | | |
| `platformHealth.snapshot_pipeline` | **VERIFIED** | Fields asserted across 5+ runs this cycle |
| `platformHealth.governance` (config-file load state) | **VERIFIED** | ComplexityBudget + CoverageCatalog state asserted |
| `platformHealth.evidence_collection` (A.6.1) | **VERIFIED** | 6-channel structure asserted on HomepageSmokeTest |
| `platformHealth.schema_evolution` | DEFERRED | Stub awaiting B4 |
| `integrity` block (A.5.4) | **VERIFIED** | Caught real cross-interaction bug with C1 — strongest possible evidence |
| `audit.overrides` (E2) | **VERIFIED** | 2-flag + 1-flag + intent-shift runs asserted |
| Override banner (E3) | **VERIFIED** | Both bypass + intent-shift branches HTML-asserted |
| **Semantic layer** | | |
| `ErrorClusterer` cluster construction | PRODUCTION | Runs every snapshot; structure observed |
| Per-cluster `amplification` (A.6.2) | EXPERIMENTAL | Classifier code runs but observed cluster count was 0 in all runs this cycle. Math is component-test-verified but real-world classification awaits a fixture that produces clusters |
| `UrlValidationTracker` + `UrlValidationRunner` | **VERIFIED** | 185 URLs / 12 findings on HomepageExhaustiveTest; order stability asserted in E1 audit |
| `BusinessOutcomeTracker` | PRODUCTION | 1 transaction observed; coverage limited to one test class until B2 |
| **Dashboard rendering** | | |
| `DashboardBuilder` orchestrator | PRODUCTION | Regenerates dashboard every run; observed |
| `SemanticPanelComponent` | PRODUCTION | Renders Semantic Health; observed in HTML grep |
| `PlatformHealthComponent` (D1) | **VERIFIED** | Browser-driven open/collapse/expand test, 7 assertions |
| `DataQualityComponent` (D2) | **VERIFIED** | Same suite, same coverage |
| `UrlValidationComponent` | PRODUCTION | Embedded in Semantic Health, observed in HTML |
| Trend chart (Chart.js) | PRODUCTION | Renders; not freshly behaviorally tested this cycle |
| Recordings section | PRODUCTION | Collapsed-by-default, video modal observed |
| **Config / governance** | | |
| `ComplexityBudget` | **VERIFIED** | Loaded every run; load state asserted in governance block |
| `CoverageCatalog` (B1) | **VERIFIED** | Loaded with 2 outcomes; state asserted |
| `GovernanceReportGenerator` | **DORMANT** | Audit: `DashboardBuilder.write()` (zero-arg) is the only call site in the codebase. Receives `govSnapshot=null` → null-safe output |
| `GovernanceSnapshotBuilder` / `GovernanceSnapshotDto` | **DORMANT** | Same audit: never invoked because production call path forces `trendSnapshot=null, correlationSnapshot=null` |
| `OrchestrationSnapshotBuilder` / `OrchestrationSnapshotDto` | **DORMANT** | Same audit |
| `OrchestrationPanel` (`buildOrchestrationHtml`) | **DORMANT** | Returns "" when `orch == null` — `orch` is always null in production |
| `WorkflowRiskDto` / `SuitePriorityDto` | **DORMANT** | Consumed only by orchestration HTML which doesn't render |
| **AI layer** | | |
| AI panel rendering (`aiAnalyzedCount` gate) | **DORMANT** | Audit: no production code path invokes AI services. Every snapshot inspected had `aiAnalyzedCount == 0` |
| `AiClient`/`AiRetryPolicy`/`AiCacheService` etc. | **DORMANT** | Audit: zero non-AI-package call sites in the codebase. The two consumer classes that import AI DTOs (`utils.report.PdfReportBuilder`, `FailureClusterService`) themselves have zero external call sites. Production PDF builder is `utils.health.report.PdfReportBuilder` (different class, no AI imports) |
| **Test infrastructure** | | |
| `BaseTest` lifecycle (setUp/afterMethod) | PRODUCTION | Every test extends; recording fix verified this cycle for SKIP-status path |
| `ApplicationReadiness` (4-signal wait) | PRODUCTION | Used across test classes |
| `NetworkMonitor` | PRODUCTION | Reset in setUp; not behaviorally re-tested |
| `VideoRecorder` | **VERIFIED** | SKIP-path fix verified; PASS/FAIL recordings observed |
| `FailureArtifactManager` | PRODUCTION | Failure folders populated correctly across multiple runs |
| `ManualLoginHelper` | **VERIFIED** | Signup→login toggle helper added this cycle; flow verified across ExploreMenu suite |
| `JsConsoleMonitor` | PRODUCTION | Used by smoke tests for FAIL-severity gate; observed firing |
| **B/C/D/E future work** | | |
| B2 Outcome Verifier registry | DEFERRED | Roadmap'd; moves catalog from declarative to executable |
| B3 TypedAssert | DEFERRED | |
| B4 SchemaMigrator | DEFERRED | `schema_evolution` stub awaits this |
| B5 Coverage-weighted score | DEFERRED | Depends on B1 + B2 |
| B6 Business-tier blocker | DEFERRED | Depends on B1 + B2 |
| B7 Flake Governance | DEFERRED | |
| D3 Assertions + trendStats panel | DEFERRED | trendStats half is already covered by D2; assertions half awaits B3 |
| D4 Audit + flake panel | DEFERRED | Override-audit half is covered by E3 banner; flake half awaits B7 |
| E1 full reproducibility CI gate | DEFERRED | Baseline characterised; gate needs comparator infrastructure |
| E4 Weight calibration | DEFERRED | Needs ≥30 stable runs post-B5/C1 + ground-truth corpus |

**Tier counts after the audit:**
VERIFIED: 19 · PRODUCTION: 14 · EXPERIMENTAL: 1 · UNVERIFIED: 0 · **DORMANT: 7 components / 3 subsystems** · DEFERRED: 11

### Subsystem-level dormancy view

Component-count understates dormant scope when many classes belong to one
subsystem. The dormant rows above collapse into three architectural units:

| Subsystem | Status | Component count (≈) | Notes |
|---|---|---|---|
| AI | DORMANT | ~6 classes (AiClient, AiRetryPolicy, AiCacheService, FailureAnalyzer, ReleaseNarrator, AiEnrichmentExecutor) + 2 DTO consumers (`utils.report.PdfReportBuilder`, `FailureClusterService`) | No call site outside `utils.ai/`. Closer to disconnected than experimental |
| Governance | DORMANT | ~4 classes (Builder, Dto, ReportGenerator, dashboard renderer) | Receives `null` because the production call path doesn't supply inputs |
| Orchestration | DORMANT | ~8 classes (Builder, Dto, Panel, WorkflowRiskDto, SuitePriorityDto, ExecutionPlanDto, AdaptiveExecutionPlanner, …) | Same null-input pattern as Governance |

**Subsystem-level health indicator:**
`dormant subsystems / total subsystems ≈ 3 / 10` (rough — scoring + dashboard +
test infra + clustering + URL + business + history + AI + governance + orchestration).
**~30% of subsystems are dormant.** Higher than the component-ratio suggests
because dormant components cluster into a few large subsystems.

**Audit-driven changes (this cycle):**
- 7 items moved UNVERIFIED → DORMANT after end-to-end call-site tracing
- 1 item (amplification classifier) reclassified PRODUCTION → EXPERIMENTAL after
  re-examining what its production output actually was (cluster count = 0 in all
  observed runs means it never produced an amplification classification in
  practice — only DEFERRED-like stubs)

**Tier-health indicators:**

| Metric | Value | What it means |
|---|---|---|
| `UNVERIFIED / (VERIFIED + PRODUCTION)` | 0 / 33 = 0.00 | Down from 0.21 because the audit resolved every UNVERIFIED entry into a sharper tier |
| `DORMANT / (VERIFIED + PRODUCTION)` | 7 / 33 ≈ 0.21 | New leading indicator. Bigger than zero is fine if there's an intentional activation plan; flag if it grows without one |
| `DORMANT / (DORMANT + VERIFIED + PRODUCTION)` | 7 / 40 = 17.5% | ~1 in 6 components has no production call path. That's the simplification-opportunity ceiling |

**The two ratios capture different risks:** UNVERIFIED is "we don't know yet";
DORMANT is "we checked and the answer is no path." If a future audit
finds UNVERIFIED creeping back above 0, that's investigation debt. If DORMANT
grows without a credible activation plan attached to each entry, that's
deletion debt.

### Observations + risks (post-audit)

What the audit proved, stated narrowly:

1. **Governance + Orchestration chain is DORMANT.** `DashboardBuilder.write()`
   (zero-arg) is the *only* call site in the codebase. The two-arg overload
   that triggers `OrchestrationSnapshotBuilder.build()` +
   `GovernanceSnapshotBuilder.build()` exists but is never invoked from any
   discovered code path. **What this does NOT prove:** that the chain is
   intentionally abandoned. At least three possibilities remain open until
   a stakeholder resolves them — (a) dead code, (b) partially-completed
   roadmap work, (c) an alternate entry point not yet traced. Resolution
   is a decision, not a discovery — see Dormant Resolution Review below.

2. **AI layer is DORMANT, and more severely so.** No AI service has any call
   site from outside `utils.ai/`. The two non-AI files that import AI DTOs
   (`utils.report.PdfReportBuilder`, `FailureClusterService`) themselves
   have zero external call sites — and the actual production PDF builder
   used by `BaseTest` is `utils.health.report.PdfReportBuilder`, a *different
   class*. Unlike governance/orchestration, the AI chain isn't merely
   receiving null inputs from a never-invoked overload; **it has no inbound
   wiring at all.** Closer to dead-code territory than the governance chain,
   but the same Resolution Review procedure applies.

3. **B-track is the right next track, but resolving the three dormant
   subsystems should happen first.** Adding B2 verifiers to a framework
   that carries three architecturally significant dormant subsystems
   makes the framework harder to understand without making it more useful.
   Either deletion or activation creates clarity; remaining dormant indefinitely
   creates none. Doing the Resolution Review costs ~1 short session per
   subsystem.

4. **Static shared WebDriver is acknowledged in the test infrastructure but
   not formally documented as a scaling limit.** Worth adding to the
   degradation review when parallel-fork support becomes a real requirement
   (currently `forkCount=1` is the supported posture — see §9 LIM #1).

5. **Maturity tier discipline must apply to deletions, not just additions.**
   The anti-decay practice covers "PR adds a component → claim a tier." It
   should also cover "PR deletes a DORMANT component → record the deletion
   here so future readers know it was a deliberate cleanup, not a regression."
   Otherwise deletions risk being reverted later by someone who finds the
   class missing and assumes accidental breakage.

### Dormant Resolution Review (procedure)

For each subsystem in DORMANT tier, a stakeholder review answers four
questions. The answers determine the next action — the doc cannot decide;
only a human with org context can. Goal: convert every DORMANT entry into
either an activation path or a deletion PR within one quarter.

**For each dormant subsystem, ask:**

1. Is activation planned within 90 days? (Y/N — pointer to which task or
   ticket, if Y)
2. Is anyone — inside or outside this repo — currently depending on the
   subsystem's output? (Y/N — name the consumer, if Y)
3. Is there a named owner for the subsystem? (Y/N — name them, if Y)
4. Is there a roadmap item that names this subsystem as a dependency? (Y/N
   — pointer to the doc, if Y)

**Resolution by answer pattern:**

| Pattern | Action |
|---|---|
| Yes to (1) | Move to DEFERRED. Add the activation path to this table. Add a phase tag (e.g. "DEFERRED — activation in F2") |
| Yes to (2) | Promote out of DORMANT (the consumer's existence is an activation path). Re-classify to PRODUCTION or UNVERIFIED depending on whether the output is observed |
| Yes to (3) but no to (1) and (4) | The owner is the deletion authority. Ask them to either claim a roadmap slot for activation or sign off on the deletion |
| No to all four | Delete in the next routine cleanup PR. Record the deletion under the anti-decay-practice note above |

**Initial Resolution Review (open — needs stakeholder input):**

| Subsystem | (1) Activation in 90d? | (2) Consumer? | (3) Owner? | (4) Roadmap item? | Suggested action |
|---|---|---|---|---|---|
| AI | TBD | TBD | TBD | TBD | Most severe of the three — no inbound wiring; deletion is the lower-risk default unless someone can point at an activation path |
| Governance | TBD | TBD | TBD | TBD | Less severe than AI — there's at least a structural slot in DashboardBuilder that *would* activate it. Wiring effort: a few hours |
| Orchestration | TBD | TBD | TBD | TBD | Same pattern as Governance — null-input path, would need a producer |

The TBDs are intentional. This table is the **structured-input slot for the
human review**; once filled, the answers drive the action column without
further design work.

### What this section is NOT

Not a comprehensive audit. Not a guarantee that the PRODUCTION items behave
correctly under all conditions — those claims live in §9 (limitations) and
the validation evidence in `docs/scoring-system-roadmap.md`. This section's
job is the narrower one of separating *what's actually shipping decisions*
from *what's scaffolding*, so future work can target the right thing.
