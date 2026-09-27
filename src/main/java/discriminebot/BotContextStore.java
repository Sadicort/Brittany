package discriminebot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

public class BotContextStore {
    private final Path filePath;
    private final Properties properties = new Properties();
    private final Object lock = new Object();

    public BotContextStore(Path filePath) {
        this.filePath = filePath;
        load();
    }

    public void set(String key, String value) {
        synchronized (lock) {
            properties.setProperty(key, value);
            save();
        }
    }

    public String get(String key, String defaultValue) {
        synchronized (lock) {
            return properties.getProperty(key, defaultValue);
        }
    }

    public long incrementCounter(String key) {
        synchronized (lock) {
            long current = parseLong(properties.getProperty(key), 0L);
            long updated = current + 1;
            properties.setProperty(key, Long.toString(updated));
            save();
            return updated;
        }
    }

    private void load() {
        synchronized (lock) {
            try {
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (Files.exists(filePath)) {
                    try (InputStream in = Files.newInputStream(filePath)) {
                        properties.load(in);
                    }
                } else {
                    properties.setProperty("context.created_at", Instant.now().toString());
                    save();
                }
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo cargar el contexto del bot: " + filePath, e);
            }
        }
    }

    private void save() {
        try (OutputStream out = Files.newOutputStream(filePath)) {
            properties.setProperty("context.updated_at", Instant.now().toString());
            properties.store(out, "DISCRIMINE bot context");
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el contexto del bot: " + filePath, e);
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
