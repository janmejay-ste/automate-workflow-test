package utils.history.calibration;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.calibration.FeedbackCaptureService.FeedbackEntry;
import utils.history.calibration.FeedbackCaptureService.FeedbackType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Analytics QA layer — tracks signal quality per signal type over time.
 *
 * Every signal type is measured across:
 *   triggered        — how many times this alert fired
 *   validatedUseful  — how many times it led to useful action (from feedback)
 *   falsePositives   — feedback-confirmed false alerts
 *   ignored          — alert was seen but no action taken (fatigue indicator)
 *
 * Computed metrics:
 *   falsePositiveRate  = falsePositives / triggered
 *   ignoredRate        = ignored / triggered
 *   usefulnessRate     = validatedUseful / triggered
 *
 * A signal with usefulnessRate < 0.3 AND ignoredRate > 0.5 is a candidate
 * for removal or threshold re-calibration.
 *
 * Storage: reports/history/calibration/validation/{signalType}.json
 * Built from feedback records — never edited manually.
 */
public final class SignalValidationReport {
    private static final Logger LOG = LoggerFactory.getLogger(SignalValidationReport.class);
    static final String VALIDATION_DIR = "reports/history/calibration/validation";

    public static final class ValidationSummary {
        public String signalType;
        public int    triggered;
        public int    validatedUseful;
        public int    falsePositives;
        public int    ignored;
        public double falsePositiveRate;   // [0.0, 1.0]
        public double ignoredRate;          // [0.0, 1.0]
        public double usefulnessRate;       // [0.0, 1.0]
        public String qualityAssessment;    // TRUSTED | MODERATE | NOISY | INSUFFICIENT_DATA
        public String lastUpdated;
    }

    private SignalValidationReport() {}

    /**
     * Records one triggered alert. Call once each time a signal fires.
     */
    @SuppressWarnings("unchecked")
    public static void recordTriggered(String signalType) {
        updateCount(signalType, "triggered", 1);
    }

    /**
     * Records a validated outcome from feedback. Positive = useful, negative = false positive.
     */
    public static void recordValidated(String signalType, boolean wasUseful) {
        if (wasUseful) {
            updateCount(signalType, "validatedUseful", 1);
        } else {
            updateCount(signalType, "falsePositives", 1);
        }
    }

    /** Records an ignored alert (seen but no action taken). */
    public static void recordIgnored(String signalType) {
        updateCount(signalType, "ignored", 1);
    }

    /**
     * Computes a ValidationSummary for a given signal type.
     * Reads both persisted counters and live feedback records.
     */
    public static ValidationSummary compute(String signalType) {
        ValidationSummary s = loadCounters(signalType);

        // Augment from feedback records (source of truth for validated/false positive/ignored)
        List<FeedbackEntry> feedback = FeedbackCaptureService.load(signalType);
        for (FeedbackEntry e : feedback) {
            if (e.feedbackType == FeedbackType.FALSE_ALERT)   s.falsePositives++;
            if (e.feedbackType == FeedbackType.IGNORED_ALERT) s.ignored++;
            if (e.feedbackType == FeedbackType.TREND_USEFUL && e.value)  s.validatedUseful++;
            if (e.feedbackType == FeedbackType.AI_CORRECT    && e.value)  s.validatedUseful++;
            if (e.feedbackType == FeedbackType.RELEASE_ACCURATE && e.value) s.validatedUseful++;
        }

        // Recompute derived metrics
        if (s.triggered > 0) {
            s.falsePositiveRate = round2((double) s.falsePositives / s.triggered);
            s.ignoredRate       = round2((double) s.ignored        / s.triggered);
            s.usefulnessRate    = round2((double) s.validatedUseful / s.triggered);
        }

        s.qualityAssessment = assessQuality(s);
        s.lastUpdated       = Instant.now().toString();
        return s;
    }

    /** Computes ValidationSummary for all known signal types. */
    public static List<ValidationSummary> computeAll() {
        List<ValidationSummary> result = new ArrayList<>();
        // From validation dir
        try {
            Path dir = Paths.get(VALIDATION_DIR);
            if (Files.exists(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(p -> p.toString().endsWith(".json"))
                          .map(p -> p.getFileName().toString().replace(".json", ""))
                          .map(SignalValidationReport::compute)
                          .filter(Objects::nonNull)
                          .forEach(result::add);
                }
            }
        } catch (Exception e) {
            LOG.debug("SignalValidationReport.computeAll failed: {}", e.getMessage());
        }
        // From feedback dir (signal types that have feedback but no validation counters yet)
        try {
            Path feedbackDir = Paths.get(FeedbackCaptureService.FEEDBACK_DIR);
            if (Files.exists(feedbackDir)) {
                try (Stream<Path> stream = Files.list(feedbackDir)) {
                    stream.filter(p -> p.toString().endsWith(".json"))
                          .map(p -> p.getFileName().toString().replace(".json", ""))
                          .filter(st -> result.stream().noneMatch(s -> st.equals(s.signalType)))
                          .map(SignalValidationReport::compute)
                          .filter(Objects::nonNull)
                          .forEach(result::add);
                }
            }
        } catch (Exception ignored) {}

        result.sort((a, b) -> Double.compare(a.usefulnessRate, b.usefulnessRate));
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void updateCount(String signalType, String field, int delta) {
        try {
            Files.createDirectories(Paths.get(VALIDATION_DIR));
            Path path = Paths.get(VALIDATION_DIR, sanitize(signalType) + ".json");
            JSONObject record = loadRecord(path, signalType);
            int current = num(record, field, 0);
            record.put(field, (long) (current + delta));
            record.put("lastUpdated", Instant.now().toString());
            Files.writeString(path, record.toJSONString());
        } catch (Exception e) {
            LOG.debug("SignalValidationReport.updateCount failed for {}.{}: {}", signalType, field, e.getMessage());
        }
    }

    private static ValidationSummary loadCounters(String signalType) {
        ValidationSummary s = new ValidationSummary();
        s.signalType = signalType;
        Path path = Paths.get(VALIDATION_DIR, sanitize(signalType) + ".json");
        if (!Files.exists(path)) return s;
        try {
            JSONObject record = (JSONObject) new JSONParser().parse(Files.readString(path));
            s.triggered       = num(record, "triggered",       0);
            s.validatedUseful = num(record, "validatedUseful", 0);
            s.falsePositives  = num(record, "falsePositives",  0);
            s.ignored         = num(record, "ignored",         0);
        } catch (Exception ignored) {}
        return s;
    }

    private static String assessQuality(ValidationSummary s) {
        if (s.triggered < 5) return "INSUFFICIENT_DATA";
        if (s.usefulnessRate >= 0.70 && s.falsePositiveRate <= 0.15) return "TRUSTED";
        if (s.usefulnessRate >= 0.40 && s.falsePositiveRate <= 0.35) return "MODERATE";
        return "NOISY";
    }

    @SuppressWarnings("unchecked")
    private static JSONObject loadRecord(Path path, String signalType) {
        if (Files.exists(path)) {
            try { return (JSONObject) new JSONParser().parse(Files.readString(path)); }
            catch (Exception ignored) {}
        }
        JSONObject rec = new JSONObject();
        rec.put("schemaVersion", 1L);
        rec.put("signalType", signalType);
        rec.put("triggered",       0L);
        rec.put("validatedUseful", 0L);
        rec.put("falsePositives",  0L);
        rec.put("ignored",         0L);
        return rec;
    }

    private static String sanitize(String s) { return s.replaceAll("[^a-zA-Z0-9._-]", "_"); }
    private static int    num(JSONObject o, String k, int def) { Object v = o.get(k); return v instanceof Number ? ((Number) v).intValue() : def; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
