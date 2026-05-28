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
 * Dashboard component — renders the URL Validation section from the
 * {@code "urlValidation"} block of {@code health_snapshot.json}.
 *
 * Same architectural contract as {@link SemanticPanelComponent}: dumb
 * presentation layer.  All severity / domain / finding-type classification
 * is done upstream by the urlvalidator package.  Don't add classification
 * logic here.
 *
 * Renders nothing (empty string) when no URLs were validated this run.
 */
public final class UrlValidationComponent {

    private static final Logger LOG = LoggerFactory.getLogger(UrlValidationComponent.class);
    private static final Path   SNAPSHOT_PATH = Paths.get("reports", "trend", "health_snapshot.json");

    private UrlValidationComponent() {}

    public static String render() {
        try {
            if (!Files.exists(SNAPSHOT_PATH)) return "";
            String raw = new String(Files.readAllBytes(SNAPSHOT_PATH), StandardCharsets.UTF_8);
            JSONObject root = (JSONObject) new JSONParser().parse(raw);
            JSONObject block = (JSONObject) root.get("urlValidation");
            if (block == null) return "";

            int pages    = jsonInt(block, "pagesScanned",  0);
            int urls     = jsonInt(block, "urlsValidated", 0);
            int findings = jsonInt(block, "findingsCount", 0);
            if (urls == 0) return "";          // never scanned anything — render nothing

            JSONArray findingsArr = (JSONArray) block.get("findings");

            StringBuilder sb = new StringBuilder(4096);
            sb.append("<div class='section url-validation-panel' style='margin-bottom:20px;'>");
            sb.append(header(pages, urls, findings));
            if (findings == 0) {
                sb.append(cleanBanner(urls, pages));
            } else {
                sb.append(table(findingsArr));
            }
            sb.append("</div>");
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("UrlValidationComponent render failed: {}", e.getMessage());
            return "";
        }
    }

    private static String header(int pages, int urls, int findings) {
        return "<div class='section-title' style='display:flex;align-items:center;gap:8px;'>"
             + "URL Validation"
             + "<span style='font-size:11px;font-weight:500;color:#94a3b8;"
             + "text-transform:none;letter-spacing:0;'>— "
             + pages + " page" + (pages == 1 ? "" : "s") + " scanned · "
             + urls + " URLs validated · "
             + findings + " finding" + (findings == 1 ? "" : "s")
             + "</span></div>";
    }

    private static String cleanBanner(int urls, int pages) {
        return "<div style='padding:14px;background:#f0fdf4;border:1px solid #bbf7d0;"
             + "border-radius:8px;font-size:13px;color:#166534;'>"
             + "✓ No URL issues detected across " + urls + " URLs over " + pages + " page"
             + (pages == 1 ? "" : "s") + "."
             + "</div>";
    }

    private static String table(JSONArray findings) {
        StringBuilder s = new StringBuilder();
        s.append("<table style='width:100%;border-collapse:collapse;font-size:12.5px;'>");
        s.append("<thead><tr style='background:#f8fafc;'>")
         .append("<th style='text-align:left;padding:8px;border-bottom:1px solid #e2e8f0;'>Finding</th>")
         .append("<th style='text-align:left;padding:8px;border-bottom:1px solid #e2e8f0;width:90px;'>Severity</th>")
         .append("<th style='text-align:left;padding:8px;border-bottom:1px solid #e2e8f0;width:110px;'>Domain</th>")
         .append("<th style='text-align:right;padding:8px;border-bottom:1px solid #e2e8f0;width:60px;'>Status</th>")
         .append("<th style='text-align:left;padding:8px;border-bottom:1px solid #e2e8f0;'>URL</th>")
         .append("<th style='text-align:left;padding:8px;border-bottom:1px solid #e2e8f0;width:90px;'>Source</th>")
         .append("</tr></thead><tbody>");
        for (Object o : findings) {
            if (!(o instanceof JSONObject f)) continue;
            String typeRaw  = jsonStr(f, "type", "");
            // Re-label SENSITIVE_PATH so the dashboard does not oversell: this
            // is a path indicator, not a security validation.  Underlying enum
            // value stays unchanged so the JSON contract is stable.
            String type     = "SENSITIVE_PATH".equals(typeRaw)
                              ? "POTENTIAL EXPOSURE (PATH INDICATOR)"
                              : typeRaw;
            String severity = jsonStr(f, "severity", "");
            String domain   = jsonStr(f, "domain", "");
            int    status   = jsonInt(f, "status", 0);
            String url      = jsonStr(f, "url", "");
            String source   = jsonStr(f, "source", "");
            String element  = jsonStr(f, "element", "");
            String reason   = jsonStr(f, "reason", "");
            String page     = jsonStr(f, "page", "");

            String statusText = status == 0 ? "—" : String.valueOf(status);
            String statusColor = status == 0 ? "#94a3b8"
                               : status >= 500 ? "#ef4444"
                               : status >= 400 ? "#f97316"
                               : status >= 300 ? "#3b82f6"
                               : "#10b981";

            s.append("<tr>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;'>")
             .append("<div style='font-weight:600;color:#1e293b;'>").append(esc(type.replace('_', ' '))).append("</div>")
             .append("<div style='font-size:11px;color:#64748b;margin-top:2px;'>").append(esc(reason)).append("</div>")
             .append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;'>").append(severityBadge(severity)).append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;'>").append(domainBadge(domain)).append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;text-align:right;font-weight:700;color:")
             .append(statusColor).append(";font-variant-numeric:tabular-nums;'>").append(statusText).append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;word-break:break-all;'>")
             .append("<div style='font-family:Consolas,monospace;font-size:11px;color:#334155;'>").append(esc(url)).append("</div>")
             .append(element.isBlank() ? "" :
                     "<div style='font-size:11px;color:#94a3b8;margin-top:2px;'>“").append(esc(element)).append("”")
             .append(page.isBlank() ? "" : (" on " + esc(page))).append("</div>")
             .append("</td>")
             .append("<td style='padding:7px 8px;border-bottom:1px solid #f1f5f9;font-size:11px;color:#64748b;'>")
             .append(esc(source.toLowerCase())).append("</td>")
             .append("</tr>");
        }
        s.append("</tbody></table>");
        return s.toString();
    }

    // ── inline badge helpers — keep aligned with SemanticPanelComponent palette ──

    private static String severityBadge(String severity) {
        String s = severity == null ? "" : severity;
        String bg, fg;
        switch (s.toLowerCase()) {
            case "critical": bg = "#fee2e2"; fg = "#991b1b"; break;
            case "high":     bg = "#fed7aa"; fg = "#9a3412"; break;
            case "medium":   bg = "#fef3c7"; fg = "#854d0e"; break;
            case "low":      bg = "#dbeafe"; fg = "#1e40af"; break;
            default:         bg = "#f1f5f9"; fg = "#475569"; break;
        }
        return "<span style='display:inline-block;padding:2px 8px;background:" + bg
             + ";color:" + fg + ";border-radius:10px;font-size:11px;font-weight:700;'>"
             + esc(s) + "</span>";
    }

    private static String domainBadge(String domain) {
        String d = domain == null ? "" : domain;
        String bg, fg;
        switch (d.toLowerCase()) {
            case "product":        bg = "#fce7f3"; fg = "#9d174d"; break;
            case "infrastructure": bg = "#fef9c3"; fg = "#854d0e"; break;
            case "framework":      bg = "#e0e7ff"; fg = "#3730a3"; break;
            case "telemetry":      bg = "#ccfbf1"; fg = "#115e59"; break;
            case "observability":  bg = "#f1f5f9"; fg = "#475569"; break;
            case "business":       bg = "#fae8ff"; fg = "#6b21a8"; break;
            default:               bg = "#f8fafc"; fg = "#94a3b8"; break;
        }
        return "<span style='display:inline-block;padding:2px 8px;background:" + bg
             + ";color:" + fg + ";border-radius:4px;font-size:11px;font-weight:600;'>"
             + esc(d) + "</span>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static int    jsonInt(JSONObject o, String k, int def)    { Object v = o.get(k); return v instanceof Number n ? n.intValue() : def; }
    private static String jsonStr(JSONObject o, String k, String def) { Object v = o.get(k); return v == null ? def : String.valueOf(v); }
}
