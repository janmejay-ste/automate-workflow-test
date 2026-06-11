package utils.health;

import org.testng.Assert;
import utils.HealthPolicy;

/**
 * Enforces health score thresholds at suite teardown.
 *
 * <h2>Phase A.5.1 + A.5.2 — Advisory mode + kill switches</h2>
 *
 * <p>This gate runs in one of three modes, selected by system properties:</p>
 * <ul>
 *   <li>{@code -DhealthGate.mode=advisory}  — DEFAULT — computes the decision,
 *       logs verbosely, but never throws. The {@code wouldHaveBlocked} flag in
 *       the snapshot records what blocking mode <em>would</em> have done so the
 *       team can calibrate before flipping the switch.</li>
 *   <li>{@code -DhealthGate.mode=blocking} — opt-in once trust is earned;
 *       failures throw {@code AssertionError} via TestNG.</li>
 *   <li>{@code -DhealthGate.disabled=true} — gate is a complete no-op
 *       regardless of mode. Use only when the gate logic itself is suspect.</li>
 * </ul>
 *
 * <p>Two other kill switches affect adjacent components, surfaced here for
 * documentation only (read by HealthTracker / writeSnapshot):</p>
 * <ul>
 *   <li>{@code -DhealthSnapshot.disabled=true} — snapshot file not written.</li>
 *   <li>{@code -DhealthTracker.disabled=true}  — tracker becomes no-op
 *       (every public method short-circuits).</li>
 * </ul>
 *
 * <h2>Why advisory is the default</h2>
 * <p>A confidence-logic bug or threshold mismatch in blocking mode halts every
 * release. In advisory mode it produces a misclassification you can
 * investigate without anyone yelling at you. The default protects the team
 * during the validation window.</p>
 *
 * <h2>Snapshot contract</h2>
 * <p>Regardless of mode, this gate produces an "enforcement" block consumed
 * by {@code HealthTracker.writeSnapshot} (via {@link #lastDecision()}):</p>
 * <pre>
 * "enforcement": {
 *   "mode":                    "advisory" | "blocking",
 *   "reasonIfNotEnforcing":    "...",
 *   "wouldHaveBlocked":        true | false,
 *   "actuallyBlocked":         true | false,
 *   "violations":              ["...", ...]
 * }
 * </pre>
 */
public final class HealthGate {

    public enum Mode { ADVISORY, BLOCKING }

    private static volatile EnforcementOutcome LAST = null;

    /**
     * Phase A.5.3 — most-recent unrecognized {@code -DhealthGate.mode=...} value.
     * Stays null unless an operator typed a non-{advisory|blocking} string. Surfaced
     * in {@code platformHealth.governance} so the dashboard can flag "you set a mode
     * the gate didn't understand and silently fell back to advisory".
     */
    private static volatile String LAST_INVALID_MODE_INPUT = null;

    private HealthGate() {}

    /**
     * Resolves the active mode from system properties. Default is ADVISORY.
     *
     * <p>Phase A.5.3 — unrecognized values (e.g. {@code -DhealthGate.mode=blcoking}
     * with a typo) now log a WARN and are recorded in {@link #lastInvalidModeInput()}
     * before falling back to ADVISORY. Previously this fall-through was silent,
     * which meant an operator who thought they'd enabled blocking would not know
     * the request was ignored.
     */
    public static Mode resolveMode() {
        if (Boolean.parseBoolean(System.getProperty("healthGate.disabled", "false"))) {
            return Mode.ADVISORY;   // disabled-on-top is treated as advisory below
        }
        String raw = System.getProperty("healthGate.mode", "advisory");
        if ("blocking".equalsIgnoreCase(raw)) return Mode.BLOCKING;
        if ("advisory".equalsIgnoreCase(raw)) return Mode.ADVISORY;

        // Unrecognized value — record + WARN once per distinct value, then fall back.
        if (raw != null && !raw.isBlank() && !raw.equals(LAST_INVALID_MODE_INPUT)) {
            LAST_INVALID_MODE_INPUT = raw;
            System.err.printf(
                    "[HealthGate] WARN: -DhealthGate.mode=%s is not recognized; "
                  + "falling back to ADVISORY. Valid values: advisory, blocking.%n",
                    raw);
        }
        return Mode.ADVISORY;
    }

    /**
     * Phase A.5.3 — returns the most-recent unrecognized {@code -DhealthGate.mode}
     * value seen in this JVM, or {@code null} if all invocations used valid values.
     */
    public static String lastInvalidModeInput() { return LAST_INVALID_MODE_INPUT; }

    /** True if the kill switch is set. Gate becomes a complete no-op. */
    public static boolean isKillSwitchActive() {
        return Boolean.parseBoolean(System.getProperty("healthGate.disabled", "false"));
    }

    /**
     * The most recent enforcement result. {@code HealthTracker.writeSnapshot}
     * reads this to emit the "enforcement" block in the snapshot. Returns
     * {@code null} if {@link #enforce} has not run in this JVM yet.
     */
    public static EnforcementOutcome lastDecision() {
        return LAST;
    }

    /**
     * Computes the gate decision and records it in {@link #lastDecision()} so the
     * snapshot writer can read it. Never throws — even in blocking mode. Use this
     * BEFORE {@code writeSnapshot} to ensure the snapshot contains the real
     * enforcement outcome rather than {@code GATE_NOT_YET_RUN}.
     *
     * <p>Phase A.5.1 split — separates decision-recording from build-failing
     * so the persisted snapshot is always complete regardless of mode.</p>
     */
    public static EnforcementOutcome evaluate(HealthTracker tracker) {
        compute(tracker, /* throwIfBlocking */ false);
        return LAST;
    }

    /**
     * Calls {@link #evaluate} if needed, then throws AssertionError if the gate
     * is in blocking mode and violations exist. Call this from a {@code finally}
     * block after all reporting has flushed.
     */
    public static void enforce(HealthTracker tracker) {
        compute(tracker, /* throwIfBlocking */ true);
    }

    private static void compute(HealthTracker tracker, boolean throwIfBlocking) {
        Mode mode = resolveMode();
        boolean killed = isKillSwitchActive();

        int score    = tracker.getScore();
        int smoothed = tracker.getSmoothedScore();
        String status = tracker.getStatus();

        System.out.printf("[HealthGate] Score: %d (smoothed: %d) | Status: %s | Mode: %s%s%n",
                score, smoothed, status, mode,
                killed ? " | KILL-SWITCH-ACTIVE" : "");

        // Compute violations the same way regardless of mode — the mode only
        // controls what we do with them.
        java.util.List<String> violations = new java.util.ArrayList<>();

        if (tracker.hasCriticalFlowFailures()) {
            StringBuilder sb = new StringBuilder("Critical business flows failed (score irrelevant when these break):");
            tracker.getCriticalFlowFailures().forEach(f -> sb.append("\n      * ").append(f));
            violations.add(sb.toString());
        }

        if (tracker.isCriticalBroken() && !tracker.hasCriticalFlowFailures()) {
            violations.add("Critical runtime error: uncaught JS exception or fatal API failure");
        }

        if (smoothed < HealthPolicy.BUILD_FAIL_THRESHOLD) {
            violations.add(String.format("Smoothed health score %d is below build threshold %d",
                    smoothed, HealthPolicy.BUILD_FAIL_THRESHOLD));
        }

        boolean wouldHaveBlocked = !violations.isEmpty();
        boolean actuallyBlocked  = false;
        String  reasonIfNotEnforcing = null;

        if (killed) {
            reasonIfNotEnforcing = "KILL_SWITCH_ACTIVE — -DhealthGate.disabled=true";
        } else if (mode == Mode.ADVISORY) {
            reasonIfNotEnforcing = "ADVISORY_MODE — set -DhealthGate.mode=blocking to enforce";
        }

        if (wouldHaveBlocked) {
            System.err.println("[HealthGate] " + (mode == Mode.BLOCKING && !killed
                    ? "BUILD WILL FAIL"
                    : "WOULD-HAVE-BLOCKED (advisory)") + " — violations:");
            for (String v : violations) System.err.println("  - " + v);
        }

        // Record the outcome BEFORE any throw so the snapshot writer can read it
        // in any case (success, advisory wouldHaveBlocked, or blocking failure).
        LAST = new EnforcementOutcome(
                mode.name().toLowerCase(),
                reasonIfNotEnforcing,
                wouldHaveBlocked,
                /* actuallyBlocked set below */ false,
                java.util.Collections.unmodifiableList(violations),
                killed);

        if (wouldHaveBlocked && mode == Mode.BLOCKING && !killed && throwIfBlocking) {
            LAST = LAST.withActuallyBlocked(true);
            Assert.fail("[HealthGate] BUILD FAILED — health constraints violated:\n  " +
                    String.join("\n  ", violations) +
                    "\n  Fix test failures, eliminate JS errors, or improve page stability to raise the score.");
        }

        if (!wouldHaveBlocked && throwIfBlocking) {
            System.out.printf("[HealthGate] PASSED — smoothed score %d >= threshold %d%n",
                    smoothed, HealthPolicy.BUILD_FAIL_THRESHOLD);
        }
    }

    /**
     * Immutable snapshot of the most-recent enforcement decision.
     * Consumed by {@code HealthTracker.writeSnapshot} for the "enforcement" block.
     */
    public record EnforcementOutcome(
            String  mode,
            String  reasonIfNotEnforcing,
            boolean wouldHaveBlocked,
            boolean actuallyBlocked,
            java.util.List<String> violations,
            boolean killSwitchActive) {

        EnforcementOutcome withActuallyBlocked(boolean v) {
            return new EnforcementOutcome(mode, reasonIfNotEnforcing, wouldHaveBlocked, v, violations, killSwitchActive);
        }

        @SuppressWarnings("unchecked")
        public org.json.simple.JSONObject toJson() {
            org.json.simple.JSONObject o = new org.json.simple.JSONObject();
            o.put("mode", mode);
            if (reasonIfNotEnforcing != null) o.put("reasonIfNotEnforcing", reasonIfNotEnforcing);
            o.put("wouldHaveBlocked", wouldHaveBlocked);
            o.put("actuallyBlocked",  actuallyBlocked);
            o.put("killSwitchActive", killSwitchActive);
            org.json.simple.JSONArray v = new org.json.simple.JSONArray();
            v.addAll(violations);
            o.put("violations", v);
            return o;
        }
    }
}
