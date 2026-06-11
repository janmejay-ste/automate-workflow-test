package testing.health.cluster;

import java.util.LinkedHashMap;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.cluster.ClusterKey;
import utils.health.cluster.ContributorSource;
import utils.health.cluster.FallbackFingerprintExtractor;
import utils.health.cluster.FingerprintExtractor;
import utils.health.cluster.JsErrorFingerprintExtractor;
import utils.health.cluster.TestFailureFingerprintExtractor;

/**
 * Locks down fingerprint extractor behavior BEFORE any production data
 * starts depending on it. Each test exercises one of three categories:
 *
 * <ol>
 *   <li><b>Stability under transient detail</b> — same root cause across
 *       different line numbers / coordinates / hashes / IDs must produce
 *       the same ClusterKey.</li>
 *   <li><b>Separation under genuine difference</b> — semantically different
 *       events on the same surface must NOT collide.</li>
 *   <li><b>Near-identical fingerprints</b> — the boundary cases. Same
 *       error shape, slightly different property name being read. These
 *       MUST get distinct keys; if they ever start colliding, the
 *       diminishing-returns policy will silently bury two real bugs as
 *       one. This is the test category most likely to drift over time, so
 *       these cases are checked explicitly.</li>
 * </ol>
 *
 * <p>No browser, no production wiring, no flag-gated code. Pure unit
 * verification of the cluster-key contract. Sub-second runtime.</p>
 */
public class FingerprintStabilityTest {

    // ────────────────────────────────────────────────────────────────────────
    // Fallback extractor
    // ────────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void fallbacks_sameSelectorAndPage_clustersTogether() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.FALLBACKS);
        ClusterKey a = x.extract(evt(Map.of(
                "name",   "TextSearchFallback",
                "target", "button.continue",
                "page",   "https://connectcloud.appypie.com/customeditor")));
        ClusterKey b = x.extract(evt(Map.of(
                "name",   "TextSearchFallback",
                "target", "button.continue",
                "page",   "https://connectcloud.appypie.com/customeditor/")));        // trailing slash
        ClusterKey c = x.extract(evt(Map.of(
                "name",   "TextSearchFallback",
                "target", "button.continue",
                "page",   "https://connectcloud.appypie.com/customeditor?step=2")));  // query string
        Assert.assertEquals(a, b, "trailing slash must not change cluster identity");
        Assert.assertEquals(a, c, "query string must not change cluster identity");
    }

    @Test(groups = {"sanity"})
    public void fallbacks_volatileSelectorIds_clusterTogether() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.FALLBACKS);
        ClusterKey a = x.extract(evt(Map.of(
                "name",   "MenuDropFallback",
                "target", "[data-id=\"123\"] .menu_icon-box",
                "page",   "connectcloud.appypie.com/customeditor")));
        ClusterKey b = x.extract(evt(Map.of(
                "name",   "MenuDropFallback",
                "target", "[data-id=\"456\"] .menu_icon-box",                          // different runtime id
                "page",   "connectcloud.appypie.com/customeditor")));
        Assert.assertEquals(a, b,
                "volatile data-id values must be normalized so two runs of the "
                + "same flow share a fingerprint");
    }

    @Test(groups = {"sanity"})
    public void fallbacks_differentSelectors_doNotCollide() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.FALLBACKS);
        ClusterKey a = x.extract(evt(Map.of(
                "name",   "TextSearchFallback",
                "target", "button.continue",
                "page",   "/customeditor")));
        ClusterKey b = x.extract(evt(Map.of(
                "name",   "TextSearchFallback",
                "target", "button.activate",
                "page",   "/customeditor")));
        Assert.assertNotEquals(a, b,
                "different selectors are different root causes — must not cluster together");
    }

    @Test(groups = {"sanity"})
    public void fallbacks_differentPages_doNotCollide() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.FALLBACKS);
        ClusterKey a = x.extract(evt(Map.of("name", "TextSearchFallback",
                "target", "button.continue", "page", "/customeditor")));
        ClusterKey b = x.extract(evt(Map.of("name", "TextSearchFallback",
                "target", "button.continue", "page", "/dashboard")));
        Assert.assertNotEquals(a, b,
                "same selector on different pages must NOT cluster — distinct root causes");
    }

    @Test(groups = {"sanity"})
    public void fallbacks_emptyEvent_yieldsUnknown() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.FALLBACKS);
        Assert.assertEquals(x.extract(Map.of()), ClusterKey.UNKNOWN);
        Assert.assertEquals(x.extract(null), ClusterKey.UNKNOWN);
    }

    // ────────────────────────────────────────────────────────────────────────
    // JsError extractor
    // ────────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void jsErrors_lineColCoordinates_strippedFromFingerprint() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.JS_ERRORS);
        ClusterKey a = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "TypeError at main.js:42:18")));
        ClusterKey b = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "TypeError at main.js:73:204")));
        Assert.assertEquals(a, b,
                "line:col coordinates must be stripped — same error different positions");
    }

    @Test(groups = {"sanity"})
    public void jsErrors_webpackHashes_strippedFromFingerprint() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.JS_ERRORS);
        ClusterKey a = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "Cannot read properties of null at vendor.a3f8e1b2.js")));
        ClusterKey b = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "Cannot read properties of null at vendor.7c91d4ef.js")));
        Assert.assertEquals(a, b, "different build hashes must collapse to the same fingerprint");
    }

    @Test(groups = {"sanity"})
    public void jsErrors_differentContexts_doNotCollide() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.JS_ERRORS);
        ClusterKey a = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "Cannot read properties of null (reading 'appendChild')")));
        ClusterKey b = x.extract(evt(Map.of(
                "context", "Dashboard",
                "message", "Cannot read properties of null (reading 'appendChild')")));
        Assert.assertNotEquals(a, b,
                "same error in different test contexts is two root causes — must not cluster");
    }

    /**
     * THE near-identical case. Same shape of error, different property
     * being read. Today's policy: distinct fingerprints. This test locks
     * that decision in so it can't drift silently. If a future change to
     * the normalization rules (e.g. "strip everything inside parentheses")
     * would cause these to collide, this test fires.
     */
    @Test(groups = {"sanity"})
    public void jsErrors_nearIdenticalPropertyName_doNotCollide() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.JS_ERRORS);
        ClusterKey a = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "Cannot read properties of null (reading 'appendChild')")));
        ClusterKey b = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "Cannot read properties of null (reading 'addEventListener')")));
        Assert.assertNotEquals(a, b,
                "Different property names = different missing API = different root cause. "
                + "Clustering these together would silently bury a real second bug.");
    }

    /**
     * The mirror near-identical case: identical property, different
     * grammatical phrasing the browser sometimes emits. These SHOULD
     * cluster — same root cause, vendor variation in the error string.
     */
    @Test(groups = {"sanity"})
    public void jsErrors_browserPhrasingVariants_cluster() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.JS_ERRORS);
        ClusterKey a = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "Cannot read properties of null (reading 'appendChild')")));
        ClusterKey b = x.extract(evt(Map.of(
                "context", "ConnectEditor",
                "message", "Cannot read properties of null (reading 'appendChild')   ")));   // trailing whitespace
        Assert.assertEquals(a, b,
                "trailing whitespace must be normalized away");
    }

    // ────────────────────────────────────────────────────────────────────────
    // TestFailure extractor
    // ────────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void testFailures_sameDeepestUserFrame_clusters() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.TEST_FAILURES);
        String sharedStack = String.join("\n",
                "at org.testng.Assert.fail(Assert.java:111)",
                "at org.testng.Assert.assertTrue(Assert.java:57)",
                "at pages.ConnectEditorPage.clickContinue(ConnectEditorPage.java:340)",
                "at testing.CreateConnectWorkflowTest.testCreateAppSheetEmailConnect(CreateConnectWorkflowTest.java:120)"
        );
        ClusterKey a = x.extract(evt(Map.of(
                "testClass",  "CreateConnectWorkflowTest",
                "testMethod", "testCreateAppSheetEmailConnect",
                "stackTrace", sharedStack)));
        ClusterKey b = x.extract(evt(Map.of(
                "testClass",  "CreateConnectWorkflowTest",
                "testMethod", "testCreateAppSheetEmailConnect",
                "stackTrace", sharedStack.replace(":340)", ":341)")))); // line drifted by 1
        Assert.assertEquals(a, b, "minor stack line drift must not split clusters");
    }

    @Test(groups = {"sanity"})
    public void testFailures_differentDeepestUserFrame_doNotCluster() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.TEST_FAILURES);
        ClusterKey a = x.extract(evt(Map.of(
                "testClass",  "TestX",
                "testMethod", "fooFails",
                "stackTrace", "at pages.PageA.clickButton(PageA.java:10)")));
        ClusterKey b = x.extract(evt(Map.of(
                "testClass",  "TestX",
                "testMethod", "fooFails",
                "stackTrace", "at pages.PageB.clickButton(PageB.java:10)")));
        Assert.assertNotEquals(a, b,
                "different deepest user frame = different root cause");
    }

    @Test(groups = {"sanity"})
    public void testFailures_noUsableStack_fallsBackToReason() {
        FingerprintExtractor x = FingerprintExtractor.forSource(ContributorSource.TEST_FAILURES);
        ClusterKey a = x.extract(evt(Map.of(
                "testClass",  "T1",
                "testMethod", "m1",
                "reason",     "Smoke setup failed: chrome session not created")));
        Assert.assertNotEquals(a, ClusterKey.UNKNOWN,
                "graceful degradation: empty stack + non-empty reason must still produce a key");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Factory contract
    // ────────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void factory_returnsCorrectExtractorPerSource() {
        Assert.assertTrue(
                FingerprintExtractor.forSource(ContributorSource.FALLBACKS)
                        instanceof FallbackFingerprintExtractor);
        Assert.assertTrue(
                FingerprintExtractor.forSource(ContributorSource.JS_ERRORS)
                        instanceof JsErrorFingerprintExtractor);
        Assert.assertTrue(
                FingerprintExtractor.forSource(ContributorSource.TEST_FAILURES)
                        instanceof TestFailureFingerprintExtractor);
    }

    // ── helper ──────────────────────────────────────────────────────────────

    /** Map.of() returns an unmodifiable map; tests sometimes need ordering. */
    private static Map<String, String> evt(Map<String, String> m) {
        return new LinkedHashMap<>(m);
    }
}
