package utils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.analytics.AnalyticsCollector;
import utils.health.HealthTracker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Handles persistent history storage in JSON format with schema versioning.
 * V4 Hardened: Maintains last 50 runs + last 5 failed runs.
 * Implements size-based rotation (5MB limit).
 */
public class HistoryJsonWriter {
    private static final Logger LOG = LoggerFactory.getLogger(HistoryJsonWriter.class);
    private static final String HISTORY_FILE = "reports/trend/history.json";
    private static final int MAX_RUNS = 50;
    private static final int MIN_FAILED_RUNS = 5;
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int SCHEMA_VERSION = 2;

    public static void write() {
        JSONObject analytics = AnalyticsCollector.collect();
        HealthTracker tracker = HealthTracker.get();
        appendRun(analytics, tracker.getSuiteName(), tracker.getEnvironment());
    }

    @SuppressWarnings("unchecked")
    public static void appendRun(JSONObject runMetrics, String suite, String environment) {
        try {
            Path historyPath = Paths.get(HISTORY_FILE);
            Files.createDirectories(historyPath.getParent());

            JSONObject root;
            if (Files.exists(historyPath)) {
                String content = Files.readString(historyPath);
                try {
                    root = (JSONObject) new JSONParser().parse(content);
                } catch (Exception e) {
                    LOG.warn("Corrupt history file, resetting: {}", e.getMessage());
                    root = createNewRoot();
                }
            } else {
                root = createNewRoot();
            }

            JSONArray runs = (JSONArray) root.get("runs");

            // Build Run Metadata
            JSONObject runEntry = new JSONObject();
            runEntry.put("runId", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
            runEntry.put("suite", suite);
            runEntry.put("environment", environment);
            runEntry.put("gitBranch", getGitBranch());
            runEntry.put("gitCommit", getGitCommit());
            runEntry.put("metrics", runMetrics);

            // Intelligence: Mark if this run failed
            boolean failed = HealthTracker.get().getTestFailures().size() > 0;
            runEntry.put("failed", failed);

            // Maintain window (V4 Hardened)
            runs.add(0, runEntry);

            // Logic: Keep last 50 runs OR last 5 failures
            List<JSONObject> allRuns = new ArrayList<>();
            for (Object o : runs)
                allRuns.add((JSONObject) o);

            List<JSONObject> failures = allRuns.stream()
                    .filter(r -> (boolean) r.getOrDefault("failed", false))
                    .collect(Collectors.toList());

            List<JSONObject> resultRuns = new ArrayList<>();
            int failureCountRetained = 0;

            for (int i = 0; i < allRuns.size(); i++) {
                JSONObject r = allRuns.get(i);
                boolean isFail = (boolean) r.getOrDefault("failed", false);

                if (i < MAX_RUNS) {
                    resultRuns.add(r);
                    if (isFail)
                        failureCountRetained++;
                } else if (isFail && failureCountRetained < MIN_FAILED_RUNS + MAX_RUNS) { // Keep some extra fails
                    // This is a bit complex for simple rotation.
                    // Simplified: always keep the last 5 failures regardless of position.
                }
            }

            // Refined Rotation logic
            JSONArray finalRuns = new JSONArray();
            List<JSONObject> kept = new ArrayList<>();

            // 1. Keep first 50
            for (int i = 0; i < Math.min(allRuns.size(), MAX_RUNS); i++) {
                kept.add(allRuns.get(i));
            }

            // 2. Ensure last 5 failures are in there
            List<JSONObject> extraFails = failures.stream()
                    .filter(f -> !kept.contains(f))
                    .limit(MIN_FAILED_RUNS)
                    .collect(Collectors.toList());
            kept.addAll(extraFails);

            finalRuns.addAll(kept);
            root.put("runs", finalRuns);

            String jsonOutput = root.toJSONString();

            // Size-based rotation check
            if (jsonOutput.length() > MAX_FILE_SIZE) {
                LOG.warn("History file exceeds 5MB, aggressive pruning applied.");
                while (finalRuns.size() > 20)
                    finalRuns.remove(finalRuns.size() - 1);
                jsonOutput = root.toJSONString();
            }

            Files.writeString(historyPath, jsonOutput);
            LOG.info("History updated at {} ({} runs)", HISTORY_FILE, finalRuns.size());

        } catch (Exception e) {
            LOG.error("Failed to append to history: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static JSONObject createNewRoot() {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("runs", new JSONArray());
        return root;
    }

    private static String getGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String getGitBranch() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").start();
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "main";
        }
    }
}
