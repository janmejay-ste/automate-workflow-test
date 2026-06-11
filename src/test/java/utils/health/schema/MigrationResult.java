package utils.health.schema;

import java.util.Collections;
import java.util.List;

import org.json.simple.JSONObject;

/**
 * Outcome of one schema-migration attempt. Carries:
 * <ul>
 *   <li>{@code outcome} — one of the four {@link MigrationOutcome} states.</li>
 *   <li>{@code sourceVersion} — the version the input was detected as.
 *       Always set, even on failure paths, so logs show what was
 *       attempted.</li>
 *   <li>{@code migrated} — the post-migration JSON, or {@code null} when
 *       the outcome is not {@link MigrationOutcome#ok() ok}.</li>
 *   <li>{@code lossy} — true iff information was discarded during the
 *       migration. Cannot be true when {@code outcome} is
 *       {@link MigrationOutcome#SUCCESS_NATIVE}. Constructor enforces
 *       this invariant — see below.</li>
 *   <li>{@code reasons} — human-readable explanations of WHY the
 *       migration is lossy / unsupported / unknown. For lossy results
 *       this must be non-empty; for native success it should be empty.</li>
 * </ul>
 *
 * <p><b>Invariant enforced:</b> a SUCCESS_NATIVE result with {@code lossy=true}
 * is a contradiction (nothing was migrated, so nothing could have been lost).
 * The constructor throws. Conversely, a SUCCESS_LOSSY result with empty
 * {@code reasons} hides what was lost; the constructor throws on that too.
 * These checks exist because every C4 review explicitly asked for "lossy
 * migration must never pretend reconstruction succeeded."</p>
 */
public record MigrationResult(
        MigrationOutcome outcome,
        SnapshotSchemaVersion sourceVersion,
        JSONObject migrated,
        boolean lossy,
        List<String> reasons
) {
    public MigrationResult {
        if (outcome == null) throw new IllegalArgumentException("outcome must be non-null");
        if (sourceVersion == null) throw new IllegalArgumentException("sourceVersion must be non-null");
        reasons = reasons == null ? List.of() : Collections.unmodifiableList(reasons);

        // Invariant 1: lossy makes no sense for a native (no-op) migration.
        if (outcome == MigrationOutcome.SUCCESS_NATIVE && lossy) {
            throw new IllegalArgumentException(
                "SUCCESS_NATIVE cannot be lossy — nothing was transformed");
        }
        // Invariant 2: a lossy migration with no reasons hides what was lost.
        if (outcome == MigrationOutcome.SUCCESS_LOSSY && reasons.isEmpty()) {
            throw new IllegalArgumentException(
                "SUCCESS_LOSSY must carry at least one reason describing what was lost");
        }
        // Invariant 3: failure outcomes must not carry migrated JSON (would
        // be misleading — there's no usable output).
        if (!outcome.ok() && migrated != null) {
            throw new IllegalArgumentException(
                outcome + " must not carry a migrated JSON object");
        }
        // Invariant 4: success outcomes must carry migrated JSON.
        if (outcome.ok() && migrated == null) {
            throw new IllegalArgumentException(
                outcome + " must carry a non-null migrated JSON object");
        }
    }

    // ── Factory shortcuts ───────────────────────────────────────────────

    public static MigrationResult success(SnapshotSchemaVersion source, JSONObject migrated) {
        return new MigrationResult(MigrationOutcome.SUCCESS_NATIVE, source, migrated, false, List.of());
    }

    public static MigrationResult lossy(SnapshotSchemaVersion source, JSONObject migrated,
                                         List<String> reasons) {
        return new MigrationResult(MigrationOutcome.SUCCESS_LOSSY, source, migrated, true, reasons);
    }

    public static MigrationResult unsupported(SnapshotSchemaVersion source, String reason) {
        return new MigrationResult(MigrationOutcome.UNSUPPORTED, source, null, false,
                reason == null ? List.of() : List.of(reason));
    }

    public static MigrationResult unknownSchema(String reason) {
        return new MigrationResult(MigrationOutcome.UNKNOWN_SCHEMA,
                SnapshotSchemaVersion.UNKNOWN, null, false,
                reason == null ? List.of() : List.of(reason));
    }
}
