package utils.ai.prompts;

public final class FailureAnalysisPrompt {

    private FailureAnalysisPrompt() {}

    public static String system() {
        return "You are an expert QA automation engineer. Analyze the provided test failure signals and return a JSON object only. " +
               "Use this exact schema: {\"failureType\":\"AUTOMATION_BUG|PRODUCT_BUG|ENVIRONMENT|TRANSIENT|TEST_ASSERTION\"," +
               "\"confidence\":0.0,\"rootCause\":\"one sentence\",\"suggestedFixes\":[\"fix1\",\"fix2\"],\"riskLevel\":\"LOW|MEDIUM|HIGH|CRITICAL\"}. " +
               "failureType definitions: " +
               "AUTOMATION_BUG=locator instability, wait timing, or test code issue; " +
               "PRODUCT_BUG=actual application defect; " +
               "ENVIRONMENT=infrastructure, network, or browser setup issue; " +
               "TRANSIENT=race condition or intermittent failure; " +
               "TEST_ASSERTION=assertion logic error in the test. " +
               "Return JSON only. No explanation text outside the JSON object.";
    }

    public static String user(String testId, String stacktrace, String url,
                               String consoleSnippet, String domSnippet) {
        StringBuilder sb = new StringBuilder();
        sb.append("Test ID: ").append(testId).append("\n");
        sb.append("URL at failure: ").append(url != null ? url : "unknown").append("\n");
        sb.append("Stacktrace:\n").append(stacktrace).append("\n");
        sb.append("Console logs (relevant lines):\n")
          .append(consoleSnippet != null ? consoleSnippet : "none").append("\n");
        sb.append("DOM context (failing component area):\n")
          .append(domSnippet != null ? domSnippet : "none");
        return sb.toString();
    }
}
