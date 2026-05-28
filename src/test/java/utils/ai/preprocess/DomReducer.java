package utils.ai.preprocess;

public final class DomReducer {
    private static final int MAX_CHARS = 500;

    private DomReducer() {}

    public static String reduce(String dom) {
        if (dom == null || dom.isBlank()) return "";
        if (dom.length() <= MAX_CHARS) return dom;

        String lower = dom.toLowerCase();
        int relevantIdx = -1;

        // Look for markers that indicate the failing component area
        String[] markers = {"data-track", "data-testid", "data-test", "aria-label", "class=\"error", "class=\"btn", "type=\"submit"};
        for (String marker : markers) {
            int idx = lower.indexOf(marker);
            if (idx >= 0) {
                relevantIdx = idx;
                break;
            }
        }

        if (relevantIdx >= 0) {
            int start = Math.max(0, relevantIdx - 100);
            int end = Math.min(dom.length(), start + MAX_CHARS);
            return dom.substring(start, end);
        }

        // Fallback: last MAX_CHARS — usually contains the active component
        return dom.substring(dom.length() - MAX_CHARS);
    }
}
