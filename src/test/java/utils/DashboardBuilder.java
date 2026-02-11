package utils;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
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

  private static String buildHtml(JSONObject snapshot, List<int[]> history, List<TestResult> testResults) {
    // Extract data from snapshot
    int score = getInt(snapshot, "score", 0);
    int warningCount = getInt(snapshot, "warningCount", 0);

    // Get slow pages
    JSONArray slowPagesDetails = (JSONArray) snapshot.get("slowPagesDetails");
    StringBuilder slowPagesHtml = new StringBuilder();
    if (slowPagesDetails != null && !slowPagesDetails.isEmpty()) {
      for (Object item : slowPagesDetails) {
        String detail = item.toString();
        String[] parts = detail.split(" = ");
        String page = parts[0];
        String timeStr = parts.length > 1 ? parts[1].replace(" ms", "") : "-";
        int time = 0;
        try {
          time = Integer.parseInt(timeStr);
        } catch (Exception ignored) {
        }
        String severity = time > 20000 ? "critical" : time > 15000 ? "high" : time > 10000 ? "medium" : "low";
        slowPagesHtml.append(String.format(
            "<tr><td>%s</td><td>%s</td><td><span class=\"badge badge-%s\">%s</span></td></tr>\n",
            escapeHtml(page), timeStr, severity, capitalize(severity)));
      }
    } else {
      slowPagesHtml
          .append("<tr><td colspan=\"3\" style=\"text-align:center;color:#94a3b8;\">No slow pages detected</td></tr>");
    }

    // Build chart data
    StringBuilder chartLabels = new StringBuilder("[");
    StringBuilder chartData = new StringBuilder("[");
    for (int i = 0; i < history.size(); i++) {
      if (i > 0) {
        chartLabels.append(",");
        chartData.append(",");
      }
      chartLabels.append(history.get(i)[0]);
      chartData.append(history.get(i)[1]);
    }
    chartLabels.append("]");
    chartData.append("]");

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
          "<tr><td>%s</td><td>%s</td><td><span class=\"badge badge-%s\">%s</span></td><td>%d</td></tr>\n",
          escapeHtml(r.className), escapeHtml(r.methodName), badgeClass, r.status, r.durationMs));
    }
    if (testRowsHtml.length() == 0) {
      testRowsHtml.append(
          "<tr><td colspan=\"4\" style=\"text-align:center;color:#94a3b8;\">No test results available</td></tr>");
    }

    // Determine score color
    String scoreColor = score >= 80 ? "#10b981" : score >= 50 ? "#f59e0b" : "#ef4444";

    return TEMPLATE
        .replace("{{SCORE}}", String.valueOf(score))
        .replace("{{SCORE_COLOR}}", scoreColor)
        .replace("{{FAILED_COUNT}}", String.valueOf(failed))
        .replace("{{HEALTHY_COUNT}}", String.valueOf(passed))
        .replace("{{PASSED_COUNT}}", String.valueOf(passed))
        .replace("{{WARNING_COUNT}}", String.valueOf(warningCount))
        .replace("{{SLOW_PAGES_ROWS}}", slowPagesHtml.toString())
        .replace("{{CHART_LABELS}}", chartLabels.toString())
        .replace("{{CHART_DATA}}", chartData.toString())
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
        background: #f8fafc;
        color: #1e293b;
        line-height: 1.5;
        padding: 32px;
        min-height: 100vh;
      }
      .container { max-width: 1200px; margin: 0 auto; }

      /* Header */
      .header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 24px;
      }
      .header h1 {
        font-size: 24px;
        font-weight: 700;
        color: #1e293b;
      }
      .score-display {
        font-size: 18px;
        font-weight: 600;
      }
      .score-value { color: {{SCORE_COLOR}}; }

      /* Stats Grid */
      .stats-grid {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 20px;
        margin-bottom: 24px;
      }
      .stat-card {
        background: #fff;
        border-radius: 12px;
        padding: 20px;
        box-shadow: 0 1px 3px rgba(0,0,0,0.08);
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
      }
      .stat-label {
        font-size: 14px;
        color: #64748b;
        margin-bottom: 8px;
      }
      .stat-value {
        font-size: 36px;
        font-weight: 700;
        margin-bottom: 8px;
        color: #1e293b;
      }
      .stat-link {
        font-size: 14px;
        color: #3b82f6;
        text-decoration: none;
      }
      .stat-link:hover { text-decoration: underline; }
      .stat-icon {
        width: 48px;
        height: 48px;
        border-radius: 10px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 20px;
      }
      .icon-danger { background: #fee2e2; color: #dc2626; }
      .icon-warning { background: #fef3c7; color: #d97706; }
      .icon-success { background: #dcfce7; color: #16a34a; }

      /* Sections */
      .section {
        background: #fff;
        border-radius: 12px;
        padding: 24px;
        margin-bottom: 24px;
        box-shadow: 0 1px 3px rgba(0,0,0,0.08);
      }
      .section-title {
        font-size: 18px;
        font-weight: 600;
        margin-bottom: 4px;
      }
      .section-subtitle {
        font-size: 13px;
        color: #64748b;
        margin-bottom: 16px;
      }

      /* Tables */
      table { width: 100%; border-collapse: collapse; }
      th {
        text-align: left;
        font-size: 13px;
        font-weight: 600;
        color: #64748b;
        padding: 12px 16px;
        border-bottom: 1px solid #e2e8f0;
      }
      td {
        padding: 12px 16px;
        font-size: 14px;
        border-bottom: 1px solid #f1f5f9;
      }
      tr:last-child td { border-bottom: none; }

      /* Badges */
      .badge {
        display: inline-block;
        padding: 4px 12px;
        border-radius: 6px;
        font-size: 12px;
        font-weight: 600;
      }
      .badge-critical { background: #fee2e2; color: #dc2626; }
      .badge-high { background: #ffedd5; color: #ea580c; }
      .badge-medium { background: #fef3c7; color: #d97706; }
      .badge-low { background: #dcfce7; color: #16a34a; }
      .badge-pass { background: #dcfce7; color: #16a34a; }
      .badge-fail { background: #fee2e2; color: #dc2626; }

      /* Chart */
      .chart-container { height: 280px; position: relative; }

      /* Runs Header */
      .runs-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 16px;
        flex-wrap: wrap;
        gap: 12px;
      }
      .runs-stats {
        display: flex;
        gap: 24px;
        font-size: 14px;
      }
      .runs-stats span { display: flex; align-items: center; gap: 6px; }
      .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
      .dot-pass { background: #10b981; }
      .dot-running { background: #f59e0b; }
      .dot-fail { color: #ef4444; }

      @media (max-width: 768px) {
        .stats-grid { grid-template-columns: 1fr; }
        body { padding: 16px; }
      }
      </style>
      </head>
      <body>

      <div class="container">
        <!-- Header -->
        <div class="header">
          <h1>System Overview</h1>
          <div class="score-display">Score: <span class="score-value">{{SCORE}}</span> / 100</div>
        </div>

        <!-- Stats Cards -->
        <div class="stats-grid">
          <div class="stat-card">
            <div>
              <div class="stat-label">Failed Automations</div>
              <div class="stat-value">{{FAILED_COUNT}}</div>
              <a class="stat-link" href="failures.html">View failures</a>
            </div>
            <div class="stat-icon icon-danger">!</div>
          </div>
          <div class="stat-card">
            <div>
              <div class="stat-label">Running</div>
              <div class="stat-value">0</div>
              <a class="stat-link" href="runs.html">View runs</a>
            </div>
            <div class="stat-icon icon-warning">⏱</div>
          </div>
          <div class="stat-card">
            <div>
              <div class="stat-label">Healthy</div>
              <div class="stat-value">{{HEALTHY_COUNT}}</div>
              <a class="stat-link" href="all.html">View all</a>
            </div>
            <div class="stat-icon icon-success">✓</div>
          </div>
        </div>

        <!-- Slow Pages -->
        <div class="section">
          <div class="section-title">Slow Pages</div>
          <div class="section-subtitle">Pages with load time above threshold</div>
          <table>
            <thead>
              <tr><th>Page</th><th>Load (ms)</th><th>Severity</th></tr>
            </thead>
            <tbody>
      {{SLOW_PAGES_ROWS}}
            </tbody>
          </table>
        </div>

        <!-- Health Score Trend -->
        <div class="section">
          <div class="section-title">Health Score Trend</div>
          <div class="chart-container">
            <canvas id="trendChart"></canvas>
          </div>
        </div>

        <!-- Recent Runs -->
        <div class="section">
          <div class="runs-header">
            <div class="section-title">Recent Runs</div>
            <div class="runs-stats">
              <span><span class="dot dot-pass"></span> {{PASSED_STAT}} passed</span>
              <span><span class="dot dot-running"></span> 0 Running</span>
              <span class="dot-fail">❌ {{FAILED_STAT}} Failed</span>
            </div>
          </div>
          <table>
            <thead>
              <tr><th>Class</th><th>Method</th><th>Status</th><th>Time (ms)</th></tr>
            </thead>
            <tbody>
      {{TEST_ROWS}}
            </tbody>
          </table>
        </div>
      </div>

      <script>
      const labels = {{CHART_LABELS}};
      const data = {{CHART_DATA}};

      if (labels.length > 0) {
        new Chart(document.getElementById('trendChart'), {
          type: 'line',
          data: {
            labels: labels,
            datasets: [{
              label: 'Health Score Trend',
              data: data,
              borderColor: '#10b981',
              backgroundColor: 'rgba(16, 185, 129, 0.1)',
              fill: true,
              tension: 0.1,
              pointRadius: 3,
              pointBackgroundColor: '#10b981',
              borderWidth: 2
            }]
          },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
              legend: { display: true, position: 'top' }
            },
            scales: {
              y: { min: 0, max: 100, grid: { color: '#f1f5f9' }, title: { display: true, text: 'Score' } },
              x: { grid: { display: false }, title: { display: true, text: 'Run' } }
            }
          }
        });
      }
      </script>
      </body>
      </html>
      """;
}