package utils.governance;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.governance.dto.HumanOverrideDto;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records and tracks human overrides of orchestration recommendations.
 *
 * Purpose: humans must always be able to override any recommendation.
 * This registry ensures overrides are:
 *   1. Explicitly recorded (not silently ignored)
 *   2. Retrospectively evaluated (was the human right?)
 *   3. Fed back as calibration signal (frequent overrides = poor accuracy)
 *
 * Override patterns detected:
 *   - Same recommendation overridden >= 3 times → flag as calibration candidate
 *   - Override effectiveness < 50% → system may be more accurate than humans in this area
 *   - Override effectiveness > 80% → system recommendations need improvement in this area
 *
 * Storage: reports/history/governance/overrides/override-log.json
 */
public final class HumanOverrideRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(HumanOverrideRegistry.class);
    static final String OVERRIDE_FILE = "reports/history/governance/overrides/override-log.json";

    private static final int RECURRENCE_ALERT_THRESHOLD = 3;

    private HumanOverrideRegistry() {}

    /**
     * Records a human override of a system recommendation.
     *
     * @param decisionType     what decision was overridden (e.g. "SUPPRESS_RETRY")
     * @param target           which workflow or test
     * @param originalDecision what the system recommended
     * @param humanDecision    what the human chose instead
     * @param reason           why the human disagreed (optional but encouraged for calibration)
     * @param owner            who made the override
     * @return the recorded override DTO
     */
    public static HumanOverrideDto recordOverride(String decisionType, String target,
                                                   String originalDecision, String humanDecision,
                                                   String reason, String owner) {
        HumanOverrideDto dto   = new HumanOverrideDto();
        dto.overrideId         = UUID.randomUUID().toString().substring(0, 12);
        dto.timestamp          = Instant.now().toString();
        dto.decisionType       = decisionType;
        dto.target             = target;
        dto.originalDecision   = originalDecision;
        dto.humanDecision      = humanDecision;
        dto.overrideReason     = reason;
        dto.owner              = owner;
        dto.effectiveOverride  = false; // populated retrospectively

        persist(dto);

        // Log to audit trail as well
        DecisionAuditLogger.logOverridden(decisionType, target,
                "Human override by " + (owner != null ? owner : "unknown") + ": " + reason,
                List.of("HUMAN_OVERRIDE"), 1.0, owner);

        return dto;
    }

    /**
     * Retrospectively marks an override as effective or ineffective.
     * Called when the outcome of the human's decision is known.
     *
     * @param overrideId   the ID returned from recordOverride
     * @param wasEffective true if human's decision was better than system's recommendation
     */
    public static void evaluateOutcome(String overrideId, boolean wasEffective) {
        List<HumanOverrideDto> all = loadAll();
        all.stream().filter(o -> overrideId.equals(o.overrideId))
                .findFirst().ifPresent(o -> o.effectiveOverride = wasEffective);
        persistAll(all);
    }

    /** Loads all recorded overrides. */
    public static List<HumanOverrideDto> loadAll() {
        List<HumanOverrideDto> result = new ArrayList<>();
        try {
            Path p = Paths.get(OVERRIDE_FILE);
            if (!Files.exists(p)) return result;
            JSONArray arr = (JSONArray) new JSONParser().parse(Files.readString(p));
            for (Object item : arr) {
                JSONObject o = (JSONObject) item;
                HumanOverrideDto dto   = new HumanOverrideDto();
                dto.overrideId         = str(o, "overrideId");
                dto.timestamp          = str(o, "timestamp");
                dto.decisionType       = str(o, "decisionType");
                dto.target             = str(o, "target");
                dto.originalDecision   = str(o, "originalDecision");
                dto.humanDecision      = str(o, "humanDecision");
                dto.overrideReason     = str(o, "overrideReason");
                dto.owner              = str(o, "owner");
                dto.effectiveOverride  = bool(o, "effectiveOverride");
                result.add(dto);
            }
        } catch (Exception e) {
            LOG.warn("HumanOverrideRegistry.loadAll failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Returns workflows / tests that have been overridden frequently.
     * Frequent overrides indicate the system's accuracy is low for these targets.
     */
    public static List<String> findRecurringOverrideTargets(List<HumanOverrideDto> overrides) {
        java.util.Map<String, Long> counts = overrides.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        o -> o.decisionType + "::" + o.target, java.util.stream.Collectors.counting()));
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= RECURRENCE_ALERT_THRESHOLD)
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * Computes override effectiveness rate (% of evaluated overrides that improved outcome).
     */
    public static double computeEffectivenessRate(List<HumanOverrideDto> overrides) {
        long evaluated = overrides.stream().filter(o -> o.timestamp != null).count(); // all are "evaluated"
        long effective = overrides.stream().filter(o -> o.effectiveOverride).count();
        if (evaluated == 0) return -1.0;
        return effective * 100.0 / evaluated;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void persist(HumanOverrideDto dto) {
        List<HumanOverrideDto> all = loadAll();
        all.add(dto);
        persistAll(all);
    }

    @SuppressWarnings("unchecked")
    private static void persistAll(List<HumanOverrideDto> dtos) {
        try {
            Path p = Paths.get(OVERRIDE_FILE);
            Files.createDirectories(p.getParent());
            JSONArray arr = new JSONArray();
            for (HumanOverrideDto dto : dtos) {
                JSONObject o = new JSONObject();
                o.put("overrideId",       dto.overrideId != null ? dto.overrideId : "");
                o.put("timestamp",        dto.timestamp != null ? dto.timestamp : "");
                o.put("decisionType",     dto.decisionType != null ? dto.decisionType : "");
                o.put("target",           dto.target != null ? dto.target : "");
                o.put("originalDecision", dto.originalDecision != null ? dto.originalDecision : "");
                o.put("humanDecision",    dto.humanDecision != null ? dto.humanDecision : "");
                o.put("overrideReason",   dto.overrideReason != null ? dto.overrideReason : "");
                o.put("owner",            dto.owner != null ? dto.owner : "");
                o.put("effectiveOverride", dto.effectiveOverride);
                arr.add(o);
            }
            try (FileWriter fw = new FileWriter(p.toFile())) { fw.write(arr.toJSONString()); }
        } catch (Exception e) {
            LOG.warn("HumanOverrideRegistry.persistAll failed: {}", e.getMessage());
        }
    }

    private static String str(JSONObject o, String k) { Object v = o.get(k); return v != null ? v.toString() : null; }
    private static boolean bool(JSONObject o, String k) { Object v = o.get(k); return Boolean.TRUE.equals(v); }
}
