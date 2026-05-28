package utils.history.calibration;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Operationalizes developer feedback for signal quality measurement.
 *
 * Feedback is the only way to distinguish a useful signal from a noisy one.
 * Without it, confidence values are permanently heuristic. With it, confidence
 * becomes empirically validated over time.
 *
 * Feedback types:
 *   AI_CORRECT         — AI root cause analysis matched actual root cause
 *   TREND_USEFUL       — trend signal led to a useful engineering decision
 *   FALSE_ALERT        — alert fired but there was no real problem
 *   IGNORED_ALERT      — alert was seen but ignored (signal fatigue indicator)
 *   RELEASE_ACCURATE   — AI release narrative matched what actually happened in prod
 *
 * Schema (per feedback entry):
 *   {signalType, signalId, feedbackType, value, timestamp, note}
 *
 * Storage: reports/history/calibration/feedback/{signalType}.json
 * Each file is an append-log array of feedback entries for one signal type.
 */
public final class FeedbackCaptureService {
    private static final Logger LOG = LoggerFactory.getLogger(FeedbackCaptureService.class);
    static final String FEEDBACK_DIR = "reports/history/calibration/feedback";

    public enum FeedbackType {
        AI_CORRECT,
        TREND_USEFUL,
        FALSE_ALERT,
        IGNORED_ALERT,
        RELEASE_ACCURATE
    }

    public static final class FeedbackEntry {
        public final String       signalType;
        public final String       signalId;
        public final FeedbackType feedbackType;
        public final boolean      value;        // true = positive, false = negative
        public final String       timestamp;
        public final String       note;         // optional free-text

        public FeedbackEntry(String signalType, String signalId, FeedbackType feedbackType,
                             boolean value, String note) {
            this.signalType   = signalType;
            this.signalId     = signalId;
            this.feedbackType = feedbackType;
            this.value        = value;
            this.timestamp    = Instant.now().toString();
            this.note         = note != null ? note : "";
        }
    }

    private FeedbackCaptureService() {}

    /**
     * Records a feedback entry for a given signal.
     *
     * @param signalType  e.g. "StabilityScore", "RegressionSpike", "AiFailureAnalysis"
     * @param signalId    e.g. testId, page URL, cluster ID
     * @param type        feedback category
     * @param value       true = positive feedback, false = negative
     */
    @SuppressWarnings("unchecked")
    public static void record(String signalType, String signalId,
                               FeedbackType type, boolean value) {
        record(signalType, signalId, type, value, null);
    }

    @SuppressWarnings("unchecked")
    public static void record(String signalType, String signalId,
                               FeedbackType type, boolean value, String note) {
        try {
            Files.createDirectories(Paths.get(FEEDBACK_DIR));
            Path path = Paths.get(FEEDBACK_DIR, sanitize(signalType) + ".json");

            JSONArray entries = loadArray(path);

            JSONObject entry = new JSONObject();
            entry.put("signalType",   signalType);
            entry.put("signalId",     signalId != null ? signalId : "");
            entry.put("feedbackType", type.name());
            entry.put("value",        value);
            entry.put("timestamp",    Instant.now().toString());
            entry.put("note",         note != null ? note : "");
            entries.add(entry);

            Files.writeString(path, entries.toJSONString());
            LOG.debug("FeedbackCaptureService: recorded {} {} for {}/{}", type, value, signalType, signalId);

        } catch (Exception e) {
            LOG.warn("FeedbackCaptureService.record failed for {}: {}", signalType, e.getMessage());
        }
    }

    /**
     * Reads back all feedback entries for a specific signal type.
     */
    public static List<FeedbackEntry> load(String signalType) {
        Path path = Paths.get(FEEDBACK_DIR, sanitize(signalType) + ".json");
        if (!Files.exists(path)) return List.of();
        try {
            JSONArray arr = loadArray(path);
            List<FeedbackEntry> result = new ArrayList<>();
            for (Object item : arr) {
                JSONObject o = (JSONObject) item;
                String st  = str(o, "signalType",   signalType);
                String sid = str(o, "signalId",      "");
                String ft  = str(o, "feedbackType",  "");
                boolean v  = bool(o, "value",         true);
                String n   = str(o, "note",           "");
                try {
                    FeedbackType feedbackType = FeedbackType.valueOf(ft);
                    result.add(new FeedbackEntry(st, sid, feedbackType, v, n));
                } catch (IllegalArgumentException ignored) {}
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Loads all feedback across every signal type.
     */
    public static List<FeedbackEntry> loadAll() {
        try {
            Path dir = Paths.get(FEEDBACK_DIR);
            if (!Files.exists(dir)) return List.of();
            List<FeedbackEntry> all = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> {
                          String signalType = p.getFileName().toString().replace(".json", "");
                          all.addAll(load(signalType));
                      });
            }
            return all;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Reads aiWasCorrect flags from existing AI failure analysis files and imports
     * them as feedback entries. This bridges the existing developerOverride field
     * into the calibration pipeline without duplicating storage.
     */
    @SuppressWarnings("unchecked")
    public static void importAiOverrides() {
        Path aiDir = Paths.get("reports/ai/failures");
        if (!Files.exists(aiDir)) return;
        try (Stream<Path> stream = Files.list(aiDir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    JSONObject rec = (JSONObject) new JSONParser().parse(Files.readString(p));
                    Object overrideObj = rec.get("developerOverride");
                    if (!(overrideObj instanceof JSONObject)) return;
                    JSONObject override = (JSONObject) overrideObj;
                    Object correctObj = override.get("aiWasCorrect");
                    if (correctObj == null) return;  // not yet reviewed

                    String testId = str(rec, "testId", p.getFileName().toString());
                    boolean wasCorrect = Boolean.parseBoolean(correctObj.toString());

                    // Only import if not already captured (avoid duplicates)
                    List<FeedbackEntry> existing = load("AiFailureAnalysis");
                    boolean alreadyRecorded = existing.stream()
                            .anyMatch(e -> testId.equals(e.signalId)
                                    && e.feedbackType == FeedbackType.AI_CORRECT);
                    if (!alreadyRecorded) {
                        record("AiFailureAnalysis", testId, FeedbackType.AI_CORRECT, wasCorrect,
                                "imported from developerOverride");
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            LOG.debug("FeedbackCaptureService.importAiOverrides failed: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static JSONArray loadArray(Path path) {
        if (Files.exists(path)) {
            try {
                Object parsed = new JSONParser().parse(Files.readString(path));
                if (parsed instanceof JSONArray) return (JSONArray) parsed;
            } catch (Exception ignored) {}
        }
        return new JSONArray();
    }

    static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String  str(JSONObject o, String k, String def)   { Object v = o.get(k); return v != null ? v.toString() : def; }
    private static boolean bool(JSONObject o, String k, boolean def)  { Object v = o.get(k); return v instanceof Boolean ? (Boolean) v : def; }
}
