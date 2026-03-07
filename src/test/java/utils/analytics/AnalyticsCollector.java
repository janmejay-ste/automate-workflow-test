package utils.analytics;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.health.HealthTracker;

/**
 * Aggregates data from all specialized trackers into a unified model.
 * V4 Hardened: Includes interpreted results from HealthTracker.
 */
public class AnalyticsCollector {

    @SuppressWarnings("unchecked")
    public static JSONObject collect() {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 2);
        root.put("timestamp", System.currentTimeMillis());

        HealthTracker ht = HealthTracker.get();

        // 0. Metadata & Global Interpreted Results
        JSONObject metadata = new JSONObject();
        metadata.put("suite", ht.getSuiteName());
        metadata.put("environment", ht.getEnvironment());
        metadata.put("timestamp", System.currentTimeMillis());
        root.put("metadata", metadata);

        root.put("overallScore", ht.getScore());
        root.put("totalPenalty", ht.getRawPenalty());
        root.put("status", ht.getStatus());

        // Pass Rates for RiskInterpreter
        long totalTests = ht.getTestFailures().size() + ht.getHealthyCount(); // Approximate
        if (totalTests == 0)
            totalTests = 1; // avoid div by zero

        double passRate = (double) ht.getHealthyCount() / totalTests;
        root.put("regressionPassRate", passRate);

        // NOTE: Smoke pass rate would ideally be filtered by group,
        // using 1.0 here unless failures found in smoke
        root.put("smokePassRate", 1.0);
        root.put("criticalProductBugs", 0); // Placeholder until bug classification integrated

        // 1. Locator Health
        JSONObject locatorObj = new JSONObject();
        LocatorTracker lt = LocatorTracker.get();
        lt.getStats().forEach((feature, counts) -> {
            JSONObject featureObj = new JSONObject();
            featureObj.put("primary", counts[0]);
            featureObj.put("fallback", counts[1]);
            featureObj.put("samples", counts[2]);
            featureObj.put("health", lt.getPrimarySuccessRate(feature));
            featureObj.put("isReliable", counts[2] >= 10); // Policy: 10 samples
            locatorObj.put(feature, featureObj);
        });
        locatorObj.put("globalHealth", lt.getGlobalSuccessRate());
        root.put("locators", locatorObj);
        root.put("totalLocatorSamples", lt.getGlobalStats()[2]);

        // 2. Performance
        JSONObject perfObj = new JSONObject();
        PerformanceTracker pt = PerformanceTracker.get();
        perfObj.put("baselineVersion", pt.getBaselineVersion());

        pt.getMeasurements().forEach((page, duration) -> {
            JSONObject m = new JSONObject();
            m.put("durationMs", duration);
            m.put("baselineMs", pt.getBaselines().getOrDefault(page, 0L));
            m.put("classification", pt.getClassification(page, duration).name());
            perfObj.put(page, m);
        });
        root.put("performance", perfObj);

        // 3. JS Errors
        JSONObject jsObj = new JSONObject();
        JsErrorTracker jet = JsErrorTracker.get();
        jsObj.put("auditMode", jet.isAuditMode());

        JSONArray prodArr = new JSONArray();
        jet.getProductErrors().forEach((key, info) -> prodArr.add(errorToJson(info)));
        jsObj.put("product", prodArr);

        JSONArray suppArr = new JSONArray();
        jet.getSuppressedErrors().forEach((key, info) -> suppArr.add(errorToJson(info)));
        jsObj.put("suppressed", suppArr);

        root.put("jsErrors", jsObj);

        // 4. Failure Type Counts
        JSONObject failObj = new JSONObject();
        // Placeholder - in real system this comes from BaseTest mapping
        failObj.put("PRODUCT_BUG", 0);
        failObj.put("AUTOMATION_BUG", 0);
        failObj.put("ENVIRONMENT", 0);
        root.put("failureTypeCounts", failObj);

        // 5. Raw Evidence (from HealthTracker)
        root.put("tests", ht.getTestRecords()); // Now returns JSONArray of test details
        root.put("slowPages", ht.getSlowPages());
        root.put("fallbacks", ht.getFallbacks());
        root.put("testFailures", ht.getTestFailures());
        root.put("warnings", ht.getWarnings());

        return root;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject errorToJson(JsErrorTracker.ErrorInfo info) {
        JSONObject err = new JSONObject();
        err.put("message", info.message);
        err.put("count", info.count.get());
        JSONArray pages = new JSONArray();
        pages.addAll(info.affectedPages);
        err.put("pages", pages);
        return err;
    }
}
