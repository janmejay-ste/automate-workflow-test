package utils.governance;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.governance.dto.AuditEntryDto;
import utils.history.trends.TrendConfidenceCalculator;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes a permanent, structured audit trail of every orchestration decision.
 *
 * Every suppression, prioritization, minimization, and strategy selection is
 * recorded so that governance reviewers can answer "why did the system do X?"
 * without inspecting code or logs.
 *
 * Storage: reports/history/governance/audit/<runId>.json
 * Accumulates across runs — append-only, never deleted automatically.
 */
public final class DecisionAuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionAuditLogger.class);
    static final String AUDIT_DIR = "reports/history/governance/audit";

    // Decision type constants — used by all orchestration components
    public static final String DECISION_SUPPRESS_RETRY     = "SUPPRESS_RETRY";
    public static final String DECISION_AUTHORIZE_RETRY    = "AUTHORIZE_RETRY";
    public static final String DECISION_PRIORITIZE_SUITE   = "PRIORITIZE_SUITE";
    public static final String DECISION_MINIMIZE_SUITE     = "MINIMIZE_SUITE";
    public static final String DECISION_EXPAND_COVERAGE    = "EXPAND_COVERAGE";
    public static final String DECISION_ISOLATE_EXECUTOR   = "ISOLATE_EXECUTOR";
    public static final String DECISION_STRATEGY_SELECTED  = "STRATEGY_SELECTED";
    public static final String DECISION_SAFETY_BLOCK       = "SAFETY_BLOCK";

    // Outcome constants
    public static final String OUTCOME_ADVISORY            = "ADVISORY";
    public static final String OUTCOME_BLOCKED_BY_SAFETY   = "BLOCKED_BY_SAFETY";
    public static final String OUTCOME_OVERRIDDEN          = "OVERRIDDEN";

    private DecisionAuditLogger() {}

    /**
     * Logs a single orchestration decision.
     *
     * @param decisionType  one of the DECISION_* constants
     * @param target        workflow or test name affected
     * @param reason        human-readable rationale
     * @param signals       list of signal names that contributed
     * @param confidence    0.0–1.0 confidence in the decision
     * @param outcome       ADVISORY | BLOCKED_BY_SAFETY | OVERRIDDEN
     */
    @SuppressWarnings("unchecked")
    public static AuditEntryDto log(String decisionType, String target, String reason,
                                     List<String> signals, double confidence, String outcome) {
        AuditEntryDto entry = buildEntry(decisionType, target, reason, signals, confidence, outcome, null);
        persist(entry);
        return entry;
    }

    public static AuditEntryDto logOverridden(String decisionType, String target, String reason,
                                               List<String> signals, double confidence,
                                               String overriddenBy) {
        AuditEntryDto entry = buildEntry(decisionType, target, reason, signals, confidence,
                OUTCOME_OVERRIDDEN, overriddenBy);
        persist(entry);
        return entry;
    }

    /**
     * Loads all audit entries for the current or a specific run.
     *
     * @param runId if null, loads the most recent run file
     */
    @SuppressWarnings("unchecked")
    public static List<AuditEntryDto> load(String runId) {
        List<AuditEntryDto> result = new ArrayList<>();
        try {
            Path dir = Paths.get(AUDIT_DIR);
            if (!Files.exists(dir)) return result;

            Path target = runId != null
                    ? dir.resolve(runId + ".json")
                    : mostRecentFile(dir);

            if (target == null || !Files.exists(target)) return result;

            JSONArray arr = (JSONArray) new JSONParser().parse(Files.readString(target));
            for (Object item : arr) {
                JSONObject o = (JSONObject) item;
                AuditEntryDto e = new AuditEntryDto();
                e.timestamp       = str(o, "timestamp");
                e.runId           = str(o, "runId");
                e.decisionType    = str(o, "decisionType");
                e.target          = str(o, "target");
                e.reason          = str(o, "reason");
                e.confidence      = dbl(o, "confidence");
                e.confidenceLabel = str(o, "confidenceLabel");
                e.outcome         = str(o, "outcome");
                e.overriddenBy    = str(o, "overriddenBy");
                JSONArray sigs = (JSONArray) o.get("signals");
                if (sigs != null) {
                    e.signals = new ArrayList<>();
                    for (Object s : sigs) e.signals.add(s.toString());
                }
                result.add(e);
            }
        } catch (Exception e) {
            LOG.warn("DecisionAuditLogger.load failed: {}", e.getMessage());
        }
        return result;
    }

    /** Loads all audit entries across all run files (full history). */
    @SuppressWarnings("unchecked")
    public static List<AuditEntryDto> loadAll() {
        List<AuditEntryDto> result = new ArrayList<>();
        try {
            Path dir = Paths.get(AUDIT_DIR);
            if (!Files.exists(dir)) return result;
            Files.list(dir).filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            JSONArray arr = (JSONArray) new JSONParser().parse(Files.readString(p));
                            for (Object item : arr) {
                                JSONObject o = (JSONObject) item;
                                AuditEntryDto e = new AuditEntryDto();
                                e.timestamp    = str(o, "timestamp");
                                e.runId        = str(o, "runId");
                                e.decisionType = str(o, "decisionType");
                                e.target       = str(o, "target");
                                e.reason       = str(o, "reason");
                                e.confidence   = dbl(o, "confidence");
                                e.outcome      = str(o, "outcome");
                                result.add(e);
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            LOG.warn("DecisionAuditLogger.loadAll failed: {}", e.getMessage());
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static AuditEntryDto buildEntry(String decisionType, String target, String reason,
                                             List<String> signals, double confidence,
                                             String outcome, String overriddenBy) {
        AuditEntryDto entry    = new AuditEntryDto();
        entry.timestamp        = Instant.now().toString();
        entry.runId            = currentRunId();
        entry.decisionType     = decisionType;
        entry.target           = target;
        entry.reason           = reason;
        entry.signals          = signals != null ? signals : List.of();
        entry.confidence       = confidence;
        entry.confidenceLabel  = TrendConfidenceCalculator.label(confidence);
        entry.outcome          = outcome;
        entry.overriddenBy     = overriddenBy;
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static void persist(AuditEntryDto entry) {
        try {
            Path dir  = Paths.get(AUDIT_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve(entry.runId + ".json");

            JSONArray existing = new JSONArray();
            if (Files.exists(file)) {
                try {
                    existing = (JSONArray) new JSONParser().parse(Files.readString(file));
                } catch (Exception ignored) {}
            }

            JSONObject o = new JSONObject();
            o.put("timestamp",       entry.timestamp);
            o.put("runId",           entry.runId);
            o.put("decisionType",    entry.decisionType);
            o.put("target",          entry.target != null ? entry.target : "");
            o.put("reason",          entry.reason != null ? entry.reason : "");
            o.put("confidence",      entry.confidence);
            o.put("confidenceLabel", entry.confidenceLabel);
            o.put("outcome",         entry.outcome != null ? entry.outcome : OUTCOME_ADVISORY);
            if (entry.overriddenBy != null) o.put("overriddenBy", entry.overriddenBy);

            JSONArray sigs = new JSONArray();
            if (entry.signals != null) entry.signals.forEach(sigs::add);
            o.put("signals", sigs);

            existing.add(o);

            try (FileWriter fw = new FileWriter(file.toFile())) {
                fw.write(existing.toJSONString());
            }
        } catch (Exception e) {
            LOG.warn("DecisionAuditLogger.persist failed: {}", e.getMessage());
        }
    }

    private static String currentRunId() {
        // Stable within a JVM session — reuse if already set, otherwise generate
        return System.getProperty("governance.runId",
                "run-" + Instant.now().toString().substring(0, 10) + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static Path mostRecentFile(Path dir) throws Exception {
        return Files.list(dir)
                .filter(p -> p.toString().endsWith(".json"))
                .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                .orElse(null);
    }

    private static String str(JSONObject o, String k) { Object v = o.get(k); return v != null ? v.toString() : null; }
    private static double dbl(JSONObject o, String k) {
        Object v = o.get(k); return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }
}
