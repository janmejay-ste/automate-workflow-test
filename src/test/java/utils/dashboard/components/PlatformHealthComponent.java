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
 * Phase D1 — Dashboard component for the Platform Health section.
 *
 * <p>Reads from {@code reports/trend/health_snapshot.json} and renders four
 * sub-panels corresponding to the {@code platformHealth} categories
 * (per {@code config/complexity_budget.json → platformHealthCategories}) plus
 * the {@code enforcement} block (which logically belongs with platformHealth
 * but is emitted as a peer top-level field):</p>
 * <ul>
 *   <li>Build Gate Enforcement</li>
 *   <li>Snapshot Pipeline</li>
 *   <li>Governance</li>
 *   <li>Schema Evolution (DEFERRED stub until B4)</li>
 * </ul>
 *
 * <p>Section is open-by-default, collapsible via the same toggle pattern
 * established for the Recordings section in {@code DashboardBuilder}. Each
 * sub-panel's left-border color encodes status (green=clean, amber=warning,
 * red=problem), so an operator scanning vertically can spot trouble without
 * reading text.</p>
 *
 * <p>Architectural contract (mirrors {@code SemanticPanelComponent}):</p>
 * <ul>
 *   <li>This is a <strong>dumb presentation layer</strong>. It does not interpret
 *       severity, ownership routing, or "why this matters" framing — those
 *       decisions live in {@code utils.health.semantic.*}.</li>
 *   <li>Returns "" gracefully when the snapshot is missing or malformed; the
 *       dashboard simply doesn't render the section.</li>
 *   <li>No inline JavaScript — relies on {@code toggleRecordingsSection}-style
 *       handler installed by {@code DashboardBuilder.buildVideoScripts}.</li>
 * </ul>
 */
public final class PlatformHealthComponent {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformHealthComponent.class);
    private static final Path SNAPSHOT_PATH = Paths.get("reports", "trend", "health_snapshot.json");

    private PlatformHealthComponent() {}

    public static String render() {
        try {
            if (!Files.exists(SNAPSHOT_PATH)) return "";
            String raw = new String(Files.readAllBytes(SNAPSHOT_PATH), StandardCharsets.UTF_8);
            JSONObject root = (JSONObject) new JSONParser().parse(raw);

            JSONObject enforcement = (JSONObject) root.get("enforcement");
            JSONObject platformHealth = (JSONObject) root.get("platformHealth");

            // Both blocks must exist for the panel to be meaningful. If platformHealth
            // is absent (very old snapshot pre-A.5.3), return "" rather than half-render.
            if (platformHealth == null) return "";

            StringBuilder sb = new StringBuilder(4096);
            // Collapsed-by-default: this panel answers "can you trust the numbers?"
            // which is a second-screen concern. Stakeholders triaging a red run
            // should land on Release Decision + Triage + Blockers first; this is
            // available one click away.
            sb.append("<div class='section platform-health-section collapsed' id='sec-platform-health'>");
            sb.append("<div class='section-header platform-health-toggle' "
                    + "onclick='toggleCollapsibleSection(this, \"platform-health-content\")' "
                    + "role='button' tabindex='0' "
                    + "aria-expanded='false' aria-controls='platform-health-content' "
                    + "title='Click to expand/collapse'>");
            sb.append("<div class='section-title'>"
                    + "<span class='collapsible-chevron' aria-hidden='true'>▶</span> "
                    + "Platform Health "
                    + "<span style='font-size:11px;font-weight:500;color:#94a3b8;"
                    + "text-transform:none;letter-spacing:0;margin-left:8px;'>"
                    + "— snapshot pipeline, governance, gate enforcement</span>"
                    + "</div></div>");

            sb.append("<div class='collapsible-content' id='platform-health-content'>");
            sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));"
                    + "gap:12px;margin-top:14px;'>");

            sb.append(enforcementCard(enforcement));
            sb.append(snapshotPipelineCard((JSONObject) platformHealth.get("snapshot_pipeline")));
            sb.append(governanceCard((JSONObject) platformHealth.get("governance")));
            sb.append(schemaEvolutionCard((JSONObject) platformHealth.get("schema_evolution")));

            sb.append("</div>");
            sb.append("</div>");   // collapsible-content
            sb.append("</div>");   // section
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("[PlatformHealthComponent] render failed: {}", e.getMessage());
            return "";
        }
    }

    // ── sub-panels ───────────────────────────────────────────────────────────

    /** Build-gate enforcement: which mode, whether it would block, any violations. */
    private static String enforcementCard(JSONObject enforcement) {
        if (enforcement == null) {
            return phCard("Build Gate", "#cbd5e1", "Not run yet", "GATE_NOT_YET_RUN", null);
        }
        String mode  = str(enforcement, "mode", "advisory");
        boolean wouldBlock = bool(enforcement, "wouldHaveBlocked");
        boolean killed     = bool(enforcement, "killSwitchActive");
        String reason      = str(enforcement, "reasonIfNotEnforcing", null);

        String accent, headline, sub;
        if (killed) {
            accent   = "#dc2626";
            headline = "Kill switch ACTIVE";
            sub      = "Gate cannot fail regardless of mode";
        } else if ("blocking".equalsIgnoreCase(mode) && wouldBlock) {
            accent   = "#dc2626";
            headline = "BLOCKING — build will fail";
            sub      = "Violations present";
        } else if (wouldBlock) {
            accent   = "#ea580c";
            headline = "Would have blocked (advisory)";
            sub      = "Run is allowed; flip mode=blocking to enforce";
        } else if ("blocking".equalsIgnoreCase(mode)) {
            accent   = "#2563eb";
            headline = "BLOCKING — no violations";
            sub      = "Build passes";
        } else {
            accent   = "#10b981";
            headline = "Advisory — no violations";
            sub      = "Default mode, build passes";
        }

        // Show violation list when present
        JSONArray violations = (JSONArray) enforcement.get("violations");
        StringBuilder detail = new StringBuilder();
        if (violations != null && !violations.isEmpty()) {
            detail.append("<ul style='margin:8px 0 0 0;padding-left:20px;font-size:11px;color:#475569;'>");
            int shown = 0;
            for (Object v : violations) {
                if (shown++ >= 3) break;
                detail.append("<li style='margin-bottom:4px;'>")
                      .append(esc(String.valueOf(v).split("\n")[0]))
                      .append("</li>");
            }
            detail.append("</ul>");
        }
        return phCard("Build Gate", accent, headline, sub, detail.toString());
    }

    /** Snapshot pipeline: write errors + previous-score-source. */
    private static String snapshotPipelineCard(JSONObject sp) {
        if (sp == null) return phCard("Snapshot Pipeline", "#cbd5e1", "Not reporting", null, null);
        int writeErrors    = jsonInt(sp, "writeErrors", 0);
        String lastError   = str(sp, "lastWriteError", null);
        String prevSource  = str(sp, "previousScoreSource", "NO_DATA");

        String accent;
        String headline;
        if (writeErrors > 0) {
            accent   = "#dc2626";
            headline = writeErrors + " write error(s)";
        } else if ("MALFORMED".equals(prevSource)) {
            accent   = "#ea580c";
            headline = "Previous snapshot malformed";
        } else if ("NO_DATA".equals(prevSource)) {
            accent   = "#3b82f6";
            headline = "First run — no predecessor";
        } else if ("CSV".equals(prevSource)) {
            accent   = "#f59e0b";
            headline = "Reading from CSV fallback";
        } else {
            accent   = "#10b981";
            headline = "Healthy";
        }

        StringBuilder detail = new StringBuilder();
        detail.append("<div style='font-size:11px;color:#64748b;line-height:1.5;'>")
              .append("Source: <code style='background:#f1f5f9;padding:1px 5px;border-radius:3px;'>")
              .append(esc(prevSource)).append("</code>");
        if (lastError != null) {
            detail.append("<br>Last error: ").append(esc(truncate(lastError, 60)));
        }
        detail.append("</div>");
        return phCard("Snapshot Pipeline", accent, headline,
                writeErrors == 0 ? "All writes successful this JVM" : "Counter survives across runs",
                detail.toString());
    }

    /** Governance: complexity budget + coverage catalog + gate-mode validity. */
    private static String governanceCard(JSONObject gov) {
        if (gov == null) return phCard("Governance", "#cbd5e1", "Not reporting", null, null);
        boolean budgetLoaded     = bool(gov, "complexityBudgetLoaded");
        String  budgetErr        = str(gov, "complexityBudgetLoadError", null);
        boolean catalogLoaded    = bool(gov, "coverageCatalogLoaded");
        int     catalogOutcomes  = jsonInt(gov, "coverageCatalogOutcomes", 0);
        String  catalogErr       = str(gov, "coverageCatalogLoadError", null);
        boolean modeValid        = bool(gov, "healthGateModeInputValid");
        String  modeRaw          = str(gov, "healthGateModeInputRaw", null);

        boolean anyProblem = !budgetLoaded || !catalogLoaded || !modeValid;
        String accent = anyProblem ? "#dc2626" : "#10b981";
        String headline = anyProblem ? "Configuration issue" : "Configuration loaded";

        StringBuilder detail = new StringBuilder();
        detail.append("<div style='font-size:11px;color:#475569;line-height:1.6;'>");
        detail.append(govIndicator("Complexity budget", budgetLoaded, budgetErr));
        detail.append(govIndicator("Coverage catalog",  catalogLoaded,
                catalogErr != null ? catalogErr : catalogOutcomes + " outcome(s)"));
        detail.append(govIndicator("Gate mode input",   modeValid,
                modeRaw != null ? "unrecognised: " + modeRaw : "valid"));
        detail.append("</div>");
        return phCard("Governance", accent, headline, null, detail.toString());
    }

    /** Schema evolution: DEFERRED stub until B4. Cosmetic until then. */
    private static String schemaEvolutionCard(JSONObject se) {
        String status = se == null ? "UNKNOWN" : str(se, "status", "UNKNOWN");
        String note   = se == null ? null      : str(se, "note", null);
        String accent = "DEFERRED".equals(status) ? "#94a3b8" : "#3b82f6";
        return phCard("Schema Evolution", accent, status,
                note != null ? note : "Pending wiring", null);
    }

    // ── primitives ───────────────────────────────────────────────────────────

    private static String phCard(String title, String accent, String headline,
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
            s.append("<div style='font-size:12px;color:#94a3b8;'>").append(esc(sub)).append("</div>");
        }
        if (detailHtml != null && !detailHtml.isEmpty()) {
            s.append(detailHtml);
        }
        s.append("</div>");
        return s.toString();
    }

    private static String govIndicator(String label, boolean ok, String note) {
        String icon  = ok ? "✓" : "✗";
        String color = ok ? "#10b981" : "#dc2626";
        return "<div style='display:flex;justify-content:space-between;gap:8px;"
             + "padding:2px 0;'>"
             + "<span style='color:" + color + ";font-weight:700;'>" + icon + "</span>"
             + "<span style='flex:1;'>" + esc(label) + "</span>"
             + (note == null ? ""
                  : "<span style='font-family:Consolas,monospace;font-size:10px;"
                  + "color:#64748b;text-align:right;'>" + esc(truncate(note, 30)) + "</span>")
             + "</div>";
    }

    private static int jsonInt(JSONObject o, String k, int def) {
        Object v = o == null ? null : o.get(k);
        return v instanceof Number n ? n.intValue() : def;
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

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max - 1) + "…";
    }
}
