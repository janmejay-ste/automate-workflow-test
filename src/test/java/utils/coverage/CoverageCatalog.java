package utils.coverage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code config/coverage-catalog.json} and exposes the declared business
 * outcomes — every {test method → outcome id → verifier → severity} mapping
 * the harness claims to cover.
 *
 * <p>Phase B1 of the scoring-system roadmap. Downstream consumers:</p>
 * <ul>
 *   <li>B2 verifier registry — resolves {@code outcome.verifier} → {@code OutcomeVerifier} impl.</li>
 *   <li>B5 coverage-weighted score — reads {@code outcome.severity} → penalty multiplier.</li>
 *   <li>B6 business-tier release decision — blocks on any CRITICAL outcome's verifier returning FAIL.</li>
 *   <li>D2 evidence dashboard panel — renders catalog coverage % alongside evidence channels.</li>
 * </ul>
 *
 * <h3>Design mirror: {@link utils.governance.ComplexityBudget}</h3>
 * <ul>
 *   <li>Lazy-init singleton via {@link #get()} — loaded once per JVM.</li>
 *   <li>Graceful degraded mode: file missing or malformed → {@code isLoaded()==false},
 *       {@code getLoadError()!=null}, but {@code findById}/{@code all} keep working
 *       (return empty). The system does NOT refuse to start over a missing catalog
 *       — premature B1 enforcement before B2 verifiers exist would be self-blocking.</li>
 *   <li>Read-only: any code that wants to add an outcome edits the file via PR.</li>
 * </ul>
 *
 * <p>The catalog uses JSON rather than the original YAML proposal because the
 * project already pulls in {@code org.json.simple} for every other config file
 * ({@code complexity_budget.json}, {@code performance.baselines.json}). Adding a
 * YAML dep for one file would violate the project's consistent-config-format rule.</p>
 */
public final class CoverageCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(CoverageCatalog.class);
    private static final Path CATALOG_PATH = Paths.get("config", "coverage-catalog.json");

    private static volatile CoverageCatalog INSTANCE;
    private static final Object INIT_LOCK = new Object();

    // ── state ──────────────────────────────────────────────────────────────

    private final boolean loaded;
    private final String  loadError;
    /** Order-preserving so the catalog file's declared order is preserved in iteration. */
    private final Map<String, OutcomeSpec> byId        = new LinkedHashMap<>();
    private final Map<String, OutcomeSpec> byTestClass = new LinkedHashMap<>();
    private final int    schemaVersion;

    // ── public API ─────────────────────────────────────────────────────────

    public static CoverageCatalog get() {
        if (INSTANCE == null) {
            synchronized (INIT_LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new CoverageCatalog();
                }
            }
        }
        return INSTANCE;
    }

    /** True if the catalog file loaded cleanly. False on missing or malformed file. */
    public boolean isLoaded() { return loaded; }

    /** Human-readable load error when {@link #isLoaded()} is false, else null. */
    public String getLoadError() { return loadError; }

    /** Schema version declared in the catalog file. Returns 0 if not loaded. */
    public int schemaVersion() { return schemaVersion; }

    /** Number of outcomes declared. Returns 0 if not loaded. */
    public int size() { return byId.size(); }

    /**
     * Look up an outcome by its declared id (e.g. {@code "connect.created.spreadsheet-to-gmail"}).
     * Returns empty Optional if the catalog isn't loaded or the id isn't declared.
     */
    public Optional<OutcomeSpec> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Look up the outcome a test class is supposed to verify. The catalog
     * currently supports one outcome per test class; if multiple outcomes
     * declare the same test, the first declared wins (and a WARN is logged
     * during init). Returns empty Optional when the test isn't catalogued.
     */
    public Optional<OutcomeSpec> findByTest(String fullyQualifiedTestClassName) {
        if (fullyQualifiedTestClassName == null) return Optional.empty();
        return Optional.ofNullable(byTestClass.get(fullyQualifiedTestClassName));
    }

    /** Read-only iteration of every declared outcome, in catalog order. */
    public List<OutcomeSpec> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    // ── construction ───────────────────────────────────────────────────────

    private CoverageCatalog() {
        // Single-assignment discipline: only set the three final fields at the
        // very end of the constructor. Each control-flow branch updates local
        // variables; no `return` statements until the assignment.
        boolean okLocal = false;
        String  errLocal = null;
        int     svLocal  = 0;
        try {
            if (!Files.exists(CATALOG_PATH)) {
                errLocal = "coverage-catalog.json not found at " + CATALOG_PATH;
                LOG.warn("[CoverageCatalog] {}", errLocal);
            } else {
                String raw = Files.readString(CATALOG_PATH);
                JSONObject root = (JSONObject) new JSONParser().parse(raw);

                svLocal = root.get("schemaVersion") instanceof Number n ? n.intValue() : 0;
                if (svLocal != 1) {
                    // Forward-compat: refuse rather than guess. Mirrors HealthTracker's
                    // schemaVersion handling — version mismatches are surface-able errors.
                    errLocal = "Unsupported schemaVersion: " + svLocal
                             + " (this build understands schemaVersion 1)";
                    LOG.warn("[CoverageCatalog] {}", errLocal);
                } else {
                    JSONArray outcomes = (JSONArray) root.get("outcomes");
                    if (outcomes == null) {
                        errLocal = "No 'outcomes' array in catalog";
                        LOG.warn("[CoverageCatalog] {}", errLocal);
                    } else {
                        loadOutcomes(outcomes);
                        okLocal = true;
                        LOG.info("[CoverageCatalog] Loaded {} outcome(s) from {} (schemaVersion={})",
                                byId.size(), CATALOG_PATH, svLocal);
                    }
                }
            }
        } catch (Exception e) {
            errLocal = "Failed to parse coverage-catalog.json: " + e.getMessage();
            LOG.warn("[CoverageCatalog] {}", errLocal);
        }

        this.loaded        = okLocal;
        this.loadError     = errLocal;
        this.schemaVersion = svLocal;
    }

    /** Splits the outcome-parsing loop out so the constructor stays linear. */
    private void loadOutcomes(JSONArray outcomes) {
        int dupTestWarnings = 0;
        for (Object o : outcomes) {
            if (!(o instanceof JSONObject row)) continue;
            OutcomeSpec spec = OutcomeSpec.fromJson(row);
            if (spec == null) continue;

            // Detect duplicate id — log + skip the dupe. Better to surface than
            // to silently overwrite the prior entry.
            if (byId.containsKey(spec.id)) {
                LOG.warn("[CoverageCatalog] Duplicate outcome id '{}' — keeping first declaration",
                        spec.id);
                continue;
            }
            byId.put(spec.id, spec);

            if (spec.test != null && !spec.test.isBlank()) {
                if (byTestClass.containsKey(spec.test)) {
                    if (++dupTestWarnings <= 3) {  // cap log spam
                        LOG.warn("[CoverageCatalog] Test '{}' declared twice — outcome '{}' "
                               + "will not be findByTest-resolvable",
                                spec.test, spec.id);
                    }
                } else {
                    byTestClass.put(spec.test, spec);
                }
            }
        }
    }

    // ── nested types ───────────────────────────────────────────────────────

    /**
     * One declared business outcome. Immutable record so consumers can't
     * mutate the catalog state by accident.
     */
    public record OutcomeSpec(
            String id,
            String name,
            String owner,
            String severity,            // CRITICAL | HIGH | MEDIUM | LOW
            String verifier,
            String test,
            String businessQuestion
    ) {
        static OutcomeSpec fromJson(JSONObject row) {
            String id = str(row, "id");
            if (id == null || id.isBlank()) {
                LOG.warn("[CoverageCatalog] Skipping outcome with missing 'id': {}", row);
                return null;
            }
            return new OutcomeSpec(
                    id,
                    str(row, "name"),
                    str(row, "owner"),
                    str(row, "severity"),
                    str(row, "verifier"),
                    str(row, "test"),
                    str(row, "businessQuestion"));
        }

        private static String str(JSONObject row, String key) {
            Object v = row.get(key);
            return v == null ? null : String.valueOf(v).trim();
        }

        /** Convenience: numeric severity weight matching B5 coverage-weighted score formula. */
        public double severityWeight() {
            return switch (severity == null ? "" : severity.toUpperCase()) {
                case "CRITICAL" -> 3.0;
                case "HIGH"     -> 2.0;
                case "MEDIUM"   -> 1.0;
                case "LOW"      -> 0.5;
                default          -> 1.0;   // unknown → MEDIUM-equivalent default
            };
        }
    }
}
