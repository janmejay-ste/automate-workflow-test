package utils.urlvalidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Suite-scoped registry of URL findings.
 *
 * Parallel to HealthTracker / BusinessOutcomeTracker so URL validation has its
 * own lifecycle.  Findings are accumulated across however many pages the suite
 * scans and rolled up into the semantic snapshot at the end.
 */
public final class UrlValidationTracker {

    private static final UrlValidationTracker INSTANCE = new UrlValidationTracker();

    private final CopyOnWriteArrayList<UrlFinding>           findings   = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<UrlValidationResult>  results    = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String>               scannedPages = new CopyOnWriteArrayList<>();

    private UrlValidationTracker() {}

    public static UrlValidationTracker get() { return INSTANCE; }

    public void recordResults(String pageContext, List<UrlValidationResult> r,
                              List<UrlFinding> f) {
        if (pageContext != null && !scannedPages.contains(pageContext)) {
            scannedPages.add(pageContext);
        }
        if (r != null) results.addAll(r);
        if (f != null) findings.addAll(f);
    }

    public List<UrlFinding>           findings()     { return Collections.unmodifiableList(findings); }
    public List<UrlValidationResult>  allResults()   { return Collections.unmodifiableList(results); }
    public List<String>               pagesScanned() { return Collections.unmodifiableList(scannedPages); }

    public int totalUrlsValidated() { return results.size(); }
    public int totalFindings()      { return findings.size(); }

    public void reset() {
        findings.clear();
        results.clear();
        scannedPages.clear();
    }
}
