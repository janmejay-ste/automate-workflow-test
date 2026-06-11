# C4 Data Collection — Targeted Noisy-Suite Procedure

## Why this document exists

Phase 3 of the C4 (defect-cluster penalty normalization) roadmap produced
a fully validated replay harness, but found the on-disk history is
**empty of contributor-rich snapshots** in the new schema. The remaining
unblock for Phase 4 (HealthTracker integration) is purely
evidence-collection: get enough real snapshots with `Over Cap ≥ 2` to
choose between Policy A and Policy B on data, not assumption.

This document is the operational procedure for that collection.

## What we are looking for

The replay harness's `Over Cap` column showed that Policy A and today's
flat scoring diverge **only** when a contributor source has at least two
clusters whose individual raw weight exceeds the source cap. Below that,
the two are arithmetically identical regardless of how many raw events
exist or how high the amplification ratio is.

**Unlock condition for Phase 4:** at least 3 archived snapshots where
`totalClustersOverCap ≥ 2`. Fewer than 3 → "C4 is theoretical, deprioritize."
3 or more → "C4 is a real production phenomenon, ship Policy A behind the
flag."

## Step-by-step

### 1. Pick a noisy suite

Suites most likely to surface multi-cluster amplified failures:

| Suite | Why |
|---|---|
| `GoHighLevelMindbodyConnectTest` | Exploratory connect workflow, exercises selectors that aren't on the smoke path. Likely fallbacks. |
| `ExploreMenuTestBase` subclasses | ~15-minute runs known to exercise many selectors; surfaces locator drift. |
| `mvn test -Dgroups=full` | Aggregates the largest selector surface area in one run. |

### 2. Run the suite

```bash
# Single noisy suite
mvn test -Dtest=GoHighLevelMindbodyConnectTest

# Or full regression
mvn test -Dgroups=full
```

The run will write `reports/trend/health_snapshot.json` at suite teardown.

### 3. Archive the snapshot before the next run overwrites it

```bash
mvn test -Dtest=ArchiveCurrentSnapshotTest \
         -Dgroups=manual \
         -Darchive.suite=GoHighLevelMindbodyConnectTest
```

This copies the live snapshot to
`reports/archive/c4-snapshots/snapshot_<UTC-timestamp>_<suite>.json`
plus a `.meta.json` sidecar containing:

| Field | What it tells you |
|---|---|
| `suite` | Which run produced this snapshot |
| `gitCommit` | Repo HEAD at archive time (or "unknown") |
| `status` | EXCELLENT / GOOD / WARNING / CRITICAL |
| `score`, `penalty` | Snapshot's own scoring |
| `totalRawEventCount` | How many raw contributor events |
| `totalClusterCount` | After re-fingerprinting |
| **`totalClustersOverCap`** | **THE KEY METRIC.** ≥ 2 means this archive is "interesting" for C4. |
| `perSourceClustersOverCap` | Which contributor source(s) hit over-cap |
| `policyAPenalty`, `policyBPenalty` | Pre-computed under both candidate policies |
| `policyADelta`, `policyBDelta` | Vs the snapshot's own penalty |

### 4. Repeat with different suites / commits

Aim for variety — different test suites, different days, possibly
different branches. The metadata sidecar makes it possible to later
ask "are over-cap runs concentrated in one suite or one commit?"
without re-running the harness.

### 5. Run replay across the archive

Once enough archives accumulate, extend `RealHistoryReplayTest` to also
discover `reports/archive/c4-snapshots/*.json` (currently it only reads
`reports/trend/health_snapshot.json`). One line change to its directory
discovery glob.

```bash
mvn test -Dtest=RealHistoryReplayTest
```

The report at `reports/c4-replay-real.md` will then show the per-run
table over the entire archived corpus.

## Quick triage — which archives matter?

To find the "interesting" archives without parsing every file:

```bash
# bash
for f in reports/archive/c4-snapshots/*.meta.json; do
  jq -r '"\(.totalClustersOverCap) \(.suite) \(input_filename)"' "$f"
done | sort -nr | head
```

```powershell
# PowerShell
Get-ChildItem reports\archive\c4-snapshots\*.meta.json |
  ForEach-Object {
    $m = Get-Content $_ -Raw | ConvertFrom-Json
    [pscustomobject]@{ OverCap = $m.totalClustersOverCap; Suite = $m.suite; File = $_.Name }
  } | Sort-Object OverCap -Descending | Select-Object -First 10
```

Anything with `OverCap ≥ 2` is decision-relevant. Anything with
`OverCap = 0` or `OverCap = 1` confirms Policy A ≈ today for that run.

## Anti-patterns to avoid

| Don't | Why |
|---|---|
| Backfill `scoreContributors.items` from older aggregate `history.json` | The raw event data is gone; reconstructing it is speculation disguised as evidence. Phase 3 explicitly rejected this path. |
| Synthesize fixtures to "show what Policy A does on amplified runs" | That's already been done. Synthetic data validated the harness, not the production prevalence question. New synthetic runs add complexity without adding evidence. |
| Choose a policy before `OverCap ≥ 2` runs are observed | The narrowing from "amplification inflates penalties" to "OverCap ≥ 2 is the divergence condition" was the whole point of Phase 3. Reverting to a coarser framing throws away that work. |
| Wire C4 into HealthTracker before archive review | Phase 4's gate is explicit and standing: 3+ archived snapshots with `OverCap ≥ 2`, replay-validated. |
| Treat console-log cluster headlines as the C4 decision input | The replay report at `reports/c4-replay-real.md` is the decision input. Console cluster headlines are intermediate output and must be confirmed by replay before any unlock claim. See `docs/evidence-first-analysis.md` §4 (anti-pattern A2). |

## When to revisit

- After **5 archived snapshots**: spot-check the distribution. If all are `OverCap = 0`, broaden the noisy-suite set.
- After **10 archived snapshots**: render the real-data replay report. Read the per-run table.
- After **3+ snapshots with `OverCap ≥ 2`**: Phase 4 unblock condition met. Choose a policy, start integration.
