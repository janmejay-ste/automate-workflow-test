package utils;

import org.testng.IReporter;
import org.testng.ISuite;
import org.testng.ISuiteResult;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.xml.XmlSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Custom TestNG HTML Reporter with modern, clean UI design.
 * Replaces the default messy TestNG report.
 */
public class CustomHtmlReporter implements IReporter {
  private static final Logger LOG = LoggerFactory.getLogger(CustomHtmlReporter.class);
  private static final String OUTPUT_DIR = "test-output";

  @Override
  public void generateReport(List<XmlSuite> xmlSuites, List<ISuite> suites, String outputDirectory) {
    try {
      // Collect all test results
      int totalPassed = 0, totalFailed = 0, totalSkipped = 0;
      long totalDuration = 0;
      List<TestMethodResult> allResults = new ArrayList<>();
      String suiteName = "Test Suite";

      for (ISuite suite : suites) {
        suiteName = suite.getName();
        Map<String, ISuiteResult> results = suite.getResults();

        for (ISuiteResult sr : results.values()) {
          ITestContext tc = sr.getTestContext();
          totalPassed += tc.getPassedTests().size();
          totalFailed += tc.getFailedTests().size();
          totalSkipped += tc.getSkippedTests().size();

          // Collect passed tests
          for (ITestResult result : tc.getPassedTests().getAllResults()) {
            allResults.add(new TestMethodResult(result, "PASS"));
          }
          // Collect failed tests
          for (ITestResult result : tc.getFailedTests().getAllResults()) {
            allResults.add(new TestMethodResult(result, "FAIL"));
          }
          // Collect skipped tests
          for (ITestResult result : tc.getSkippedTests().getAllResults()) {
            allResults.add(new TestMethodResult(result, "SKIP"));
          }

          totalDuration += tc.getEndDate().getTime() - tc.getStartDate().getTime();
        }
      }

      // Sort by class name then method name
      allResults.sort(Comparator.comparing((TestMethodResult r) -> r.className)
          .thenComparing(r -> r.methodName));

      // Generate HTML
      String html = generateHtml(suiteName, totalPassed, totalFailed, totalSkipped, totalDuration, allResults);

      // Write to file
      Path outPath = Paths.get(OUTPUT_DIR, "custom-report.html");
      Files.createDirectories(outPath.getParent());
      Files.writeString(outPath, html, StandardCharsets.UTF_8);

      LOG.info("Custom HTML report generated: {}", outPath.toAbsolutePath());

    } catch (Exception e) {
      LOG.error("Failed to generate custom HTML report: {}", e.getMessage());
    }
  }

  private static class TestMethodResult {
    final String className;
    final String methodName;
    final String status;
    final long durationMs;
    final String description;
    final String exception;

    TestMethodResult(ITestResult result, String status) {
      this.className = result.getTestClass().getName();
      this.methodName = result.getMethod().getMethodName();
      this.status = status;
      this.durationMs = result.getEndMillis() - result.getStartMillis();
      this.description = result.getMethod().getDescription();

      if (result.getThrowable() != null) {
        StringWriter sw = new StringWriter();
        result.getThrowable().printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        // Truncate long stack traces
        this.exception = stack.length() > 500 ? stack.substring(0, 500) + "..." : stack;
      } else {
        this.exception = null;
      }
    }
  }

  private String generateHtml(String suiteName, int passed, int failed, int skipped,
      long durationMs, List<TestMethodResult> results) {
    int total = passed + failed + skipped;
    double passRate = total > 0 ? (passed * 100.0 / total) : 0;
    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    String duration = formatDuration(durationMs);

    StringBuilder testRows = new StringBuilder();
    for (TestMethodResult r : results) {
      String badgeClass = switch (r.status) {
        case "PASS" -> "pass";
        case "FAIL" -> "fail";
        default -> "skip";
      };
      String shortClass = r.className.contains(".") ? r.className.substring(r.className.lastIndexOf('.') + 1)
          : r.className;

      String exceptionHtml = "";
      if (r.exception != null) {
        exceptionHtml = String.format(
            "<tr class=\"exception-row\"><td colspan=\"5\"><pre class=\"exception\">%s</pre></td></tr>",
            escapeHtml(r.exception));
      }

      testRows.append(String.format("""
          <tr class="test-row %s" onclick="toggleException(this)">
            <td><span class="class-name">%s</span></td>
            <td>%s</td>
            <td><span class="badge badge-%s">%s</span></td>
            <td>%dms</td>
            <td>%s</td>
          </tr>
          %s
          """,
          r.status.toLowerCase(),
          escapeHtml(shortClass),
          escapeHtml(r.methodName),
          badgeClass, r.status,
          r.durationMs,
          r.description != null ? escapeHtml(r.description) : "-",
          exceptionHtml));
    }

    String passRateColor = passRate >= 90 ? "#10b981" : passRate >= 70 ? "#f59e0b" : "#ef4444";

    return TEMPLATE
        .replace("{{SUITE_NAME}}", escapeHtml(suiteName))
        .replace("{{TIMESTAMP}}", timestamp)
        .replace("{{DURATION}}", duration)
        .replace("{{TOTAL}}", String.valueOf(total))
        .replace("{{PASSED}}", String.valueOf(passed))
        .replace("{{FAILED}}", String.valueOf(failed))
        .replace("{{SKIPPED}}", String.valueOf(skipped))
        .replace("{{PASS_RATE}}", String.format("%.1f", passRate))
        .replace("{{PASS_RATE_COLOR}}", passRateColor)
        .replace("{{TEST_ROWS}}", testRows.toString());
  }

  private static String escapeHtml(String s) {
    if (s == null)
      return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  private static String formatDuration(long ms) {
    if (ms < 1000)
      return ms + "ms";
    if (ms < 60000)
      return String.format("%.1fs", ms / 1000.0);
    long mins = ms / 60000;
    long secs = (ms % 60000) / 1000;
    return String.format("%dm %ds", mins, secs);
  }

  private static final String TEMPLATE = """
      <!DOCTYPE html>
      <html lang="en">
      <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Test Report - {{SUITE_NAME}}</title>
      <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
      <style>
      * { box-sizing: border-box; margin: 0; padding: 0; }
      body {
        font-family: 'Inter', -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        min-height: 100vh;
        padding: 32px;
      }
      .container {
        max-width: 1400px;
        margin: 0 auto;
        background: #fff;
        border-radius: 20px;
        box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
        overflow: hidden;
      }

      /* Header */
      .header {
        background: linear-gradient(135deg, #1e293b 0%, #334155 100%);
        color: white;
        padding: 32px 40px;
      }
      .header h1 {
        font-size: 28px;
        font-weight: 700;
        margin-bottom: 8px;
      }
      .header-meta {
        display: flex;
        gap: 24px;
        font-size: 14px;
        opacity: 0.8;
      }

      /* Stats Grid */
      .stats-grid {
        display: grid;
        grid-template-columns: repeat(5, 1fr);
        gap: 1px;
        background: #e2e8f0;
      }
      .stat-card {
        background: #fff;
        padding: 24px;
        text-align: center;
      }
      .stat-label {
        font-size: 12px;
        color: #64748b;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        margin-bottom: 8px;
      }
      .stat-value {
        font-size: 32px;
        font-weight: 700;
      }
      .stat-value.pass { color: #10b981; }
      .stat-value.fail { color: #ef4444; }
      .stat-value.skip { color: #f59e0b; }
      .stat-value.total { color: #3b82f6; }

      /* Filter Tabs */
      .filters {
        display: flex;
        gap: 8px;
        padding: 20px 40px;
        background: #f8fafc;
        border-bottom: 1px solid #e2e8f0;
      }
      .filter-btn {
        padding: 8px 20px;
        border: none;
        border-radius: 20px;
        font-size: 14px;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s;
        background: #fff;
        color: #64748b;
        border: 1px solid #e2e8f0;
      }
      .filter-btn:hover { background: #f1f5f9; }
      .filter-btn.active { background: #3b82f6; color: white; border-color: #3b82f6; }
      .filter-btn.pass-filter.active { background: #10b981; border-color: #10b981; }
      .filter-btn.fail-filter.active { background: #ef4444; border-color: #ef4444; }
      .filter-btn.skip-filter.active { background: #f59e0b; border-color: #f59e0b; }

      /* Results Table */
      .results {
        padding: 0;
      }
      table {
        width: 100%;
        border-collapse: collapse;
      }
      th {
        text-align: left;
        padding: 16px 24px;
        font-size: 12px;
        font-weight: 600;
        color: #64748b;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        background: #f8fafc;
        border-bottom: 1px solid #e2e8f0;
        position: sticky;
        top: 0;
      }
      td {
        padding: 16px 24px;
        border-bottom: 1px solid #f1f5f9;
        font-size: 14px;
      }
      .test-row { cursor: pointer; transition: background 0.2s; }
      .test-row:hover { background: #f8fafc; }
      .test-row.pass { border-left: 4px solid #10b981; }
      .test-row.fail { border-left: 4px solid #ef4444; background: #fef2f2; }
      .test-row.skip { border-left: 4px solid #f59e0b; background: #fffbeb; }

      .class-name {
        font-weight: 500;
        color: #1e293b;
      }

      /* Badges */
      .badge {
        display: inline-block;
        padding: 4px 12px;
        border-radius: 20px;
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
      }
      .badge-pass { background: #dcfce7; color: #166534; }
      .badge-fail { background: #fee2e2; color: #991b1b; }
      .badge-skip { background: #fef3c7; color: #92400e; }

      /* Exception */
      .exception-row {
        display: none;
      }
      .exception-row.show { display: table-row; }
      .exception {
        background: #1e293b;
        color: #f1f5f9;
        padding: 16px;
        border-radius: 8px;
        font-size: 12px;
        font-family: 'Monaco', 'Consolas', monospace;
        white-space: pre-wrap;
        word-break: break-word;
        max-height: 300px;
        overflow-y: auto;
        margin: 8px 0;
      }

      /* Footer */
      .footer {
        padding: 20px 40px;
        background: #f8fafc;
        text-align: center;
        font-size: 13px;
        color: #64748b;
      }

      /* Hidden classes for filtering */
      .hidden { display: none !important; }

      @media (max-width: 900px) {
        .stats-grid { grid-template-columns: repeat(2, 1fr); }
        body { padding: 16px; }
        .header { padding: 24px; }
      }
      </style>
      </head>
      <body>

      <div class="container">
        <div class="header">
          <h1>📊 {{SUITE_NAME}}</h1>
          <div class="header-meta">
            <span>🕐 {{TIMESTAMP}}</span>
            <span>⏱️ Duration: {{DURATION}}</span>
            <span>📈 Pass Rate: <strong style="color: {{PASS_RATE_COLOR}}">{{PASS_RATE}}%</strong></span>
          </div>
        </div>

        <div class="stats-grid">
          <div class="stat-card">
            <div class="stat-label">Total Tests</div>
            <div class="stat-value total">{{TOTAL}}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">Passed</div>
            <div class="stat-value pass">{{PASSED}}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">Failed</div>
            <div class="stat-value fail">{{FAILED}}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">Skipped</div>
            <div class="stat-value skip">{{SKIPPED}}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">Pass Rate</div>
            <div class="stat-value" style="color: {{PASS_RATE_COLOR}}">{{PASS_RATE}}%</div>
          </div>
        </div>

        <div class="filters">
          <button class="filter-btn active" onclick="filterTests('all')">All ({{TOTAL}})</button>
          <button class="filter-btn pass-filter" onclick="filterTests('pass')">✓ Passed ({{PASSED}})</button>
          <button class="filter-btn fail-filter" onclick="filterTests('fail')">✗ Failed ({{FAILED}})</button>
          <button class="filter-btn skip-filter" onclick="filterTests('skip')">⊘ Skipped ({{SKIPPED}})</button>
        </div>

        <div class="results">
          <table>
            <thead>
              <tr>
                <th>Class</th>
                <th>Method</th>
                <th>Status</th>
                <th>Duration</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody id="testResults">
      {{TEST_ROWS}}
            </tbody>
          </table>
        </div>

        <div class="footer">
          Generated by Custom TestNG Reporter • Click on failed tests to view stack trace
        </div>
      </div>

      <script>
      function filterTests(status) {
        const rows = document.querySelectorAll('.test-row');
        const exceptions = document.querySelectorAll('.exception-row');
        const buttons = document.querySelectorAll('.filter-btn');

        buttons.forEach(btn => btn.classList.remove('active'));
        event.target.classList.add('active');

        rows.forEach(row => {
          if (status === 'all') {
            row.classList.remove('hidden');
          } else {
            row.classList.toggle('hidden', !row.classList.contains(status));
          }
        });

        exceptions.forEach(row => row.classList.remove('show'));
      }

      function toggleException(row) {
        const next = row.nextElementSibling;
        if (next && next.classList.contains('exception-row')) {
          next.classList.toggle('show');
        }
      }
      </script>
      </body>
      </html>
      """;
}
