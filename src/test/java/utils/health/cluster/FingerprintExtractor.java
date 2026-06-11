package utils.health.cluster;

import java.util.Map;

/**
 * Strategy interface: given a raw contributor event (as a string-keyed map),
 * produce the {@link ClusterKey} that groups it with semantically identical
 * events.
 *
 * <p>Implementations MUST be deterministic and free of run-local state
 * (timestamps, IDs, RNGs). The same input must always produce the same key.
 * This is non-negotiable — a non-deterministic extractor would silently
 * inflate cluster counts and defeat the purpose of normalization.</p>
 *
 * <p><b>Sealed surface.</b> The set of contributor sources is fixed by
 * {@link ContributorSource}; every value must have a corresponding
 * implementation. Adding a new source without an extractor is a compile
 * error at {@link #forSource}.</p>
 *
 * <p><b>Normalization principle.</b> An extractor must aggressively strip
 * transient detail (line numbers, coordinates, request IDs, page-instance
 * hashes) while preserving the human-recognisable identity of the failure.
 * Drift here is what the fingerprint-stability test suite locks down.</p>
 */
public sealed interface FingerprintExtractor
        permits FallbackFingerprintExtractor,
                JsErrorFingerprintExtractor,
                TestFailureFingerprintExtractor {

    /**
     * Compute the ClusterKey for one raw event.
     *
     * @param event opaque map of event attributes. Each extractor documents
     *              which keys it reads.
     * @return non-null ClusterKey. Return {@link ClusterKey#UNKNOWN} if the
     *         event cannot be fingerprinted — never null, never throw.
     */
    ClusterKey extract(Map<String, String> event);

    /** Which contributor source this extractor is registered for. */
    ContributorSource source();

    /**
     * Factory: pick the right extractor for a source. Exhaustive switch is
     * intentional — if a new {@link ContributorSource} is added without a
     * corresponding extractor, this stops compiling.
     */
    static FingerprintExtractor forSource(ContributorSource source) {
        return switch (source) {
            case FALLBACKS    -> new FallbackFingerprintExtractor();
            case JS_ERRORS    -> new JsErrorFingerprintExtractor();
            case TEST_FAILURES -> new TestFailureFingerprintExtractor();
        };
    }
}
