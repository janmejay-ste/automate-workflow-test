# System Design — AppyPie Automate QA Framework

**Project:** `com.appypie.testautomation:appypie-automation`
**Target Application:** https://www.appypieautomate.ai
**Dashboard (Auth):** https://connectcloud.appypie.com/connects
**Editor (Auth):** https://connectcloud.appypie.com/customeditor
**Last Updated:** 2026-06-05

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

```text
┌──────────────────────────────────────────────────────────────────────┐
│                      Maven Build (mvn test)                          │
└─────────────────────────────┬────────────────────────────────────────┘
                              │ -DsuiteFile=<suite>.xml
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       TestNG Suite Runner                            │
│  testng.xml / sanity-testng.xml / smoke-testng.xml / e2e-testng.xml │
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
 │ DataQualityComp │
 │ PlatformHealth  │
 │   Component     │
 └────────┬────────┘

 ┌──────────────────────────────────────────────────────────────┐
 │               Governance & Budget (NEW)                      │
 │  ComplexityBudget  CoverageCatalog  UrlRegistry  BrandText   │
 │  ContributorBucket  ReleaseDecision  DecisionFactor          │
 │  SnapshotSchemaVersion  SchemaMigrator  TrendStatsCalculator │
 └──────────────────────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────────────────────┐
 │            Marketing Pages (NEW — flozic.ai rebrand)         │
 │  pages/marketing/  HomePage  PricingPage                     │
 │  testing/marketing/  testing/RebrandCompletionTest           │
 └──────────────────────────────────────────────────────────────┘
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

```text
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
│   ├── marketing/
│   │   ├── HomePage.java          # flozic.ai marketing homepage POM; hero CTA + email modal
│   │   └── PricingPage.java       # flozic.ai pricing page POM; plan cards + billing toggle
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
│   ├── HomepageExhaustiveTest.java       # SANITY: all links/buttons via UrlValidationRunner
│   ├── LoginTest.java                    # SMOKE/SANITY: auth flow start
│   ├── RebrandCompletionTest.java        # NIGHTLY: appypieautomate.ai → flozic.ai redirect probe
│   ├── ResponsiveTest.java               # REGRESSION: 10 viewport tests
│   ├── SignupTest.java                   # SMOKE/SANITY: signup flow start
│   ├── TopAppCombinationsTest.java       # REGRESSION: top app combination workflows
│   ├── TrendingAppIntegrationsTest.java  # REGRESSION: trending app integration flows
│   ├── dashboard/
│   │   ├── DashboardPanelInteractionTest.java  # Phase D1/D2: browser-level panel behavior
│   │   ├── ScoreDriversRowFixtureTest.java     # Phase D1: fixture-based triage box HTML verify
│   │   └── TrendStatsBlockFixtureTest.java     # Phase D3: fixture-based trend stats surface verify
│   ├── health/                           # Phase A/B health subsystem tests (TBD)
│   ├── marketing/                        # flozic.ai marketing page tests
│   └── semantic/                         # Semantic health subsystem tests
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
    ├── config/
    │   ├── UrlRegistry.java           # Centralised URL constants; handles rebrand (flozic.ai)
    │   │                              #   MARKETING_BASE: -Dmarketing.baseUrl (default flozic.ai)
    │   │                              #   isOwnedMarketingHost() — lenient (old + new domain)
    │   │                              #   isCurrentMarketingHost() — strict (flozic.ai only)
    │   └── BrandText.java             # Brand-name constants: PRODUCT_NAME / PRODUCT_NAME_LEGACY
    │
    ├── coverage/
    │   └── CoverageCatalog.java       # Reads coverage-catalog.json; declares business outcomes
    │                                  #   OutcomeSpec: id, owner, severity, verifier, test
    │                                  #   findById(), findByTest(), severityWeight()
    │                                  #   CRITICAL=3.0, HIGH=2.0, MEDIUM=1.0, LOW=0.5
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
    │   │                              #   Phase A1: per-source ContributorBucket tracking
    │   │                              #   Phase A.5.3: snapshot write error counters + prev score source
    │   ├── HealthGate.java            # Suite gate (advisory/blocking/disabled modes)
    │   │                              #   -DhealthGate.mode=advisory|blocking (default: advisory)
    │   │                              #   -DhealthGate.disabled=true / -DhealthSnapshot.disabled=true
    │   ├── ContributorBucket.java     # Per-source raw vs applied penalty tracker (Phase A1)
    │   │                              #   Tracks suppressed penalty + cap reason per bucket
    │   │                              #   toJson() → {source, rawTotal, appliedTotal, items[]}
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
    │   ├── cluster/
    │   │   ├── ClusterKey.java              # Immutable grouping key (fingerprint/hash)
    │   │   ├── Cluster.java                 # Error group: id, fingerprint, severity, items
    │   │   ├── ClusterScoringEngine.java    # Penalty from cluster (caps per classification)
    │   │   ├── FingerprintExtractor.java    # Interface for computing error fingerprints
    │   │   ├── JsErrorFingerprintExtractor.java   # Context + message fingerprint
    │   │   ├── TestFailureFingerprintExtractor.java
    │   │   └── FallbackFingerprintExtractor.java
    │   ├── schema/
    │   │   ├── SnapshotSchemaVersion.java   # Enum: UNKNOWN/V1_LEGACY/V2_AGGREGATE/V3_CONTRIBUTOR_RICH
    │   │   │                                #   c4Replayable(): V3+ only
    │   │   └── SchemaMigrator.java          # Sealed interface; IdentityMigratorV3, LossyV2ToV3Migrator
    │   └── trend/
    │       └── SemanticTrendWriter.java     # Appends to semantic_history.jsonl
    │
    ├── policy/
    │   └── SeverityClassifier.java
    │
    ├── dashboard/
    │   └── components/
    │       ├── SemanticPanelComponent.java  # Renders layered scores + error clusters section
    │       ├── UrlValidationComponent.java  # Renders URL validation findings section
    │       ├── DataQualityComponent.java    # Phase D2: Integrity + Evidence + Amplification + Trend
    │       └── PlatformHealthComponent.java # Phase D1: Enforcement + Pipeline + Governance + Schema
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
    │   ├── ComplexityBudget.java           # Reads complexity_budget.json; enforces scoring bounds
    │   │                                   #   BudgetEntry: maximum, current, currentValues
    │   │                                   #   EnumTable: maximum, finalSet, values (isProtected)
    │   │                                   #   GovernanceBudget: PR approvals, review days, overrides
    │   │                                   #   checkViolations(observedCounts) → Violation[]
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
    │   │           TrendStatsCalculator   ← Phase C2: mean/stdDev/z-score + outlier flag
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
    ├── release/
    │   ├── DecisionFactor.java    # Single blocker or warning: code, value, threshold
    │   └── ReleaseDecision.java   # Structured decision: status, blockers[], warnings[], wouldShip
    │                              #   Status escalation: any blocker→BLOCKED; else AT_RISK/WARNING/READY
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

```text
@BeforeSuite
  └─ silenceJavaUtilLogging()

@BeforeMethod (per test)
  ├─ createDriver() if null
  │    └─ ChromeOptions: --no-sandbox, --disable-dev-shm-usage
  │       headless mode: System.getProperty("headless", "false")
  │       LoggingPreferences: BROWSER=ALL (for JS error capture)
  │       DRIVER_HOLDER ThreadLocal — each thread owns one WebDriver (no shared static state)
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

```text
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

**Phase A4 — Structured `ReleaseDecision`:**

`RiskInterpreter.decide(DecisionInputs)` returns a `ReleaseDecision` record instead of a bare status enum. Each blocker and warning is a `DecisionFactor(code, value, threshold)` — no human-readable strings in the model, so the dashboard explains the decision via factor codes.

Blocker rules (strict priority):

```text
INSUFFICIENT_DATA → < 3 tests executed
SMOKE_FAIL        → smokePassRate < 90%
CRITICAL_BUGS_OPEN → criticalBugs > 0
PRODUCT_HEALTH_CRITICAL → productHealthScore < 20 AND score < POOR
CRITICAL_CLUSTER_LIMIT  → criticalClusterCount > allowed maximum
```

Warning rules:

```text
REGRESSION_INCOMPLETE → regressionPassRate < 100%
LOW_SCORE             → score < POOR_MIN (30)
BELOW_HEALTHY         → score < HEALTHY_MIN (75)
LOW_CONFIDENCE        → evidence confidence below threshold
```

`ReleaseDecision.wouldShip`: `true` only when status is READY and no blockers.

Legacy `interpret()` methods still available for backward compatibility.

**Phase A.5 — HealthGate Modes:**

Three enforcement modes controlled via system properties:

| Mode | System Property | Behavior |
|---|---|---|
| `advisory` | `-DhealthGate.mode=advisory` (default) | Logs result; never fails build |
| `blocking` | `-DhealthGate.mode=blocking` | Fails build when score < threshold |
| `disabled` | `-DhealthGate.disabled=true` | Complete no-op; no-op for snapshot too |

Additional kill switches:

- `-DhealthSnapshot.disabled=true` — snapshot not written
- `-DhealthTracker.disabled=true` — tracker becomes no-op

Unrecognized mode values are logged and recorded in `HealthGate.lastInvalidModeInput()`.

Hard gates when mode is `blocking`:

- `smoothedScore >= BUILD_FAIL_THRESHOLD` (default 40, override: `-Dhealth.fail.score=N`)
- `!isCriticalBroken()` (uncaught JS exceptions or fatal API failures)

### 6.3 Composite Readiness Detection (ApplicationReadiness)

Four signals must all pass before any test interaction:

```text
Signal 1: document.readyState === 'complete'
Signal 2: DOM mutation idle — no MutationObserver changes for 300 ms
Signal 3: Network idle — NetworkMonitor.getPendingCount() == 0
Signal 4: No visible loaders (spinner elements gone)
```

Default timeout: 15 seconds. All signals checked via JavaScript injection (no CDP dependency).

### 6.4 Network Monitoring (NetworkMonitor)

JavaScript-injected interceptors wrap `fetch()` and `XMLHttpRequest.open/send`:

```text
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

```text
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

```text
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

```text
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

```text
Per-suite run data
    │
    ▼
TrendComputationService → score history, regression detection
    │
    ├─▶ RegressionSpikeDetector → sustained degradation alerts
    ├─▶ OscillationDetector → flakiness pattern detection
    ├─▶ PerformanceTrendAnalyzer → slow-page regression tracking
    ├─▶ ReliabilityTrendAnalyzer → locator health over time
    └─▶ TrendStatsCalculator (Phase C2) — enabled: -DtrendStats.enabled=true
             └─ compute(priorScores, currentScore) → TrendStats
                  ├─ window: DEFAULT_WINDOW=30 runs
                  ├─ mean + Bessel-corrected stdDev (n-1)
                  ├─ z-score: (current - mean) / stdDev
                  └─ outlier flag: |z| ≥ 2.0 AND sampleSize ≥ 5
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

```text
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

```text
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

### 6.12 Health Contributor Buckets & Schema Versioning (Phase A1 / B4)

**ContributorBucket** — per-source penalty explainability:

```text
HealthTracker (per run)
  ├─ jsErrors      ContributorBucket  ← raw vs applied, cap reason
  ├─ testFailures  ContributorBucket
  ├─ fallbacks     ContributorBucket
  ├─ slowPages     ContributorBucket
  └─ warnings      ContributorBucket
```

Each bucket tracks raw total, applied total, suppressed amount, cap reason (e.g. `TOTAL_JS_PENALTY_CAP`), and per-item records. `toJson()` emits the full breakdown for the dashboard triage box.

**SnapshotSchemaVersion** — forward-compatible versioning:

| Version | Code | C4-Replayable | Notes |
|---|---|---|---|
| `V1_LEGACY` | 1 | No | Pre-versioning; no scoreContributors |
| `V2_AGGREGATE` | 2 | No | Has runs[], aggregate data |
| `V3_CONTRIBUTOR_RICH` | 3 | Yes | Has scoreContributors.items[] |
| `UNKNOWN` | -1 | No | Unrecognised code (forward-compat) |

**SchemaMigrator** (sealed interface): `IdentityMigratorV3` (no-op on V3), `LossyV2ToV3Migrator` (stamps `migrationLossy: true` on JSON). Migrations never throw — failures return as `MigrationOutcome`.

**ClusterScoringEngine** applies per-classification caps:

| Classification | Description |
|---|---|
| `RETRY_STORM` | Burst of identical retryable errors — capped penalty |
| `BOOT_LOOP` | Repeated initialisation failures |
| `EMBEDDED_REPEAT` | Same error within one test run |
| `NORMAL_REPEAT` | Repeated across tests, normal weight |

### 6.13 Governance Budget & Coverage Catalog (Phase A.5.0 / B1)

**ComplexityBudget** (reads `config/complexity_budget.json`):

- Enforces bounds on the scoring system's own complexity — prevents unbounded enum/channel growth.
- `checkViolations(observedCounts)` returns `Violation[]` with code, observed, maximum, severity.
- `GovernanceBudget` inner type tracks PR approval steps, enum review days, quarterly override budget, and `frictionScore()`.
- Read-only singleton; any budget change goes through a PR so it's reviewable.

**CoverageCatalog** (reads `config/coverage-catalog.json`):

- Declares every business outcome the harness claims to verify.
- `OutcomeSpec`: `id`, `name`, `owner`, `severity` (CRITICAL/HIGH/MEDIUM/LOW), `verifier`, `test`, `businessQuestion`.
- `severityWeight()`: CRITICAL=3.0, HIGH=2.0, MEDIUM=1.0, LOW=0.5 — used in release scoring.

### 6.14 Marketing Pages & Rebrand (Phase P1/P2)

The `appypieautomate.ai → flozic.ai` domain rebrand introduced a config-driven URL layer:

```text
UrlRegistry (utils/config)
  MARKETING_BASE = "https://www.flozic.ai"  ← -Dmarketing.baseUrl override
  AUTH_BASE      = "https://accounts.appypie.com"  (unchanged)
  CONNECT_BASE   = "https://connectcloud.appypie.com" (unchanged)

  isOwnedMarketingHost(url)   → accepts both old + new domain (30-day transition window)
  isCurrentMarketingHost(url) → strict flozic.ai check (used by RebrandCompletionTest)
  integratePath(slug)         → builds /integrate/{slug} paths
```

**Marketing page objects** (`pages/marketing/`):

- `HomePage` — hero CTA, email modal (#myModal), header/footer component accessors; 20 s load timeout.
- `PricingPage` — plan cards, billing toggle, FAQ section; `buyCtaCount()`, `enterpriseContactCount()`.

**`RebrandCompletionTest`** (nightly): proves the redirect chain is intact.

- `legacyDomainRedirectsToCurrentMarketingHost()` — asserts 301 chain from old domain.
- `currentMarketingHostLoadsDirectly()` — asserts no bounce on flozic.ai.

### 6.15 Data Quality & Platform Health Dashboard Panels (Phase D1/D2)

Two new collapsible panels added to `dashboard.html`:

**`DataQualityComponent`** (open by default) — "can I trust this run's numbers?":

| Sub-panel | Content |
|---|---|
| Integrity | status (PASS/DEGRADED/FAIL), checksRun, smoothing source |
| Evidence channels | registered vs expected; per-channel quality (STRONG/PARTIAL/DEGRADED/MISSING) |
| Amplification | cluster counts by classification (RETRY_STORM, BOOT_LOOP, EMBEDDED_REPEAT, NORMAL_REPEAT) |
| Trend statistics | sampleSize, mean, z-score, outlier flag (Phase C2) |

**`PlatformHealthComponent`** (collapsed by default — second-screen concern) — "can the infrastructure execute safely?":

| Sub-panel | Content |
|---|---|
| Enforcement | mode (advisory/blocking), wouldBlock, violations list |
| Snapshot pipeline | write errors, lastWriteError, previousScoreSource (NO_DATA/CSV/MALFORMED/OK) |
| Governance | ComplexityBudget loaded, CoverageCatalog loaded, gate-mode valid |
| Schema evolution | DEFERRED stub (Phase B4 full implementation pending) |

Color convention across both panels: green (#10b981) = healthy, amber (#f59e0b) = warning, red (#dc2626) = problem. Both render `""` gracefully when snapshot is missing.

**Fixture-based panel tests** (no browser, < 5 s runtime):

- `ScoreDriversRowFixtureTest` — backup/inject/restore snapshot; verifies triage box HTML and bucket reconciliation (Σ per-bucket penalties == total penalty).
- `TrendStatsBlockFixtureTest` — verifies outlier badge, "within range" label, and suppression when sample < `MIN_SAMPLE_FOR_OUTLIER` (5).

### 6.16 Origin-Aware JS Console Classification

`JsConsoleMonitor` now classifies errors by origin before routing to `HealthTracker`:

**First-party domains:** `appypieautomate.ai`, `flozic.ai`, `appypie.com`, `connectcloud.appypie.com`, `accounts.appypie.com`

| Severity | Conditions |
|---|---|
| FAIL | Uncaught exceptions, TypeErrors from first-party origins |
| WARN | Failed fetches, non-critical console errors |
| IGNORE | Favicon 404, analytics (Zaraz/Cloudflare, fedcm), Intercom, Hotjar, swiper |

---

## 7. Test Classes

| Class | Type | Login | What It Tests | Steps/Iterations |
|---|---|---|---|---|
| [LoginTest](src/test/java/testing/LoginTest.java) | SMOKE/SANITY | No | Auth flow starts correctly | 1 |
| [SignupTest](src/test/java/testing/SignupTest.java) | SMOKE/SANITY | No | Signup flow starts correctly | 1 |
| [AppyPieNavigationTest](src/test/java/testing/AppyPieNavigationTest.java) | SANITY | No | Homepage · LCP/CLS · nav links · category pages | 7 methods |
| [HomepageExhaustiveTest](src/test/java/testing/HomepageExhaustiveTest.java) | SANITY | No | All links via UrlValidationRunner · business outcome instrumentation | 1 (crawl) |
| [AppDirectoryTest](src/test/java/testing/AppDirectoryTest.java) | SANITY | No | Directory load · search · card content · lazy load | 7 methods |
| [ErrorHandlingTest](src/test/java/testing/ErrorHandlingTest.java) | REGRESSION | No | 404 · long URLs · special chars · XSS · recovery | 7 methods |
| [ResponsiveTest](src/test/java/testing/ResponsiveTest.java) | REGRESSION | No | Mobile/tablet/desktop viewports · menu · overflow | 10 methods |
| [AppPairingTest](src/test/java/testing/AppPairingTest.java) | REGRESSION | Handled | 6 app pairings: search→card→+→second app→automate→verify | 6 iterations |
| [TopAppCombinationsTest](src/test/java/testing/TopAppCombinationsTest.java) | REGRESSION | Handled | Top app combination workflow navigation | varies |
| [TrendingAppIntegrationsTest](src/test/java/testing/TrendingAppIntegrationsTest.java) | REGRESSION | Handled | Trending app integration landing flows | varies |
| [ExploreMenuIntegrationTest](src/test/java/testing/ExploreMenuIntegrationTest.java) | SANITY | No | Explore menu navigation and category flows | varies |
| [AuthenticatedTest](src/test/java/testing/AuthenticatedTest.java) | FULL | Required | E2E: login→dashboard→connect→activate · business outcome instrumentation | 1 (multi-step) |
| [CreateConnectWorkflowTest](src/test/java/testing/CreateConnectWorkflowTest.java) | FULL | Programmatic | Google Sheets (New Row) → Gmail (Create Draft) → activate | 1 (17 steps) |
| [GoHighLevelMindbodyConnectTest](src/test/java/testing/GoHighLevelMindbodyConnectTest.java) | FULL | Programmatic | GoHighLevel V2 (New Opportunity) → Mindbody (Create Sale) → activate | 1 (17 steps) |
| [RebrandCompletionTest](src/test/java/testing/RebrandCompletionTest.java) | NIGHTLY | No | appypieautomate.ai → flozic.ai redirect chain probe | 2 methods |
| [DashboardPanelInteractionTest](src/test/java/testing/dashboard/DashboardPanelInteractionTest.java) | REGRESSION | No | Panel collapse/expand · card borders · JS errors on load | 6 methods |
| [ScoreDriversRowFixtureTest](src/test/java/testing/dashboard/ScoreDriversRowFixtureTest.java) | REGRESSION | No | Fixture-based: triage box HTML + bucket reconciliation | 2 methods |
| [TrendStatsBlockFixtureTest](src/test/java/testing/dashboard/TrendStatsBlockFixtureTest.java) | REGRESSION | No | Fixture-based: outlier badge, within-range label, min-sample suppression | 4 methods |

Total: 18 test classes, ~65+ test methods

---

## 8. AppPairingTest — Detailed Flow

The most complex iterative test; updated with structured result tracking and observability:

```text
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

```text
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
| [config/js_error_ignore.json](config/js_error_ignore.json) | 3rd-party JS error suppression patterns (analytics, hotjar, intercom, zaraz, fedcm, swiper, etc.) |
| [config/performance.baselines.json](config/performance.baselines.json) | Expected page load times (ms): Homepage 1000, AppDirectory 1500, ConnectEditor 2500 |
| [config/workflow-dependencies.json](config/workflow-dependencies.json) | Test dependency graph for correlation engine |
| [config/ai.properties](config/ai.properties) | AI client configuration (API key, model, rate limits) |
| [config/history.properties](config/history.properties) | Historical data retention and calibration settings |
| [config/orchestration.properties](config/orchestration.properties) | Execution planning and prioritization settings |
| [config/complexity_budget.json](config/complexity_budget.json) | Phase A.5.0: scoring system complexity bounds — evidenceChannels (max 8), releaseDecisionCodes (max 16), enumTables, governanceBudget |
| [config/coverage-catalog.json](config/coverage-catalog.json) | Phase B1: business outcomes under test — currently 2 outcomes (connect.created.spreadsheet-to-gmail, homepage.navigation.health) |

---

## 12. Test Suites

| Suite File | Groups | Purpose | Approx Time |
|---|---|---|---|
| `testng.xml` | all | Default; runs everything | ~12 min |
| `smoke-testng.xml` | smoke | Critical path only | < 90s |
| `sanity-testng.xml` | sanity | Core functionality | ~3 min |
| `regression-testng.xml` | regression | Deeper validation | ~6 min |
| `e2e-testng.xml` | (no filter) | Runs every @Test in the `testing` package — comprehensive coverage in one command | ~25 min |
| `all-testng.xml` | (no filter) | Equivalent to e2e-testng.xml; kept for historical scripts | ~25 min |

---

## 13. Execution Reference

```powershell
# Default regression
mvn test

# Specific suite
mvn test "-DsuiteFile=e2e-testng.xml"
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
mvn test "-DsuiteFile=e2e-testng.xml" "-Dheadless=true"

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
| `marketing.baseUrl` | `https://www.flozic.ai` | Override for marketing domain (rebrand config) |
| `brand.productName` | `Flozic` | Override for product name in assertions |
| `quantity` | *(not set)* | Mindbody Create Sale: quantity value for auto-fill |
| `amount` | *(not set)* | Mindbody Create Sale: amount value for auto-fill |
| `notes` | `Automation Test` | Mindbody Create Sale: notes text for auto-fill |
| `manualTimeout` | `300` | Seconds to wait for manual field entry (fallback mode) |
| `recordVideo` | `false` | Enable per-test MP4 recording via FFmpeg |
| `recordVideo.maxMinutes` | `10` | Max recording duration before truncation |
| `recordPassedVideo` | `false` | Also record passing tests (default: FAIL only) |
| `healthGate.mode` | `advisory` | HealthGate enforcement: `advisory` (log only) or `blocking` (fail build) |
| `healthGate.disabled` | `false` | `true` = complete no-op; skips gate and snapshot |
| `healthSnapshot.disabled` | `false` | `true` = snapshot not written; gate still runs |
| `healthTracker.disabled` | `false` | `true` = HealthTracker becomes no-op |
| `trendStats.enabled` | `false` | Enable Phase C2 z-score/outlier trend statistics |

---

## 15. Output Artifacts

```text
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
| Health Score gate | ≥ 40 (default) | `HealthGate` / `-Dhealth.fail.score` (blocking mode only) |
| Smoke pass rate | ≥ 90% | `RiskInterpreter` (SMOKE_FAIL blocker) |
| Regression pass rate | 100% | `RiskInterpreter` (REGRESSION_INCOMPLETE warning) |
| Product Health BLOCKED | < 20 AND overall < 30 | `RiskInterpreter` (PRODUCT_HEALTH_CRITICAL blocker) |
| Trend stats outlier | z-score ≥ 2.0 AND sampleSize ≥ 5 | `TrendStatsCalculator` (Phase C2) |
| Trend stats window | 30 runs | `TrendStatsCalculator.DEFAULT_WINDOW` |
| Evidence channels budget | max 8 | `ComplexityBudget` |
| Release decision codes budget | max 16 | `ComplexityBudget` |
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
| Penalty-based health scoring | Non-binary: captures gradual quality degradation. **Caveat:** penalty weights (failure=15, fallback=5, JS error=1) are calibrated heuristically, not derived mathematically. They reflect relative production risk judgement. Any claim of scientific precision in a score like "73" is misleading — the number is a relative signal, not an objective measurement |
| EMA smoothing (α=0.3) | Prevents single flaky test from tanking score; favors historical stability. **Limitation:** with α=0.3 a run that collapses from 95→10 smooths to ~69 — release gates should therefore check raw blocker signals first (smoke pass rate, critical bugs, critical clusters) and use EMA only as a trend signal, not a hard threshold |
| Flow multipliers | Login/smoke failures have 2× impact — higher production risk |
| 4-signal readiness model | Eliminates `Thread.sleep()` races; handles Angular/React async rendering |
| JS injection over CDP | Version-agnostic; works with any Chrome version without protocol version lock |
| TRANSIENT-only retries | Prevents masking structural failures; only retries recoverable exceptions |
| Async AI enrichment | Enrichment never blocks test execution; gracefully skipped if API unavailable |
| ThreadLocal WebDriver (replaced static) | `DRIVER_HOLDER` ThreadLocal replaced the original single static WebDriver. Each thread owns its browser; enables parallel test execution. The old "single browser per suite" pattern is retired |
| Failure artifacts on disk | Screenshot + DOM + console in one folder; no log.txt bloat |
| Self-contained HTML dashboard | No server required; embeds Chart.js and filters inline |
| Owner enforcement (FULL tests) | Governance: prevents anonymous tests reaching highest risk tier |
| Governance layer for AI | AI recommendations are advisory only; humans retain release authority |
| Three Mindbody field patterns | Angular UI exposes readmorebutton2 (choices), contenteditable (direct), and plain input — each needs a different interaction path; unified under `fillMindbodySaleSetup()` |
| Maven -D properties for field values | Eliminates manual browser interaction; enables CI automation for field-dependent tests |
| Dual-gate product health | `productHealth < 20 AND score < POOR` required for BLOCKED — prevents false positives when only domain signal degrades |
| `scheduleWithFixedDelay` for video | Prevents frame-capture tasks from stacking under load; effective FPS degrades gracefully instead of crashing |
| `UrlRegistry` for rebrand | Single-point change for appypieautomate.ai → flozic.ai; lenient `isOwnedMarketingHost()` accepts both during 30-day transition, strict `isCurrentMarketingHost()` used only in `RebrandCompletionTest` |
| Defensive marketing selectors | `HomePage`/`PricingPage` accept multiple CSS patterns — the public marketing site changes faster than the Connect app; tests must survive DOM updates |
| HealthGate advisory-by-default | New Phase A.5 default is `advisory` — the gate never breaks CI until a team opts in with `-DhealthGate.mode=blocking`; prevents surprise failures during rollout |
| Structured `ReleaseDecision` | `DecisionFactor(code, value, threshold)` with no human-readable strings in the model — the dashboard explains WHY via factor codes, keeping the model machine-readable and the UI separately localizable |
| `ContributorBucket` explainability | Tracks raw vs applied penalty per source so the dashboard can answer "why did this run score 73?" without re-running analysis |
| `ComplexityBudget` read-only | The budget file is read-only at runtime; any change requires a PR — prevents silent growth of enums and decision codes that make scoring hard to reason about |
| `CoverageCatalog` severity weights | Outcomes weighted CRITICAL=3.0…LOW=0.5 so release scoring reflects business value, not just test count |
| Fixture-based dashboard tests | `ScoreDriversRowFixtureTest` / `TrendStatsBlockFixtureTest` use backup/inject/restore — browser-free, < 5 s, deterministic; verify HTML generation logic without needing a running suite |
| Sealed `SchemaMigrator` | Compiler-enforced implementations list; lossy migrations stamp `migrationLossy: true` so consumers know data fidelity was reduced |
| Origin-aware JS classification | First-party errors routed to FAIL/WARN; third-party (analytics, CDN, fedcm) ignored — reduces noise without a growing ignore list for framework errors |
| Singleton analytics trackers (risk) | Singletons enable cross-test aggregation without parameter threading. **Risk:** as parallel execution scales, concurrent writes from multiple threads into shared `HealthTracker`/`AnalyticsCollector` state can produce race conditions. Current thread-safety is synchronised at the method level; a full parallel suite would require per-thread child aggregators merged at suite end |

---

## 18. Extension Points

| Area | How to Extend |
|---|---|
| Add a new page | Create `pages/NewPage.java`; use `WaitUtils` + `ApplicationReadiness` |
| Add a new marketing page | Create `pages/marketing/NewPage.java`; use `UrlRegistry.integratePath(slug)` for URL construction |
| Add a new test | Extend `BaseTest`, add `@TestCategory` + TestNG group + owner |
| Add a new Connect workflow | Extend `CreateConnectWorkflowTest` pattern; add app constants; implement field-fill method in `ConnectEditorPage` |
| Add a Mindbody-style field | Add field name to `handleSetupStep()` Map or implement `fillVia*` helper matching field DOM pattern |
| Add a new suite | Create `new-suite-testng.xml`; run via `-DsuiteFile=new-suite-testng.xml` |
| Suppress a JS error | Add pattern to `config/js_error_ignore.json` |
| Update a perf baseline | Edit value in `config/performance.baselines.json` |
| Declare a test dependency | Add entry to `config/workflow-dependencies.json` |
| Adjust gate threshold | `-Dhealth.fail.score=N` or update default in `HealthPolicy` |
| Switch gate to blocking mode | Pass `-DhealthGate.mode=blocking`; or disable entirely with `-DhealthGate.disabled=true` |
| Add a new penalty type | Method in `HealthPolicy` → call from `HealthTracker` (add a `ContributorBucket`) → surface in `AnalyticsCollector` |
| Add a new evidence channel | Add to `config/complexity_budget.json` (evidenceChannels.current + currentValues); implement collector; wire into `DataQualityComponent` |
| Register a new business outcome | Add `OutcomeSpec` to `config/coverage-catalog.json`; reference from test via `CoverageCatalog.findById()` |
| Add a release decision code | Add to `config/complexity_budget.json` (releaseDecisionCodes); add `DecisionFactor` constant in `RiskInterpreter` |
| Change the marketing base URL | Set `-Dmarketing.baseUrl=https://...`; all page objects and `UrlRegistry` pick it up automatically |
| Enable AI analysis | Set API key in `config/ai.properties` |
| Enable video recording | Pass `-DrecordVideo=true`; ensure FFmpeg is in PATH |
| Override login credentials | Set `AUTOMATION_LOGIN_USER` / `AUTOMATION_LOGIN_PASS` env vars |
| Enable trend statistics | Pass `-DtrendStats.enabled=true`; requires ≥ 5 prior runs in history |

---

## 19. Architecture Type

This is a **modular monolithic test automation platform** — not microservices.

### Why it is NOT microservices

Microservices would require separate deployable processes, network communication (REST/gRPC/queues), independent scaling, and separate data stores per service. None of that exists here. All modules run inside a single JVM launched by:

```text
mvn test → TestNG → single JVM process
```

### Why it IS a monolith

Every subsystem — Selenium, HealthTracker, DashboardBuilder, AI layer, Trend engine, Governance layer, Correlation engine — is loaded together. A crash in one area can affect the whole execution.

### Why it is NOT a "big ball of mud"

Module boundaries are well-defined and respected:

```text
health/        → scoring and penalty aggregation
ai/            → async enrichment, rate-limited, optional
governance/    → AI safety, audit, budget constraints
history/       → trend computation and calibration
orchestration/ → execution planning and prioritisation
release/       → structured release decisions
dashboard/     → HTML generation only
```

Modules communicate through method calls and shared objects, not arbitrary cross-cutting state.

### Correct classification

> **Modular Monolith** — all modules are Java packages inside one Maven JVM with clear responsibility boundaries.

### Future service boundary candidates

If the platform grows to serve multiple teams, high run volumes, or an independently consumed dashboard, the following are natural service extraction candidates:

| Current package | Future service |
| --- | --- |
| `utils.ai` | AI Analysis Service |
| `utils.history` + `utils.orchestration` | Trend & Scheduling Service |
| Dashboard build pipeline | Reporting Service |
| `utils.health` + `utils.release` | Health Scoring Service |

Today these boundaries exist in code only. Extraction is not warranted at current scale.

---

## 20. Known Risks & Technical Debt

### High Priority

| Risk | Description | Mitigation |
| --- | --- | --- |
| **Singleton concurrency** | `HealthTracker`, `AnalyticsCollector`, `CoverageCatalog`, `ComplexityBudget` are shared across all test threads. Current `synchronized` blocks are fine for sequential execution but will produce aggregation corruption under true parallel TestNG. | If parallel suite execution is added, replace singletons with per-thread child aggregators that merge at `@AfterSuite`. |
| **EMA hides catastrophic regressions** | Score 95→10 smooths to ~69 at α=0.3. A broken release could appear HEALTHY in the dashboard. | Hard blockers in `RiskInterpreter` (`SMOKE_FAIL`, `CRITICAL_BUGS_OPEN`, `CRITICAL_CLUSTER_LIMIT`) bypass EMA and use raw signals. Never rely on smoothed score alone for release gating. |
| **Penalty weights are heuristic** | failure=15, fallback=5, JS error=1 are manual calibrations. The number "73" is a relative signal, not an objective measurement. | Document weights as opinionated baselines. Re-calibrate if false positives/negatives accumulate. Do not present scores as scientific measurements. |
| **Framework-to-test ratio** | ~18 test classes / ~65 test methods against hundreds of framework classes. The platform is growing faster than the test suite. | Apply the **What to Defer** list (§20 below) before adding new framework subsystems. Prioritise new test coverage over new framework capabilities. |

### Medium Priority

| Risk | Description |
| --- | --- |
| **Product health double-counting** | JS errors influence both the `HealthTracker` penalty score and the `SemanticHealthAnalyzer` product health dimension, which then feeds `RiskInterpreter`. The same signal can trigger multiple penalty paths. Audit `RiskInterpreter.decide()` inputs to ensure no input is counted twice. |
| **AI layer ROI** | `AiEnrichmentExecutor`, `FailureAnalyzer`, `ReleaseNarrator`, `AiCacheService`, and the full prompt system add significant maintenance surface. The screenshots, DOM, and stacktrace produced by `FailureArtifactManager` typically outperform AI narrative for root-cause diagnosis. AI layer should remain fully optional and fire-and-forget. |
| **Dashboard density** | The dashboard now renders health, governance, trend, clusters, semantic health, data quality, platform health, release decision, and orchestration panels. Panels that nobody opens in practice should be collapsed-by-default or hidden behind a feature flag. |
| **Governance self-referential growth** | `ComplexityBudget`, `CoverageCatalog`, `DecisionAuditLogger`, `GovernanceSnapshotBuilder`, `SuppressionAuditTracker` add overhead. Governance subsystems should not require governance of themselves. New governance additions need explicit justification against the existing budget. |

### What to Defer (do not implement next)

The following subsystems already exist or have been proposed. Adding more in these areas will worsen the framework-to-test ratio without proportionate bug-finding value:

- Additional AI narrative features
- Extended calibration algorithms beyond `TrendStatsCalculator`
- New orchestration optimisation strategies
- Expanded governance audit trails
- Further dashboard panels

---

## 21. Coverage Gaps (Intentionally Out of Scope — Today)

These gaps are documented so future teams can make an explicit decision to address them, not silently discover them.

| Gap | Why It Matters | Effort to Add |
| --- | --- | --- |
| **Visual regression testing** | Modern UI regressions (layout shifts, colour changes, element overlaps) pass all functional tests. Selenium validates behaviour, not appearance. | Medium — integrate Percy, Applitools, or screenshot diffing into `@AfterMethod` |
| **Accessibility (WCAG)** | No ARIA validation, keyboard navigation, or contrast checks. A page can pass all tests and still be inaccessible. | Medium — `axe-core` JS injection into existing test lifecycle |
| **Contract testing** | Connect workflows depend on Google Sheets, Gmail, Mindbody, GoHighLevel APIs. Network monitoring detects failures but does not validate schema contracts. A silent API shape change breaks workflows in ways `NetworkMonitor` cannot detect. | High — Pact or similar consumer-driven contract framework |
| **Synthetic production monitoring** | Framework executes against the application on demand. It does not continuously probe production. Long-term, test execution suites and synthetic monitors tend to converge. | High — requires separate always-on deployment |

---

## 22. Service Decomposition Roadmap

**Decision: do not convert to microservices now.**

The current modular monolith is a natural fit for a QA automation platform. Premature extraction would introduce service discovery, distributed tracing, network failures, API versioning, inter-service authentication, and eventual consistency — without proportionate benefit at current scale.

The roadmap below describes how to preserve the option to extract services later, without forcing that decision today.

---

### Phase 1 — Enforce Module Boundaries (No Extraction)

Reorganise the existing package tree into bounded contexts so that future extraction is possible without refactoring:

```text
Current                       Target
─────────────────────────     ─────────────────────────────
src/test/java/                src/test/java/
  utils/                        modules/
    health/                        core/      (Selenium, TestNG, POM, BaseTest)
    ai/             →              health/    (scoring, gating, release decision)
    governance/                    reporting/ (dashboard, PDF, trend charts)
    history/                       history/   (run store, trend, calibration)
    orchestration/                 ai/        (enrichment, narrator, cache)
    dashboard/                     governance/(budget, catalog, audit)
```

No service extraction yet. This step prevents cross-module coupling from calcifying before it can be broken.

---

### Phase 2 — Replace Direct Calls with Interfaces

Today modules call each other by concrete class:

```java
HealthTracker.get().recordTestFailure(...)
DashboardBuilder.write(HealthTracker.get().getSnapshot())
```

Replace with narrow interfaces so that each module depends on a contract, not an implementation:

```java
public interface HealthService {
    HealthSnapshot getCurrentHealth();
    void recordTestFailure(String testName, String flow);
}

public interface TrendStore {
    void appendRun(RunRecord run);
    List<Double> recentScores(int window);
}
```

`DashboardBuilder` depends on `HealthService` and `TrendStore` only. This step is the prerequisite for any service extraction — without it, extraction requires simultaneous refactoring of every call site.

---

### Candidate Services

Not everything deserves extraction. Evaluate each independently.

| Service | Owns | Input | Output | Extraction Value |
| --- | --- | --- | --- | --- |
| **Test Execution** | Selenium, TestNG, POM, BaseTest, workflow tests | Suite config, credentials | `RunRecord` (tests, failures, artifacts) | Core — stays central |
| **Health Service** | `HealthTracker`, `HealthGate`, `RiskInterpreter`, `ContributorBucket` | Test results, JS errors, perf metrics | `HealthSnapshot` (score, status, factors) | High — clean API boundary |
| **Trend Service** | `HistoryJsonWriter`, `TrendComputationService`, `TrendStatsCalculator` | Run records over time | Trend data, z-scores, regression flags | High — naturally wants its own datastore |
| **Reporting Service** | `DashboardBuilder`, `PdfReportBuilder`, `TrendExporter` | Health + analytics snapshots | `dashboard.html`, `executive.pdf`, `technical.pdf` | Medium — consumes snapshots only |
| **AI Analysis Service** | `AiClient`, `FailureAnalyzer`, `ReleaseNarrator`, `AiCacheService` | Failure artifacts (DOM, console, stacktrace) | Root cause + narrative JSON | Best candidate — calls are already external; isolating prevents AI failures from affecting test execution |

### Services to keep in the monolith

| Subsystem | Reason not to extract |
| --- | --- |
| **Governance** (`ComplexityBudget`, `CoverageCatalog`, `DecisionAuditLogger`) | Current size does not justify network overhead; read-only config with low call volume |
| **Correlation engine** | Tightly coupled to in-process failure objects; not enough value to justify boundary |
| **Orchestration engine** | Directly controls test execution order; network latency would harm suite startup |

---

### Target Architecture (If Extracted)

```text
              ┌──────────────────┐
              │  Dashboard UI    │
              └────────┬─────────┘
                       │
     ┌─────────────────┼─────────────────┐
     │                 │                 │
     ▼                 ▼                 ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  Health     │  │  Trend      │  │  Reporting  │
│  Service    │  │  Service    │  │  Service    │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
               ┌──────────────────┐
               │  Test Execution  │
               │  Service (core)  │
               └────────┬─────────┘
                        │
                        ▼
               ┌──────────────────┐
               │  AI Analysis     │
               │  Service         │
               └──────────────────┘
```

---

### Migration Order

Execute steps in order. Stop and re-evaluate after each one.

| Step | Action | Risk |
| --- | --- | --- |
| 1 | Enforce module package boundaries (Phase 1 above) | Low — rename only |
| 2 | Introduce service interfaces (Phase 2 above) | Low — add interfaces alongside concrete classes |
| 3 | Extract **AI Analysis Service** — lowest risk; calls are already external | Low |
| 4 | Extract **Health Service** — clean API, well-defined input/output | Medium |
| 5 | Extract **Trend Service** — needs its own persistent datastore | Medium |
| 6 | Extract **Reporting Service** — snapshot consumer, stateless | Medium |
| **Checkpoint** | Re-evaluate: does the platform genuinely need further decomposition, or is the modular monolith now sufficient? | — |

Many teams skip the checkpoint and continue to 20+ services that could have remained a well-structured monolith. The realistic end state for this platform is **4–5 services plus a Test Execution core**. Finer-grained decomposition is not warranted at current scale.
