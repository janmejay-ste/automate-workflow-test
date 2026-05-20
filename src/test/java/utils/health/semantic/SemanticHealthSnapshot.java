package utils.health.semantic;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.health.HealthTracker;
import utils.health.business.BusinessOutcomeState;
import utils.health.business.BusinessOutcomeTracker;
import utils.health.business.BusinessTransaction;
import utils.health.business.SuccessCriterion;
import utils.urlvalidator.UrlFinding;
import utils.urlvalidator.UrlValidationTracker;

import java.util.*;

/**
 * Read-only composition of all semantic-layer outputs for a single run.
 *
 * Built once from the live HealthTracker state at report-generation time.
 * Both the textual printReport() and the HTML DashboardBuilder consume this
 * facade — there is no other path through which the dashboard should read
 * scoring data.
 */
public final class SemanticHealthSnapshot {

    public final List<ErrorCluster>       clusters;
    public final List<PhaseReliability>   reliabilities;
    public final LayeredHealthScores      scores;
    public final int                      unknownTimingCount;
    public final int                      validTimingCount;
    public final Map<FailureDomain, Integer> errorCountByDomain;

    /** All business transactions recorded for this run, in registration order. */
    public final List<BusinessTransaction> businessOutcomes;

    /** Count of business transactions in each terminal state. */
    public final int  businessSuccess;
    public final int  businessPartial;
    public final int  businessFailed;
    public final int  businessAborted;

    private SemanticHealthSnapshot(List<ErrorCluster> clusters,
                                   List<PhaseReliability> reliabilities,
                                   LayeredHealthScores scores,
                                   int unknownTimingCount,
                                   int validTimingCount,
                                   Map<FailureDomain, Integer> errorCountByDomain,
                                   List<BusinessTransaction> businessOutcomes,
                                   int businessSuccess,
                                   int businessPartial,
                                   int businessFailed,
                                   int businessAborted) {
        this.clusters             = clusters;
        this.reliabilities        = reliabilities;
        this.scores               = scores;
        this.unknownTimingCount   = unknownTimingCount;
        this.validTimingCount     = validTimingCount;
        this.errorCountByDomain   = errorCountByDomain;
        this.businessOutcomes     = businessOutcomes;
        this.businessSuccess      = businessSuccess;
        this.businessPartial      = businessPartial;
        this.businessFailed       = businessFailed;
        this.businessAborted      = businessAborted;
    }

    /**
     * Build a snapshot from the singleton HealthTracker.  Pure read — does not
     * mutate any state on the tracker.
     */
    @SuppressWarnings("unchecked")
    public static SemanticHealthSnapshot from(HealthTracker tracker) {
        // 1. Cluster raw JS errors
        List<Map<String, String>> rawJs = jsonArrayToList(tracker.getJsErrors());
        List<ErrorCluster> clusters = ErrorClusterer.cluster(rawJs);

        // 2. Domain histogram for the summary line
        Map<FailureDomain, Integer> byDomain = new EnumMap<>(FailureDomain.class);
        for (ErrorCluster c : clusters) {
            byDomain.merge(c.domain, c.count, Integer::sum);
        }

        // 3. Reliability per phase — compare successes to phase-specific failure counts
        //    (failures in the same phase are inferred from criticalFlowFailures + testFailures)
        Map<String, Integer> successes = tracker.getSuccessCounters();
        Map<String, Integer> phaseFailures = countPhaseFailures(tracker);

        Set<String> phases = new LinkedHashSet<>();
        phases.addAll(successes.keySet());
        phases.addAll(phaseFailures.keySet());

        List<PhaseReliability> reliabilities = new ArrayList<>();
        for (String phase : phases) {
            reliabilities.add(new PhaseReliability(
                    phase,
                    successes.getOrDefault(phase, 0),
                    phaseFailures.getOrDefault(phase, 0)));
        }

        // 4. Layered scores — split test failures into product vs framework
        int testFailuresProduct   = 0;
        int testFailuresFramework = 0;
        for (Object o : tracker.getTestFailures()) {
            Map<String, String> tf = (Map<String, String>) o;
            FailureClassifier.Classification c =
                    FailureClassifier.classifyTestFailure(tf.get("test"), tf.get("reason"));
            if (c.domain == FailureDomain.FRAMEWORK) testFailuresFramework++;
            else                                      testFailuresProduct++;
        }

        int unknown = tracker.getUnknownTimings().size();
        // Valid timing samples are not separately tracked yet — count slowPages entries
        // with severity != "unknown" as a proxy.
        int valid = 0;
        for (Object o : tracker.getSlowPages()) {
            Map<String, String> sp = (Map<String, String>) o;
            if (!"unknown".equalsIgnoreCase(sp.getOrDefault("severity", ""))) {
                valid++;
            }
        }

        // ── Business outcomes ─────────────────────────────────────────────
        // Loaded from the BusinessOutcomeTracker (parallel singleton).  Counts feed
        // into the layered-score compute; the full transaction list is also exposed
        // so renderers can show per-transaction tables / criteria evidence.
        BusinessOutcomeTracker bt = BusinessOutcomeTracker.get();
        List<BusinessTransaction> outcomes = bt.all();
        int bSuccess = bt.countByState(BusinessOutcomeState.SUCCESS);
        int bPartial = bt.countByState(BusinessOutcomeState.PARTIAL);
        int bFailed  = bt.countByState(BusinessOutcomeState.FAILED);
        int bAborted = bt.countByState(BusinessOutcomeState.ABORTED);

        LayeredHealthScores scores = LayeredHealthScores.compute(
                clusters, unknown, valid, testFailuresProduct, testFailuresFramework,
                bSuccess, bPartial, bFailed);

        return new SemanticHealthSnapshot(clusters, reliabilities, scores,
                unknown, valid, byDomain,
                outcomes, bSuccess, bPartial, bFailed, bAborted);
    }

    /**
     * Emit a JSONObject representation suitable for embedding in the dashboard
     * snapshot JSON or for external consumption.
     */
    @SuppressWarnings("unchecked")
    public JSONObject toJson() {
        JSONObject out = new JSONObject();

        JSONObject layered = new JSONObject();
        layered.put("productHealth",       scores.productHealth);
        layered.put("frameworkHealth",     scores.frameworkHealth);
        layered.put("telemetryConfidence", scores.telemetryConfidence);
        // Use null in JSON when business outcomes were never recorded — distinct from "0"
        layered.put("businessOutcome",     scores.businessUnscored() ? null : scores.businessOutcome);
        layered.put("productStatus",       scores.productStatus());
        layered.put("frameworkStatus",     scores.frameworkStatus());
        layered.put("telemetryStatus",     scores.telemetryStatus());
        layered.put("businessStatus",      scores.businessStatus());
        out.put("layeredScores", layered);

        JSONArray clusterArr = new JSONArray();
        for (ErrorCluster c : clusters) {
            JSONObject co = new JSONObject();
            co.put("title",         c.title);
            co.put("count",         c.count);
            co.put("domain",        c.domain.label);
            co.put("severity",      c.severity.label);
            co.put("sampleMessage", c.sampleMessage);
            co.put("sampleContext", c.sampleContext);
            co.put("reason",        c.reason);
            clusterArr.add(co);
        }
        out.put("clusters", clusterArr);

        JSONArray relArr = new JSONArray();
        for (PhaseReliability pr : reliabilities) {
            JSONObject po = new JSONObject();
            po.put("phase",       pr.phase);
            po.put("successes",   pr.successes);
            po.put("failures",    pr.failures);
            po.put("percentage",  pr.percentage);
            po.put("confidence",  pr.confidence.label);
            relArr.add(po);
        }
        out.put("reliabilities", relArr);

        JSONObject domainCounts = new JSONObject();
        for (Map.Entry<FailureDomain, Integer> e : errorCountByDomain.entrySet()) {
            domainCounts.put(e.getKey().label, e.getValue());
        }
        out.put("errorCountByDomain", domainCounts);

      JSONObject business = new JSONObject();
        business.put("success", businessSuccess);
        business.put("partial", businessPartial);
        business.put("failed",  businessFailed);
        business.put("aborted", businessAborted);

        JSONArray bizTx = new JSONArray();
        for (BusinessTransaction tx : businessOutcomes) {
            JSONObject t = new JSONObject();
            t.put("id",         tx.id);
            t.put("name",       tx.name);
            t.put("category",   tx.category.name());
            t.put("state",      tx.state().name());
            t.put("durationMs", tx.durationMs());
            t.put("startedAt",  tx.startedAtMs);
            if (tx.failureReason() != null) t.put("failureReason", tx.failureReason());

            JSONArray critArr = new JSONArray();
            for (SuccessCriterion c : tx.criteria()) {
                JSONObject co = new JSONObject();
                co.put("label",    c.label);
                co.put("expected", c.expected);
                co.put("observed", c.observed);
                co.put("passed",   c.passed);
                co.put("pending",  c.isPending());
                critArr.add(co);
            }
            t.put("criteria", critArr);

            JSONObject evObj = new JSONObject();
            evObj.putAll(tx.evidence());
            t.put("evidence", evObj);

            bizTx.add(t);
        }
        business.put("transactions", bizTx);
        out.put("businessOutcomes", business);

        // ── URL validation findings ──────────────────────────────────────────
        // Recorded by utils.urlvalidator.UrlValidationRunner during the suite.
        // Exposed here in raw form (one entry per finding) so renderers can
        // aggregate by domain / severity / page using the same machinery used
        // for ErrorCluster.  No severity recalculation in the renderers.
        JSONArray urlArr = new JSONArray();
        UrlValidationTracker urlT = UrlValidationTracker.get();
        for (UrlFinding f : urlT.findings()) {
            JSONObject uf = new JSONObject();
            uf.put("url",         f.result.discovered.url);
            uf.put("finalUrl",    f.result.finalUrl);
            uf.put("type",        f.type.name());
            uf.put("severity",    f.severity.label);
            uf.put("domain",      f.domain.label);
            uf.put("reason",      f.reason);
            uf.put("status",      f.result.status);
            uf.put("source",      f.result.discovered.source.name());
            uf.put("page",        f.result.discovered.pageContext);
            uf.put("element",     f.result.discovered.elementSnippet);
            uf.put("attribute",   f.result.discovered.attribute);
            uf.put("redirects",   f.result.redirectCount());
            uf.put("method",      f.result.method.name());
            uf.put("durationMs",  f.result.durationMs);
            urlArr.add(uf);
        }
        JSONObject urlBlock = new JSONObject();
        urlBlock.put("pagesScanned",      urlT.pagesScanned().size());
        urlBlock.put("urlsValidated",     urlT.totalUrlsValidated());
        urlBlock.put("findingsCount",     urlT.totalFindings());
        urlBlock.put("findings",          urlArr);
        out.put("urlValidation", urlBlock);

        JSONObject telemetry = new JSONObject();
        telemetry.put("unknown", unknownTimingCount);
        telemetry.put("valid",   validTimingCount);
        out.put("telemetry", telemetry);

        return out;
    }

    /**
     * Render the human-readable section for printReport().
     */
    public void appendTo(StringBuilder out) {
        out.append("┌─ Layered Scores ────────────────────────────────────────┐\n");
        out.append(String.format("│  Product Health        : %3d  [%s]%n",
                scores.productHealth,       scores.productStatus()));
        out.append(String.format("│  Framework Health      : %3d  [%s]%n",
                scores.frameworkHealth,     scores.frameworkStatus()));
        out.append(String.format("│  Telemetry Confidence  : %3d  [%s]%n",
                scores.telemetryConfidence, scores.telemetryStatus()));
        if (scores.businessUnscored()) {
            out.append("│  Business Outcome      :   —  [NO DATA — no transactions recorded]\n");
        } else {
            out.append(String.format("│  Business Outcome      : %3d  [%s]   (S:%d P:%d F:%d A:%d)%n",
                    scores.businessOutcome, scores.businessStatus(),
                    businessSuccess, businessPartial, businessFailed, businessAborted));
        }
        out.append("└─────────────────────────────────────────────────────────┘\n");

        if (!clusters.isEmpty()) {
            out.append("\n┌─ Error Clusters ────────────────────────────────────────┐\n");
            for (ErrorCluster c : clusters) {
                out.append(String.format("│  [%-8s][%-14s] x%-3d  %s%n",
                        c.severity.label, c.domain.label, c.count, truncate(c.title, 48)));
            }
            out.append("└─────────────────────────────────────────────────────────┘\n");
        }

        if (!reliabilities.isEmpty()) {
            out.append("\n┌─ Phase Reliability ─────────────────────────────────────┐\n");
            for (PhaseReliability pr : reliabilities) {
                out.append("│").append(pr.asLine()).append("\n");
            }
            out.append("└─────────────────────────────────────────────────────────┘\n");
        }

        if (unknownTimingCount > 0) {
            out.append(String.format(
                    "%nℹ  %d timing measurement(s) came back UNKNOWN — telemetry gap, "
                  + "not a performance failure.%n", unknownTimingCount));
        }

        // ── URL Validation summary ──────────────────────────────────────────
        UrlValidationTracker urlT = UrlValidationTracker.get();
        if (urlT.totalUrlsValidated() > 0) {
            out.append("\n┌─ URL Validation ────────────────────────────────────────┐\n");
            out.append(String.format("│  Pages scanned     : %d%n", urlT.pagesScanned().size()));
            out.append(String.format("│  URLs validated    : %d%n", urlT.totalUrlsValidated()));
            out.append(String.format("│  Findings          : %d%n", urlT.totalFindings()));
            if (urlT.totalFindings() > 0) {
                // Top 5 findings by severity then status code
                urlT.findings().stream()
                        .sorted((a, b) -> {
                            int s = Integer.compare(b.severity.ordinal(), a.severity.ordinal());
                            if (s != 0) return s;
                            return Integer.compare(b.result.status, a.result.status);
                        })
                        .limit(5)
                        .forEach(f -> out.append(String.format(
                                "│   • [%s] %s — %s%n",
                                f.severity.label, f.type.name(),
                                truncate(f.result.discovered.url, 50))));
            }
            out.append("└─────────────────────────────────────────────────────────┘\n");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> jsonArrayToList(JSONArray arr) {
        List<Map<String, String>> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            if (o instanceof Map<?, ?> m) {
                Map<String, String> casted = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    casted.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                out.add(casted);
            }
        }
        return out;
    }

    /**
     * Derives per-phase failure counts from the tracker's existing critical-flow set
     * and test-failure list.  Phase names follow the convention
     * {@code Phase/Detail}, e.g. {@code WorkflowCreation/Telegram} → phase "WorkflowCreation".
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> countPhaseFailures(HealthTracker tracker) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String flow : tracker.getCriticalFlowFailures()) {
            String phase = flow.contains("/") ? flow.substring(0, flow.indexOf('/')) : flow;
            out.merge(phase, 1, Integer::sum);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
