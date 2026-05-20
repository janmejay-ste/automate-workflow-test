package utils.urlvalidator;

import utils.health.semantic.ClusterSeverity;
import utils.health.semantic.FailureDomain;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts {@link UrlValidationResult}s into {@link UrlFinding}s.
 *
 * One result can produce multiple findings (broken AND sensitive-path AND
 * https-downgrade) — we emit them all so the report shows the complete picture
 * rather than collapsing to a single tag.  The dashboard / PDF can choose to
 * collapse to the highest severity if needed.
 *
 * Domain mapping:
 *   BROKEN          → INFRASTRUCTURE  (the link target is unreachable — could be product or external)
 *   UNREACHABLE     → INFRASTRUCTURE  (DNS / TLS / connection error)
 *   SENSITIVE_PATH  → PRODUCT          (exposed in product DOM = product/security issue)
 *   HTTPS_DOWNGRADE → PRODUCT          (mixed-content / downgrade originated from product page)
 *   EXCESSIVE_REDIRECTS → INFRASTRUCTURE (typically CDN / load-balancer misconfig)
 *   UNEXPECTED_EXTERNAL_REDIRECT → PRODUCT (link disguised as in-platform actually leaves)
 */
public final class UrlFindingClassifier {

    private static final int REDIRECT_THRESHOLD = 5;

    /** Set of host suffixes considered "expected external" — telemetry/analytics, CDNs, etc. */
    private static final List<String> ALLOWED_EXTERNAL_SUFFIXES = List.of(
            "appypie.com", "appypieautomate.ai", "flozic.ai",
            "googletagmanager.com", "google-analytics.com",
            "googleapis.com", "gstatic.com", "google.com",
            "fonts.googleapis.com", "cloudflare.com",
            "youtube.com", "ytimg.com",
            "linkedin.com", "facebook.com", "twitter.com",
            "cdn.jsdelivr.net"
    );

    /**
     * Domains owned by Appy Pie / flozic.  A 5xx from these is HIGH severity —
     * it's our own infrastructure failing.  A 5xx from any other domain is MEDIUM —
     * it's a third-party site that Appy Pie cannot fix; it should be visible in the
     * report but must not gate the build.
     */
    private static final List<String> OWNED_DOMAIN_SUFFIXES = List.of(
            "appypie.com", "appypieautomate.ai", "flozic.ai",
            "connectcloud.appypie.com"
    );

    /**
     * Host suffixes whose status responses to programmatic HEAD/GET probes
     * are known to be unreliable due to anti-bot / WAF layers.  We classify
     * 4xx responses from these hosts as INFO rather than BROKEN — the link
     * may be perfectly valid in a real browser; the probe failure is a
     * limitation of automated checking, not a defect.
     *
     * 999 (LinkedIn) and 400/403/406/451 from social networks are the most
     * common false positives.  Same path treatment for Cloudflare's
     * {@code /cdn-cgi/l/email-protection} obfuscation endpoint, which 404s by
     * design when accessed directly — it only resolves when invoked client-side
     * by the CF JS shim.
     */
    private static final List<String> ANTI_BOT_HOST_SUFFIXES = List.of(
            "facebook.com",   "fb.com",     "instagram.com",
            "linkedin.com",   "lnkd.in",
            "twitter.com",    "x.com",      "t.co",
            "tiktok.com",     "pinterest.com",
            "youtube.com",    "youtu.be",
            "reddit.com",     "medium.com"
    );

    /**
     * Path fragments that 4xx by design when probed directly — the link itself
     * is valid in a real browser, the probe just can't validate it.
     */
    private static final List<String> NON_PROBEABLE_PATH_FRAGMENTS = List.of(
            "/cdn-cgi/l/email-protection",     // Cloudflare email obfuscation
            "/cdn-cgi/challenge-platform/"     // Cloudflare bot challenge
    );

    private UrlFindingClassifier() {}

    public static List<UrlFinding> classify(List<UrlValidationResult> results) {
        List<UrlFinding> out = new ArrayList<>();
        for (UrlValidationResult r : results) {
            out.addAll(classifyOne(r));
        }
        return out;
    }

    public static List<UrlFinding> classifyOne(UrlValidationResult r) {
        List<UrlFinding> findings = new ArrayList<>();

        // ── Sensitive path (independent of status — even a 403 admin route is a finding) ─
        Optional<SensitivePathDetector.Match> sm = SensitivePathDetector.match(r.discovered.url);
        if (sm.isEmpty()) {
            sm = SensitivePathDetector.match(r.finalUrl);
        }
        sm.ifPresent(m -> findings.add(new UrlFinding(
                r, UrlFindingType.SENSITIVE_PATH, m.severity, FailureDomain.PRODUCT, m.reason)));

        // ── Broken / unreachable — but anti-bot hosts get a noise downgrade ──
        // A 400 from facebook.com or a 404 from cdn-cgi/l/email-protection is almost
        // certainly NOT a broken link; it's an anti-bot/WAF response to programmatic
        // probing.  These get classified as INFO so they appear in the diagnostic
        // table but don't drive Product/Infrastructure scores or trigger test failure.
        boolean isAntiBotProbe = isAntiBotHost(r.discovered.url) || isNonProbeablePath(r.discovered.url);
        if (r.isUnreachable()) {
            if (isAntiBotProbe) {
                findings.add(new UrlFinding(
                        r, UrlFindingType.UNREACHABLE, ClusterSeverity.INFO,
                        FailureDomain.OBSERVABILITY,
                        "Anti-bot / non-probeable host returned no response — informational only"));
            } else {
                findings.add(new UrlFinding(
                        r, UrlFindingType.UNREACHABLE, ClusterSeverity.HIGH,
                        FailureDomain.INFRASTRUCTURE,
                        "URL could not be reached: " + (r.errorMessage == null ? "(no detail)" : r.errorMessage)));
            }
        } else if (r.isBroken()) {
            if (isAntiBotProbe) {
                findings.add(new UrlFinding(
                        r, UrlFindingType.BROKEN, ClusterSeverity.INFO,
                        FailureDomain.OBSERVABILITY,
                        "Anti-bot / non-probeable host returned " + r.status
                                + " — informational only (browser likely sees this link as valid)"));
            } else {
                // 5xx from Appy Pie–owned domains = HIGH (our own infrastructure).
                // 5xx from third-party domains = MEDIUM — it's their problem, not ours;
                // visible in the report but must not gate the build.
                ClusterSeverity sev;
                if (r.status >= 500) {
                    sev = isOwnedDomain(r.discovered.url) ? ClusterSeverity.HIGH : ClusterSeverity.MEDIUM;
                } else {
                    sev = ClusterSeverity.MEDIUM;
                }
                findings.add(new UrlFinding(
                        r, UrlFindingType.BROKEN, sev, FailureDomain.INFRASTRUCTURE,
                        "Server returned " + r.status));
            }
        }

        // ── HTTPS downgrade — any hop in the chain that went https→http ─────
        if (hasHttpsDowngrade(r.redirectChain)) {
            findings.add(new UrlFinding(
                    r, UrlFindingType.HTTPS_DOWNGRADE, ClusterSeverity.HIGH,
                    FailureDomain.PRODUCT,
                    "Redirect chain crossed from HTTPS to HTTP — mixed-content / downgrade risk"));
        }

        // ── Excessive redirects ──────────────────────────────────────────────
        if (r.redirectCount() >= REDIRECT_THRESHOLD) {
            findings.add(new UrlFinding(
                    r, UrlFindingType.EXCESSIVE_REDIRECTS, ClusterSeverity.MEDIUM,
                    FailureDomain.INFRASTRUCTURE,
                    "URL went through " + r.redirectCount() + " redirects (threshold "
                            + REDIRECT_THRESHOLD + ")"));
        }

        // ── Unexpected external redirect — start on one host, end on unrelated one ─
        if (!r.redirectChain.isEmpty()
                && r.redirectChain.size() > 1
                && isUnexpectedExternal(r.discovered.url, r.finalUrl)) {
            findings.add(new UrlFinding(
                    r, UrlFindingType.UNEXPECTED_EXTERNAL_REDIRECT, ClusterSeverity.MEDIUM,
                    FailureDomain.PRODUCT,
                    "URL redirected from " + host(r.discovered.url)
                            + " to unrelated host " + host(r.finalUrl)));
        }

        // ── Mixed content — image/script/iframe loaded over http: from any page ─
        if (isMixedContent(r.discovered)) {
            findings.add(new UrlFinding(
                    r, UrlFindingType.MIXED_CONTENT, ClusterSeverity.MEDIUM,
                    FailureDomain.PRODUCT,
                    "Resource loaded over HTTP — mixed-content risk on HTTPS pages"));
        }

        return findings;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean hasHttpsDowngrade(List<String> chain) {
        boolean sawHttps = false;
        for (String u : chain) {
            if (u == null) continue;
            String lower = u.toLowerCase();
            if (lower.startsWith("https://")) sawHttps = true;
            else if (sawHttps && lower.startsWith("http://")) return true;
        }
        return false;
    }

    private static boolean isUnexpectedExternal(String original, String finalUrl) {
        String o = host(original);
        String f = host(finalUrl);
        if (o.isEmpty() || f.isEmpty() || o.equalsIgnoreCase(f)) return false;
        // Allowed list — common analytics / CDN / SSO hops.
        for (String suffix : ALLOWED_EXTERNAL_SUFFIXES) {
            if (f.toLowerCase().endsWith(suffix)) return false;
        }
        // Same registrable domain → not unexpected (subdomain shifts inside one brand are fine).
        return !sameRegistrableDomain(o, f);
    }

    private static boolean isMixedContent(DiscoveredUrl du) {
        // We don't know the page's protocol from here; treat any sub-resource (img/script/iframe/source)
        // explicitly served over http: as a mixed-content risk.  This is a conservative heuristic —
        // refine in Sprint 3 with CDP page-context awareness.
        if (du.url == null) return false;
        if (!du.url.toLowerCase().startsWith("http://")) return false;
        return du.source == UrlSourceKind.IMAGE
            || du.source == UrlSourceKind.SCRIPT
            || du.source == UrlSourceKind.STYLESHEET
            || du.source == UrlSourceKind.IFRAME
            || du.source == UrlSourceKind.SOURCE
            || du.source == UrlSourceKind.VIDEO
            || du.source == UrlSourceKind.AUDIO;
    }

    private static String host(String url) {
        try { return new URI(url).getHost() == null ? "" : new URI(url).getHost(); }
        catch (Exception e) { return ""; }
    }

    /** {@code true} if the URL belongs to an Appy Pie–owned domain. */
    private static boolean isOwnedDomain(String url) {
        String h = host(url).toLowerCase();
        if (h.isEmpty()) return false;
        for (String suffix : OWNED_DOMAIN_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

    /** {@code true} if the URL's host is in {@link #ANTI_BOT_HOST_SUFFIXES}. */
    private static boolean isAntiBotHost(String url) {
        String h = host(url).toLowerCase();
        if (h.isEmpty()) return false;
        for (String suffix : ANTI_BOT_HOST_SUFFIXES) {
            if (h.endsWith(suffix)) return true;
        }
        return false;
    }

    /** {@code true} if the URL contains a path fragment known to 4xx by design. */
    private static boolean isNonProbeablePath(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        for (String fragment : NON_PROBEABLE_PATH_FRAGMENTS) {
            if (lower.contains(fragment)) return true;
        }
        return false;
    }

    private static boolean sameRegistrableDomain(String h1, String h2) {
        String[] p1 = h1.toLowerCase().split("\\.");
        String[] p2 = h2.toLowerCase().split("\\.");
        if (p1.length < 2 || p2.length < 2) return false;
        return p1[p1.length - 2].equals(p2[p2.length - 2])
            && p1[p1.length - 1].equals(p2[p2.length - 1]);
    }
}
