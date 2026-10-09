package io.github.freecoreagent.llm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Handles multi-turn LLM completions using standard OpenAI Chat Completion protocol.
 */
public final class LlmClient {
    private final JavaPlugin plugin;

    public record ChatMessage(String role, String content) {}

    public LlmClient(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<String> completeMessagesAsync(String systemPrompt, List<ChatMessage> history, String currentUserMessage) {
        return CompletableFuture.supplyAsync(() -> {
            String apiKey = plugin.getConfig().getString("llm.api-key", "");
            String baseUrl = plugin.getConfig().getString("llm.base-url", "https://api.deepseek.com/v1");
            String model = plugin.getConfig().getString("llm.model", "deepseek-chat");
            double temp = plugin.getConfig().getDouble("llm.temperature", 0.8);
            int maxTokens = plugin.getConfig().getInt("llm.max-tokens", 500);
            int timeoutSeconds = plugin.getConfig().getInt("llm.timeout-seconds", 15);

            if (apiKey.isBlank() || apiKey.equals("YOUR_API_KEY_HERE")) {
                plugin.getLogger().fine("LLM API key is not configured in FreeCoreAgent/config.yml (Using central CoreNyan daemon).");
                return null;
            }

            try {
                String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
                URL url = URI.create(endpoint).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(timeoutSeconds * 1000);
                conn.setReadTimeout(timeoutSeconds * 1000);
                conn.setDoOutput(true);

                // Build JSON messages array with history
                StringBuilder messagesJson = new StringBuilder("[");
                messagesJson.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(systemPrompt)).append("\"}");

                if (history != null) {
                    for (ChatMessage msg : history) {
                        messagesJson.append(",{\"role\":\"").append(escapeJson(msg.role())).append("\",\"content\":\"").append(escapeJson(msg.content())).append("\"}");
                    }
                }

                if (currentUserMessage != null && !currentUserMessage.isBlank()) {
                    messagesJson.append(",{\"role\":\"user\",\"content\":\"").append(escapeJson(currentUserMessage)).append("\"}");
                }
                messagesJson.append("]");

                String payload = "{"
                        + "\"model\":\"" + escapeJson(model) + "\","
                        + "\"temperature\":" + temp + ","
                        + "\"max_tokens\":" + maxTokens + ","
                        + "\"messages\":" + messagesJson
                        + "}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder errSb = new StringBuilder();
                        String line;
                        while ((line = err.readLine()) != null) errSb.append(line);
                        plugin.getLogger().warning("LLM API returned HTTP " + code + ": " + errSb);
                    }
                    return null;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder responseSb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) responseSb.append(line);
                    return extractContent(responseSb.toString());
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error calling LLM API: " + e.getMessage());
                return null;
            }
        });
    }

    private static String extractContent(String json) {
        // Strictly parse message.content only! NEVER expose internal reasoning_content / CoT to players!
        int contentIdx = json.indexOf("\"content\":");
        if (contentIdx >= 0) {
            int startQuote = json.indexOf("\"", contentIdx + 10);
            if (startQuote >= 0) {
                StringBuilder sb = new StringBuilder();
                boolean escape = false;
                for (int i = startQuote + 1; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (escape) {
                        if (c == 'n') sb.append('\n');
                        else if (c == 'r') sb.append('\r');
                        else if (c == 't') sb.append('\t');
                        else if (c == '"') sb.append('"');
                        else if (c == '\\') sb.append('\\');
                        else sb.append(c);
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == '"') {
                        break;
                    } else {
                        sb.append(c);
                    }
                }
                String res = sb.toString().trim();
                if (!res.isBlank()) return res;
            }
        }
        return null;
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 32) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
