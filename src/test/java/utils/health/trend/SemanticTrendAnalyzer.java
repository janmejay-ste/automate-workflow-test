package utils.health.trend;

import java.util.*;

/**
 * Pure-function trend computations over the persisted semantic history.
 *
 * Both the PDF and the dashboard can render trend summaries from these
 * results — same answers, same deltas, no duplicated logic.
 */
public final class SemanticTrendAnalyzer {

    private SemanticTrendAnalyzer() {}

    /**
     * Delta of one score against its previous run and against a rolling average.
     * A {@code -1} previous value means "no comparison data" — render as "—".
     */
    public static final class ScoreDelta {
        public final int    current;
        public final int    previous;      // -1 = no previous run
        public final double rollingAvg;    // -1 = not enough samples
        public final int    rollingWindow;

        public ScoreDelta(int current, int previous, double rollingAvg, int rollingWindow) {
            this.current       = current;
            this.previous      = previous;
            this.rollingAvg    = rollingAvg;
            this.rollingWindow = rollingWindow;
        }

        public int deltaVsPrevious() {
            return previous < 0 ? 0 : current - previous;
        }

        public double deltaVsAverage() {
            return rollingAvg < 0 ? 0.0 : current - rollingAvg;
        }

        /** Human-readable inline delta string, e.g. {@code "(▲ +5 vs prev · ▲ +2.3 vs 5-run avg)"} */
        public String inlineLabel() {
            StringBuilder s = new StringBuilder();
            if (previous >= 0) {
                int d = deltaVsPrevious();
                s.append(arrow(d)).append(" ").append(signed(d)).append(" vs prev");
            }
            if (rollingAvg >= 0) {
                if (s.length() > 0) s.append(" · ");
                double da = deltaVsAverage();
                s.append(arrow((int) Math.signum(da))).append(" ").append(signedFmt(da))
                 .append(" vs ").append(rollingWindow).append("-run avg");
            }
            return s.length() == 0 ? "first recorded run" : s.toString();
        }

        private static String arrow(double d) { return d > 0 ? "▲" : d < 0 ? "▼" : "→"; }
        private static String signed(int d)   { return (d > 0 ? "+" : "") + d; }
        private static String signedFmt(double d) {
            return String.format("%s%.1f", d > 0 ? "+" : (d < 0 ? "" : "±"), d);
        }
    }

    /** Compute delta for a layered score using the trend history. */
    public static ScoreDelta deltaFor(LayeredField field, int currentValue, int rollingWindow) {
        List<SemanticTrendEntry> history = SemanticTrendReader.readAll();

        int previous = -1;
        // Use the last entry as "previous" — the current run hasn't been written yet
        // when the dashboard/PDF renders.
        if (!history.isEmpty()) {
            previous = extract(field, history.get(history.size() - 1));
        }

        double avg = -1;
        if (history.size() >= rollingWindow) {
            int from = history.size() - rollingWindow;
            int sum  = 0;
            for (int i = from; i < history.size(); i++) sum += extract(field, history.get(i));
            avg = sum / (double) rollingWindow;
        }
        return new ScoreDelta(currentValue, previous, avg, rollingWindow);
    }

    /**
     * For each known cluster fingerprint in the recent window, count how many of
     * the last {@code window} runs included it.  A high recurrence (e.g. 9/10) is
     * the signal we want: persistent defects, not one-off flakes.
     */
    public static Map<String, RecurrenceRow> clusterRecurrence(int window) {
        List<SemanticTrendEntry> recent = SemanticTrendReader.readRecent(window);
        Map<String, RecurrenceRow> out = new LinkedHashMap<>();
        for (SemanticTrendEntry e : recent) {
            for (SemanticTrendEntry.ClusterRow c : e.clusters) {
                RecurrenceRow rr = out.computeIfAbsent(c.fingerprint,
                        k -> new RecurrenceRow(c.fingerprint, c.title, c.domain, c.severity));
                rr.runCount++;
                rr.totalOccurrences += c.count;
            }
        }
        return out;
    }

    public static final class RecurrenceRow {
        public final String fingerprint;
        public final String title;
        public final String domain;
        public final String severity;
        public int runCount;          // how many of the recent runs contained this cluster
        public int totalOccurrences;  // sum of per-run counts across the window

        RecurrenceRow(String fingerprint, String title, String domain, String severity) {
            this.fingerprint = fingerprint;
            this.title       = title;
            this.domain      = domain;
            this.severity    = severity;
        }
    }

    public enum LayeredField { PRODUCT, FRAMEWORK, TELEMETRY }

    private static int extract(LayeredField f, SemanticTrendEntry e) {
        return switch (f) {
            case PRODUCT   -> e.productHealth;
            case FRAMEWORK -> e.frameworkHealth;
            case TELEMETRY -> e.telemetryConfidence;
        };
    }
}
