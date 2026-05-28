package utils.correlation;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Loads and provides access to manually-declared test/workflow dependencies.
 *
 * Dependency declaration is intentionally manual. Automatic graph inference
 * would create false cascade chains and hide real regressions. Declare what
 * you know; let the engine work from that stable foundation.
 *
 * Configuration file: config/workflow-dependencies.json
 * Schema:
 * {
 *   "LoginTest.verifyLogin": [],
 *   "AuthenticatedFlowTest": ["LoginTest.verifyLogin"],
 *   "CreateConnectWorkflowTest": ["LoginTest.verifyLogin", "DashboardNavigationTest"]
 * }
 *
 * Keys are testClass.methodName or testClass (class-level dependency).
 * Values are arrays of upstream dependency IDs that must pass for this test to be valid.
 *
 * If the config file is missing, the registry operates with zero declared
 * dependencies (all tests treated as independent). This is safe — it just
 * means no cascade detection fires.
 */
public final class DependencyRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(DependencyRegistry.class);
    private static final String CONFIG_PATH = "config/workflow-dependencies.json";

    // Singleton — loaded once per JVM session
    private static volatile Map<String, List<String>> graph;

    private DependencyRegistry() {}

    /**
     * Returns the full dependency map: testId → list of upstream dependency IDs.
     * Never returns null — returns empty map if config is absent or malformed.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, List<String>> getGraph() {
        if (graph != null) return graph;
        synchronized (DependencyRegistry.class) {
            if (graph != null) return graph;
            graph = load();
        }
        return graph;
    }

    /**
     * Returns the upstream dependencies for a given test ID.
     * Returns empty list if the test has no declared dependencies.
     */
    public static List<String> dependenciesOf(String testId) {
        return getGraph().getOrDefault(testId, List.of());
    }

    /**
     * Returns all test IDs that have a direct dependency on the given upstream.
     * Used to find downstream failures when an upstream test fails.
     */
    public static List<String> downstreamOf(String upstreamTestId) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : getGraph().entrySet()) {
            if (entry.getValue().contains(upstreamTestId)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Returns the full transitive downstream set of a given upstream test.
     * BFS traversal — terminates even if cycles exist (cycle-safe).
     */
    public static Set<String> transitiveDownstreamOf(String upstreamTestId) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue  = new LinkedList<>(downstreamOf(upstreamTestId));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.add(current)) {
                queue.addAll(downstreamOf(current));
            }
        }
        return visited;
    }

    /**
     * Returns true if the test has any declared upstream dependencies.
     */
    public static boolean hasDependencies(String testId) {
        return !dependenciesOf(testId).isEmpty();
    }

    /**
     * Returns the set of all known test IDs in the registry.
     */
    public static Set<String> allRegisteredTests() {
        return Collections.unmodifiableSet(getGraph().keySet());
    }

    /** Forces a reload from disk. Useful in tests or after config changes. */
    public static void reload() {
        synchronized (DependencyRegistry.class) {
            graph = load();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> load() {
        if (!Files.exists(Paths.get(CONFIG_PATH))) {
            LOG.info("DependencyRegistry: {} not found — operating with zero dependencies. "
                    + "Create this file to enable cascade detection.", CONFIG_PATH);
            return Map.of();
        }
        try {
            Object parsed = new JSONParser().parse(Files.readString(Paths.get(CONFIG_PATH)));
            if (!(parsed instanceof JSONObject)) return Map.of();
            JSONObject json = (JSONObject) parsed;

            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Object key : json.keySet()) {
                String testId   = key.toString();
                Object depsObj  = json.get(key);
                List<String> deps = new ArrayList<>();
                if (depsObj instanceof JSONArray) {
                    for (Object dep : (JSONArray) depsObj) deps.add(dep.toString());
                }
                result.put(testId, Collections.unmodifiableList(deps));
            }
            LOG.info("DependencyRegistry: loaded {} test dependency declarations from {}",
                    result.size(), CONFIG_PATH);
            return Collections.unmodifiableMap(result);

        } catch (Exception e) {
            LOG.warn("DependencyRegistry: failed to parse {} — operating with zero dependencies: {}",
                    CONFIG_PATH, e.getMessage());
            return Map.of();
        }
    }
}
