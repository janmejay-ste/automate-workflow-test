package utils.health.trend;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Read-only loader for {@code semantic_history.jsonl}.
 *
 * Tolerant of malformed/partial lines (skips them silently with debug logging) —
 * one corrupted append must never destroy the rest of the history.
 *
 * Consumers (PDF, dashboard, future Slack digest) call {@link #readAll()} or
 * {@link #readRecent(int)} — never parse the file themselves.
 */
public final class SemanticTrendReader {

    private static final Logger LOG = LoggerFactory.getLogger(SemanticTrendReader.class);
    private static final Path   PATH = Paths.get("reports", "trend", "semantic_history.jsonl");

    private SemanticTrendReader() {}

    /** Returns the full history in insertion order (oldest → newest). */
    public static List<SemanticTrendEntry> readAll() {
        List<SemanticTrendEntry> out = new ArrayList<>();
        if (!Files.exists(PATH)) return out;
        try {
            for (String line : Files.readAllLines(PATH, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                SemanticTrendEntry e = parseLine(line);
                if (e != null) out.add(e);
            }
        } catch (Exception ex) {
            LOG.debug("Failed to read semantic history: {}", ex.getMessage());
        }
        return out;
    }

    /** Convenience: last {@code n} entries (newest last).  Returns fewer if the file is shorter. */
    public static List<SemanticTrendEntry> readRecent(int n) {
        List<SemanticTrendEntry> all = readAll();
        if (n >= all.size()) return all;
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    /** The most recent entry, or {@code null} if no history. */
    public static SemanticTrendEntry latest() {
        List<SemanticTrendEntry> all = readAll();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** The entry immediately prior to the most recent, or {@code null}. */
    public static SemanticTrendEntry previous() {
        List<SemanticTrendEntry> all = readAll();
        return all.size() < 2 ? null : all.get(all.size() - 2);
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static SemanticTrendEntry parseLine(String line) {
        try {
            JSONObject row = (JSONObject) new JSONParser().parse(line);
            String runId    = asString(row.get("runId"), "");
            long   ts       = asLong  (row.get("timestampMs"), 0L);
            String suite    = asString(row.get("suite"), "");
            String env      = asString(row.get("environment"), "");

            JSONObject scores = (JSONObject) row.get("scores");
            int product   = scores == null ? 0 : asInt(scores.get("product"),   0);
            int framework = scores == null ? 0 : asInt(scores.get("framework"), 0);
            int telemetry = scores == null ? 0 : asInt(scores.get("telemetry"), 0);

            JSONObject telemetryBlock = (JSONObject) row.get("telemetry");
            int unknownTiming = telemetryBlock == null ? 0 : asInt(telemetryBlock.get("unknown"), 0);
            int validTiming   = telemetryBlock == null ? 0 : asInt(telemetryBlock.get("valid"),   0);

            List<SemanticTrendEntry.ReliabilityRow> reliability = new ArrayList<>();
            JSONArray relArr = (JSONArray) row.get("reliability");
            if (relArr != null) {
                for (Object o : relArr) {
                    if (!(o instanceof JSONObject ro)) continue;
                    reliability.add(new SemanticTrendEntry.ReliabilityRow(
                            asString(ro.get("phase"), ""),
                            asInt(ro.get("successes"), 0),
                            asInt(ro.get("failures"),  0),
                            asDouble(ro.get("percentage"), -1.0)));
                }
            }

            List<SemanticTrendEntry.ClusterRow> clusters = new ArrayList<>();
            JSONArray clArr = (JSONArray) row.get("clusters");
            if (clArr != null) {
                for (Object o : clArr) {
                    if (!(o instanceof JSONObject co)) continue;
                    clusters.add(new SemanticTrendEntry.ClusterRow(
                            asString(co.get("fingerprint"), ""),
                            asString(co.get("title"),       ""),
                            asString(co.get("domain"),      ""),
                            asString(co.get("severity"),    ""),
                            asInt(co.get("count"), 0)));
                }
            }

            Map<String, Integer> byDomain = new LinkedHashMap<>();
            JSONObject domObj = (JSONObject) row.get("errorCountByDomain");
            if (domObj != null) {
                for (Object k : domObj.keySet()) {
                    byDomain.put(String.valueOf(k), asInt(domObj.get(k), 0));
                }
            }

            return new SemanticTrendEntry(runId, ts, suite, env,
                    product, framework, telemetry,
                    unknownTiming, validTiming,
                    reliability, clusters, byDomain);
        } catch (Exception ex) {
            LOG.debug("Skipping malformed semantic trend line: {}", ex.getMessage());
            return null;
        }
    }

    private static String asString(Object o, String def) { return o == null ? def : String.valueOf(o); }
    private static int    asInt(Object o, int def)       { return o instanceof Number n ? n.intValue()    : def; }
    private static long   asLong(Object o, long def)     { return o instanceof Number n ? n.longValue()   : def; }
    private static double asDouble(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }
}
