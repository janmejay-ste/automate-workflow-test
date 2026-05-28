package utils.ai.client;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class AiClient {
    private static final Logger LOG = LoggerFactory.getLogger(AiClient.class);
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private AiClient() {}

    /**
     * Sends a chat completion request to OpenAI with json_object response format.
     * Handles retries, token tracking, and graceful degradation.
     * Returns parsed JSON content, or null on any failure.
     */
    @SuppressWarnings("unchecked")
    public static JSONObject chat(String model, String systemPrompt, String userContent) {
        if (!AiConfig.isEnabled()) return null;

        long estimatedTokens = estimateTokens(systemPrompt, userContent);
        if (!AiRateLimiter.allowRequest(estimatedTokens)) return null;

        String requestJson = buildRequestJson(model, systemPrompt, userContent);

        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) {
                AiRetryPolicy.sleepBeforeRetry(attempt - 1);
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT))
                        .header("Authorization", "Bearer " + AiConfig.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .timeout(Duration.ofSeconds(AiConfig.getTimeoutSeconds()))
                        .build();

                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (AiRetryPolicy.isRetryable(status)) {
                    if (attempt < AiRetryPolicy.maxRetries()) continue;
                    LOG.warn("OpenAI failed after {} retries: HTTP {}", AiRetryPolicy.maxRetries(), status);
                    return null;
                }

                if (status != 200) {
                    LOG.warn("OpenAI returned non-retryable HTTP {}", status);
                    return null;
                }

                return parseResponse(response.body());

            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < AiRetryPolicy.maxRetries()) continue;
                LOG.warn("OpenAI request timed out after retries");
                return null;
            } catch (Exception e) {
                LOG.warn("OpenAI call failed: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String buildRequestJson(String model, String systemPrompt, String userContent) {
        JSONObject body = new JSONObject();
        body.put("model", model);

        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        body.put("response_format", responseFormat);

        JSONArray messages = new JSONArray();

        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        messages.add(userMsg);

        body.put("messages", messages);
        return body.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private static JSONObject parseResponse(String responseBody) throws Exception {
        JSONObject parsed = (JSONObject) new JSONParser().parse(responseBody);

        // Extract and record actual token usage
        JSONObject usage = (JSONObject) parsed.get("usage");
        if (usage != null) {
            long totalTokens = ((Number) usage.get("total_tokens")).longValue();
            AiRateLimiter.recordUsage(totalTokens);
        }

        // Extract message content
        JSONArray choices = (JSONArray) parsed.get("choices");
        if (choices == null || choices.isEmpty()) return null;

        JSONObject firstChoice = (JSONObject) choices.get(0);
        JSONObject message = (JSONObject) firstChoice.get("message");
        String content = (String) message.get("content");

        return (JSONObject) new JSONParser().parse(content);
    }

    private static long estimateTokens(String system, String user) {
        // Rough estimate: 1 token ≈ 4 chars, +500 buffer for response
        return (system.length() + user.length()) / 4L + 500;
    }
}
