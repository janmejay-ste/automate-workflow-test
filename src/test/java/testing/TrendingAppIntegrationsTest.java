package testing;

import base.TestCategory;
import base.TestType;
import org.testng.annotations.Test;

/**
 * Validates the Explore → "Trending App Integrations" submenu section end-to-end.
 *
 * Flow per link:
 *   1. Hover Explore nav → collect links from "Trending App Integrations" section
 *   2. Navigate to each integration page
 *   3. If a family picker is shown (e.g. Microsoft Suite) → iterate each app card
 *   4. Click a.bannerInnerBtn → handle login redirect → wait for editor loader
 *   5. 5-gate editor validation → logout → return to home for next iteration
 *
 * Duplicate entries in the section (same label, same href) are automatically
 * deduplicated by LinkedHashMap so each unique integration is tested once.
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = true,
        feature = "Trending App Integrations", owner = "Janmejay")
public class TrendingAppIntegrationsTest extends ExploreMenuTestBase {

    @Override
    String sectionTitle() {
        return "Trending App Integrations";
    }

    @Test(groups = { "regression" }, priority = 3)
    public void testTrendingAppIntegrations() {
        runSection();
    }
}
