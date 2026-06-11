package utils.health.semantic;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups raw JS error events (as recorded by HealthTracker.recordJsError) into
 * {@link ErrorCluster}s using normalized fingerprints + classifier-assigned domains.
 *
 * Replaces the flat "you have 36 JS errors" panel with named clusters that the
 * dashboard can render as actionable rows.
 */
public final class ErrorClusterer {

    private ErrorClusterer() {}

    /**
     * Cluster a flat list of {context, message} entries.
     *
     * @param events list of maps with keys "context" and "message"
     * @return clusters sorted by severity descending, then count descending
     */
    public static List<ErrorCluster> cluster(List<Map<String, String>> events) {
        if (events == null || events.isEmpty()) return Collections.emptyList();

        // fingerprint → mutable accumulator
        Map<String, Acc> buckets = new LinkedHashMap<>();
        for (Map<String, String> evt : events) {
            String ctx = evt.getOrDefault("context", "");
            String msg = evt.getOrDefault("message", "");
            String fp  = fingerprint(ctx, msg);
            Acc acc = buckets.computeIfAbsent(fp, k -> new Acc(ctx, msg));
            acc.count++;
            // Phase A.6.2 — track every distinct context this fingerprint occurred in.
            // This gives the amplification classifier a signal orthogonal to count:
            // count tells you HOW MANY events, distinctContextCount tells you HOW SPREAD.
            if (ctx != null && !ctx.isBlank()) acc.contexts.add(ctx);
        }

        // classify once per cluster (not once per raw event — that was the old penalty bug)
        return buckets.values().stream()
                .map(acc -> {
                    FailureClassifier.Classification c =
                            FailureClassifier.classifyJsError(acc.sampleContext, acc.sampleMessage);
                    return new ErrorCluster(
                            deriveTitle(acc.sampleMessage),
                            acc.count,
                            c.domain,
                            c.severity,
                            acc.sampleMessage,
                            acc.sampleContext,
                            c.reason,
                            acc.contexts.size());
                })
                .sorted(Comparator
                        .comparingInt((ErrorCluster ec) -> -ec.severity.ordinal())
                        .thenComparingInt(ec -> -ec.count))
                .collect(Collectors.toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String fingerprint(String ctx, String msg) {
        String normalizedMsg = (msg == null ? "" : msg)
                .replaceAll(":\\d+:\\d+", "")                       // line:col coords
                .replaceAll(":\\d+(?=[^\\d]|$)", "")                // lone column refs
                .replaceAll("\\.[a-fA-F0-9]{8,}", ".<hash>")        // webpack hashes
                .replaceAll("(?<=\\.)\\d+(?=\\.|$)", "N")           // numeric segments
                .replaceAll("https?://[^\\s]+", "<url>")            // URLs vary per-page
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
        return (ctx == null ? "" : ctx.toLowerCase()) + "│" + normalizedMsg;
    }

    /**
     * Derive a short, readable title from a sample message.  Trims URLs, line:col,
     * trailing stack hints — leaves just the human-recognisable phrase.
     */
    private static String deriveTitle(String message) {
        if (message == null || message.isBlank()) return "(empty)";
        String t = message
                .replaceAll("https?://[^\\s]+", "")
                .replaceAll(":\\d+:\\d+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.length() > 90) t = t.substring(0, 87) + "…";
        return t;
    }

    private static final class Acc {
        final String sampleContext;
        final String sampleMessage;
        int count;
        /** Phase A.6.2 — every distinct context this fingerprint was observed in,
         *  insertion-ordered so the first-seen sample stays first. Used by the
         *  amplification classifier to distinguish single-context repeats (likely
         *  EMBEDDED_REPEAT or RETRY_STORM) from multi-context spread (NORMAL_REPEAT). */
        final java.util.LinkedHashSet<String> contexts = new java.util.LinkedHashSet<>();
        Acc(String ctx, String msg) {
            this.sampleContext = ctx;
            this.sampleMessage = msg;
            this.count = 0;
        }
    }
}
