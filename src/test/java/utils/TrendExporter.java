package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class TrendExporter {

    private static final Logger LOG = LoggerFactory.getLogger(TrendExporter.class);
    private static volatile boolean exported = false;

    public static synchronized void updateTrend(int score) {
        if (exported) {
            LOG.debug("Trend already exported; skipping duplicate export");
            return;
        }
        exported = true;

        try {
            // Ensure directory
            TrendDataWriter.ensureBase();

            // Persist run (append to CSV history)
            TrendDataWriter.appendRun(score);

            // Build chart from history
            List<Integer> scores = TrendDataWriter.readScores();
            TrendChartRenderer.render(scores);

            // Build dashboard HTML and auxiliary pages
            int[] stats = TrendDataWriter.readStatsFromCsv();
            int failed = stats[0];
            int running = stats[1];
            int healthy = stats[2];

            List<String> slowPages = SnapshotReader.getSlowPages();
            List<TestNgResultParser.TestMethodResult> methods = TestNgResultParser.parse();

            DashboardBuilder.build(score, failed, running, healthy, slowPages, methods);

            // Optionally launch dashboard (disabled by default to avoid CI issues)
            DashboardLauncher.launchIfEnabled();

        } catch (Exception ex) {
            LOG.warn("Trend export failed: {}", ex.getMessage());
            ex.printStackTrace(System.out);
        }
    }
}