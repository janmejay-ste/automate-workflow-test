package utils;

import org.testng.annotations.Test;
import org.testng.Assert;

public class HealthTrackerSuppressionTest {

    @Test
    public void suppressionKeywordsMatchAndWarningsRecorded() {
        HealthTracker tracker = HealthTracker.get();

        // Test data
        String homepageWarning = "HomePage - Title unexpected: 'AI No-Code Platform to Turn Prompt to Apps & Websites'";
        String fallbackDetail = "MCP Server → https://www.appypieautomate.ai/mcp/";

        // Check suppression helper
        Assert.assertTrue(
                tracker.isSuppressedConsole(homepageWarning),
                "Homepage title unexpected must be suppressed"
        );

        Assert.assertTrue(
                tracker.isSuppressedConsole(fallbackDetail),
                "MCP Server fallback must be suppressed"
        );

        // Record warnings/fallbacks
        tracker.addWarning(
                "HomePage",
                "Title unexpected: 'AI No-Code Platform to Turn Prompt to Apps & Websites'"
        );

        tracker.recordFallback(
                "MCP Server",
                "https://www.appypieautomate.ai/mcp/"
        );

        boolean foundHomePageWarn = tracker.getWarningsList()
                .stream()
                .anyMatch(w ->
                        w.toLowerCase().contains("homepage") &&
                        w.toLowerCase().contains("title unexpected")
                );

        Assert.assertTrue(
                foundHomePageWarn,
                "Recorded warnings should include the homepage title warning"
        );

        boolean foundFallback = tracker.getFallbackDetails()
                .stream()
                .anyMatch(f ->
                        f.toLowerCase().contains("mcp server")
                );

        Assert.assertTrue(
                foundFallback,
                "Recorded fallback details should include MCP Server entry"
        );
    }
}
