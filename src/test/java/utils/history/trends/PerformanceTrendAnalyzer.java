package utils.history.trends;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.history.HistoryConfig;
import utils.history.PagePerformanceHistory;
import utils.history.dto.PerformanceTrendDto;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rolling-window performance trend analysis per page.
 *
 * Uses trend.window.runs (default 20) for rolling averages.
 * Degradation thresholds:
 *   NONE     : degradation <= 10%
 *   MILD     : degradation <= 30%
 *   MODERATE : degradation <= 50%
 *   SEVERE   : degradation >  50%
 */
public final class PerformanceTrendAnalyzer {

    public static final class Result {
        public final PerformanceTrendDto dto;
        public final String degradationSeverity;  // NONE | MILD | MODERATE | SEVERE
        public final String trend;                // IMPROVING | STABLE | DEGRADING
        public final double rollingAvgMs;         // average within window
        public final double volatility;           // stddev / mean — measures consistency

        private Result(PerformanceTrendDto dto, String severity, String trend,
                       double rollingAvgMs, double volatility) {
            this.dto               = dto;
            this.degradationSeverity = severity;
            this.trend             = trend;
            this.rollingAvgMs      = rollingAvgMs;
            this.volatility        = volatility;
        }
    }

    private PerformanceTrendAnalyzer() {}

    public static Result analyze(String page) {
        int window = HistoryConfig.getStabilityWindow(); // reuse window config
        PerformanceTrendDto dto = buildDto(page, window);
        if (dto.entryCount == 0) {
            return new Result(dto, "NONE", "STABLE", 0.0, 0.0);
        }

        String severity = classifyDegradation(dto.degradationPct);
        String trend    = computeTrend(page, window);
        double volatility = computeVolatility(page, window);

        return new Result(dto, severity, trend, dto.avgLoadMs, round2(volatility));
    }

    private static final String PAGES_DIR = "reports/history/pages";

    public static List<Result> analyzeAll() {
        try {
            if (!Files.exists(Paths.get(PAGES_DIR))) return List.of();
            try (Stream<java.nio.file.Path> stream =
                         Files.list(Paths.get(PAGES_DIR))) {
                return stream
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> {
                            try {
                                JSONObject rec = PagePerformanceHistory.load(
                                        p.getFileName().toString().replace(".json", ""));
                                if (rec == null) return null;
                                String page = str(rec, "page", p.getFileName().toString());
                                return analyze(page);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static PerformanceTrendDto buildDto(String page, int window) {
        PerformanceTrendDto dto = new PerformanceTrendDto();
        dto.page = page;
        JSONObject record = PagePerformanceHistory.load(page);
        if (record == null) return dto;

        JSONArray history = arrOrEmpty(record, "history");
        if (history.isEmpty()) return dto;

        List<Long> all = new ArrayList<>();
        for (Object item : history) all.add(lng((JSONObject) item, "loadTimeMs", 0L));

        List<Long> win = tail(all, window);
        dto.entryCount   = win.size();
        dto.latestLoadMs = win.get(win.size() - 1);
        dto.avgLoadMs    = (long) win.stream().mapToLong(Long::longValue).average().orElse(0);
        dto.maxLoadMs    = win.stream().mapToLong(Long::longValue).max().orElse(0);
        dto.minLoadMs    = win.stream().mapToLong(Long::longValue).min().orElse(0);
        dto.baselineMs   = lng((JSONObject) history.get(0), "loadTimeMs", dto.avgLoadMs);
        dto.degradationPct = dto.baselineMs > 0
                ? ((dto.latestLoadMs - dto.baselineMs) / (double) dto.baselineMs) * 100.0 : 0.0;
        dto.isDegraded   = dto.degradationPct > 50.0;
        return dto;
    }

    private static String classifyDegradation(double pct) {
        if (pct <= 10.0) return "NONE";
        if (pct <= 30.0) return "MILD";
        if (pct <= 50.0) return "MODERATE";
        return "SEVERE";
    }

    private static String computeTrend(String page, int window) {
        JSONObject record = PagePerformanceHistory.load(page);
        if (record == null) return "STABLE";
        JSONArray history = arrOrEmpty(record, "history");
        if (history.size() < 4) return "STABLE";

        // Compare second half average vs first half average of the window
        List<Long> all = new ArrayList<>();
        for (Object item : history) all.add(lng((JSONObject) item, "loadTimeMs", 0L));
        List<Long> win = tail(all, window);
        if (win.size() < 4) return "STABLE";

        int mid = win.size() / 2;
        double firstHalf  = win.subList(0, mid).stream().mapToLong(Long::longValue).average().orElse(0);
        double secondHalf = win.subList(mid, win.size()).stream().mapToLong(Long::longValue).average().orElse(0);
        double changePct  = firstHalf > 0 ? ((secondHalf - firstHalf) / firstHalf) * 100.0 : 0.0;

        if (changePct > 10.0)  return "DEGRADING";
        if (changePct < -10.0) return "IMPROVING";
        return "STABLE";
    }

    private static double computeVolatility(String page, int window) {
        JSONObject record = PagePerformanceHistory.load(page);
        if (record == null) return 0.0;
        JSONArray history = arrOrEmpty(record, "history");
        if (history.isEmpty()) return 0.0;

        List<Long> all = new ArrayList<>();
        for (Object item : history) all.add(lng((JSONObject) item, "loadTimeMs", 0L));
        List<Long> win = tail(all, window);

        double mean = win.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean == 0) return 0.0;
        double variance = win.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    @SuppressWarnings("unchecked")
    private static JSONArray arrOrEmpty(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    private static <T> List<T> tail(List<T> list, int n) {
        if (list.size() <= n) return list;
        return list.subList(list.size() - n, list.size());
    }

    private static long    lng(JSONObject o, String k, long def)    { Object v = o.get(k); return v instanceof Number ? ((Number) v).longValue()  : def; }
    private static String  str(JSONObject o, String k, String def)  { Object v = o.get(k); return v != null ? v.toString() : def; }
    private static double  round2(double v)                          { return Math.round(v * 100.0) / 100.0; }
}
