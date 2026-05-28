package testing;

import base.TestCategory;
import base.TestType;
import org.testng.annotations.Test;

/**
 * Validates the Explore → "Popular App Integrations" submenu section end-to-end.
 *
 * Flow per link:
 *   1. Hover Explore nav → collect links from "Popular App Integrations" section
 *   2. Navigate to each integration page
 *   3. If a family picker is shown (e.g. Microsoft Suite) → iterate each app card
 *   4. Click a.bannerInnerBtn → handle login redirect → wait for editor loader
 *   5. 5-gate editor validation → logout → return to home for next iteration
 *
 * Link count is dynamic: 1 link found → 1 run, 20 links found → MAX_LINKS runs.
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = true,
        feature = "Popular App Integrations", owner = "Janmejay")
public class ExploreMenuIntegrationTest extends ExploreMenuTestBase {

    @Override
    String sectionTitle() {
        return "Popular App Integrations";
    }

    @Test(groups = { "regression" }, priority = 2)
    public void testPopularAppIntegrations() {
        runSection();
    }
}
