package discriminebot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Configuracion de Ollama con variables de entorno como prioridad. */
public record OllamaConfig(String host, String model, int timeoutSeconds, int maxHistoryMessages) {

    public static OllamaConfig load() {
        Path configPath = Path.of(getenvOrDefault("OLLAMA_CONFIG_FILE", "data/ollama.toml"));
        Map<String, String> fileValues = readOllamaSection(configPath);

        String host = firstNonBlank(
                Environment.get("OLLAMA_HOST", ""),
                fileValues.get("host"),
                "http://127.0.0.1:11434"
        );
        String model = firstNonBlank(
                Environment.get("OLLAMA_MODEL", ""),
                fileValues.get("model"),
                "llama3.2:latest"
        );
        int timeout = parsePositiveInt(getenvOrDefault("OLLAMA_TIMEOUT_SECONDS", "120"), 120);
        // El historial bruto es solo memoria inmediata y queda acotado. La
        // memoria duradera vive en registros extraidos, no en mensajes completos.
        boolean unlimitedMemory = Boolean.parseBoolean(getenvOrDefault("MEMORY_UNLIMITED", "true"));
        int maxHistory = unlimitedMemory ? 0
                : parseNonNegativeInt(getenvOrDefault("AI_MAX_HISTORY_MESSAGES", "2000"), 2000);

        return new OllamaConfig(host.replaceAll("/+$", ""), model, timeout, maxHistory);
    }

    private static Map<String, String> readOllamaSection(Path path) {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(path)) {
            return values;
        }

        try {
            List<String> lines = Files.readAllLines(path);
            boolean inOllamaSection = false;
            for (String line : lines) {
                String clean = line.trim();
                if (clean.isEmpty() || clean.startsWith("#")) continue;
                if (clean.startsWith("[") && clean.endsWith("]")) {
                    inOllamaSection = "[ollama]".equalsIgnoreCase(clean);
                    continue;
                }
                if (!inOllamaSection || !clean.contains("=")) continue;

                String[] parts = clean.split("=", 2);
                String key = parts[0].trim();
                String value = parts[1].trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            System.err.println("No se pudo leer la configuracion de Ollama " + path + ": " + e.getMessage());
        }
        return values;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String getenvOrDefault(String key, String fallback) {
        return Environment.get(key, fallback);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseNonNegativeInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
