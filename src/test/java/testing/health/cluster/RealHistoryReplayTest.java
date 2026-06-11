package testing.health.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.cluster.replay.ReplayHarness;
import utils.health.cluster.replay.ReplayReportRenderer;
import utils.health.cluster.replay.ReplayRunResult;

/**
 * Runs the C4 replay harness against whatever real historical
 * {@code health_snapshot*.json} files exist on disk and writes the result
 * to {@code reports/c4-replay-real.md}. This is the report Phase 4 will
 * read before any policy is chosen.
 *
 * <p><b>Honest framing:</b> this test does not assert that the result
 * looks any particular way. It asserts only that the harness completed
 * and produced a report. Whether the report contains enough data to make
 * a decision is itself an empirical finding, not a pass/fail condition.</p>
 *
 * <p>The test always passes when the harness runs cleanly. Use the
 * console output and the generated markdown file to read the findings.</p>
 */
public class RealHistoryReplayTest {

    private static final Logger LOG = LoggerFactory.getLogger(RealHistoryReplayTest.class);
    private static final Path TREND_DIR = Paths.get("reports/trend");
    private static final Path REPORT_PATH = Paths.get("reports/c4-replay-real.md");

    @Test(groups = {"sanity"})
    public void replayRealHistoryAndWriteReport() throws Exception {
        // Discover all health-snapshot files in reports/trend (current +
        // any archived rotations). The current snapshot is always
        // health_snapshot.json; archived ones may use suffixes like
        // .yyyymmdd-hhmmss. Ignore .fixture-backup files (those are test
        // artifacts, not real history).
        List<Path> snapshots = new ArrayList<>();
        if (Files.isDirectory(TREND_DIR)) {
            try (Stream<Path> walk = Files.list(TREND_DIR)) {
                walk.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("health_snapshot")
                            && n.endsWith(".json")
                            && !n.contains("fixture-backup");
                    })
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(snapshots::add);
            }
        }
        LOG.info("[RealHistoryReplayTest] Found {} real-history snapshot file(s) in {}",
                snapshots.size(), TREND_DIR);

        List<Map.Entry<String, String>> inputs = new ArrayList<>();
        for (Path p : snapshots) {
            inputs.add(new SimpleEntry<>(p.getFileName().toString(), Files.readString(p)));
        }

        List<ReplayRunResult> runs = ReplayHarness.replayBatch(inputs,
                ReplayHarness.DEFAULT_CAPS);
        LOG.info("[RealHistoryReplayTest] Replayed {} run(s); {} parsed cleanly",
                inputs.size(), runs.size());

        // Build the report — prepended with a data-availability preface
        // so the decision-maker sees the scarcity caveat first, not last.
        String preface = buildDataAvailabilityPreface(snapshots.size(), runs);
        String body = ReplayReportRenderer.render(runs);
        String full = preface + "\n" + body;

        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, full);
        LOG.info("[RealHistoryReplayTest] Real-history report written to {}", REPORT_PATH);

        // Log the high-level finding to the console so a CI viewer sees it
        // even without opening the file.
        logFindings(runs);

        Assert.assertTrue(Files.exists(REPORT_PATH),
                "real-history replay report must be created");
    }

    private static String buildDataAvailabilityPreface(int filesFound, List<ReplayRunResult> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# C4 Replay — Real History\n\n");
        sb.append("## Data availability\n\n");
        sb.append("Snapshot files discovered: **").append(filesFound).append("**\n\n");
        sb.append("Successfully parsed: **").append(runs.size()).append("**\n\n");

        long withContributors = runs.stream()
                .filter(r -> r.policyA().totalRawEventCount() > 0)
                .count();
        sb.append("Runs with non-empty contributor data: **")
          .append(withContributors).append("**\n\n");

        // The honest caveat — without contributor-rich snapshots, the
        // harness can't re-cluster anything, and the report is empty.
        if (withContributors == 0) {
            sb.append("> **Note.** The contributor-rich snapshot format")
              .append(" (`scoreContributors.items`) was introduced recently;")
              .append(" the on-disk history at the time of this replay does")
              .append(" not yet carry per-event raw data for any failing run.")
              .append(" The available snapshot(s) are clean (score=100, no")
              .append(" contributors), so neither candidate policy can")
              .append(" diverge from today.\n\n");
            sb.append("> **What to do.** Either wait for production runs to")
              .append(" accumulate snapshots with non-empty contributor data,")
              .append(" or re-run a representative suite (one expected to")
              .append(" surface fallbacks / JS errors / failures) so the")
              .append(" harness has something to cluster. The synthetic-corpus")
              .append(" report at `reports/c4-replay-fixture.md` shows the")
              .append(" harness's behavior on canonical scenarios in the")
              .append(" meantime.\n\n");
        }
        return sb.toString();
    }

    private static void logFindings(List<ReplayRunResult> runs) {
        if (runs.isEmpty()) {
            LOG.warn("[RealHistoryReplayTest] No runs to summarize — see report for "
                  + "data-availability caveats.");
            return;
        }
        int nDiverged = 0;
        double maxAbsDeltaA = 0;
        for (ReplayRunResult r : runs) {
            if (Math.abs(r.policyADelta()) > 0.5) nDiverged++;
            maxAbsDeltaA = Math.max(maxAbsDeltaA, Math.abs(r.policyADelta()));
        }
        LOG.info("[RealHistoryReplayTest] {} of {} runs diverge between Policy A and today; "
              + "max |Δ A| = {}",
              nDiverged, runs.size(), String.format("%.1f", maxAbsDeltaA));
    }
}
