package utils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Interprets metrics into high-level risk categories for the dashboard.
 */
public class RiskInterpreter {

    public static HealthPolicy.ReleaseStatus interpret(int score, double smokePassRate, int criticalBugs,
            double regressionPassRate, int locatorSamples) {
        // Priority 1: Smoke Failures > 10%
        if (smokePassRate < 0.90)
            return HealthPolicy.ReleaseStatus.BLOCKED;

        // Priority 2: Critical Product Bugs
        if (criticalBugs > 0)
            return HealthPolicy.ReleaseStatus.BLOCKED;

        // Priority 3: Regression Failures
        if (regressionPassRate < 1.0)
            return HealthPolicy.ReleaseStatus.AT_RISK;

        // Priority 4: Health Score Floors
        if (score < HealthPolicy.POOR_MIN)
            return HealthPolicy.ReleaseStatus.AT_RISK;
        if (score < HealthPolicy.HEALTHY_MIN)
            return HealthPolicy.ReleaseStatus.WARNING;

        return HealthPolicy.ReleaseStatus.READY;
    }

    @SuppressWarnings("unchecked")
    public static JSONObject getStabilityMetrics() {
        JSONObject metrics = new JSONObject();
        try {
            Path historyPath = Paths.get("reports/trend/history.json");
            if (!Files.exists(historyPath))
                return metrics;

            JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(historyPath));
            JSONArray runs = (JSONArray) root.get("runs");

            // Calculate flakiness (tests that failed recently but passed in history)
            Set<String> allFailingTests = new HashSet<>();
            int totalRuns = Math.min(runs.size(), 10);
            for (int i = 0; i < totalRuns; i++) {
                JSONObject run = (JSONObject) runs.get(i);
                JSONObject runMetrics = (JSONObject) run.get("metrics");
                JSONArray failures = (JSONArray) runMetrics.get("failures");
                if (failures != null) {
                    for (Object f : failures) {
                        allFailingTests.add(((JSONObject) f).get("test").toString());
                    }
                }
            }
            Map<String, Object> history = new HashMap<>();
            history.put("totalRuns", (long) runs.size());
            history.put("flakyTestCount", (long) allFailingTests.size());
            metrics.put("history", history);
            metrics.put("windowSize", totalRuns);
        } catch (Exception e) {
            // ignore
        }
        return metrics;
    }
}
