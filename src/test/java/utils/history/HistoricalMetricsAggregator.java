package utils.history;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Recomputes all derived (rolling) aggregates from the immutable run snapshots.
 *
 * Normal path: incremental updates happen in @AfterMethod (TestStabilityTracker)
 * and @AfterSuite (run snapshot write + locator/perf updates).
 *
 * This aggregator is the recovery / audit path — call recomputeFromRuns() when:
 *   - history files are deleted or corrupted
 *   - schema migration is needed
 *   - CI full-rebuild is requested
 *
 * Known limitation: run snapshots only record FAILED tests, not the full passed
 * set. The recompute path therefore only reconstructs failure-based stability;
 * tests that always pass will not appear until they fail at least once.
 * The incremental @AfterMethod path correctly records both outcomes.
 */
public final class HistoricalMetricsAggregator {
    private static final Logger LOG = LoggerFactory.getLogger(HistoricalMetricsAggregator.class);

    private HistoricalMetricsAggregator() {}

    public static void recomputeFromRuns() {
        List<JSONObject> runs = HistoricalRunStore.readAll();
        if (runs.isEmpty()) {
            LOG.info("HistoricalMetricsAggregator: no run snapshots found — nothing to recompute");
            return;
        }
        LOG.info("Recomputing historical aggregates from {} run snapshot(s)", runs.size());

        // Rebuild test stability for tests that appear in failure records
        Map<String, List<Boolean>> testOutcomes = new LinkedHashMap<>();
        for (JSONObject run : runs) {
            JSONArray failures = arrOrEmpty(run, "failures");
            Set<String> failedThisRun = new HashSet<>();
            for (Object item : failures) {
                String testId = str((JSONObject) item, "testId", null);
                if (testId != null) failedThisRun.add(testId);
            }
            for (String testId : failedThisRun) {
                testOutcomes.computeIfAbsent(testId, k -> new ArrayList<>()).add(false);
            }
        }

        for (Map.Entry<String, List<Boolean>> entry : testOutcomes.entrySet()) {
            for (boolean passed : entry.getValue()) {
                TestStabilityTracker.update(entry.getKey(), passed);
            }
        }

        // Rebuild page performance from run performance snapshots
        for (JSONObject run : runs) {
            JSONArray perf = arrOrEmpty(run, "performance");
            for (Object item : perf) {
                JSONObject p = (JSONObject) item;
                String page  = str(p, "page", null);
                long   loadMs = lng(p, "loadTimeMs", 0L);
                if (page != null && loadMs > 0) {
                    PagePerformanceHistory.record(page, loadMs);
                }
            }
        }

        LOG.info("Historical aggregates recomputed from {} run(s)", runs.size());
    }

    @SuppressWarnings("unchecked")
    private static JSONArray arrOrEmpty(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    private static String str(JSONObject o, String k, String def) { Object v = o.get(k); return v != null ? v.toString() : def; }
    private static long   lng(JSONObject o, String k, long def)   { Object v = o.get(k); return v instanceof Number ? ((Number)v).longValue() : def; }
}
