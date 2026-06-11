# Phase A.5 Degraded-Mode Review

**Purpose**: Static code analysis of every failure path the user enumerated, before continuing to A.5.3. No production data was corrupted — claims are derived from the source.

**Scope**: A.5.0 (`ComplexityBudget`), A.5.1 (advisory mode in `HealthGate`), A.5.2 (kill switches in `HealthTracker`/`HealthGate`).

**Method**: Trace each injection scenario through the code; identify graceful, silent-lying, and crash behaviors.

---

## Summary table

| # | Injection | Code behavior | Verdict | Severity if it ever happens |
|---|-----------|---------------|---------|----------------------------|
| 1 | Corrupt `complexity_budget.json` (malformed JSON) | `ComplexityBudget` catches Exception in constructor, sets `loaded=false`, `loadError=<msg>`, logs WARN, instance is unusable but doesn't crash JVM | **Graceful — partial** | LOW — but currently **no code reads `loaded`** so the failure is silent |
| 2 | `complexity_budget.json` missing | Constructor reports `loadError=file not found`, `loaded=false` | **Graceful — partial** | LOW — same silent-failure issue as #1 |
| 3 | Malformed previous `health_snapshot.json` | `readPreviousSmoothedScore()` catches Exception, falls through to CSV, then returns -1 → no smoothing applied, score = raw | **Graceful — silent** | MEDIUM — caller cannot tell smoothing was skipped |
| 4 | `health_snapshot.json` exists but missing `smoothedScore` key | `root.get("smoothedScore")` returns null → `instanceof Number` is false → fall through to CSV | **Graceful — silent** | MEDIUM — same |
| 5 | Invalid `-DhealthGate.mode=garbage` | `resolveMode()` falls through to ADVISORY default | **Graceful — silent** | LOW — but operator's intent (blocking) is silently ignored |
| 6 | `-DhealthGate.disabled=true` + violations present | `enforce()` checks kill switch, records `reasonIfNotEnforcing=KILL_SWITCH_ACTIVE`, never throws | **Working correctly** | N/A |
| 7 | `-DhealthSnapshot.disabled=true` | `writeSnapshot()` early-returns at line 471 with stderr message; snapshot NOT updated | **Working correctly** | N/A — but stale file from prior run remains on disk |
| 8 | `-DhealthTracker.disabled=true` | Mutators short-circuit; `writeSnapshot()` early-returns at line 467 | **Working correctly** | N/A — but the `printReport()` still runs and shows the previous-run state (because in-memory state is preserved across the disabled mutators) |
| 9 | IOException during snapshot write | `writeSnapshot` catches at line 588, prints to stderr, swallows | **Graceful — silent** | HIGH — caller doesn't know the snapshot wasn't written; next run's smoothing reads stale data |
| 10 | Concurrent writeSnapshot calls from parallel surefire forks | `RUN_WRITE_LOCK` is **JVM-local**, not file-level | **Broken** | HIGH — parallel forks in separate JVMs will race; lock is ineffective across processes |

---

## Detailed findings

### Finding 1 (HIGH severity): `RUN_WRITE_LOCK` is JVM-local, not cross-process

**Code**: `HealthTracker.java:660`
```java
public static final Object RUN_WRITE_LOCK = new Object();
```

**The problem**: Maven Surefire can fork parallel JVMs for test execution (`<forkCount>2</forkCount>`). Each JVM has its own `Object` instance. Two forks running simultaneously will both pass the `synchronized` block without coordinating.

**Result**: `health_snapshot.json`, `history.json`, `history.csv`, `semantic_history.jsonl` can be partially overwritten by interleaved writes.

**The transactional-writes claim from Phase A2 is invalid for parallel Surefire builds.**

**Fix shape** (NOT implementing yet — flagging): file-level lock using `FileChannel.tryLock()` or moving to an atomic write-rename pattern. Current single-JVM serial runs are fine; parallel forks are not.

### Finding 2 (HIGH severity): Snapshot write failure is invisible to the caller

**Code**: `HealthTracker.java:587-590`
```java
Files.writeString(path, json.toJSONString());
} catch (Exception e) {
    System.err.println("Failed to write snapshot: " + e.getMessage());
}
```

**The problem**: If `writeString` fails (disk full, permission denied, antivirus lock), nothing returns the failure. The next run reads stale data from disk, computes a (wrong) smoothing, and persists it as if it were a normal continuation.

**Why it matters**: This is the exact failure mode the user warned about — "silent collector failure" that corrupts release confidence. Smoothing across runs is meant to dampen noise; here it would actively encode disk-write failures into the score.

**Fix shape**: Increment a `platformHealth.snapshot_pipeline.writeErrors` counter (planned in A.5.3). For now, at minimum the failure should be visible in the snapshot return value, and the next snapshot should refuse to smooth against a known-stale predecessor.

### Finding 3 (MEDIUM severity): Smoothing-skipped is indistinguishable from first-run

**Code**: `HealthTracker.java:610-641`

When the previous snapshot is malformed, the read falls through and returns `-1`. Then in `getSmoothedScore()`:
```java
int prev = readPreviousSmoothedScore();
if (prev < 0) return raw;     // ← treats malformed-file the same as first-run
```

**The problem**: The caller can't distinguish "no prior run" (legitimate first invocation) from "prior run's snapshot is corrupt" (a real telemetry failure). Both produce `raw == smoothed`.

**Fix shape**: return a tagged result `{value, source: SNAPSHOT|CSV|NO_DATA|MALFORMED}` and surface MALFORMED in `platformHealth`.

### Finding 4 (MEDIUM severity): `ComplexityBudget.loaded == false` is currently silent

**Code**: `ComplexityBudget.java` — well-formed degraded-mode handling: `loaded=false`, `loadError=<msg>`, instance still usable but empty.

**The problem**: **No production code path reads `loaded` or `loadError`**. The budget is constructed lazily on first `.get()` call, and currently nothing in the codebase calls it. If `complexity_budget.json` is corrupt:
- The error is logged once via SLF4J WARN
- All `getEntry(key)` calls return `null`
- All `checkViolations()` calls return empty lists (nothing to violate against)
- The system carries on as if no budgets exist

**This is the failure mode the v8 critique warned about** — config-theater. Budgets exist, but nothing enforces them yet.

**Fix shape** (A.5.3 territory): the upcoming `platformHealth.governance.indicators` should read `ComplexityBudget.get().isLoaded()` and surface `loadError` as a HIGH-severity governance indicator. Wire this in before claiming budgets are enforced.

### Finding 5 (LOW severity): Invalid gate mode silently defaults

**Code**: `HealthGate.java`
```java
String raw = System.getProperty("healthGate.mode", "advisory");
return "blocking".equalsIgnoreCase(raw) ? Mode.BLOCKING : Mode.ADVISORY;
```

**The problem**: `-DhealthGate.mode=blcoking` (typo) silently becomes ADVISORY. An operator who thinks they enabled blocking won't know they didn't.

**Fix shape**: log WARN when the property is set but not one of the recognized values. Cheap and worth doing now.

### Finding 6 (LOW severity): Kill-switch `healthTracker.disabled=true` preserves in-memory state across mutator no-ops

When the kill switch is active, `recordJsError()` etc. short-circuit. But existing in-memory state from earlier in the JVM lifecycle (if the switch was toggled mid-run via `System.setProperty`) is preserved. `printReport()` and `writeSnapshot()` (if `healthSnapshot.disabled` isn't also set) would emit *partial* state.

**This is an unusual scenario** — kill switches are typically set at startup, not toggled mid-process — but worth documenting.

---

## What the static review changes about my A.5.0-A.5.2 confidence claim

**Previous claim** (last session): "verified end-to-end."

**Corrected claim**: verified on the happy path. Three failure modes are graceful-but-silent (would corrupt confidence without alerting); two are actively broken (cross-process lock + write-failure invisibility); the remainder degrade as designed.

**A.5.0-A.5.2 do not yet meet the bar for "production-ready" under degraded conditions. They are operationally credible only when the file system, single JVM, and well-formed configs are all assumed.**

---

## What this means for next steps

### Must do BEFORE continuing to A.5.3

1. **Surface `ComplexityBudget.isLoaded()` somewhere** — even a single `LOG.warn` at suite start. Otherwise the budget is silently absent.
2. **Document invalid-mode WARN** — one line in `HealthGate.resolveMode()`.
3. **Decide on the cross-process lock posture** — either:
   - (a) document that the system requires `forkCount=1` for now, OR
   - (b) replace `RUN_WRITE_LOCK` with a file lock (~30 lines, low risk)
4. **Snapshot-write failure visibility** — at minimum, set a static volatile flag `LAST_SNAPSHOT_WRITE_FAILED` so A.5.3's `platformHealth` block can report it.

These are **four small fixes** addressing specific findings, not new features. They harden what exists.

### Defer to A.5.3 proper

- Full `platformHealth.snapshot_pipeline` counter system
- `MALFORMED` vs `NO_DATA` distinction for previous-score reads
- Cross-process file lock implementation (vs the documentation-only fix above)

### Defer to A.5.4

- The integrity invariant check is the right place to also detect "previous snapshot was malformed" — the integrity check should fail-closed when the predecessor can't be parsed.

---

## Recommendation

Address findings 4 + 5 + 6 (LOW/MEDIUM, easy) and the lightweight version of 1 + 2 (HIGH but cheap: document `forkCount=1` requirement + add `LAST_SNAPSHOT_WRITE_FAILED` flag) **before** A.5.3. They are operational hardening for what already exists, not feature creep.

Total estimated work: ~1.5 hours. Compile-check after each.

Then proceed to A.5.3 with a cleaner foundation.
