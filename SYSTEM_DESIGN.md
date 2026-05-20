# System Design — AppyPie Automate QA Framework

**Project:** `com.appypie.testautomation:appypie-automation`
**Target Application:** https://www.appypieautomate.ai
**Dashboard (Auth):** https://connectcloud.appypie.com/connects
**Editor (Auth):** https://connectcloud.appypie.com/customeditor
**Last Updated:** 2026-05-18

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
│  CreateConnectWorkflowTest · AppDirectoryTest · ErrorHandlingTest    │
│  HomepageExhaustiveTest · AppyPieNavigationTest · ResponsiveTest     │
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
 │  analytics/test_analytics.json│
 │  pdf/report-*.pdf            │
 │  failures/{test}_{ts}/       │
 │  dom_dumps/dom_*.html        │
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
| PDF Generation | OpenPDF (com.github.librepdf) | 1.3.43 |
| JSON | json-simple | 1.1.1 |
| Chart Rendering | XChart | 3.8.8 |
| Logging | SLF4J Simple | 2.0.9 |
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
│   ├── AppPairingTest.java        # REGRESSION: 6-iteration app pairing with full login cycle
│   ├── AppDirectoryTest.java      # SANITY: directory load, search, card content
│   ├── AppyPieNavigationTest.java # SANITY: homepage, nav links, perf (LCP/CLS), category pages
│   ├── AuthenticatedTest.java     # FULL: E2E journey login→dashboard→connect→activate
│   ├── CreateConnectWorkflowTest.java # FULL: Google Sheets→Gmail Draft with programmatic login
│   ├── ErrorHandlingTest.java     # REGRESSION: 404, special chars, XSS, recovery
│   ├── HomepageExhaustiveTest.java # SANITY: all links/buttons HTTP status check
│   ├── LoginTest.java             # SMOKE/SANITY: auth flow start
│   ├── ResponsiveTest.java        # REGRESSION: 10 viewport tests mobile→desktop
│   └── SignupTest.java            # SMOKE/SANITY: signup flow start
│
└── utils/
    ├── ApplicationReadiness.java  # 4-signal composite readiness (ready+DOM+network+loader)
    ├── ConsoleLogFilter.java      # Browser SEVERE log capture, filter, route to HealthTracker
    ├── CustomHtmlReporter.java    # TestNG HTML report customization
    ├── DashboardBuilder.java      # Generates reports/trend/dashboard.html
    ├── DashboardLauncher.java     # Auto-opens dashboard in browser post-suite
    ├── FailureArtifactManager.java # screenshot + DOM + URL + console on failure
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
    │
    ├── analytics/
    │   ├── AnalyticsCollector.java    # Unified JSON aggregator for all trackers
    │   ├── JsErrorTracker.java        # Deduplicates + categorizes JS errors
    │   ├── LocatorTracker.java        # Per-feature primary/fallback locator rates
    │   ├── PerformanceTracker.java    # Page load vs baselines
    │   └── TestAnalyticsLogger.java   # Per-test lifecycle: started/passed/failed/skipped
    │
    ├── health/
    │   ├── HealthTracker.java     # Singleton penalty aggregator + score + EMA smoothing
    │   └── HealthGate.java        # Suite gate: Assert.fail if score < threshold
    │
    ├── policy/
    │   └── SeverityClassifier.java
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
    └── report/
        ├── PdfReportBuilder.java  # iText/OpenPDF PDF generation (Executive + Engineering modes)
        └── ReportDto.java         # Report data transfer object
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
  └─ TestAnalyticsLogger.testStarted()

@AfterMethod (per test)
  ├─ FAILURE:
  │    ├─ isDriverHealthy() → FailureArtifactManager.capture()
  │    ├─ TestAnalyticsLogger.testFailed()
  │    ├─ HealthTracker.recordTestFailure()
  │    └─ HealthTracker.addTestRecord(... "FAIL" ...)
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

### 6.2 Release Gate (RiskInterpreter + HealthGate)

`RiskInterpreter` evaluates in strict priority order:

```
1. smokePassRate < 90%          → BLOCKED
2. criticalBugs > 0             → BLOCKED
3. regressionPassRate < 100%    → AT_RISK
4. score < 30 (POOR_MIN)        → AT_RISK
5. score < 75 (HEALTHY_MIN)     → WARNING
6. else                         → READY
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

---

## 7. Test Classes — What Each Tests

| Class | Type | Login | What It Tests | Iterations |
|---|---|---|---|---|
| [LoginTest](src/test/java/testing/LoginTest.java) | SMOKE/SANITY | No | Auth flow starts correctly | 1 |
| [SignupTest](src/test/java/testing/SignupTest.java) | SMOKE/SANITY | No | Signup flow starts correctly | 1 |
| [AppyPieNavigationTest](src/test/java/testing/AppyPieNavigationTest.java) | SANITY | No | Homepage load · LCP/CLS · nav links · category pages | 7 methods |
| [HomepageExhaustiveTest](src/test/java/testing/HomepageExhaustiveTest.java) | SANITY | No | All `<a>` and `<button>` HTTP status (HEAD requests) | 1 (crawl) |
| [AppDirectoryTest](src/test/java/testing/AppDirectoryTest.java) | SANITY | No | Directory load · search · card content · lazy load | 7 methods |
| [ErrorHandlingTest](src/test/java/testing/ErrorHandlingTest.java) | REGRESSION | No | 404 · long URLs · special chars · XSS · recovery nav | 7 methods |
| [ResponsiveTest](src/test/java/testing/ResponsiveTest.java) | REGRESSION | No | Mobile/tablet/desktop viewports · menu · image overflow | 10 methods |
| [AppPairingTest](src/test/java/testing/AppPairingTest.java) | REGRESSION | Handled | 6 app pairings: search→card→+→second app→automate→editor verify | 6 iterations |
| [AuthenticatedTest](src/test/java/testing/AuthenticatedTest.java) | FULL | Required | E2E: login→dashboard→create connect→trigger→action→activate | 1 (multi-step) |
| [CreateConnectWorkflowTest](src/test/java/testing/CreateConnectWorkflowTest.java) | FULL | Programmatic | Google Sheets (New Row) → Gmail (Create Draft) → activate | 1 (17 steps) |

**Total: 10 test classes, ~41 test methods**

---

## 8. AppPairingTest — Detailed Flow

The most complex test; updated with structured result tracking and observability:

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
- **Mutation-observer dropdown stability**: Waits for Angular dropdown to stop re-rendering before clicking
- **Chip confirmation**: Verifies field mapping chips appear after selection
- **Label-scoped interaction**: XPath scoped to `ancestor::div[contains(@class,'form-check')]` per field
- **Fail-fast Continue**: Checks `isEnabled()` + `disabled` attribute before clicking; throws if incomplete form
- **Multi-strategy visibility**: Checks `f-canvas` (Angular), `#connectName`, `.canvas-container`, `app-custom-editor`

### ApplicationReadiness
- Injects `__qaLastMutation` timestamp via MutationObserver on `document.body`
- Polls until `Date.now() - __qaLastMutation > 300`
- Combined with `NetworkMonitor.getPendingCount() === 0` for true idle detection

### AuthState
- Checks localStorage keys: `authToken`, `accessToken`, `token`, `user_data`, `userInfo`
- Checks cookies: `PHPSESSID`, `session`, `auth`
- URL-based fallback: `/login`, `/signup`, `accounts.appypie.com`

---

## 10. Configuration Files

| File | Purpose |
|---|---|
| [config/js_error_ignore.json](config/js_error_ignore.json) | 3rd-party JS error suppression patterns (analytics, hotjar, intercom, etc.) |
| [config/performance.baselines.json](config/performance.baselines.json) | Expected page load times (ms): Homepage 1000, AppDirectory 1500, ConnectEditor 2500 |
| [config/workflow-dependencies.json](config/workflow-dependencies.json) | Test dependency graph for correlation engine |
| [config/ai.properties](config/ai.properties) | AI client configuration (API key, model, rate limits) |
| [config/history.properties](config/history.properties) | Historical data retention and calibration settings |
| [config/orchestration.properties](config/orchestration.properties) | Execution planning and prioritization settings |

---

## 11. Test Suites

| Suite File | Groups | Purpose | Approx Time |
|---|---|---|---|
| `testng.xml` | all | Default; runs everything | ~10 min |
| `smoke-testng.xml` | smoke | Critical path only | < 90s |
| `sanity-testng.xml` | sanity | Core functionality | ~3 min |
| `regression-testng.xml` | regression | Deeper validation | ~6 min |
| `full-testng.xml` | full | Login-required E2E | ~15 min |

---

## 12. Execution Reference

```powershell
# Default regression
mvn test

# Specific suite (quote for PowerShell)
mvn test "-DsuiteFile=full-testng.xml"
mvn test "-DsuiteFile=sanity-testng.xml"
mvn test "-DsuiteFile=smoke-testng.xml"
mvn test "-DsuiteFile=regression-testng.xml"

# Single test class
mvn test "-Dtest=CreateConnectWorkflowTest"

# Headless (CI mode)
mvn test "-DsuiteFile=full-testng.xml" "-Dheadless=true"

# Override health gate threshold
mvn test "-Dhealth.fail.score=30"

# Custom target URL
mvn test "-Dbase.url=https://staging.example.com"
```

---

## 13. Output Artifacts

```
reports/
├── trend/
│   ├── dashboard.html           ← Main interactive dashboard (open in browser)
│   ├── history.csv              ← Per-run: timestamp, score, status, pass/fail counts
│   ├── history.json             ← Per-run: full analytics JSON
│   └── health_snapshot.json     ← Latest score snapshot
├── analytics/
│   └── test_analytics.json      ← Full aggregated analytics for current run
├── pdf/
│   └── report-{ts}-{mode}.pdf   ← PDF: executive (2-4pp) or engineering (full)
├── dom_dumps/
│   └── dom_{label}_{ts}.html    ← On-demand DOM snapshots (AuthenticatedTest etc.)
└── failures/
    └── {TestName}_{timestamp}/
        ├── screenshot.png
        ├── dom.html
        ├── url.txt
        └── console.log
```

---

## 14. Key Metrics & Thresholds

| Metric | Threshold | Source |
|---|---|---|
| Health Score gate | ≥ 40 (default) | `HealthGate` / `-Dhealth.fail.score` |
| Smoke pass rate | ≥ 90% | `RiskInterpreter` |
| Regression pass rate | 100% | `RiskInterpreter` |
| LCP (Largest Contentful Paint) | ≤ 2500 ms | `AppyPieNavigationTest` |
| CLS (Cumulative Layout Shift) | ≤ 0.1 | `AppyPieNavigationTest` |
| Navigation time | ≤ 5000 ms | `AppyPieAutomatePage` |
| DOM mutation idle | 300 ms stable | `ApplicationReadiness` |
| Network idle | 0 pending requests | `NetworkMonitor` |
| Manual login fallback | 3 min timeout | `ManualLoginHelper` |
| AI enrichment (async) | Fire-and-forget | `AiEnrichmentExecutor` |
| Retry max attempts | 3 | `RetryClassifier` |
| Retry backoff | 400 ms | `RetryClassifier` |
| Virtual scroll attempts | 8 | `AppPairingTest` |
| Pairing iterations | 6 | `AppPairingTest` |

---

## 15. Design Decisions

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

---

## 16. Extension Points

| Area | How to Extend |
|---|---|
| Add a new page | Create `pages/NewPage.java`; use `WaitUtils` + `ApplicationReadiness` |
| Add a new test | Extend `BaseTest`, add `@TestCategory` + TestNG group + owner |
| Add a new suite | Create `new-suite-testng.xml`; run via `-DsuiteFile=new-suite-testng.xml` |
| Suppress a JS error | Add pattern to `config/js_error_ignore.json` |
| Update a perf baseline | Edit value in `config/performance.baselines.json` |
| Declare a test dependency | Add entry to `config/workflow-dependencies.json` |
| Adjust gate threshold | `-Dhealth.fail.score=N` or update default in `HealthPolicy` |
| Add a new penalty type | Method in `HealthPolicy` → call from `HealthTracker` → surface in `AnalyticsCollector` |
| Enable AI analysis | Set API key in `config/ai.properties` |
| Override login credentials | Set `AUTOMATION_LOGIN_USER` / `AUTOMATION_LOGIN_PASS` env vars |
