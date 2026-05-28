package utils;

import org.openqa.selenium.*;

/**
 * Classifies Selenium exceptions into retry categories.
 *
 * TRANSIENT — caused by timing, animation, or render lag. Retrying is correct.
 *   Examples: stale element, intercepted click, loader still animating.
 *
 * STRUCTURAL — reflects real product defect or missing element. Retrying hides the problem.
 *   Examples: no such element, assertion failure, auth error, missing selector.
 *
 * TimeoutException classification:
 *   Blanket STRUCTURAL is wrong. Timeouts have context:
 *   - Waiting for a loader to disappear → TRANSIENT (retry while page settles)
 *   - Waiting for a required element that was never rendered → STRUCTURAL
 *   Use classifyTimeout(TimeoutContext) when you know the wait's purpose.
 *   The exception-based classify(Throwable) inspects the message as a fallback.
 */
public final class RetryClassifier {

    public enum Category { TRANSIENT, STRUCTURAL }

    /**
     * Timeout context — callers supply this so the classifier knows whether a
     * timeout represents transient load lag or a genuinely absent element.
     */
    public enum TimeoutContext {
        /** Waiting for a loader/spinner to vanish. TRANSIENT — page is still settling. */
        LOADER_DISAPPEAR,
        /** Waiting for a stale element to stabilize before re-querying. TRANSIENT. */
        STALE_STABILIZE,
        /** Waiting for a render/animation to finish. TRANSIENT. */
        RENDER_COMPLETE,
        /** Waiting for a required functional element (button, input, card). STRUCTURAL. */
        REQUIRED_ELEMENT,
        /** Waiting for a page/URL transition to complete. STRUCTURAL if it never arrives. */
        NAVIGATION,
    }

    private RetryClassifier() {}

    /** Context-aware timeout classification — preferred over classify(Throwable) for timeouts. */
    public static Category classifyTimeout(TimeoutContext ctx) {
        return switch (ctx) {
            case LOADER_DISAPPEAR, STALE_STABILIZE, RENDER_COMPLETE -> Category.TRANSIENT;
            case REQUIRED_ELEMENT, NAVIGATION                       -> Category.STRUCTURAL;
        };
    }

    /**
     * Exception-based classification. For TimeoutException, inspects the message
     * for clues since the exception alone doesn't carry intent.
     */
    public static Category classify(Throwable t) {
        if (t == null) return Category.STRUCTURAL;

        // Assertion failures are always structural — retrying them is forbidden
        if (t instanceof AssertionError) return Category.STRUCTURAL;

        String cls = t.getClass().getSimpleName();
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";

        return switch (cls) {
            // Definitively transient: element state changed mid-interaction
            case "StaleElementReferenceException"   -> Category.TRANSIENT;
            case "ElementClickInterceptedException" -> Category.TRANSIENT;
            case "MoveTargetOutOfBoundsException"   -> Category.TRANSIENT;
            // ElementNotInteractableException: animation overlap (transient) or disabled (structural).
            // Treat as transient — the post-retry assertion catches permanent disability.
            case "ElementNotInteractableException"  -> Category.TRANSIENT;

            // Definitively structural
            case "NoSuchElementException"           -> Category.STRUCTURAL;
            case "UnreachableBrowserException"      -> Category.STRUCTURAL;
            case "SessionNotCreatedException"       -> Category.STRUCTURAL;

            // TimeoutException: context-aware via message inspection
            case "TimeoutException" -> {
                // Transient: timeout while waiting for UI to settle
                if (msg.contains("loader") || msg.contains("spinner") ||
                    msg.contains("animation") || msg.contains("render") ||
                    msg.contains("invisibility") || msg.contains("disappear")) {
                    yield Category.TRANSIENT;
                }
                // Structural: timeout waiting for a required element or navigation
                yield Category.STRUCTURAL;
            }

            // WebDriverException covers many root causes — inspect message
            case "WebDriverException" -> {
                if (msg.contains("stale")) yield Category.TRANSIENT;
                if (msg.contains("intercepted") || msg.contains("other element would receive")) {
                    yield Category.TRANSIENT;
                }
                yield Category.STRUCTURAL;
            }

            default -> {
                if (msg.contains("stale element") || msg.contains("element is not attached")) {
                    yield Category.TRANSIENT;
                }
                if (msg.contains("intercepted") || msg.contains("other element would receive")) {
                    yield Category.TRANSIENT;
                }
                yield Category.STRUCTURAL;
            }
        };
    }

    /**
     * Executes the given action with up to maxAttempts attempts.
     * TRANSIENT failures are retried after delayMs. STRUCTURAL failures propagate
     * immediately — retrying them would hide real breakage.
     */
    public static <T> T withRetry(int maxAttempts, long delayMs, RetryAction<T> action) throws Exception {
        Exception lastTransient = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.execute();
            } catch (AssertionError ae) {
                throw ae;
            } catch (Exception e) {
                if (classify(e) == Category.STRUCTURAL) throw e;
                lastTransient = e;
                if (attempt < maxAttempts) {
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                }
            }
        }
        throw lastTransient;
    }

    /** Void variant of withRetry. */
    public static void withRetryVoid(int maxAttempts, long delayMs, VoidAction action) throws Exception {
        withRetry(maxAttempts, delayMs, () -> { action.execute(); return null; });
    }

    @FunctionalInterface
    public interface RetryAction<T> { T execute() throws Exception; }

    @FunctionalInterface
    public interface VoidAction { void execute() throws Exception; }
}
