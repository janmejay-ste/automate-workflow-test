package utils.governance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code config/complexity_budget.json} and exposes the limits and
 * current-state metadata to runtime callers (snapshot writer, governance
 * panel, platformHealth).
 *
 * <p>Phase A.5.0 of the v8 plan. The budget itself is value-based: enum values
 * are retired by lowest {@code signalValue × observation_frequency}, not by
 * insertion order. Protected values ({@code MISSING}, {@code DISABLED},
 * {@code UNKNOWN}, etc.) survive even at zero observed frequency because they
 * are structural uncertainty states, not telemetry labels.</p>
 *
 * <p>This class is intentionally read-only. The file is the source of truth;
 * any code that wants to violate the budget must go through a PR that edits
 * the file, which makes the change reviewable.</p>
 *
 * <p>Loaded once per JVM via lazy-init {@link #get}. Failure to read the file
 * is logged as a {@code platformHealth.governance} indicator and treated as
 * a permissive default — the system does not refuse to start over a missing
 * budget file (that would be a worse failure mode).</p>
 */
public final class ComplexityBudget {

    private static final Logger LOG = LoggerFactory.getLogger(ComplexityBudget.class);
    private static final Path BUDGET_PATH = Paths.get("config", "complexity_budget.json");

    private static volatile ComplexityBudget INSTANCE;
    private static final Object INIT_LOCK = new Object();

    // ────────────────────────── state ──────────────────────────

    private final boolean loaded;
    private final String loadError;
    private final Map<String, BudgetEntry> entries = new LinkedHashMap<>();
    private final Map<String, EnumTable> enumTables = new LinkedHashMap<>();
    private final GovernanceBudget governance;
    private final String deprecationRule;
    private final String reviewCadence;

    // ────────────────────────── public API ──────────────────────────

    public static ComplexityBudget get() {
        if (INSTANCE == null) {
            synchronized (INIT_LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new ComplexityBudget();
                }
            }
        }
        return INSTANCE;
    }

    /** True if the budget file loaded cleanly. False on missing or malformed file. */
    public boolean isLoaded() { return loaded; }

    /** Human-readable load error if {@link #isLoaded()} is false, else null. */
    public String getLoadError() { return loadError; }

    /**
     * @param key one of: "evidenceChannels", "releaseDecisionCodes",
     *            "platformHealthCategories"
     */
    public BudgetEntry getEntry(String key) {
        return entries.get(key);
    }

    /**
     * @param key one of: "qualityEnum", "reasonEnum", "amplificationClassifications"
     */
    public EnumTable getEnumTable(String key) {
        return enumTables.get(key);
    }

    public GovernanceBudget getGovernance() { return governance; }
    public String getDeprecationRule()      { return deprecationRule; }
    public String getReviewCadence()        { return reviewCadence; }

    /**
     * Checks all known budgets against the supplied current-state map and returns
     * violations. Returns empty list if everything is within budget.
     *
     * @param observedCounts e.g. {@code {"evidenceChannels": 6, "releaseDecisionCodes": 9}}
     */
    public List<Violation> checkViolations(Map<String, Integer> observedCounts) {
        List<Violation> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : observedCounts.entrySet()) {
            BudgetEntry b = entries.get(e.getKey());
            if (b == null) continue;
            int observed = e.getValue();
            if (observed > b.maximum) {
                out.add(new Violation(e.getKey(), observed, b.maximum, "EXCEEDED"));
            } else if (observed == b.maximum) {
                out.add(new Violation(e.getKey(), observed, b.maximum, "AT_LIMIT"));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public JSONObject toJson() {
        JSONObject root = new JSONObject();
        root.put("loaded",          loaded);
        if (loadError != null) root.put("loadError", loadError);
        JSONObject entriesJson = new JSONObject();
        for (Map.Entry<String, BudgetEntry> e : entries.entrySet()) {
            entriesJson.put(e.getKey(), e.getValue().toJson());
        }
        root.put("entries", entriesJson);
        JSONObject enumsJson = new JSONObject();
        for (Map.Entry<String, EnumTable> e : enumTables.entrySet()) {
            enumsJson.put(e.getKey(), e.getValue().toJson());
        }
        root.put("enumTables", enumsJson);
        if (governance != null) root.put("governanceBudget", governance.toJson());
        if (deprecationRule != null) root.put("deprecationRule", deprecationRule);
        if (reviewCadence != null) root.put("reviewCadence", reviewCadence);
        return root;
    }

    // ────────────────────────── inner types ──────────────────────────

    public static final class BudgetEntry {
        public final int maximum;
        public final Integer current;
        public final List<String> currentValues;
        public final String additionsRequire;

        BudgetEntry(int maximum, Integer current, List<String> currentValues, String additionsRequire) {
            this.maximum         = maximum;
            this.current         = current;
            this.currentValues   = currentValues == null ? List.of() : Collections.unmodifiableList(currentValues);
            this.additionsRequire = additionsRequire;
        }

        @SuppressWarnings("unchecked")
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("maximum", maximum);
            if (current != null) o.put("current", current);
            if (!currentValues.isEmpty()) {
                JSONArray arr = new JSONArray();
                arr.addAll(currentValues);
                o.put("currentValues", arr);
            }
            if (additionsRequire != null) o.put("additionsRequire", additionsRequire);
            return o;
        }
    }

    public static final class EnumTable {
        public final int maximum;
        public final boolean finalSet;
        public final Map<String, EnumValueMeta> values;

        EnumTable(int maximum, boolean finalSet, Map<String, EnumValueMeta> values) {
            this.maximum = maximum;
            this.finalSet = finalSet;
            this.values  = Collections.unmodifiableMap(values);
        }

        public Set<String> protectedValues() {
            Set<String> p = new java.util.LinkedHashSet<>();
            values.forEach((k, v) -> { if (v.isProtected) p.add(k); });
            return p;
        }

        public boolean contains(String value) { return values.containsKey(value); }

        @SuppressWarnings("unchecked")
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("maximum",  maximum);
            o.put("finalSet", finalSet);
            o.put("size",     values.size());
            JSONObject valuesJson = new JSONObject();
            for (Map.Entry<String, EnumValueMeta> e : values.entrySet()) {
                JSONObject v = new JSONObject();
                v.put("signalValue", e.getValue().signalValue);
                v.put("protected",   e.getValue().isProtected);
                valuesJson.put(e.getKey(), v);
            }
            o.put("values", valuesJson);
            return o;
        }
    }

    public static final class EnumValueMeta {
        public final String  signalValue;   // HIGH | MEDIUM | LOW
        public final boolean isProtected;
        EnumValueMeta(String signalValue, boolean isProtected) {
            this.signalValue = signalValue;
            this.isProtected = isProtected;
        }
    }

    public static final class GovernanceBudget {
        public final int prApprovalStepsMax;
        public final int enumChangeReviewDaysMax;
        public final int budgetViolationOverridesPerQuarter;
        public final int overridesUsed;
        public final int approvalStepsObserved;
        public final int avgEnumReviewDays;

        GovernanceBudget(int prApprovalStepsMax, int enumChangeReviewDaysMax,
                         int budgetViolationOverridesPerQuarter,
                         int overridesUsed, int approvalStepsObserved, int avgEnumReviewDays) {
            this.prApprovalStepsMax                = prApprovalStepsMax;
            this.enumChangeReviewDaysMax           = enumChangeReviewDaysMax;
            this.budgetViolationOverridesPerQuarter = budgetViolationOverridesPerQuarter;
            this.overridesUsed                     = overridesUsed;
            this.approvalStepsObserved             = approvalStepsObserved;
            this.avgEnumReviewDays                 = avgEnumReviewDays;
        }

        /** Friction score 0.0..1.0 — higher = more governance overhead. */
        public double frictionScore() {
            double approvalFriction = (double) approvalStepsObserved / Math.max(1, prApprovalStepsMax);
            double reviewFriction   = (double) avgEnumReviewDays / Math.max(1, enumChangeReviewDaysMax);
            double overrideFriction = (double) overridesUsed / Math.max(1, budgetViolationOverridesPerQuarter);
            return Math.min(1.0, (approvalFriction + reviewFriction + overrideFriction) / 3.0);
        }

        @SuppressWarnings("unchecked")
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("prApprovalStepsMax",                prApprovalStepsMax);
            o.put("enumChangeReviewDaysMax",           enumChangeReviewDaysMax);
            o.put("budgetViolationOverridesPerQuarter", budgetViolationOverridesPerQuarter);
            o.put("overridesUsed",                     overridesUsed);
            o.put("approvalStepsObserved",             approvalStepsObserved);
            o.put("avgEnumReviewDays",                 avgEnumReviewDays);
            o.put("frictionScore",                     frictionScore());
            return o;
        }
    }

    public record Violation(String budgetKey, int observed, int maximum, String severity) {
        @SuppressWarnings("unchecked")
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("budgetKey", budgetKey);
            o.put("observed",  observed);
            o.put("maximum",   maximum);
            o.put("severity",  severity);
            return o;
        }
    }

    // ────────────────────────── private constructor ──────────────────────────

    private ComplexityBudget() {
        boolean ok = false;
        String err = null;
        GovernanceBudget gov = null;
        String deprecation = null;
        String cadence = null;

        try {
            if (!Files.exists(BUDGET_PATH)) {
                err = "complexity_budget.json not found at " + BUDGET_PATH.toAbsolutePath();
            } else {
                JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(BUDGET_PATH));
                parseBudgetEntries(root);
                parseEnumTables(root);

                Object g = root.get("governanceBudget");
                if (g instanceof JSONObject) gov = parseGovernance((JSONObject) g);

                Object dp = root.get("deprecationPolicy");
                if (dp instanceof JSONObject) {
                    JSONObject dpo = (JSONObject) dp;
                    Object rule = dpo.get("rule");          if (rule    != null) deprecation = rule.toString();
                    Object cad  = dpo.get("reviewCadence"); if (cad     != null) cadence     = cad.toString();
                }

                ok = true;
            }
        } catch (Exception e) {
            err = "Failed to parse complexity_budget.json: " + e.getMessage();
            LOG.warn("[ComplexityBudget] {}", err);
        }

        this.loaded            = ok;
        this.loadError         = err;
        this.governance        = gov;
        this.deprecationRule   = deprecation;
        this.reviewCadence     = cadence;

        if (ok) {
            LOG.info("[ComplexityBudget] Loaded {} entries, {} enum tables", entries.size(), enumTables.size());
        }
    }

    @SuppressWarnings("unchecked")
    private void parseBudgetEntries(JSONObject root) {
        String[] keys = { "evidenceChannels", "releaseDecisionCodes", "platformHealthCategories" };
        for (String k : keys) {
            Object obj = root.get(k);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject o = (JSONObject) obj;
            int max = numericValue(o.get("maximum"), 0);
            Integer cur = (o.get("current") instanceof Number) ? ((Number) o.get("current")).intValue() : null;
            List<String> currentValues = new ArrayList<>();
            Object cv = o.get("currentValues");
            if (cv instanceof JSONArray) {
                for (Object v : (JSONArray) cv) if (v != null) currentValues.add(v.toString());
            }
            String additions = o.get("additionsRequire") == null ? null : o.get("additionsRequire").toString();
            entries.put(k, new BudgetEntry(max, cur, currentValues, additions));
        }
    }

    @SuppressWarnings("unchecked")
    private void parseEnumTables(JSONObject root) {
        String[] keys = { "qualityEnum", "reasonEnum", "amplificationClassifications" };
        for (String k : keys) {
            Object obj = root.get(k);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject o = (JSONObject) obj;
            int max = numericValue(o.get("maximum"), 0);
            boolean finalSet = Boolean.TRUE.equals(o.get("finalSet"));
            Map<String, EnumValueMeta> values = new LinkedHashMap<>();
            Object v = o.get("values");
            if (v instanceof JSONObject) {
                JSONObject vo = (JSONObject) v;
                for (Object eo : vo.entrySet()) {
                    Map.Entry<String, Object> me = (Map.Entry<String, Object>) eo;
                    if (!(me.getValue() instanceof JSONObject)) continue;
                    JSONObject meta = (JSONObject) me.getValue();
                    String signalValue = meta.get("signalValue") == null ? "MEDIUM" : meta.get("signalValue").toString();
                    boolean isProtected = Boolean.TRUE.equals(meta.get("protected"));
                    values.put(me.getKey(), new EnumValueMeta(signalValue, isProtected));
                }
            }
            enumTables.put(k, new EnumTable(max, finalSet, values));
        }
    }

    private GovernanceBudget parseGovernance(JSONObject g) {
        int prMax     = numericValue(g.get("prApprovalStepsMax"),                3);
        int reviewMax = numericValue(g.get("enumChangeReviewDaysMax"),           7);
        int overrides = numericValue(g.get("budgetViolationOverridesPerQuarter"), 4);
        int overridesUsed       = 0;
        int approvalStepsObs    = 0;
        int avgEnumReview       = 0;
        Object usage = g.get("currentQuarterUsage");
        if (usage instanceof JSONObject) {
            JSONObject u = (JSONObject) usage;
            overridesUsed    = numericValue(u.get("overridesUsed"),         0);
            approvalStepsObs = numericValue(u.get("approvalStepsObserved"), 0);
            avgEnumReview    = numericValue(u.get("avgEnumReviewDays"),     0);
        }
        return new GovernanceBudget(prMax, reviewMax, overrides, overridesUsed, approvalStepsObs, avgEnumReview);
    }

    private static int numericValue(Object o, int defaultValue) {
        return (o instanceof Number) ? ((Number) o).intValue() : defaultValue;
    }
}
