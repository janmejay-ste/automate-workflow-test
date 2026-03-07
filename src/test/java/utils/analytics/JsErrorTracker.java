package utils.analytics;

import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deduplicates and tracks JS errors originating from the product.
 * Filters out 3rd-party noise based on config.
 */
public class JsErrorTracker {
    private static final Logger LOG = LoggerFactory.getLogger(JsErrorTracker.class);
    private static final JsErrorTracker INSTANCE = new JsErrorTracker();
    private static final String IGNORE_FILE = "config/js_error_ignore.json";

    private final List<String> ignoreList = new ArrayList<>();

    // Error Unique Key (Msg + Stack snippet) -> Info
    private final Map<String, ErrorInfo> productErrors = new ConcurrentHashMap<>();
    private final Map<String, ErrorInfo> suppressedErrors = new ConcurrentHashMap<>();

    private final boolean auditMode;

    private JsErrorTracker() {
        this.auditMode = Boolean.parseBoolean(System.getenv("QA_INTELLIGENCE_AUDIT"));
        loadIgnoreList();
    }

    public static JsErrorTracker get() {
        return INSTANCE;
    }

    private void loadIgnoreList() {
        try {
            if (Files.exists(Paths.get(IGNORE_FILE))) {
                String content = Files.readString(Paths.get(IGNORE_FILE));
                JSONArray arr = (JSONArray) new JSONParser().parse(content);
                for (Object o : arr) {
                    ignoreList.add(o.toString().toLowerCase());
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not load JS error ignore list: {}", e.getMessage());
        }
    }

    public void recordError(String context, String message, String stack) {
        if (message == null)
            return;

        String key = generateKey(message, stack);
        boolean ignored = isIgnored(message, stack);

        if (ignored) {
            suppressedErrors.computeIfAbsent(key, k -> new ErrorInfo(message, stack, true))
                    .addOccurrence(context);
        } else {
            productErrors.computeIfAbsent(key, k -> new ErrorInfo(message, stack, false))
                    .addOccurrence(context);
        }
    }

    private boolean isIgnored(String message, String stack) {
        String m = message.toLowerCase();
        String s = (stack != null) ? stack.toLowerCase() : "";

        for (String ignore : ignoreList) {
            if (m.contains(ignore) || s.contains(ignore))
                return true;
        }
        return false;
    }

    private String generateKey(String message, String stack) {
        // Simple key: message + first line of stack
        String firstLine = (stack != null && stack.contains("\n")) ? stack.substring(0, stack.indexOf('\n')) : stack;
        return message + "|" + firstLine;
    }

    public Map<String, ErrorInfo> getProductErrors() {
        return productErrors;
    }

    public Map<String, ErrorInfo> getSuppressedErrors() {
        return suppressedErrors;
    }

    public boolean isAuditMode() {
        return auditMode;
    }

    public static class ErrorInfo {
        public final String message;
        public final String stack;
        public final boolean isSuppressed;
        public final AtomicInteger count = new AtomicInteger(0);
        public final Set<String> affectedPages = Collections.synchronizedSet(new HashSet<>());

        public ErrorInfo(String message, String stack, boolean isSuppressed) {
            this.message = message;
            this.stack = stack;
            this.isSuppressed = isSuppressed;
        }

        public void addOccurrence(String page) {
            count.incrementAndGet();
            if (page != null)
                affectedPages.add(page);
        }
    }
}
