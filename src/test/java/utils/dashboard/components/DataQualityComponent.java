package utils.dashboard.components;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Phase D2 — Dashboard component for the Data Quality section.
 *
 * <p>Surfaces the four "can I trust this run's numbers?" signals from the
 * snapshot in one panel:</p>
 * <ul>
 *   <li><strong>Integrity</strong> (A.5.4) — contributor math invariants</li>
 *   <li><strong>Evidence channels</strong> (A.6.1) — which channels reported, with what quality</li>
 *   <li><strong>Amplification</strong> (A.6.2) — cluster classification orthogonal to count</li>
 *   <li><strong>Trend statistics</strong> (C2) — mean/stdDev/z-score, outlier flag</li>
 * </ul>
 *
 * <p>These four signals all answer "is the score believable?" — they belong
 * together. An operator triaging a degraded run starts here, not at the score
 * itself.</p>
 *
 * <p>Open-by-default, collapsible. Same toggle pattern as
 * {@link PlatformHealthComponent} (open) and Recordings section (closed).</p>
 */
public final class DataQualityComponent {

    private static final Logger LOG = LoggerFactory.getLogger(DataQualityComponent.class);
    private static final Path SNAPSHOT_PATH = Paths.get("reports", "trend", "health_snapshot.json");

    private DataQualityComponent() {}

    public static String render() {
        try {
            if (!Files.exists(SNAPSHOT_PATH)) return "";
            String raw = new String(Files.readAllBytes(SNAPSHOT_PATH), StandardCharsets.UTF_8);
            JSONObject root = (JSONObject) new JSONParser().parse(raw);

            JSONObject integrity   = (JSONObject) root.get("integrity");
            JSONObject trendStats  = (JSONObject) root.get("trendStats");
            JSONObject platformH   = (JSONObject) root.get("platformHealth");
            JSONObject semantic    = (JSONObject) root.get("semantic");
            JSONObject evidenceCol = platformH == null ? null : (JSONObject) platformH.get("evidence_collection");
            JSONObject amplification = semantic == null ? null : (JSONObject) semantic.get("amplification");

            // All four blocks must exist for the panel to be meaningful.
            if (integrity == null && trendStats == null
                    && evidenceCol == null && amplification == null) {
                return "";
            }

            StringBuilder sb = new StringBuilder(4096);
            // Collapsed-by-default — same rationale as PlatformHealth.
            sb.append("<div class='section data-quality-section collapsed' id='sec-data-quality'>");
            sb.append("<div class='section-header data-quality-toggle' "
                    + "onclick='toggleCollapsibleSection(this, \"data-quality-content\")' "
                    + "role='button' tabindex='0' "
                    + "aria-expanded='false' aria-controls='data-quality-content' "
                    + "title='Click to expand/collapse'>");
            sb.append("<div class='section-title'>"
                    + "<span class='collapsible-chevron' aria-hidden='true'>▶</span> "
                    + "Data Quality "
                    + "<span style='font-size:11px;font-weight:500;color:#94a3b8;"
                    + "text-transform:none;letter-spacing:0;margin-left:8px;'>"
                    + "— integrity, evidence channels, amplification, trend statistics</span>"
                    + "</div></div>");

            sb.append("<div class='collapsible-content' id='data-quality-content'>");
            sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));"
                    + "gap:12px;margin-top:14px;'>");

            sb.append(integrityCard(integrity));
            sb.append(evidenceCard(evidenceCol));
            sb.append(amplificationCard(amplification));
            sb.append(trendStatsCard(trendStats));

            sb.append("</div>");
            sb.append("</div>");   // collapsible-content
            sb.append("</div>");   // section
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("[DataQualityComponent] render failed: {}", e.getMessage());
            return "";
        }
    }

    // ── sub-panels ───────────────────────────────────────────────────────────

    private static String integrityCard(JSONObject integ) {
        if (integ == null) return dqCard("Integrity", "#cbd5e1", "Not reporting", null, null);
        String  status     = str(integ, "status", "UNKNOWN");
        int     checksRun  = jsonInt(integ, "checksRun", 0);
        JSONArray violations = (JSONArray) integ.get("violations");
        int violationCount = violations == null ? 0 : violations.size();
        JSONObject trust   = (JSONObject) integ.get("trust");
        String prevSource  = trust == null ? "—" : str(trust, "previousScoreSource", "—");

        String accent;
        switch (status) {
            case "PASS"     -> accent = "#10b981";
            case "DEGRADED" -> accent = "#ea580c";
            case "FAIL"     -> accent = "#dc2626";
            default         -> accent = "#94a3b8";
        }

        StringBuilder detail = new StringBuilder();
        detail.append("<div style='font-size:11px;color:#475569;line-height:1.6;'>")
              .append(checksRun).append(" invariant check(s) run").append("<br>")
              .append("Smoothing source: <code style='background:#f1f5f9;padding:1px 5px;"
                    + "border-radius:3px;font-size:10px;'>")
              .append(esc(prevSource)).append("</code>");
        if (violationCount > 0 && violations != null) {
            detail.append("<ul style='margin:8px 0 0 0;padding-left:20px;font-size:11px;'>");
            int shown = 0;
            for (Object v : violations) {
                if (shown++ >= 2) break;
                if (!(v instanceof JSONObject vio)) continue;
                detail.append("<li><strong>").append(esc(str(vio, "code", "?"))).append("</strong>")
                      .append(" — ").append(esc(str(vio, "field", "")))
                      .append("</li>");
            }
            detail.append("</ul>");
        }
        detail.append("</div>");
        return dqCard("Integrity", accent,
                violationCount == 0 ? status : status + " (" + violationCount + ")",
                violationCount == 0 ? "All math invariants hold" : "Violation(s) present",
                detail.toString());
    }

    private static String evidenceCard(JSONObject ec) {
        if (ec == null) return dqCard("Evidence", "#cbd5e1", "Not reporting", null, null);
        JSONArray channels = (JSONArray) ec.get("channels");
        int registered = jsonInt(ec, "registeredCount", 0);
        int expected   = jsonInt(ec, "expectedCount",   0);

        int strong = 0, partial = 0, missing = 0, degraded = 0, other = 0;
        if (channels != null) {
            for (Object o : channels) {
                if (!(o instanceof JSONObject c)) continue;
                String q = str(c, "quality", "");
                switch (q) {
                    case "STRONG"   -> strong++;
                    case "PARTIAL"  -> partial++;
                    case "DEGRADED" -> degraded++;
                    case "MISSING"  -> missing++;
                    default          -> other++;
                }
            }
        }

        String accent;
        String headline;
        if (registered != expected) {
            accent   = "#dc2626";
            headline = "Schema drift (" + registered + "/" + expected + ")";
        } else if (degraded > 0) {
            accent   = "#ea580c";
            headline = degraded + " degraded";
        } else if (strong >= expected / 2) {
            accent   = "#10b981";
            headline = strong + "/" + expected + " strong";
        } else {
            accent   = "#f59e0b";
            headline = strong + " strong, " + missing + " missing";
        }

        StringBuilder detail = new StringBuilder();
        detail.append("<div style='font-size:11px;color:#475569;line-height:1.6;'>");
        if (channels != null) {
            for (Object o : channels) {
                if (!(o instanceof JSONObject c)) continue;
                String name = str(c, "name", "?");
                String q    = str(c, "quality", "?");
                int    cnt  = jsonInt(c, "count", 0);
                String qColor = switch (q) {
                    case "STRONG"   -> "#10b981";
                    case "PARTIAL"  -> "#f59e0b";
                    case "DEGRADED" -> "#ea580c";
                    case "MISSING"  -> "#dc2626";
                    default          -> "#94a3b8";
                };
                detail.append("<div style='display:flex;justify-content:space-between;padding:2px 0;'>")
                      .append("<span>").append(esc(name)).append("</span>")
                      .append("<span style='color:").append(qColor).append(";font-weight:600;'>")
                      .append(esc(q)).append(" (").append(cnt).append(")</span>")
                      .append("</div>");
            }
        }
        detail.append("</div>");
        return dqCard("Evidence Channels", accent, headline,
                "registered " + registered + " / expected " + expected, detail.toString());
    }

    private static String amplificationCard(JSONObject amp) {
        if (amp == null) return dqCard("Amplification", "#cbd5e1", "Not reporting", null, null);
        int total = jsonInt(amp, "totalClusters", 0);
        JSONObject byClass = (JSONObject) amp.get("byClassification");

        int retryStorm   = byClass == null ? 0 : jsonInt(byClass, "RETRY_STORM",     0);
        int bootLoop     = byClass == null ? 0 : jsonInt(byClass, "BOOT_LOOP",       0);
        int embedded     = byClass == null ? 0 : jsonInt(byClass, "EMBEDDED_REPEAT", 0);
        int normal       = byClass == null ? 0 : jsonInt(byClass, "NORMAL_REPEAT",   0);
        int none         = byClass == null ? 0 : jsonInt(byClass, "NONE",            0);

        String accent;
        String headline;
        if (total == 0) {
            accent   = "#10b981";
            headline = "No clusters";
        } else if (retryStorm > 0 || bootLoop > 0) {
            accent   = "#dc2626";
            headline = retryStorm + " storm / " + bootLoop + " loop";
        } else if (embedded > 0) {
            accent   = "#ea580c";
            headline = embedded + " embedded";
        } else {
            accent   = "#f59e0b";
            headline = normal + " normal repeats";
        }

        StringBuilder detail = new StringBuilder();
        detail.append("<div style='font-size:11px;color:#475569;line-height:1.6;'>");
        for (String[] entry : new String[][] {
                {"RETRY_STORM",     String.valueOf(retryStorm), "#dc2626"},
                {"BOOT_LOOP",       String.valueOf(bootLoop),   "#dc2626"},
                {"EMBEDDED_REPEAT", String.valueOf(embedded),   "#ea580c"},
                {"NORMAL_REPEAT",   String.valueOf(normal),     "#f59e0b"},
                {"NONE (single)",   String.valueOf(none),       "#10b981"}}) {
            detail.append("<div style='display:flex;justify-content:space-between;padding:2px 0;'>")
                  .append("<span style='color:").append(entry[2]).append(";'>")
                  .append(esc(entry[0])).append("</span>")
                  .append("<span style='font-variant-numeric:tabular-nums;'>")
                  .append(entry[1]).append("</span>")
                  .append("</div>");
        }
        detail.append("</div>");
        return dqCard("Amplification", accent, headline,
                total + " cluster(s) classified", detail.toString());
    }

    private static String trendStatsCard(JSONObject ts) {
        if (ts == null) return dqCard("Trend Stats", "#cbd5e1", "Not reporting", null, null);
        int    sampleSize = jsonInt(ts, "sampleSize", 0);
        double mean       = jsonDouble(ts, "mean", 0.0);
        double stdDev     = jsonDouble(ts, "stdDev", 0.0);
        double zScore     = jsonDouble(ts, "currentZScore", 0.0);
        boolean outlier   = bool(ts, "outlier");

        String accent;
        String headline;
        if (sampleSize == 0) {
            accent   = "#94a3b8";
            headline = "No history";
        } else if (outlier) {
            accent   = "#dc2626";
            headline = "OUTLIER (z=" + fmt(zScore) + ")";
        } else if (Math.abs(zScore) >= 1.0) {
            accent   = "#f59e0b";
            headline = "z=" + fmt(zScore) + " (>1σ)";
        } else {
            accent   = "#10b981";
            headline = "z=" + fmt(zScore) + " (within 1σ)";
        }

        StringBuilder detail = new StringBuilder();
        detail.append("<div style='font-size:11px;color:#475569;line-height:1.6;'>")
              .append("Sample: ").append(sampleSize).append(" run(s)").append("<br>")
              .append("Mean: ").append(fmt(mean)).append("<br>")
              .append("StdDev: ").append(fmt(stdDev))
              .append("</div>");
        return dqCard("Trend Statistics", accent, headline,
                sampleSize == 0 ? "First run" : "Recent " + sampleSize + "-run window",
                detail.toString());
    }

    // ── primitives ───────────────────────────────────────────────────────────

    private static String dqCard(String title, String accent, String headline,
                                  String sub, String detailHtml) {
        StringBuilder s = new StringBuilder();
        s.append("<div style='padding:14px 16px;background:#fff;border:1px solid #e2e8f0;"
              + "border-left:4px solid ").append(accent)
         .append(";border-radius:8px;display:flex;flex-direction:column;'>")
         .append("<div style='font-size:11px;font-weight:700;color:#64748b;"
               + "text-transform:uppercase;letter-spacing:0.6px;margin-bottom:6px;'>")
         .append(esc(title)).append("</div>")
         .append("<div style='font-size:15px;font-weight:700;color:").append(accent)
         .append(";margin-bottom:4px;'>").append(esc(headline)).append("</div>");
        if (sub != null && !sub.isEmpty()) {
            s.append("<div style='font-size:12px;color:#94a3b8;margin-bottom:6px;'>")
             .append(esc(sub)).append("</div>");
        }
        if (detailHtml != null && !detailHtml.isEmpty()) {
            s.append(detailHtml);
        }
        s.append("</div>");
        return s.toString();
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }

    private static int jsonInt(JSONObject o, String k, int def) {
        Object v = o == null ? null : o.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static double jsonDouble(JSONObject o, String k, double def) {
        Object v = o == null ? null : o.get(k);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static String str(JSONObject o, String k, String def) {
        Object v = o == null ? null : o.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(JSONObject o, String k) {
        Object v = o == null ? null : o.get(k);
        return v instanceof Boolean b && b;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
