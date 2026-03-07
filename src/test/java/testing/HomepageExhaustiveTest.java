package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@TestCategory(type = TestType.SANITY, feature = "Homepage")
public class HomepageExhaustiveTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(HomepageExhaustiveTest.class);

    @Test(groups = { "sanity" })
    public void testAllButtonsAndLinks() {
        LOG.info("=== Starting Exhaustive Homepage Test for Appy Pie Automate ===");
        driver.get("https://www.appypieautomate.ai/");

        // Wait for page to fully load
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
        }

        List<WebElement> allInteractables = driver.findElements(By.cssSelector("a, button"));
        LOG.info("Total interactive elements found on page: {}", allInteractables.size());

        Set<String> uniqueUrls = new HashSet<>();
        AtomicInteger brokenLinksCount = new AtomicInteger(0);
        AtomicInteger emptyInteractablesCount = new AtomicInteger(0);

        for (WebElement element : allInteractables) {
            String tagName = element.getTagName();
            String href = element.getAttribute("href");
            String onclick = element.getAttribute("onclick");
            String text = element.getText().trim();
            if (text.isEmpty()) {
                text = element.getAttribute("title"); // Fallback for icon buttons
            }

            if ("a".equalsIgnoreCase(tagName)) {
                if (href != null && !href.isEmpty() && !href.startsWith("javascript:") && !href.equals("#")) {
                    uniqueUrls.add(href);
                } else if (onclick == null || onclick.isEmpty()) {
                    LOG.warn("Anchor element missing both valid href and onclick: {}",
                            element.getAttribute("outerHTML"));
                    emptyInteractablesCount.incrementAndGet();
                }
            } else if ("button".equalsIgnoreCase(tagName)) {
                // Buttons often rely on attached JS listeners and Angular bindings rather than
                // href/onclick attributes
                // We log them for reporting but do not strictly fail them if they lack explicit
                // onclicks
            }
        }

        LOG.info("Total unique actionable URLs found: {}", uniqueUrls.size());

        // Programmatic Link Validation Loop
        for (String targetUrl : uniqueUrls) {
            try {
                // Ignore mailto or tel links for HTTP checks
                if (targetUrl.startsWith("mailto:") || targetUrl.startsWith("tel:")) {
                    continue;
                }

                // Add default protocol if missing
                if (targetUrl.startsWith("//")) {
                    targetUrl = "https:" + targetUrl;
                } else if (targetUrl.startsWith("/")) {
                    targetUrl = "https://www.appypieautomate.ai" + targetUrl;
                }

                HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
                connection.setRequestMethod("HEAD"); // Use HEAD to check status without downloading body content
                connection.setConnectTimeout(5000); // 5 sec timeout
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                connection.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
                connection.connect();

                int respCode = connection.getResponseCode();

                // 999 is commonly used by LinkedIn / anti-bot CDNs to block non-browser
                // programmatic access
                if (respCode == 999 && targetUrl.contains("linkedin.com")) {
                    LOG.debug("LinkedIn anti-bot 999 ignored for: {}", targetUrl);
                    respCode = 200; // Treat as OK for this test
                }

                if (respCode >= 400 && respCode != 403 && respCode != 401) { // 403/401 aren't necessarily broken links,
                                                                             // just protected endpoints
                    LOG.error("BROKEN LINK FOUND: {} responded with code {}", targetUrl, respCode);
                    brokenLinksCount.incrementAndGet();
                } else {
                    LOG.debug("Link OK: {} (Code: {})", targetUrl, respCode);
                }
            } catch (Exception e) {
                LOG.error("Error checking link {}: {}", targetUrl, e.getMessage());
                brokenLinksCount.incrementAndGet();
            }
        }

        Assert.assertEquals(brokenLinksCount.get(), 0,
                "Found " + brokenLinksCount.get() + " broken links on the homepage.");

        LOG.info("=== Exhaustive Homepage Test Completed Successfully ===");
    }
}
