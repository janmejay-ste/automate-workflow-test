package utils.health;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.HealthPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class HealthTracker {

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    private static final HealthTracker INSTANCE = new HealthTracker();
    @SuppressWarnings("unused")
    private final List<Integer> trendScores = new ArrayList<>();

    public static HealthTracker get() { return INSTANCE; }

    private int penaltyPoints = 0;
    private boolean criticalBroken = false;

    private final List<String> warnings = new ArrayList<>();
    private final List<String> fallbackDetails = new ArrayList<>();
    private final List<Map<String, String>> jsErrors = new ArrayList<>();

    private HealthTracker() {}

    /* ---------------- recording ---------------- */

    public void addWarning(String context, String message) {
        warnings.add(context + " - " + message);
        penaltyPoints += HealthPolicy.penalty(Severity.LOW, false);
    }

    public void warn(String message) {
        warnings.add(message);
        penaltyPoints += HealthPolicy.penalty(Severity.LOW, false);
    }

    public void recordFallback(String step, String url) {
        fallbackDetails.add(step + " → " + url);
    }

    public void recordJsError(String context, String message, boolean critical) {
        penaltyPoints += HealthPolicy.penalty(Severity.HIGH, critical);

        jsErrors.add(Map.of(
                "context", context,
                "message", firstLine(message)
        ));

        if (critical) {
            criticalBroken = true;
        }
    }

    /* ---------------- suppression ---------------- */

    public boolean isSuppressedConsole(String text) {
        if (text == null) return false;
        String s = text.toLowerCase();

        return s.contains("title unexpected")
                || s.contains("mcp server")
                || s.contains("fedcm")
                || s.contains("accounts.google.com")
                || s.contains("deprecated")
                || s.contains("clarity");
    }

    /* ---------------- derived ---------------- */

    public int getScore() {
        return Math.max(0, 100 - penaltyPoints);
    }

    public String getStatus() {
        int s = getScore();
        if (s >= HealthPolicy.STABLE_MIN) return "STABLE";
        if (s >= HealthPolicy.DEGRADED_MIN) return "DEGRADED";
        return "AT_RISK";
    }

    public boolean isCriticalBroken() {
        return criticalBroken;
    }

    /* ---------------- getters ---------------- */

    public List<String> getWarningsList() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getFallbackDetails() {
        return Collections.unmodifiableList(fallbackDetails);
    }

    public List<Map<String, String>> getJsErrors() {
        return Collections.unmodifiableList(jsErrors);
    }

    /* ---------------- reporting ---------------- */

    public void printReport() {
        System.out.println("\n========= HEALTH REPORT =========");
        System.out.println("Score      : " + getScore());
        System.out.println("Status     : " + getStatus());
        System.out.println("Warnings   : " + warnings.size());
        System.out.println("JS Errors  : " + jsErrors.size());
        System.out.println("Fallbacks  : " + fallbackDetails.size());
        System.out.println("================================\n");
    }

    /* ---------------- snapshot ---------------- */

    @SuppressWarnings("unchecked")
    public void writeSnapshot() {
        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis());
            root.put("score", getScore());
            root.put("status", getStatus());
            root.put("criticalBroken", criticalBroken);

            root.put("warnings", new JSONArray() {{ addAll(warnings); }});
            root.put("fallbacks", new JSONArray() {{ addAll(fallbackDetails); }});
            root.put("jsErrors", new JSONArray() {{ addAll(jsErrors); }});

            Path out = Paths.get("reports/trend/health_snapshot.json");
            Files.createDirectories(out.getParent());
            Files.write(out, root.toJSONString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // reporting must never break tests
        }
    }

    /* ---------------- helpers ---------------- */

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

  


    
  
}
