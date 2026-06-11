package utils.health.cluster;

import java.util.Map;

/**
 * Fingerprint extractor for JS errors recorded by HealthTracker.recordJsError.
 *
 * <p><b>Identity rule:</b> a JS error's identity is its normalized message
 * plus its context (the test method / page where it was observed). The
 * context is included because the same generic error
 * ("Cannot read properties of null") in two different features is two
 * different root causes, not one.</p>
 *
 * <p><b>Reads from event map:</b></p>
 * <ul>
 *   <li>{@code context} — test method or page descriptor</li>
 *   <li>{@code message} — error message text</li>
 * </ul>
 *
 * <p><b>Normalization rules</b> (mirrored from the existing
 * {@code ErrorClusterer.fingerprint} — kept in sync intentionally; if either
 * changes, the fingerprint-stability test catches the drift):</p>
 * <ul>
 *   <li>Strip {@code :line:col} coordinates</li>
 *   <li>Strip lone column references like {@code :42}</li>
 *   <li>Replace webpack/bundler hashes with literal {@code &lt;hash&gt;}</li>
 *   <li>Replace numeric segments between dots with {@code N}</li>
 *   <li>Replace URLs with literal {@code &lt;url&gt;}</li>
 *   <li>Lowercase, trim, collapse whitespace</li>
 * </ul>
 */
public final class JsErrorFingerprintExtractor implements FingerprintExtractor {

    @Override
    public ContributorSource source() {
        return ContributorSource.JS_ERRORS;
    }

    @Override
    public ClusterKey extract(Map<String, String> event) {
        if (event == null) return ClusterKey.UNKNOWN;

        String ctx = event.getOrDefault("context", "");
        String msg = event.getOrDefault("message", "");

        String normCtx = (ctx == null ? "" : ctx).trim().toLowerCase();
        String normMsg = normalizeMessage(msg);

        if (normCtx.isBlank() && normMsg.isBlank()) return ClusterKey.UNKNOWN;

        return new ClusterKey("jsErrors│"
                + (normCtx.isBlank() ? "?" : normCtx) + "│"
                + (normMsg.isBlank() ? "?" : normMsg));
    }

    private static String normalizeMessage(String msg) {
        if (msg == null) return "";
        return msg
                .replaceAll(":\\d+:\\d+", "")                       // :line:col
                .replaceAll(":\\d+(?=[^\\d]|$)", "")                // lone column refs
                .replaceAll("\\.[a-fA-F0-9]{8,}", ".<hash>")        // webpack hashes
                .replaceAll("(?<=\\.)\\d+(?=\\.|$)", "N")           // numeric segments
                .replaceAll("https?://[^\\s]+", "<url>")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
    }
}
