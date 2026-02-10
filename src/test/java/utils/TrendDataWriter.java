package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class TrendDataWriter {
    private static final Logger LOG = LoggerFactory.getLogger(TrendDataWriter.class);
    private static final String BASE = "reports/trend";
    private static final String CSV = BASE + "/health_history.csv";
    private static final String HEADER = "Run,Score,Penalty,Status,JsErrors,FailedTests";

    public static void ensureBase() {
        try {
            Files.createDirectories(Paths.get(BASE));
        } catch (Exception ex) {
            LOG.debug("Failed to create trend base dir: {}", ex.getMessage());
        }
    }

    /**
     * Append a run score to the CSV (legacy single-column support).
     */
    public static void appendRun(int score) {
        appendRun(score, Math.max(0, 100 - score), "UNKNOWN", 0, 0);
    }

    /**
     * Append a run with extended metadata to the CSV.
     */
    public static void appendRun(int score, int penalty, String status, int jsErrors, int failedTests) {
        ensureBase();
        Path p = Paths.get(CSV);
        boolean exists = Files.exists(p);
        boolean needsUpgrade = false;

        if (exists) {
            // Check if existing CSV has the old 2-column header
            try {
                List<String> lines = Files.readAllLines(p);
                if (!lines.isEmpty()) {
                    String header = lines.get(0).trim();
                    if (header.equals("Run,Score")) {
                        needsUpgrade = true;
                    }
                }
            } catch (Exception ex) {
                LOG.debug("Failed to check CSV header: {}", ex.getMessage());
            }
        }

        if (needsUpgrade) {
            upgradeCsv(p);
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(p.toFile(), true))) {
            if (!exists) {
                bw.write(HEADER + "\n");
            }
            int nextRun = countDataRows(p) + 1;
            bw.write(String.format("%d,%d,%d,%s,%d,%d%n",
                    nextRun, score, penalty,
                    status != null ? status : "UNKNOWN",
                    jsErrors, failedTests));
            bw.flush();
        } catch (Exception ex) {
            LOG.warn("Failed to append run to {}: {}", CSV, ex.getMessage());
        }
    }

    /**
     * Upgrade old 2-column CSV to new 6-column format in place.
     */
    private static void upgradeCsv(Path p) {
        try {
            List<String> lines = Files.readAllLines(p);
            List<String> upgraded = new ArrayList<>();
            upgraded.add(HEADER);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty())
                    continue;
                String[] parts = line.split("\\s*,\\s*");
                if (parts.length >= 2) {
                    int run = Integer.parseInt(parts[0]);
                    int score = Integer.parseInt(parts[1]);
                    int penalty = Math.max(0, 100 - score);
                    String status = score >= 80 ? "STABLE" : score >= 50 ? "DEGRADED" : "AT_RISK";
                    // Old rows don't have JS errors or failed test counts
                    upgraded.add(String.format("%d,%d,%d,%s,-1,-1", run, score, penalty, status));
                }
            }
            Files.write(p, upgraded);
            LOG.info("Upgraded CSV from 2-column to 6-column format");
        } catch (Exception ex) {
            LOG.warn("Failed to upgrade CSV: {}", ex.getMessage());
        }
    }

    /**
     * Count data rows (excluding header and empty lines).
     */
    private static int countDataRows(Path p) {
        try {
            List<String> lines = Files.readAllLines(p);
            int count = 0;
            for (String line : lines) {
                if (line == null)
                    continue;
                String s = line.trim();
                if (s.isEmpty() || s.matches("[A-Za-z].*"))
                    continue;
                count++;
            }
            return count;
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * Read numeric score history from the CSV. Returns empty list if none.
     */
    public static List<Integer> readScores() {
        List<Integer> out = new ArrayList<>();
        try {
            Path p = Paths.get(CSV);
            if (!Files.exists(p))
                return out;
            List<String> lines = Files.readAllLines(p);
            for (String line : lines) {
                if (line == null)
                    continue;
                String s = line.trim();
                if (s.isEmpty())
                    continue;
                // skip header lines that start with non-digit
                if (s.matches("[A-Za-z].*"))
                    continue;
                String[] parts = s.split("\\s*,\\s*");
                if (parts.length < 2)
                    continue;
                try {
                    out.add(Integer.parseInt(parts[1]));
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            }
        } catch (Exception ex) {
            LOG.debug("Failed reading scores: {}", ex.getMessage());
        }
        return out;
    }

    /**
     * Read full run history with extended metadata.
     * Returns list of int[]{run, score, penalty, jsErrors, failedTests} + status
     * string.
     */
    public static List<RunData> readFullHistory() {
        List<RunData> out = new ArrayList<>();
        try {
            Path p = Paths.get(CSV);
            if (!Files.exists(p))
                return out;
            List<String> lines = Files.readAllLines(p);
            for (String line : lines) {
                if (line == null)
                    continue;
                String s = line.trim();
                if (s.isEmpty() || s.matches("[A-Za-z].*"))
                    continue;
                String[] parts = s.split("\\s*,\\s*");
                if (parts.length < 2)
                    continue;
                try {
                    int run = Integer.parseInt(parts[0]);
                    int score = Integer.parseInt(parts[1]);
                    int penalty = parts.length > 2 ? Integer.parseInt(parts[2]) : Math.max(0, 100 - score);
                    String status = parts.length > 3 ? parts[3]
                            : (score >= 80 ? "STABLE" : score >= 50 ? "DEGRADED" : "AT_RISK");
                    int jsErrors = parts.length > 4 ? Integer.parseInt(parts[4]) : -1;
                    int failedTests = parts.length > 5 ? Integer.parseInt(parts[5]) : -1;
                    out.add(new RunData(run, score, penalty, status, jsErrors, failedTests));
                } catch (NumberFormatException nfe) {
                    // ignore malformed rows
                }
            }
        } catch (Exception ex) {
            LOG.debug("Failed reading full history: {}", ex.getMessage());
        }
        return out;
    }

    /**
     * Data class holding extended run metadata.
     */
    public static class RunData {
        public final int run;
        public final int score;
        public final int penalty;
        public final String status;
        public final int jsErrors;
        public final int failedTests;

        public RunData(int run, int score, int penalty, String status, int jsErrors, int failedTests) {
            this.run = run;
            this.score = score;
            this.penalty = penalty;
            this.status = status;
            this.jsErrors = jsErrors;
            this.failedTests = failedTests;
        }
    }

    /**
     * Compatibility method to provide same output as original
     * TrendExporter.readStatsFromCsv
     * Returns int[]{failed, running, healthy}
     */
    public static int[] readStatsFromCsv() {
        // Look for project-level health_results.csv first
        List<Path> candidates = List.of(Paths.get("health_results.csv"), Paths.get(CSV));
        for (Path p : candidates) {
            try {
                if (Files.exists(p)) {
                    List<String> lines = Files.readAllLines(p);
                    for (String line : lines) {
                        if (line == null)
                            continue;
                        String s = line.trim();
                        if (s.isEmpty())
                            continue;
                        if (s.matches("[A-Za-z].*"))
                            continue;
                        if (s.startsWith("\uFEFF"))
                            s = s.substring(1);
                        String[] parts = s.split("\\s*,\\s*");
                        List<Integer> nums = new ArrayList<>();
                        for (String part : parts) {
                            try {
                                nums.add(Integer.parseInt(part));
                            } catch (NumberFormatException nfe) {
                            }
                        }
                        if (nums.isEmpty())
                            continue;
                        int failed = 0, running = 0, healthy = 0;
                        if (nums.size() >= 5) {
                            failed = nums.get(1);
                            running = nums.get(3);
                            healthy = nums.get(4);
                        } else if (nums.size() == 4) {
                            failed = nums.get(1);
                            running = nums.get(2);
                            healthy = nums.get(3);
                        } else if (nums.size() == 3) {
                            failed = nums.get(1);
                            healthy = nums.get(2);
                        } else if (nums.size() == 2) {
                            failed = nums.get(1);
                            healthy = nums.get(0);
                        } else if (nums.size() == 1) {
                            healthy = nums.get(0);
                        }
                        if (healthy == 0) {
                            int sum = nums.stream().mapToInt(Integer::intValue).sum();
                            if (sum > failed + running)
                                healthy = sum - failed - running;
                        }
                        return new int[] { failed, running, healthy };
                    }
                }
            } catch (Exception ex) {
                LOG.debug("Failed reading {}: {}", p, ex.getMessage());
            }
        }
        return new int[] { 0, 0, 0 };
    }
}
