package testing.health.cluster;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import utils.HealthPolicy;
import utils.health.cluster.Cluster;
import utils.health.cluster.ClusterKey;
import utils.health.cluster.ClusterPenaltyPolicy;
import utils.health.cluster.ClusterScoringEngine;
import utils.health.cluster.ClusterScoringEngine.PolicyComparison;
import utils.health.cluster.ContributorSource;
import utils.health.cluster.ScoringResult;

/**
 * Fixture-driven tests for the cluster scoring engine and the two
 * candidate policies. These tests lock in HOW each policy behaves — not
 * which one is "correct." Phase 3's replay analysis on historical data is
 * what answers that question.
 *
 * <p>All tests run under default flag state (penalty.curveCap=false) so
 * {@code applyDiminishingReturns(raw, cap) == min(raw, cap)}. When the C1
 * curve flag is enabled, the absolute numbers change but the relative
 * ordering ("Policy A &gt;= Policy B for amplified clusters") is
 * preserved — that's the structural invariant the tests check.</p>
 *
 * <p>Zero production wiring. No HealthTracker, no DashboardBuilder.
 * &lt;1s runtime.</p>
 */
public class ClusterScoringFixtureTest {

    // Canonical caps for these tests — independent of HealthPolicy defaults
    // so the tests stay deterministic if HealthPolicy values change.
    private static final Map<ContributorSource, Double> CAPS = Map.of(
            ContributorSource.FALLBACKS,    30.0,
            ContributorSource.JS_ERRORS,    20.0,
            ContributorSource.TEST_FAILURES, 60.0
    );

    // ────────────────────────────────────────────────────────────────────
    // The canonical C4 case: 1 root cause, 22 raw events
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void singleClusterAmplified_policyAPreservesAmplification() {
        // 22 fallback events at weight 10 each = 220 raw
        Cluster c = mkCluster(ContributorSource.FALLBACKS, "fp.amp", 22, 10.0);
        ScoringResult r = ClusterScoringEngine.score(List.of(c),
                new ClusterPenaltyPolicy.PreserveAmplification(), CAPS);

        // Policy A applies diminishing returns to rawTotal=220 with cap=30.
        // Under default flag (curveCap=false): min(220, 30) = 30.
        double expected = HealthPolicy.applyDiminishingReturns(220.0, 30.0);
        Assert.assertEquals(r.totalApplied(), expected, 0.001,
                "Policy A: 1 cluster, raw=220, cap=30 → applyDiminishingReturns(220,30)");
        Assert.assertEquals(r.totalRawEventCount(), 22,
                "raw event count must be preserved through scoring for amplification visibility");
        Assert.assertEquals(r.totalClusterCount(), 1,
                "one cluster — that's the whole point of normalization");
        Assert.assertEquals(r.amplificationRatio(ContributorSource.FALLBACKS), 22.0, 0.001,
                "amplification ratio = 22 / 1 = 22.0");
    }

    @Test(groups = {"sanity"})
    public void singleClusterAmplified_policyBFlattens() {
        // Same input as previous test.
        Cluster c = mkCluster(ContributorSource.FALLBACKS, "fp.amp", 22, 10.0);
        ScoringResult r = ClusterScoringEngine.score(List.of(c),
                new ClusterPenaltyPolicy.FlatCluster(), CAPS);

        // Policy B applies one-event-worth: 10. Then source-level cap (30).
        double expected = HealthPolicy.applyDiminishingReturns(10.0, 30.0);
        Assert.assertEquals(r.totalApplied(), expected, 0.001,
                "Policy B: cluster contributes one event's worth (10), capped (30) → 10");
        // Same amplification visibility as Policy A — raw count is metadata,
        // not consumed by the policy itself. Important for transparency.
        Assert.assertEquals(r.totalRawEventCount(), 22,
                "raw event count is metadata, must survive regardless of policy choice");
    }

    @Test(groups = {"sanity"})
    public void singleClusterAmplified_policiesDifferByExpectedDelta() {
        Cluster c = mkCluster(ContributorSource.FALLBACKS, "fp.amp", 22, 10.0);
        PolicyComparison cmp = ClusterScoringEngine.compare(List.of(c), CAPS);

        // Under default flag: Policy A = 30, Policy B = 10 → delta = +20.
        // Under curve flag ON: Policy A ≈ 29.98, Policy B ≈ 10. Delta still ~+20.
        // The structural assertion (Policy A >= Policy B) holds either way.
        Assert.assertTrue(cmp.delta() > 0,
                "amplified cluster: Policy A must score higher (preserves the signal) — "
                + "this is the central design difference between the two policies");
    }

    // ────────────────────────────────────────────────────────────────────
    // Non-amplified case: 1 cluster, 1 event → policies must agree
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void singleEvent_policiesAgree() {
        Cluster c = mkCluster(ContributorSource.FALLBACKS, "fp.lone", 1, 10.0);
        PolicyComparison cmp = ClusterScoringEngine.compare(List.of(c), CAPS);
        Assert.assertEquals(cmp.delta(), 0.0, 0.001,
                "rawEventCount=1 → no amplification → both policies must produce the same "
                + "result. Disagreement here would mean one of the policies is leaking "
                + "extra penalty for non-amplified events.");
    }

    // ────────────────────────────────────────────────────────────────────
    // N independent clusters from same source — no cross-contamination
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void manyIndependentClusters_sameSource_areIndependent() {
        // Five independent root causes, 1 event each, weight 5
        List<Cluster> clusters = List.of(
            mkCluster(ContributorSource.JS_ERRORS, "k1", 1, 5.0),
            mkCluster(ContributorSource.JS_ERRORS, "k2", 1, 5.0),
            mkCluster(ContributorSource.JS_ERRORS, "k3", 1, 5.0),
            mkCluster(ContributorSource.JS_ERRORS, "k4", 1, 5.0),
            mkCluster(ContributorSource.JS_ERRORS, "k5", 1, 5.0));

        ScoringResult a = ClusterScoringEngine.score(clusters,
                new ClusterPenaltyPolicy.PreserveAmplification(), CAPS);
        // 5 × applyDiminishingReturns(5, 20) = 5 × 5 = 25 — but JS_ERRORS cap=20.
        // Under Policy A, each cluster is capped INDEPENDENTLY, so 5 × 5 = 25
        // (each cluster's raw is below cap).
        Assert.assertEquals(a.totalApplied(), 25.0, 0.001,
                "Policy A: independent clusters scored independently, no source-level cap");

        ScoringResult b = ClusterScoringEngine.score(clusters,
                new ClusterPenaltyPolicy.FlatCluster(), CAPS);
        // Policy B: 5 events × 5 = 25, then source-level cap=20 → 20.
        Assert.assertEquals(b.totalApplied(), 20.0, 0.001,
                "Policy B: source-level cap pulls 25 down to 20");
    }

    // ────────────────────────────────────────────────────────────────────
    // Mixed contributors — independent per-source scoring
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void mixedContributors_noCrossSourceLeak() {
        List<Cluster> clusters = List.of(
            mkCluster(ContributorSource.FALLBACKS,     "f1", 22, 10.0),    // raw 220
            mkCluster(ContributorSource.JS_ERRORS,     "j1",  3,  5.0),    // raw 15
            mkCluster(ContributorSource.TEST_FAILURES, "t1",  1, 15.0));   // raw 15

        ScoringResult r = ClusterScoringEngine.score(clusters,
                new ClusterPenaltyPolicy.PreserveAmplification(), CAPS);

        Assert.assertEquals(r.perSourceApplied().get(ContributorSource.FALLBACKS),
                HealthPolicy.applyDiminishingReturns(220, 30.0), 0.001);
        Assert.assertEquals(r.perSourceApplied().get(ContributorSource.JS_ERRORS),
                HealthPolicy.applyDiminishingReturns(15, 20.0), 0.001);
        Assert.assertEquals(r.perSourceApplied().get(ContributorSource.TEST_FAILURES),
                HealthPolicy.applyDiminishingReturns(15, 60.0), 0.001);

        // Sum reconciliation
        double sumPerSource = r.perSourceApplied().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        Assert.assertEquals(r.totalApplied(), sumPerSource, 0.001,
                "totalApplied must equal Σ perSourceApplied — single reconciliation invariant");

        // Per-source cluster + raw counts
        Assert.assertEquals(r.perSourceClusterCount().get(ContributorSource.FALLBACKS).intValue(),    1);
        Assert.assertEquals(r.perSourceClusterCount().get(ContributorSource.JS_ERRORS).intValue(),    1);
        Assert.assertEquals(r.perSourceClusterCount().get(ContributorSource.TEST_FAILURES).intValue(), 1);
        Assert.assertEquals(r.perSourceRawEventCount().get(ContributorSource.FALLBACKS).intValue(),    22);
        Assert.assertEquals(r.perSourceRawEventCount().get(ContributorSource.JS_ERRORS).intValue(),     3);
        Assert.assertEquals(r.perSourceRawEventCount().get(ContributorSource.TEST_FAILURES).intValue(), 1);
    }

    // ────────────────────────────────────────────────────────────────────
    // Edge cases
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void emptyInput_yieldsZero() {
        ScoringResult r = ClusterScoringEngine.score(List.of(),
                new ClusterPenaltyPolicy.PreserveAmplification(), CAPS);
        Assert.assertEquals(r.totalApplied(), 0.0, 0.001);
        Assert.assertEquals(r.totalClusterCount(), 0);
        Assert.assertEquals(r.totalRawEventCount(), 0);
    }

    @Test(groups = {"sanity"})
    public void nullClusters_yieldsZero_noNpe() {
        ScoringResult r = ClusterScoringEngine.score(null,
                new ClusterPenaltyPolicy.FlatCluster(), CAPS);
        Assert.assertEquals(r.totalApplied(), 0.0, 0.001);
    }

    @Test(groups = {"sanity"})
    public void missingCap_treatedAsUncapped() {
        Cluster c = mkCluster(ContributorSource.FALLBACKS, "fp", 5, 10.0);
        // Empty caps map — should not throw, should not cap.
        ScoringResult r = ClusterScoringEngine.score(List.of(c),
                new ClusterPenaltyPolicy.PreserveAmplification(), Map.of());
        Assert.assertEquals(r.totalApplied(), 50.0, 0.001,
                "missing cap = uncapped = raw passes through");
    }

    // ────────────────────────────────────────────────────────────────────
    // Structural invariants — hold regardless of which policy wins replay
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void invariant_policyAGtePolicyB_forAmplifiedClusters() {
        // Whenever ANY cluster has rawEventCount > 1, Policy A scores >= Policy B.
        // This is the structural reason Policy A is the "preserves amplification"
        // policy. If this ever breaks, the policies have been mis-implemented.
        for (int eventCount : new int[]{2, 5, 10, 22, 100}) {
            Cluster c = mkCluster(ContributorSource.FALLBACKS, "fp" + eventCount, eventCount, 10.0);
            PolicyComparison cmp = ClusterScoringEngine.compare(List.of(c), CAPS);
            Assert.assertTrue(cmp.delta() >= 0,
                    "Policy A must be >= Policy B for any amplified cluster (eventCount="
                    + eventCount + ", deltaActual=" + cmp.delta() + ")");
        }
    }

    @Test(groups = {"sanity"})
    public void invariant_reconciliation_totalEqualsSumPerSource() {
        // For ANY input + ANY policy: totalApplied == Σ perSourceApplied.
        // Forward-compat for the dashboard data-bucket-pts / data-penalty-total
        // contract — score engine MUST produce internally-consistent totals.
        List<Cluster> mixed = List.of(
            mkCluster(ContributorSource.FALLBACKS,     "a", 3, 10.0),
            mkCluster(ContributorSource.FALLBACKS,     "b", 7, 10.0),
            mkCluster(ContributorSource.JS_ERRORS,     "c", 2, 5.0),
            mkCluster(ContributorSource.TEST_FAILURES, "d", 1, 15.0));

        for (ClusterPenaltyPolicy policy : List.of(
                new ClusterPenaltyPolicy.PreserveAmplification(),
                new ClusterPenaltyPolicy.FlatCluster())) {
            ScoringResult r = ClusterScoringEngine.score(mixed, policy, CAPS);
            double sumPerSource = r.perSourceApplied().values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            Assert.assertEquals(r.totalApplied(), sumPerSource, 0.001,
                    "policy=" + policy.name()
                    + " totalApplied must equal Σ perSourceApplied");
        }
    }

    // ── helper ──────────────────────────────────────────────────────────

    private static Cluster mkCluster(ContributorSource source, String fp,
                                     int eventCount, double perEventWeight) {
        return new Cluster(
                new ClusterKey(source.name().toLowerCase() + "│" + fp),
                source,
                eventCount,
                eventCount * perEventWeight,
                Cluster.NO_TEMPORAL_DATA,
                Map.of());
    }
}
