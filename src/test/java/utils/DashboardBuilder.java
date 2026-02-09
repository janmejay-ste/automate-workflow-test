package utils;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DashboardBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(DashboardBuilder.class);
  private static final String REPORTS_DIR = "reports/trend";

  private DashboardBuilder() {
  }

  public static void write() {
    try {
      // Read data from files
      JSONObject snapshot = readSnapshot();
      List<int[]> history = readHistory();
      List<TestResult> testResults = readTestResults();

      // Build HTML with embedded data
      String html = buildHtml(snapshot, history, testResults);

      Path out = Paths.get(REPORTS_DIR, "dashboard.html");
      Files.createDirectories(out.getParent());
      Files.write(out, html.getBytes(StandardCharsets.UTF_8));
      LOG.info("Dashboard written to {}", out);
    } catch (Exception e) {
      LOG.warn("Failed to write dashboard: {}", e.getMessage());
    }
  }

  private static JSONObject readSnapshot() {
    try {
      Path p = Paths.get(REPORTS_DIR, "health_snapshot.json");
      if (Files.exists(p)) {
        String content = Files.readString(p);
        return (JSONObject) new JSONParser().parse(content);
      }
    } catch (Exception e) {
      LOG.debug("Could not read snapshot: {}", e.getMessage());
    }
    return new JSONObject();
  }

  private static List<int[]> readHistory() {
    List<int[]> history = new ArrayList<>();
    try {
      Path p = Paths.get(REPORTS_DIR, "health_history.csv");
      if (Files.exists(p)) {
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) { // Skip header
          String line = lines.get(i).trim();
          if (line.isEmpty())
            continue;
          String[] parts = line.split(",");
          if (parts.length >= 2) {
            try {
              history.add(new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) });
            } catch (NumberFormatException ignored) {
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not read history: {}", e.getMessage());
    }
    return history;
  }

  private static List<TestResult> readTestResults() {
    List<TestResult> results = new ArrayList<>();
    try {
      Path p = Paths.get("test-output/testng-results.xml");
      if (Files.exists(p)) {
        results = TestNgResultParser.parse().stream()
            .map(r -> new TestResult(r.className, r.methodName, r.status, r.durationMs))
            .toList();
      }
    } catch (Exception e) {
      LOG.debug("Could not read test results: {}", e.getMessage());
    }
    return results;
  }

  private static class TestResult {
    final String className;
    final String methodName;
    final String status;
    final long durationMs;

    TestResult(String className, String methodName, String status, long durationMs) {
      this.className = className;
      this.methodName = methodName;
      this.status = status;
      this.durationMs = durationMs;
    }
  }

  // Format duration: < 60s -> "26.38s", >= 60s -> "2m 21s"
  private static String formatDuration(long ms) {
    if (ms < 1000) {
      return ms + "ms";
    } else if (ms < 60000) {
      return String.format("%.2fs", ms / 1000.0);
    } else {
      long minutes = ms / 60000;
      long seconds = (ms % 60000) / 1000;
      return String.format("%dm %ds", minutes, seconds);
    }
  }

  // Format timestamp to readable date
  private static String formatTimestamp(long timestamp) {
    if (timestamp <= 0)
      return "N/A";
    return DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp));
  }

  private static String buildHtml(JSONObject snapshot, List<int[]> history, List<TestResult> testResults) {
    // Extract data from snapshot
    int score = getInt(snapshot, "score", 0);
    int warningCount = getInt(snapshot, "warningCount", 0);
    int jsErrorCount = getInt(snapshot, "jsErrorCount", 0);
    int slowPagesCount = getInt(snapshot, "slowPagesCount", 0);
    int fallbackCount = getInt(snapshot, "fallbackCount", 0);
    int penaltyPoints = getInt(snapshot, "penaltyPoints", 0);
    long timestamp = getLong(snapshot, "timestamp", 0);
    String status = getString(snapshot, "status", "UNKNOWN");

    // Get slow pages
    JSONArray slowPagesDetails = (JSONArray) snapshot.get("slowPagesDetails");
    StringBuilder slowPagesHtml = new StringBuilder();
    if (slowPagesDetails != null && !slowPagesDetails.isEmpty()) {
      for (Object item : slowPagesDetails) {
        String detail = item.toString();
        String[] parts = detail.split(" = ");
        String page = parts[0];
        String timeStr = parts.length > 1 ? parts[1].replace(" ms", "") : "0";
        long time = 0;
        try {
          time = Long.parseLong(timeStr);
        } catch (Exception ignored) {
        }
        String severity = time > 20000 ? "critical" : time > 15000 ? "high" : time > 10000 ? "medium" : "low";
        slowPagesHtml.append(String.format(
            "<tr data-severity=\"%s\"><td>%s</td><td>%s</td><td><span class=\"badge badge-%s\">%s</span></td></tr>\n",
            severity, escapeHtml(page), formatDuration(time), severity, capitalize(severity)));
      }
    } else {
      slowPagesHtml
          .append("<tr><td colspan=\"3\" style=\"text-align:center;color:#94a3b8;\">No slow pages detected</td></tr>");
    }

    // Get JS errors
    JSONArray jsErrors = (JSONArray) snapshot.get("jsErrors");
    StringBuilder jsErrorsHtml = new StringBuilder();
    if (jsErrors != null && !jsErrors.isEmpty()) {
      int idx = 0;
      for (Object item : jsErrors) {
        String error = item.toString();
        String[] parts = error.split(" → ", 2);
        String source = parts.length > 0 ? parts[0] : "Unknown";
        String message = parts.length > 1 ? parts[1] : error;
        // Truncate long messages
        String shortMsg = message.length() > 100 ? message.substring(0, 100) + "..." : message;
        jsErrorsHtml.append(String.format(
            "<tr class=\"js-error-row\"><td>%s</td><td class=\"error-msg\" title=\"%s\">%s</td></tr>\n",
            escapeHtml(source), escapeHtml(message), escapeHtml(shortMsg)));
        idx++;
      }
    } else {
      jsErrorsHtml
          .append("<tr><td colspan=\"2\" style=\"text-align:center;color:#94a3b8;\">No JS errors detected</td></tr>");
    }

    // Get warnings
    JSONArray warnings = (JSONArray) snapshot.get("warnings");
    StringBuilder warningsHighHtml = new StringBuilder();
    StringBuilder warningsLowHtml = new StringBuilder();
    int highCount = 0, lowCount = 0;
    if (warnings != null && !warnings.isEmpty()) {
      for (Object item : warnings) {
        String warning = item.toString();
        boolean isHigh = warning.startsWith("[HIGH]");
        String cleanWarning = warning.replaceFirst("^\\[(HIGH|LOW)\\]\\s*", "");
        String[] parts = cleanWarning.split(" - ", 2);
        String source = parts.length > 0 ? parts[0] : "Unknown";
        String message = parts.length > 1 ? parts[1] : cleanWarning;
        String shortMsg = message.length() > 80 ? message.substring(0, 80) + "..." : message;

        String row = String.format(
            "<tr><td>%s</td><td title=\"%s\">%s</td></tr>\n",
            escapeHtml(source), escapeHtml(message), escapeHtml(shortMsg));
        if (isHigh) {
          warningsHighHtml.append(row);
          highCount++;
        } else {
          warningsLowHtml.append(row);
          lowCount++;
        }
      }
    }

    // Get fallback details
    JSONArray fallbackDetails = (JSONArray) snapshot.get("fallbackDetails");
    StringBuilder fallbackHtml = new StringBuilder();
    if (fallbackDetails != null && !fallbackDetails.isEmpty()) {
      for (Object item : fallbackDetails) {
        String detail = item.toString();
        String[] parts = detail.split(" → ", 2);
        String nav = parts.length > 0 ? parts[0] : "Unknown";
        String url = parts.length > 1 ? parts[1] : detail;
        fallbackHtml.append(String.format(
            "<tr><td>%s</td><td class=\"url-cell\"><a href=\"%s\" target=\"_blank\">%s</a></td></tr>\n",
            escapeHtml(nav), escapeHtml(url), escapeHtml(url.length() > 60 ? url.substring(0, 60) + "..." : url)));
      }
    } else {
      fallbackHtml
          .append("<tr><td colspan=\"2\" style=\"text-align:center;color:#94a3b8;\">No fallbacks used</td></tr>");
    }

    // Build chart data - limit to last 15 runs for clarity
    int maxRuns = 15;
    int startIdx = Math.max(0, history.size() - maxRuns);
    List<int[]> recentHistory = history.subList(startIdx, history.size());

    StringBuilder chartLabels = new StringBuilder("[");
    StringBuilder chartData = new StringBuilder("[");
    int totalScore = 0;
    for (int i = 0; i < recentHistory.size(); i++) {
      if (i > 0) {
        chartLabels.append(",");
        chartData.append(",");
      }
      chartLabels.append("\"Run #").append(recentHistory.get(i)[0]).append("\"");
      int runScore = recentHistory.get(i)[1];
      chartData.append(runScore);
      totalScore += runScore;
    }
    chartLabels.append("]");
    chartData.append("]");
    int avgScore = recentHistory.isEmpty() ? 0 : totalScore / recentHistory.size();

    // Count test results
    int passed = 0, failed = 0;
    StringBuilder testRowsHtml = new StringBuilder();
    for (TestResult r : testResults) {
      if ("PASS".equalsIgnoreCase(r.status))
        passed++;
      else if ("FAIL".equalsIgnoreCase(r.status))
        failed++;

      // Skip setup/teardown methods in display
      if (r.methodName.contains("setUp") || r.methodName.contains("tearDown") ||
          r.methodName.equals("beforeSuite") || r.methodName.equals("afterSuite"))
        continue;

      String badgeClass = "PASS".equalsIgnoreCase(r.status) ? "pass" : "fail";
      testRowsHtml.append(String.format(
          "<tr data-status=\"%s\"><td>%s</td><td>%s</td><td><span class=\"badge badge-%s\">%s</span></td><td>%s</td></tr>\n",
          r.status.toLowerCase(), escapeHtml(r.className), escapeHtml(r.methodName),
          badgeClass, r.status, formatDuration(r.durationMs)));
    }
    if (testRowsHtml.length() == 0) {
      testRowsHtml.append(
          "<tr><td colspan=\"4\" style=\"text-align:center;color:#94a3b8;\">No test results available</td></tr>");
    }

    // Determine score color and status badge
    String scoreColor = score >= 80 ? "#10b981" : score >= 50 ? "#f59e0b" : "#ef4444";
    String statusBadgeClass = "AT_RISK".equals(status) ? "badge-warning"
        : "CRITICAL".equals(status) ? "badge-critical" : "badge-healthy";

    return TEMPLATE
        .replace("{{SCORE}}", String.valueOf(score))
        .replace("{{SCORE_COLOR}}", scoreColor)
        .replace("{{STATUS}}", status.replace("_", " "))
        .replace("{{STATUS_BADGE_CLASS}}", statusBadgeClass)
        .replace("{{TIMESTAMP}}", formatTimestamp(timestamp))
        .replace("{{PENALTY_POINTS}}", String.valueOf(penaltyPoints))
        .replace("{{FAILED_COUNT}}", String.valueOf(failed))
        .replace("{{JS_ERROR_COUNT}}", String.valueOf(jsErrorCount))
        .replace("{{WARNING_COUNT}}", String.valueOf(warningCount))
        .replace("{{WARNING_HIGH_COUNT}}", String.valueOf(highCount))
        .replace("{{WARNING_LOW_COUNT}}", String.valueOf(lowCount))
        .replace("{{SLOW_PAGES_COUNT}}", String.valueOf(slowPagesCount))
        .replace("{{FALLBACK_COUNT}}", String.valueOf(fallbackCount))
        .replace("{{HEALTHY_COUNT}}", String.valueOf(passed))
        .replace("{{PASSED_COUNT}}", String.valueOf(passed))
        .replace("{{SLOW_PAGES_ROWS}}", slowPagesHtml.toString())
        .replace("{{JS_ERRORS_ROWS}}", jsErrorsHtml.toString())
        .replace("{{WARNINGS_HIGH_ROWS}}", warningsHighHtml.length() > 0 ? warningsHighHtml.toString()
            : "<tr><td colspan=\"2\" style=\"text-align:center;color:#94a3b8;\">No high severity warnings</td></tr>")
        .replace("{{WARNINGS_LOW_ROWS}}",
            warningsLowHtml.length() > 0 ? warningsLowHtml.toString()
                : "<tr><td colspan=\"2\" style=\"text-align:center;color:#94a3b8;\">No low severity warnings</td></tr>")
        .replace("{{FALLBACK_ROWS}}", fallbackHtml.toString())
        .replace("{{CHART_LABELS}}", chartLabels.toString())
        .replace("{{CHART_DATA}}", chartData.toString())
        .replace("{{AVG_SCORE}}", String.valueOf(avgScore))
        .replace("{{TEST_ROWS}}", testRowsHtml.toString())
        .replace("{{PASSED_STAT}}", String.valueOf(passed))
        .replace("{{FAILED_STAT}}", String.valueOf(failed));
  }

  private static int getInt(JSONObject obj, String key, int def) {
    Object v = obj.get(key);
    if (v instanceof Number)
      return ((Number) v).intValue();
    return def;
  }

  private static long getLong(JSONObject obj, String key, long def) {
    Object v = obj.get(key);
    if (v instanceof Number)
      return ((Number) v).longValue();
    return def;
  }

  private static String getString(JSONObject obj, String key, String def) {
    Object v = obj.get(key);
    if (v != null)
      return v.toString();
    return def;
  }

  private static String escapeHtml(String s) {
    if (s == null)
      return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  private static String capitalize(String s) {
    if (s == null || s.isEmpty())
      return s;
    return s.substring(0, 1).toUpperCase() + s.substring(1);
  }

  private static final String TEMPLATE = """
      <!DOCTYPE html>
      <html lang="en">
      <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Health Dashboard</title>
      <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
      <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
      <style>
      * { box-sizing: border-box; margin: 0; padding: 0; }
      body {
        font-family: 'Inter', -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
        background: linear-gradient(135deg, #f0f4f8 0%, #e2e8f0 100%);
        color: #1e293b;
        line-height: 1.6;
        padding: 32px;
        min-height: 100vh;
      }
      .container { max-width: 1400px; margin: 0 auto; }

      /* Header */
      .header {
        background: linear-gradient(135deg, #1e293b 0%, #334155 100%);
        border-radius: 16px;
        padding: 24px 32px;
        margin-bottom: 24px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        flex-wrap: wrap;
        gap: 16px;
        box-shadow: 0 4px 20px rgba(0,0,0,0.15);
      }
      .header-left h1 {
        font-size: 28px;
        font-weight: 700;
        color: #fff;
        margin-bottom: 4px;
      }
      .header-meta {
        display: flex;
        gap: 16px;
        align-items: center;
        flex-wrap: wrap;
      }
      .header-meta span {
        font-size: 13px;
        color: #94a3b8;
      }
      .header-right {
        display: flex;
        align-items: center;
        gap: 24px;
      }
      .score-display {
        text-align: right;
      }
      .score-label {
        font-size: 13px;
        color: #94a3b8;
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }
      .score-value {
        font-size: 48px;
        font-weight: 700;
        color: {{SCORE_COLOR}};
        line-height: 1;
      }
      .score-max {
        font-size: 20px;
        color: #64748b;
      }
      .status-badge {
        padding: 8px 16px;
        border-radius: 8px;
        font-size: 13px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }
      .badge-healthy { background: #dcfce7; color: #16a34a; }
      .badge-warning { background: #fef3c7; color: #d97706; }
      .badge-critical { background: #fee2e2; color: #dc2626; }

      /* Stats Grid */
      .stats-grid {
        display: grid;
        grid-template-columns: repeat(6, 1fr);
        gap: 16px;
        margin-bottom: 24px;
      }
      .stat-card {
        background: #fff;
        border-radius: 14px;
        padding: 20px;
        box-shadow: 0 2px 12px rgba(0,0,0,0.06);
        display: flex;
        flex-direction: column;
        position: relative;
        overflow: hidden;
        transition: transform 0.2s, box-shadow 0.2s;
      }
      .stat-card:hover {
        transform: translateY(-2px);
        box-shadow: 0 8px 24px rgba(0,0,0,0.1);
      }
      .stat-card::before {
        content: '';
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        height: 4px;
      }
      .stat-card.danger::before { background: linear-gradient(90deg, #ef4444, #f87171); }
      .stat-card.warning::before { background: linear-gradient(90deg, #f59e0b, #fbbf24); }
      .stat-card.success::before { background: linear-gradient(90deg, #10b981, #34d399); }
      .stat-card.info::before { background: linear-gradient(90deg, #3b82f6, #60a5fa); }
      .stat-label {
        font-size: 13px;
        color: #64748b;
        font-weight: 500;
        margin-bottom: 8px;
      }
      .stat-value {
        font-size: 32px;
        font-weight: 700;
        color: #1e293b;
        margin-bottom: 4px;
      }
      .stat-icon {
        position: absolute;
        top: 16px;
        right: 16px;
        width: 40px;
        height: 40px;
        border-radius: 10px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 18px;
      }
      .icon-danger { background: #fee2e2; color: #dc2626; }
      .icon-warning { background: #fef3c7; color: #d97706; }
      .icon-success { background: #dcfce7; color: #16a34a; }
      .icon-info { background: #dbeafe; color: #3b82f6; }

      /* Sections */
      .section {
        background: #fff;
        border-radius: 14px;
        padding: 24px;
        margin-bottom: 20px;
        box-shadow: 0 2px 12px rgba(0,0,0,0.06);
      }
      .section-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 16px;
        flex-wrap: wrap;
        gap: 12px;
      }
      .section-title {
        font-size: 18px;
        font-weight: 600;
        color: #1e293b;
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .section-title .count {
        background: #e2e8f0;
        padding: 2px 10px;
        border-radius: 12px;
        font-size: 13px;
        font-weight: 600;
        color: #64748b;
      }
      .section-subtitle {
        font-size: 13px;
        color: #64748b;
      }

      /* Collapsible */
      .collapsible-header {
        cursor: pointer;
        user-select: none;
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .collapsible-header::before {
        content: '▼';
        font-size: 10px;
        transition: transform 0.2s;
      }
      .collapsible-header.collapsed::before {
        transform: rotate(-90deg);
      }
      .collapsible-content {
        max-height: 500px;
        overflow-y: auto;
        transition: max-height 0.3s ease;
      }
      .collapsible-content.collapsed {
        max-height: 0;
        overflow: hidden;
      }

      /* Filter Controls */
      .filter-controls {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
      }
      .filter-btn {
        padding: 6px 14px;
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        background: #fff;
        font-size: 13px;
        font-weight: 500;
        color: #64748b;
        cursor: pointer;
        transition: all 0.2s;
      }
      .filter-btn:hover {
        border-color: #3b82f6;
        color: #3b82f6;
      }
      .filter-btn.active {
        background: #3b82f6;
        border-color: #3b82f6;
        color: #fff;
      }
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
      .chart-container { height: 300px; position: relative; }

      /* Two Column Layout */
      .two-col {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 20px;
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
            <h1>🏥 Health Dashboard</h1>
            <div class="header-meta">
              <span>📅 {{TIMESTAMP}}</span>
              <span>⚡ Penalty: {{PENALTY_POINTS}} pts</span>
            </div>
          </div>
          <div class="header-right">
            <span class="status-badge {{STATUS_BADGE_CLASS}}">{{STATUS}}</span>
            <div class="score-display">
              <div class="score-label">Health Score</div>
              <div><span class="score-value">{{SCORE}}</span><span class="score-max"> / 100</span></div>
            </div>
          </div>
        </div>

        <!-- Stats Cards -->
        <div class="stats-grid">
          <div class="stat-card danger">
            <div class="stat-label">Failed Tests</div>
            <div class="stat-value">{{FAILED_COUNT}}</div>
            <div class="stat-icon icon-danger">✕</div>
          </div>
          <div class="stat-card warning">
            <div class="stat-label">JS Errors</div>
            <div class="stat-value">{{JS_ERROR_COUNT}}</div>
            <div class="stat-icon icon-warning">⚠</div>
          </div>
          <div class="stat-card warning">
            <div class="stat-label">Warnings</div>
            <div class="stat-value">{{WARNING_COUNT}}</div>
            <div class="stat-icon icon-warning">!</div>
          </div>
          <div class="stat-card info">
            <div class="stat-label">Slow Pages</div>
            <div class="stat-value">{{SLOW_PAGES_COUNT}}</div>
            <div class="stat-icon icon-info">🐢</div>
          </div>
          <div class="stat-card warning">
            <div class="stat-label">Fallbacks Used</div>
            <div class="stat-value">{{FALLBACK_COUNT}}</div>
            <div class="stat-icon icon-warning">↩</div>
          </div>
          <div class="stat-card success">
            <div class="stat-label">Passed Tests</div>
            <div class="stat-value">{{HEALTHY_COUNT}}</div>
            <div class="stat-icon icon-success">✓</div>
          </div>
        </div>

        <!-- Health Score Trend -->
        <div class="section">
          <div class="section-header">
            <div class="section-title">📈 Health Score Trend</div>
          </div>
          <div class="chart-container">
            <canvas id="trendChart"></canvas>
          </div>
        </div>

        <!-- Slow Pages -->
        <div class="section">
          <div class="section-header">
            <div class="section-title">🐢 Slow Pages <span class="count">{{SLOW_PAGES_COUNT}}</span></div>
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
              <div class="section-title">⚠️ JS Errors <span class="count">{{JS_ERROR_COUNT}}</span></div>
            </div>
            <div class="collapsible-content" id="jsErrorsContent">
              <table>
                <thead>
                  <tr><th>Source</th><th>Error Message</th></tr>
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
              <div class="section-title">↩️ Fallback Details <span class="count">{{FALLBACK_COUNT}}</span></div>
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

        <!-- Warnings Details -->
        <div class="section">
          <div class="section-header collapsible-header" data-target="warningsContent">
            <div class="section-title">📋 Warnings Details <span class="count">{{WARNING_COUNT}}</span></div>
            <div class="filter-controls">
              <button class="filter-btn active" data-filter="all" data-table="warnings">All</button>
              <button class="filter-btn" data-filter="high" data-table="warnings">High ({{WARNING_HIGH_COUNT}})</button>
              <button class="filter-btn" data-filter="low" data-table="warnings">Low ({{WARNING_LOW_COUNT}})</button>
            </div>
          </div>
          <div class="collapsible-content" id="warningsContent">
            <div id="warningsHigh" class="warnings-group">
              <h4 style="color:#ea580c;margin:16px 0 8px;font-size:14px;">🔴 High Severity</h4>
              <table>
                <thead><tr><th>Source</th><th>Warning</th></tr></thead>
                <tbody>{{WARNINGS_HIGH_ROWS}}</tbody>
              </table>
            </div>
            <div id="warningsLow" class="warnings-group">
              <h4 style="color:#64748b;margin:16px 0 8px;font-size:14px;">🟡 Low Severity</h4>
              <table>
                <thead><tr><th>Source</th><th>Warning</th></tr></thead>
                <tbody>{{WARNINGS_LOW_ROWS}}</tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- Recent Runs -->
        <div class="section">
          <div class="section-header">
            <div class="section-title">🧪 Recent Test Runs</div>
            <div style="display:flex;gap:16px;align-items:center;flex-wrap:wrap;">
              <div class="runs-stats">
                <span><span class="dot dot-pass"></span> {{PASSED_STAT}} passed</span>
                <span><span class="dot dot-fail"></span> {{FAILED_STAT}} failed</span>
              </div>
              <div class="filter-controls">
                <button class="filter-btn active" data-filter="all" data-table="tests">All</button>
                <button class="filter-btn" data-filter="pass" data-table="tests">Passed</button>
                <button class="filter-btn" data-filter="fail" data-table="tests">Failed</button>
              </div>
              <input type="text" class="search-input" id="testSearch" placeholder="Search tests...">
            </div>
          </div>
          <table id="testsTable">
            <thead>
              <tr><th data-sort="text">Class <span class="sort-icon">↕</span></th><th data-sort="text">Method <span class="sort-icon">↕</span></th><th data-sort="status">Status <span class="sort-icon">↕</span></th><th data-sort="duration">Duration <span class="sort-icon">↕</span></th></tr>
            </thead>
            <tbody>
      {{TEST_ROWS}}
            </tbody>
          </table>
        </div>
      </div>

      <script>
      // Chart
      const labels = {{CHART_LABELS}};
      const data = {{CHART_DATA}};
      const avgScore = {{AVG_SCORE}};
      const threshold = 80;

      if (labels.length > 0) {
        const ctx = document.getElementById('trendChart').getContext('2d');
        const gradient = ctx.createLinearGradient(0, 0, 0, 300);
        gradient.addColorStop(0, 'rgba(16, 185, 129, 0.3)');
        gradient.addColorStop(1, 'rgba(16, 185, 129, 0.0)');

        // Create threshold and average data arrays
        const thresholdData = labels.map(() => threshold);
        const avgData = labels.map(() => avgScore);

        new Chart(ctx, {
          type: 'line',
          data: {
            labels: labels,
            datasets: [
              {
                label: 'Health Score',
                data: data,
                borderColor: '#10b981',
                backgroundColor: gradient,
                fill: true,
                tension: 0.4,
                pointRadius: 5,
                pointBackgroundColor: '#10b981',
                pointBorderColor: '#fff',
                pointBorderWidth: 2,
                borderWidth: 3,
                order: 1
              },
              {
                label: 'Healthy Threshold (80)',
                data: thresholdData,
                borderColor: '#22c55e',
                borderWidth: 2,
                borderDash: [8, 4],
                pointRadius: 0,
                fill: false,
                order: 2
              },
              {
                label: 'Average (' + avgScore + ')',
                data: avgData,
                borderColor: '#3b82f6',
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
            plugins: {
              legend: {
                display: true,
                position: 'top',
                labels: {
                  usePointStyle: true,
                  padding: 20,
                  font: { size: 12 }
                }
              },
              tooltip: {
                backgroundColor: '#1e293b',
                titleColor: '#fff',
                bodyColor: '#fff',
                padding: 12,
                cornerRadius: 8,
                filter: function(tooltipItem) {
                  return tooltipItem.datasetIndex === 0;
                }
              }
            },
            scales: {
              y: {
                min: 0,
                max: 100,
                grid: { color: '#f1f5f9' },
                ticks: { font: { size: 12 } }
              },
              x: {
                grid: { display: false },
                ticks: { font: { size: 11 }, maxRotation: 45, minRotation: 0 }
              }
            }
          }
        });
      }

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

      // Filter buttons
      document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          const table = btn.getAttribute('data-table');
          const filter = btn.getAttribute('data-filter');

          // Update active state
          btn.parentElement.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
          btn.classList.add('active');

          // Apply filter
          if (table === 'slowPages') {
            filterTable('slowPagesTable', 'data-severity', filter);
          } else if (table === 'tests') {
            filterTable('testsTable', 'data-status', filter);
          } else if (table === 'warnings') {
            const highEl = document.getElementById('warningsHigh');
            const lowEl = document.getElementById('warningsLow');
            highEl.style.display = (filter === 'all' || filter === 'high') ? 'block' : 'none';
            lowEl.style.display = (filter === 'all' || filter === 'low') ? 'block' : 'none';
          }
        });
      });

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

      // Search
      document.getElementById('testSearch')?.addEventListener('input', (e) => {
        const query = e.target.value.toLowerCase();
        document.querySelectorAll('#testsTable tbody tr').forEach(row => {
          const text = row.textContent.toLowerCase();
          row.style.display = text.includes(query) ? '' : 'none';
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