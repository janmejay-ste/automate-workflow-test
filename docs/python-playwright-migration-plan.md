# Python + Playwright Migration Plan

A phased plan for migrating (or partially migrating) this Java /
Selenium / TestNG project to Python / Playwright / pytest. Applies the
discipline from `docs/evidence-first-analysis.md` throughout — Phase 0
is a decision gate, every later phase has an explicit unlock condition
and a rollback path.

## TL;DR

Before any code is written, **Phase 0 (decision)** must produce a
written answer to: *what problem are we actually solving?* Migration
on its own is a cost, not a benefit. The benefits are conditional on
specific failure modes Python/Playwright addresses better than the
current stack.

The most defensible end-state is probably **hybrid**, not full
migration:

| Layer | Recommended stack | Reason |
|---|---|---|
| Browser-driven test classes | Python / Playwright | Playwright's auto-waiting eliminates the StaleElementReferenceException class of failures we've been hitting |
| Page objects | Python / Playwright | Cleaner `page.evaluate()` vs the current heavy JS injection pattern |
| Scoring engine + dashboard + PDF reports | **Stay Java** | ~6 months of recent investment; algorithm is now complex (C1–C4, B4, D1–D3); rewriting it doubles risk for no behavioural gain |
| Schema / snapshot format | **Stay Java OR specified contract** | B4 already gives us version-stamped snapshots; either stack reads/writes the same JSON |
| Reports + dashboard | Stay Java | openhtmltopdf has no direct Python equivalent; weasyprint produces different output |

Full migration is possible but requires evidence that the hybrid
option is insufficient. Phase 0 records that evidence.

This plan supersedes the earlier `Python + Playwright Migration` plan
that lived in the planning notes from before the C4 / B4 / D3 / E3
work landed. The project has roughly doubled in surface area since
that earlier plan; some sections (network monitor, video recording)
remain accurate, others (scoring port, schema migration) need
substantial revision.

---

## Phase 0 — Decision (1 day, MUST happen first)

Produce a 1-page decision document answering, in writing:

| Question | Acceptable answer shape |
|---|---|
| What specific failure modes does Python/Playwright address better than today? | Concrete examples — e.g. "StaleElementReferenceException accounted for N of the last M failures, Playwright's auto-wait would have eliminated them" |
| What does it cost? | LOC count to migrate, team capacity, calendar weeks. Estimated, not authoritative. |
| What happens if we don't migrate? | The status-quo failure rate, what's blocked by it |
| What's the rollback plan if the migrated stack underperforms? | Concrete — "freeze migration, return to Java-only" must be a real option until full cut-over |
| Hybrid vs full — which is being chosen, and why? | Explicit. Default to hybrid unless full is justified. |

**Unlock condition for Phase 1:** the decision doc is written, reviewed
by at least one engineer who will own the work, and committed to
`docs/python-migration-decision.md`. Without this doc, no migration
code lands.

**Falsifiability check (Rule 1):** *what observation would tell us
the decision was wrong?* For example: "if after one Phase-3 test class
migrates and the new stack's flakiness equals the old stack's, the
decision is wrong." Record the equivalent observation in the decision
doc.

---

## Phase 1 — Scope inventory (1–2 days)

Classify every Java file in `src/test/java/**` into one of five
buckets. This is bookkeeping, but it produces the bill of materials
that the rest of the plan depends on.

| Bucket | Definition | Action |
|---|---|---|
| **MIGRATE — Browser-driven** | Test classes that drive a browser, page objects, wait utilities | Convert to Python in later phases |
| **MIGRATE — Test infrastructure** | BaseTest, TestNG listeners, FailureArtifactManager, VideoRecorder, ManualLoginHelper | Convert; some collapse into pytest fixtures |
| **KEEP — Scoring & reporting** | `utils/HealthPolicy`, `utils/health/**`, `utils/DashboardBuilder`, `utils/health/report/**`, the C4 cluster package, the B4 schema package | Stay Java; both stacks consume the same JSON snapshot |
| **REWRITE OR DELETE** | Dead helpers, deprecated patterns, duplicated utilities | Audit during migration; don't carry forward what isn't used |
| **DEFER** | Optional features whose Python equivalent doesn't exist cleanly (e.g. specific openhtmltopdf CSS) | Pin a follow-up; not blocking |

**Unlock condition for Phase 2:** every Java file in `src/test/java/**`
has a bucket label. The bucket map is committed.

**Self-check (Rule 5 — phased delivery):** the buckets define which
files Phase 3 may touch and which Phase 4 may touch. Crossing those
boundaries during later work is a violation. Record explicitly.

---

## Phase 2 — Hybrid bridge (1 week)

The Java and Python stacks will share `health_snapshot.json` and
analytics output via the file system. B4's schema versioning is what
makes this safe.

### 2a. Snapshot contract

The shared contract is `schemaVersion: 3` (V3_CONTRIBUTOR_RICH) — the
exact format `SchemaDetector` already recognises. Document it as a
contract, not as an implementation detail:

- Create `docs/snapshot-contract.md` extracting the schema from
  `SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH` and the example output
  at `reports/trend/health_snapshot.json`
- List every field a consumer can rely on
- List which fields are forward-compatible (additive) vs which are
  load-bearing (a migrator must handle their change)

This is a small standalone Phase 2 deliverable. Even if migration is
never started, the contract doc is valuable for any other consumer
(internal tooling, dashboards, observability pipelines).

### 2b. Python project skeleton

```
automate-workflow-py/
├── pyproject.toml
├── requirements.txt          # playwright, pytest, pytest-playwright, etc.
├── pytest.ini
├── conftest.py               # session-scope + function-scope fixtures
├── pages/                    # page objects
├── utils/
│   └── health_snapshot_writer.py  # writes v3-schema snapshots Java tooling can read
├── tests/
└── reports/                  # mirrors Java layout — same file paths so dashboards still work
```

The Python stack writes its run output to the same `reports/trend/`
directory the Java stack does. The Java dashboard / health-gate
reads from there. Hybrid works without any cross-language IPC.

### 2c. CI integration

CI runs Java first, then Python. Both contribute test rows to the
same `health_snapshot.json` via additive append. The Java dashboard
shows the combined picture.

Alternative if append-merge is fragile: each stack writes its own
snapshot file (`health_snapshot.python.json`), and a merge step in CI
combines them before dashboard generation. Slower, but no cross-stack
race conditions.

**Unlock condition for Phase 3:** snapshot contract doc exists,
Python project skeleton compiles + runs an empty test, both stacks'
output paths agree.

---

## Phase 3 — Migrate one test class as proof-of-concept (1 week)

Pick **one** test class. The cheapest informative choice is the
shortest browser-driven test that exercises the same surfaces as the
big ones — `AuthenticatedTest` (the sanity user journey) is a good
candidate. ~5 steps, login + dashboard + editor visibility + logout.

Deliverable:

- `tests/test_authenticated_sanity.py` in the Python project
- A page object subset (`pages/dashboard_page.py`, `pages/wait_utils.py`)
- pytest fixtures replacing `BaseTest.setUp` / `afterMethod`
- Network monitor port (`page.on("request" | "response")` instead of
  the current JS-injected `window.__nmErrors`)
- Failure artifact capture using Playwright built-ins (screenshot,
  DOM dump, trace if `--tracing on`)
- Video via Playwright's `record_video_dir` instead of the custom
  4-FPS PNG → FFmpeg pipeline

Run both stacks side-by-side for 5 consecutive days. Record:

- Pass rate (Python vs Java for the same test)
- Wall-clock time (Python vs Java)
- Flakiness (re-run stability)
- Failure quality (did Playwright produce more actionable artefacts?)

**Unlock condition for Phase 4:** the 5-day comparison shows Python
materially better OR explicitly equal-with-other-benefits. If Python
is *worse* on any axis without compensating wins, freeze migration
and revisit Phase 0.

**Rule 1 falsifiability check before Phase 4:** *what would tell us
the proof-of-concept failed?* — flakiness equal-or-worse than Java,
no measurable performance improvement, no qualitative artefact gain.
Phase 3 is allowed to fail at this gate.

---

## Phase 4 — Migrate browser-driven test classes (2–4 weeks)

Migrate in this order, one class per merge:

| Order | Class | Rationale |
|---|---|---|
| 1 | `AuthenticatedTest` | Already done in Phase 3 |
| 2 | `CreateConnectWorkflowTest` | Highest-value workflow; proves dropdown handling works in Playwright. Today's main StaleElementReferenceException site. |
| 3 | `GoHighLevelMindbodyConnectTest` | Larger Connect workflow; proves Mindbody-specific custom-value patterns translate |
| 4 | `ExploreMenuTestBase` + subclasses | The 15-minute exploratory suite; biggest browser test surface |
| 5 | `AppPairingTest` | Smaller scope; checks search/pair flow |
| 6 | `ResponsiveTest` | Viewport-dependent — Playwright's `page.set_viewport_size` is cleaner than the current Java equivalent |
| 7 | `SignupTest` | Auth flow with the post-login routing issue we surfaced earlier |
| 8 | Remaining test classes | One per merge, with the same 1-day comparison gate each time |

For each migration:

- Pull the Java page-object methods being called
- Translate to Python; use `page.locator(...).wait_for(state="visible")`
  in place of every `WebDriverWait(driver, 20).until(...)`
- Use Playwright's auto-wait everywhere possible — avoid `time.sleep`
  except for true async-completion races
- Run side-by-side for 1 day before deleting the Java class
- Keep the Java class in the repo (commented out or moved to
  `archive/`) until the comparison period passes

**Unlock condition for Phase 5:** ≥ 6 of the 8 classes migrated with
documented comparison data. The Java versions of migrated classes
can now be removed.

---

## Phase 5 — Migrate test infrastructure (1–2 weeks)

The infrastructure layer in Java is roughly:

| Java component | Python equivalent | Notes |
|---|---|---|
| `BaseTest.setUp / afterMethod` | `conftest.py` autouse fixtures | Function-scope fixture wraps each test; session-scope handles browser lifecycle |
| `@TestCategory` annotation | `@test_category` decorator + pytest marks | Use pytest marks (`@pytest.mark.full`, etc.) for group filtering; the decorator stores metadata for HealthTracker |
| `ManualLoginHelper` | `pages/auth_helper.py` | Same flow, Playwright `page.fill` / `page.click` |
| `FailureArtifactManager` | `tests/conftest.py` `pytest_runtest_makereport` hook | Playwright's screenshot + dom() reads come for free |
| `VideoRecorder` (custom FFmpeg pipeline) | `context.new_context(record_video_dir=...)` | Playwright built-in. Conversion from .webm to .mp4 optional |
| `NetworkMonitor` (JS-injected) | `page.on("request") / page.on("response")` | Native Playwright API; far cleaner |
| `WaitUtils` | `pages/wait_utils.py` | Most calls become no-ops because Playwright auto-waits; the helper shrinks |
| `CustomHtmlReporter` | Either pytest-html OR a thin Python script | Or skip; the Java DashboardBuilder is the canonical report |

**Unlock condition for Phase 6:** Python stack can run any migrated
test independently; all test-infrastructure features needed by
migrated tests are present.

---

## Phase 6 — Schema / scoring contract verification (1 week)

Before considering the scoring layer for migration, prove the hybrid
contract works in steady state.

- Python stack writes contributor events to `health_snapshot.json`
  using the v3 schema
- Java HealthTracker / DashboardBuilder reads it
- The dashboard renders correctly
- The B4 SchemaDetector classifies the Python-produced snapshots as
  V3_CONTRIBUTOR_RICH
- The C4 ReplayHarness reads them and produces ScoringResults

This is largely a verification phase, not new code. Done correctly,
the hybrid is now stable and could be the permanent end state.

**Unlock condition for Phase 7:** 2 weeks of CI runs with the hybrid
stack producing identical (or improved) dashboard output vs the
pure-Java baseline.

---

## Phase 7 (OPTIONAL) — Migrate scoring + dashboard

Only enter if Phase 0's decision doc says full migration is the goal,
or if maintenance of the Java scoring code becomes a bottleneck.

The scoring layer is the highest-risk migration target because:

- The algorithm has feature flags (`penalty.curveCap`,
  `healthGate.mode`, `js.suppression.sampleSizeAware`)
- B4 schema migration relies on sealed Java interfaces
  (`SchemaMigrator permits IdentityMigratorV3, LossyV2ToV3Migrator`)
  with compile-time exhaustiveness; Python equivalents require
  explicit registry + tests
- C4 cluster scoring (`ClusterPenaltyPolicy` sealed interface, same
  story)
- Dashboard HTML is ~2500 lines of string-concat in Java; Python
  port would be a Jinja2 rewrite — better long-term, but a rewrite,
  not a translation
- PDF reports use openhtmltopdf (Java-only); Python equivalent
  (weasyprint / pdfkit) produces different output and may require
  CSS retuning

The path through Phase 7 — if taken — should mirror C4's discipline:

| Sub-phase | Scope |
|---|---|
| 7a | Port pure-logic classes (`HealthPolicy`, `ContributorBucket`, `ClusterScoringEngine`, `SchemaDetector`) with full fixture-test coverage |
| 7b | Port `HealthTracker` (singleton state, snapshot writes) |
| 7c | Port `DashboardBuilder` to Jinja2 templates |
| 7d | Port PDF builder OR keep Java for PDF generation only |
| 7e | Cut-over: Java scoring engine retired |

Each sub-phase has its own unlock condition (Python output equals
Java output bit-for-bit on a frozen test corpus). Diverging output is
a Phase-7 stop-the-world event — the migrated version is wrong, by
definition, until proven otherwise.

**Realistic effort:** ≥ 8 weeks for Phase 7 alone. The C4 + B4 work
took weeks in Java; reimplementing it carries the same effort, plus
the cross-checking overhead.

**Recommendation:** stay hybrid unless Phase 0's decision doc
specifically justifies Phase 7. The conditions justifying Phase 7
are narrow: Java scoring becomes a measured bottleneck, OR the team
decides Python single-language is worth the rewrite cost.

---

## Risk register

| Risk | Mitigation |
|---|---|
| Migration drift — Python stack diverges from Java without anyone noticing | Side-by-side comparison gates between every phase. Java archive preserved until comparison passes. |
| Lost scoring fidelity during Phase 7 | Bit-for-bit output comparison on a frozen test corpus; any divergence stops the sub-phase |
| Team retraining cost | Phase 3 absorbs this risk in a small surface; if the team can't ship Phase 3 in a week, they probably can't ship Phase 4 |
| openhtmltopdf has no direct Python equivalent | Keep Java for PDF generation only (hybrid sub-pattern); skip Python PDF if not justified |
| Custom video recorder loses on Playwright | This is actually a win — Playwright's built-in is materially simpler |
| Schema evolution during migration | B4 is the protection. Any new version adds a migrator on both sides. Compile-time exhaustiveness on Java side, runtime check on Python side |
| Reproducibility tests (E1) re-baseline | Run the reproducibility audit fresh after each major migration phase to confirm no new nondeterminism crept in |

---

## What this plan does NOT promise

Applying Rule 2 (vocabulary calibration) explicitly:

- This plan does **not** claim Python/Playwright will be faster than
  Java/Selenium. It claims Playwright's auto-waiting addresses
  specific failure modes (stale-element exceptions) — that's
  observable and bounded.
- This plan does **not** claim a specific calendar duration. Each
  phase has a rough estimate; actual time depends on the team and
  the discovered surface area.
- This plan does **not** claim full migration is the right call. It
  argues hybrid is the safer default and that full migration
  requires explicit Phase-0 justification.
- This plan does **not** claim the migrated dashboard will look
  identical. If Phase 7 runs, expect dashboard re-styling work.

---

## Standing references

- `docs/evidence-first-analysis.md` — applied throughout: each phase
  has an unlock condition (Rule 5), claims use calibrated vocabulary
  (Rule 2), and Phase 0 is the decision artifact (Rule 6).
- `docs/scoring-system.md` — the scoring layer that Phase 7 would
  port; understand it before deciding to migrate it.
- `docs/scoring-weights-rationale.md` — must be ported alongside
  HealthPolicy.java in Phase 7a; the weight invariants apply to both
  languages.
- `docs/c4-data-collection.md` — the operational procedure for C4
  evidence collection; would need a Python equivalent if C4 is
  migrated.
- `docs/scoring-system-roadmap.md` — the B/C/D/E work breakdown; any
  pending item there interacts with this migration plan.

---

## When to revisit this plan

- After Phase 0 decision doc is written — revise Phase 1+ if the
  decision narrows scope (e.g. "hybrid only, no Phase 7")
- After Phase 3 proof-of-concept — revise effort estimates with real
  data
- After any scoring-layer roadmap item lands (C4 Phase 4, E4 weight
  calibration, B5/B6) — that's new Python-side work if Phase 7 is
  active
- After a real divergence between the Java and Python stacks during
  the hybrid period — add the discovered failure mode to the risk
  register and re-evaluate

This plan should be revised, not replaced, as the migration
progresses. The phase structure is the spine; the estimates and
sub-tasks are best-effort approximations subject to discovery.
