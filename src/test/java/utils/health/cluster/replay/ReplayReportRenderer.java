package utils.health.cluster.replay;

import java.util.List;

import utils.health.cluster.ContributorSource;

/**
 * Renders a list of {@link ReplayRunResult}s as a markdown report. The
 * shape of the table is intentionally fixed: any decision-maker reading
 * the report knows exactly where to look for the "why did the score
 * change" attribution.
 */
public final class ReplayReportRenderer {

    private ReplayReportRenderer() {}

    public static String render(List<ReplayRunResult> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# C4 Replay Report — Policy A vs Policy B\n\n");
        sb.append("Per-run comparison and distribution stats. Run count: ")
          .append(runs.size()).append("\n\n");

        if (runs.isEmpty()) {
            sb.append("_No replay data available._\n");
            return sb.toString();
        }

        // Per-run table.  Cluster count alone is a coarse driver of
        // Policy A vs today divergence — the structural condition is
        // "clusters whose raw weight exceeds the source cap", surfaced
        // here as the dedicated "Over Cap" column. When Over Cap is 0,
        // Policy A and today produce IDENTICAL output regardless of
        // cluster count or amplification ratio.
        //
        // The "Lossy?" column (B4 Phase 3) flags rows produced by the
        // V2→V3 lossy migrator. Lossy rows always show 0 raw events / 0
        // clusters because V2 has no per-event data to reconstruct.
        // Without this column, those rows look like "policies agree";
        // with it, they read as "no signal — migrated from older schema".
        sb.append("## Per-run breakdown\n\n");
        sb.append("| Run | Lossy? | Old | Policy A | Policy B | Δ A | Δ B | Largest Δ source | Clusters | Over Cap | Amplification |\n");
        sb.append("|---|:-:|---:|---:|---:|---:|---:|---|---:|---:|---:|\n");
        for (ReplayRunResult r : runs) {
            sb.append("| ").append(escape(r.runId())).append(" | ")
              .append(r.lossy() ? "⚠ yes" : "—").append(" | ")
              .append(r.oldScore()).append(" | ")
              .append(r.policyAScore()).append(" | ")
              .append(r.policyBScore()).append(" | ")
              .append(formatSignedDelta(r.policyADelta())).append(" | ")
              .append(formatSignedDelta(r.policyBDelta())).append(" | ")
              .append(sourceLabel(r.largestDeltaSource())).append(" | ")
              .append(clusterCountForLargestSource(r)).append(" | ")
              .append(overCapForLargestSource(r)).append(" | ")
              .append(formatAmplification(r.largestAmplification())).append(" |\n");
        }
        sb.append("\n");

        // If any rows are lossy, emit a per-row "what was lost" note so
        // the reasons surface in the same document, not just in logs.
        boolean anyLossy = runs.stream().anyMatch(ReplayRunResult::lossy);
        if (anyLossy) {
            sb.append("### Lossy migration notes\n\n");
            for (ReplayRunResult r : runs) {
                if (!r.lossy()) continue;
                sb.append("- **").append(escape(r.runId())).append("** — ")
                  .append(String.join("; ", r.migrationReasons()))
                  .append("\n");
            }
            sb.append("\n");
        }

        // Distribution stats
        ReplayDistributionStats a = ReplayDistributionStats.forPolicyA(runs);
        ReplayDistributionStats b = ReplayDistributionStats.forPolicyB(runs);
        sb.append("## Distribution stats (Δ from old)\n\n");
        sb.append("| Policy | n | Mean Δ | Median Δ | p95 Δ | Max |Δ| |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
        sb.append("| ").append(a.policyName()).append(" | ").append(a.sampleSize()).append(" | ")
          .append(formatSigned(a.meanDelta())).append(" | ")
          .append(formatSigned(a.medianDelta())).append(" | ")
          .append(formatSigned(a.p95Delta())).append(" | ")
          .append(formatNumber(a.maxAbsDelta())).append(" |\n");
        sb.append("| ").append(b.policyName()).append(" | ").append(b.sampleSize()).append(" | ")
          .append(formatSigned(b.meanDelta())).append(" | ")
          .append(formatSigned(b.medianDelta())).append(" | ")
          .append(formatSigned(b.p95Delta())).append(" | ")
          .append(formatNumber(b.maxAbsDelta())).append(" |\n");
        sb.append("\n");

        // Reading guidance — keep small, factual, no recommendations
        sb.append("## How to read this\n\n");
        sb.append("- **Δ** = candidate-policy penalty minus the snapshot's recorded penalty. ")
          .append("Positive Δ means the candidate scores STRICTER than today; ")
          .append("negative Δ means MORE LENIENT.\n");
        sb.append("- **Largest Δ source** is the contributor source where Policy A diverges most ")
          .append("from the snapshot. That's the column to read first when asking ")
          .append("\"why did this run move?\"\n");
        sb.append("- **Amplification** is raw events per cluster in that source. ")
          .append("`22:1` means one root cause generated 22 raw events.\n");
        sb.append("- **Clusters** is the number of distinct fingerprints in the largest-Δ source.\n");
        sb.append("- **Over Cap** is the number of those clusters whose raw weight individually ")
          .append("exceeds the source cap. This metric exposes where the policies diverge from ")
          .append("today's flat scoring:\n")
          .append("    - `Over Cap = 0` → Policy A's per-cluster cap never activates for this ")
          .append("source. Policy A's total ≤ today's total. No visible Δ.\n")
          .append("    - `Over Cap = 1` → exactly one cluster hits the cap. Today applies cap once ")
          .append("(source-level); Policy A applies cap once (per-cluster). Numerically identical → ")
          .append("still Δ ≈ 0.\n")
          .append("    - `Over Cap ≥ 2` → THIS is where Δ A becomes non-zero. Today still applies ")
          .append("the cap once (source-level aggregates all clusters first); Policy A applies the ")
          .append("cap N times. Δ A grows linearly with Over Cap.\n");
        sb.append("- **Median vs Max |Δ|**: a small median with a large max ")
          .append("means the policy is benign for most runs but produces a few large shifts. ")
          .append("Inspect those runs individually before adopting the policy.\n");
        sb.append("- **Lossy?**: `⚠ yes` means the row's source snapshot was migrated forward ")
          .append("from an older schema (B4) and the raw per-event data was not recoverable. ")
          .append("Those rows ALWAYS show 0 raw events and 0 over-cap clusters — that is the ")
          .append("honest \"no signal\" outcome, NOT a finding that policies agree. The lossy ")
          .append("migration notes section above lists what was lost per row.\n");

        return sb.toString();
    }

    /** Cluster count in the source that drove the largest delta. "—" if unknown. */
    private static String clusterCountForLargestSource(ReplayRunResult r) {
        if (r.largestDeltaSource() == null) return "—";
        Integer n = r.policyA().perSourceClusterCount().get(r.largestDeltaSource());
        return n == null ? "0" : n.toString();
    }

    /** Clusters-over-cap in the source that drove the largest delta. */
    private static String overCapForLargestSource(ReplayRunResult r) {
        if (r.largestDeltaSource() == null) return "—";
        Integer n = r.policyA().perSourceClustersOverCap().get(r.largestDeltaSource());
        return n == null ? "0" : n.toString();
    }

    private static String sourceLabel(ContributorSource s) {
        if (s == null) return "—";
        return switch (s) {
            case FALLBACKS     -> "Fallbacks";
            case JS_ERRORS     -> "JS Errors";
            case TEST_FAILURES -> "Test Failures";
        };
    }

    private static String formatAmplification(double ratio) {
        if (ratio <= 0) return "—";
        return Math.round(ratio) + ":1";
    }

    private static String formatSignedDelta(double d) {
        if (Math.abs(d) < 0.05) return "0";
        return (d >= 0 ? "+" : "") + (Math.round(d * 10.0) / 10.0);
    }

    private static String formatSigned(double d) {
        if (Math.abs(d) < 0.05) return "0.0";
        return (d >= 0 ? "+" : "") + String.format("%.1f", d);
    }

    private static String formatNumber(double d) {
        return String.format("%.1f", d);
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("|", "\\|");
    }
}
