package discriminebot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Configuracion comun: entorno > propiedad Java > archivo local .env > valor por defecto. */
final class Environment {
    private static final Map<String, String> DOT_ENV = loadDotEnv(Path.of(".env"));

    private Environment() {
    }

    static String get(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = System.getProperty(key);
        if (value == null || value.isBlank()) value = DOT_ENV.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, String> loadDotEnv(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return values;
        try {
            for (String line : Files.readAllLines(path)) {
                String clean = line.trim();
                if (clean.isBlank() || clean.startsWith("#") || !clean.contains("=")) continue;
                if (clean.startsWith("export ")) clean = clean.substring(7).trim();
                String[] parts = clean.split("=", 2);
                String key = parts[0].trim();
                String value = parts[1].trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isBlank()) values.put(key, value);
            }
            return values;
        } catch (IOException error) {
            throw new IllegalStateException("No se pudo leer " + path + ": " + error.getMessage(), error);
        }
    }
}
