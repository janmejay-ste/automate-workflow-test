package utils.health.trend;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.health.HealthTracker;
import utils.health.semantic.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Append-only writer for {@code semantic_history.jsonl}.
 *
 * Called once per suite, right after the semantic snapshot is built.  Each call
 * appends exactly one JSON object on its own line — no rewrites, no compaction.
 * History grows linearly with run count; rotate or trim externally when needed.
 *
 * The fingerprint scheme matches {@link ErrorClusterer}'s normalization so the
 * same defect produces the same key across runs (even when line:col shifts).
 */
public final class SemanticTrendWriter {

    private static final Logger LOG = LoggerFactory.getLogger(SemanticTrendWriter.class);
    private static final Path   PATH = Paths.get("reports", "trend", "semantic_history.jsonl");

    private SemanticTrendWriter() {}

    /**
     * Build a snapshot from the singleton HealthTracker and append it to the
     * trend file.  Non-fatal: any failure is logged but does not propagate.
     */
    public static void appendCurrentRun() {
        try {
            SemanticHealthSnapshot snap = SemanticHealthSnapshot.from(HealthTracker.get());
            append(toEntry(snap));
        } catch (Exception e) {
            LOG.warn("Failed to append semantic trend entry (non-fatal): {}", e.getMessage());
        }
    }

    /** Write one entry. Creates parent directories on demand. */
    @SuppressWarnings("unchecked")
    public static void append(SemanticTrendEntry e) {
        try {
            Files.createDirectories(PATH.getParent());
            JSONObject row = new JSONObject();
            row.put("runId",               e.runId);
            row.put("timestampMs",         e.timestampMs);
            row.put("suite",               e.suite);
            row.put("environment",         e.environment);

            JSONObject scores = new JSONObject();
            scores.put("product",   e.productHealth);
            scores.put("framework", e.frameworkHealth);
            scores.put("telemetry", e.telemetryConfidence);
            row.put("scores", scores);

            JSONObject telemetry = new JSONObject();
            telemetry.put("unknown", e.unknownTimingCount);
            telemetry.put("valid",   e.validTimingCount);
            row.put("telemetry", telemetry);

            JSONArray rel = new JSONArray();
            for (SemanticTrendEntry.ReliabilityRow r : e.reliability) {
                JSONObject ro = new JSONObject();
                ro.put("phase",       r.phase);
                ro.put("successes",   r.successes);
                ro.put("failures",    r.failures);
                ro.put("percentage",  r.percentage);
                rel.add(ro);
            }
            row.put("reliability", rel);

            JSONArray clusters = new JSONArray();
            for (SemanticTrendEntry.ClusterRow c : e.clusters) {
                JSONObject co = new JSONObject();
                co.put("fingerprint", c.fingerprint);
                co.put("title",       c.title);
                co.put("domain",      c.domain);
                co.put("severity",    c.severity);
                co.put("count",       c.count);
                clusters.add(co);
            }
            row.put("clusters", clusters);

            JSONObject byDomain = new JSONObject();
            byDomain.putAll(e.errorCountByDomain);
            row.put("errorCountByDomain", byDomain);

            // One object per line — JSONL.  Append in binary so the line ending
            // is stable across OSes.
            String line = row.toJSONString() + "\n";
            Files.write(PATH, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOG.info("Appended semantic trend entry to {}", PATH);
        } catch (Exception ex) {
            LOG.warn("Failed to append semantic trend entry: {}", ex.getMessage());
        }
    }

    /**
     * Convert a live SemanticHealthSnapshot into the persisted trend entry shape.
     */
    public static SemanticTrendEntry toEntry(SemanticHealthSnapshot snap) {
        HealthTracker t = HealthTracker.get();
        String runId = Instant.now().toString();
        long   ts    = System.currentTimeMillis();

        List<SemanticTrendEntry.ReliabilityRow> reliability = new ArrayList<>();
        for (PhaseReliability pr : snap.reliabilities) {
            reliability.add(new SemanticTrendEntry.ReliabilityRow(
                    pr.phase, pr.successes, pr.failures, pr.percentage));
        }

        List<SemanticTrendEntry.ClusterRow> clusters = new ArrayList<>();
        for (ErrorCluster c : snap.clusters) {
            clusters.add(new SemanticTrendEntry.ClusterRow(
                    fingerprint(c),
                    c.title,
                    c.domain.label,
                    c.severity.label,
                    c.count));
        }

        Map<String, Integer> byDomain = new LinkedHashMap<>();
        for (Map.Entry<FailureDomain, Integer> e : snap.errorCountByDomain.entrySet()) {
            byDomain.put(e.getKey().label, e.getValue());
        }

        return new SemanticTrendEntry(
                runId, ts, t.getSuiteName(), t.getEnvironment(),
                snap.scores.productHealth,
                snap.scores.frameworkHealth,
                snap.scores.telemetryConfidence,
                snap.unknownTimingCount,
                snap.validTimingCount,
                reliability,
                clusters,
                byDomain);
    }

    /**
     * Stable recurrence key for a cluster.  Uses domain + normalized title so
     * webpack hash drift / line-number drift across runs does not break recurrence
     * tracking.  Matches the spirit of ErrorClusterer's fingerprint without
     * needing the original raw message.
     */
    private static String fingerprint(ErrorCluster c) {
        String norm = c.title == null ? "" : c.title
                .toLowerCase()
                .replaceAll("[0-9]+", "N")
                .replaceAll("\\.[a-f0-9]{6,}", ".<hash>")
                .replaceAll("\\s+", " ")
                .trim();
        return c.domain.label.toLowerCase() + "│" + norm;
    }
}
