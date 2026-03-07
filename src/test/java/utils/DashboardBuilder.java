package utils;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray; // Added based on usage
import org.json.simple.parser.JSONParser; // Added based on usage
import org.slf4j.Logger; // Added based on usage
import org.slf4j.LoggerFactory; // Added based on usage

public final class DashboardBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(DashboardBuilder.class);
  private static final String REPORTS_DIR = "reports/trend";

  private DashboardBuilder() {
  }

  public static void main(String[] args) {
    write();
  }

  public static void write() {
    try {
      JSONObject analytics = readAnalytics();
      List<TrendDataWriter.RunData> history = TrendDataWriter.readFullHistory();

      // Release status interpretation (V4 Hardened)
      int score = getInt(analytics, "overallScore", 100);
      double smokePass = getDouble(analytics, "smokePassRate", 1.0);
      double regressionPass = getDouble(analytics, "regressionPassRate", 1.0);
      int criticalBugs = getInt(analytics, "criticalProductBugs", 0);
      int locatorSamples = getInt(analytics, "totalLocatorSamples", 0);

      HealthPolicy.ReleaseStatus releaseStatus = RiskInterpreter.interpret(
          score, smokePass, criticalBugs, regressionPass, locatorSamples);

      String html = buildHtml(analytics, history, releaseStatus);

      Path out = Paths.get(REPORTS_DIR, "dashboard.html");
      Files.createDirectories(out.getParent());
      Files.write(out, html.getBytes(StandardCharsets.UTF_8));
      LOG.info("Dashboard (Status: {}) written to {}", releaseStatus.label, out);
    } catch (Exception e) {
      LOG.error("Failed to write dashboard: {}", e.getMessage());
    }
  }

  private static JSONObject readAnalytics() {
    try {
      Path p = Paths.get("reports/analytics/test_analytics.json");
      if (Files.exists(p)) {
        return (JSONObject) new JSONParser().parse(Files.readString(p));
      }
    } catch (Exception ignored) {
    }
    return new JSONObject();
  }

  private static String buildHtml(JSONObject analytics, List<TrendDataWriter.RunData> history,
      HealthPolicy.ReleaseStatus status) {
    // Extract top-level context
    JSONObject metadata = (JSONObject) analytics.get("metadata");
    String environment = getString(metadata, "environment", "QA");
    String suite = getString(metadata, "suite", "REGRESSION");
    String gitInfo = getString(metadata, "gitBranch", "main") + " (" + getString(metadata, "gitHash", "n/a") + ")";
    long durationMs = getLong(analytics, "totalDurationMs", 0);
    long timestamp = getLong(metadata, "timestamp", System.currentTimeMillis());

    // Risk Panel
    String readyHtml = String.format(
        "<div class='risk-card %s'><div class='risk-label'>Release Status</div><div class='risk-status'>%s</div><div class='risk-desc'>%s (Score: %d)</div></div>",
        status.cssClass, status.label, getReleaseDescription(status), getInt(analytics, "overallScore", 0));

    // Failure Analysis
    JSONObject failTypes = (JSONObject) analytics.get("failureTypeCounts");
    String failureTypeHtml = buildFailureAnalysisHtml(failTypes);

    // Locator Health
    JSONObject locators = (JSONObject) analytics.get("locators");
    String locatorHtml = buildLocatorHealthHtml(locators);

    // JS Errors (Dual Channel)
    JSONObject jsErrorRoot = (JSONObject) analytics.get("jsErrors");
    boolean auditMode = (boolean) jsErrorRoot.getOrDefault("auditMode", false);
    String jsErrorHtml = buildJsErrorAnalysisHtml(jsErrorRoot);

    // Table Rows
    JSONArray tests = (JSONArray) analytics.get("tests");
    String testRows = buildTestRows(tests);

    JSONArray slowPages = (JSONArray) analytics.get("slowPages");
    String slowPageRows = buildSlowPageRows(slowPages);

    JSONArray jsProdErrors = (JSONArray) jsErrorRoot.get("product");
    String jsErrorRows = buildJsErrorRows(jsProdErrors);

    JSONArray fallbacks = (JSONArray) analytics.get("fallbacks");
    String fallbackRows = buildFallbackRows(fallbacks);

    JSONArray testFailures = (JSONArray) analytics.get("testFailures");
    String testFailureRows = buildTestFailureRows(testFailures);

    JSONArray warns = (JSONArray) analytics.get("warnings");
    String warningRows = buildWarningRows(warns);

    // Score Color
    int score = getInt(analytics, "overallScore", 0);
    String scoreColor = score >= 90 ? "#10b981"
        : score >= 75 ? "#3b82f6" : score >= 60 ? "#f59e0b" : score >= 40 ? "#f97316" : "#ef4444";

    // History Analysis for Trends
    TrendAnalysis trend = analyzeHistory(history);

    return TEMPLATE
        .replace("{{SCORE}}", String.valueOf(score))
        .replace("{{SCORE_COLOR}}", scoreColor)
        .replace("{{STATUS}}", status.label)
        .replace("{{STATUS_BADGE_CLASS}}", status.cssClass)
        .replace("{{ENV}}", environment)
        .replace("{{SUITE}}", suite)
        .replace("{{GIT}}", gitInfo)
        .replace("{{DURATION}}", formatDuration(durationMs))
        .replace("{{TIMESTAMP}}", formatTimestamp(timestamp))
        .replace("{{PENALTY_POINTS}}", String.valueOf(getDouble(analytics, "totalPenalty", 0.0)))

        .replace("{{FAILED_COUNT}}",
            String.valueOf(getInt(analytics, "failedCount", tests != null ? getFailedCount(tests) : 0)))
        .replace("{{JS_ERROR_COUNT}}", String.valueOf(prodCount(jsErrorRoot)))
        .replace("{{WARNING_COUNT}}", String.valueOf(getInt(analytics, "warningCount", 0)))
        .replace("{{SLOW_PAGES_COUNT}}", String.valueOf(slowPages != null ? slowPages.size() : 0))
        .replace("{{FALLBACK_COUNT}}", String.valueOf(fallbacks != null ? fallbacks.size() : 0))
        .replace("{{HEALTHY_COUNT}}", String.valueOf(getInt(analytics, "healthyCount", 0)))

        .replace("{{READY_PANEL}}", readyHtml)
        .replace("{{FAILURE_ANALYSIS_PANEL}}", failureTypeHtml)
        .replace("{{LOCATOR_PANEL}}", locatorHtml)
        .replace("{{JS_ERROR_PANEL}}", jsErrorHtml)
        .replace("{{AUDIT_MODE}}", auditMode ? "<span class='audit-badge'>AUDIT MODE ACTIVE</span>" : "")

        .replace("{{SLOW_PAGES_ROWS}}", slowPageRows)
        .replace("{{JS_ERRORS_ROWS}}", jsErrorRows)
        .replace("{{FALLBACK_ROWS}}", fallbackRows)
        .replace("{{TEST_FAILURES_ROWS}}", testFailureRows)
        .replace("{{WARNINGS_HIGH_ROWS}}", warningRows)
        .replace("{{WARNINGS_LOW_ROWS}}", "<tr><td colspan='2'>See analytics snapshot for raw details</td></tr>")
        .replace("{{WARNING_HIGH_COUNT}}", String.valueOf(warns != null ? warns.size() : 0))
        .replace("{{WARNING_LOW_COUNT}}", "0")

        .replace("{{TEST_ROWS}}", testRows)
        .replace("{{PASSED_STAT}}", String.valueOf(getInt(analytics, "healthyCount", 0)))
        .replace("{{FAILED_STAT}}", String.valueOf(getInt(analytics, "failedCount", 0)))

        .replace("{{HEALTHY_RUN_COUNT}}", String.valueOf(trend.healthy))
        .replace("{{MINOR_COUNT}}", String.valueOf(trend.minor))
        .replace("{{DEGRADED_COUNT}}", String.valueOf(trend.degraded))
        .replace("{{AT_RISK_COUNT}}", String.valueOf(trend.atRisk))
        .replace("{{CRITICAL_COUNT}}", String.valueOf(trend.critical))

        .replace("{{CHART_LABELS}}", trend.labels.toJSONString())
        .replace("{{CHART_DATA}}", trend.scores.toJSONString())
        .replace("{{CHART_PENALTIES}}", trend.penalties.toJSONString())
        .replace("{{CHART_STATUSES}}", trend.statuses.toJSONString())
        .replace("{{CHART_TYPES}}", trend.runTypes.toJSONString())
        .replace("{{CHART_JS_ERRORS}}", trend.jsErrors.toJSONString())
        .replace("{{CHART_FAILED_TESTS}}", trend.failedTests.toJSONString())
        .replace("{{AVG_SCORE}}", String.format("%.1f", trend.avgScore))
        .replace("{{JS_ERRORS_MAX_HEIGHT}}", jsProdErrors != null && jsProdErrors.size() > 10 ? "360px" : "none")
        .replace("{{CATEGORY_SUMMARY}}", ""); // Optional summary space
  }

  private static int prodCount(JSONObject jsRoot) {
    if (jsRoot == null)
      return 0;
    JSONArray prod = (JSONArray) jsRoot.get("product");
    return prod != null ? prod.size() : 0;
  }

  private static int getFailedCount(JSONArray tests) {
    int count = 0;
    for (Object t : tests) {
      if ("FAIL".equalsIgnoreCase(getString((JSONObject) t, "status", "")))
        count++;
    }
    return count;
  }

  private static String buildFailureAnalysisHtml(JSONObject fails) {
    if (fails == null)
      return "No data";
    int product = getInt(fails, "PRODUCT_BUG", 0);
    int auto = getInt(fails, "AUTOMATION_BUG", 0);
    int env = getInt(fails, "ENVIRONMENT", 0);
    return String.format(
        "<div class='fail-row'>Product: %d</div><div class='fail-row'>Automation: %d</div><div class='fail-row'>Env: %d</div>",
        product, auto, env);
  }

  private static String buildLocatorHealthHtml(JSONObject locators) {
    if (locators == null)
      return "No data";
    StringBuilder sb = new StringBuilder();
    for (Object key : locators.keySet()) {
      if ("globalHealth".equals(key))
        continue;
      JSONObject feature = (JSONObject) locators.get(key);
      double health = getDouble(feature, "health", 0.0);
      boolean reliable = (boolean) feature.getOrDefault("isReliable", false);

      String color = health >= 95 ? "#10b981" : health >= 85 ? "#f59e0b" : "#ef4444";
      String reliableText = reliable ? "" : " <span style='font-size:10px;color:#94a3b8;'>(low sample)</span>";

      sb.append(String.format(
          "<div class='feature-tile'><span>%s</span><span style='color:%s'>%.1f%%</span>%s</div>",
          key, color, health, reliableText));
    }
    return sb.toString();
  }

  private static String buildJsErrorAnalysisHtml(JSONObject jsRoot) {
    if (jsRoot == null)
      return "No data";
    JSONArray prod = (JSONArray) jsRoot.get("product");
    JSONArray supp = (JSONArray) jsRoot.get("suppressed");

    int prodCount = prod != null ? prod.size() : 0;
    int suppCount = supp != null ? supp.size() : 0;

    return String.format(
        "<div class='fail-row'>Product Risks: <span class='badge badge-fail'>%d</span></div>" +
            "<div class='fail-row'>Filtered Noise: <span class='badge badge-low'>%d</span></div>",
        prodCount, suppCount);
  }

  private static String buildTestRows(JSONArray tests) {
    if (tests == null)
      return "<tr><td colspan='8'>No tests</td></tr>";
    StringBuilder sb = new StringBuilder();
    for (Object item : tests) {
      JSONObject test = (JSONObject) item;
      String status = getString(test, "status", "PASS");
      String category = getString(test, "category", "");
      String badgeClass = "PASS".equalsIgnoreCase(status) ? "pass" : "fail";
      String dataStatus = "PASS".equalsIgnoreCase(status) ? "pass" : "fail";
      sb.append(String.format(
          "<tr data-status='%s' data-category='%s'><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td><span class='badge badge-%s'>%s</span></td><td>%s ms</td><td>%s</td></tr>",
          dataStatus, category,
          category, getString(test, "login", "Global"), getString(test, "feature", ""),
          getString(test, "class", ""), getString(test, "method", ""),
          badgeClass, status, getLong(test, "duration", 0),
          buildArtifactLinks(test)));
    }
    return sb.toString();
  }

  private static String buildArtifactLinks(JSONObject test) {
    String folder = getString(test, "artifacts", null);
    if (folder == null)
      return "";
    return String.format("<a href='%s' class='artifact-link' target='_blank'>Logs</a>", folder);
  }

  private static String buildSlowPageRows(JSONArray slowPages) {
    if (slowPages == null)
      return "<tr><td colspan='3'>No slow pages</td></tr>";
    StringBuilder sb = new StringBuilder();
    for (Object item : slowPages) {
      JSONObject page = (JSONObject) item;
      String severity = getString(page, "severity", "medium");
      sb.append(String.format(
          "<tr data-severity='%s'><td>%s</td><td>%d ms</td><td><span class='badge badge-%s'>%s</span></td></tr>",
          severity, getString(page, "url", "unknown"), getLong(page, "loadTime", 0), severity, severity));
    }
    return sb.toString();
  }

  private static String buildJsErrorRows(JSONArray errors) {
    if (errors == null)
      return "<tr><td colspan='3'>No JS errors</td></tr>";
    StringBuilder sb = new StringBuilder();
    for (Object item : errors) {
      JSONObject error = (JSONObject) item;
      String msg = getString(error, "message", "");
      String shortMsg = msg.length() > 80 ? msg.substring(0, 80) + "..." : msg;
      sb.append(String.format(
          "<tr data-severity='high'><td>%s</td><td><span class='badge badge-high'>High</span></td><td>" +
              "<div class='error-short %s'>%s</div><div class='error-full'>%s</div></td></tr>",
          getString(error, "url", "Internal"), msg.length() > 80 ? "expandable" : "", shortMsg, msg));
    }
    return sb.toString();
  }

  private static String buildFallbackRows(JSONArray fallbacks) {
    if (fallbacks == null)
      return "<tr><td colspan='2'>No fallbacks</td></tr>";
    StringBuilder sb = new StringBuilder();
    for (Object item : fallbacks) {
      JSONObject f = (JSONObject) item;
      sb.append(String.format("<tr><td>%s</td><td class='url-cell'>%s</td></tr>",
          getString(f, "name", "Locator"), getString(f, "target", "unknown")));
    }
    return sb.toString();
  }

  private static String buildTestFailureRows(JSONArray failures) {
    if (failures == null)
      return "<tr><td colspan='2'>No failures</td></tr>";
    StringBuilder sb = new StringBuilder();
    for (Object item : failures) {
      JSONObject f = (JSONObject) item;
      sb.append(String.format("<tr><td>%s</td><td><div class='error-msg'>%s</div></td></tr>",
          getString(f, "test", "Unknown"), getString(f, "reason", "")));
    }
    return sb.toString();
  }

  private static String buildWarningRows(JSONArray warnings) {
    if (warnings == null || warnings.isEmpty())
      return "<tr><td colspan='2'>None</td></tr>";
    StringBuilder sb = new StringBuilder();
    for (Object item : warnings) {
      String msg = item.toString();
      String source = msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : "System";
      String actualMsg = msg.contains(":") ? msg.substring(msg.indexOf(':') + 1) : msg;
      sb.append(String.format("<tr><td>%s</td><td>%s</td></tr>", source, actualMsg));
    }
    return sb.toString();
  }

  private static class TrendAnalysis {
    JSONArray labels = new JSONArray();
    JSONArray scores = new JSONArray();
    JSONArray penalties = new JSONArray();
    JSONArray statuses = new JSONArray();
    JSONArray runTypes = new JSONArray();
    JSONArray jsErrors = new JSONArray();
    JSONArray failedTests = new JSONArray();
    int healthy, minor, degraded, atRisk, critical;
    double avgScore;
  }

  @SuppressWarnings("unchecked")
  private static TrendAnalysis analyzeHistory(List<TrendDataWriter.RunData> history) {
    TrendAnalysis ta = new TrendAnalysis();
    if (history == null || history.isEmpty())
      return ta;

    long sum = 0;
    for (TrendDataWriter.RunData run : history) {
      ta.labels.add("Run " + run.run);
      ta.scores.add(run.score);
      ta.penalties.add(run.penalty);
      ta.statuses.add(run.status);
      ta.runTypes.add(run.runType != null && !run.runType.isEmpty() ? run.runType : "REGRESSION");
      ta.jsErrors.add(run.jsErrors);
      ta.failedTests.add(run.failedTests);

      sum += run.score;
      if (run.score >= 90)
        ta.healthy++;
      else if (run.score >= 75)
        ta.minor++;
      else if (run.score >= 60)
        ta.degraded++;
      else if (run.score >= 40)
        ta.atRisk++;
      else
        ta.critical++;
    }
    ta.avgScore = sum / (double) history.size();
    return ta;
  }

  private static String formatDuration(long ms) {
    if (ms < 1000)
      return ms + "ms";
    if (ms < 60000)
      return String.format("%.2fs", ms / 1000.0);
    return String.format("%dm %ds", ms / 60000, (ms % 60000) / 1000);
  }

  private static String formatTimestamp(long timestamp) {
    if (timestamp <= 0)
      return "N/A";
    return DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss")
        .withZone(ZoneId.of("Asia/Kolkata"))
        .format(Instant.ofEpochMilli(timestamp));
  }

  private static int getInt(JSONObject obj, String key, int def) {
    if (obj == null)
      return def;
    Object v = obj.get(key);
    if (v instanceof Number)
      return ((Number) v).intValue();
    return def;
  }

  private static long getLong(JSONObject obj, String key, long def) {
    if (obj == null)
      return def;
    Object v = obj.get(key);
    if (v instanceof Number)
      return ((Number) v).longValue();
    return def;
  }

  private static String getString(JSONObject obj, String key, String def) {
    if (obj == null)
      return def;
    Object v = obj.get(key);
    return v != null ? v.toString() : def;
  }

  private static double getDouble(JSONObject obj, String key, double def) {
    if (obj == null)
      return def;
    Object v = obj.get(key);
    if (v instanceof Number)
      return ((Number) v).doubleValue();
    return def;
  }

  private static String getReleaseDescription(HealthPolicy.ReleaseStatus status) {
    switch (status) {
      case READY:
        return "All criteria met. Safe for release.";
      case WARNING:
        return "Minor issues detected. Review recommended.";
      case AT_RISK:
        return "Significant regression or health drop. Proceed with caution.";
      case BLOCKED:
        return "Critical failures or policy violation. Release blocked.";
      default:
        return "Unknown status";
    }
  }

  private static final String TEMPLATE = """
      <!DOCTYPE html>
      <html lang="en">
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Health Dashboard</title>
        <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
        <style>
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;900&display=swap');
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: 'Inter', -apple-system, sans-serif; background: #f0f4f8; color: #1e293b; min-height: 100vh; }
        .container { max-width: 1400px; margin: 0 auto; padding: 24px; }
        /* ── Header ── */
        .header {
          display: flex; justify-content: space-between; align-items: center;
          background: linear-gradient(130deg, #0f172a 0%, #1e293b 55%, #1a2942 100%);
          border-radius: 16px; padding: 20px 32px; margin-bottom: 24px; color: white;
          border: 1px solid rgba(255,255,255,0.06);
          box-shadow: 0 4px 24px rgba(0,0,0,0.18);
        }
        .header-left { display: flex; flex-direction: column; gap: 10px; }
        .header-title { font-size: 26px; font-weight: 800; letter-spacing: -0.5px; color: #fff; }
        .header-chips { display: flex; gap: 8px; flex-wrap: wrap; }
        .header-chip {
          display: inline-flex; align-items: center; gap: 5px;
          background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.12);
          border-radius: 20px; padding: 3px 10px; font-size: 12px; color: #94a3b8;
        }
        .header-chip strong { color: #e2e8f0; }
        .header-right { display: flex; align-items: center; gap: 20px; }
        .score-ring {
          display: flex; flex-direction: column; align-items: center;
          background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1);
          border-radius: 14px; padding: 12px 20px;
        }
        .score-ring-label { font-size: 11px; font-weight: 600; color: #64748b; text-transform: uppercase; letter-spacing: 0.8px; margin-bottom: 4px; }
        .score-ring-value { font-size: 30px; font-weight: 900; line-height: 1; }
        .score-ring-max { font-size: 14px; font-weight: 400; color: #64748b; margin-left: 2px; }
        .status-badge {
          padding: 7px 18px; border-radius: 30px; font-size: 12px; font-weight: 700;
          text-transform: uppercase; letter-spacing: 0.8px;
          border: 1.5px solid currentColor;
        }
        .status-excellent, .status-healthy { color: #10b981; background: rgba(16,185,129,0.1); }
        .status-minor { color: #3b82f6; background: rgba(59,130,246,0.1); }
        .status-degraded { color: #f59e0b; background: rgba(245,158,11,0.1); }
        .status-atrisk { color: #f97316; background: rgba(249,115,22,0.1); }
        .status-critical { color: #ef4444; background: rgba(239,68,68,0.1); }
        /* ── Stat Cards ── */
        .stats-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 14px; margin-bottom: 24px; }
        .stat-card {
          background: white; border-radius: 12px; padding: 14px 16px;
          box-shadow: 0 1px 3px rgba(0,0,0,0.07); border: 1px solid #e2e8f0;
          display: flex; justify-content: space-between; align-items: center; gap: 10px;
          border-left: 4px solid #e2e8f0;
        }
        .stat-card.danger  { border-left-color: #ef4444; }
        .stat-card.warning { border-left-color: #f59e0b; }
        .stat-card.info    { border-left-color: #3b82f6; }
        .stat-card.success { border-left-color: #10b981; }
        .stat-label { font-size: 11px; font-weight: 600; color: #64748b; text-transform: uppercase; letter-spacing: 0.5px; line-height: 1.3; }
        .stat-value { font-size: 28px; font-weight: 800; color: #1e293b; flex-shrink: 0; }
        .stat-card.danger  .stat-value { color: #ef4444; }
        .stat-card.warning .stat-value { color: #d97706; }
        .stat-card.info    .stat-value { color: #3b82f6; }
        .stat-card.success .stat-value { color: #059669; }
        /* chart toggle pills */
        .chart-toggle-group { display: flex; gap: 6px; flex-wrap: wrap; }
        .chart-toggle {
          display: inline-flex; align-items: center; gap: 5px;
          padding: 4px 12px; border-radius: 20px; border: 1.5px solid;
          font-size: 12px; font-weight: 500; cursor: pointer; transition: all 0.2s; user-select: none;
        }
        .chart-toggle .ct-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
        .chart-toggle.active   { opacity: 1; }
        .chart-toggle.inactive { opacity: 0.35; }
        /* grouped filter for test runs */
        .filter-group { display: flex; align-items: center; gap: 4px; border: 1px solid #e2e8f0; border-radius: 8px; padding: 3px; background: #f8fafc; }
        .filter-group-label { font-size: 11px; font-weight: 600; color: #94a3b8; text-transform: uppercase; padding: 0 6px; white-space: nowrap; }
        .fg-btn {
          padding: 5px 12px; border: none; background: transparent; border-radius: 6px;
          font-size: 12px; font-weight: 500; cursor: pointer; color: #64748b;
          transition: all 0.15s; white-space: nowrap; display: flex; align-items: center; gap: 4px;
        }
        .fg-btn.active { background: #6366f1; color: #fff; }
        .fg-btn:not(.active):hover { background: #e2e8f0; color: #1e293b; }
        .fg-count { font-size: 10px; background: rgba(255,255,255,0.25); border-radius: 8px; padding: 1px 5px; font-weight: 700; }
        .section { background: white; border-radius: 12px; padding: 24px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); border: 1px solid #e2e8f0; margin-bottom: 20px; }
        .section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
        .section-title { font-size: 16px; font-weight: 700; color: #1e293b; }
        .count { font-size: 13px; font-weight: 600; background: #f1f5f9; color: #64748b; padding: 2px 8px; border-radius: 20px; margin-left: 8px; }
        .filter-controls { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
        .filter-btn { padding: 6px 14px; border-radius: 6px; border: 1px solid #e2e8f0; background: white; font-size: 12px; font-weight: 500; cursor: pointer; transition: all 0.15s; color: #64748b; }
        .filter-btn.active, .filter-btn:hover { background: #6366f1; color: white; border-color: #6366f1; }
        .collapsible-header { cursor: pointer; }
        .collapsible-content { overflow: hidden; }
        .chart-container { height: 360px; position: relative; }
        .chart-container-sm { height: 280px; position: relative; }
        .search-input {
          padding: 8px 14px;
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        font-size: 13px;
        width: 200px;
        transition: border-color 0.2s;
      }
      .search-input:focus {
        outline: none;
        border-color: #3b82f6;
      }

      /* Tables */
      table { width: 100%; border-collapse: collapse; }
      th {
        text-align: left;
        font-size: 12px;
        font-weight: 600;
        color: #64748b;
        padding: 12px 16px;
        border-bottom: 2px solid #e2e8f0;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        cursor: pointer;
        user-select: none;
        white-space: nowrap;
      }
      th:hover { color: #3b82f6; }
      th .sort-icon { margin-left: 4px; font-size: 10px; }
      td {
        padding: 14px 16px;
        font-size: 14px;
        border-bottom: 1px solid #f1f5f9;
      }
      tr:last-child td { border-bottom: none; }
      tr:hover { background: #f8fafc; }
      .url-cell { word-break: break-all; }
      .url-cell a { color: #3b82f6; text-decoration: none; }
      .url-cell a:hover { text-decoration: underline; }
      .error-msg {
        font-family: 'SF Mono', Monaco, 'Courier New', monospace;
        font-size: 12px;
        color: #64748b;
      }
      .error-short.expandable {
        cursor: pointer;
        color: #3b82f6;
      }
      .error-short.expandable::after {
        content: ' ▶ click to expand';
        font-size: 10px;
        color: #94a3b8;
        font-style: italic;
      }
      .error-full {
        display: none;
        margin-top: 8px;
        padding: 10px 14px;
        background: #f8fafc;
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        word-break: break-all;
        white-space: pre-wrap;
        font-size: 12px;
        color: #334155;
        line-height: 1.5;
      }
      .error-full.visible {
        display: block;
      }
      .error-short.expandable.expanded::after {
        content: ' ▼ click to collapse';
      }

      /* Artifact Links & Hover Preview */
      .artifact-links {
        display: flex;
        gap: 6px;
        flex-wrap: wrap;
      }
      .artifact-link {
        font-size: 11px;
        padding: 4px 8px;
        border-radius: 4px;
        background: #f1f5f9;
        color: #475569;
        text-decoration: none;
        font-weight: 500;
        border: 1px solid #e2e8f0;
        transition: all 0.2s;
        position: relative;
      }
      .artifact-link:hover {
        background: #e2e8f0;
        color: #1e293b;
        border-color: #cbd5e1;
      }
      .screenshot-link:hover::after {
        content: '';
        position: absolute;
        bottom: 100%;
        left: 50%;
        transform: translateX(-50%);
        width: 300px;
        height: 168px; /* 16:9 ratio */
        background-color: #fff;
        background-image: attr(href url);
        background-image: var(--bg-img);
        background-size: contain;
        background-repeat: no-repeat;
        background-position: center;
        border: 2px solid #e2e8f0;
        border-radius: 8px;
        box-shadow: 0 10px 25px rgba(0,0,0,0.2);
        z-index: 100;
        margin-bottom: 8px;
        pointer-events: none;
      }

      /* Pulsing latest-run marker */
      @keyframes pulse-ring {
        0% { box-shadow: 0 0 0 0 rgba(99, 102, 241, 0.6); }
        70% { box-shadow: 0 0 0 10px rgba(99, 102, 241, 0); }
        100% { box-shadow: 0 0 0 0 rgba(99, 102, 241, 0); }
      }

      /* Badges */
      .badge {
        display: inline-block;
        padding: 4px 12px;
        border-radius: 6px;
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.3px;
      }
      .badge-critical { background: #fee2e2; color: #dc2626; }
      .badge-high { background: #ffedd5; color: #ea580c; }
      .badge-medium { background: #fef3c7; color: #d97706; }
      .badge-low { background: #dcfce7; color: #16a34a; }
      .badge-pass { background: #dcfce7; color: #16a34a; }
      .badge-fail { background: #fee2e2; color: #dc2626; }

      /* Chart */
      .chart-container { height: 360px; position: relative; }
      .chart-container-sm { height: 280px; position: relative; }

      /* Trend Stats Bar */
      .trend-stats {
        display: flex;
        gap: 16px;
        margin-bottom: 12px;
        flex-wrap: wrap;
        align-items: center;
      }
      .trend-stat {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        font-weight: 500;
        color: #64748b;
      }
      .trend-dot {
        width: 12px;
        height: 12px;
        border-radius: 50%;
        display: inline-block;
      }
      .trend-dot-healthy { background: #10b981; }
      .trend-dot-minor { background: #3b82f6; }
      .trend-dot-degraded { background: #f59e0b; }
      .trend-dot-atrisk { background: #f97316; }
      .trend-dot-critical { background: #ef4444; }
      .trend-divider {
        width: 1px;
        height: 20px;
        background: #e2e8f0;
      }

      /* Two Column Layout */
      .two-col {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 20px;
      }

      /* Three Column Layout for charts */
      .chart-row {
        display: grid;
        grid-template-columns: 2fr 1fr;
        gap: 20px;
        margin-bottom: 20px;
      }

      /* Runs Header */
      .runs-stats {
        display: flex;
        gap: 20px;
        font-size: 14px;
      }
      .runs-stats span { display: flex; align-items: center; gap: 6px; }
      .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
      .dot-pass { background: #10b981; }
      .dot-fail { background: #ef4444; }

      /* Responsive */
      @media (max-width: 1200px) {
        .stats-grid { grid-template-columns: repeat(3, 1fr); }
        .two-col { grid-template-columns: 1fr; }
        .chart-row { grid-template-columns: 1fr; }
      }
      @media (max-width: 768px) {
        .stats-grid { grid-template-columns: repeat(2, 1fr); }
        body { padding: 16px; }
        .header { flex-direction: column; text-align: center; }
        .header-right { flex-direction: column; }
      }
      @media (max-width: 480px) {
        .stats-grid { grid-template-columns: 1fr; }
      }
      </style>
      </head>
      <body>

      <div class="container">
        <!-- Header -->
        <div class="header">
          <div class="header-left">
            <div class="header-title">Health Dashboard</div>
            <div class="header-chips">
              <span class="header-chip">&#x1F4C5; <strong>{{TIMESTAMP}}</strong></span>
              <span class="header-chip">Env: <strong>{{ENV}}</strong></span>
              <span class="header-chip">Suite: <strong>{{SUITE}}</strong></span>
              <span class="header-chip">Branch: <strong>{{GIT}}</strong></span>
              <span class="header-chip">Duration: <strong>{{DURATION}}</strong></span>
              <span class="header-chip">Penalty: <strong>{{PENALTY_POINTS}} pts</strong></span>
            </div>
          </div>
          <div class="header-right">
            <span class="status-badge {{STATUS_BADGE_CLASS}}">{{STATUS}}</span>
            <div class="score-ring">
              <div class="score-ring-label">Health Score</div>
              <div><span class="score-ring-value" style="color:{{SCORE_COLOR}}">{{SCORE}}</span><span class="score-ring-max">/100</span></div>
            </div>
          </div>
        </div>

        <!-- Stats Cards -->
        <div class="stats-grid">
          <div class="stat-card danger">
            <div class="stat-label">Failed Tests</div>
            <div class="stat-value">{{FAILED_COUNT}}</div>
          </div>
          <div class="stat-card warning">
            <div class="stat-label">JS Errors</div>
            <div class="stat-value">{{JS_ERROR_COUNT}}</div>
          </div>
          <div class="stat-card warning">
            <div class="stat-label">Warnings</div>
            <div class="stat-value">{{WARNING_COUNT}}</div>
          </div>
          <div class="stat-card info">
            <div class="stat-label">Slow Pages</div>
            <div class="stat-value">{{SLOW_PAGES_COUNT}}</div>
          </div>
          <div class="stat-card warning">
            <div class="stat-label">Fallbacks Used</div>
            <div class="stat-value">{{FALLBACK_COUNT}}</div>
          </div>
          <div class="stat-card success">
            <div class="stat-label">Passed Tests</div>
            <div class="stat-value">{{HEALTHY_COUNT}}</div>
          </div>
        </div>

        <!-- Health Score Trend + Penalty Breakdown -->
        <div class="chart-row">
          <div class="section">
            <div class="section-header" style="flex-direction:column;align-items:flex-start;gap:10px;">
              <div style="display:flex;justify-content:space-between;align-items:center;width:100%;">
                <div class="section-title">Health Score Trend</div>
                <div class="trend-stats">
                  <span class="trend-stat"><span class="trend-dot trend-dot-healthy"></span>Healthy: {{HEALTHY_RUN_COUNT}}</span>
                  <span class="trend-divider"></span>
                  <span class="trend-stat"><span class="trend-dot trend-dot-minor"></span>Minor: {{MINOR_COUNT}}</span>
                  <span class="trend-divider"></span>
                  <span class="trend-stat"><span class="trend-dot trend-dot-degraded"></span>Degraded: {{DEGRADED_COUNT}}</span>
                  <span class="trend-divider"></span>
                  <span class="trend-stat"><span class="trend-dot trend-dot-atrisk"></span>At Risk: {{AT_RISK_COUNT}}</span>
                  <span class="trend-divider"></span>
                  <span class="trend-stat"><span class="trend-dot trend-dot-critical"></span>Critical: {{CRITICAL_COUNT}}</span>
                </div>
              </div>
              <!-- Chart line toggle pills (replace broken Chart.js legend checkboxes) -->
              <div class="chart-toggle-group" id="chartToggles">
                <span class="chart-toggle active" id="toggle-threshold" data-dataset="1" style="color:#22c55e;border-color:#22c55e;">
                  <span class="ct-dot" style="background:#22c55e;"></span> Healthy Threshold (90)
                </span>
                <span class="chart-toggle active" id="toggle-avg" data-dataset="2" style="color:#3b82f6;border-color:#3b82f6;">
                  <span class="ct-dot" style="background:#3b82f6;"></span> Average ({{AVG_SCORE}})
                </span>
              </div>
            </div>
            <div class="chart-container">
              <canvas id="trendChart"></canvas>
            </div>
          </div>
          <div class="section">
            <div class="section-header">
              <div class="section-title">Penalty Breakdown</div>
            </div>
            <div class="chart-container-sm">
              <canvas id="penaltyChart"></canvas>
            </div>
            <div style="text-align:center;margin-top:12px;">
              <span style="font-size:32px;font-weight:700;color:{{SCORE_COLOR}};">{{SCORE}}</span>
              <span style="font-size:14px;color:#94a3b8;">/100</span>
              <div style="font-size:12px;color:#64748b;margin-top:4px;">Total Penalty: {{PENALTY_POINTS}} pts</div>
            </div>
          </div>
        </div>

        <!-- Slow Pages -->
        <div class="section">
          <div class="section-header">
            <div class="section-title">Slow Pages <span class="count">{{SLOW_PAGES_COUNT}}</span></div>
            <div class="filter-controls">
              <button class="filter-btn active" data-filter="all" data-table="slowPages">All</button>
              <button class="filter-btn" data-filter="critical" data-table="slowPages">Critical</button>
              <button class="filter-btn" data-filter="high" data-table="slowPages">High</button>
              <button class="filter-btn" data-filter="medium" data-table="slowPages">Medium</button>
            </div>
          </div>
          <table id="slowPagesTable">
            <thead>
              <tr><th data-sort="text">Page <span class="sort-icon">↕</span></th><th data-sort="duration">Load Time <span class="sort-icon">↕</span></th><th data-sort="severity">Severity <span class="sort-icon">↕</span></th></tr>
            </thead>
            <tbody>
      {{SLOW_PAGES_ROWS}}
            </tbody>
          </table>
        </div>

        <!-- Two Column: JS Errors and Warnings -->
        <div class="two-col">
          <!-- JS Errors -->
          <div class="section">
            <div class="section-header collapsible-header" data-target="jsErrorsContent">
              <div class="section-title">JS Errors <span class="count">{{JS_ERROR_COUNT}}</span></div>
              <div class="filter-controls">
                <button class="filter-btn active" data-filter="all" data-table="jsErrors">All</button>
                <button class="filter-btn" data-filter="critical" data-table="jsErrors">Critical</button>
                <button class="filter-btn" data-filter="high" data-table="jsErrors">High</button>
              </div>
            </div>
            <div class="collapsible-content" id="jsErrorsContent" style="max-height:{{JS_ERRORS_MAX_HEIGHT}};overflow-y:auto;">
              <table id="jsErrorsTable">
                <thead>
                  <tr><th style="width:150px;">Source</th><th style="width:100px;">Severity</th><th>Error Message</th></tr>
                </thead>
                <tbody>
                  {{JS_ERRORS_ROWS}}
                </tbody>
              </table>
            </div>
          </div>

          <!-- Fallback Details -->
          <div class="section">
            <div class="section-header collapsible-header" data-target="fallbackContent">
              <div class="section-title">Fallback Details <span class="count">{{FALLBACK_COUNT}}</span></div>
            </div>
            <div class="collapsible-content" id="fallbackContent">
              <table>
                <thead>
                  <tr><th>Navigation</th><th>Fallback URL</th></tr>
                </thead>
                <tbody>
      {{FALLBACK_ROWS}}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- Two Column: Test Failures and Warnings -->
        <div class="two-col">
          <!-- Test Failures Details -->
          <div class="section">
            <div class="section-header collapsible-header" data-target="testFailuresContent">
              <div class="section-title">Test Failure Details <span class="count">{{FAILED_COUNT}}</span></div>
            </div>
            <div class="collapsible-content" id="testFailuresContent">
              <table>
                <thead>
                  <tr><th>Test Name</th><th>Reason & Stack Trace</th></tr>
                </thead>
                <tbody>
                  {{TEST_FAILURES_ROWS}}
                </tbody>
              </table>
            </div>
          </div>

          <!-- Warnings Details -->
          <div class="section">
            <div class="section-header collapsible-header" data-target="warningsContent">
              <div class="section-title">Warnings Details <span class="count">{{WARNING_COUNT}}</span></div>
              <div class="filter-controls">
                <button class="filter-btn active" data-filter="all" data-table="warnings">All</button>
                <button class="filter-btn" data-filter="high" data-table="warnings">High ({{WARNING_HIGH_COUNT}})</button>
                <button class="filter-btn" data-filter="low" data-table="warnings">Low ({{WARNING_LOW_COUNT}})</button>
              </div>
            </div>
            <div class="collapsible-content" id="warningsContent">
              <div id="warningsHigh" class="warnings-group">
                <h4 style="color:#ea580c;margin:16px 0 8px;font-size:14px;">High Severity</h4>
                <table>
                  <thead><tr><th>Source</th><th>Warning</th></tr></thead>
                  <tbody>{{WARNINGS_HIGH_ROWS}}</tbody>
                </table>
              </div>
              <div id="warningsLow" class="warnings-group">
                <h4 style="color:#64748b;margin:16px 0 8px;font-size:14px;">Low Severity</h4>
                <table>
                  <thead><tr><th>Source</th><th>Warning</th></tr></thead>
                  <tbody>{{WARNINGS_LOW_ROWS}}</tbody>
                </table>
              </div>
            </div>
          </div>
        </div>

        <!-- Recent Runs -->
        <div class="section">
          <div class="section-header" style="flex-direction:column;align-items:flex-start;gap:12px;">
            <div style="display:flex;justify-content:space-between;align-items:center;width:100%;">
              <div class="section-title">Recent Test Runs</div>
              <div class="runs-stats">
                <span><span class="dot dot-pass"></span> {{PASSED_STAT}} passed</span>
                <span><span class="dot dot-fail"></span> {{FAILED_STAT}} failed</span>
              </div>
            </div>
            <!-- Smart Grouped Filter -->
            <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;width:100%;">
              <div class="filter-group">
                <span class="filter-group-label">Status</span>
                <button class="fg-btn active" data-filter="all"    data-table="tests" id="fg-all">All</button>
                <button class="fg-btn"        data-filter="pass"   data-table="tests" id="fg-pass">Passed</button>
                <button class="fg-btn"        data-filter="fail"   data-table="tests" id="fg-fail">Failed</button>
              </div>
              <div class="filter-group">
                <span class="filter-group-label">Type</span>
                <button class="fg-btn active" data-filter="all"        data-table="tests" data-filterby="category" id="fg-type-all">All</button>
                <button class="fg-btn"        data-filter="SANITY"     data-table="tests" data-filterby="category" id="fg-sanity">Sanity</button>
                <button class="fg-btn"        data-filter="SMOKE"      data-table="tests" data-filterby="category" id="fg-smoke">Smoke</button>
                <button class="fg-btn"        data-filter="REGRESSION" data-table="tests" data-filterby="category" id="fg-regression">Regression</button>
                <button class="fg-btn"        data-filter="FULL"       data-table="tests" data-filterby="category" id="fg-full">Full</button>
              </div>
              <input type="text" class="search-input" id="testSearch" placeholder="Search tests..." style="margin-left:auto;">
            </div>
          </div>
          {{CATEGORY_SUMMARY}}
          <table id="testsTable">
            <thead>
              <tr><th data-sort="text">Category</th><th data-sort="text">Login</th><th data-sort="text">Feature</th><th data-sort="text">Class <span class="sort-icon">↕</span></th><th data-sort="text">Method <span class="sort-icon">↕</span></th><th data-sort="status">Status <span class="sort-icon">↕</span></th><th data-sort="duration">Duration <span class="sort-icon">↕</span></th><th data-sort="none">Artifacts</th></tr>
            </thead>
            <tbody>
      {{TEST_ROWS}}
            </tbody>
          </table>
        </div>
      </div>

      <script>
      // Automatically add data-urls to screenshot links for hover previews
      document.addEventListener('DOMContentLoaded', () => {
          document.querySelectorAll('.screenshot-link').forEach(link => {
              link.style.setProperty('--bg-img', 'url("' + link.href + '")');
          });
      });
      // === DATA ===
      const labels = {{CHART_LABELS}};
      const scoreData = {{CHART_DATA}};
      const penaltyData = {{CHART_PENALTIES}};
      const statusData = {{CHART_STATUSES}};
      const runTypes = {{CHART_TYPES}};
      const jsErrorData = {{CHART_JS_ERRORS}};
      const failedTestData = {{CHART_FAILED_TESTS}};
      const avgScore = {{AVG_SCORE}};
      const threshold = 90;
      const currentPenalty = {{PENALTY_POINTS}};
      const currentJsErrors = {{JS_ERROR_COUNT}};
      const currentSlowPages = {{SLOW_PAGES_COUNT}};
      const currentFallbacks = {{FALLBACK_COUNT}};
      const currentWarnings = {{WARNING_COUNT}};

      // === TREND CHART ===
      if (labels.length > 0) {
        const ctx = document.getElementById('trendChart').getContext('2d');

        // Dynamic point colors based on score
        const lastIdx = scoreData.length - 1;
        const pointColors = scoreData.map((s, i) => i === lastIdx ? '#6366f1' : s >= 90 ? '#10b981' : s >= 75 ? '#3b82f6' : s >= 60 ? '#f59e0b' : s >= 40 ? '#f97316' : '#ef4444');
        const pointBorderColors = scoreData.map((s, i) => i === lastIdx ? '#4338ca' : s >= 90 ? '#059669' : s >= 75 ? '#2563eb' : s >= 60 ? '#d97706' : s >= 40 ? '#ea580c' : '#dc2626');
        const pointRadii = scoreData.map((s, i) => i === lastIdx ? 10 : 5);
        const pointStyles = scoreData.map((s, i) => i === lastIdx ? 'rectRot' : 'circle');
        const pointBorderWidths = scoreData.map((s, i) => i === lastIdx ? 3 : 2);

        // Gradient fill
        const gradient = ctx.createLinearGradient(0, 0, 0, 360);
        gradient.addColorStop(0, 'rgba(99, 102, 241, 0.15)');
        gradient.addColorStop(1, 'rgba(99, 102, 241, 0.0)');

        new Chart(ctx, {
          type: 'line',
          data: {
            labels: labels,
            datasets: [
              {
                label: 'Health Score',
                data: scoreData,
                borderColor: '#6366f1',
                backgroundColor: gradient,
                fill: true,
                tension: 0.3,
                pointRadius: pointRadii,
                pointBackgroundColor: pointColors,
                pointBorderColor: pointBorderColors,
                pointBorderWidth: pointBorderWidths,
                pointStyle: pointStyles,
                pointHoverRadius: 10,
                borderWidth: 3,
                order: 1
              },
              {
                label: 'Healthy Threshold (90)',
                data: labels.map(() => threshold),
                borderColor: 'rgba(34, 197, 94, 0.5)',
                borderWidth: 2,
                borderDash: [8, 4],
                pointRadius: 0,
                fill: false,
                order: 2
              },
              {
                label: 'Average (' + avgScore + ')',
                data: labels.map(() => avgScore),
                borderColor: 'rgba(59, 130, 246, 0.5)',
                borderWidth: 2,
                borderDash: [4, 4],
                pointRadius: 0,
                fill: false,
                order: 3
              }
            ]
          },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: {
              mode: 'index',
              intersect: false
            },
            plugins: {
              legend: {
                display: false
              },
              // External toggle wired below

              tooltip: {
                backgroundColor: '#1e293b',
                titleColor: '#fff',
                bodyColor: '#e2e8f0',
                padding: 16,
                cornerRadius: 10,
                titleFont: { size: 14, weight: '600' },
                bodyFont: { size: 12 },
                bodySpacing: 6,
                displayColors: false,
                filter: function(tooltipItem) {
                  return tooltipItem.datasetIndex === 0;
                },
                callbacks: {
                  title: function(items) {
                    return items[0].label;
                  },
                  label: function(context) {
                    const idx = context.dataIndex;
                    const score = scoreData[idx];
                    const penalty = penaltyData[idx];
                    const status = statusData[idx];
                    const jsErr = jsErrorData[idx];
                    const failedT = failedTestData[idx];
                    const statusLabel = status.replace('_', ' ');
                    const lines = [];
                    lines.push('Score: ' + score + ' / 100');
                    lines.push('Status: ' + statusLabel);
                    if (runTypes && runTypes[idx]) lines.push('Type: ' + runTypes[idx]);
                    lines.push('Penalty: ' + penalty + ' pts');
                    if (jsErr !== null && jsErr >= 0) lines.push('JS Errors: ' + jsErr);
                    if (failedT !== null && failedT >= 0) lines.push('Failed Tests: ' + failedT);
                    return lines;
                  },
                  afterLabel: function(context) {
                    const idx = context.dataIndex;
                    if (idx > 0) {
                      const diff = scoreData[idx] - scoreData[idx - 1];
                      if (diff !== 0) {
                        const arrow = diff > 0 ? 'up' : 'down';
                        return (diff > 0 ? '+' : '') + diff + ' from previous run (' + arrow + ')';
                      }
                    }
                    return '';
                  }
                }
              },
              annotation: {
                annotations: {
                  healthyZone: {
                    type: 'box',
                    yMin: 90,
                    yMax: 100,
                    backgroundColor: 'rgba(16, 185, 129, 0.08)',
                    borderWidth: 0,
                    label: {
                      display: true,
                      content: 'HEALTHY',
                      position: {x: 'end', y: 'center'},
                      color: 'rgba(16, 185, 129, 0.5)',
                      font: { size: 10, weight: '600' },
                      padding: 3
                    }
                  },
                  minorZone: {
                    type: 'box',
                    yMin: 75,
                    yMax: 90,
                    backgroundColor: 'rgba(59, 130, 246, 0.05)',
                    borderWidth: 0,
                    label: {
                      display: true,
                      content: 'MINOR',
                      position: {x: 'end', y: 'center'},
                      color: 'rgba(59, 130, 246, 0.4)',
                      font: { size: 10, weight: '600' },
                      padding: 3
                    }
                  },
                  degradedZone: {
                    type: 'box',
                    yMin: 60,
                    yMax: 75,
                    backgroundColor: 'rgba(245, 158, 11, 0.05)',
                    borderWidth: 0,
                    label: {
                      display: true,
                      content: 'DEGRADED',
                      position: {x: 'end', y: 'center'},
                      color: 'rgba(245, 158, 11, 0.4)',
                      font: { size: 10, weight: '600' },
                      padding: 3
                    }
                  },
                  atRiskZone: {
                    type: 'box',
                    yMin: 40,
                    yMax: 60,
                    backgroundColor: 'rgba(249, 115, 22, 0.05)',
                    borderWidth: 0,
                    label: {
                      display: true,
                      content: 'AT RISK',
                      position: {x: 'end', y: 'center'},
                      color: 'rgba(249, 115, 22, 0.4)',
                      font: { size: 10, weight: '600' },
                      padding: 3
                    }
                  },
                  criticalZone: {
                    type: 'box',
                    yMin: 0,
                    yMax: 40,
                    backgroundColor: 'rgba(239, 68, 68, 0.05)',
                    borderWidth: 0,
                    label: {
                      display: true,
                      content: 'CRITICAL',
                      position: {x: 'end', y: 'center'},
                      color: 'rgba(239, 68, 68, 0.35)',
                      font: { size: 10, weight: '600' },
                      padding: 3
                    }
                  },
                  healthyLine: {
                    type: 'line',
                    yMin: 90,
                    yMax: 90,
                    borderColor: 'rgba(16, 185, 129, 0.3)',
                    borderWidth: 1,
                    borderDash: [6, 3]
                  },
                  minorLine: {
                    type: 'line',
                    yMin: 75,
                    yMax: 75,
                    borderColor: 'rgba(59, 130, 246, 0.3)',
                    borderWidth: 1,
                    borderDash: [6, 3]
                  },
                  degradedLine: {
                    type: 'line',
                    yMin: 60,
                    yMax: 60,
                    borderColor: 'rgba(245, 158, 11, 0.3)',
                    borderWidth: 1,
                    borderDash: [6, 3]
                  },
                  atRiskLine: {
                    type: 'line',
                    yMin: 40,
                    yMax: 40,
                    borderColor: 'rgba(249, 115, 22, 0.3)',
                    borderWidth: 1,
                    borderDash: [6, 3]
                  }
                }
              }
            },
            scales: {
              y: {
                min: 0,
                max: 100,
                grid: { color: 'rgba(241, 245, 249, 0.8)' },
                ticks: {
                  font: { size: 12 },
                  callback: function(value) {
                    if (value === 90) return '90 ─ Healthy';
                    if (value === 75) return '75 ─ Minor';
                    if (value === 60) return '60 ─ Degraded';
                    if (value === 40) return '40 ─ At Risk';
                    return value;
                  }
                }
              },
              x: {
                grid: { display: false },
                ticks: { font: { size: 11 }, maxRotation: 45, minRotation: 0 }
              }
            }
          }
        });

        // Wire chart-toggle pills to show/hide threshold & average datasets
        const trendChartInst = Chart.getChart('trendChart');
        if (trendChartInst) {
          document.querySelectorAll('.chart-toggle').forEach(pill => {
            pill.addEventListener('click', () => {
              const dsIdx = parseInt(pill.getAttribute('data-dataset'));
              const meta = trendChartInst.getDatasetMeta(dsIdx);
              meta.hidden = !meta.hidden;
              pill.classList.toggle('active',   !meta.hidden);
              pill.classList.toggle('inactive',  meta.hidden);
              trendChartInst.update();
            });
          });
        }
      }

      // === PENALTY BREAKDOWN DONUT ===
      (function() {
        const penaltyCtx = document.getElementById('penaltyChart').getContext('2d');
        // Estimate penalty source breakdown from available snapshot data
        const jsErrPenalty = currentJsErrors * 10;  // ~10 pts per JS error
        const slowPagePenalty = currentSlowPages * 5; // ~5 pts per slow page
        const fallbackPenalty = currentFallbacks * 3; // ~3 pts per fallback
        const otherPenalty = Math.max(0, currentPenalty - jsErrPenalty - slowPagePenalty - fallbackPenalty);
        const remaining = Math.max(0, 100 - currentPenalty);

        new Chart(penaltyCtx, {
          type: 'doughnut',
          data: {
            labels: ['JS Errors', 'Slow Pages', 'Fallbacks', 'Other Warnings', 'Healthy'],
            datasets: [{
              data: [jsErrPenalty, slowPagePenalty, fallbackPenalty, otherPenalty, remaining],
              backgroundColor: [
                'rgba(239, 68, 68, 0.8)',
                'rgba(59, 130, 246, 0.8)',
                'rgba(245, 158, 11, 0.8)',
                'rgba(148, 163, 184, 0.6)',
                'rgba(16, 185, 129, 0.8)'
              ],
              borderColor: '#fff',
              borderWidth: 3,
              hoverOffset: 6
            }]
          },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            cutout: '65%',
            plugins: {
              legend: {
                display: true,
                position: 'bottom',
                labels: {
                  usePointStyle: true,
                  padding: 12,
                  font: { size: 11 },
                  filter: function(item) {
                    return item.raw > 0;
                  }
                }
              },
              tooltip: {
                backgroundColor: '#1e293b',
                titleColor: '#fff',
                bodyColor: '#e2e8f0',
                padding: 12,
                cornerRadius: 8,
                callbacks: {
                  label: function(context) {
                    const val = context.raw;
                    return context.label + ': ' + val + ' pts';
                  }
                }
              }
            }
          }
        });
      })();

      // === INTERACTIVITY ===

      // Collapsible sections
      document.querySelectorAll('.collapsible-header').forEach(header => {
        header.addEventListener('click', (e) => {
          if (e.target.closest('.filter-controls')) return;
          const targetId = header.getAttribute('data-target');
          const content = document.getElementById(targetId);
          header.classList.toggle('collapsed');
          content.classList.toggle('collapsed');
        });
      });

      // Current active filters state
      let activeStatusFilter = 'all';
      let activeCategoryFilter = 'all';

      // Generic filter buttons (slowPages, jsErrors, warnings)
      document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          const table = btn.getAttribute('data-table');
          const filter = btn.getAttribute('data-filter');
          btn.parentElement.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
          btn.classList.add('active');
          if (table === 'slowPages') { filterTable('slowPagesTable', 'data-severity', filter); }
          else if (table === 'jsErrors') { filterTable('jsErrorsTable', 'data-severity', filter); }
          else if (table === 'warnings') {
            const highEl = document.getElementById('warningsHigh');
            const lowEl = document.getElementById('warningsLow');
            highEl.style.display = (filter === 'all' || filter === 'high') ? 'block' : 'none';
            lowEl.style.display  = (filter === 'all' || filter === 'low')  ? 'block' : 'none';
          }
        });
      });

      // Grouped fg-btn filters for test runs (Status group + Type group)
      document.querySelectorAll('.fg-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          const filter   = btn.getAttribute('data-filter');
          const filterBy = btn.getAttribute('data-filterby');
          // reset only buttons in same filter-group
          btn.closest('.filter-group').querySelectorAll('.fg-btn').forEach(b => b.classList.remove('active'));
          btn.classList.add('active');
          if (filterBy === 'category') {
            activeCategoryFilter = filter;
          } else {
            activeStatusFilter = filter;
          }
          applyTestFilter();
        });
      });

      function applyTestFilter() {
        const searchQuery = (document.getElementById('testSearch')?.value || '').toLowerCase();
        document.querySelectorAll('#testsTable tbody tr').forEach(row => {
          const rowStatus = (row.getAttribute('data-status') || '').toLowerCase();
          const rowCategory = (row.getAttribute('data-category') || '').toUpperCase();
          const rowText = row.textContent.toLowerCase();

          const statusOk = activeStatusFilter === 'all' || rowStatus === activeStatusFilter;
          const categoryOk = activeCategoryFilter === 'all' || rowCategory === activeCategoryFilter.toUpperCase();
          const searchOk = !searchQuery || rowText.includes(searchQuery);

          row.style.display = (statusOk && categoryOk && searchOk) ? '' : 'none';
        });
      }

      function filterTable(tableId, attr, value) {
        const rows = document.querySelectorAll('#' + tableId + ' tbody tr');
        rows.forEach(row => {
          if (value === 'all' || row.getAttribute(attr) === value) {
            row.style.display = '';
          } else {
            row.style.display = 'none';
          }
        });
      }

      // Search (respects active category + status filters)
      document.getElementById('testSearch')?.addEventListener('input', () => {
        applyTestFilter();
      });

      // Expandable JS error messages
      document.querySelectorAll('.error-short.expandable').forEach(el => {
        el.addEventListener('click', () => {
          el.classList.toggle('expanded');
          const fullEl = el.nextElementSibling;
          if (fullEl) fullEl.classList.toggle('visible');
        });
      });

      // Table sorting
      document.querySelectorAll('th[data-sort]').forEach(th => {
        th.addEventListener('click', () => {
          const table = th.closest('table');
          const tbody = table.querySelector('tbody');
          const rows = Array.from(tbody.querySelectorAll('tr'));
          const colIndex = Array.from(th.parentElement.children).indexOf(th);
          const sortType = th.getAttribute('data-sort');
          const isAsc = th.classList.contains('sort-asc');

          // Reset other headers
          table.querySelectorAll('th').forEach(h => h.classList.remove('sort-asc', 'sort-desc'));
          th.classList.add(isAsc ? 'sort-desc' : 'sort-asc');

          rows.sort((a, b) => {
            const aVal = a.children[colIndex]?.textContent || '';
            const bVal = b.children[colIndex]?.textContent || '';

            if (sortType === 'duration') {
              const parseMs = (s) => {
                const m = s.match(/(\\d+)m\\s*(\\d+)s/);
                if (m) return parseInt(m[1]) * 60000 + parseInt(m[2]) * 1000;
                const sec = s.match(/([\\d.]+)s/);
                if (sec) return parseFloat(sec[1]) * 1000;
                const ms = s.match(/(\\d+)ms/);
                if (ms) return parseInt(ms[1]);
                return 0;
              };
              return (isAsc ? -1 : 1) * (parseMs(aVal) - parseMs(bVal));
            } else if (sortType === 'severity') {
              const order = {critical: 0, high: 1, medium: 2, low: 3};
              const aO = order[aVal.toLowerCase()] ?? 99;
              const bO = order[bVal.toLowerCase()] ?? 99;
              return (isAsc ? -1 : 1) * (aO - bO);
            } else if (sortType === 'status') {
              return (isAsc ? -1 : 1) * aVal.localeCompare(bVal);
            } else {
              return (isAsc ? -1 : 1) * aVal.localeCompare(bVal);
            }
          });

          rows.forEach(row => tbody.appendChild(row));
        });
      });
      </script>
      </body>
      </html>
      """;
}