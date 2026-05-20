package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.By;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import utils.health.HealthTracker;
import utils.health.business.BusinessOutcomeTracker;
import utils.health.business.BusinessTransaction;
import utils.health.business.BusinessTransactionCategory;
import utils.health.semantic.ClusterSeverity;
import utils.urlvalidator.UrlFinding;
import utils.urlvalidator.UrlValidationRunner;
import utils.urlvalidator.UrlValidationTracker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exhaustive homepage validation — discovers every URL reachable from the
 * home page (anchors, scripts, iframes, images, fetches, …) and validates
 * each one through the central URL framework.
 *
 * <p><b>Migration note:</b> this test previously rolled its own
 * {@code HttpURLConnection} loop with no retry, no GET fallback, no
 * canonicalisation, and only scanned {@code a, button} elements.  That
 * implementation generated false positives for Cloudflare email-protection,
 * Facebook anti-bot 400s, and any transient timeout.  Replaced by
 * {@link UrlValidationRunner#scan(org.openqa.selenium.WebDriver, String)}
 * which centralises every URL-validation concern in one place and shares
 * its policy with every other consumer (dashboard, PDF, future tests).</p>
 *
 * <p><b>Business outcome:</b> the run is also recorded as one user-intent
 * transaction so the executive PDF can answer "did the homepage actually
 * work for visitors?" instead of just "did the harness pass?"</p>
 */
@TestCategory(type = TestType.SANITY, feature = "Homepage")
public class HomepageExhaustiveTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(HomepageExhaustiveTest.class);

    /**
     * Severity threshold that gates test pass/fail.  HIGH+ findings fail the
     * test (real broken backend, real exposed admin path).  MEDIUM/LOW/INFO
     * findings are recorded for visibility but do not fail the run — they're
     * the kind of noise (anti-bot 400s, redirected media) that block CI for
     * no operational value.
     */
    private static final int GATING_ORDINAL = ClusterSeverity.HIGH.ordinal();

    @Test(groups = { "sanity" })
    public void testAllButtonsAndLinks() {
        LOG.info("=== Starting Exhaustive Homepage Test for Appy Pie Automate ===");

        // ── Declare the business outcome up front ──────────────────────────
        // The test represents one user-visible intent: "the homepage loads and
        // its declared links resolve cleanly enough that a visitor can navigate
        // away."  Recorded as a transaction so the layered Business Outcome
        // score reflects whether the homepage actually works for users, not
        // just whether the harness ran.
        BusinessTransaction tx = BusinessOutcomeTracker.get().begin(
                "Homepage Navigation Health — appypieautomate.ai",
                BusinessTransactionCategory.ONBOARDING)
            .require("Homepage loads without WebDriver error", "driver.get() returns")
            .require("URL framework discovers URLs from DOM",   "discoveredCount > 0")
            .require("No high/critical broken-link findings",   "all findings severity < HIGH");

        try {
            driver.get("https://www.appypieautomate.ai/");
            // Settle dynamic content briefly so iframes/images/scripts are in the DOM.
            Thread.sleep(5000);
            tx.observePass("Homepage loads without WebDriver error", driver.getCurrentUrl());

            // ── Discover + validate via the central framework ──────────────
            // Every URL classification concern (retry, canonicalisation, anti-bot
            // skip, HEAD→GET fallback, redirect chain capture) lives in
            // utils.urlvalidator/ and applies uniformly here.  No ad-hoc URL
            // logic in this test file.
            UrlValidationRunner.ScanSummary summary =
                    UrlValidationRunner.scan(driver, "Appypie Automate Homepage");
            LOG.info("URL scan summary: {}", summary);

            // Also count the raw interactive elements for diagnostic comparison
            // with the old test's reporting.
            int interactives = driver.findElements(By.cssSelector("a, button")).size();
            LOG.info("Total interactive elements (a, button): {}", interactives);
            LOG.info("Total unique URLs discovered (all element kinds): {}", summary.urlsDiscovered);

            if (summary.urlsDiscovered > 0) {
                tx.observePass("URL framework discovers URLs from DOM",
                               summary.urlsDiscovered + " URLs discovered");
            } else {
                tx.observeFail("URL framework discovers URLs from DOM",
                               "0 URLs discovered — homepage may not have rendered");
                tx.complete();
                Assert.fail("URL discovery returned zero URLs — homepage likely failed to render");
            }

            // ── Gate ────────────────────────────────────────────────────────
            // Only HIGH/CRITICAL findings fail the test.  MEDIUM and below are
            // recorded for the report but don't block — they're noise unless
            // promoted to a higher severity by the classifier.
            List<UrlFinding> gating = UrlValidationTracker.get().findings().stream()
                    .filter(f -> f.severity.ordinal() >= GATING_ORDINAL)
                    .toList();

            if (gating.isEmpty()) {
                tx.observePass("No high/critical broken-link findings",
                               UrlValidationTracker.get().totalFindings()
                                       + " informational findings, 0 gating");
                tx.complete();
                LOG.info("=== Exhaustive Homepage Test Completed Successfully ===");
            } else {
                tx.observeFail("No high/critical broken-link findings",
                               gating.size() + " gating finding(s)");
                tx.withEvidence("gatingCount", String.valueOf(gating.size()));
                tx.complete();

                // ── Structured detail for the dashboard ────────────────────
                // Convert gating findings to plain maps so HealthTracker can
                // store them as JSON and the dashboard can render them as a
                // mini-table inside the Test Failure Details row.
                // This must happen BEFORE Assert.fail() because the TestNG
                // listener records the failure entry moments after the throw.
                List<Map<String, Object>> findingMaps = gating.stream()
                        .map(f -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("url",      f.result.discovered.url);
                            m.put("severity", f.severity.label);
                            m.put("type",     f.type.name());
                            m.put("status",   f.result.status);
                            m.put("reason",   f.reason);
                            m.put("source",   f.result.discovered.source != null
                                              ? f.result.discovered.source.name() : "");
                            return m;
                        })
                        .collect(Collectors.toList());
                HealthTracker.get().queueFailureDetail("urlFindings", findingMaps);

                // ── Human-readable assertion message ───────────────────────
                StringBuilder msg = new StringBuilder("Homepage produced ")
                        .append(gating.size()).append(" high/critical URL finding(s):\n");
                for (UrlFinding f : gating) {
                    msg.append("  • [").append(f.severity.label).append("] ")
                       .append(f.type.name()).append("  ")
                       .append(f.result.discovered.url)
                       .append("  (status ").append(f.result.status).append(")\n");
                }
                Assert.fail(msg.toString());
            }
        } catch (AssertionError ae) {
            tx.complete();
            throw ae;
        } catch (Exception e) {
            tx.abort("Unexpected exception mid-flow: " + e.getMessage());
            LOG.error("Homepage exhaustive test crashed: {}", e.getMessage());
            Assert.fail("Homepage exhaustive test crashed: " + e.getMessage());
        }
    }
}
