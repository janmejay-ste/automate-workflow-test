package utils.governance;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.governance.dto.SuppressionRecordDto;
import utils.orchestration.dto.OrchestrationSnapshotDto;
import utils.orchestration.dto.RetryStrategyDto;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks suppression history across runs for governance visibility.
 *
 * Problem being solved: without this, retry suppression is a black box.
 * Engineers cannot tell if suppressions are hiding real regressions or
 * correctly filtering noise.
 *
 * This tracker answers:
 *   - "How many times was X suppressed?"
 *   - "Was any suppression later found to be hiding a real failure?"
 *   - "What is our suppression accuracy rate?"
 *
 * Storage: reports/history/governance/suppression/suppression-log.json
 * Append-only. Outcome fields are populated via markValidated() / markFalseSuppression().
 */
public final class SuppressionAuditTracker {

    private static final Logger LOG = LoggerFactory.getLogger(SuppressionAuditTracker.class);
    static final String SUPPRESSION_FILE = "reports/history/governance/suppression/suppression-log.json";

    private SuppressionAuditTracker() {}

    /**
     * Records all suppressions from the current run's retry recommendations.
     *
     * @param snapshot from OrchestrationSnapshotBuilder (contains retry recommendations)
     */
    public static void record(OrchestrationSnapshotDto snapshot) {
        if (snapshot == null || snapshot.executionPlan == null
                || snapshot.executionPlan.retryRecommendations == null) return;

        for (RetryStrategyDto r : snapshot.executionPlan.retryRecommendations) {
            if (!r.shouldRetry) {
                String reason = r.suppressIfCascade ? "CASCADE_DOWNSTREAM" : r.failureClass;
                recordSuppression(r.testId, reason);
            }
        }
    }

    /**
     * Marks a previous suppression as validated (was genuinely noise — suppression was correct).
     *
     * @param target       the test or workflow that was suppressed
     * @param reason       the suppression reason (to identify the specific record)
     */
    public static void markValidated(String target, String reason) {
        updateRecord(target, reason, true, false);
    }

    /**
     * Marks a previous suppression as a false suppression (it was hiding a real failure).
     * This is the most important signal for recalibrating suppression rules.
     *
     * @param target the test that was suppressed but turned out to have a real failure
     * @param reason the suppression reason
     */
    public static void markFalseSuppression(String target, String reason) {
        updateRecord(target, reason, false, true);
    }

    /** Loads all suppression records from persistent storage. */
    @SuppressWarnings("unchecked")
    public static List<SuppressionRecordDto> loadAll() {
        List<SuppressionRecordDto> result = new ArrayList<>();
        try {
            Path p = Paths.get(SUPPRESSION_FILE);
            if (!Files.exists(p)) return result;
            JSONArray arr = (JSONArray) new JSONParser().parse(Files.readString(p));
            for (Object item : arr) {
                JSONObject o = (JSONObject) item;
                SuppressionRecordDto r = new SuppressionRecordDto();
                r.suppressionKey     = str(o, "suppressionKey");
                r.target             = str(o, "target");
                r.suppressionReason  = str(o, "suppressionReason");
                r.timesSuppressed    = intVal(o, "timesSuppressed");
                r.firstSuppressed    = str(o, "firstSuppressed");
                r.lastSuppressed     = str(o, "lastSuppressed");
                r.eventuallyValidated= bool(o, "eventuallyValidated");
                r.hidRealRegression  = bool(o, "hidRealRegression");
                r.validatedCount     = intVal(o, "validatedCount");
                r.falseSuppressionCount = intVal(o, "falseSuppressionCount");
                result.add(r);
            }
        } catch (Exception e) {
            LOG.warn("SuppressionAuditTracker.loadAll failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Computes the suppression accuracy rate across all records.
     * accuracy = validated / (validated + false) — only when outcomes are known.
     */
    public static double computeAccuracyPct(List<SuppressionRecordDto> records) {
        int validated = records.stream().mapToInt(r -> r.validatedCount).sum();
        int falsed    = records.stream().mapToInt(r -> r.falseSuppressionCount).sum();
        int total     = validated + falsed;
        return total == 0 ? -1.0 : (validated * 100.0 / total);
    }

    /** Returns records where suppression hid a real regression. */
    public static List<SuppressionRecordDto> falseSuppressionsOnly(List<SuppressionRecordDto> all) {
        return all.stream().filter(r -> r.hidRealRegression || r.falseSuppressionCount > 0).toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void recordSuppression(String target, String reason) {
        List<SuppressionRecordDto> all = loadAll();
        String key = suppressionKey(target, reason);
        SuppressionRecordDto existing = all.stream()
                .filter(r -> key.equals(r.suppressionKey)).findFirst().orElse(null);

        if (existing == null) {
            existing = new SuppressionRecordDto();
            existing.suppressionKey  = key;
            existing.target          = target;
            existing.suppressionReason = reason;
            existing.timesSuppressed = 0;
            existing.firstSuppressed = Instant.now().toString();
            all.add(existing);
        }
        existing.timesSuppressed++;
        existing.lastSuppressed = Instant.now().toString();
        persist(all);
    }

    private static void updateRecord(String target, String reason, boolean validated, boolean falseSupp) {
        List<SuppressionRecordDto> all = loadAll();
        String key = suppressionKey(target, reason);
        all.stream().filter(r -> key.equals(r.suppressionKey)).findFirst().ifPresent(r -> {
            if (validated) {
                r.validatedCount++;
                r.eventuallyValidated = true;
            }
            if (falseSupp) {
                r.falseSuppressionCount++;
                r.hidRealRegression = true;
            }
        });
        persist(all);
    }

    private static String suppressionKey(String target, String reason) {
        return (target != null ? target : "") + "::" + (reason != null ? reason : "");
    }

    @SuppressWarnings("unchecked")
    private static void persist(List<SuppressionRecordDto> records) {
        try {
            Path p = Paths.get(SUPPRESSION_FILE);
            Files.createDirectories(p.getParent());
            JSONArray arr = new JSONArray();
            for (SuppressionRecordDto r : records) {
                JSONObject o = new JSONObject();
                o.put("suppressionKey",      r.suppressionKey);
                o.put("target",              r.target != null ? r.target : "");
                o.put("suppressionReason",   r.suppressionReason != null ? r.suppressionReason : "");
                o.put("timesSuppressed",     r.timesSuppressed);
                o.put("firstSuppressed",     r.firstSuppressed != null ? r.firstSuppressed : "");
                o.put("lastSuppressed",      r.lastSuppressed != null ? r.lastSuppressed : "");
                o.put("eventuallyValidated", r.eventuallyValidated);
                o.put("hidRealRegression",   r.hidRealRegression);
                o.put("validatedCount",      r.validatedCount);
                o.put("falseSuppressionCount", r.falseSuppressionCount);
                arr.add(o);
            }
            try (FileWriter fw = new FileWriter(p.toFile())) { fw.write(arr.toJSONString()); }
        } catch (Exception e) {
            LOG.warn("SuppressionAuditTracker.persist failed: {}", e.getMessage());
        }
    }

    private static String str(JSONObject o, String k) { Object v = o.get(k); return v != null ? v.toString() : null; }
    private static int    intVal(JSONObject o, String k) { Object v = o.get(k); return v instanceof Number ? ((Number)v).intValue() : 0; }
    private static boolean bool(JSONObject o, String k) { Object v = o.get(k); return Boolean.TRUE.equals(v); }
}
