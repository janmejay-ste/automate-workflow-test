package utils.ai.preprocess;

public final class StacktraceReducer {
    private static final int MAX_FRAMES = 8;
    private static final int CAUSE_MAX_FRAMES = 4;

    private StacktraceReducer() {}

    public static String reduce(Throwable t) {
        if (t == null) return "";

        StringBuilder sb = new StringBuilder();

        // Root exception + message
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        sb.append("\n");

        // Top frames only
        StackTraceElement[] frames = t.getStackTrace();
        int limit = Math.min(MAX_FRAMES, frames.length);
        for (int i = 0; i < limit; i++) {
            sb.append("\tat ").append(frames[i]).append("\n");
        }

        // One level of cause chain
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("Caused by: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            sb.append("\n");
            StackTraceElement[] causeFrames = cause.getStackTrace();
            int causeLimit = Math.min(CAUSE_MAX_FRAMES, causeFrames.length);
            for (int i = 0; i < causeLimit; i++) {
                sb.append("\tat ").append(causeFrames[i]).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
