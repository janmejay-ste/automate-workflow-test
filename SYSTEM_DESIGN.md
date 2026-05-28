# System Design — AppyPie Automate QA Framework

**Project:** `com.appypie.testautomation:appypie-automation`
**Target Application:** https://www.appypieautomate.ai
**Dashboard (Auth):** https://connectcloud.appypie.com/connects
**Editor (Auth):** https://connectcloud.appypie.com/customeditor
**Last Updated:** 2026-05-26

---

## 1. Purpose & Goals

A **production-grade QA intelligence system** built on Selenium + TestNG. Goals extend beyond binary pass/fail:

- Execute UI automation tests across public and authenticated AppyPie Automate flows
- Instrument every run with runtime signals: JS errors, locator reliability, network failures, performance metrics
- Aggregate signals into a **health score (0–100)** reflecting overall product quality
- Generate an **interactive HTML dashboard** for release decision-making
- **Gate releases** automatically when health degrades below configurable thresholds
- Support **AI-driven root cause analysis** and release narrative generation
- Provide **historical trending**, failure clustering, and orchestration intelligence
- Cover **end-to-end Connect workflow automation** for third-party app integrations

---

## 2. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                      Maven Build (mvn test)                          │
└─────────────────────────────┬────────────────────────────────────────┘
                              │ -DsuiteFile=<suite>.xml
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       TestNG Suite Runner                            │
│  testng.xml / sanity-testng.xml / smoke-testng.xml / full-testng.xml│
│  Listeners: TestNGInitListener, CustomHtmlReporter                   │
└──────────┬───────────────────────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         BaseTest (Abstract)                          │
│  @BeforeSuite  → silence logging                                     │
│  @BeforeMethod → driver init · auth check · category validation      │
│  @AfterMethod  → artifact capture · health recording · analytics     │
│  @AfterSuite   → analytics · trend export · dashboard · gate enforce │
└──────────┬───────────────────────────────────────────────────────────┘
           │ extends
           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                      Test Classes (testing/)                         │
│  AppPairingTest · LoginTest · SignupTest · AuthenticatedTest         │
│  CreateConnectWorkflowTest · GoHighLevelMindbodyConnectTest          │
│  AppDirectoryTest · ErrorHandlingTest · HomepageExhaustiveTest       │
│  AppyPieNavigationTest · ResponsiveTest · TopAppCombinationsTest     │
│  TrendingAppIntegrationsTest · ExploreMenuIntegrationTest            │
└──────────┬───────────────────────────────────────────────────────────┘
           │ uses
           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Page Object Model (pages/)                       │
│  DashboardPage · ConnectEditorPage · AppyPieAutomatePage             │
│  AppDirectoryPage · LoginPage · SignupPage · ConnectTopNavigation    │
│  ErrorPage · ResponsiveHelper · WaitUtils · auth/AuthState           │
└──────────┬───────────────────────────────────────────────────────────┘
           │ signals flow into
           ▼
┌──────────────────────────────────────────────────────────────────────┐
│               Observability & Instrumentation (utils/)               │
│                                                                      │
│  ApplicationReadiness    NetworkMonitor    JsConsoleMonitor          │
│  (4-signal readiness)    (XHR/fetch)       (SEVERE errors)           │
│         │                    │                   │                   │
│         └────────────────────┴───────────────────┘                  │
│                              │ all feed into                         │
│                              ▼                                       │
│                    ┌──────────────────────┐                          │
│                    │   HealthTracker       │ ← Singleton             │
│                    │  (penalty aggregator) │                          │
│                    └──────────┬───────────┘                          │
│                               │                                      │
│                    ┌──────────▼───────────┐                          │
│                    │  AnalyticsCollector   │                          │
│                    └──────────┬───────────┘                          │
└───────────────────────────────┼──────────────────────────────────────┘
                                │
          ┌─────────────────────┼──────────────────────┐
          ▼                     ▼                      ▼
 ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────────┐
 │ Reporting       │  │ AI Intelligence  │  │ Orchestration        │
 │ TrendExporter   │  │ FailureAnalyzer  │  │ ExecutionPriorityEng │
 │ HistoryJson     │  │ ReleaseNarrator  │  │ IntelligentRetryMgr  │
 │ DashboardBuilder│  │ AiEnrichmentExec │  │ RiskWeightedSuite    │
 │ RiskInterpreter │  │ AiCacheService   │  │ CorrelationEngine    │
 │ HealthGate      │  └──────────────────┘  └──────────────────────┘
 └────────┬────────┘
          │ writes
          ▼
 ┌──────────────────────────────┐
 │ reports/                     │
 │  trend/dashboard.html        │
 │  trend/history.csv           │
 │  trend/history.json          │
 │  trend/health_snapshot.json  │
 │  trend/semantic_history.jsonl│
 │  trend/executive_report.pdf  │
 │  trend/technical_report.pdf  │
 │  analytics/test_analytics.json│
 │  failures/{test}_{ts}/       │
 │  recordings/{test}_{ts}/     │
 └──────────────────────────────┘
```

---

## 3. Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 21 |
| Build | Maven | 3.x+ |
| Test Framework | TestNG | 7.10.2 |
| Browser Automation | Selenium Java | 4.24.0 |
| PDF Generation | OpenPDF / openhtmltopdf | 1.3.43 / 1.0.10 |
| HTML Processing | jsoup | 1.17.2 |
| JSON | json-simple | 1.1.1 |
| Chart Rendering | XChart | 3.8.8 |
| Logging | SLF4J Simple | 2.0.9 |
| Video Recording | FFmpeg (external, in PATH) | any |
| Browser | Chrome (ChromeDriver) | auto-managed |

---

## 4. Package Structure

```
src/test/java/
├── base/
│   ├── BaseTest.java              # Abstract lifecycle: driver, auth, health, teardown
│   ├── TestCategory.java          # Annotation: type, owner, feature, severity, requiresLogin
│   ├── TestType.java              # Enums: SMOKE/SANITY/REGRESSION/FULL + Severity + FailureType
│   └── TestNGInitListener.java    # IExecutionListener: silences JUL + DevTools logging
│
├── pages/
│   ├── AppyPieAutomatePage.java   # Homepage POM + Web Vitals (LCP, CLS, Nav time)
│   ├── AppDirectoryPage.java      # App directory, search, card validation
│   ├── ConnectEditorPage.java     # Workflow editor: trigger/action/setup/activate
│   │                              #   Key methods (see §9 for detail):
│   │                              #   selectTriggerApp / selectTriggerEvent
│   │                              #   selectActionApp  / selectActionEvent
│   │                              #   handleSetupStep(Map) — targeted dropdown search
│   │                              #   clickContinue / clickContinueRunTest / clickActivateConnect
│   │                              #   fillGmailDraftSetup()    — Gmail Draft field mapping
│   │                              #   fillMindbodySaleSetup()  — Mindbody Create Sale fields
│   │                              #   fillMindbodyCustomValue  — readmorebutton2 → chip
│   │                              #   fillMindbodyContentEditable — div[contenteditable]
│   │                              #   fillMindbodyDirectInput  — plain <input> sendKeys
│   │                              #   mapGmailTextField        — choices-container mapping
│   ├── ConnectTopNavigation.java  # Top nav bar; external tab management
│   ├── DashboardPage.java         # Authenticated dashboard; logout
│   ├── ErrorPage.java             # 404/500 detection and recovery
│   ├── LoginPage.java             # Auth transition wait
│   ├── SignupPage.java            # Signup flow start
│   ├── ResponsiveHelper.java      # Viewport switching + responsive assertions
│   ├── WaitUtils.java             # Explicit waits, overlay/loader dismissal, guide completion
│   └── auth/
│       └── AuthState.java         # Static: localStorage + cookie session detection
│
├── testing/
│   ├── AppPairingTest.java               # REGRESSION: 6-iteration app pairing
│   ├── AppDirectoryTest.java             # SANITY: directory load, search, card content
│   ├── AppyPieNavigationTest.java        # SANITY: homepage, nav links, LCP/CLS
│   ├── AuthenticatedTest.java            # FULL: E2E login→dashboard→connect→activate
│   ├── CreateConnectWorkflowTest.java    # FULL: Google Sheets → Gmail Draft (17 steps)
│   ├── GoHighLevelMindbodyConnectTest.java # FULL: GoHighLevel V2 → Mindbody Create Sale
│   ├── ErrorHandlingTest.java            # REGRESSION: 404, special chars, XSS, recovery
│   ├── ExploreMenuTestBase.java          # Base class for explore-menu tests
│   ├── ExploreMenuIntegrationTest.java   # Integration: explore menu navigation flows
│   ├── HomepageExhaustiveTest.java       # SANITY: all links/buttons HTTP status check
│   ├── LoginTest.java                    # SMOKE/SANITY: auth flow start
│   ├── ResponsiveTest.java               # REGRESSION: 10 viewport tests
│   ├── SignupTest.java                   # SMOKE/SANITY: signup flow start
│   ├── TopAppCombinationsTest.java       # REGRESSION: top app combination workflows
│   └── TrendingAppIntegrationsTest.java  # REGRESSION: trending app integration flows
│
└── utils/
    ├── ApplicationReadiness.java  # 4-signal composite readiness (ready+DOM+network+loader)
    ├── ConsoleLogFilter.java      # Browser SEVERE log capture, filter, route to HealthTracker
    ├── CustomHtmlReporter.java    # TestNG HTML report customization
    ├── DashboardBuilder.java      # Generates reports/trend/dashboard.html
    ├── DashboardLauncher.java     # Auto-opens dashboard in browser post-suite
    ├── FailureArtifactManager.java# screenshot + DOM + URL + console on failure
    ├── HealthPolicy.java          # Scoring constants, penalty formulas, thresholds
    ├── HistoryJsonWriter.java     # Appends run to reports/trend/history.json
    ├── JsConsoleMonitor.java      # SEVERE browser error capture + classification
    ├── ManualLoginHelper.java     # Automated login with 3-min manual fallback
    ├── NetworkMonitor.java        # fetch/XHR interception (JS-based, no CDP)
    ├── RetryClassifier.java       # TRANSIENT vs STRUCTURAL failure classification
    ├── RiskInterpreter.java       # Maps signals → release decision
    ├── SnapshotReader.java        # Reads health snapshots
    ├── TrendChartRenderer.java    # XChart-based score trend charts
    ├── TrendDataWriter.java       # CSV history writer
    ├── TrendExporter.java         # Orchestrates trend update
    ├── VideoRecorder.java         # Optional MP4 recording via FFmpeg (-DrecordVideo=true)
    │                              #   ThreadLocal per-test; 4 FPS PNG frames → FFmpeg stitch
    │
    ├── analytics/
    │   ├── AnalyticsCollector.java    # Unified JSON aggregator for all trackers
    │   ├── JsErrorTracker.java        # Deduplicates + categorizes JS errors
    │   ├── LocatorTracker.java        # Per-feature primary/fallback locator rates
    │   ├── PerformanceTracker.java    # Page load vs baselines
    │   └── TestAnalyticsLogger.java   # Per-test lifecycle: started/passed/failed/skipped
    │
    ├── health/
    │   ├── HealthTracker.java         # Singleton penalty aggregator + score + EMA smoothing
    │   ├── HealthGate.java            # Suite gate: Assert.fail if score < threshold
    │   ├── business/
    │   │   └── BusinessOutcomeTracker.java  # Tracks success/partial/failed business transactions
    │   ├── report/
    │   │   ├── PdfReportBuilder.java  # openhtmltopdf PDF (EXECUTIVE + TECHNICAL modes)
    │   │   └── ReportDto.java
    │   ├── semantic/
    │   │   ├── SemanticHealthSnapshot.java  # Serializes per-run semantic scores
    │   │   ├── LayeredHealthScores.java     # productHealth/frameworkHealth/businessOutcome
    │   │   ├── SemanticHealthAnalyzer.java  # Computes layered scores from raw signals
    │   │   └── ErrorCluster.java            # Single cluster: title, count, domain, severity
    │   └── trend/
    │       └── SemanticTrendWriter.java     # Appends to semantic_history.jsonl
    │
    ├── policy/
    │   └── SeverityClassifier.java
    │
    ├── dashboard/
    │   └── components/
    │       ├── SemanticPanelComponent.java  # Renders layered scores + error clusters section
    │       └── UrlValidationComponent.java  # Renders URL validation findings section
    │
    ├── ai/
    │   ├── client/   AiClient · AiConfig · AiRateLimiter · AiRetryPolicy
    │   ├── models/   AiAnalysisResult · AiFailureAnalysis · AiReleaseNarrative
    │   ├── preprocess/ DomReducer · LogReducer · StacktraceReducer
    │   ├── prompts/  FailureAnalysisPrompt · ReleaseNarrativePrompt
    │   ├── services/ AiCacheService · AiEnrichmentExecutor · FailureAnalyzer · ReleaseNarrator
    │   └── storage/  AiResultReader · AiResultWriter
    │
    ├── clustering/
    │   ├── FailureCluster.java
    │   └── FailureClusterService.java
    │
    ├── correlation/
    │   ├── CascadeFailureDetector.java
    │   ├── CorrelationSnapshotBuilder.java
    │   ├── DependencyRegistry.java
    │   ├── FailureCorrelationEngine.java
    │   ├── FailurePropagationAnalyzer.java
    │   ├── RootCauseClusterBuilder.java
    │   ├── WorkflowDependencyGraph.java
    │   ├── WorkflowHealthScorer.java
    │   └── dto/  CascadeFailureDto · CorrelatedFailureDto · CorrelationSnapshotDto · ...
    │
    ├── governance/
    │   ├── ConfidenceGuardrailService.java
    │   ├── DecisionAuditLogger.java
    │   ├── ExplainabilityEngine.java
    │   ├── GovernanceReportGenerator.java
    │   ├── GovernanceSnapshotBuilder.java
    │   ├── HumanOverrideRegistry.java
    │   ├── RecommendationSafetyValidator.java
    │   ├── SuppressionAuditTracker.java
    │   └── dto/  AuditEntryDto · ExplanationDto · GovernanceSnapshotDto · ...
    │
    ├── history/
    │   ├── HistoryConfig.java · HistoricalRunStore.java · HistoricalMetricsAggregator.java
    │   ├── LocatorReliabilityHistory.java · PagePerformanceHistory.java
    │   ├── TestStabilityTracker.java · TrendComputationService.java
    │   ├── calibration/  ConfidenceCalibrationService · AlertCooldownManager
    │   │               NoiseSuppressionService · SignalTrustScorer
    │   │               RegressionSensitivityTuner · StabilityThresholdCalibrator
    │   │               FeedbackCaptureService · SignalValidationReport
    │   ├── trends/  OscillationDetector · PerformanceTrendAnalyzer · RegressionSpikeDetector
    │   │           ReliabilityTrendAnalyzer · StabilityComputationEngine
    │   │           TrendConfidenceCalculator · TrendSnapshotBuilder
    │   └── dto/  HistoricalTrendDto · RegressionSpikeDto · StabilityTrendDto · ...
    │
    ├── orchestration/
    │   ├── AdaptiveExecutionPlanner.java · CriticalPathSelector.java
    │   ├── ExecutionPriorityEngine.java · IntelligentRetryManager.java
    │   ├── OrchestrationConfig.java · OrchestrationSnapshotBuilder.java
    │   ├── RegressionRiskPredictor.java · ResourceAllocationOptimizer.java
    │   ├── RiskWeightedSuiteBuilder.java · SuiteMinimizationEngine.java
    │   └── dto/  ExecutionPlanDto · OrchestrationSnapshotDto · WorkflowRiskDto · ...
    │
    └── urlvalidator/
        ├── UrlValidator.java · UrlValidationRunner.java
        ├── UrlDiscoveryEngine.java · UrlCanonicalizer.java
        ├── SensitivePathDetector.java
        ├── DiscoveredUrl.java · UrlFinding.java · UrlFindingClassifier.java
        ├── UrlFindingType.java · UrlSourceKind.java
        ├── UrlValidationResult.java · UrlValidationTracker.java
        └── (renders via dashboard/components/UrlValidationComponent)
```

---

## 5. BaseTest Lifecycle

```
@BeforeSuite
  └─ silenceJavaUtilLogging()

@BeforeMethod (per test)
  ├─ createDriver() if null
  │    └─ ChromeOptions: --no-sandbox, --disable-dev-shm-usage
  │       headless mode: System.getProperty("headless", "false")
  │       LoggingPreferences: BROWSER=ALL (for JS error capture)
  ├─ resolve @TestCategory (method-level overrides class-level)
  ├─ if requiresLogin → navigate to /dashboard
  │    → fail-fast SkipException if not authenticated
  ├─ validateCategoryConsistency() → warn if TestNG groups ≠ type
  ├─ validateTestHasGroup() → warn if no groups
  ├─ owner enforcement:
  │    FULL + missing owner → SkipException (governance)
  │    REGRESSION + missing owner → warning only
  ├─ VideoRecorder.start() if -DrecordVideo=true
  └─ TestAnalyticsLogger.testStarted()

@AfterMethod (per test)
  ├─ VideoRecorder teardown (FIRST — while driver still healthy):
  │    FAILURE → stop(failures/{folder}, assemble=true) → recording.mp4
  │    SUCCESS + -DrecordPassedVideo → stop(recordings/{folder}, true)
  │    else → stop(null, false) — discard frames immediately
  ├─ FAILURE:
  │    ├─ isDriverHealthy() → FailureArtifactManager.capture()
  │    ├─ TestAnalyticsLogger.testFailed()
  │    ├─ HealthTracker.recordTestFailure()
  │    └─ HealthTracker.addTestRecord(... "FAIL", artifactFolder, videoPath ...)
  ├─ SUCCESS:
  │    ├─ TestAnalyticsLogger.testPassed()
  │    ├─ HealthTracker.recordTestSuccess()
  │    └─ HealthTracker.addTestRecord(... "PASS" ...)
  ├─ SKIP:
  │    └─ TestAnalyticsLogger.testSkipped()
  └─ ConsoleLogFilter.ignoreKnownErrors(driver)

@AfterSuite
  ├─ TestAnalyticsLogger.generateReports()
  ├─ HistoryJsonWriter.appendRun(AnalyticsCollector.collect())
  ├─ TrendExporter.updateTrend(score)
  ├─ HealthTracker.printReport()
  ├─ DashboardBuilder.write()
  └─ finally:
       ├─ DashboardLauncher.launchIfEnabled()
       └─ HealthGate.enforce(HealthTracker.get())
```

---

## 6. Core Subsystems

### 6.1 Health Scoring Model

**Scoring Formula:**
```
effectivePenalty  = min(rawPenalty, MAX_TOTAL_PENALTY = 150)
rawScore          = max(0, 100 − effectivePenalty)
smoothedScore     = EMA_ALPHA(0.3) × rawScore + 0.7 × previousSmoothedScore
```

**Penalty Table:**

| Source | Base Penalty | Cap | Notes |
|---|---|---|---|
| Test failure | 15 pts | — | Per failing test |
| JS error (SEVERE) | 1.0 pt | 20 pts | Deduplicated by message+stack |
| Locator fallback | 5 pts | — | Per fallback usage |
| Slow page ≥ 5s | 1 pt | — | Tiered: ≥5s / ≥10s(3pt) / ≥20s(6pt) |
| Warning | 0.5 pts | 10 pts | Capped aggregate |

**Flow Multipliers (context-aware):**

| Flow | Multiplier | Triggered by |
|---|---|---|
| CRITICAL | 2.0× | smoke, login, signup |
| CORE | 1.5× | sanity, homepage, navigation |
| SECONDARY | 1.0× | everything else |

**Status Thresholds:**

| Score | Status |
|---|---|
| ≥ 90 | EXCELLENT |
| 75–89 | HEALTHY |
| 50–74 | WARNING |
| 30–49 | POOR |
| < 30 | CRITICAL |

**Layered Scores (Semantic Health):**

| Dimension | Description |
|---|---|
| Product Health | Derived from JS error clusters (PRODUCT/INFRASTRUCTURE domain) |
| Framework Health | Derived from locator fallback rate + test failure signals |
| Telemetry Confidence | Network monitor reliability + DOM stability signals |
| Business Outcome | `(success×100 + partial×50) / scorable` transactions; penalized by CRITICAL/HIGH clusters |

### 6.2 Release Gate (RiskInterpreter + HealthGate)

`RiskInterpreter` evaluates in strict priority order:

```
1. smokePassRate < 90%                        → BLOCKED
2. criticalBugs > 0                           → BLOCKED
3. productHealthScore < 20 AND score < POOR   → BLOCKED  (product instability)
4. regressionPassRate < 100%                  → AT_RISK
5. score < 30 (POOR_MIN)                      → AT_RISK
6. score < 75 (HEALTHY_MIN)                   → WARNING
7. else                                        → READY
```

`HealthGate` enforces two hard gates at `@AfterSuite`:
- `smoothedScore >= BUILD_FAIL_THRESHOLD` (default 40, override: `-Dhealth.fail.score=N`)
- `!isCriticalBroken()` (uncaught JS exceptions or fatal API failures)

### 6.3 Composite Readiness Detection (ApplicationReadiness)

Four signals must all pass before any test interaction:

```
Signal 1: document.readyState === 'complete'
Signal 2: DOM mutation idle — no MutationObserver changes for 300 ms
Signal 3: Network idle — NetworkMonitor.getPendingCount() == 0
Signal 4: No visible loaders (spinner elements gone)
```

Default timeout: 15 seconds. All signals checked via JavaScript injection (no CDP dependency).

### 6.4 Network Monitoring (NetworkMonitor)

JavaScript-injected interceptors wrap `fetch()` and `XMLHttpRequest.open/send`:

```
injectInterceptors(driver) → injects guard + tracking arrays
getApiFailures(driver)     → returns [{url, status, method, type, error}]
getPendingCount(driver)    → in-flight request count
reset(driver)              → clears errors + pending count
```

Known gaps: WebSockets, SSE, service workers, cross-origin iframes.

### 6.5 Intelligent Retry (RetryClassifier)

Classifies every exception as TRANSIENT (retry) or STRUCTURAL (fail immediately):

| TRANSIENT — retry up to 3× | STRUCTURAL — fail immediately |
|---|---|
| StaleElementReferenceException | NoSuchElementException |
| ElementClickInterceptedException | AssertionError |
| ElementNotInteractableException | SessionNotCreatedException |
| Loader still animating | Required element missing |

Backoff: 400 ms between retries. `withRetry(int, long, RetryAction<T>)` API.

### 6.6 AI Intelligence Layer

Async enrichment pipeline triggered per failure in `@AfterMethod`:

```
AiEnrichmentExecutor.submit() ──async──▶ FailureAnalyzer
                                              │
                              ┌───────────────┼─────────────────┐
                              ▼               ▼                 ▼
                         DomReducer    StacktraceReducer   LogReducer
                              │               │                 │
                              └───────────────┴─────────────────┘
                                              │
                                         AiClient (rate-limited + retry)
                                              │
                                    AiResultWriter.write()
                                              │
                            reports/ai/{testId}_analysis.json
```

`ReleaseNarrator` generates a qualitative release advisory at suite end. Results cached via `AiCacheService`. Gracefully no-ops if API key not configured.

### 6.7 Failure Clustering & Correlation

```
Raw failures
    │
    ▼
FailureClusterService → FailureCluster[] (grouped by exception type + stack frame)
    │
    ▼
FailureCorrelationEngine → detects co-occurring failure patterns
    │
    ▼
CascadeFailureDetector → identifies root workflow causing downstream failures
    │
    ▼
WorkflowDependencyGraph (config/workflow-dependencies.json)
    │
    ▼
CorrelationSnapshotDto → embedded in dashboard + PDF report
```

### 6.8 Governance Layer

Prevents unsafe AI recommendations from reaching users:

```
AI recommendation
    │
    ▼
ConfidenceGuardrailService → blocks low-confidence decisions
    │
    ▼
RecommendationSafetyValidator → policy-based safety check
    │
    ▼
ExplainabilityEngine → attaches human-readable rationale
    │
    ▼
DecisionAuditLogger → immutable audit trail
    │
    ▼
GovernanceSnapshotDto → visible in dashboard governance panel
```

Human overrides tracked via `HumanOverrideRegistry`. Suppressions audited via `SuppressionAuditTracker`.

### 6.9 Historical Trend & Calibration

```
Per-suite run data
    │
    ▼
TrendComputationService → score history, regression detection
    │
    ├─▶ RegressionSpikeDetector → sustained degradation alerts
    ├─▶ OscillationDetector → flakiness pattern detection
    ├─▶ PerformanceTrendAnalyzer → slow-page regression tracking
    └─▶ ReliabilityTrendAnalyzer → locator health over time
         │
         ▼
    calibration/
         ├─ NoiseSuppressionService → filters ephemeral failures
         ├─ SignalTrustScorer → weights signals by historical reliability
         ├─ StabilityThresholdCalibrator → auto-tunes health thresholds
         └─ AlertCooldownManager → prevents alert fatigue
```

### 6.10 Orchestration Intelligence

Optimizes which tests run, in what order, with what retry strategy:

```
Historical data + current failures
    │
    ▼
ExecutionPriorityEngine → ranks tests by risk and value
    │
    ▼
CriticalPathSelector → identifies minimum test set for release confidence
    │
    ▼
RiskWeightedSuiteBuilder → generates optimized TestNG suites
    │
    ▼
IntelligentRetryManager → per-test retry budgets based on flakiness history
    │
    ▼
ResourceAllocationOptimizer → parallelism and timeout allocation
```

### 6.11 Video Recording (VideoRecorder)

Optional per-test MP4 replay via FFmpeg. Opt-in only — zero overhead when disabled.

```
BaseTest.setUp()
  └─ VideoRecorder.start(testId, driver)  ← ThreadLocal; no-op if -DrecordVideo not set
       │
       └─ ScheduledExecutor (scheduleWithFixedDelay 250ms)
            └─ driver.getScreenshotAs(OutputType.FILE) → temp PNG frame
                 (Chrome writes PNG; Java moves file — no image processing)

BaseTest.afterMethod()
  FAIL → recorder.stop(failures/{folder}, assemble=true)
           └─ executor.awaitTermination(3s)  ← driver-safe after this point
           └─ FFmpeg: -framerate 4 -c:v libx264 -pix_fmt yuv420p → recording.mp4
  PASS (default) → recorder.stop(null, false) — frames deleted immediately
  PASS (-DrecordPassedVideo=true) → stop(recordings/{folder}, true)
```

**Known limitations:** viewport-only capture (no OS dialogs); ~250ms/frame ChromeDriver round-trip; designed for local execution.

---

## 7. Test Classes

| Class | Type | Login | What It Tests | Steps/Iterations |
|---|---|---|---|---|
| [LoginTest](src/test/java/testing/LoginTest.java) | SMOKE/SANITY | No | Auth flow starts correctly | 1 |
| [SignupTest](src/test/java/testing/SignupTest.java) | SMOKE/SANITY | No | Signup flow starts correctly | 1 |
| [AppyPieNavigationTest](src/test/java/testing/AppyPieNavigationTest.java) | SANITY | No | Homepage · LCP/CLS · nav links · category pages | 7 methods |
| [HomepageExhaustiveTest](src/test/java/testing/HomepageExhaustiveTest.java) | SANITY | No | All `<a>` + `<button>` HTTP status (HEAD requests) | 1 (crawl) |
| [AppDirectoryTest](src/test/java/testing/AppDirectoryTest.java) | SANITY | No | Directory load · search · card content · lazy load | 7 methods |
| [ErrorHandlingTest](src/test/java/testing/ErrorHandlingTest.java) | REGRESSION | No | 404 · long URLs · special chars · XSS · recovery | 7 methods |
| [ResponsiveTest](src/test/java/testing/ResponsiveTest.java) | REGRESSION | No | Mobile/tablet/desktop viewports · menu · overflow | 10 methods |
| [AppPairingTest](src/test/java/testing/AppPairingTest.java) | REGRESSION | Handled | 6 app pairings: search→card→+→second app→automate→verify | 6 iterations |
| [TopAppCombinationsTest](src/test/java/testing/TopAppCombinationsTest.java) | REGRESSION | Handled | Top app combination workflow navigation | varies |
| [TrendingAppIntegrationsTest](src/test/java/testing/TrendingAppIntegrationsTest.java) | REGRESSION | Handled | Trending app integration landing flows | varies |
| [ExploreMenuIntegrationTest](src/test/java/testing/ExploreMenuIntegrationTest.java) | SANITY | No | Explore menu navigation and category flows | varies |
| [AuthenticatedTest](src/test/java/testing/AuthenticatedTest.java) | FULL | Required | E2E: login→dashboard→create connect→activate | 1 (multi-step) |
| [CreateConnectWorkflowTest](src/test/java/testing/CreateConnectWorkflowTest.java) | FULL | Programmatic | Google Sheets (New Row) → Gmail (Create Draft) → activate | 1 (17 steps) |
| [GoHighLevelMindbodyConnectTest](src/test/java/testing/GoHighLevelMindbodyConnectTest.java) | FULL | Programmatic | GoHighLevel V2 (New Opportunity) → Mindbody (Create Sale) → activate | 1 (17 steps) |

**Total: 14 test classes, ~55+ test methods**

---

## 8. AppPairingTest — Detailed Flow

The most complex iterative test; updated with structured result tracking and observability:

```
For each iteration (6 total: Zoho, Zoom, Slack, Trello, Google Sheets, Shopify):
  1. Navigate to App Directory
  2. ApplicationReadiness.waitForReady() [4-signal]
  3. NetworkMonitor.injectInterceptors()
  4. searchForApp() — MutationObserver-based DOM stability (300ms idle)
  5. findAndClickVerifiedAppCard() — virtual scroll (8 attempts), slug matching
  6. clickPlusIcon()
  7. clickVerifiedSecondAppCard()
  8. clickAutomateButton()
  9. URL check: if login redirect → handleLoginRedirect()
     else → scrollPageSmoothly() → result.succeed()
  10. captureAndRecordObservability():
       → JsConsoleMonitor.getSevereErrors()
       → NetworkMonitor.getApiFailures()
       → HealthTracker.recordJsError() / recordTestFailure()

Failure types tracked: APP_NOT_FOUND · SECOND_APP_NOT_FOUND · AUTOMATE_BUTTON_MISSING
                        LOGIN_FAILED · EDITOR_NOT_LOADED · EDITOR_BLOCKED_BY_OVERLAY

Final assertion: all 6 results must be success
```

---

## 9. Page Objects — Key Patterns

### ConnectEditorPage (most complex)

**General patterns:**
- **Mutation-observer dropdown stability**: Waits for Angular dropdown to stop re-rendering before clicking
- **Chip confirmation**: Verifies field mapping chips appear after selection
- **Label-scoped interaction**: XPath scoped to ancestor containers per field
- **Fail-fast Continue**: Checks `isEnabled()` + `disabled` attribute before clicking
- **Multi-strategy visibility**: Checks `f-canvas`, `#connectName`, `.canvas-container`, `app-custom-editor`

**`handleSetupStep(Map<String,String> fieldSearchTerms)` — targeted dropdown selection:**

Iterates all `div[id^='menu-drop']` elements. For each field:
- `choices-container` type → skipped (handled by `fillMindbodySaleSetup` / `fillGmailDraftSetup`)
- `menu_icon-box` type → checks `fieldSearchTerms` map; if matched, types the search term and selects; otherwise falls back to first available option

```java
editor.handleSetupStep(Map.of(
    "TRI__site_id", "Appy Pie",      // Location: search "Appy Pie" → select match
    "client_id",    "01Test 01",     // Client: search "01Test 01" → select match
    "LocationId",   "Appy Pie",      // Location ID: search "Appy Pie"
    "SendEmail",    "false",         // Email: search "false" → select "false"
    "product_id",   "Test1",         // Product: search "Test1" → select match
    "service_id",   "initial",       // Service: search "initial" → select match
    "p_type",       "Cash"           // Payment type: search "Cash" → select "Cash"
));
```

### Mindbody Create Sale Field-Fill Patterns

The Appy Pie Connect Angular UI uses three distinct DOM patterns for field input. Each requires a different interaction strategy:

| Field | DOM Pattern | Method | Interaction |
|---|---|---|---|
| `quantity` | `span.readmorebutton2 > div` trigger → choices panel → chip | `fillMindbodyCustomValue()` | Click `.readmorebutton2 > div` → click "Use a Custom Value" → type into `div.checkIfNotBlurAfteraWhile` → Enter |
| `amount` | `div[contenteditable='true']` direct | `fillMindbodyContentEditable()` | Click contenteditable div → clear via JS → `sendKeys(value)` → TAB to blur |
| `notes` | Plain `<input>` or `<textarea>` | `fillMindbodyDirectInput()` | Click input → `clear()` → `sendKeys(value)`; JS value-set fallback |

**`fillMindbodySaleSetup()` — auto-fill via Maven properties:**

```powershell
# Fully automated — no browser interaction
mvn clean test "-Dtest=GoHighLevelMindbodyConnectTest" "-Dquantity=1" "-Damount=1000" "-Dnotes=Automation Test"
```

When `-Dquantity` and `-Damount` are both provided, all three fields are filled automatically and the method returns immediately. If either property is absent, the manual fallback activates (polls DOM every 2s up to `-DmanualTimeout` seconds).

### ApplicationReadiness
- Injects `__qaLastMutation` timestamp via MutationObserver on `document.body`
- Polls until `Date.now() - __qaLastMutation > 300`
- Combined with `NetworkMonitor.getPendingCount() === 0` for true idle detection

### AuthState
- Checks localStorage keys: `authToken`, `accessToken`, `token`, `user_data`, `userInfo`
- Checks cookies: `PHPSESSID`, `session`, `auth`
- URL-based fallback: `/login`, `/signup`, `accounts.appypie.com`

---

## 10. Connect Workflow — End-to-End Flow

Both `CreateConnectWorkflowTest` and `GoHighLevelMindbodyConnectTest` follow the same 17-step page-transition pattern. Only the app/event constants and action-setup method differ.

```
Step  1: Login (programmatic → 3-min manual fallback)
Step  2: Wait for dashboard, dismiss overlays/user-guide
Step  3: Click "Create Connect" → verify editor visible
Step  4: selectTriggerApp(TRIGGER_APP)
Step  5: selectTriggerEvent(TRIGGER_EVENT)
Step  6: clickContinue() — data-track="continue with event"
Step  7: clickContinue() — data-track="continue with account"  [non-fatal]
Step  8: handleSetupStep() — trigger-app dropdowns             [non-fatal]
Step  9: clickContinueRunTest() — data-track="continue"
Step 9.5: clickContinue() — optional post-run continue        [non-fatal]
Step 10: clickAddNewStep() → clickAddApp()
Step 11: selectActionApp(ACTION_APP)
Step 12: selectActionEvent(ACTION_EVENT)
Step 13: clickContinue() — action event panel
Step 14: clickContinue() — action account panel
Step 15: handleSetupStep(Map) — action dropdowns with targeted search
Step 15.5: fillGmailDraftSetup()  ← Google Sheets→Gmail variant
           OR fillMindbodySaleSetup() ← GoHighLevel→Mindbody variant
Step 16: clickContinueRunTest()
Step 16.5: clickContinue() — optional post-action-run continue [non-fatal]
Step 17: clickActivateConnect()
```

**App/Event Constants:**

| Test | TRIGGER_APP | TRIGGER_EVENT | ACTION_APP | ACTION_EVENT |
|---|---|---|---|---|
| `CreateConnectWorkflowTest` | Google Sheets | New Spreadsheet Row | Gmail | Create Draft |
| `GoHighLevelMindbodyConnectTest` | GoHighLevel V2 | New Opportunity | Mindbody | Create Sale |

---

## 11. Configuration Files

| File | Purpose |
|---|---|
| [config/js_error_ignore.json](config/js_error_ignore.json) | 3rd-party JS error suppression patterns (analytics, hotjar, intercom, etc.) |
| [config/performance.baselines.json](config/performance.baselines.json) | Expected page load times (ms): Homepage 1000, AppDirectory 1500, ConnectEditor 2500 |
| [config/workflow-dependencies.json](config/workflow-dependencies.json) | Test dependency graph for correlation engine |
| [config/ai.properties](config/ai.properties) | AI client configuration (API key, model, rate limits) |
| [config/history.properties](config/history.properties) | Historical data retention and calibration settings |
| [config/orchestration.properties](config/orchestration.properties) | Execution planning and prioritization settings |

---

## 12. Test Suites

| Suite File | Groups | Purpose | Approx Time |
|---|---|---|---|
| `testng.xml` | all | Default; runs everything | ~12 min |
| `smoke-testng.xml` | smoke | Critical path only | < 90s |
| `sanity-testng.xml` | sanity | Core functionality | ~3 min |
| `regression-testng.xml` | regression | Deeper validation | ~6 min |
| `full-testng.xml` | full | Login-required E2E | ~20 min |

---

## 13. Execution Reference

```powershell
# Default regression
mvn test

# Specific suite
mvn test "-DsuiteFile=full-testng.xml"
mvn test "-DsuiteFile=sanity-testng.xml"
mvn test "-DsuiteFile=smoke-testng.xml"
mvn test "-DsuiteFile=regression-testng.xml"

# Single test class
mvn test "-Dtest=CreateConnectWorkflowTest"
mvn test "-Dtest=GoHighLevelMindbodyConnectTest"

# GoHighLevel → Mindbody (fully automated — no manual browser input)
mvn clean test "-Dtest=GoHighLevelMindbodyConnectTest" "-Dquantity=1" "-Damount=1000"
mvn clean test "-Dtest=GoHighLevelMindbodyConnectTest" "-Dquantity=1" "-Damount=1000" "-Dnotes=Automation Test"

# GoHighLevel → Mindbody (manual fallback — fill quantity+amount in browser)
mvn clean test "-Dtest=GoHighLevelMindbodyConnectTest" "-DmanualTimeout=600"

# Video recording (requires FFmpeg in PATH)
mvn clean test "-Dtest=GoHighLevelMindbodyConnectTest" "-Dquantity=1" "-Damount=1000" "-DrecordVideo=true"
mvn clean test "-Dtest=GoHighLevelMindbodyConnectTest" "-DrecordVideo=true" "-DrecordVideo.maxMinutes=15"
mvn clean test "-Dtest=GoHighLevelMindbodyConnectTest" "-DrecordPassedVideo=true"

# Headless (CI mode)
mvn test "-DsuiteFile=full-testng.xml" "-Dheadless=true"

# Override health gate threshold
mvn test "-Dhealth.fail.score=30"

# Custom target URL
mvn test "-Dbase.url=https://staging.example.com"
```

---

## 14. System Properties Reference

| Property | Default | Description |
|---|---|---|
| `suiteFile` | `testng.xml` | TestNG suite file to run |
| `headless` | `false` | Run Chrome headless (CI mode) |
| `health.fail.score` | `40` | Minimum smoothed score before build fails |
| `base.url` | production URL | Target application base URL |
| `quantity` | *(not set)* | Mindbody Create Sale: quantity value for auto-fill |
| `amount` | *(not set)* | Mindbody Create Sale: amount value for auto-fill |
| `notes` | `Automation Test` | Mindbody Create Sale: notes text for auto-fill |
| `manualTimeout` | `300` | Seconds to wait for manual field entry (fallback mode) |
| `recordVideo` | `false` | Enable per-test MP4 recording via FFmpeg |
| `recordVideo.maxMinutes` | `10` | Max recording duration before truncation |
| `recordPassedVideo` | `false` | Also record passing tests (default: FAIL only) |

---

## 15. Output Artifacts

```
reports/
├── trend/
│   ├── dashboard.html            ← Main interactive dashboard (open in browser)
│   ├── history.csv               ← Per-run: timestamp, score, status, pass/fail counts
│   ├── history.json              ← Per-run: full analytics JSON (55+ runs)
│   ├── health_snapshot.json      ← Latest layered score snapshot
│   ├── semantic_history.jsonl    ← Per-run semantic trend entries (JSONL)
│   ├── executive_report.pdf      ← Executive summary PDF
│   └── technical_report.pdf      ← Engineering detail PDF
├── analytics/
│   └── test_analytics.json       ← Full aggregated analytics for current run
├── failures/
│   └── {TestName}_{timestamp}/
│       ├── screenshot.png         ← Failure screenshot
│       ├── dom.html               ← Full DOM at failure time
│       ├── url.txt                ← Browser URL at failure time
│       ├── console.log            ← Browser console output
│       └── recording.mp4          ← Replay video (if -DrecordVideo=true + FFmpeg)
└── recordings/
    └── {TestName}_{timestamp}/
        └── recording.mp4          ← Passed-test video (if -DrecordPassedVideo=true)
```

---

## 16. Key Metrics & Thresholds

| Metric | Threshold | Source |
|---|---|---|
| Health Score gate | ≥ 40 (default) | `HealthGate` / `-Dhealth.fail.score` |
| Smoke pass rate | ≥ 90% | `RiskInterpreter` |
| Regression pass rate | 100% | `RiskInterpreter` |
| Product Health BLOCKED | < 20 AND overall < 30 | `RiskInterpreter` (dual-gate) |
| LCP (Largest Contentful Paint) | ≤ 2500 ms | `AppyPieNavigationTest` |
| CLS (Cumulative Layout Shift) | ≤ 0.1 | `AppyPieNavigationTest` |
| Navigation time | ≤ 5000 ms | `AppyPieAutomatePage` |
| DOM mutation idle | 300 ms stable | `ApplicationReadiness` |
| Network idle | 0 pending requests | `NetworkMonitor` |
| Manual login fallback | 3 min timeout | `ManualLoginHelper` |
| Manual field entry fallback | 300 s (default) | `fillMindbodySaleSetup` / `-DmanualTimeout` |
| AI enrichment (async) | Fire-and-forget | `AiEnrichmentExecutor` |
| Retry max attempts | 3 | `RetryClassifier` |
| Retry backoff | 400 ms | `RetryClassifier` |
| Virtual scroll attempts | 8 | `AppPairingTest` |
| Pairing iterations | 6 | `AppPairingTest` |
| Video max FPS | 4 | `VideoRecorder` (scheduleWithFixedDelay 250ms) |
| Video frame max | `maxMinutes × 60 × 4` | `VideoRecorder` / `-DrecordVideo.maxMinutes` |
| FFmpeg assembly timeout | 120 s | `VideoRecorder` |

---

## 17. Design Decisions

| Decision | Rationale |
|---|---|
| Singleton analytics trackers | Thread-safe aggregation across test methods without passing state |
| `@TestCategory` annotation | Decouples execution filtering (TestNG groups) from metadata (owner, severity, feature) |
| Penalty-based health scoring | Non-binary: captures gradual quality degradation |
| EMA smoothing (α=0.3) | Prevents single flaky test from tanking score; favors historical stability |
| Flow multipliers | Login/smoke failures have 2× impact — higher production risk |
| 4-signal readiness model | Eliminates `Thread.sleep()` races; handles Angular/React async rendering |
| JS injection over CDP | Version-agnostic; works with any Chrome version without protocol version lock |
| TRANSIENT-only retries | Prevents masking structural failures; only retries recoverable exceptions |
| Async AI enrichment | Enrichment never blocks test execution; gracefully skipped if API unavailable |
| Static shared WebDriver | Single browser per suite; avoids repeated startup overhead |
| Failure artifacts on disk | Screenshot + DOM + console in one folder; no log.txt bloat |
| Self-contained HTML dashboard | No server required; embeds Chart.js and filters inline |
| Owner enforcement (FULL tests) | Governance: prevents anonymous tests reaching highest risk tier |
| Governance layer for AI | AI recommendations are advisory only; humans retain release authority |
| Three Mindbody field patterns | Angular UI exposes readmorebutton2 (choices), contenteditable (direct), and plain input — each needs a different interaction path; unified under `fillMindbodySaleSetup()` |
| Maven -D properties for field values | Eliminates manual browser interaction; enables CI automation for field-dependent tests |
| Dual-gate product health | `productHealth < 20 AND score < POOR` required for BLOCKED — prevents false positives when only domain signal degrades |
| `scheduleWithFixedDelay` for video | Prevents frame-capture tasks from stacking under load; effective FPS degrades gracefully instead of crashing |

---

## 18. Extension Points

| Area | How to Extend |
|---|---|
| Add a new page | Create `pages/NewPage.java`; use `WaitUtils` + `ApplicationReadiness` |
| Add a new test | Extend `BaseTest`, add `@TestCategory` + TestNG group + owner |
| Add a new Connect workflow | Extend `CreateConnectWorkflowTest` pattern; add app constants; implement field-fill method in `ConnectEditorPage` |
| Add a Mindbody-style field | Add field name to `handleSetupStep()` Map or implement `fillVia*` helper matching field DOM pattern |
| Add a new suite | Create `new-suite-testng.xml`; run via `-DsuiteFile=new-suite-testng.xml` |
| Suppress a JS error | Add pattern to `config/js_error_ignore.json` |
| Update a perf baseline | Edit value in `config/performance.baselines.json` |
| Declare a test dependency | Add entry to `config/workflow-dependencies.json` |
| Adjust gate threshold | `-Dhealth.fail.score=N` or update default in `HealthPolicy` |
| Add a new penalty type | Method in `HealthPolicy` → call from `HealthTracker` → surface in `AnalyticsCollector` |
| Enable AI analysis | Set API key in `config/ai.properties` |
| Enable video recording | Pass `-DrecordVideo=true`; ensure FFmpeg is in PATH |
| Override login credentials | Set `AUTOMATION_LOGIN_USER` / `AUTOMATION_LOGIN_PASS` env vars |
