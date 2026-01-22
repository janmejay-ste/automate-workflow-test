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

    public static void ensureBase() {
        try {
            Files.createDirectories(Paths.get(BASE));
        } catch (Exception ex) {
            LOG.debug("Failed to create trend base dir: {}", ex.getMessage());
        }
    }

    /**
     * Append a run score to the CSV. Creates file with header if missing.
     */
    public static void appendRun(int score) {
        ensureBase();
        Path p = Paths.get(CSV);
        boolean exists = Files.exists(p);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(p.toFile(), true))) {
            if (!exists) {
                bw.write("Run,Score\n");
            }
            int nextRun = readScores().size() + 1;
            bw.write(nextRun + "," + score + "\n");
            bw.flush();
        } catch (Exception ex) {
            LOG.warn("Failed to append run to {}: {}", CSV, ex.getMessage());
        }
    }

    /**
     * Read numeric score history from the CSV. Returns empty list if none.
     */
    public static List<Integer> readScores() {
        List<Integer> out = new ArrayList<>();
        try {
            Path p = Paths.get(CSV);
            if (!Files.exists(p)) return out;
            List<String> lines = Files.readAllLines(p);
            for (String line : lines) {
                if (line == null) continue;
                String s = line.trim();
                if (s.isEmpty()) continue;
                // skip header lines that start with non-digit
                if (s.matches("[A-Za-z].*")) continue;
                String[] parts = s.split("\\s*,\\s*");
                if (parts.length == 0) continue;
                String last = parts[parts.length - 1];
                try {
                    out.add(Integer.parseInt(last));
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
     * Compatibility method to provide same output as original TrendExporter.readStatsFromCsv
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
                        if (line == null) continue;
                        String s = line.trim();
                        if (s.isEmpty()) continue;
                        if (s.matches("[A-Za-z].*")) continue;
                        if (s.startsWith("\uFEFF")) s = s.substring(1);
                        String[] parts = s.split("\\s*,\\s*");
                        List<Integer> nums = new ArrayList<>();
                        for (String part : parts) {
                            try { nums.add(Integer.parseInt(part)); } catch (NumberFormatException nfe) { }
                        }
                        if (nums.isEmpty()) continue;
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
                            if (sum > failed + running) healthy = sum - failed - running;
                        }
                        return new int[]{failed, running, healthy};
                    }
                }
            } catch (Exception ex) {
                LOG.debug("Failed reading {}: {}", p, ex.getMessage());
            }
        }
        return new int[]{0,0,0};
    }
}
