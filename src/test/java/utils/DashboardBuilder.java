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
import utils.governance.GovernanceReportGenerator;
import utils.governance.GovernanceSnapshotBuilder;
import utils.governance.dto.GovernanceSnapshotDto;
import utils.orchestration.OrchestrationSnapshotBuilder;
import utils.orchestration.dto.OrchestrationSnapshotDto;
import utils.orchestration.dto.SuitePriorityDto;
import utils.orchestration.dto.WorkflowRiskDto;

public final class DashboardBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(DashboardBuilder.class);
  private static final String REPORTS_DIR = "reports/trend";

  private DashboardBuilder() {
  }

  public static void main(String[] args) {
    write();
  }

  public static void write() {
    write(null, null);
  }

  public static void write(utils.history.dto.TrendSnapshotDto trendSnapshot,
                            utils.correlation.dto.CorrelationSnapshotDto correlationSnapshot) {
    try {
      JSONObject analytics = readAnalytics();
      List<TrendDataWriter.RunData> history = TrendDataWriter.readFullHistory();

      // Release status interpretation (V5 — semantic-aware)
      int score = getInt(analytics, "overallScore", 100);
      double smokePass = getDouble(analytics, "smokePassRate", 1.0);
      double regressionPass = getDouble(analytics, "regressionPassRate", 1.0);
      int criticalBugs = getInt(analytics, "criticalProductBugs", 0);
      int locatorSamples = getInt(analytics, "totalLocatorSamples", 0);

      // Read Product Health from semantic snapshot (written by TrendExporter before this runs).
      // Default 100 = healthy: missing data must never block a release.
      int productHealth = readProductHealthFromSnapshot();

      HealthPolicy.ReleaseStatus releaseStatus = RiskInterpreter.interpret(
          score, smokePass, criticalBugs, regressionPass, locatorSamples, productHealth);

      // Build orchestration snapshot when upstream data is available
      OrchestrationSnapshotDto orchSnapshot = null;
      GovernanceSnapshotDto govSnapshot = null;
      if (trendSnapshot != null || correlationSnapshot != null) {
        JSONArray testFailures = (JSONArray) analytics.get("testFailures");
        try {
          orchSnapshot = OrchestrationSnapshotBuilder.build(trendSnapshot, correlationSnapshot, testFailures);
          govSnapshot  = GovernanceSnapshotBuilder.build(orchSnapshot);
        } catch (Exception orchEx) {
          LOG.warn("Orchestration/governance snapshot build failed — dashboard continues without it: {}", orchEx.getMessage());
        }
      }

      String html = buildHtml(analytics, history, releaseStatus, orchSnapshot, govSnapshot, trendSnapshot, correlationSnapshot);

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
    return buildHtml(analytics, history, status, null, null, null, null);
  }

  private static String buildHtml(JSONObject analytics, List<TrendDataWriter.RunData> history,
      HealthPolicy.ReleaseStatus status, OrchestrationSnapshotDto orchSnapshot) {
    return buildHtml(analytics, history, status, orchSnapshot, null, null, null);
  }

  private static String buildHtml(JSONObject analytics, List<TrendDataWriter.RunData> history,
      HealthPolicy.ReleaseStatus status, OrchestrationSnapshotDto orchSnapshot,
      GovernanceSnapshotDto govSnapshot) {
    return buildHtml(analytics, history, status, orchSnapshot, govSnapshot, null, null);
  }

  private static String buildHtml(JSONObject analytics, List<TrendDataWriter.RunData> history,
      HealthPolicy.ReleaseStatus status, OrchestrationSnapshotDto orchSnapshot,
      GovernanceSnapshotDto govSnapshot,
      utils.history.dto.TrendSnapshotDto trendSnapshot,
      utils.correlation.dto.CorrelationSnapshotDto correlationSnapshot) {
    // Extract top-level context
    JSONObject metadata = (JSONObject) analytics.get("metadata");
    String environment = getString(metadata, "environment", "QA");
    String suite = getString(metadata, "suite", "REGRESSION");
    String gitInfo = getString(metadata, "gitBranch", "main") + " (" + getString(metadata, "gitHash", "n/a") + ")";
    long durationMs = getLong(analytics, "totalDurationMs", 0);
    long timestamp = getLong(metadata, "timestamp", System.currentTimeMillis());

    // Risk Panel — uses dimension-specific description when Product Health data is available
    int productHealthForDesc = readProductHealthFromSnapshot();
    String readyHtml = String.format(
        "<div class='risk-card %s'><div class='risk-label'>Release Status</div><div class='risk-status'>%s</div><div class='risk-desc'>%s (Score: %d)</div></div>",
        status.cssClass, status.label, getReleaseDescription(status, productHealthForDesc), getInt(analytics, "overallScore", 0));

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
        .replace("{{CATEGORY_SUMMARY}}", "") // Optional summary space
        .replace("{{OVERRIDE_BANNER}}", buildOverrideBannerHtml())
        .replace("{{LEGACY_SCORE_RING}}", buildLegacyScoreRingHtml(score, scoreColor))
        .replace("{{LEGACY_STATS_BLOCK}}", buildLegacyStatsBlockHtml(analytics, tests, jsErrorRoot, slowPages))
        .replace("{{LEGACY_SLOW_PAGES_TABLE}}", buildLegacySlowPagesTableHtml(slowPages, slowPageRows))
        .replace("{{LEGACY_JS_ERRORS_TABLE}}", buildLegacyJsErrorsTableHtml(jsProdErrors, jsErrorRows))
        .replace("{{PLATFORM_HEALTH_PANEL}}", utils.dashboard.components.PlatformHealthComponent.render())
        .replace("{{DATA_QUALITY_PANEL}}",    utils.dashboard.components.DataQualityComponent.render())
        .replace("{{TRIAGE_BOX}}", buildTriageSummaryHtml(analytics, status, readProductHealthFromSnapshot(), tests))
        .replace("{{TREND_STATS_BLOCK}}", buildTrendStatsBlockHtml())
        .replace("{{EXECUTIVE_PANEL}}", buildExecutivePanelHtml(analytics, status, trendSnapshot, correlationSnapshot, orchSnapshot, govSnapshot))
        .replace("{{RELEASE_BLOCKERS}}", buildReleaseBlockersHtml(analytics, status, correlationSnapshot, orchSnapshot, trendSnapshot))
        .replace("{{SEMANTIC_PANEL}}", buildSemanticPanelHtml())
        .replace("{{CONFIDENCE_INDICATOR}}", buildConfidenceIndicatorHtml(analytics, trendSnapshot, govSnapshot))
        .replace("{{DELTA_PANEL}}", buildDeltaHtml(history, analytics))
        .replace("{{WORKFLOW_MATRIX}}", buildWorkflowMatrixHtml(orchSnapshot, correlationSnapshot))
        .replace("{{ROOT_CAUSE_CLUSTERS}}", buildRootCauseClustersHtml(analytics, correlationSnapshot))
        .replace("{{ORCHESTRATION_PANEL}}", buildOrchestrationHtml(orchSnapshot))
        .replace("{{GOVERNANCE_PANEL}}", GovernanceReportGenerator.buildDashboardHtml(govSnapshot))
        .replace("{{RECORDINGS_SECTION}}", buildRecordingsSection(tests))
        .replace("{{VIDEO_STYLES}}",  buildVideoStyles())
        .replace("{{VIDEO_MODAL}}",   buildVideoModal())
        .replace("{{VIDEO_SCRIPTS}}", buildVideoScripts());
  }

  private static String buildOrchestrationHtml(OrchestrationSnapshotDto orch) {
    if (orch == null) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("<div class='section' style='margin-bottom:20px;'>");
    sb.append("<div class='section-header'><div class='section-title'>Execution Intelligence");
    sb.append(" <span style='font-size:11px;font-weight:500;color:#94a3b8;margin-left:8px;'>ADVISORY — human review required</span></div>");

    // Strategy badge
    String strategy = orch.executionPlan != null ? orch.executionPlan.strategy : "N/A";
    String strategyColor = switch (strategy) {
      case "CRITICAL_PATH_ONLY" -> "#ef4444";
      case "RISK_WEIGHTED"      -> "#f97316";
      case "STABILITY_FIRST"    -> "#f59e0b";
      default                   -> "#10b981";
    };
    sb.append("<span style='padding:4px 12px;border-radius:20px;font-size:12px;font-weight:700;background:")
      .append(strategyColor).append("22;color:").append(strategyColor).append(";'>")
      .append(strategy).append("</span></div>");

    // Strategy reason
    if (orch.executionPlan != null && orch.executionPlan.strategyReason != null) {
      sb.append("<p style='font-size:13px;color:#64748b;margin-bottom:16px;'>")
        .append(orch.executionPlan.strategyReason).append("</p>");
    }

    // Risk summary cards
    sb.append("<div style='display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:16px;'>");
    sb.append(orchCard("Critical Risk", String.valueOf(orch.criticalRiskCount), "#ef4444"));
    sb.append(orchCard("High Risk", String.valueOf(orch.highRiskCount), "#f97316"));
    sb.append(orchCard("Suppressed Retries", String.valueOf(orch.suppressedRetries), "#6366f1"));
    sb.append(orchCard("CI Time Saving",
        orch.executionPlan != null ? orch.executionPlan.estimatedTimeSavingPct + "%" : "N/A", "#10b981"));
    sb.append("</div>");

    // Risk hotspots table
    if (orch.workflowRisks != null && !orch.workflowRisks.isEmpty()) {
      sb.append("<div style='margin-bottom:16px;'><div style='font-size:13px;font-weight:600;color:#334155;margin-bottom:8px;'>Workflow Risk Hotspots</div>");
      sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
      sb.append("<thead><tr style='color:#64748b;font-size:11px;text-transform:uppercase;'>");
      sb.append("<th style='padding:8px;text-align:left;'>Workflow</th><th>Risk</th><th>Stability</th><th>Driver</th></tr></thead><tbody>");
      int shown = 0;
      for (WorkflowRiskDto r : orch.workflowRisks) {
        if (shown++ >= 8) break; // top 8
        String rColor = switch (r.riskLevel != null ? r.riskLevel : "LOW") {
          case "CRITICAL" -> "#ef4444"; case "HIGH" -> "#f97316"; case "MEDIUM" -> "#f59e0b"; default -> "#10b981";
        };
        sb.append("<tr style='border-bottom:1px solid #f1f5f9;'>")
          .append("<td style='padding:8px;font-weight:500;'>").append(r.workflow).append("</td>")
          .append("<td style='padding:8px;'><span style='padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700;background:")
          .append(rColor).append("22;color:").append(rColor).append(";'>").append(r.riskLevel).append("</span></td>")
          .append("<td style='padding:8px;color:#64748b;'>").append(r.stabilityScore >= 0 ? r.stabilityScore : "N/A").append("</td>")
          .append("<td style='padding:8px;color:#64748b;font-size:12px;'>").append(r.primaryDriver != null ? r.primaryDriver : "").append("</td>")
          .append("</tr>");
      }
      sb.append("</tbody></table></div>");
    }

    // Minimization summary
    if (orch.minimizationSummary != null && !orch.minimizationSummary.isBlank()) {
      sb.append("<div style='background:#f8fafc;border-radius:8px;padding:12px 16px;font-size:12px;color:#475569;border-left:3px solid #6366f1;'>")
        .append("<strong style='color:#334155;'>Minimization Advisory:</strong> ")
        .append(orch.minimizationSummary).append("</div>");
    }

    sb.append("</div>"); // close section
    return sb.toString();
  }

  private static String orchCard(String label, String value, String color) {
    return String.format(
        "<div style='background:white;border-radius:10px;padding:14px 16px;border:1px solid #e2e8f0;border-left:4px solid %s;'>" +
        "<div style='font-size:11px;font-weight:600;color:#64748b;text-transform:uppercase;letter-spacing:0.5px;'>%s</div>" +
        "<div style='font-size:26px;font-weight:800;color:%s;margin-top:4px;'>%s</div></div>",
        color, label, color, value);
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
    String  folder    = getString(test, "artifacts", null);
    String  videoPath = getString(test, "videoPath",  null);
    boolean truncated = Boolean.TRUE.equals(test.get("videoTruncated"));
    if (folder == null && videoPath == null) return "";

    StringBuilder sb = new StringBuilder("<div class='artifact-links'>");
    if (folder != null) {
      String rel = "../../failures/" + folder;
      sb.append(String.format(
          "<a href='%s/screenshot.png' class='artifact-link screenshot-link' target='_blank'>📷 Screenshot</a>", rel));
      sb.append(String.format(
          "<a href='%s/console.log'    class='artifact-link' target='_blank'>📋 Console</a>", rel));
      sb.append(String.format(
          "<a href='%s/dom.html'       class='artifact-link' target='_blank'>🔍 DOM</a>", rel));
    }
    if (videoPath != null) {
      String relVideo  = toRelativePathFromDashboard(videoPath).replace("\\", "/");
      String title     = truncated
          ? "Recording truncated — test exceeded max duration (-DrecordVideo.maxMinutes)"
          : "Play test recording";
      String label     = truncated ? "▶ Replay ⚠" : "▶ Replay";
      String cssClass  = truncated ? "artifact-link video-link video-truncated"
                                   : "artifact-link video-link";
      sb.append(String.format(
          "<button class='%s' onclick='openVideoModal(\"%s\")' title='%s'>%s</button>",
          cssClass, relVideo, title, label));
    }
    sb.append("</div>");
    return sb.toString();
  }

  /** Converts an absolute video path to a path relative from reports/trend/dashboard.html. */
  private static String toRelativePathFromDashboard(String absPath) {
    try {
      java.nio.file.Path dashDir = java.nio.file.Paths.get("reports/trend").toAbsolutePath();
      return dashDir.relativize(java.nio.file.Paths.get(absPath).toAbsolutePath()).toString();
    } catch (Exception e) {
      return absPath.replace("\\", "/");
    }
  }

  // ── Recordings section ────────────────────────────────────────────────────

  /** Lightweight holder for a discovered recording on disk. */
  private static class RecordingEntry {
    final String testName;   // extracted from folder name
    final String status;     // "PASS" or "FAIL"
    final String relPath;    // relative path from dashboard.html to recording.mp4
    final String timestamp;  // "yyyyMMdd_HHmmss_SSS" portion of the folder name
    RecordingEntry(String testName, String status, String relPath, String timestamp) {
      this.testName  = testName;
      this.status    = status;
      this.relPath   = relPath;
      this.timestamp = timestamp;
    }
  }

  /**
   * Scans {@code reports/failures/} and {@code reports/recordings/} on disk for
   * {@code recording.mp4} files and returns one entry per found file, sorted
   * newest-first.  This approach persists across runs: refreshing the dashboard
   * always shows ALL previously captured recordings, not just the current run.
   */
  private static java.util.List<RecordingEntry> scanAllRecordings() {
    java.util.List<RecordingEntry> entries = new java.util.ArrayList<>();
    scanRecordingDir(java.nio.file.Paths.get("reports", "failures"),   "FAIL", entries);
    scanRecordingDir(java.nio.file.Paths.get("reports", "recordings"), "PASS", entries);
    // Sort newest-first by the timestamp embedded in the folder name
    entries.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
    return entries;
  }

  private static void scanRecordingDir(java.nio.file.Path dir, String status,
                                        java.util.List<RecordingEntry> entries) {
    if (!java.nio.file.Files.exists(dir)) return;
    try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
      stream.filter(java.nio.file.Files::isDirectory).forEach(folder -> {
        java.nio.file.Path mp4 = folder.resolve("recording.mp4");
        if (!java.nio.file.Files.exists(mp4)) return;

        // Folder name format: {testMethodName}_{yyyyMMdd}_{HHmmss}_{SSS}
        // Split on the first occurrence of _<8-digits> to isolate test name from timestamp
        String folderName = folder.getFileName().toString();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("^(.+?)_(\\d{8}_\\d{6}_\\d{3})$")
                .matcher(folderName);
        String testName = m.matches() ? m.group(1) : folderName;
        String ts       = m.matches() ? m.group(2) : "00000000_000000_000";

        String relPath = toRelativePathFromDashboard(mp4.toAbsolutePath().toString())
                            .replace("\\", "/");
        entries.add(new RecordingEntry(testName, status, relPath, ts));
      });
    } catch (java.io.IOException e) {
      LOG.warn("[DashboardBuilder] Could not scan recordings dir {}: {}", dir, e.getMessage());
    }
  }

  /**
   * Builds the full Recordings section HTML by scanning the filesystem.
   * The {@code tests} parameter is unused but kept for method-signature consistency
   * with the other build helpers — all data comes from disk so it persists across runs.
   */
  private static String buildRecordingsSection(JSONArray tests) {
    java.util.List<RecordingEntry> recordings = scanAllRecordings();

    StringBuilder sb = new StringBuilder();
    // Section starts COLLAPSED — the recordings list is heavy (potentially dozens
    // of cards) and most operators only need it when they're triaging a specific
    // failure. Header is clickable; CSS + JS in buildVideoStyles/buildVideoScripts
    // handle the show/hide and chevron-rotation. To open by default, replace
    // 'collapsed' below with the empty string.
    sb.append("<div class='section recordings-section collapsed' id='sec-recordings-card'>");
    sb.append("<div class='section-header recordings-toggle' "
            + "onclick='toggleRecordingsSection(this)' "
            + "role='button' tabindex='0' "
            + "aria-expanded='false' aria-controls='recordings-content' "
            + "title='Click to expand/collapse recordings'>");
    sb.append(String.format(
        "<div class='section-title'>"
      + "<span class='recordings-chevron' aria-hidden='true'>▶</span> "
      + "Recordings <span style='font-size:13px;font-weight:400;color:#64748b;margin-left:8px;'>"
      + "%d video%s</span></div>",
        recordings.size(), recordings.size() == 1 ? "" : "s"));
    sb.append("<div style='font-size:12px;color:#64748b;'>"
            + "Test execution replay videos — persisted across runs. Click header to expand.</div>");
    sb.append("</div>");

    // Collapsible body — CSS toggles max-height/display based on .collapsed on parent.
    sb.append("<div class='recordings-content' id='recordings-content'>");

    if (recordings.isEmpty()) {
      sb.append("""
          <div class='recordings-empty'>
            <div class='empty-icon'>🎬</div>
            <div class='empty-title'>No recordings yet</div>
            <div>Run tests with <code>-DrecordVideo=true</code> to capture replay videos.</div>
            <div style='margin-top:8px;font-size:12px;'>
              Example: <code>mvn test -Dtest=CreateConnectWorkflowTest -DrecordVideo=true</code>
            </div>
          </div>
          """);
    } else {
      sb.append("<div class='recordings-grid'>");
      for (RecordingEntry r : recordings) {
        String badgeCss = "PASS".equals(r.status) ? "badge-healthy" : "badge-high";
        // Format timestamp "20260522_143012_456" → "2026-05-22 14:30:12"
        String displayTs = formatRecordingTimestamp(r.timestamp);

        sb.append("<div class='recording-card'>");
        sb.append("  <div class='recording-card-header'>");
        sb.append(String.format("    <span class='badge %s'>%s</span>", badgeCss, r.status));
        sb.append("  </div>");
        sb.append(String.format("  <div class='recording-card-name' title='%s'>%s</div>",
            r.testName, r.testName));
        sb.append(String.format("  <div class='recording-card-meta'>%s</div>", displayTs));
        sb.append(String.format(
            "  <button class='recording-play-btn' onclick='openVideoModal(\"%s\")'" +
            "  title='Play recording for %s'>▶ Play</button>",
            r.relPath, r.testName));
        sb.append("</div>");
      }
      sb.append("</div>"); // recordings-grid
    }

    sb.append("</div>"); // recordings-content
    sb.append("</div>"); // section
    return sb.toString();
  }

  /** Formats "20260522_143012_456" → "2026-05-22 14:30:12" for display. */
  private static String formatRecordingTimestamp(String ts) {
    // ts = "yyyyMMdd_HHmmss_SSS"  e.g. "20260522_143012_456"
    try {
      if (ts.length() >= 15) {
        return ts.substring(0, 4) + "-" + ts.substring(4, 6) + "-" + ts.substring(6, 8)
             + " " + ts.substring(9, 11) + ":" + ts.substring(11, 13) + ":" + ts.substring(13, 15);
      }
    } catch (Exception ignored) {}
    return ts;
  }

  // ── Video modal helpers (CSS / HTML / JS extracted for maintainability) ──

  private static String buildVideoStyles() {
    return """
        .video-link { background:#eef2ff; color:#4f46e5; border:1px solid #c7d2fe;
                      cursor:pointer; font-family:inherit; font-size:11px;
                      padding:4px 8px; border-radius:4px; }
        .video-link:hover { background:#e0e7ff; }
        .video-truncated { border-color:#fbbf24; color:#92400e; background:#fffbeb; }
        .video-truncated:hover { background:#fef3c7; }
        .video-modal-overlay { display:none; position:fixed; inset:0;
                               background:rgba(0,0,0,0.75); z-index:9999;
                               align-items:center; justify-content:center; }
        .video-modal-overlay.active { display:flex; }
        .video-modal-box { background:#fff; border-radius:12px; padding:20px;
                           max-width:900px; width:90vw; position:relative;
                           box-shadow:0 25px 60px rgba(0,0,0,0.4); }
        .video-modal-box video { width:100%; border-radius:8px; background:#000; }
        .video-modal-close { position:absolute; top:12px; right:16px; background:none;
                             border:none; font-size:22px; cursor:pointer; color:#64748b; }
        /* ── Recording cards grid ── */
        .recordings-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr));
                           gap:16px; margin-top:16px; }
        .recording-card { background:#fff; border:1px solid #e2e8f0; border-radius:12px;
                          padding:16px; display:flex; flex-direction:column; gap:10px;
                          box-shadow:0 1px 4px rgba(0,0,0,0.06); transition:box-shadow 0.2s; }
        .recording-card:hover { box-shadow:0 4px 16px rgba(0,0,0,0.12); }
        .recording-card-header { display:flex; align-items:center; gap:8px; }
        .recording-card-name { font-size:13px; font-weight:600; color:#1e293b;
                               word-break:break-word; flex:1; }
        .recording-card-meta { font-size:11px; color:#64748b; }
        .recording-play-btn { width:100%; padding:10px; border:none; border-radius:8px;
                              background:#6366f1; color:#fff; font-size:13px; font-weight:600;
                              cursor:pointer; transition:background 0.15s; }
        .recording-play-btn:hover { background:#4f46e5; }
        .recording-play-btn.truncated { background:#f59e0b; }
        .recording-play-btn.truncated:hover { background:#d97706; }
        .recordings-empty { text-align:center; padding:60px 20px; color:#94a3b8; }
        .recordings-empty .empty-icon { font-size:48px; margin-bottom:12px; }
        .recordings-empty .empty-title { font-size:16px; font-weight:600; color:#64748b; margin-bottom:8px; }
        .recordings-empty code { background:#f1f5f9; padding:2px 6px; border-radius:4px;
                                 font-size:12px; color:#4f46e5; }
        /* ── D1/D2 collapsible sections: open by default, click header to collapse ── */
        .platform-health-toggle, .data-quality-toggle {
            cursor:pointer; user-select:none; transition:background 0.15s ease;
        }
        .platform-health-toggle:hover, .data-quality-toggle:hover { background:#f8fafc; }
        .platform-health-toggle:focus-visible,
        .data-quality-toggle:focus-visible {
            outline:2px solid #6366f1; outline-offset:2px; border-radius:6px;
        }
        .collapsible-chevron {
            display:inline-block; transition:transform 0.25s ease;
            font-size:11px; color:#64748b; margin-right:4px;
        }
        /* Chevron rotation: HTML serves ▶ (collapsed) by default; rotate +90deg
           when expanded to point ▼ (down). Inverted from the prior expand-by-default
           behaviour so the chevron text + CSS agree visually after the D1/D2 default
           was switched to collapsed. */
        .platform-health-section:not(.collapsed) .collapsible-chevron,
        .data-quality-section:not(.collapsed) .collapsible-chevron {
            transform:rotate(90deg);
        }
        .collapsible-content {
            overflow:hidden;
            transition:max-height 0.3s ease, opacity 0.2s ease;
            max-height:5000px; opacity:1;
        }
        .platform-health-section.collapsed .collapsible-content,
        .data-quality-section.collapsed .collapsible-content {
            max-height:0; opacity:0; pointer-events:none;
        }
        /* ── Recordings section: collapsible toggle (collapsed by default) ── */
        .recordings-toggle { cursor:pointer; user-select:none;
                             transition:background 0.15s ease; }
        .recordings-toggle:hover { background:#f8fafc; }
        .recordings-toggle:focus-visible { outline:2px solid #6366f1; outline-offset:2px;
                                            border-radius:6px; }
        .recordings-chevron { display:inline-block; transition:transform 0.25s ease;
                              font-size:11px; color:#64748b; margin-right:4px;
                              transform-origin:center; }
        .recordings-section:not(.collapsed) .recordings-chevron { transform:rotate(90deg); }
        .recordings-content { overflow:hidden;
                              transition:max-height 0.3s ease, opacity 0.2s ease, padding 0.2s ease;
                              max-height:5000px; opacity:1; }
        .recordings-section.collapsed .recordings-content {
            max-height:0; opacity:0; padding-top:0; padding-bottom:0;
            pointer-events:none;
        }
        """;
  }

  private static String buildVideoModal() {
    return """
        <div id="videoModalOverlay" class="video-modal-overlay" onclick="handleOverlayClick(event)">
          <div class="video-modal-box">
            <button class="video-modal-close" onclick="closeVideoModal()">&times;</button>
            <video id="videoPlayer" controls preload="metadata">
              <source id="videoSource" src="" type="video/mp4">
            </video>
          </div>
        </div>
        """;
  }

  private static String buildVideoScripts() {
    return """
        <script>
        function openVideoModal(src) {
            document.getElementById('videoSource').src = src;
            document.getElementById('videoPlayer').load();
            document.getElementById('videoModalOverlay').classList.add('active');
            document.body.style.overflow = 'hidden';
        }
        function closeVideoModal() {
            document.getElementById('videoPlayer').pause();
            document.getElementById('videoSource').src = '';
            document.getElementById('videoModalOverlay').classList.remove('active');
            document.body.style.overflow = '';
        }
        function handleOverlayClick(e) {
            if (e.target.id === 'videoModalOverlay') closeVideoModal();
        }
        document.addEventListener('keydown', e => { if (e.key === 'Escape') closeVideoModal(); });

        /* Recordings section collapse/expand toggle.
         * Default state is .collapsed (set by server-rendered HTML). Click on header
         * flips the .collapsed class on the parent .recordings-section, CSS animates
         * the max-height transition and rotates the chevron 90deg. Keyboard support:
         * Enter/Space on the focused header fires the same handler.
         */
        function toggleRecordingsSection(headerEl) {
            const section = headerEl.closest('.recordings-section');
            if (!section) return;
            const willExpand = section.classList.contains('collapsed');
            section.classList.toggle('collapsed');
            headerEl.setAttribute('aria-expanded', String(willExpand));
        }

        /* D1/D2 collapsible sections — generalised toggle. Difference from the
         * Recordings flavor: these sections default to OPEN (no .collapsed at
         * server-render), and the chevron rotates the opposite direction.
         * The closest('.section') walk works because both PlatformHealth and
         * DataQuality components emit a parent .section element with their
         * own *-section class. */
        function toggleCollapsibleSection(headerEl, contentId) {
            const section = headerEl.closest('.section');
            if (!section) return;
            const willCollapse = !section.classList.contains('collapsed');
            section.classList.toggle('collapsed');
            headerEl.setAttribute('aria-expanded', String(!willCollapse));
        }

        document.addEventListener('keydown', e => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            if (!e.target.classList) return;
            if (e.target.classList.contains('recordings-toggle')) {
                e.preventDefault();
                toggleRecordingsSection(e.target);
            } else if (e.target.classList.contains('platform-health-toggle')
                    || e.target.classList.contains('data-quality-toggle')) {
                e.preventDefault();
                toggleCollapsibleSection(e.target);
            }
        });
        </script>
        """;
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
      String test   = getString(f, "test",   "Unknown");
      String reason = getString(f, "reason", "");

      sb.append("<tr><td>").append(escHtml(test)).append("</td><td>");
      sb.append("<div class='error-msg'>").append(escHtml(reason)).append("</div>");

      // If the failure recorded structured URL findings (e.g. from HomepageExhaustiveTest),
      // render them as a mini-table with severity badges and clickable URLs.
      Object rawFindings = f.get("urlFindings");
      if (rawFindings instanceof JSONArray urlFindings && !urlFindings.isEmpty()) {
        sb.append(renderUrlFindingsDetail(urlFindings));
      }

      sb.append("</td></tr>");
    }
    return sb.toString();
  }

  /**
   * Renders a compact, colour-coded table of broken URLs beneath the failure
   * message in the Test Failure Details section.
   *
   * Columns: Severity badge | Type | HTTP Status | URL (linked) | Reason
   */
  @SuppressWarnings("unchecked")
  private static String renderUrlFindingsDetail(JSONArray findings) {
    StringBuilder s = new StringBuilder();
    s.append("<div style='margin-top:10px;'>")
     .append("<div style='font-size:11px;font-weight:700;color:#475569;text-transform:uppercase;")
     .append("letter-spacing:0.5px;margin-bottom:6px;'>")
     .append("Broken / Unreachable URLs (").append(findings.size()).append(")</div>")
     .append("<table style='width:100%;border-collapse:collapse;font-size:11.5px;'>")
     .append("<thead><tr style='background:#f8fafc;'>")
     .append("<th style='text-align:left;padding:5px 8px;border-bottom:1px solid #e2e8f0;width:76px;'>Severity</th>")
     .append("<th style='text-align:left;padding:5px 8px;border-bottom:1px solid #e2e8f0;width:105px;'>Type</th>")
     .append("<th style='text-align:right;padding:5px 8px;border-bottom:1px solid #e2e8f0;width:52px;'>Status</th>")
     .append("<th style='text-align:left;padding:5px 8px;border-bottom:1px solid #e2e8f0;'>URL</th>")
     .append("<th style='text-align:left;padding:5px 8px;border-bottom:1px solid #e2e8f0;'>Reason</th>")
     .append("</tr></thead><tbody>");

    for (Object o : findings) {
      if (!(o instanceof JSONObject fi)) continue;
      String url      = getString(fi, "url",      "");
      String severity = getString(fi, "severity", "");
      String type     = getString(fi, "type",     "");
      int    status   = getInt(fi,   "status",    0);
      String reason   = getString(fi, "reason",   "");

      // Severity badge palette (mirrors UrlValidationComponent / SemanticPanelComponent)
      String sevBg, sevFg;
      switch (severity.toLowerCase()) {
        case "critical" -> { sevBg = "#fee2e2"; sevFg = "#991b1b"; }
        case "high"     -> { sevBg = "#fed7aa"; sevFg = "#9a3412"; }
        case "medium"   -> { sevBg = "#fef3c7"; sevFg = "#854d0e"; }
        case "low"      -> { sevBg = "#dbeafe"; sevFg = "#1e40af"; }
        default         -> { sevBg = "#f1f5f9"; sevFg = "#475569"; }
      }
      String statusColor = status == 0   ? "#94a3b8"
                         : status >= 500 ? "#ef4444"
                         : status >= 400 ? "#f97316"
                         :                "#10b981";
      String statusText = status == 0 ? "—" : String.valueOf(status);

      s.append("<tr>")
       // Severity
       .append("<td style='padding:5px 8px;border-bottom:1px solid #f1f5f9;vertical-align:top;'>")
       .append("<span style='display:inline-block;padding:2px 8px;background:").append(sevBg)
       .append(";color:").append(sevFg)
       .append(";border-radius:10px;font-size:10px;font-weight:700;letter-spacing:0.3px;'>")
       .append(escHtml(severity)).append("</span></td>")
       // Type
       .append("<td style='padding:5px 8px;border-bottom:1px solid #f1f5f9;color:#475569;vertical-align:top;'>")
       .append(escHtml(type.replace('_', ' '))).append("</td>")
       // Status
       .append("<td style='padding:5px 8px;border-bottom:1px solid #f1f5f9;text-align:right;font-weight:700;")
       .append("color:").append(statusColor).append(";font-variant-numeric:tabular-nums;vertical-align:top;'>")
       .append(statusText).append("</td>")
       // URL — clickable, monospaced, wraps at word breaks
       .append("<td style='padding:5px 8px;border-bottom:1px solid #f1f5f9;word-break:break-all;vertical-align:top;'>")
       .append("<a href='").append(escHtml(url)).append("' target='_blank' rel='noopener noreferrer' ")
       .append("style='font-family:Consolas,\"Courier New\",monospace;font-size:11px;")
       .append("color:#2563eb;text-decoration:none;'>")
       .append(escHtml(url)).append("</a></td>")
       // Reason
       .append("<td style='padding:5px 8px;border-bottom:1px solid #f1f5f9;color:#64748b;vertical-align:top;'>")
       .append(escHtml(reason)).append("</td>")
       .append("</tr>");
    }

    s.append("</tbody></table></div>");
    return s.toString();
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

  // ── Executive Decision Panel ──────────────────────────────────────────────

  private static String buildExecutivePanelHtml(JSONObject analytics, HealthPolicy.ReleaseStatus status,
      utils.history.dto.TrendSnapshotDto trend,
      utils.correlation.dto.CorrelationSnapshotDto corr,
      OrchestrationSnapshotDto orch, GovernanceSnapshotDto gov) {

    String primaryReason = derivePrimaryReason(analytics, status, corr, orch);
    String action        = deriveRecommendedAction(status, corr, orch);
    String riskLevel     = deriveRiskLevel(orch, status);
    double confidence    = deriveConfidence(gov, trend);
    int downstream = corr != null && corr.cascadeDetected && corr.cascadeFailure != null
        ? Math.max(0, corr.cascadeFailure.affectedTests - 1) : 0;

    String statusColor = switch (status) {
      case BLOCKED -> "#ef4444"; case AT_RISK -> "#f97316"; case WARNING -> "#f59e0b"; default -> "#10b981";
    };
    String statusBg = switch (status) {
      case BLOCKED -> "rgba(239,68,68,0.05)"; case AT_RISK -> "rgba(249,115,22,0.05)";
      case WARNING -> "rgba(245,158,11,0.05)"; default -> "rgba(16,185,129,0.05)";
    };
    String confColor = confidence >= 0.80 ? "#10b981" : confidence >= 0.60 ? "#f59e0b" : "#ef4444";
    String riskColor = switch (riskLevel) {
      case "CRITICAL", "HIGH" -> "#ef4444"; case "MEDIUM" -> "#f59e0b"; default -> "#10b981";
    };

    StringBuilder sb = new StringBuilder();
    sb.append("<div style='display:grid;grid-template-columns:70% 30%;gap:0;background:white;border-radius:16px;")
      .append("border:1px solid #e2e8f0;overflow:hidden;margin-bottom:20px;box-shadow:0 2px 8px rgba(0,0,0,0.06);'>");

    // LEFT — decision dominant
    sb.append("<div style='padding:28px 32px;border-right:1px solid #f1f5f9;background:").append(statusBg).append(";'>")
      .append("<div style='font-size:11px;font-weight:700;color:").append(statusColor)
      .append(";text-transform:uppercase;letter-spacing:1px;margin-bottom:8px;'>Release Decision</div>")
      .append("<div style='font-size:36px;font-weight:900;color:").append(statusColor)
      .append(";line-height:1;margin-bottom:12px;'>").append(status.label).append("</div>")
      .append("<div style='font-size:16px;font-weight:600;color:#1e293b;margin-bottom:6px;'>")
      .append(escHtml(primaryReason)).append("</div>");
    if (downstream > 0) {
      sb.append("<div style='font-size:13px;color:#64748b;'>")
        .append(downstream).append(" downstream failure").append(downstream > 1 ? "s" : "")
        .append(" across cascaded workflows.</div>");
    }
    sb.append("</div>");

    // RIGHT — metadata
    // Confidence: qualitative label only (avoids false numeric precision)
    String confLabel = confidence >= 0.80 ? "HIGH" : confidence >= 0.60 ? "MODERATE" : "LOW";
    sb.append("<div style='padding:28px 24px;display:flex;flex-direction:column;gap:16px;'>")
      .append("<div title='").append(String.format("Computed: %.0f%%", confidence * 100)).append("'>")
      .append("<div style='font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.8px;margin-bottom:4px;'>Confidence</div>")
      .append("<div style='font-size:22px;font-weight:900;color:").append(confColor).append(";'>").append(confLabel).append("</div></div>")
      .append("<div><div style='font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.8px;margin-bottom:4px;'>Risk Level</div>")
      .append("<div style='font-size:17px;font-weight:700;color:").append(riskColor).append(";'>").append(riskLevel).append("</div></div>")
      .append("<div><div style='font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.8px;margin-bottom:4px;'>Action</div>")
      .append("<div style='font-size:13px;font-weight:500;color:#334155;line-height:1.4;'>").append(escHtml(action)).append("</div></div>")
      // Governance disclaimer — mandatory at this authority level
      .append("<div style='margin-top:auto;padding-top:12px;border-top:1px solid #f1f5f9;font-size:10px;color:#94a3b8;line-height:1.5;'>")
      .append("Advisory system only.<br>Final release decisions require human review.")
      .append("</div>")
      .append("</div>");

    sb.append("</div>");
    return sb.toString();
  }

  private static String derivePrimaryReason(JSONObject a, HealthPolicy.ReleaseStatus status,
      utils.correlation.dto.CorrelationSnapshotDto corr, OrchestrationSnapshotDto orch) {
    if (corr != null && corr.cascadeDetected && corr.cascadeFailure != null)
      return corr.cascadeFailure.rootWorkflow + " regression detected";
    if (getInt(a, "criticalProductBugs", 0) > 0)
      return "Critical product bugs detected";
    if (getDouble(a, "smokePassRate", 1.0) < 0.90)
      return "Smoke suite pass rate below threshold";
    if (getDouble(a, "regressionPassRate", 1.0) < 1.0) {
      int f = getInt(a, "failedCount", 0);
      return f + " test failure" + (f > 1 ? "s" : "") + " in regression suite";
    }
    if (orch != null && orch.criticalRiskCount > 0)
      return orch.criticalRiskCount + " critical-risk workflow" + (orch.criticalRiskCount > 1 ? "s" : "") + " detected";
    return switch (status) {
      case WARNING -> "Minor quality degradation detected";
      case READY   -> "All quality criteria met";
      default      -> "Quality gate analysis complete";
    };
  }

  private static String deriveRecommendedAction(HealthPolicy.ReleaseStatus status,
      utils.correlation.dto.CorrelationSnapshotDto corr, OrchestrationSnapshotDto orch) {
    return deriveRecommendedAction(status, corr, orch, readProductHealthFromSnapshot());
  }

  private static String deriveRecommendedAction(HealthPolicy.ReleaseStatus status,
      utils.correlation.dto.CorrelationSnapshotDto corr, OrchestrationSnapshotDto orch,
      int productHealth) {
    if (corr != null && corr.cascadeDetected && corr.cascadeFailure != null)
      return "Fix " + corr.cascadeFailure.rootWorkflow + " first, then rerun";
    return switch (status) {
      case BLOCKED -> productHealth < 20
          ? "Resolve product-level JS errors (Frontend Engineering), then re-run"
          : "Resolve blocking failures before release";
      case AT_RISK -> productHealth < 50
          ? "Investigate JS error clusters under Semantic Health; re-run after fix"
          : "Review failures and rerun affected suites";
      case WARNING -> "Review warnings, proceed with caution";
      default      -> "Ready to release";
    };
  }

  private static String deriveRiskLevel(OrchestrationSnapshotDto orch, HealthPolicy.ReleaseStatus status) {
    if (orch != null) {
      if (orch.criticalRiskCount > 0) return "CRITICAL";
      if (orch.highRiskCount > 0)     return "HIGH";
    }
    return switch (status) {
      case BLOCKED -> "HIGH"; case AT_RISK -> "MEDIUM"; case WARNING -> "LOW"; default -> "NONE";
    };
  }

  private static double deriveConfidence(GovernanceSnapshotDto gov, utils.history.dto.TrendSnapshotDto trend) {
    double conf = 0.75;
    if (gov != null) {
      if (gov.violationCount > 0)            conf -= 0.10;
      if (gov.confirmedFalseSuppressions > 0) conf -= 0.15;
      if (gov.guardedToAdvisoryCount > 3)    conf -= 0.05;
      if ("HEALTHY".equals(gov.governanceHealth)) conf += 0.10;
    }
    if (trend != null) {
      if (trend.regressionSpikeActive) conf -= 0.05;
    }
    return Math.min(1.0, Math.max(0.30, conf));
  }

  // ── Release Blockers ──────────────────────────────────────────────────────

  private static String buildReleaseBlockersHtml(JSONObject analytics, HealthPolicy.ReleaseStatus status,
      utils.correlation.dto.CorrelationSnapshotDto corr, OrchestrationSnapshotDto orch,
      utils.history.dto.TrendSnapshotDto trend) {

    java.util.List<String[]> items = new java.util.ArrayList<>();

    if (corr != null && corr.cascadeDetected && corr.cascadeFailure != null)
      items.add(new String[]{"BLOCKING", corr.cascadeFailure.rootWorkflow + " workflow failing — root of cascade"});

    double smokePass = getDouble(analytics, "smokePassRate", 1.0);
    if (smokePass < 0.90)
      items.add(new String[]{"BLOCKING", String.format("Smoke pass rate %.0f%% — below 90%% threshold", smokePass * 100)});

    int critBugs = getInt(analytics, "criticalProductBugs", 0);
    if (critBugs > 0)
      items.add(new String[]{"BLOCKING", critBugs + " critical product bug" + (critBugs > 1 ? "s" : "") + " — must fix before release"});

    if (orch != null && orch.criticalRiskCount > 0)
      items.add(new String[]{"BLOCKING", orch.criticalRiskCount + " critical-risk workflow" + (orch.criticalRiskCount > 1 ? "s" : "") + " — critical path at risk"});

    if (trend != null && trend.regressionSpikeActive)
      items.add(new String[]{"WARNING", "Regression spike active — sustained degradation detected across recent runs"});

    double regPass = getDouble(analytics, "regressionPassRate", 1.0);
    if (regPass < 1.0 && (corr == null || !corr.cascadeDetected)) {
      int fc = getInt(analytics, "failedCount", 0);
      // Subject-verb agreement: singular "failure requires", plural "failures require".
      // The fc==0 branch is defensive — post the AnalyticsCollector dual-truth fix it
      // should be unreachable (regPass < 1.0 implies fc > 0), but keep the safety net.
      if (fc == 1) {
        items.add(new String[]{"WARNING", "1 regression test failure requires investigation"});
      } else if (fc > 1) {
        items.add(new String[]{"WARNING", fc + " regression test failures require investigation"});
      }
    }

    if (orch != null && orch.highRiskCount > 0)
      items.add(new String[]{"WARNING", orch.highRiskCount + " high-risk workflow" + (orch.highRiskCount > 1 ? "s" : "") + " — monitor closely"});

    if (status == HealthPolicy.ReleaseStatus.READY && items.isEmpty()) {
      items.add(new String[]{"OK", "All quality gates passed"});
      items.add(new String[]{"OK", "No critical failures detected"});
      items.add(new String[]{"OK", "Regression suite fully passing"});
    }

    if (items.isEmpty()) return "";

    long blockingCount = items.stream().filter(b -> "BLOCKING".equals(b[0])).count();
    StringBuilder sb = new StringBuilder();
    sb.append("<div style='background:white;border-radius:12px;border:1px solid #e2e8f0;padding:20px 24px;margin-bottom:20px;box-shadow:0 1px 4px rgba(0,0,0,0.06);'>")
      .append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;'>")
      .append("<div style='font-size:15px;font-weight:700;color:#1e293b;'>Release Blockers</div>");
    if (blockingCount > 0)
      sb.append("<span style='background:#fee2e2;color:#dc2626;font-size:12px;font-weight:700;padding:3px 10px;border-radius:20px;'>").append(blockingCount).append(" blocking</span>");
    else
      sb.append("<span style='background:#dcfce7;color:#16a34a;font-size:12px;font-weight:700;padding:3px 10px;border-radius:20px;'>All clear</span>");
    sb.append("</div><div style='display:flex;flex-direction:column;gap:8px;'>");

    for (String[] item : items) {
      String icon, color, bg;
      if ("BLOCKING".equals(item[0])) { icon = "✕"; color = "#dc2626"; bg = "#fee2e2"; }
      else if ("WARNING".equals(item[0])) { icon = "⚠"; color = "#d97706"; bg = "#fef3c7"; }
      else                               { icon = "✓"; color = "#16a34a"; bg = "#dcfce7"; }
      sb.append("<div style='display:flex;align-items:flex-start;gap:10px;padding:8px 12px;background:").append(bg)
        .append(";border-radius:8px;'>")
        .append("<span style='font-size:14px;font-weight:700;color:").append(color).append(";flex-shrink:0;'>").append(icon).append("</span>")
        .append("<span style='font-size:13px;color:#334155;font-weight:").append("BLOCKING".equals(item[0]) ? "600" : "400").append(";'>").append(escHtml(item[1])).append("</span>")
        .append("</div>");
    }
    sb.append("</div></div>");
    return sb.toString();
  }

  // ── Semantic Panel — delegated to SemanticPanelComponent ─────────────────
  //
  // The entire ~300-line inline rendering of the Semantic Health section
  // (layered scores, per-domain panels, reliability table, telemetry footnote)
  // has been lifted out to utils.dashboard.components.SemanticPanelComponent
  // as the first step of the planned componentization.  This file is becoming
  // an orchestrator — placeholder wiring + glue — not a template engine.
  //
  // The component owns all the inline CSS, badge styling, and grid layout for
  // its section.  DashboardBuilder no longer carries semantic-panel helpers
  // (scoreCard, severityBadge, domainBadge, confidenceBadge, escapeHtml,
  //  renderPerDomainPanels, renderDomainPanel) — they all moved with it.
  private static String buildSemanticPanelHtml() {
    // Concatenate the Semantic Health panel + the new URL Validation panel.
    // Both are render-only consumers of health_snapshot.json and degrade
    // gracefully (return "") when their respective blocks are absent.
    return utils.dashboard.components.SemanticPanelComponent.render()
         + utils.dashboard.components.UrlValidationComponent.render();
  }

  // ── Confidence Indicator ──────────────────────────────────────────────────

  private static String buildConfidenceIndicatorHtml(JSONObject analytics,
      utils.history.dto.TrendSnapshotDto trend, GovernanceSnapshotDto gov) {
    double confidence  = deriveConfidence(gov, trend);
    // Use qualitative labels — avoid fake numeric precision
    String confLabel   = confidence >= 0.80 ? "HIGH" : confidence >= 0.60 ? "MODERATE" : "LOW";
    String confColor   = confidence >= 0.80 ? "#10b981" : confidence >= 0.60 ? "#f59e0b" : "#ef4444";
    String qualLabel   = confidence >= 0.80 ? "STRONG" : confidence >= 0.60 ? "MODERATE" : "WEAK";
    String signalTrust = trend != null && trend.overallConfidenceLabel != null ? trend.overallConfidenceLabel : "MODERATE";
    int aiAnalyzed     = getInt(analytics, "aiAnalyzedCount", 0);
    String numericTip  = String.format("Computed: %.0f%%", confidence * 100);

    StringBuilder sb = new StringBuilder();
    sb.append("<div style='background:white;border-radius:12px;border:1px solid #e2e8f0;padding:14px 20px;margin-bottom:20px;")
      .append("display:flex;align-items:center;gap:24px;box-shadow:0 1px 4px rgba(0,0,0,0.04);flex-wrap:wrap;'>")
      .append("<div title='").append(numericTip).append("'>")
      .append("<div style='font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.8px;'>Release Confidence</div>")
      .append("<div style='font-size:22px;font-weight:900;color:").append(confColor).append(";margin-top:2px;'>").append(confLabel).append("</div></div>")
      .append("<div style='width:1px;height:36px;background:#e2e8f0;flex-shrink:0;'></div>")
      .append("<div><div style='font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.8px;'>Evidence Quality</div>")
      .append("<div style='font-size:16px;font-weight:700;color:").append(confColor).append(";margin-top:2px;'>").append(qualLabel).append("</div></div>")
      .append("<div style='width:1px;height:36px;background:#e2e8f0;flex-shrink:0;'></div>")
      .append("<div><div style='font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.8px;'>Signal Trust</div>")
      .append("<div style='font-size:16px;font-weight:700;color:#334155;margin-top:2px;'>").append(escHtml(signalTrust)).append("</div></div>");
    if (aiAnalyzed > 0)
      sb.append("<div style='width:1px;height:36px;background:#e2e8f0;flex-shrink:0;'></div>")
        .append("<div><div style='font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.8px;'>AI Analyzed</div>")
        .append("<div style='font-size:16px;font-weight:700;color:#6366f1;margin-top:2px;'>").append(aiAnalyzed).append(" tests</div></div>");
    sb.append("<div style='margin-left:auto;font-size:11px;color:#94a3b8;font-style:italic;'>Advisory · hover for numeric estimate</div>")
      .append("</div>");
    return sb.toString();
  }

  // ── Delta Panel ───────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static String buildDeltaHtml(List<TrendDataWriter.RunData> history, JSONObject analytics) {
    if (history == null || history.size() < 2) return "";
    TrendDataWriter.RunData cur  = history.get(history.size() - 1);
    TrendDataWriter.RunData prev = history.get(history.size() - 2);

    java.util.List<String[]> changes = new java.util.ArrayList<>();

    int scoreDelta = cur.score - prev.score;
    if (scoreDelta > 0) changes.add(new String[]{"IMPROVED",  "Health score +" + scoreDelta + " pts (" + prev.score + " → " + cur.score + ")"});
    else if (scoreDelta < 0) changes.add(new String[]{"REGRESSED", "Health score " + scoreDelta + " pts (" + prev.score + " → " + cur.score + ")"});

    int jsDelta = cur.jsErrors - prev.jsErrors;
    if (jsDelta < 0) changes.add(new String[]{"IMPROVED",  Math.abs(jsDelta) + " fewer JS error" + (Math.abs(jsDelta) > 1 ? "s" : "") + " than previous run"});
    else if (jsDelta > 0) changes.add(new String[]{"NEW", jsDelta + " new JS error" + (jsDelta > 1 ? "s" : "") + " since previous run"});

    int testDelta = cur.failedTests - prev.failedTests;
    if (testDelta < 0) changes.add(new String[]{"RESOLVED",  Math.abs(testDelta) + " fewer test failure" + (Math.abs(testDelta) > 1 ? "s" : "") + " than previous run"});
    else if (testDelta > 0) changes.add(new String[]{"NEW", testDelta + " additional test failure" + (testDelta > 1 ? "s" : "") + " detected"});

    if (!cur.status.equals(prev.status)) {
      if ("READY".equals(cur.status))
        changes.add(new String[]{"IMPROVED", "Status improved: " + prev.status + " → " + cur.status});
      else if ("BLOCKED".equals(cur.status) || "AT_RISK".equals(cur.status))
        changes.add(new String[]{"REGRESSED", "Status regressed: " + prev.status + " → " + cur.status});
    }

    if (changes.isEmpty())
      changes.add(new String[]{"IMPROVED", "No significant changes from previous run"});

    long worsened  = changes.stream().filter(c -> "NEW".equals(c[0]) || "REGRESSED".equals(c[0])).count();
    long improved  = changes.stream().filter(c -> "RESOLVED".equals(c[0]) || "IMPROVED".equals(c[0])).count();

    StringBuilder sb = new StringBuilder();
    sb.append("<div style='background:white;border-radius:12px;border:1px solid #e2e8f0;padding:20px 24px;margin-bottom:20px;box-shadow:0 1px 4px rgba(0,0,0,0.06);'>")
      .append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;'>")
      .append("<div style='font-size:15px;font-weight:700;color:#1e293b;'>What Changed Since Last Run?</div>")
      .append("<div style='display:flex;gap:8px;'>");
    if (worsened > 0)
      sb.append("<span style='background:#fee2e2;color:#dc2626;font-size:12px;font-weight:700;padding:3px 10px;border-radius:20px;'>").append(worsened).append(" new / worsened</span>");
    if (improved > 0)
      sb.append("<span style='background:#dcfce7;color:#16a34a;font-size:12px;font-weight:700;padding:3px 10px;border-radius:20px;'>").append(improved).append(" improved</span>");
    sb.append("</div></div><div style='display:flex;flex-direction:column;gap:4px;'>");

    for (String[] item : changes) {
      String badge, color;
      switch (item[0]) {
        case "NEW"       -> { badge = "NEW";      color = "#dc2626"; }
        case "REGRESSED" -> { badge = "WORSENED"; color = "#f97316"; }
        case "RESOLVED"  -> { badge = "RESOLVED"; color = "#16a34a"; }
        default          -> { badge = "IMPROVED"; color = "#10b981"; }
      }
      sb.append("<div style='display:flex;align-items:center;gap:10px;padding:7px 0;border-bottom:1px solid #f8fafc;'>")
        .append("<span style='font-size:10px;font-weight:700;color:").append(color).append(";background:").append(color).append("18;")
        .append("padding:2px 8px;border-radius:4px;min-width:76px;text-align:center;'>").append(badge).append("</span>")
        .append("<span style='font-size:13px;color:#334155;'>").append(escHtml(item[1])).append("</span>")
        .append("</div>");
    }
    sb.append("</div></div>");
    return sb.toString();
  }

  // ── Workflow Health Matrix ────────────────────────────────────────────────

  private static String buildWorkflowMatrixHtml(OrchestrationSnapshotDto orch,
      utils.correlation.dto.CorrelationSnapshotDto corr) {
    if (orch == null || orch.workflowRisks == null || orch.workflowRisks.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("<div class='section' style='margin-bottom:20px;'>")
      .append("<div class='section-header'>")
      .append("<div class='section-title'>Workflow Health Matrix</div>")
      .append("<span style='font-size:12px;color:#94a3b8;cursor:default;'>Click row to expand details</span></div>")
      .append("<table style='width:100%;border-collapse:collapse;'>")
      .append("<thead><tr style='font-size:11px;font-weight:600;color:#64748b;text-transform:uppercase;'>")
      .append("<th style='padding:10px 12px;text-align:left;border-bottom:2px solid #f1f5f9;'>Workflow</th>")
      .append("<th style='padding:10px 12px;text-align:center;border-bottom:2px solid #f1f5f9;'>Health</th>")
      .append("<th style='padding:10px 12px;text-align:center;border-bottom:2px solid #f1f5f9;'>Trend</th>")
      .append("</tr></thead><tbody>");

    for (int i = 0; i < orch.workflowRisks.size(); i++) {
      WorkflowRiskDto r = orch.workflowRisks.get(i);
      String rLevel = r.riskLevel != null ? r.riskLevel : "LOW";
      String rColor = switch (rLevel) {
        case "CRITICAL" -> "#ef4444"; case "HIGH" -> "#f97316"; case "MEDIUM" -> "#f59e0b"; default -> "#10b981";
      };
      boolean isCascadeRoot = corr != null && corr.cascadeDetected && corr.cascadeFailure != null
          && r.workflow != null && r.workflow.equals(corr.cascadeFailure.rootWorkflow);
      String trendIcon  = r.stabilityScore >= 85 ? "→" : r.stabilityScore >= 60 ? "↘" : "↓";
      String trendColor = r.stabilityScore >= 85 ? "#10b981" : r.stabilityScore >= 60 ? "#f59e0b" : "#ef4444";
      String detailId   = "wf-detail-" + i;

      sb.append("<tr onclick='toggleWfDetail(\"").append(detailId).append("\")' ")
        .append("style='cursor:pointer;border-bottom:1px solid #f1f5f9;' ")
        .append("onmouseover='this.style.background=\"#f8fafc\"' onmouseout='this.style.background=\"\"'>")
        .append("<td style='padding:12px;font-size:13px;font-weight:500;color:#1e293b;'>");
      if (isCascadeRoot)
        sb.append("<span style='background:#fee2e2;color:#dc2626;font-size:9px;font-weight:700;padding:1px 6px;border-radius:3px;margin-right:6px;'>ROOT</span>");
      sb.append(escHtml(r.workflow)).append("</td>")
        .append("<td style='padding:12px;text-align:center;'>")
        .append("<span style='padding:3px 10px;border-radius:4px;font-size:11px;font-weight:700;background:").append(rColor)
        .append("18;color:").append(rColor).append(";'>").append(rLevel).append("</span></td>")
        .append("<td style='padding:12px;text-align:center;font-size:20px;font-weight:700;color:").append(trendColor).append(";'>")
        .append(trendIcon).append("</td></tr>");

      // Detail row (hidden by default)
      sb.append("<tr id='").append(detailId).append("' style='display:none;background:#f8fafc;'>")
        .append("<td colspan='3' style='padding:12px 16px;'>")
        .append("<div style='display:grid;grid-template-columns:repeat(3,1fr);gap:12px;font-size:12px;'>")
        .append("<div><div style='font-weight:600;color:#64748b;margin-bottom:4px;'>Stability Score</div>")
        .append("<div style='color:#1e293b;font-weight:500;'>").append(r.stabilityScore >= 0 ? r.stabilityScore : "N/A").append("</div></div>")
        .append("<div><div style='font-weight:600;color:#64748b;margin-bottom:4px;'>Primary Driver</div>")
        .append("<div style='color:#1e293b;'>").append(r.primaryDriver != null ? escHtml(r.primaryDriver) : "—").append("</div></div>")
        .append("<div><div style='font-weight:600;color:#64748b;margin-bottom:4px;'>Downstream Impact</div>")
        .append("<div style='color:#1e293b;'>").append(r.downstreamImpact > 0 ? r.downstreamImpact + " tests" : "None").append("</div></div>");
      if (isCascadeRoot)
        sb.append("<div style='grid-column:1/-1;background:#fee2e2;padding:8px 10px;border-radius:6px;color:#dc2626;font-weight:500;font-size:12px;'>")
          .append("Cascade root — fix this first. Downstream retries suppressed until this resolves.</div>");
      sb.append("</div></td></tr>");
    }

    sb.append("</tbody></table></div>");
    return sb.toString();
  }

  // ── Root Cause Clusters ───────────────────────────────────────────────────

  private static String buildRootCauseClustersHtml(JSONObject analytics,
      utils.correlation.dto.CorrelationSnapshotDto corr) {
    JSONObject failTypes  = (JSONObject) analytics.get("failureTypeCounts");
    JSONArray  testFails  = (JSONArray)  analytics.get("testFailures");
    if (failTypes == null || testFails == null || testFails.isEmpty()) return "";

    int productBugs = getInt(failTypes, "PRODUCT_BUG", 0);
    int autoBugs    = getInt(failTypes, "AUTOMATION_BUG", 0);
    int envIssues   = getInt(failTypes, "ENVIRONMENT", 0);
    if (productBugs + autoBugs + envIssues == 0 && (corr == null || !corr.cascadeDetected)) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("<div class='section' style='margin-bottom:20px;'>")
      .append("<div class='section-header'><div class='section-title'>Root Cause Clusters</div></div>")
      .append("<div style='display:flex;flex-direction:column;gap:10px;'>");

    if (corr != null && corr.cascadeDetected && corr.cascadeFailure != null)
      sb.append(clusterCard("CASCADE FAILURE",
          "Cascade: " + corr.cascadeFailure.rootWorkflow,
          corr.cascadeFailure.affectedTests + " tests affected — fix root workflow first",
          "#ef4444", "91% confidence · Root cause identified", false));

    if (productBugs > 0)
      sb.append(clusterCard("PRODUCT BUG",
          productBugs + " Product Bug" + (productBugs > 1 ? "s" : ""),
          "Feature regressions confirmed — requires developer investigation",
          "#f97316", "High confidence · Developer fix required", corr != null && corr.cascadeDetected));

    if (autoBugs > 0)
      sb.append(clusterCard("AUTOMATION",
          autoBugs + " Automation Bug" + (autoBugs > 1 ? "s" : ""),
          "Test code issue — not a product regression",
          "#6366f1", "Moderate confidence · Update test selectors / logic", true));

    if (envIssues > 0)
      sb.append(clusterCard("ENVIRONMENT",
          envIssues + " Environment Issue" + (envIssues > 1 ? "s" : ""),
          "Infrastructure or dependency failures",
          "#64748b", "Moderate confidence · Check infrastructure health", true));

    sb.append("</div></div>");
    return sb.toString();
  }

  private static String clusterCard(String type, String title, String subtitle,
      String color, String confidence, boolean collapsed) {
    String bg = color + "10";
    return "<div style='border:1px solid " + color + "40;border-radius:10px;overflow:hidden;'>" +
        "<div style='background:" + bg + ";padding:14px 16px;border-left:4px solid " + color +
        ";display:flex;justify-content:space-between;align-items:center;'>" +
        "<div><span style='font-size:10px;font-weight:700;color:" + color +
        ";text-transform:uppercase;letter-spacing:0.8px;'>" + type + "</span>" +
        "<div style='font-size:14px;font-weight:700;color:#1e293b;margin-top:2px;'>" + escHtml(title) + "</div>" +
        "<div style='font-size:12px;color:#64748b;margin-top:2px;'>" + escHtml(subtitle) + "</div></div>" +
        "<div style='font-size:11px;color:" + color + ";font-weight:500;text-align:right;max-width:200px;'>" +
        escHtml(confidence) + "</div></div></div>";
  }

  // ── Shared helper ─────────────────────────────────────────────────────────

  private static String escHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

  // ── Semantic snapshot reader ──────────────────────────────────────────────

  /**
   * Reads {@code semantic.layeredScores.productHealth} from the current
   * {@code health_snapshot.json}.  That file is written by
   * {@link TrendExporter#updateTrend} <em>before</em> {@link #write()} is called,
   * so the data is always available at dashboard-build time.
   *
   * @return product health score (0–100), or {@code 100} (healthy default) if the
   *         file is missing or the semantic block is absent — missing data must
   *         never block a release.
   */
  private static int readProductHealthFromSnapshot() {
    try {
      Path p = Paths.get("reports/trend/health_snapshot.json");
      if (!Files.exists(p)) return 100;
      JSONObject root   = (JSONObject) new JSONParser().parse(Files.readString(p));
      JSONObject sem    = (JSONObject) root.get("semantic");
      if (sem == null) return 100;
      JSONObject scores = (JSONObject) sem.get("layeredScores");
      if (scores == null) return 100;
      Object v = scores.get("productHealth");
      return v instanceof Number n ? n.intValue() : 100;
    } catch (Exception e) {
      return 100;
    }
  }

  // ── 30-Second Triage Box ──────────────────────────────────────────────────

  /**
   * Prominent card rendered at the very top of the dashboard (before the
   * executive panel) when the release status is not READY.
   *
   * <p>Shows four cells: WHAT BROKE / BLAST RADIUS / OWNER / ACTION — the
   * four things an engineer needs in the first 30 seconds of investigating a
   * red build.  Returns "" when status is READY (nothing to triage).
   */
  private static String buildTriageSummaryHtml(JSONObject analytics,
      HealthPolicy.ReleaseStatus status, int productHealth, JSONArray tests) {
    if (status == HealthPolicy.ReleaseStatus.READY) return "";

    String headerColor = switch (status) {
      case BLOCKED -> "#dc2626"; case AT_RISK -> "#ea580c"; default -> "#ca8a04";
    };
    String bg = switch (status) {
      case BLOCKED -> "#fef2f2"; case AT_RISK -> "#fff7ed"; default -> "#fefce8";
    };
    String border = switch (status) {
      case BLOCKED -> "#fca5a5"; case AT_RISK -> "#fed7aa"; default -> "#fde68a";
    };

    // WHAT: first failing test method name
    String topFail = "—";
    if (tests != null) {
      for (Object o : tests) {
        if (!(o instanceof JSONObject t)) continue;
        if ("FAIL".equals(getString(t, "status", ""))) {
          topFail = getString(t, "method", getString(t, "class", "Unknown"));
          break;
        }
      }
    }

    // BLAST RADIUS
    int failedCount = getInt(analytics, "failedCount", 0);
    int totalCount  = getInt(analytics, "totalTestCount", 0);
    String blastRadius = failedCount + " of " + (totalCount > 0 ? totalCount : "?") + " tests";

    // DOMAIN + OWNER (driven by Product Health)
    boolean isProductFailure = productHealth < 50;
    String domain = isProductFailure ? "Product (Frontend)" : "Framework (QA)";
    String owner  = isProductFailure ? "Frontend Engineering" : "QA Automation";

    // ACTION
    String action;
    if (status == HealthPolicy.ReleaseStatus.BLOCKED) {
      action = isProductFailure
          ? "Fix app-level JS errors, then re-run"
          : "Fix failing tests, then re-run smoke suite";
    } else {
      action = isProductFailure
          ? "Review JS error clusters → Semantic Health tab"
          : "Investigate failures → Recent Test Runs tab";
    }

    return "<div style='background:" + bg + ";border:2px solid " + border
        + ";border-radius:12px;padding:20px 24px;margin-bottom:20px;'>"
        + "<div style='font-size:11px;font-weight:700;color:" + headerColor
        + ";text-transform:uppercase;letter-spacing:1px;margin-bottom:12px;'>"
        + "🔍 30-Second Triage — What You Need To Know Right Now</div>"
        + "<div style='display:grid;grid-template-columns:repeat(4,1fr);gap:20px;'>"
        + triageCell("WHAT BROKE",   escHtml(topFail))
        + triageCell("BLAST RADIUS", escHtml(blastRadius))
        + triageCell("OWNER",
              escHtml(domain) + "<br><span style='font-weight:400;color:#475569;font-size:12px;'>"
              + escHtml(owner) + "</span>")
        + triageCell("ACTION",       escHtml(action))
        + "</div>"
        + buildScoreDriversRow(headerColor)
        + "</div>";
  }

  /**
   * Renders the score-driver inline row beneath the 4 triage cells. Reads
   * {@code scoreContributors} from {@code health_snapshot.json} and displays
   * each bucket's applied penalty so the stakeholder sees WHY the score is
   * what it is — not just that "N tests failed." Addresses the common
   * misreading where a stakeholder sees "1 of 25 failed" and concludes the
   * scoring model is too aggressive, when in reality the dominant contributor
   * was N locator-fallback retries from a single failing iteration.
   *
   * <p>Returns "" when scoreContributors is missing or shows zero total — the
   * row only appears when there's something to explain.</p>
   */
  @SuppressWarnings("unchecked")
  private static String buildScoreDriversRow(String accentColor) {
    try {
      java.nio.file.Path p = java.nio.file.Paths.get("reports/trend/health_snapshot.json");
      if (!java.nio.file.Files.exists(p)) return "";
      JSONObject root = (JSONObject) new JSONParser().parse(java.nio.file.Files.readString(p));
      JSONObject sc = (JSONObject) root.get("scoreContributors");
      if (sc == null) return "";

      double totalApplied = (sc.get("totalApplied") instanceof Number n) ? n.doubleValue() : 0.0;
      if (totalApplied < 0.5) return "";   // nothing meaningful to display

      // Collect each non-total contributor's applied penalty + item count.
      // Per-bucket data attributes (data-bucket / data-bucket-pts /
      // data-bucket-items) provide a machine-readable contract for
      // reconciliation tests; the visible text is for humans and may be
      // re-worded without breaking the contract.
      //
      // C4 forward-compat: when cluster normalization lands, each bucket will
      // also emit data-bucket-clusters and data-bucket-raw-events for full
      // raw→cluster→applied traceability. The items reconciliation locked in
      // here is the first step toward that richer contract.
      StringBuilder drivers = new StringBuilder();
      java.util.List<String> ordered = new java.util.ArrayList<>(
              java.util.Arrays.asList("testFailures", "jsErrors", "fallbacks", "slowPages", "warnings"));
      boolean first = true;
      int totalItems = 0;
      for (String key : ordered) {
        Object v = sc.get(key);
        if (!(v instanceof JSONObject bucket)) continue;
        double applied = (bucket.get("appliedTotal") instanceof Number an) ? an.doubleValue() : 0.0;
        if (applied < 0.5) continue;
        JSONArray items = (JSONArray) bucket.get("items");
        int itemCount = items != null ? items.size() : 0;
        totalItems += itemCount;
        if (!first) drivers.append("  +  ");
        first = false;
        drivers.append("<span data-bucket='").append(escHtml(key))
               .append("' data-bucket-pts='").append(formatPts(applied))
               .append("' data-bucket-items='").append(itemCount).append("'>")
               .append("<strong>").append(itemCount).append("</strong>&nbsp;")
               .append(escHtml(humanLabel(key)))
               .append("&nbsp;<span style='color:#94a3b8;'>(")
               .append(formatPts(applied)).append(" pts)</span>")
               .append("</span>");
      }
      if (first) return "";   // no non-total contributors

      return "<div data-component='score-drivers'"
           + " data-penalty-total='" + formatPts(totalApplied) + "'"
           + " data-total-items='" + totalItems + "'"
           + " style='margin-top:14px;padding:10px 14px;background:rgba(0,0,0,0.04);"
           + "border-radius:8px;font-size:12px;color:#1e293b;'>"
           + "<span style='font-weight:700;color:" + accentColor
           + ";text-transform:uppercase;letter-spacing:0.5px;font-size:10px;'>"
           + "Score drivers</span> &nbsp;"
           + drivers
           + " &nbsp;<span style='color:#64748b;'>= " + formatPts(totalApplied) + " pts applied</span>"
           + "</div>";
    } catch (Exception e) {
      return "";
    }
  }

  private static String humanLabel(String contributorKey) {
    return switch (contributorKey) {
      case "testFailures" -> "test failure(s)";
      case "jsErrors"     -> "JS error cluster(s)";
      case "fallbacks"    -> "locator fallback(s)";
      case "slowPages"    -> "slow page(s)";
      case "warnings"     -> "warning(s)";
      default              -> contributorKey;
    };
  }

  private static String formatPts(double d) {
    return d == (long) d ? String.format("%d", (long) d) : String.format("%.1f", d);
  }

  private static String triageCell(String label, String value) {
    return "<div>"
        + "<div style='font-size:10px;font-weight:700;color:#94a3b8;"
        + "text-transform:uppercase;letter-spacing:0.8px;margin-bottom:6px;'>"
        + escHtml(label) + "</div>"
        + "<div style='font-size:14px;font-weight:600;color:#1e293b;line-height:1.4;'>"
        + value + "</div>"
        + "</div>";
  }

  // ── Legacy section gating (off by default) ────────────────────────────────

  /**
   * Returns the legacy single-score ring HTML only when {@code -Ddashboard.showLegacy=true}.
   * The ring renders the raw composite score that pre-dated Semantic Health and is
   * retained for backward compatibility only — semantic layered scores (Product /
   * Framework / Telemetry / Business) are the authoritative read.
   */
  private static String buildLegacyScoreRingHtml(int score, String scoreColor) {
    if (!Boolean.parseBoolean(System.getProperty("dashboard.showLegacy", "false"))) {
      return "";
    }
    return "<div class='score-ring legacy-score' title='Legacy composite score — see Semantic Health for authoritative scores'>"
         + "<div class='score-ring-label' style='color:#94a3b8;font-size:9px;'>Legacy Composite</div>"
         + "<div style='opacity:0.55;'>"
         + "<span class='score-ring-value' style='color:" + scoreColor + ";font-size:22px;'>" + score + "</span>"
         + "<span class='score-ring-max' style='font-size:11px;'>/100</span></div>"
         + "<div style='font-size:9px;color:#cbd5e1;text-transform:uppercase;letter-spacing:0.4px;margin-top:2px;'>deprecated</div>"
         + "</div>";
  }

  /**
   * Returns the legacy raw-count stats block HTML only when {@code -Ddashboard.showLegacy=true}.
   * Pre-Semantic-Health raw counts (failed tests, raw JS errors, etc.) are retained
   * for forensic access but hidden from the default dashboard view to reduce
   * stakeholder confusion about which scoring layer is authoritative.
   */
  @SuppressWarnings("unchecked")
  private static String buildLegacyStatsBlockHtml(JSONObject analytics, JSONArray tests,
                                                   JSONObject jsErrorRoot, JSONArray slowPages) {
    if (!Boolean.parseBoolean(System.getProperty("dashboard.showLegacy", "false"))) {
      return "";
    }
    int failed = getInt(analytics, "failedCount", tests != null ? getFailedCount(tests) : 0);
    int jsErr  = prodCount(jsErrorRoot);
    int slow   = slowPages != null ? slowPages.size() : 0;
    int healthy = getInt(analytics, "healthyCount", 0);
    return "<details class='legacy-section' "
         + "style='border:1px dashed #e2e8f0;border-radius:8px;background:#fafbfc;margin-bottom:24px;'>"
         + "<summary style='cursor:pointer;padding:12px 16px;font-size:13px;color:#64748b;font-weight:600;'>"
         + "<span style='display:inline-block;padding:2px 8px;background:#fee2e2;color:#991b1b;"
         + "border-radius:4px;font-size:10px;letter-spacing:0.4px;margin-right:8px;'>DEPRECATED</span>"
         + "Legacy raw-count stats"
         + "<span style='font-weight:400;color:#94a3b8;margin-left:8px;'>"
         + "— see Semantic Health for clustered, domain-tagged metrics</span>"
         + "</summary>"
         + "<div style='padding:0 16px 16px;'>"
         + "<div class='stats-grid'>"
         + "<div class='stat-card danger'><div class='stat-label'>Failed Tests</div><div class='stat-value'>" + failed + "</div></div>"
         + "<div class='stat-card warning'><div class='stat-label'>JS Errors (raw)</div><div class='stat-value'>" + jsErr + "</div></div>"
         + "<div class='stat-card info'><div class='stat-label'>Slow Pages (raw)</div><div class='stat-value'>" + slow + "</div></div>"
         + "<div class='stat-card success'><div class='stat-label'>Passed Tests</div><div class='stat-value'>" + healthy + "</div></div>"
         + "</div></div></details>";
  }

  /**
   * Returns the legacy raw "Slow Pages" table only when {@code -Ddashboard.showLegacy=true}.
   * Superseded by Semantic Health / Telemetry Confidence — kept for forensic drill-down
   * but hidden from the default view to avoid duplicate-source-of-truth confusion.
   */
  private static String buildLegacySlowPagesTableHtml(JSONArray slowPages, String slowPageRows) {
    if (!Boolean.parseBoolean(System.getProperty("dashboard.showLegacy", "false"))) {
      return "";
    }
    int count = slowPages != null ? slowPages.size() : 0;
    return "<div class='section'>"
         + "<details class='legacy-section' "
         + "style='border:1px dashed #e2e8f0;border-radius:8px;background:#fafbfc;'>"
         + "<summary style='cursor:pointer;padding:12px 16px;font-size:13px;color:#64748b;font-weight:600;'>"
         + "<span style='display:inline-block;padding:2px 8px;background:#fee2e2;color:#991b1b;"
         + "border-radius:4px;font-size:10px;letter-spacing:0.4px;margin-right:8px;'>DEPRECATED</span>"
         + "Legacy: raw Slow Pages table <span class='count'>" + count + "</span>"
         + "<span style='font-weight:400;color:#94a3b8;margin-left:8px;'>"
         + "— now surfaced under Semantic Health / Telemetry Confidence</span>"
         + "</summary>"
         + "<div style='padding:0 16px 16px;'>"
         + "<table id='slowPagesTable'><thead>"
         + "<tr><th>Page</th><th>Load Time</th><th>Severity</th></tr></thead>"
         + "<tbody>" + slowPageRows + "</tbody></table>"
         + "</div></details></div>";
  }

  /**
   * Returns the legacy raw JS Errors table only when {@code -Ddashboard.showLegacy=true}.
   * Same rationale as the Slow Pages legacy table — superseded by Semantic Health
   * Error Clusters (clustered, domain-tagged).
   */
  private static String buildLegacyJsErrorsTableHtml(JSONArray jsProdErrors, String jsErrorRows) {
    if (!Boolean.parseBoolean(System.getProperty("dashboard.showLegacy", "false"))) {
      return "";
    }
    int count = jsProdErrors != null ? jsProdErrors.size() : 0;
    String maxHeight = count > 10 ? "360px" : "none";
    return "<div class='section'>"
         + "<details class='legacy-section' "
         + "style='border:1px dashed #e2e8f0;border-radius:8px;background:#fafbfc;'>"
         + "<summary style='cursor:pointer;padding:12px 16px;font-size:13px;color:#64748b;font-weight:600;'>"
         + "<span style='display:inline-block;padding:2px 8px;background:#fee2e2;color:#991b1b;"
         + "border-radius:4px;font-size:10px;letter-spacing:0.4px;margin-right:8px;'>DEPRECATED</span>"
         + "Legacy: raw JS Errors table <span class='count'>" + count + "</span>"
         + "<span style='font-weight:400;color:#94a3b8;margin-left:8px;'>"
         + "— now clustered under Semantic Health / Error Clusters</span>"
         + "</summary>"
         + "<div style='padding:0 16px 16px;'>"
         + "<div style='max-height:" + maxHeight + ";overflow-y:auto;'>"
         + "<table id='jsErrorsTable'><thead>"
         + "<tr><th style='width:150px;'>Source</th><th style='width:100px;'>Severity</th><th>Error Message</th></tr>"
         + "</thead><tbody>" + jsErrorRows + "</tbody></table>"
         + "</div></div></details></div>";
  }

  // ── Phase D3 — Trend Stats / Outlier Badge ────────────────────────────────

  /**
   * Reads the {@code trendStats} block (written by C2) and renders a small
   * "vs recent" mini-card. When {@code trendStats.outlier} is true, the
   * card is promoted to a prominent badge with bg color.
   *
   * <p>The rendered HTML carries machine-readable {@code data-*}
   * attributes ({@code data-component='trend-stats'}, {@code data-z-score},
   * {@code data-outlier}, {@code data-sample-size}, {@code data-mean},
   * {@code data-stddev}) so reconciliation tests can assert on values
   * without scraping human-facing text. Same contract pattern as the
   * score-drivers row.</p>
   *
   * <p>Returns "" when:</p>
   * <ul>
   *   <li>No snapshot file</li>
   *   <li>No {@code trendStats} block</li>
   *   <li>{@code sampleSize} below the calculator's minimum (no outlier
   *       judgement is yet meaningful)</li>
   * </ul>
   */
  @SuppressWarnings("unchecked")
  private static String buildTrendStatsBlockHtml() {
    try {
      java.nio.file.Path p = java.nio.file.Paths.get("reports/trend/health_snapshot.json");
      if (!java.nio.file.Files.exists(p)) return "";
      JSONObject root = (JSONObject) new JSONParser().parse(
              java.nio.file.Files.readString(p));
      JSONObject ts = (JSONObject) root.get("trendStats");
      if (ts == null) return "";

      int sampleSize = (ts.get("sampleSize") instanceof Number sn) ? sn.intValue() : 0;
      // Below the calculator's minimum sample size, "outlier" is meaningless.
      // C2 chose 5 as MIN_SAMPLE_FOR_OUTLIER; mirror that here as a constant
      // rather than re-read it to keep the dashboard a pure consumer.
      final int MIN_SAMPLE = 5;
      if (sampleSize < MIN_SAMPLE) return "";

      double zScore = (ts.get("currentZScore") instanceof Number zn) ? zn.doubleValue() : 0.0;
      double mean   = (ts.get("mean")          instanceof Number mn) ? mn.doubleValue() : 0.0;
      double stdDev = (ts.get("stdDev")        instanceof Number dn) ? dn.doubleValue() : 0.0;
      double threshold = (ts.get("outlierThreshold") instanceof Number tn) ? tn.doubleValue() : 2.0;
      boolean outlier = Boolean.TRUE.equals(ts.get("outlier"));

      String bg, border, color, badge;
      if (outlier) {
        bg     = "#fff7ed";
        border = "#ea580c";
        color  = "#7c2d12";
        badge  = "⚠ Outlier — this run is " + formatPts(Math.abs(zScore))
              + "σ from the recent mean (threshold ±" + formatPts(threshold) + "σ)";
      } else {
        bg     = "#f8fafc";
        border = "#cbd5e1";
        color  = "#475569";
        badge  = "Within normal range — current z-score " + formatPts(zScore)
              + "σ (threshold ±" + formatPts(threshold) + "σ)";
      }

      // Compact one-line summary with structured attributes for tests.
      return "<div data-component='trend-stats'"
           + " data-sample-size='" + sampleSize + "'"
           + " data-z-score='" + formatPts(zScore) + "'"
           + " data-mean='" + formatPts(mean) + "'"
           + " data-stddev='" + formatPts(stdDev) + "'"
           + " data-outlier='" + outlier + "'"
           + " style='background:" + bg + ";border:1px solid " + border
           + ";border-left:5px solid " + border + ";border-radius:8px;"
           + "padding:10px 14px;margin-bottom:14px;color:" + color + ";"
           + "font-size:12px;display:flex;flex-wrap:wrap;align-items:center;gap:18px;'>"
           + "<span style='font-weight:700;'>" + escHtml(badge) + "</span>"
           + "<span style='color:#64748b;'>vs recent " + sampleSize + " runs: "
           + "mean " + formatPts(mean) + ", σ " + formatPts(stdDev) + "</span>"
           + "</div>";
    } catch (Exception e) {
      LOG.debug("[DashboardBuilder] Trend stats block render failed: {}", e.getMessage());
      return "";
    }
  }

  // ── Phase E3 — Override Banner ────────────────────────────────────────────

  /**
   * Reads {@code audit.overrides} from {@code health_snapshot.json} and renders
   * a red banner at the top of the dashboard when any gate-bypass flag is active.
   * Empty string when no overrides are active — the banner takes no space on
   * clean runs.
   *
   * <p>Display rules:</p>
   * <ul>
   *   <li>0 overrides → return ""</li>
   *   <li>Any "bypass"-kind override → red banner, "RELEASE GATE OVERRIDES ACTIVE"</li>
   *   <li>Only "intent-shift" (e.g. blocking mode) → blue banner, "Build gate is BLOCKING"</li>
   * </ul>
   */
  @SuppressWarnings("unchecked")
  private static String buildOverrideBannerHtml() {
    try {
      java.nio.file.Path p = java.nio.file.Paths.get("reports/trend/health_snapshot.json");
      if (!java.nio.file.Files.exists(p)) return "";
      JSONObject root = (JSONObject) new JSONParser().parse(
              java.nio.file.Files.readString(p));
      JSONObject audit = (JSONObject) root.get("audit");
      if (audit == null) return "";
      JSONArray overrides = (JSONArray) audit.get("overrides");
      if (overrides == null || overrides.isEmpty()) return "";

      boolean hasBypass    = false;
      boolean hasIntentShift = false;
      StringBuilder rows = new StringBuilder();
      for (Object o : overrides) {
        if (!(o instanceof JSONObject e)) continue;
        String prop  = getString(e, "property",     "");
        String value = getString(e, "currentValue", "");
        String eff   = getString(e, "effect",       "");
        String kind  = getString(e, "kind",         "bypass");
        if ("bypass".equals(kind)) hasBypass = true;
        else                       hasIntentShift = true;
        rows.append("<div style='display:flex;gap:14px;align-items:flex-start;"
                  + "padding:6px 0;border-top:1px solid rgba(0,0,0,0.06);'>")
            .append("<code style='font-size:11px;font-weight:700;color:inherit;"
                  + "background:rgba(255,255,255,0.5);padding:2px 8px;border-radius:4px;"
                  + "white-space:nowrap;'>")
            .append("-D").append(escHtml(prop)).append("=").append(escHtml(value))
            .append("</code>")
            .append("<span style='font-size:12px;line-height:1.5;'>")
            .append(escHtml(eff)).append("</span>")
            .append("</div>");
      }

      String bg, border, color, headline;
      if (hasBypass) {
        bg       = "#fef2f2";   // red bg
        border   = "#dc2626";
        color    = "#7f1d1d";
        headline = "⚠ Release-gate overrides ACTIVE — this run is not fully gate-protected";
      } else if (hasIntentShift) {
        bg       = "#eff6ff";   // blue bg (informational, not warning)
        border   = "#2563eb";
        color    = "#1e3a8a";
        headline = "ℹ Build gate is in BLOCKING mode — failures will fail the build";
      } else {
        return "";   // unreachable defensive
      }

      return "<div style='background:" + bg + ";border:2px solid " + border
           + ";border-left:6px solid " + border + ";border-radius:10px;padding:14px 20px;"
           + "margin-bottom:18px;color:" + color + ";'>"
           + "<div style='font-size:13px;font-weight:800;letter-spacing:0.3px;margin-bottom:6px;"
           + "text-transform:uppercase;'>" + headline + "</div>"
           + rows
           + "</div>";
    } catch (Exception e) {
      LOG.debug("[DashboardBuilder] Override banner render failed: {}", e.getMessage());
      return "";
    }
  }

  /** Backward-compat overload — no product-health context available. */
  private static String getReleaseDescription(HealthPolicy.ReleaseStatus status) {
    return getReleaseDescription(status, 100);
  }

  /**
   * Dimension-specific release description.  When {@code productHealth} is
   * available from the semantic snapshot, AT_RISK and BLOCKED descriptions call
   * out the responsible team rather than giving a generic message.
   */
  private static String getReleaseDescription(HealthPolicy.ReleaseStatus status, int productHealth) {
    return switch (status) {
      case READY   -> "All criteria met. Safe for release.";
      case WARNING -> "Minor issues detected. Review recommended.";
      case BLOCKED -> productHealth < 20
          ? "Product instability critical (Frontend Engineering). Release blocked."
          : "Critical failures or policy violation. Release blocked.";
      case AT_RISK -> productHealth < 50
          ? "Product instability detected (Frontend Engineering). Resolve JS error clusters before release."
          : "Test suite regressions present (QA Automation). Re-run after investigation.";
    };
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
        /*
         * Export menu — buttons that download the pre-rendered PDFs sitting
         * next to the dashboard HTML.  Never wire these to window.print(); the
         * PDFs are produced by a separate semantic renderer for a reason.
         */
        .export-menu { display: flex; gap: 8px; }
        .export-btn {
          display: inline-flex; align-items: center; gap: 6px;
          padding: 7px 12px; border-radius: 6px;
          background: #fff; border: 1px solid #cbd5e1;
          color: #334155; font-size: 12px; font-weight: 600;
          text-decoration: none; cursor: pointer;
          transition: all 0.15s ease;
        }
        .export-btn:hover { border-color: #3b82f6; color: #1d4ed8; background: #eff6ff; }
        .export-btn-exec:hover { border-color: #16a34a; color: #166534; background: #f0fdf4; }
        .export-btn-tech:hover { border-color: #6366f1; color: #3730a3; background: #eef2ff; }
        .export-icon { font-size: 14px; line-height: 1; }
        .export-label { letter-spacing: 0.3px; }

        /*
         * Legacy sections — visually de-emphasised so the semantic panel reads
         * as the authoritative source.  Kept accessible via collapsed details
         * for forensic drill-down but never the first thing the eye lands on.
         */
        .legacy-section { margin-bottom: 16px; }
        .legacy-section[open] { background: #fff !important; border-color: #cbd5e1 !important; }
        .legacy-section summary:hover { background: #f1f5f9; border-radius: 8px 8px 0 0; }
        .legacy-section summary::marker { color: #94a3b8; }
        .legacy-score { opacity: 0.78; transition: opacity 0.18s ease; }
        .legacy-score:hover { opacity: 1; }

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
        .stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 24px; }
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

      /* ── Sticky Nav Tabs ── */
      .sticky-nav {
        position: sticky; top: 0; z-index: 200;
        background: rgba(240, 244, 248, 0.95);
        backdrop-filter: blur(8px);
        -webkit-backdrop-filter: blur(8px);
        padding: 8px 0 10px;
        margin-bottom: 16px;
        border-bottom: 1px solid rgba(226,232,240,0.7);
      }
      .sticky-nav-inner {
        display: flex; gap: 4px;
        background: white; border-radius: 10px; padding: 4px;
        border: 1px solid #e2e8f0;
        box-shadow: 0 1px 4px rgba(0,0,0,0.06);
        overflow-x: auto; scrollbar-width: none;
      }
      .sticky-nav-inner::-webkit-scrollbar { display: none; }
      .nav-tab {
        padding: 6px 16px; border-radius: 7px; font-size: 13px; font-weight: 500;
        color: #64748b; text-decoration: none; white-space: nowrap;
        transition: all 0.15s; border: none; background: none; cursor: pointer;
      }
      .nav-tab:hover { background: #f1f5f9; color: #1e293b; }
      .nav-tab.active { background: #6366f1; color: white; font-weight: 600; }
      /* scroll-margin-top so sections don't hide behind sticky nav */
      [data-section] { scroll-margin-top: 64px; }
      /* ── Global Search ── */
      .global-search-wrap { position: relative; margin-bottom: 16px; }
      .global-search {
        width: 100%; padding: 10px 16px 10px 40px;
        border: 1.5px solid #e2e8f0; border-radius: 10px;
        font-size: 14px; background: white; color: #1e293b;
        transition: border-color 0.2s; box-shadow: 0 1px 3px rgba(0,0,0,0.05);
      }
      .global-search:focus { outline: none; border-color: #6366f1; }
      .global-search-icon { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: #94a3b8; font-size: 16px; pointer-events: none; }
      .search-highlight { background: #fef9c3; border-radius: 2px; }
      /* ── Severity Filter Chips ── */
      .sev-filter-row { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; align-items: center; }
      .sev-chip {
        padding: 4px 12px; border-radius: 20px; font-size: 12px; font-weight: 600;
        border: 1.5px solid #e2e8f0; background: white; cursor: pointer; color: #64748b;
        transition: all 0.15s;
      }
      .sev-chip:hover { border-color: #94a3b8; color: #334155; }
      .sev-chip.active { background: #1e293b; color: white; border-color: #1e293b; }
      .sev-chip.chip-critical.active { background: #ef4444; border-color: #ef4444; }
      .sev-chip.chip-cascade.active  { background: #f97316; border-color: #f97316; }
      .sev-chip.chip-new.active      { background: #dc2626; border-color: #dc2626; }
      .sev-chip.chip-gov.active      { background: #6366f1; border-color: #6366f1; }
      /* ── Appendix / details ── */
      details.appendix { background: white; border-radius: 12px; border: 1px solid #e2e8f0; margin-bottom: 20px; }
      details.appendix > summary {
        padding: 16px 24px; font-size: 14px; font-weight: 600; color: #64748b;
        cursor: pointer; list-style: none; display: flex; align-items: center; gap: 8px;
      }
      details.appendix > summary::before { content: '▶'; font-size: 10px; transition: transform 0.2s; }
      details.appendix[open] > summary::before { transform: rotate(90deg); }
      details.appendix > .appendix-body { padding: 0 24px 20px; }
      /* ── Passed tests collapsed by default ── */
      .tests-passed-row { display: none; }
      .tests-passed-row.visible { display: table-row; }
      .show-passed-toggle { cursor: pointer; font-size: 12px; color: #6366f1; font-weight: 600; background: none; border: none; padding: 6px 0; }
      /* ── Responsive ── */
      @media (max-width: 1200px) {
        .stats-grid { grid-template-columns: repeat(2, 1fr); }
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
      {{VIDEO_STYLES}}
      </style>
      </head>
      <body>

      <div class="container">
        <!-- Sticky Navigation Tabs -->
        <div class="sticky-nav">
          <div class="sticky-nav-inner">
            <button class="nav-tab active" onclick="navTo('sec-overview')">Overview</button>
            <button class="nav-tab" onclick="navTo('sec-workflows')">Workflows</button>
            <button class="nav-tab" onclick="navTo('sec-failures')">Failures</button>
            <button class="nav-tab" onclick="navTo('sec-trends')">Trends</button>
            <button class="nav-tab" onclick="navTo('sec-governance')">Governance</button>
            <button class="nav-tab" onclick="navTo('sec-runs')">Test Runs</button>
            <button class="nav-tab" onclick="navTo('sec-recordings')">▶ Recordings</button>
            <button class="nav-tab" onclick="navTo('sec-appendix')">Appendix</button>
          </div>
        </div>

        <!-- Header -->
        <div class="header" data-section id="sec-overview">
          <div class="header-left">
            <div class="header-title">Health Dashboard</div>
            <div class="header-chips">
              <span class="header-chip">&#x1F4C5; <strong>{{TIMESTAMP}}</strong></span>
              <span class="header-chip">Env: <strong>{{ENV}}</strong></span>
              <span class="header-chip">Suite: <strong>{{SUITE}}</strong></span>
              <span class="header-chip">Branch: <strong>{{GIT}}</strong></span>
              <span class="header-chip">Duration: <strong>{{DURATION}}</strong></span>
            </div>
          </div>
          <div class="header-right">
            <!--
              Export menu — these link directly to the PDFs generated by
              PdfReportBuilder during BaseTest.afterSuite().  They are NOT a
              browser print-to-PDF: clicking them downloads the same semantic
              renderer's output that goes to CI artefacts.  This guarantees the
              shared PDF matches what the build emitted, byte-for-byte.
            -->
            <div class="export-menu" title="Pre-rendered PDF reports from this run">
              <a class="export-btn export-btn-exec" href="executive_report.pdf" download
                 title="Executive summary — for managers, release reviewers, stakeholders">
                <span class="export-icon">📄</span>
                <span class="export-label">Executive PDF</span>
              </a>
              <a class="export-btn export-btn-tech" href="technical_report.pdf" download
                 title="Technical deep-dive — for QA, engineering, DevOps">
                <span class="export-icon">📊</span>
                <span class="export-label">Technical PDF</span>
              </a>
            </div>
            <span class="status-badge {{STATUS_BADGE_CLASS}}">{{STATUS}}</span>
            {{LEGACY_SCORE_RING}}
          </div>
        </div>

        <!-- Global Search + Severity Filters -->
        <div class="global-search-wrap">
          <span class="global-search-icon">&#x1F50D;</span>
          <input type="text" class="global-search" id="globalSearch" placeholder="Search across failures, workflows, JS errors, clusters…" autocomplete="off">
        </div>
        <div class="sev-filter-row">
          <span style="font-size:11px;font-weight:600;color:#94a3b8;text-transform:uppercase;letter-spacing:0.5px;margin-right:4px;">Filter:</span>
          <button class="sev-chip chip-all active"      onclick="setSevFilter('all',      this)">All</button>
          <button class="sev-chip chip-critical"        onclick="setSevFilter('critical', this)">Critical only</button>
          <button class="sev-chip chip-cascade"         onclick="setSevFilter('cascade',  this)">Cascades</button>
          <button class="sev-chip chip-new"             onclick="setSevFilter('new',      this)">New regressions</button>
          <button class="sev-chip chip-gov"             onclick="setSevFilter('gov',      this)">Governance alerts</button>
        </div>

        {{OVERRIDE_BANNER}}

        {{TRIAGE_BOX}}

        {{TREND_STATS_BLOCK}}

        {{EXECUTIVE_PANEL}}

        {{RELEASE_BLOCKERS}}

        {{SEMANTIC_PANEL}}

        {{DATA_QUALITY_PANEL}}

        {{PLATFORM_HEALTH_PANEL}}

        {{CONFIDENCE_INDICATOR}}

        {{DELTA_PANEL}}

        {{LEGACY_STATS_BLOCK}}

        {{ORCHESTRATION_PANEL}}

        <div data-section id="sec-governance"></div>
        {{GOVERNANCE_PANEL}}

        <div data-section id="sec-workflows"></div>
        {{WORKFLOW_MATRIX}}

        {{ROOT_CAUSE_CLUSTERS}}

        <!-- Health Score Trend + Penalty Breakdown -->
        <div data-section id="sec-trends"></div>
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

        <div data-section id="sec-failures"></div>
        <!-- Test Failure Details -->
        <div class="section">
          <div class="section-header collapsible-header" data-target="testFailuresContent">
            <div class="section-title">Test Failure Details <span class="count">{{FAILED_COUNT}}</span></div>
          </div>
          <div class="collapsible-content" id="testFailuresContent">
            <table id="testFailuresTable">
              <thead>
                <tr><th data-sort="text">Test Name <span class="sort-icon">↕</span></th><th>Reason &amp; Stack Trace</th></tr>
              </thead>
              <tbody>
                {{TEST_FAILURES_ROWS}}
              </tbody>
            </table>
          </div>
        </div>

        <!-- Legacy raw Slow Pages + JS Errors tables — gated behind
             -Ddashboard.showLegacy=true (both blocks renderable for forensics). -->
        {{LEGACY_SLOW_PAGES_TABLE}}
        {{LEGACY_JS_ERRORS_TABLE}}

        <div data-section id="sec-appendix"></div>
        <!-- Recordings section -->
        <div data-section id="sec-recordings"></div>
        {{RECORDINGS_SECTION}}

        <!-- Appendix: Fallbacks + Warnings (collapsed) -->
        <details class="appendix">
          <summary>Appendix — Fallbacks &amp; Warnings <span class="count" style="margin-left:4px;">{{FALLBACK_COUNT}} fallbacks · {{WARNING_COUNT}} warnings</span></summary>
          <div class="appendix-body">
            <div class="two-col">
              <div>
                <div style="font-size:13px;font-weight:600;color:#64748b;margin-bottom:10px;">Fallback Details</div>
                <table>
                  <thead><tr><th>Navigation</th><th>Fallback URL</th></tr></thead>
                  <tbody>{{FALLBACK_ROWS}}</tbody>
                </table>
              </div>
              <div>
                <div style="font-size:13px;font-weight:600;color:#64748b;margin-bottom:10px;">Warnings <span class="count">{{WARNING_COUNT}}</span></div>
                <div id="warningsHigh" class="warnings-group">
                  <table>
                    <thead><tr><th>Source</th><th>Warning</th></tr></thead>
                    <tbody>{{WARNINGS_HIGH_ROWS}}</tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        </details>

        <div data-section id="sec-runs"></div>
        <!-- Recent Test Runs -->
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
                <button class="fg-btn" data-filter="fail"   data-table="tests" id="fg-fail">Failed only</button>
                <button class="fg-btn" data-filter="all"    data-table="tests" id="fg-all">All</button>
                <button class="fg-btn" data-filter="pass"   data-table="tests" id="fg-pass">Passed</button>
              </div>
              <div class="filter-group">
                <span class="filter-group-label">Type</span>
                <button class="fg-btn active" data-filter="all"        data-table="tests" data-filterby="category" id="fg-type-all">All</button>
                <button class="fg-btn"        data-filter="SANITY"     data-table="tests" data-filterby="category" id="fg-sanity">Sanity</button>
                <button class="fg-btn"        data-filter="SMOKE"      data-table="tests" data-filterby="category" id="fg-smoke">Smoke</button>
                <button class="fg-btn"        data-filter="REGRESSION" data-table="tests" data-filterby="category" id="fg-regression">Regression</button>
                <button class="fg-btn"        data-filter="FULL"       data-table="tests" data-filterby="category" id="fg-full">Full</button>
              </div>
              <button class="show-passed-toggle" id="showPassedBtn" onclick="togglePassedRows()" style="margin-left:auto;">Show {{PASSED_STAT}} passed tests ▼</button>
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
        document.querySelectorAll('#testsTable tbody tr').forEach(row => {
          const rowStatus   = (row.getAttribute('data-status') || '').toLowerCase();
          const rowCategory = (row.getAttribute('data-category') || '').toUpperCase();
          const isPass      = rowStatus === 'pass';

          // Passed rows respect both the toggle and the status filter
          const passOk     = passedVisible || activeStatusFilter === 'pass';
          const statusOk   = activeStatusFilter === 'all'
              ? (isPass ? passOk : true)
              : rowStatus === activeStatusFilter;
          const categoryOk = activeCategoryFilter === 'all' || rowCategory === activeCategoryFilter.toUpperCase();

          row.style.display = (statusOk && categoryOk) ? '' : 'none';
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

      // === STICKY NAV ===
      function navTo(sectionId) {
        const el = document.getElementById(sectionId);
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
        const sectionMap = {
          'sec-overview': 0, 'sec-workflows': 1, 'sec-failures': 2,
          'sec-trends': 3, 'sec-governance': 4, 'sec-runs': 5, 'sec-recordings': 6, 'sec-appendix': 7
        };
        const tabs = document.querySelectorAll('.nav-tab');
        const idx = sectionMap[sectionId];
        if (tabs[idx]) tabs[idx].classList.add('active');
      }

      // Scroll-spy: update active nav tab on scroll
      (function() {
        const sections = ['sec-overview','sec-workflows','sec-failures','sec-trends','sec-governance','sec-runs','sec-recordings','sec-appendix'];
        const sectionMap = {};
        sections.forEach((id, i) => { const el = document.getElementById(id); if (el) sectionMap[i] = el; });
        const tabs = document.querySelectorAll('.nav-tab');
        function updateActiveTab() {
          let current = 0;
          Object.entries(sectionMap).forEach(([i, el]) => {
            if (el.getBoundingClientRect().top <= 80) current = parseInt(i);
          });
          tabs.forEach((t, i) => t.classList.toggle('active', i === current));
        }
        window.addEventListener('scroll', updateActiveTab, { passive: true });
      })();

      // === SEVERITY FILTER CHIPS ===
      function setSevFilter(type, chip) {
        document.querySelectorAll('.sev-chip').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');

        // Reset all filtered elements first
        document.querySelectorAll('#testFailuresTable tbody tr, #jsErrorsTable tbody tr, #slowPagesTable tbody tr').forEach(r => r.style.removeProperty('display'));

        if (type === 'all') return;

        if (type === 'critical') {
          // Show only CRITICAL severity rows
          document.querySelectorAll('#testFailuresTable tbody tr').forEach(r => {
            const txt = r.textContent.toUpperCase();
            r.style.display = (txt.includes('CRITICAL') || txt.includes('PRODUCT_BUG') || txt.includes('PRODUCT BUG')) ? '' : 'none';
          });
          document.querySelectorAll('#jsErrorsTable tbody tr').forEach(r => {
            r.style.display = r.getAttribute('data-severity') === 'critical' ? '' : 'none';
          });
        } else if (type === 'cascade') {
          // Show only cascade-related rows
          document.querySelectorAll('#testFailuresTable tbody tr').forEach(r => {
            r.style.display = r.textContent.toLowerCase().includes('cascade') ? '' : 'none';
          });
        } else if (type === 'new') {
          // Show only rows that are "new" relative to last run — highlight delta section
          document.getElementById('sec-overview')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } else if (type === 'gov') {
          // Scroll to governance section
          document.getElementById('sec-governance')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      }

      // === PASSED TESTS COLLAPSE ===
      let passedVisible = false;
      function togglePassedRows() {
        passedVisible = !passedVisible;
        document.querySelectorAll('#testsTable tbody tr[data-status="pass"]').forEach(row => {
          row.style.display = passedVisible ? '' : 'none';
        });
        const btn = document.getElementById('showPassedBtn');
        if (btn) btn.textContent = passedVisible
          ? 'Hide passed tests ▲'
          : 'Show {{PASSED_STAT}} passed tests ▼';
        // re-apply current filters so status filter still applies
        applyTestFilter();
      }
      // Collapse passed on initial load
      document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('#testsTable tbody tr[data-status="pass"]').forEach(row => {
          row.style.display = 'none';
        });
        // Default status filter: fail only
        document.getElementById('fg-fail')?.classList.add('active');
        activeStatusFilter = 'fail';
        applyTestFilter();
      });

      // === WORKFLOW MATRIX ROW EXPAND ===
      function toggleWfDetail(id) {
        const row = document.getElementById(id);
        if (!row) return;
        row.style.display = row.style.display === 'none' ? '' : 'none';
      }

      // === GLOBAL SEARCH ===
      document.getElementById('globalSearch')?.addEventListener('input', function() {
        const q = this.value.toLowerCase().trim();
        if (!q) {
          // restore normal visibility when cleared
          document.querySelectorAll('[data-searchable]').forEach(el => el.style.display = '');
          document.querySelectorAll('[data-searchable-row]').forEach(row => row.style.removeProperty('display'));
          return;
        }
        // Search test failure rows
        document.querySelectorAll('#testFailuresTable tbody tr').forEach(row => {
          row.style.display = row.textContent.toLowerCase().includes(q) ? '' : 'none';
        });
        // Search test runs table
        document.querySelectorAll('#testsTable tbody tr').forEach(row => {
          row.style.display = row.textContent.toLowerCase().includes(q) ? '' : 'none';
        });
        // Search slow pages
        document.querySelectorAll('#slowPagesTable tbody tr').forEach(row => {
          row.style.display = row.textContent.toLowerCase().includes(q) ? '' : 'none';
        });
        // Search JS errors
        document.querySelectorAll('#jsErrorsTable tbody tr').forEach(row => {
          row.style.display = row.textContent.toLowerCase().includes(q) ? '' : 'none';
        });
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
      {{VIDEO_MODAL}}
      {{VIDEO_SCRIPTS}}
      </body>
      </html>
      """;
}
