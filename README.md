# AppyPie Automate — Test Agent Framework

> **Branch:** `appypieautomate-test-agent`
> **Status:** Active Development — evolving from a test automation suite into a self-driving QA Test Agent

---

## What This Project Does

This is a **Selenium + TestNG** based QA framework for [AppyPie Automate](https://automate.appypie.com) that goes beyond simple pass/fail testing. It is being progressively evolved into an **autonomous QA Test Agent** that can:

- Run multi-suite tests (Sanity, Smoke, Regression, Full)
- Collect rich analytics per test run (JS errors, locator reliability, performance baselines)
- Score each run with a **Health Score (0–100)** using a penalty-based model
- Generate an **interactive Health Dashboard** with trend charts, filter controls, and drill-down details
- Track history across runs and detect regressions automatically
- Make go/no-go release decisions based on configurable risk thresholds

---

## Current Project State

| Layer | Status | Description |
|-------|--------|-------------|
| **Test Execution** | ✅ Active | Selenium/TestNG tests across App Pairing, Login, Navigation, Error Handling |
| **Analytics Collector** | ✅ Active | Per-test JS error tracking, locator reliability, performance baselines |
| **Health Scoring** | ✅ Active | Penalty-based scoring (JS errors, fallbacks, slow pages, test failures) |
| **Health Dashboard** | ✅ Active | Interactive HTML dashboard with trend charts and grouped filters |
| **History Tracking** | ✅ Active | CSV + JSON trend history with run metadata |
| **Test Agent Evolution** | 🔄 In Progress | Moving towards autonomous test orchestration and self-healing |

---

## Project Structure

```
automate-workflow-test/
├── src/test/java/
│   ├── base/
│   │   ├── BaseTest.java          # Browser setup, dashboard generation, health gate
│   │   ├── TestCategory.java      # Test suite category enum (SANITY, SMOKE, REGRESSION, FULL)
│   │   └── TestType.java          # Test type annotations
│   ├── pages/                     # Page Object Model — each file = one web page
│   │   ├── DashboardPage.java
│   │   ├── ConnectEditorPage.java
│   │   └── WaitUtils.java
│   ├── testing/                   # Test classes
│   │   ├── AppPairingTest.java    # Core app pairing flow (Zoho, Shopify, etc.)
│   │   ├── AppDirectoryTest.java
│   │   ├── LoginTest.java
│   │   ├── ErrorHandlingTest.java
│   │   ├── SignupTest.java
│   │   ├── ResponsiveTest.java
│   │   └── AuthenticatedTest.java
│   └── utils/
│       ├── DashboardBuilder.java  # Generates the HTML health dashboard
│       ├── TrendDataWriter.java   # Writes/reads run history (CSV)
│       ├── HistoryJsonWriter.java # Writes run history (JSON)
│       ├── HealthPolicy.java      # Go/no-go release thresholds
│       ├── RiskInterpreter.java   # Interprets risk levels from analytics
│       ├── FailureArtifactManager.java # Captures screenshots on failure
│       ├── analytics/
│       │   ├── AnalyticsCollector.java    # Central analytics aggregator
│       │   ├── JsErrorTracker.java        # Captures browser console JS errors
│       │   ├── LocatorTracker.java        # Tracks locator reliability per feature
│       │   ├── PerformanceTracker.java    # Page load time vs baselines
│       │   └── TestAnalyticsLogger.java   # Writes analytics reports
│       ├── health/
│       │   └── HealthTracker.java         # Health score computation
│       └── policy/
│           └── SeverityClassifier.java    # Classifies issue severity
├── config/
│   ├── js_error_ignore.json       # JS error patterns to ignore (noise filtering)
│   └── performance.baselines.json # Page load time baselines per page
├── reports/trend/
│   ├── dashboard.html             # Generated health dashboard (open in browser)
│   ├── history.csv                # Per-run trend history
│   └── history.json              # Per-run trend history (JSON format)
├── testng.xml                     # Default test suite (Regression)
├── sanity-testng.xml              # Sanity suite
├── smoke-testng.xml               # Smoke suite
├── regression-testng.xml          # Regression suite
├── full-testng.xml                # Full suite
└── pom.xml                        # Maven build config (JDK 21)
```

---

## How to Run

### Prerequisites
- **JDK 21** (configured in `.vscode/settings.json`)
- **Maven 3.x**
- **Google Chrome** (ChromeDriver auto-managed)

### Run Tests (Maven)

```powershell
# Run default regression suite
mvn test

# Run a specific test class (headless)
mvn test -Dtest=AppPairingTest -Dheadless=true

# Run with a specific suite file
mvn test -DsuiteFile=sanity-testng.xml

# Run full suite
mvn test -DsuiteFile=full-testng.xml
```

### View the Health Dashboard

After any test run, open the generated dashboard:
```
reports/trend/dashboard.html
```

The dashboard shows:
- **Health Score** (0–100) with status: Healthy / Minor / Degraded / At Risk / Critical
- **Health Score Trend** chart across last N runs (with toggle pills for threshold/average lines)
- **Penalty Breakdown** donut chart (JS Errors, Slow Pages, Fallbacks, Other)
- **Slow Pages**, **JS Errors**, **Fallback Details**, **Test Failures**, **Warnings**
- **Recent Test Runs** table with grouped smart filters (Status: All/Passed/Failed + Type: All/Sanity/Smoke/Regression/Full)

---

## Health Scoring Model

| Issue Type | Penalty per Occurrence |
|-----------|----------------------|
| Test Failure | ~15 pts |
| JS Error (critical) | ~10 pts |
| Fallback Used | ~10 pts |
| Slow Page | ~5 pts |
| Warning (high) | ~3 pts |

**Score = 100 − total penalties (capped at 0)**

| Score | Status |
|-------|--------|
| ≥ 90 | ✅ Healthy |
| 75–89 | 🔵 Minor Issues |
| 60–74 | 🟡 Degraded |
| 40–59 | 🟠 At Risk |
| < 40 | 🔴 Critical |

---

## Test Agent Roadmap

The project is evolving towards an autonomous **QA Test Agent** capable of:

- [ ] Self-healing locators (auto-retry with fallback strategies)
- [ ] Intelligent test prioritization based on historical failure rates
- [ ] Automated root cause analysis using analytics signals
- [ ] PR-level regression gating (CI/CD integration)
- [ ] Natural language test generation from user stories
- [ ] Autonomous re-run of flaky tests with environment context

---

## Key Configuration Files

| File | Purpose |
|------|---------|
| `config/js_error_ignore.json` | Regex patterns for JS errors to suppress (noise reduction) |
| `config/performance.baselines.json` | Expected page load times per URL pattern |
| `testng.xml` | Default suite; controls which tests run and with what listeners |

---

## Branch
**`appypieautomate-test-agent`** — the single active branch on `https://github.com/janmejay-ste/automate-workflow-test`
