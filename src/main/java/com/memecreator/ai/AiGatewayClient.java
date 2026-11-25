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

import java.util.ArrayList;
import java.util.Base64;
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

    /** Text-only caption call with the caption model (ai-gateway.model, default auto/caption). */
    public String chat(String systemPrompt, String userPrompt) {
        return send(props.model(), props.maxTokens(), props.reasoningEffort(), props.providerOptions(),
                systemPrompt, userPrompt, "ai-gateway.max-tokens");
    }

    /**
     * Photo-meme call with an image, using the vision model (ai-gateway.vision-model, default auto/vision).
     *
     * @param imageDataUri the image as {@code data:image/jpeg;base64,...}
     */
    public String chatWithImage(String systemPrompt, String userPrompt, String imageDataUri) {
        if (!StringUtils.hasText(props.visionModel())) {
            throw new IllegalStateException("No vision model configured: set ai-gateway.vision-model "
                    + "(a model that can read images, e.g. groq/qwen/qwen3.8-27b)");
        }
        return send(props.visionModel(), props.visionMaxTokens(), null, props.visionProviderOptions(),
                systemPrompt, visionContent(userPrompt, imageDataUri, props.imageFormat()), "ai-gateway.vision-max-tokens");
    }

    /**
     * The user message of a photo-meme call: the prompt, then the photo as a data URI in the shape
     * ai-gateway.image-format names (aiproxy: {"type":"image","url":...}; it rejects OpenAI's "image_url" part).
     * Never an http link: aiproxy's Gemini adapter rejects image URLs.
     */
    static List<Map<String, Object>> visionContent(String userPrompt, String imageDataUri,
                                                   AiGatewayProperties.ImageFormat format) {
        if (imageDataUri == null || !imageDataUri.startsWith("data:image/") || !imageDataUri.contains(";base64,")) {
            throw new IllegalArgumentException("Photo memes need the image as a data URI (data:image/jpeg;base64,...)");
        }
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", userPrompt);
        return List.of(text, imagePart(imageDataUri, format));
    }

    /**
     * Image editing with the image model (ai-gateway.image-model): a prompt plus input images in, one image out.
     * For Gemini models the call asks for text and image output (generationConfig.responseModalities), passed
     * through aiproxy's provider_options.
     *
     * @param imageDataUris the input images as {@code data:image/jpeg;base64,...}, in the order the prompt names them
     * @return the bytes of the image the model returned (PNG or JPEG)
     */
    public byte[] editImage(String prompt, List<String> imageDataUris) {
        String model = props.imageModel();
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("No image model configured: set ai-gateway.image-model "
                    + "(a model that edits images, e.g. gemini/gemini-2.5-flash-image)");
        }
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", prompt);
        content.add(text);
        for (String uri : imageDataUris) {
            content.add(imagePart(uri, props.imageFormat()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        Map<String, Object> options = new LinkedHashMap<>();
        if (props.imageProviderOptions() != null) {
            options.putAll(props.imageProviderOptions());
        }
        String provider = model.contains("/") ? model.substring(0, model.indexOf('/')) : "";
        if (provider.equals("gemini") && !options.containsKey("gemini")) {
            // aiproxy merges provider_options into the payload it sends to Gemini
            options.put("gemini", Map.of("generationConfig", Map.of("responseModalities", List.of("TEXT", "IMAGE"))));
        }
        if (!options.isEmpty()) {
            body.put("provider_options", options);
        }

        long start = System.currentTimeMillis();
        JsonNode response = post(body);
        logUsage(model, response, System.currentTimeMillis() - start);
        rejectMockFallback(model, response);
        String uri = extractImage(response);
        if (uri == null) {
            String said = extractText(response);
            throw new AiGatewayException("The image model answered without an image"
                    + (said == null || said.isBlank() ? "" : ": " + abbreviate(said))
                    + ". Check that the gateway passes image parts back (see README, 'AI face replacement').");
        }
        if (!uri.startsWith("data:")) {
            throw new AiGatewayException("The image model returned an image link instead of the image itself");
        }
        int comma = uri.indexOf(',');
        try {
            return Base64.getDecoder().decode(uri.substring(comma + 1).replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new AiGatewayException("The image model returned an unreadable image", e);
        }
    }

    private static Map<String, Object> imagePart(String dataUri, AiGatewayProperties.ImageFormat format) {
        Map<String, Object> image = new LinkedHashMap<>();
        if (format == AiGatewayProperties.ImageFormat.OPENAI) {
            image.put("type", "image_url");
            image.put("image_url", Map.of("url", dataUri));
        } else {
            image.put("type", "image");
            image.put("url", dataUri);
        }
        return image;
    }

    private JsonNode post(Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(props.chatPath())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new AiGatewayException("AI gateway returned " + e.getStatusCode().value() + ": "
                    + abbreviate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new AiGatewayException("Could not reach AI gateway at " + props.baseUrl() + props.chatPath()
                    + " (" + e.getMessage() + ")", e);
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /**
     * The first image in an answer, as a data URI or link: aiproxy / Anthropic-style content parts
     * ({"type":"image","url":...}), OpenAI-style ({"type":"image_url","image_url":{"url":...}}), or an
     * OpenRouter-style "images" list on the message.
     */
    static String extractImage(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode message = root.path("choices").path(0).path("message");
        for (JsonNode list : List.of(message.path("content"), message.path("images"), root.path("content"))) {
            if (!list.isArray()) {
                continue;
            }
            for (JsonNode part : list) {
                String type = part.path("type").asText("");
                if (!type.startsWith("image")) {
                    continue;
                }
                for (JsonNode url : List.of(part.path("url"), part.path("image_url").path("url"), part.path("image_url"))) {
                    if (url.isTextual() && !url.asText().isBlank()) {
                        return url.asText();
                    }
                }
            }
        }
        return null;
    }

    private String send(String model, int maxTokens, String reasoningEffort,
                        Map<String, Map<String, String>> providerOptions,
                        String systemPrompt, Object userContent, String maxTokensProperty) {
        Map<String, Object> body = chatBody(model, props.temperature(), maxTokens, reasoningEffort, providerOptions,
                systemPrompt, userContent);
        if (log.isDebugEnabled()) {
            log.debug("AI request: model={}, estimated input tokens={} (text chars / 4, images not counted), max_tokens={}",
                    model, estimateInputTokens(systemPrompt, userContent), maxTokens);
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
        logUsage(model, response, System.currentTimeMillis() - start);
        rejectMockFallback(model, response);

        String text = extractText(response);
        if (text == null || text.isBlank()) {
            String finish = response == null ? null : response.path("choices").path(0).path("finish_reason").asText(null);
            if ("length".equals(finish)) {
                throw new AiGatewayException("The model ran out of tokens before answering (finish_reason=length). "
                        + "Increase " + maxTokensProperty + " or lower the reasoning effort.");
            }
            throw new AiGatewayException("AI gateway response had no text: " + response);
        }
        return text;
    }

    /**
     * The chat completions body for caption and photo-meme calls. Always has an explicit "max_tokens" (Groq's free
     * tier counts prompt + max_tokens against its tokens-per-minute limit, so a large default gets every call
     * rejected) and "response_format" json_object: every caller parses the reply as JSON. The prompts still ask
     * for JSON too, which OpenAI-style JSON mode requires.
     */
    static Map<String, Object> chatBody(String model, double temperature, int maxTokens, String reasoningEffort,
                                        Map<String, Map<String, String>> providerOptions,
                                        String systemPrompt, Object userContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));
        if (StringUtils.hasText(reasoningEffort)) {
            body.put("reasoning_effort", reasoningEffort);
        }
        body.put("response_format", Map.of("type", "json_object"));
        if (providerOptions != null && !providerOptions.isEmpty()) {
            body.put("provider_options", providerOptions);
        }
        return body;
    }

    /**
     * Rough input size of a request: the characters of the system prompt and the user's text parts, divided by 4
     * (about one token per 4 characters of English). Image parts are not counted.
     */
    static int estimateInputTokens(String systemPrompt, Object userContent) {
        long chars = systemPrompt == null ? 0 : systemPrompt.length();
        if (userContent instanceof String text) {
            chars += text.length();
        } else if (userContent instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> m && "text".equals(m.get("type")) && m.get("text") instanceof String t) {
                    chars += t.length();
                }
            }
        }
        return (int) ((chars + 3) / 4);
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

    /**
     * aiproxy falls back to its mock models when no real one is available; their answers are placeholders, so a
     * meme made from them would make no sense. Treated as a failure, unless a mock model was asked for on purpose.
     */
    static void rejectMockFallback(String requested, JsonNode response) {
        if (response == null || isMock(requested)) {
            return;
        }
        String answered = response.path("model").asText("");
        if (isMock(answered)) {
            throw new AiGatewayException("Requested model '" + requested + "' but the gateway answered with the mock "
                    + "model '" + answered + "' (no real provider was available)");
        }
    }

    private static boolean isMock(String model) {
        return model != null && (model.startsWith("mock/") || model.startsWith("mock-"));
    }

    /** Logs the token usage the gateway reports in the standard "usage" block, if present. */
    private void logUsage(String requested, JsonNode response, long millis) {
        JsonNode usage = response == null ? null : response.path("usage");
        String model = response == null ? requested : response.path("model").asText(requested);
        boolean sameModel = model.equals(requested) || model.endsWith("/" + requested)
                || requested.endsWith("/" + model);
        if (requested.startsWith("auto/")) {
            // a virtual model: aiproxy chose the real one, that's the point
            log.debug("Virtual model '{}' answered by '{}'", requested, model);
        } else if (!sameModel && response != null) {
            log.warn("Requested model '{}' but the gateway answered with '{}'. Gateway routing trace:",
                    requested, model);
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
        if (openAi.isArray()) {   // content parts, e.g. text next to an image
            StringBuilder sb = new StringBuilder();
            openAi.forEach(part -> {
                if (part.isTextual()) {
                    sb.append(part.asText());
                } else if ("text".equals(part.path("type").asText())) {
                    sb.append(part.path("text").asText(""));
                }
            });
            return sb.toString();
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
