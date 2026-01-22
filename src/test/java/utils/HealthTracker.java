package utils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class HealthTracker {

    private static final HealthTracker INSTANCE = new HealthTracker();

    private static final Logger LOG = LoggerFactory.getLogger(HealthTracker.class);

    public static HealthTracker get() {
        return INSTANCE;
    }

    // *** ONLY THESE ARE CRITICAL ***
    // 'Login' fallbacks are expected in some security flows (SSO) — do not treat them as critical broken.
    private static final Set<String> CRITICAL_PAGES = Set.of("AI Automation", "AI Connects", "Signup");

    // Keywords / fragments to suppress from console output (case-insensitive).
    // These are intentionally permissive substrings so messages like "Navigation mismatch → URL did not contain expected fragment: signup"
    // will be matched and suppressed from console logs, while still being recorded internally.
    private static final List<String> SUPPRESSED_KEYWORDS = List.of(
            "homepage - title unexpected",
            "title unexpected",
            "lcp unavailable",
            "popular automations",
            "ai automation", // used to suppress AI Automation fallback console noise
            "fallback used",
            "used fallback url",
            "mcp-server",
            "mcp server",
            "navigation mismatch → url did not contain expected fragment: mcp-server",
            "navigation mismatch → url did not contain expected fragment: contact",
            "navigation mismatch → url did not contain expected fragment: signup",
            "contact",
            "signup"
    );

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    // Counters
    private int totalNavSteps = 0;
    private int fallbackCount = 0;
    private int slowPagesCount = 0;
    private int jsErrorCount = 0; // unique root-cause JS error count
    private int warningCount = 0;
    private int thirdPartyAuthCount = 0;
    private int expectedExternalRedirectCount = 0;
    private int penaltyPoints = 0;

    // Thresholds
    private long slowThresholdMs = 5000L;

    // Details
    private final List<String> fallbackDetails = new ArrayList<>();
    private final List<String> slowPagesDetails = new ArrayList<>();
    private final List<String> jsErrors = new ArrayList<>(); // full recorded JS messages
    private final List<String> thirdPartyAuthDetails = new ArrayList<>();
    private final List<String> expectedExternalRedirectDetails = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    // Track unique JS error root causes per context to avoid double-penalizing same root
    private final Map<String, Set<String>> jsErrorRootsByContext = new HashMap<>();

    private HealthTracker() {
    }

    // ------------ helpers ------------

    private boolean isCriticalContext(String ctx) {
        if (ctx == null)
            return false;
        String s = ctx.trim().toLowerCase();
        return CRITICAL_PAGES.stream().anyMatch(p -> s.equals(p.toLowerCase()));
    }

    private boolean containsSuppressedKeyword(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String kw : SUPPRESSED_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private boolean shouldSuppressConsole(String labelContext, Severity severity, String message, String composedLabel) {
        // If the composed label or message contains a suppression keyword, always suppress.
        if (containsSuppressedKeyword(composedLabel) || containsSuppressedKeyword(labelContext) || containsSuppressedKeyword(message)) {
            return true;
        }

        // Otherwise, do not suppress HIGH/CRITICAL by default
        if (severity == Severity.CRITICAL || severity == Severity.HIGH) return false;

        return false;
    }

    // Sanitize long messages for console output. Keep internal stored messages unchanged.
    private String sanitizeForConsole(String labelContext, Severity severity, String message, String composedLabel) {
        if (composedLabel != null && containsSuppressedKeyword(composedLabel)) {
            return "[" + severity + "] " + labelContext + " - (suppressed)";
        }

        // Prefer message if available; otherwise fall back to composedLabel
        String text = (message != null && !message.isBlank()) ? message : composedLabel;
        if (text == null) text = "";

        // Only keep first meaningful line and trim out verbose driver blocks
        String[] lines = text.split("\\r?\\n");
        String first = lines.length > 0 ? lines[0] : text;

        // Remove common verbose substrings introduced by Selenium exceptions
        int idx = first.indexOf("Build info:");
        if (idx != -1) first = first.substring(0, idx).trim();
        idx = first.indexOf("Session ID:");
        if (idx != -1) first = first.substring(0, idx).trim();
        idx = first.indexOf("Driver info:");
        if (idx != -1) first = first.substring(0, idx).trim();
        idx = first.indexOf("For documentation on this error");
        if (idx != -1) first = first.substring(0, idx).trim();

        // If still large, truncate to 200 chars
        if (first.length() > 200) {
            first = first.substring(0, 200) + "...";
        }

        return "[" + severity + "] " + labelContext + " - " + first;
    }

    private String formatForConsoleFromStoredLabel(String storedLabel) {
        if (storedLabel == null) return "";
        // Expected format: "[SEVERITY] Context - message"
        int close = storedLabel.indexOf("] ");
        if (close > 0 && storedLabel.startsWith("[")) {
            String sevStr = storedLabel.substring(1, close);
            String rest = storedLabel.substring(close + 2);
            int dash = rest.indexOf(" - ");
            String ctx = dash > 0 ? rest.substring(0, dash) : rest;
            String msg = dash > 0 ? rest.substring(dash + 3) : "";
            Severity sev;
            try {
                sev = Severity.valueOf(sevStr);
            } catch (Exception ex) {
                sev = Severity.LOW;
            }
            return sanitizeForConsole(ctx, sev, msg, storedLabel);
        }
        // Fallback: shorten storedLabel
        String firstLine = storedLabel.split("\\r?\\n")[0];
        int bi = firstLine.indexOf("Build info:");
        if (bi != -1) firstLine = firstLine.substring(0, bi).trim();
        if (firstLine.length() > 300) firstLine = firstLine.substring(0, 300) + "...";
        return firstLine;
    }

    // Extract a JS error root signature (first meaningful line trimmed)
    private String extractJsRoot(String message) {
        if (message == null) return "";
        String[] lines = message.split("\\r?\\n");
        String first = lines.length > 0 ? lines[0] : message;
        first = first.trim();
        if (first.length() > 300) first = first.substring(0, 300);
        return first;
    }

    // ------------ recording API ------------

    private void applyPenalty(String context, Severity severity, String message) {
        warningCount++;

        String labelContext = (context == null || context.isBlank()) ? "GLOBAL" : context.trim();

        String label = "[" + severity + "] " + labelContext + " - " + message;
        warnings.add(label);

        boolean critical = isCriticalContext(labelContext);

        // Critical flows hit harder
        int delta = switch (severity) {
            case CRITICAL -> critical ? 12 : 4;
            case HIGH -> critical ? 8 : 3;
            case MEDIUM -> critical ? 4 : 1;
            case LOW -> 1;
        };

        penaltyPoints += delta;

        // Only log to console if not suppressed by policy; when logging, sanitize long messages
        if (!shouldSuppressConsole(labelContext, severity, message, label)) {
            String consoleLabel = sanitizeForConsole(labelContext, severity, message, label);
            LOG.warn("⚠ {}", consoleLabel);
        }
    }

    public void recordNavStep(String stepName, long loadTimeMs) {
        totalNavSteps++;

        if (loadTimeMs > slowThresholdMs) {
            slowPagesCount++;
            String msg = stepName + " = " + loadTimeMs + " ms";
            slowPagesDetails.add(msg);

            Severity sev = loadTimeMs > 10000 ? Severity.HIGH : Severity.MEDIUM;
            applyPenalty(stepName, sev, "Slow page: " + msg);
        }
    }

    public void recordFallback(String stepName, String url) {
        fallbackCount++;
        String msg = stepName + " → " + url;
        fallbackDetails.add(msg);
        // If this fallback is an expected external redirect (SSO / identity provider), record separately
        if (isExpectedExternalRedirect(stepName, url)) {
            expectedExternalRedirectCount++;
            expectedExternalRedirectDetails.add(msg);
            LOG.info("Recorded expected external redirect for {} -> {}", stepName, url);
            return; // do not penalize
        }

        // Non-expected fallbacks: keep recorded for trend but do not penalize here (penalty handled elsewhere)
        LOG.info("Recorded fallback for {} -> {}", stepName, url);
    }

    private boolean isExpectedExternalRedirect(String stepName, String url) {
        if (url != null && url.toLowerCase().contains("accounts.appypie.com")) return true;
        if (stepName != null && stepName.trim().equalsIgnoreCase("login")) return true;
        return false;
    }

    public void recordJsError(String context, String message) {
        // Record full message for reporting
        jsErrors.add(context + " → " + message);

        // Deduplicate based on root cause per context
        String ctx = (context == null || context.isBlank()) ? "GLOBAL" : context.trim();
        String root = extractJsRoot(message);
        Set<String> roots = jsErrorRootsByContext.computeIfAbsent(ctx.toLowerCase(), k -> new HashSet<>());
        if (roots.contains(root)) {
            // Already penalized for this root cause in this context — skip double-penalty
            LOG.info("Duplicate JS root for {} -> {} (skipping additional penalty)", ctx, root);
            return;
        }

        // New unique JS root for this context
        roots.add(root);
        jsErrorCount++;
        applyPenalty(context, Severity.HIGH, "JS Error: " + root);
    }

    /**
     * Record third-party auth / FedCM / GSI related console messages.
     * These are informational and should not be counted as JS errors or penalized.
     */
    public void recordThirdPartyAuth(String context, String message) {
        thirdPartyAuthCount++;
        thirdPartyAuthDetails.add(context + " → " + message);
        // Do not increment jsErrorCount or penaltyPoints; do not call applyPenalty
        LOG.info("[THIRD_PARTY_AUTH] {} → {}", context, sanitizeForConsole(context, Severity.LOW, message, message));
    }

    /**
     * Add a warning but do not apply penalty if a JS error already exists for the same context.
     * This prevents double-penalizing a page where a JS error already caused failures.
     */
    public void addWarningNoPenaltyIfJsError(String context, String message) {
        String labelContext = (context == null || context.isBlank()) ? "GLOBAL" : context.trim();
        String label = "[LOW] " + labelContext + " - " + message;
        warnings.add(label);
        if (hasJsErrorForContext(context)) {
            LOG.info("Suppressed additional penalty for {} because JS error exists: {}", context, message);
            return;
        }
        // No JS error exists for this context; apply normal LOW penalty
        applyPenalty(context, Severity.LOW, message);
    }

    public void addWarning(String context, String message) {
        applyPenalty(context, Severity.LOW, message);
    }

    public boolean hasJsErrorForContext(String context) {
        if (context == null) return false;
        Set<String> roots = jsErrorRootsByContext.get(context.trim().toLowerCase());
        return roots != null && !roots.isEmpty();
    }

    // ------------ score + status ------------

    private int computeScoreInternal() {
        int score = 100 - penaltyPoints;
        return Math.max(score, 0);
    }

    public int getScore() {
        return computeScoreInternal();
    }

    public String getStatus() {
        int score = computeScoreInternal();
        if (score >= 80)
            return "STABLE";
        if (score >= 60)
            return "DEGRADED";
        return "AT_RISK";
    }

    public boolean isCriticalBroken() {
        for (String fb : fallbackDetails) {
            // fb has format: "Step → URL"
            String[] parts = fb.split("→");
            String step = parts.length > 0 ? parts[0].trim() : "";
            String url = parts.length > 1 ? parts[1].trim().toLowerCase() : "";
            // Exclude expected security redirect domains
            if (isExpectedExternalRedirect(step, url)) continue;
            if (isCriticalContext(step)) return true;
        }
        return false;
    }

    // ------------ getters ------------

    public int getTotalNavSteps() {
        return totalNavSteps;
    }

    public int getFallbackCount() {
        return fallbackCount;
    }

    public int getSlowPagesCount() {
        return slowPagesCount;
    }

    public int getJsErrorCount() {
        return jsErrorCount;
    }

    public int getThirdPartyAuthCount() {
        return thirdPartyAuthCount;
    }

    public int getWarningCount() {
        return warningCount;
    }

    public int getPenaltyPoints() {
        return penaltyPoints;
    }

    public List<String> getFallbackDetails() {
        return Collections.unmodifiableList(fallbackDetails);
    }

    public List<String> getSlowPagesDetails() {
        return Collections.unmodifiableList(slowPagesDetails);
    }

    public List<String> getJsErrorDetails() {
        return Collections.unmodifiableList(jsErrors);
    }

    public List<String> getThirdPartyAuthDetails() {
        return Collections.unmodifiableList(thirdPartyAuthDetails);
    }

    public List<String> getWarningsList() {
        return Collections.unmodifiableList(warnings);
    }

    // ------------ console report ------------

    private boolean isSuppressedWarningLabel(String label) {
        return containsSuppressedKeyword(label);
    }

    private boolean isSuppressedFallbackDetail(String detail) {
        if (detail == null) return false;
        String lower = detail.toLowerCase();
        // suppress fallback console lines for AI Automation specifically or other suppressed keywords
        return containsSuppressedKeyword(lower);
    }

    // Public helper so other classes (pages, tests) can check whether a particular message
    // should be suppressed from console output. Returns true if the text matches any
    // suppression keyword (case-insensitive).
    public boolean isSuppressedConsole(String text) {
        return containsSuppressedKeyword(text);
    }

    public void printReport() {
        int score = computeScoreInternal();
        String status = getStatus();

        LOG.info("\n========= APP HEALTH REPORT =========");
        LOG.info("Status         : {}", status);
        LOG.info("Score          : {}", score);
        LOG.info("Penalty Points : {}", penaltyPoints);
        LOG.info("Slow Pages     : {}", slowPagesCount);
        LOG.info("JS Errors      : {}", jsErrorCount);
        LOG.info("Fallbacks      : {}", fallbackCount);
        LOG.info("Warnings       : {}", warningCount);
        LOG.info("Critical Broken: {}", (isCriticalBroken() ? "YES" : "NO"));

        if (!fallbackDetails.isEmpty()) {
            LOG.info("\nFallback Details:");
            // Print fallbacks excluding expected external redirects (they have their own section)
            fallbackDetails.stream()
                    .filter(d -> !isSuppressedFallbackDetail(d))
                    .filter(d -> !isExpectedExternalRedirect(d.split(" → ")[0], d.contains("→") ? d.split(" → ")[1] : ""))
                    .forEach(x -> LOG.info(" - {}", sanitizeForConsole(x.split(" → ")[0], Severity.CRITICAL, x, x)));
        }
        if (!expectedExternalRedirectDetails.isEmpty()) {
            LOG.info("\nExpected External Redirects:");
            expectedExternalRedirectDetails.forEach(x -> LOG.info(" - {}", x));
        }
        // Do not print individual slow-page entries to the console to reduce noise.
        // Slow page details are written to `reports/trend/health_snapshot.json` and
        // rendered in the dashboard UI as a sortable, color-coded table.
        if (!slowPagesDetails.isEmpty()) {
            LOG.info("\nSlow Pages (summary): {} entries (details written to reports/trend/health_snapshot.json)", slowPagesDetails.size());
        }
        if (!jsErrors.isEmpty()) {
            LOG.info("\nJS Console Errors:");
            jsErrors.forEach(x -> LOG.info(" - {}", x.split("\\r?\\n")[0]));
        }
        if (!thirdPartyAuthDetails.isEmpty()) {
            LOG.info("\nThird-Party Auth Messages:");
            thirdPartyAuthDetails.forEach(x -> LOG.info(" - {}", x));
        }
        if (!warnings.isEmpty()) {
            LOG.info("\nWarnings List:");
            warnings.stream()
                    .filter(w -> !isSuppressedWarningLabel(w))
                    .forEach(x -> LOG.info(" - {}", formatForConsoleFromStoredLabel(x)));
        }
        LOG.info("====================================\n");

        // Also write a machine-readable snapshot for dashboard consumption
        try {
            JSONObject snap = toLatestJsonSnapshot();
            Path outDir = Paths.get("reports", "trend");
            Files.createDirectories(outDir);
            Path out = outDir.resolve("health_snapshot.json");
            Files.write(out, snap.toJSONString().getBytes(StandardCharsets.UTF_8));
            LOG.info("Wrote health snapshot for dashboard: {}", out.toAbsolutePath());
        } catch (Exception ex) {
            LOG.warn("Failed writing health snapshot for dashboard: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public JSONObject toLatestJsonSnapshot() {
        JSONObject json = new JSONObject();
        json.put("timestamp", System.currentTimeMillis());
        json.put("score", getScore());
        json.put("status", getStatus());
        json.put("penaltyPoints", penaltyPoints);
        json.put("totalNavSteps", totalNavSteps);
        json.put("fallbackCount", fallbackCount);
        json.put("slowPagesCount", slowPagesCount);
        json.put("jsErrorCount", jsErrorCount);
        json.put("thirdPartyAuthCount", thirdPartyAuthCount);
        json.put("warningCount", warningCount);
        json.put("criticalBroken", isCriticalBroken());

        JSONArray fb = new JSONArray();
        fb.addAll(fallbackDetails);
        JSONArray expected = new JSONArray();
        expected.addAll(expectedExternalRedirectDetails);
        JSONArray slow = new JSONArray();
        slow.addAll(slowPagesDetails);
        JSONArray js = new JSONArray();
        js.addAll(jsErrors);
        JSONArray third = new JSONArray();
        third.addAll(thirdPartyAuthDetails);
        JSONArray warn = new JSONArray();
        warn.addAll(warnings);

        json.put("fallbackDetails", fb);
        json.put("expectedExternalRedirects", expected);
        json.put("slowPagesDetails", slow);
        json.put("jsErrors", js);
        json.put("thirdPartyAuth", third);
        json.put("warnings", warn);

        return json;
    }

    public int getExpectedExternalRedirectCount() {
        return expectedExternalRedirectCount;
    }

    public List<String> getExpectedExternalRedirectDetails() {
        return Collections.unmodifiableList(expectedExternalRedirectDetails);
    }
}
