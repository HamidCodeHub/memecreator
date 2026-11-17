package com.memecreator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.memecreator.config.AiGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the AI gateway with an OpenAI-compatible chat completions request.
 * The response parser also accepts Anthropic-style ("content[].text") and
 * plain ("output_text" / "text") bodies so it works with most gateway shapes.
 */
@Component
public class AiGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayClient.class);

    private final RestClient restClient;
    private final AiGatewayProperties props;

    public AiGatewayClient(RestClient aiGatewayRestClient, AiGatewayProperties props) {
        this.restClient = aiGatewayRestClient;
        this.props = props;
    }

    public String chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("temperature", props.temperature());
        body.put("max_tokens", props.maxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (StringUtils.hasText(props.reasoningEffort())) {
            body.put("reasoning_effort", props.reasoningEffort());
        }
        if (props.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (props.providerOptions() != null && !props.providerOptions().isEmpty()) {
            body.put("provider_options", props.providerOptions());
        }

        long start = System.currentTimeMillis();
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(props.chatPath())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new AiGatewayException("AI gateway returned " + e.getStatusCode().value() + ": "
                    + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new AiGatewayException("Could not reach AI gateway at " + props.baseUrl() + props.chatPath()
                    + " (" + e.getMessage() + ")", e);
        }
        logUsage(response, System.currentTimeMillis() - start);

        String text = extractText(response);
        if (text == null || text.isBlank()) {
            String finish = response == null ? null : response.path("choices").path(0).path("finish_reason").asText(null);
            if ("length".equals(finish)) {
                throw new AiGatewayException("The model ran out of tokens before answering (finish_reason=length). "
                        + "Increase ai-gateway.max-tokens or lower ai-gateway.reasoning-effort.");
            }
            throw new AiGatewayException("AI gateway response had no text: " + response);
        }
        return text;
    }

    /** Raw model list from the gateway (GET {models-path}), returned as-is. */
    public JsonNode models() {
        try {
            return restClient.get().uri(props.modelsPath()).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new AiGatewayException("AI gateway returned " + e.getStatusCode().value() + " for "
                    + props.modelsPath() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new AiGatewayException("Could not reach AI gateway at " + props.baseUrl() + props.modelsPath()
                    + " (" + e.getMessage() + ")", e);
        }
    }

    /** Logs the token usage the gateway reports in the standard "usage" block, if present. */
    private void logUsage(JsonNode response, long millis) {
        JsonNode usage = response == null ? null : response.path("usage");
        String model = response == null ? props.model() : response.path("model").asText(props.model());
        boolean sameModel = model.equals(props.model()) || model.endsWith("/" + props.model())
                || props.model().endsWith("/" + model);
        if (!sameModel && response != null) {
            log.warn("Requested model '{}' but the gateway answered with '{}'. Gateway routing trace:",
                    props.model(), model);
            logRoutingTrace(response.path("routing"));
        }
        if (usage != null && usage.isObject()) {
            log.info("AI call: model={}, prompt_tokens={}, completion_tokens={}, total_tokens={}, {} ms",
                    model,
                    usage.path("prompt_tokens").asText("?"),
                    usage.path("completion_tokens").asText("?"),
                    usage.path("total_tokens").asText("?"),
                    millis);
        } else {
            log.info("AI call: model={}, {} ms (gateway reported no usage)", model, millis);
        }
    }

    /** Prints aiproxy's "routing" explanation: what was tried, what failed and why. */
    private void logRoutingTrace(JsonNode routing) {
        if (routing == null || routing.isMissingNode() || routing.isNull()) {
            log.warn("  (no routing trace in the response; call the gateway with ?trace=true to see it)");
            return;
        }
        for (JsonNode a : routing.path("attempts")) {
            log.warn("  attempt {}: {} / {} -> {} {} {}",
                    a.path("number").asText(""),
                    a.path("provider").asText(""),
                    a.path("model").asText(""),
                    a.path("outcome").asText(""),
                    field(a, "error_code", "errorCode"),
                    field(a, "error_message", "errorMessage"));
        }
        for (JsonNode r : routing.path("rejected")) {
            log.warn("  rejected: {} / {} -> {}", r.path("provider").asText(""), r.path("model").asText(""),
                    r.path("reasons"));
        }
        for (JsonNode w : routing.path("warnings")) {
            log.warn("  warning: {}", w.asText());
        }
    }

    private static String field(JsonNode node, String snake, String camel) {
        JsonNode v = node.has(snake) ? node.get(snake) : node.get(camel);
        return v == null || v.isNull() ? "" : v.asText();
    }

    static String extractText(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode openAi = root.path("choices").path(0).path("message").path("content");
        if (openAi.isTextual()) {
            return openAi.asText();
        }
        JsonNode content = root.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            content.forEach(part -> sb.append(part.path("text").asText("")));
            return sb.toString();
        }
        for (String field : List.of("output_text", "text", "response", "content")) {
            if (root.path(field).isTextual()) {
                return root.path(field).asText();
            }
        }
        return null;
    }
}
