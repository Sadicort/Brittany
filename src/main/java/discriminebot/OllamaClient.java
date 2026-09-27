package discriminebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Cliente minimo para el endpoint /api/chat de Ollama. */
public final class OllamaClient {
    private final OllamaConfig config;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public OllamaClient(OllamaConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(config.timeoutSeconds(), 30)))
                .build();
    }

    public CompletableFuture<String> chat(List<ConversationMemoryStore.StoredMessage> messages) {
        return chat(messages, 0.7);
    }

    public CompletableFuture<String> chat(List<ConversationMemoryStore.StoredMessage> messages, double temperature) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.add("messages", gson.toJsonTree(messages.stream()
                .map(message -> new ChatMessage(message.role(), message.content()))
                .toList()));
        body.addProperty("stream", false);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", Math.max(0.0, Math.min(1.0, temperature)));
        body.add("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.host() + "/api/chat"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parseResponse(response.statusCode(), response.body()));
    }

    private String parseResponse(int statusCode, String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (statusCode < 200 || statusCode >= 300) {
                String error = root.has("error") ? root.get("error").getAsString() : responseBody;
                throw new IllegalStateException("Ollama respondio HTTP " + statusCode + ": " + error);
            }
            JsonObject message = root.getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                throw new IllegalStateException("La respuesta de Ollama no contiene message.content");
            }
            String content = message.get("content").getAsString().trim();
            if (content.isBlank()) {
                throw new IllegalStateException("Ollama devolvio una respuesta vacia");
            }
            return content;
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IllegalStateException("Respuesta invalida de Ollama: " + e.getMessage(), e);
        }
    }

    private record ChatMessage(String role, String content) {
    }
}
