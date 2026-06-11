package utils.health.cluster.replay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import utils.health.cluster.ContributorSource;

/**
 * Copies the current {@code reports/trend/health_snapshot.json} to
 * {@code reports/archive/c4-snapshots/} with a structured metadata
 * sidecar. Enables the targeted-noisy-suites data-collection strategy
 * for C4 Phase 4 without depending on every run getting timestamped on
 * the fly by the production pipeline.
 *
 * <p><b>What gets archived</b></p>
 * <ul>
 *   <li>{@code snapshot_&lt;timestamp&gt;_&lt;suite&gt;.json} — exact copy of
 *       the production health_snapshot.json at the moment of archiving.</li>
 *   <li>{@code snapshot_&lt;timestamp&gt;_&lt;suite&gt;.meta.json} — metadata
 *       sidecar with suite name, git commit, status, score, penalty,
 *       contributor totals, and {@code totalClustersOverCap}.</li>
 * </ul>
 *
 * <p><b>Why the sidecar pre-computes totalClustersOverCap</b>: it answers
 * "is this snapshot interesting for C4?" without re-running the replay
 * harness. Triage at a glance.</p>
 *
 * <p><b>Not wired into production after-suite</b>: archiving is invoked
 * explicitly via {@code ArchiveCurrentSnapshotTest} so the procedure is
 * always intentional. Cf. {@code docs/c4-data-collection.md}.</p>
 */
public final class SnapshotArchiver {

    private static final Path SNAPSHOT_SOURCE = Paths.get("reports/trend/health_snapshot.json");
    private static final Path ARCHIVE_DIR     = Paths.get("reports/archive/c4-snapshots");
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private SnapshotArchiver() {}

    /**
     * Archive the current snapshot with the given suite label.
     *
     * @param suiteLabel free-form identifier for the run that produced
     *                   this snapshot. Sanitized into the filename.
     * @return the path to the archived snapshot file, or {@code null} if
     *         the source snapshot is missing.
     */
    public static Path archive(String suiteLabel) throws Exception {
        if (!Files.exists(SNAPSHOT_SOURCE)) return null;

        Files.createDirectories(ARCHIVE_DIR);
        String stamp = STAMP_FMT.format(Instant.now());
        String safeSuite = sanitize(suiteLabel);
        String base = "snapshot_" + stamp + "_" + safeSuite;

        Path archivedSnap = ARCHIVE_DIR.resolve(base + ".json");
        Path metaSidecar  = ARCHIVE_DIR.resolve(base + ".meta.json");

        Files.copy(SNAPSHOT_SOURCE, archivedSnap, StandardCopyOption.REPLACE_EXISTING);

        // Build sidecar by replaying the snapshot through ReplayHarness so
        // totalClustersOverCap is computed using the same logic as the
        // replay report — no risk of definition drift between archiving
        // and replay.
        JSONObject sidecar = buildSidecar(archivedSnap, suiteLabel, stamp);
        Files.writeString(metaSidecar, sidecar.toJSONString(), StandardCharsets.UTF_8);

        return archivedSnap;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject buildSidecar(Path archivedSnap, String suiteLabel, String archivedAtStamp)
            throws Exception {
        String snapshotJson = Files.readString(archivedSnap);
        JSONObject root = (JSONObject) new JSONParser().parse(snapshotJson);

        ReplayRunResult r = ReplayHarness.replay(
                archivedSnap.getFileName().toString(), snapshotJson, ReplayHarness.DEFAULT_CAPS);

        JSONObject meta = new JSONObject();
        meta.put("archivedAt", archivedAtStamp);
        meta.put("suite", suiteLabel == null ? "unknown" : suiteLabel);
        meta.put("gitCommit", readGitCommit());
        // Explicit schema version — read from the snapshot itself rather
        // than inferred from structure. Set to -1 only if the field is
        // truly absent (legacy snapshots predating schema stamping).
        // When B4 (SchemaMigrator) lands, this field will become the
        // routing key for forward-migration; without it, future migrations
        // would have to re-infer versions from structure, which is fragile.
        meta.put("schemaVersion", root.containsKey("schemaVersion")
                ? root.get("schemaVersion") : -1);
        meta.put("status", root.getOrDefault("status", "UNKNOWN"));
        meta.put("score", root.getOrDefault("score", 0));
        meta.put("penalty", root.getOrDefault("penalty", 0.0));
        meta.put("snapshotTimestamp", root.getOrDefault("timestamp", 0L));

        // Pre-computed C4 triage fields — read these to find "interesting" archives fast.
        if (r != null) {
            meta.put("totalRawEventCount", r.policyA().totalRawEventCount());
            meta.put("totalClusterCount",  r.policyA().totalClusterCount());
            meta.put("totalClustersOverCap", r.policyA().totalClustersOverCap());

            // Per-source over-cap so a future query like "which source is
            // driving over-cap?" doesn't require re-replaying.
            JSONObject perSourceOverCap = new JSONObject();
            for (ContributorSource s : ContributorSource.values()) {
                Integer n = r.policyA().perSourceClustersOverCap().get(s);
                if (n != null && n > 0) perSourceOverCap.put(s.name(), n);
            }
            meta.put("perSourceClustersOverCap", perSourceOverCap);

            // The two policy outcomes — saves the future replayer one
            // re-derivation step if they only want to scan headlines.
            meta.put("policyAPenalty", r.policyA().totalApplied());
            meta.put("policyBPenalty", r.policyB().totalApplied());
            meta.put("policyADelta",   r.policyADelta());
            meta.put("policyBDelta",   r.policyBDelta());
        } else {
            meta.put("totalRawEventCount", 0);
            meta.put("totalClusterCount",  0);
            meta.put("totalClustersOverCap", 0);
        }

        return meta;
    }

    /**
     * Resolves the current git commit via {@code git rev-parse --short HEAD}.
     * Best-effort: returns "unknown" on any failure (no git, detached state,
     * not a repo, etc.). NEVER throws — archiving must continue.
     */
    private static String readGitCommit() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--short", "HEAD");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "unknown"; }
            if (p.exitValue() != 0 || line == null) return "unknown";
            return line.trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_").substring(0, Math.min(s.length(), 64));
    }

    /**
     * List archived snapshots — convenience for ops scripts. Returns just
     * the .json paths (skips .meta.json sidecars).
     */
    public static List<Path> listArchives() throws Exception {
        if (!Files.isDirectory(ARCHIVE_DIR)) return List.of();
        try (var stream = Files.list(ARCHIVE_DIR)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .filter(p -> !p.getFileName().toString().endsWith(".meta.json"))
                .sorted()
                .toList();
        }
    }

    /** Where archives land — exposed for tests and the ops guide. */
    public static Path archiveDir() { return ARCHIVE_DIR; }

    /** Suppress unused-warning for the Map import; used implicitly via JSONObject. */
    @SuppressWarnings("unused")
    private static void __keepImport(Map<?, ?> ignored) {}
}
