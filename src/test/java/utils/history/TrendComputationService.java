package utils.history;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.dto.HistoricalTrendDto;
import utils.history.dto.LocatorTrendDto;
import utils.history.dto.PerformanceTrendDto;
import utils.history.dto.StabilityTrendDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes trend intelligence from persisted history.
 *
 * Dashboard and PDF builders call this service — they never read raw
 * storage files directly. This indirection protects them from schema changes.
 *
 * All computation is deterministic (no ML, no GPT). Anomaly detection uses
 * simple threshold rules designed to be stable across runs.
 */
public final class TrendComputationService {
    private static final Logger LOG = LoggerFactory.getLogger(TrendComputationService.class);

    // Anomaly threshold: a single-run drop of this many points triggers an alert
    private static final int ANOMALY_DROP_THRESHOLD = 15;
    // Flaky: stabilityScore below this AND oscillationRate above FLAKY_OSCILLATION_MIN
    private static final int    FLAKY_SCORE_MAX       = 70;
    private static final double FLAKY_OSCILLATION_MIN = 0.20;

    private TrendComputationService() {}

    // ── Score trend ───────────────────────────────────────────────────────

    public static HistoricalTrendDto computeScoreTrend(int windowRuns) {
        List<JSONObject> runs = HistoricalRunStore.readAll();
        HistoricalTrendDto dto = new HistoricalTrendDto();
        if (runs.isEmpty()) return dto;

        List<JSONObject> window = tail(runs, windowRuns);
        dto.windowSize = window.size();

        for (JSONObject run : window) {
            JSONObject summary = objOrEmpty(run, "summary");
            dto.scores.add(num(summary, "healthScore", 0));
            dto.runIds.add(str(run, "runId", "?"));
        }

        if (!dto.scores.isEmpty()) {
            dto.latestScore   = dto.scores.get(dto.scores.size() - 1);
            dto.movingAverage = dto.scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);

            if (dto.scores.size() >= 2) {
                int prev  = dto.scores.get(dto.scores.size() - 2);
                int curr  = dto.scores.get(dto.scores.size() - 1);
                dto.delta = curr - prev;
                dto.trend = dto.delta > 0 ? "IMPROVING" : dto.delta < 0 ? "DEGRADING" : "STABLE";

                int drop = prev - curr;
                if (drop >= ANOMALY_DROP_THRESHOLD) {
                    dto.anomalyDetected     = true;
                    dto.anomalyDescription  = "Score dropped " + drop
                            + " points in one run — investigate regression";
                }
            }
        }
        return dto;
    }

    // ── Stability trends ──────────────────────────────────────────────────

    public static List<StabilityTrendDto> computeStabilityTrends() {
        List<JSONObject> records = TestStabilityTracker.loadAll();
        List<StabilityTrendDto> dtos = new ArrayList<>();

        for (JSONObject rec : records) {
            StabilityTrendDto dto = new StabilityTrendDto();
            dto.testId = str(rec, "testId", "unknown");

            JSONObject metrics   = objOrEmpty(rec, "metrics");
            dto.stabilityScore   = num(metrics, "stabilityScore",     100);
            dto.passRate         = dbl(metrics, "passRate",           1.0);
            dto.failureRate      = dbl(metrics, "failureRate",        0.0);
            dto.oscillationRate  = dbl(metrics, "oscillationRate",    0.0);
            dto.totalRuns        = num(metrics, "totalRuns",            0);
            dto.isFlaky          = dto.stabilityScore < FLAKY_SCORE_MAX
                                && dto.oscillationRate > FLAKY_OSCILLATION_MIN;
            dto.lastUpdated      = str(rec, "lastUpdated", "");

            JSONArray recent     = arrOrEmpty(rec, "recentHistory");
            dto.recentHistory    = new ArrayList<>();
            for (Object r : recent) dto.recentHistory.add(r.toString());

            dtos.add(dto);
        }

        // Sort: most unstable first
        dtos.sort((a, b) -> Integer.compare(a.stabilityScore, b.stabilityScore));
        return dtos;
    }

    public static List<StabilityTrendDto> flakyTests() {
        return computeStabilityTrends().stream()
                .filter(d -> d.isFlaky)
                .toList();
    }

    // ── Performance trends ────────────────────────────────────────────────

    public static PerformanceTrendDto computePerformanceTrend(String page, int windowEntries) {
        PerformanceTrendDto dto = new PerformanceTrendDto();
        dto.page = page;

        JSONObject record = PagePerformanceHistory.load(page);
        if (record == null) return dto;

        JSONArray history = arrOrEmpty(record, "history");
        if (history.isEmpty()) return dto;

        List<Long> times = new ArrayList<>();
        for (Object item : history) {
            times.add(lng((JSONObject) item, "loadTimeMs", 0L));
        }

        List<Long> window = tail(times, windowEntries);
        dto.entryCount    = window.size();
        dto.latestLoadMs  = window.get(window.size() - 1);
        dto.avgLoadMs     = (long) window.stream().mapToLong(Long::longValue).average().orElse(0);
        dto.maxLoadMs     = window.stream().mapToLong(Long::longValue).max().orElse(0);
        dto.minLoadMs     = window.stream().mapToLong(Long::longValue).min().orElse(0);
        // Baseline = first ever recorded value (not windowed)
        dto.baselineMs    = lng((JSONObject) history.get(0), "loadTimeMs", dto.avgLoadMs);
        dto.degradationPct = dto.baselineMs > 0
                ? ((dto.latestLoadMs - dto.baselineMs) / (double) dto.baselineMs) * 100.0 : 0.0;
        dto.isDegraded    = dto.degradationPct > 50.0;

        return dto;
    }

    // ── Locator trends ────────────────────────────────────────────────────

    public static List<LocatorTrendDto> computeLocatorTrends() {
        return LocatorReliabilityHistory.loadAllAsDtos().stream()
                .sorted((a, b) -> Integer.compare(a.reliabilityScore, b.reliabilityScore))
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static <T> List<T> tail(List<T> list, int n) {
        if (list.size() <= n) return list;
        return list.subList(list.size() - n, list.size());
    }

    @SuppressWarnings("unchecked")
    private static JSONArray arrOrEmpty(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    private static JSONObject objOrEmpty(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    private static String str(JSONObject o, String k, String def) { Object v = o.get(k); return v != null ? v.toString() : def; }
    private static int    num(JSONObject o, String k, int def)    { Object v = o.get(k); return v instanceof Number ? ((Number)v).intValue()   : def; }
    private static long   lng(JSONObject o, String k, long def)   { Object v = o.get(k); return v instanceof Number ? ((Number)v).longValue()  : def; }
    private static double dbl(JSONObject o, String k, double def) { Object v = o.get(k); return v instanceof Number ? ((Number)v).doubleValue(): def; }
}
