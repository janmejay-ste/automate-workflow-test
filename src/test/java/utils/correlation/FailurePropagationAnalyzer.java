package utils.correlation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.correlation.dto.CorrelatedFailureDto;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes how failures propagate through the workflow dependency graph.
 *
 * Produces a human-readable propagation chain that explains how a root failure
 * spread to downstream workflows. This is the structural narrative behind the
 * cascade — not just "what failed" but "how it spread".
 *
 * Example output:
 *   Login outage → Session invalidation → Dashboard failures → Workflow creation failures
 */
public final class FailurePropagationAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(FailurePropagationAnalyzer.class);

    public static final class PropagationChain {
        public final String       rootTestId;
        public final List<String> chainSteps;      // ordered: root → leaf (workflow names)
        public final List<String> chainTestIds;    // ordered: root → leaf (test IDs)
        public final int          chainLength;
        public final String       narrative;       // "Login → Session → Dashboard → Workflow"

        private PropagationChain(String root, List<String> steps,
                                  List<String> testIds, String narrative) {
            this.rootTestId   = root;
            this.chainSteps   = Collections.unmodifiableList(steps);
            this.chainTestIds = Collections.unmodifiableList(testIds);
            this.chainLength  = steps.size();
            this.narrative    = narrative;
        }
    }

    private FailurePropagationAnalyzer() {}

    /**
     * Builds propagation chains for all correlated failures that have downstream impact.
     *
     * @param correlatedFailures output from FailureCorrelationEngine
     * @return chains sorted by length descending (longest cascade first)
     */
    public static List<PropagationChain> analyze(List<CorrelatedFailureDto> correlatedFailures) {
        if (correlatedFailures == null || correlatedFailures.isEmpty()) return List.of();

        WorkflowDependencyGraph graph = new WorkflowDependencyGraph();
        List<PropagationChain> chains = new ArrayList<>();

        for (CorrelatedFailureDto cf : correlatedFailures) {
            if (cf.downstreamFailures == null || cf.downstreamFailures.isEmpty()) continue;

            List<String> chain = buildChain(cf.rootTestId, cf.downstreamFailures, graph);
            if (chain.size() < 2) continue;

            List<String> workflowChain = chain.stream()
                    .map(WorkflowDependencyGraph::workflowOf)
                    .collect(Collectors.toList());

            String narrative = String.join(" → ", workflowChain);
            chains.add(new PropagationChain(cf.rootTestId, workflowChain, chain, narrative));
        }

        chains.sort((a, b) -> Integer.compare(b.chainLength, a.chainLength));
        return chains;
    }

    /**
     * Builds a linear propagation path from root through downstream failures.
     * Uses BFS to find the longest path through the failed set.
     */
    private static List<String> buildChain(String root, List<String> downstream,
                                             WorkflowDependencyGraph graph) {
        Set<String> failedSet = new LinkedHashSet<>();
        failedSet.add(root);
        failedSet.addAll(downstream);

        // BFS to find longest path from root through failed tests
        List<String> bestChain = new ArrayList<>();
        bestChain.add(root);

        Queue<List<String>> paths = new LinkedList<>();
        paths.add(new ArrayList<>(List.of(root)));

        while (!paths.isEmpty()) {
            List<String> currentPath = paths.poll();
            String current           = currentPath.get(currentPath.size() - 1);

            List<String> nextFailed = DependencyRegistry.downstreamOf(current).stream()
                    .filter(failedSet::contains)
                    .filter(t -> !currentPath.contains(t)) // cycle protection
                    .collect(Collectors.toList());

            if (nextFailed.isEmpty()) {
                if (currentPath.size() > bestChain.size()) {
                    bestChain = currentPath;
                }
            } else {
                for (String next : nextFailed) {
                    List<String> newPath = new ArrayList<>(currentPath);
                    newPath.add(next);
                    paths.add(newPath);
                }
            }
        }
        return bestChain;
    }
}
