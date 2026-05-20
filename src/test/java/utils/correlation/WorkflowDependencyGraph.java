package utils.correlation;

import java.util.*;

/**
 * Structural understanding of the product's test dependency topology.
 *
 * This is the key difference between test-level intelligence and workflow-level
 * intelligence. A flat list of failures tells you nothing about causality.
 * A dependency graph tells you which failures are symptoms of which root causes.
 *
 * Built from DependencyRegistry at construction time. Provides graph-level
 * queries used by FailureCorrelationEngine and CascadeFailureDetector.
 */
public final class WorkflowDependencyGraph {

    /** Represents one test node in the dependency graph. */
    public static final class Node {
        public final String       testId;
        public final List<String> upstreamDeps;     // direct upstream dependencies
        public final List<String> downstreamDeps;   // direct downstream dependents
        public final int          depth;             // depth from root (0 = no deps)

        private Node(String testId, List<String> upstream, List<String> downstream, int depth) {
            this.testId        = testId;
            this.upstreamDeps  = Collections.unmodifiableList(upstream);
            this.downstreamDeps = Collections.unmodifiableList(downstream);
            this.depth         = depth;
        }
    }

    private final Map<String, Node> nodes;

    public WorkflowDependencyGraph() {
        Map<String, List<String>> raw = DependencyRegistry.getGraph();
        nodes = buildNodes(raw);
    }

    /**
     * Returns the Node for a test, or null if the test is not registered.
     */
    public Node getNode(String testId) {
        return nodes.get(testId);
    }

    /**
     * Returns all test IDs in the graph.
     */
    public Set<String> allNodes() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * Returns the root nodes — tests with no upstream dependencies.
     * If a root test fails, all descendants are potentially symptomatic.
     */
    public List<String> roots() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            if (e.getValue().upstreamDeps.isEmpty()) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    /**
     * Returns the workflow name for a test — derived from the class name prefix.
     * E.g. "LoginTest.verifyLogin" → "Login"
     */
    public static String workflowOf(String testId) {
        if (testId == null) return "Unknown";
        String className = testId.contains(".") ? testId.substring(0, testId.lastIndexOf('.')) : testId;
        // Strip trailing "Test" suffix for readability
        return className.endsWith("Test") ? className.substring(0, className.length() - 4) : className;
    }

    /**
     * Returns the full transitive downstream set for a set of failed root tests.
     * Used by cascade detection: if these roots failed, these descendants are suspect.
     */
    public Set<String> transitiveDownstream(Collection<String> rootFailures) {
        Set<String> result = new LinkedHashSet<>();
        for (String root : rootFailures) {
            result.addAll(DependencyRegistry.transitiveDownstreamOf(root));
        }
        return result;
    }

    /**
     * Given a set of currently failed tests, returns only those that are NOT
     * explainable by a dependency failure. These are independent failures worth
     * investigating on their own.
     */
    public Set<String> independentFailures(Set<String> failedTests) {
        Set<String> downstream = transitiveDownstream(failedTests);
        Set<String> independent = new LinkedHashSet<>(failedTests);
        independent.removeAll(downstream);
        return independent;
    }

    /**
     * Returns true if testB is reachable downstream from testA.
     */
    public boolean isDownstreamOf(String testA, String testB) {
        return DependencyRegistry.transitiveDownstreamOf(testA).contains(testB);
    }

    /**
     * Returns all tests that share the same workflow cluster as the given test.
     * A cluster is defined as tests that are connected by dependency chains.
     */
    public Set<String> clusterOf(String testId) {
        Set<String> cluster = new LinkedHashSet<>();
        cluster.add(testId);
        // Collect all transitive upstreams
        Queue<String> queue = new LinkedList<>(DependencyRegistry.dependenciesOf(testId));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (cluster.add(current)) {
                queue.addAll(DependencyRegistry.dependenciesOf(current));
            }
        }
        // Collect all transitive downstreams
        cluster.addAll(DependencyRegistry.transitiveDownstreamOf(testId));
        return cluster;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Map<String, Node> buildNodes(Map<String, List<String>> raw) {
        if (raw.isEmpty()) return Map.of();

        // Build downstream index
        Map<String, List<String>> downstreamIndex = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            String testId = entry.getKey();
            downstreamIndex.putIfAbsent(testId, new ArrayList<>());
            for (String dep : entry.getValue()) {
                downstreamIndex.computeIfAbsent(dep, k -> new ArrayList<>()).add(testId);
            }
        }

        // Compute depth via BFS from roots
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (String testId : raw.keySet()) {
            if (raw.get(testId).isEmpty()) {
                depth.put(testId, 0);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
                String testId = entry.getKey();
                if (depth.containsKey(testId)) continue;
                boolean allUpstreamKnown = entry.getValue().stream().allMatch(depth::containsKey);
                if (allUpstreamKnown) {
                    int maxUpstreamDepth = entry.getValue().stream()
                            .mapToInt(u -> depth.getOrDefault(u, 0))
                            .max().orElse(0);
                    depth.put(testId, maxUpstreamDepth + 1);
                    changed = true;
                }
            }
        }

        // Build nodes
        Map<String, Node> result = new LinkedHashMap<>();
        for (String testId : raw.keySet()) {
            List<String> upstream   = raw.getOrDefault(testId, List.of());
            List<String> downstream = downstreamIndex.getOrDefault(testId, List.of());
            int d = depth.getOrDefault(testId, 0);
            result.put(testId, new Node(testId, upstream, downstream, d));
        }
        return Collections.unmodifiableMap(result);
    }
}
