package utils.urlvalidator;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * One-call orchestrator for the URL validation pipeline.
 *
 * Tests use this single entry point:
 * <pre>
 *   UrlValidationRunner.scan(driver, "Home Page");
 * </pre>
 *
 * Composes:
 *   {@link UrlDiscoveryEngine}.discover(driver, label)
 *      → {@link UrlValidator}.validateAll(urls)
 *      → {@link UrlFindingClassifier}.classify(results)
 *      → {@link UrlValidationTracker}.recordResults(...)
 *
 * Returns a brief summary so the test can log how many URLs were checked
 * and how many issues were found — useful for visibility during the run.
 */
public final class UrlValidationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(UrlValidationRunner.class);

    private UrlValidationRunner() {}

    public static final class ScanSummary {
        public final String pageContext;
        public final int    urlsDiscovered;
        public final int    urlsValidated;
        public final int    findingsCount;

        ScanSummary(String pageContext, int discovered, int validated, int findings) {
            this.pageContext    = pageContext;
            this.urlsDiscovered = discovered;
            this.urlsValidated  = validated;
            this.findingsCount  = findings;
        }

        @Override
        public String toString() {
            return String.format("URL scan [%s]: %d discovered, %d validated, %d findings",
                    pageContext, urlsDiscovered, urlsValidated, findingsCount);
        }
    }

    /**
     * Discover, validate, classify, and record URL findings for the current page.
     * Failures inside the pipeline are logged but never thrown — URL validation
     * is observability, not a release gate.  (The findings themselves can affect
     * the release decision via the semantic-layer scoring.)
     */
    public static ScanSummary scan(WebDriver driver, String pageContext) {
        try {
            List<DiscoveredUrl>        urls     = UrlDiscoveryEngine.discover(driver, pageContext);
            List<UrlValidationResult>  results  = UrlValidator.validateAll(urls);
            List<UrlFinding>           findings = UrlFindingClassifier.classify(results);
            UrlValidationTracker.get().recordResults(pageContext, results, findings);

            ScanSummary s = new ScanSummary(pageContext, urls.size(), results.size(), findings.size());
            LOG.info(s.toString());
            return s;
        } catch (Exception e) {
            LOG.warn("URL validation scan failed for {}: {}", pageContext, e.getMessage());
            return new ScanSummary(pageContext, 0, 0, 0);
        }
    }
}
