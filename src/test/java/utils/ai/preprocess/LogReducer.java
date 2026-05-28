package utils.ai.preprocess;

public final class LogReducer {
    private static final int MAX_LINES = 20;

    private LogReducer() {}

    public static String reduce(String consoleLog) {
        if (consoleLog == null || consoleLog.isBlank()) return "";

        String[] lines = consoleLog.split("\n");
        StringBuilder sb = new StringBuilder();
        int kept = 0;

        // Scan from end, keep only relevant lines
        for (int i = lines.length - 1; i >= 0 && kept < MAX_LINES; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase();
            if (upper.contains("ERROR") || upper.contains("WARN") ||
                upper.contains("UNCAUGHT") || upper.contains("EXCEPTION") ||
                upper.contains("TYPEERROR") || upper.contains("REFERENCEERROR") ||
                upper.contains("SYNTAXERROR") || upper.contains("FAILED")) {
                sb.insert(0, line + "\n");
                kept++;
            }
        }

        // Fallback: last 10 lines if nothing matched
        if (kept == 0) {
            sb = new StringBuilder();
            int start = Math.max(0, lines.length - 10);
            for (int i = start; i < lines.length; i++) {
                if (!lines[i].isBlank()) sb.append(lines[i]).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
