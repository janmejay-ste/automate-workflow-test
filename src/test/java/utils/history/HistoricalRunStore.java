package utils.history;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Immutable append-log of suite run snapshots.
 *
 * Schema contract (schemaVersion = 1):
 *   runId, timestamp, suite{name,type,environment,branch,commitSha},
 *   summary{healthScore,status,totalTests,passed,failed,skipped},
 *   failures[{testId,failureType,durationMs}],
 *   performance[{page,loadTimeMs}]
 *
 * Files are NEVER overwritten after creation — they are the authoritative
 * source-of-truth from which all derived aggregates are computed.
 */
public final class HistoricalRunStore {
    private static final Logger LOG = LoggerFactory.getLogger(HistoricalRunStore.class);
    static final String RUNS_DIR = "reports/history/runs";
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    private HistoricalRunStore() {}

    @SuppressWarnings("unchecked")
    public static String write(JSONObject analytics) {
        if (analytics == null) return null;
        try {
            Files.createDirectories(Paths.get(RUNS_DIR));
            String runId = TS_FMT.format(Instant.now());
            Path out = Paths.get(RUNS_DIR, runId + ".json");

            // Never overwrite — append epoch suffix if collision
            if (Files.exists(out)) {
                runId = runId + "-" + System.currentTimeMillis();
                out   = Paths.get(RUNS_DIR, runId + ".json");
            }

            Files.writeString(out, buildSnapshot(runId, analytics).toJSONString());
            LOG.info("Run snapshot written: {}", out.getFileName());
            return runId;
        } catch (Exception e) {
            LOG.error("HistoricalRunStore.write failed: {}", e.getMessage());
            return null;
        }
    }

    public static List<JSONObject> readAll() {
        try {
            Path dir = Paths.get(RUNS_DIR);
            if (!Files.exists(dir)) return List.of();
            try (Stream<Path> stream = Files.list(dir)) {
                return stream
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(HistoricalRunStore::parseQuiet)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            LOG.warn("HistoricalRunStore.readAll failed: {}", e.getMessage());
            return List.of();
        }
    }

    public static void applyRetentionPolicy() {
        try {
            Path dir = Paths.get(RUNS_DIR);
            if (!Files.exists(dir)) return;

            int maxDays      = HistoryConfig.getRetentionDays();
            int maxSnapshots = HistoryConfig.getMaxRunSnapshots();
            Instant cutoff   = Instant.now().minusSeconds((long) maxDays * 86_400L);

            List<Path> files;
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream.filter(p -> p.toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(Collectors.toList());
            }

            int deleted = 0;
            for (int i = 0; i < files.size(); i++) {
                Path f = files.get(i);
                boolean overCount = (files.size() - deleted) > maxSnapshots;
                boolean overAge   = Files.getLastModifiedTime(f).toInstant().isBefore(cutoff);
                if (overCount || overAge) {
                    Files.deleteIfExists(f);
                    deleted++;
                }
            }
            if (deleted > 0) LOG.info("Run retention: trimmed {} snapshot(s)", deleted);
        } catch (Exception e) {
            LOG.warn("Retention policy failed: {}", e.getMessage());
        }
    }

    // ── Schema builder ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static JSONObject buildSnapshot(String runId, JSONObject a) {
        JSONObject snap = new JSONObject();
        snap.put("schemaVersion", 1L);
        snap.put("runId",         runId);
        snap.put("timestamp",     Instant.now().toString());

        JSONObject meta  = objOrEmpty(a, "metadata");
        JSONObject suite = new JSONObject();
        suite.put("name",        str(meta, "suite",       "unknown"));
        suite.put("type",        str(meta, "suiteType",   "REGRESSION"));
        suite.put("environment", str(meta, "environment", "local"));
        suite.put("branch",      str(meta, "gitBranch",   "main"));
        suite.put("commitSha",   str(meta, "gitHash",     "n/a"));
        snap.put("suite", suite);

        JSONObject summary = new JSONObject();
        int passed  = num(a, "healthyCount", 0);
        int failed  = num(a, "failedCount",  0);
        summary.put("healthScore", (long) num(a, "overallScore",  0));
        summary.put("status",      str(a, "status", "UNKNOWN"));
        summary.put("totalTests",  (long)(passed + failed));
        summary.put("passed",      (long) passed);
        summary.put("failed",      (long) failed);
        summary.put("skipped",     (long) num(a, "skippedCount", 0));
        snap.put("summary", summary);

        // Failures — minimal projection (full detail in ai/ and artifacts/)
        JSONArray failures = new JSONArray();
        JSONArray srcFails = arr(a, "testFailures");
        for (Object item : srcFails) {
            JSONObject f   = (JSONObject) item;
            JSONObject rec = new JSONObject();
            rec.put("testId",      str(f, "test",        "unknown"));
            rec.put("failureType", str(f, "failureType", "UNKNOWN"));
            rec.put("durationMs",  lng(f, "duration",    0L));
            failures.add(rec);
        }
        snap.put("failures", failures);

        // Performance — slow page snapshots only
        JSONArray perf    = new JSONArray();
        JSONArray srcSlow = arr(a, "slowPages");
        for (Object item : srcSlow) {
            JSONObject p   = (JSONObject) item;
            JSONObject rec = new JSONObject();
            rec.put("page",       str(p, "url",      "unknown"));
            rec.put("loadTimeMs", lng(p, "loadTime", 0L));
            perf.add(rec);
        }
        snap.put("performance", perf);

        return snap;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static JSONObject objOrEmpty(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    @SuppressWarnings("unchecked")
    private static JSONArray arr(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    private static String str(JSONObject obj, String key, String def) {
        Object v = obj.get(key);
        return v != null ? v.toString() : def;
    }

    private static int num(JSONObject obj, String key, int def) {
        Object v = obj.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static long lng(JSONObject obj, String key, long def) {
        Object v = obj.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private static JSONObject parseQuiet(Path path) {
        try {
            return (JSONObject) new JSONParser().parse(Files.readString(path));
        } catch (Exception e) {
            LOG.debug("Could not parse {}: {}", path.getFileName(), e.getMessage());
            return null;
        }
    }
}
