package utils.history;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.Instant;

/**
 * Append-log of per-page load time observations.
 *
 * Schema contract (schemaVersion = 1):
 *   page, history[{timestamp, loadTimeMs, baselineMs, deltaPercent}]
 *
 * Each call to record() appends one entry. Old entries are trimmed by
 * history.max.perf.entries.per.page. The first recorded entry for a page
 * becomes its implicit baseline.
 */
public final class PagePerformanceHistory {
    private static final Logger LOG = LoggerFactory.getLogger(PagePerformanceHistory.class);
    static final String PAGES_DIR = "reports/history/pages";

    private PagePerformanceHistory() {}

    @SuppressWarnings("unchecked")
    public static void record(String page, long loadTimeMs) {
        record(page, loadTimeMs, 0L);  // baseline resolved internally from first entry
    }

    @SuppressWarnings("unchecked")
    public static void record(String page, long loadTimeMs, long explicitBaselineMs) {
        if (page == null || page.isBlank()) return;
        try {
            Files.createDirectories(Paths.get(PAGES_DIR));
            Path path = Paths.get(PAGES_DIR, sanitize(page) + ".json");
            JSONObject record  = loadOrInit(path, page);
            JSONArray  history = (JSONArray) record.get("history");

            // Resolve baseline: explicit > first recorded entry > 0
            long baselineMs = explicitBaselineMs;
            if (baselineMs <= 0 && !history.isEmpty()) {
                JSONObject first = (JSONObject) history.get(0);
                baselineMs = lng(first, "loadTimeMs", 0L);
            }

            double deltaPercent = baselineMs > 0
                    ? round2(((loadTimeMs - baselineMs) / (double) baselineMs) * 100.0)
                    : 0.0;

            JSONObject entry = new JSONObject();
            entry.put("timestamp",    Instant.now().toString());
            entry.put("loadTimeMs",   loadTimeMs);
            entry.put("baselineMs",   baselineMs);
            entry.put("deltaPercent", deltaPercent);
            history.add(entry);

            // Trim to retention limit (oldest entries first)
            int max = HistoryConfig.getMaxPerfEntriesPerPage();
            while (history.size() > max) history.remove(0);

            Files.writeString(path, record.toJSONString());

        } catch (Exception e) {
            LOG.debug("PagePerformanceHistory.record failed for {}: {}", page, e.getMessage());
        }
    }

    public static JSONObject load(String page) {
        Path path = Paths.get(PAGES_DIR, sanitize(page) + ".json");
        if (!Files.exists(path)) return null;
        try {
            return (JSONObject) new JSONParser().parse(Files.readString(path));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static JSONObject loadOrInit(Path path, String page) {
        if (Files.exists(path)) {
            try { return (JSONObject) new JSONParser().parse(Files.readString(path)); }
            catch (Exception ignored) {}
        }
        JSONObject rec = new JSONObject();
        rec.put("schemaVersion", 1L);
        rec.put("page", page);
        rec.put("history", new JSONArray());
        return rec;
    }

    static String sanitize(String s) {
        // Strip protocol + host from URLs, then sanitize remaining characters
        String cleaned = s.replaceAll("https?://[^/]+", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static long   lng(JSONObject o, String k, long def) { Object v = o.get(k); return v instanceof Number ? ((Number)v).longValue() : def; }
}
