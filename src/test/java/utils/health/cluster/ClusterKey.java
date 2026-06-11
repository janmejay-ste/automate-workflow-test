package utils.health.cluster;

import java.util.Objects;

/**
 * Strongly-typed wrapper around a fingerprint string. Exists so that
 * "this is a clustering identifier" is a compile-time fact rather than a
 * convention, and so the equality contract (case-sensitive, whitespace-exact)
 * is centralized in one place.
 *
 * <p>Two events belong to the same cluster iff their ClusterKeys are equal.
 * The string itself is opaque — never parse it; the only valid operations
 * are equality, hash, and pretty-printing for diagnostics.</p>
 *
 * <p><b>Stability contract:</b> a given input must produce the same key
 * across runs. Fingerprint extractors must not include timestamps, run IDs,
 * coordinates, or other transient data in the key — that's what the
 * extractors' normalization rules are for.</p>
 *
 * <p>Part of the C4 cluster-normalization work. Currently unused by
 * production code paths; instantiated only by fixture-driven tests until the
 * {@code -Dpenalty.clusterNormalize=true} flag is added in a later phase.</p>
 */
public final class ClusterKey {

    /** Sentinel for events that couldn't be fingerprinted (rare, but possible). */
    public static final ClusterKey UNKNOWN = new ClusterKey("source.unknown:<no-fingerprint>");

    private final String value;

    public ClusterKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ClusterKey value must be non-blank");
        }
        this.value = value;
    }

    public String value() { return value; }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClusterKey ck && value.equals(ck.value);
    }

    @Override
    public int hashCode() { return Objects.hash(value); }

    @Override
    public String toString() { return "ClusterKey[" + value + "]"; }
}
