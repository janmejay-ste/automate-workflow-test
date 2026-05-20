package utils.dashboard.components;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.health.semantic.FailureDomain;
import utils.health.semantic.OwnershipRouter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Dashboard component — renders the "Semantic Health" section.
 *
 * Architectural contract (read carefully):
 *
 *   This component is a <strong>dumb presentation layer</strong>.  It consumes
 *   the {@code "semantic"} block from {@code health_snapshot.json} and emits
 *   HTML.  It does not interpret severity, decide ownership routing, format
 *   "Why This Matters" text, or rank clusters.  All of that is decided
 *   upstream in {@code utils.health.semantic.*}.
 *
 *   If you find yourself adding {@code if (severity.equals("critical")) ...}
 *   logic here, STOP.  That belongs in the semantic layer.  This file should
 *   only contain string formatting, CSS classes, and grid layout.
 *
 * Originally lived as a 190-line inline method inside DashboardBuilder.  Lifted
 * out as the first step of the planned componentization — DashboardBuilder is
 * an orchestrator, not a template engine.  Future components (header, trend
 * chart, reliability table) follow the same shape: one class per section,
 * one public {@code render(...)} method, zero semantic policy.
 */
public final class SemanticPanelComponent {

    private static final Logger LOG = LoggerFactory.getLogger(SemanticPanelComponent.class);
    private static final Path   SNAPSHOT_PATH = Paths.get("reports", "trend", "health_snapshot.json");

    private SemanticPanelComponent() {}

    /**
     * Render the full Semantic Health section.  Returns "" (empty string)
     * when no snapshot is available — caller can substitute the placeholder
     * unconditionally; the section simply doesn't appear.
     */
    public static String render() {
        try {
            if (!Files.exists(SNAPSHOT_PATH)) return "";
            String raw = new String(Files.readAllBytes(SNAPSHOT_PATH), StandardCharsets.UTF_8);
            JSONObject root = (JSONObject) new JSONParser().parse(raw);
            JSONObject sem  = (JSONObject) root.get("semantic");
            if (sem == null) return "";

            StringBuilder sb = new StringBuilder(4096);
            sb.append("<div class='section semantic-panel' style='margin-bottom:20px;'>");
            sb.append(header());
            sb.append(layeredScores((JSONObject) sem.get("layeredScores")));
            sb.append(perDomainPanels((JSONArray) sem.get("clusters")));
            sb.append(reliabilityTable((JSONArray) sem.get("reliabilities")));
            sb.append(telemetryFootnote((JSONObject) sem.get("telemetry")));
            sb.append("</div>");
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("SemanticPanelComponent render failed: {}", e.getMessage());
            return "";
        }
    }

    // ── sub-templates ────────────────────────────────────────────────────────

    private static String header() {
        return "<div class='section-title' style='display:flex;align-items:center;gap:8px;'>"
             + "Semantic Health"
             + "<span style='font-size:11px;font-weight:500;color:#94a3b8;"
             + "text-transform:none;letter-spacing:0;'>"
             + "— layered scores, clustered defects, phase reliability"
             + "</span></div>";
    }

    private static String layeredScores(JSONObject layered) {
        if (layered == null) return "";
        int p = jsonInt(layered, "productHealth", 100);
        int f = jsonInt(layered, "frameworkHealth", 100);
        int t = jsonInt(layered, "telemetryConfidence", 100);
        // Business outcome may be null when no transactions were recorded.
        Object bRaw = layered.get("businessOutcome");
        Integer b = (bRaw instanceof Number n) ? n.intValue() : null;
        String pStatus = jsonStr(layered, "productStatus", "");
        String fStatus = jsonStr(layered, "frameworkStatus", "");
        String tStatus = jsonStr(layered, "telemetryStatus", "");
        String bStatus = jsonStr(layered, "businessStatus", "NO DATA");

        String businessCardHtml = (b == null)
                ? scoreCardNoData("Business Outcome", "User-intent — wire BusinessOutcomeTracker to enable")
                : scoreCard("Business Outcome", b, bStatus, "User-intent — workflows/integrations completed");

        return "<div class='semantic-scores' "
             + "style='display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));"
             + "gap:12px;margin-bottom:18px;'>"
             + scoreCard("Product Health",       p, pStatus, "App stability — auth, editor, frontend")
             + scoreCard("Framework Health",     f, fStatus, "Automation — selectors, retries, WebDriver")
             + scoreCard("Telemetry Confidence", t, tStatus, "Observability — metric integrity, valid samples")
             + businessCardHtml
             + "</div>";
    }

    /** Card variant for "no data" — renders "—" with a muted NO DATA tag. */
    private static String scoreCardNoData(String label, String desc) {
        return "<div style='padding:16px;background:#fff;border:1px solid #e2e8f0;"
             + "border-radius:8px;border-left:4px solid #cbd5e1;'>"
             + "<div style='font-size:11px;font-weight:700;color:#64748b;text-transform:uppercase;"
             + "letter-spacing:0.6px;margin-bottom:4px;'>" + esc(label) + "</div>"
             + "<div style='display:flex;align-items:baseline;gap:6px;margin-bottom:2px;'>"
             + "<span style='font-size:32px;font-weight:900;color:#cbd5e1;line-height:1;'>—</span>"
             + "<span style='font-size:11px;font-weight:700;color:#94a3b8;margin-left:auto;"
             + "letter-spacing:0.5px;'>NO DATA</span>"
             + "</div>"
             + "<div style='font-size:11px;color:#94a3b8;'>" + esc(desc) + "</div>"
             + "</div>";
    }

    private static String perDomainPanels(JSONArray clusters) {
        // Convert JSONArray to typed list, then bucket by domain.
        List<JSONObject> all = new ArrayList<>();
        if (clusters != null) {
            for (Object o : clusters) if (o instanceof JSONObject jo) all.add(jo);
        }

        String[] domainOrder = { "Product", "Infrastructure", "Framework", "Telemetry", "Observability" };
        Map<String, List<JSONObject>> byDomain = new LinkedHashMap<>();
        for (String d : domainOrder) byDomain.put(d, new ArrayList<>());
        for (JSONObject c : all) {
            String d = jsonStr(c, "domain", "");
            String key = "Product";
            for (String known : domainOrder) {
                if (known.equalsIgnoreCase(d)) { key = known; break; }
            }
            byDomain.get(key).add(c);
        }

        StringBuilder out = new StringBuilder();
        out.append("<div style='margin-bottom:18px;'>")
           .append("<div style='font-size:13px;font-weight:700;color:#475569;"
                 + "text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;'>")
           .append("Per-Domain Issues")
           .append("<span style='font-size:11px;font-weight:500;color:#94a3b8;"
                 + "text-transform:none;letter-spacing:0;margin-left:8px;'>")
           .append("— routed by ownership boundary</span></div>")
           .append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:12px;'>");
        for (String domain : domainOrder) {
            out.append(domainPanel(domain, byDomain.get(domain)));
        }
        out.append("</div></div>");
        return out.toString();
    }

    private static String domainPanel(String domain, List<JSONObject> rows) {
        boolean clean = rows.isEmpty();
        // OwnershipRouter is the single source of truth for team + action.
        // This component never invents its own routing.
        OwnershipRouter.Routing route = OwnershipRouter.routeForLabel(domain);

        String accent = domainAccent(domain);
        StringBuilder p = new StringBuilder();
        p.append("<div style='background:#fff;border:1px solid #e2e8f0;"
              + "border-top:3px solid ").append(accent)
         .append(";border-radius:8px;padding:14px;display:flex;flex-direction:column;'>");
        p.append("<div style='display:flex;align-items:center;justify-content:space-between;"
              + "margin-bottom:6px;'>")
         .append("<div style='font-size:13px;font-weight:700;color:#1e293b;'>")
         .append(esc(domain)).append(" Issues</div>");
        if (clean) {
            p.append("<span style='display:inline-block;padding:2px 8px;background:#dcfce7;"
                  + "color:#166534;border-radius:10px;font-size:11px;font-weight:700;'>")
             .append("✓ Clean</span>");
        } else {
            p.append("<span style='display:inline-block;padding:2px 8px;background:#f1f5f9;"
                  + "color:#475569;border-radius:10px;font-size:11px;font-weight:700;"
                  + "font-variant-numeric:tabular-nums;'>")
             .append(rows.size()).append(" cluster").append(rows.size() == 1 ? "" : "s")
             .append("</span>");
        }
        p.append("</div>");
        p.append("<div style='font-size:11px;color:#94a3b8;margin-bottom:10px;'>")
         .append(esc(route.inlineLabel())).append("</div>");

        if (clean) {
            p.append("<div style='font-size:12px;color:#94a3b8;padding:14px 0;text-align:center;"
                  + "font-style:italic;'>No issues detected in this domain this run.</div>");
        } else {
            p.append("<table style='width:100%;border-collapse:collapse;font-size:12px;'>");
            for (JSONObject c : rows) {
                p.append("<tr>")
                 .append("<td style='padding:5px 0;border-bottom:1px solid #f1f5f9;color:#334155;'>")
                 .append(esc(jsonStr(c, "title", ""))).append("</td>")
                 .append("<td style='padding:5px 8px;border-bottom:1px solid #f1f5f9;"
                       + "text-align:right;font-variant-numeric:tabular-nums;color:#64748b;"
                       + "width:40px;'>×").append(jsonInt(c, "count", 0)).append("</td>")
                 .append("<td style='padding:5px 0;border-bottom:1px solid #f1f5f9;width:85px;'>")
                 .append(severityBadge(jsonStr(c, "severity", ""))).append("</td>")
                 .append("</tr>");
            }
            p.append("</table>");
        }
        p.append("</div>");
        return p.toString();
    }

    private static String reliabilityTable(JSONArray reliab) {
        if (reliab == null || reliab.isEmpty()) return "";
        StringBuilder s = new StringBuilder();
        s.append("<div style='margin-bottom:6px;'>")
         .append("<div style='font-size:13px;font-weight:700;color:#475569;"
               + "text-transform:uppercase;letter-spacing:0.5px;margin-bottom:6px;'>")
         .append("Phase Reliability</div>")
         .append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>")
         .append("<thead><tr style='background:#f8fafc;'>")
         .append("<th style='text-align:left;padding:8px;border-bottom:1px solid #e2e8f0;'>Phase</th>")
         .append("<th style='text-align:right;padding:8px;border-bottom:1px solid #e2e8f0;width:80px;'>Pass %</th>")
         .append("<th style='text-align:right;padding:8px;border-bottom:1px solid #e2e8f0;width:100px;'>Successes / Total</th>")
         .append("<th style='text-align:left;padding:8px;border-bottom:1px solid #e2e8f0;width:110px;'>Confidence</th>")
         .append("</tr></thead><tbody>");
        for (Object o : reliab) {
            if (!(o instanceof JSONObject pr)) continue;
            String phase = esc(jsonStr(pr, "phase", ""));
            double pct   = jsonDouble(pr, "percentage", -1.0);
            int succ     = jsonInt(pr, "successes", 0);
            int fail     = jsonInt(pr, "failures", 0);
            String conf  = jsonStr(pr, "confidence", "");
            String pctText  = pct < 0 ? "—" : String.format("%.1f%%", pct);
            String pctColor = pct < 0 ? "#94a3b8"
                            : pct >= 90 ? "#10b981"
                            : pct >= 75 ? "#3b82f6"
                            : pct >= 50 ? "#f59e0b"
                            : "#ef4444";
            s.append("<tr>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;'>").append(phase).append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;text-align:right;"
                   + "font-weight:700;color:").append(pctColor)
             .append(";font-variant-numeric:tabular-nums;'>").append(pctText).append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;text-align:right;"
                   + "font-variant-numeric:tabular-nums;color:#64748b;'>")
             .append(succ).append(" / ").append(succ + fail).append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;'>")
             .append(confidenceBadge(conf)).append("</td>")
             .append("</tr>");
        }
        s.append("</tbody></table></div>");
        return s.toString();
    }

    private static String telemetryFootnote(JSONObject telemetry) {
        if (telemetry == null) return "";
        int unknown = jsonInt(telemetry, "unknown", 0);
        int valid   = jsonInt(telemetry, "valid", 0);
        if (unknown <= 0) return "";
        return "<div style='margin-top:12px;padding:10px 12px;background:#f1f5f9;"
             + "border-radius:6px;font-size:12px;color:#475569;'>"
             + "ℹ <b>" + unknown + "</b> timing measurement"
             + (unknown == 1 ? "" : "s")
             + " came back UNKNOWN — telemetry gap, not a performance failure. "
             + "<b>" + valid + "</b> valid timing sample"
             + (valid == 1 ? "" : "s")
             + " recorded.</div>";
    }

    // ── tiny render primitives ──────────────────────────────────────────────

    private static String scoreCard(String label, int value, String status, String desc) {
        String color = value >= 90 ? "#10b981"
                     : value >= 75 ? "#3b82f6"
                     : value >= 50 ? "#f59e0b"
                     : value >= 30 ? "#f97316"
                     : "#ef4444";
        return "<div style='padding:16px;background:#fff;border:1px solid #e2e8f0;"
             + "border-radius:8px;border-left:4px solid " + color + ";'>"
             + "<div style='font-size:11px;font-weight:700;color:#64748b;text-transform:uppercase;"
             + "letter-spacing:0.6px;margin-bottom:4px;'>" + esc(label) + "</div>"
             + "<div style='display:flex;align-items:baseline;gap:6px;margin-bottom:2px;'>"
             + "<span style='font-size:32px;font-weight:900;color:" + color
             + ";line-height:1;'>" + value + "</span>"
             + "<span style='font-size:13px;color:#94a3b8;'>/100</span>"
             + "<span style='font-size:11px;font-weight:700;color:" + color
             + ";margin-left:auto;letter-spacing:0.5px;'>" + esc(status) + "</span>"
             + "</div>"
             + "<div style='font-size:11px;color:#94a3b8;'>" + esc(desc) + "</div>"
             + "</div>";
    }

    private static String severityBadge(String severity) {
        String s = severity == null ? "" : severity;
        String bg, fg;
        switch (s.toLowerCase()) {
            case "critical": bg = "#fee2e2"; fg = "#991b1b"; break;
            case "high":     bg = "#fed7aa"; fg = "#9a3412"; break;
            case "medium":   bg = "#fef3c7"; fg = "#854d0e"; break;
            case "low":      bg = "#dbeafe"; fg = "#1e40af"; break;
            case "info":     bg = "#f1f5f9"; fg = "#475569"; break;
            default:         bg = "#f1f5f9"; fg = "#475569"; break;
        }
        return "<span style='display:inline-block;padding:2px 8px;background:" + bg
             + ";color:" + fg + ";border-radius:10px;font-size:11px;font-weight:700;"
             + "letter-spacing:0.3px;'>" + esc(s) + "</span>";
    }

    private static String confidenceBadge(String confidence) {
        String c = confidence == null ? "" : confidence;
        String bg, fg;
        switch (c.toLowerCase()) {
            case "valid":   bg = "#dcfce7"; fg = "#166534"; break;
            case "partial": bg = "#fef3c7"; fg = "#854d0e"; break;
            case "unknown": bg = "#f1f5f9"; fg = "#475569"; break;
            case "failed":  bg = "#fee2e2"; fg = "#991b1b"; break;
            default:        bg = "#f8fafc"; fg = "#94a3b8"; break;
        }
        return "<span style='display:inline-block;padding:2px 8px;background:" + bg
             + ";color:" + fg + ";border-radius:4px;font-size:11px;font-weight:600;'>"
             + esc(c) + "</span>";
    }

    private static String domainAccent(String domain) {
        return switch (domain.toLowerCase()) {
            case "product"        -> "#ec4899";
            case "infrastructure" -> "#eab308";
            case "framework"      -> "#6366f1";
            case "telemetry"      -> "#14b8a6";
            case "observability"  -> "#94a3b8";
            default               -> "#cbd5e1";
        };
    }

    // ── JSON helpers (kept here to avoid leaking accessors from DashboardBuilder) ──

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static int jsonInt(JSONObject o, String k, int def) {
        Object v = o.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static double jsonDouble(JSONObject o, String k, double def) {
        Object v = o.get(k);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static String jsonStr(JSONObject o, String k, String def) {
        Object v = o.get(k);
        return v == null ? def : String.valueOf(v);
    }
}
