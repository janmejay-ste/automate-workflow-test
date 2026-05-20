package testing;

import base.TestCategory;
import base.TestType;
import org.testng.annotations.Test;

/**
 * Validates the Explore → "Top App Combinations" submenu section end-to-end.
 *
 * Flow per link:
 *   1. Hover Explore nav → collect links from "Top App Combinations" section
 *   2. Navigate to each combination integration page
 *   3. If a family picker is shown → iterate each app card
 *   4. Click a.bannerInnerBtn → handle login redirect → wait for editor loader
 *   5. 5-gate editor validation → logout → return to home for next iteration
 *
 * Combination pages (e.g. /integrate/apps/gmail/integrations/telegram) typically
 * show a two-app banner with a single Automate button — no family picker expected.
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = true,
        feature = "Top App Combinations", owner = "Janmejay")
public class TopAppCombinationsTest extends ExploreMenuTestBase {

    @Override
    String sectionTitle() {
        return "Top App Combinations";
    }

    @Test(groups = { "regression" }, priority = 4)
    public void testTopAppCombinations() {
        runSection();
    }
}
