package discriminebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Historial persistente append-only por conversacion. Puede ser ilimitado.
 *
 * La clave de conversacion es el servidor/canal de Discord, de modo que el bot
 * puede recordar el contexto despues de reiniciarse sin mezclar canales.
 */
public final class ConversationMemoryStore {
    private static final Type FILE_TYPE = new TypeToken<Map<String, List<StoredMessage>>>() { }.getType();

    private final Path filePath;
    private final int maxMessages;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Gson jsonlGson = new GsonBuilder().disableHtmlEscaping().create();
    private final Object lock = new Object();
    private final Map<String, List<StoredMessage>> conversations = new LinkedHashMap<>();

    public ConversationMemoryStore(Path filePath, int maxMessages) {
        this.filePath = filePath;
        // Cero significa memoria persistente ilimitada. El historial que se
        // manda a Ollama se limita por separado para no desbordar su contexto.
        this.maxMessages = maxMessages <= 0 ? 0 : Math.max(4, maxMessages);
        load();
    }

    public List<StoredMessage> append(String conversationId, String role, String content) {
        if (content == null || content.isBlank()) {
            return get(conversationId);
        }

        synchronized (lock) {
            List<StoredMessage> messages = conversations.computeIfAbsent(
                    conversationId,
                    ignored -> new ArrayList<>()
            );
            StoredMessage stored = new StoredMessage(role, content.trim(), Instant.now().toString());
            messages.add(stored);
            boolean trimmed = trim(messages);
            if (trimmed) rewriteJsonl();
            else appendRecord(new MessageRecord(conversationId, stored.role(), stored.content(), stored.at()));
            return copyOf(messages);
        }
    }

    public List<StoredMessage> get(String conversationId) {
        synchronized (lock) {
            return copyOf(conversations.getOrDefault(conversationId, List.of()));
        }
    }

    /**
     * Devuelve los mensajes recientes y recupera mensajes antiguos que tengan
     * palabras relacionadas con la consulta actual. Todo sigue guardado en
     * disco; este filtro solo evita enviar miles de mensajes a Ollama de una vez.
     */
    public List<StoredMessage> getForPrompt(
            String conversationId,
            String query,
            int recentMessages,
            int relevantOlderMessages
    ) {
        synchronized (lock) {
            List<StoredMessage> allMessages = conversations.getOrDefault(conversationId, List.of());
            if (allMessages.isEmpty()) return List.of();

            int recentLimit = Math.max(1, recentMessages);
            int relevantLimit = Math.max(0, relevantOlderMessages);
            if (allMessages.size() <= recentLimit || relevantLimit == 0) {
                return new ArrayList<>(allMessages.subList(
                        Math.max(0, allMessages.size() - recentLimit), allMessages.size()));
            }

            Set<String> terms = searchTerms(query);
            Set<Integer> selectedIndexes = new LinkedHashSet<>();
            int firstRecent = Math.max(0, allMessages.size() - recentLimit);
            for (int index = firstRecent; index < allMessages.size(); index++) {
                selectedIndexes.add(index);
            }

            if (!terms.isEmpty()) {
                List<ScoredMessage> candidates = new ArrayList<>();
                for (int index = 0; index < firstRecent; index++) {
                    StoredMessage message = allMessages.get(index);
                    int score = score(message.content(), terms);
                    if (score > 0) {
                        candidates.add(new ScoredMessage(index, score));
                    }
                }
                candidates.sort((left, right) -> {
                    int scoreOrder = Integer.compare(right.score(), left.score());
                    return scoreOrder != 0
                            ? scoreOrder
                            : Integer.compare(left.index(), right.index());
                });
                candidates.stream()
                        .limit(relevantLimit)
                        .forEach(candidate -> selectedIndexes.add(candidate.index()));
            }

            return selectedIndexes.stream()
                    .sorted()
                    .map(allMessages::get)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private boolean trim(List<StoredMessage> messages) {
        if (maxMessages == 0) return false;
        int excess = messages.size() - maxMessages;
        if (excess > 0) {
            messages.subList(0, excess).clear();
            return true;
        }
        return false;
    }

    private List<StoredMessage> copyOf(List<StoredMessage> messages) {
        return new ArrayList<>(messages);
    }

    private static Set<String> searchTerms(String query) {
        if (query == null || query.isBlank()) return Set.of();
        return List.of(query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .stream()
                .filter(term -> term.length() >= 4)
                .filter(term -> !STOP_WORDS.contains(term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static int score(String content, Set<String> terms) {
        String normalized = content.toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (normalized.contains(term)) score++;
        }
        return score;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "para", "como", "esta", "este", "esto", "desde", "sobre", "tiene", "tengo",
            "quiero", "puedes", "puede", "donde", "cuando", "hablamos", "recuerda", "recordar"
    );

    private void load() {
        synchronized (lock) {
            try {
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (!Files.exists(filePath)) {
                    return;
                }

                String serialized = Files.readString(filePath, StandardCharsets.UTF_8);
                if (serialized.isBlank()) return;
                if (loadLegacyJson(serialized)) {
                    rewriteJsonl();
                    return;
                }
                try (var reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        MessageRecord record = jsonlGson.fromJson(line, MessageRecord.class);
                        if (record == null || record.conversationId() == null || record.content() == null) continue;
                        List<StoredMessage> messages = conversations.computeIfAbsent(record.conversationId(), ignored -> new ArrayList<>());
                        messages.add(new StoredMessage(record.role(), record.content(), record.at()));
                        trim(messages);
                    }
                }
            } catch (IOException | JsonParseException e) {
                System.err.println("No se pudo cargar la memoria de IA en " + filePath + ": " + e.getMessage());
            }
        }
    }

    private boolean loadLegacyJson(String serialized) {
        try {
            Map<String, List<StoredMessage>> loaded = gson.fromJson(serialized, FILE_TYPE);
            if (loaded == null) return false;
            loaded.forEach((key, value) -> {
                List<StoredMessage> messages = value == null ? new ArrayList<>() : new ArrayList<>(value);
                trim(messages);
                conversations.put(key, messages);
            });
            return true;
        } catch (RuntimeException notLegacyJson) {
            conversations.clear();
            return false;
        }
    }

    private void appendRecord(MessageRecord record) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(filePath, jsonlGson.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar la memoria de IA en " + filePath, e);
        }
    }

    private void rewriteJsonl() {
        try {
            Path parent = filePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporaryFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, List<StoredMessage>> entry : conversations.entrySet()) {
                    for (StoredMessage message : entry.getValue()) {
                        writer.write(jsonlGson.toJson(new MessageRecord(entry.getKey(), message.role(), message.content(), message.at())));
                        writer.write(System.lineSeparator());
                    }
                }
            }

            try {
                Files.move(temporaryFile, filePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar la memoria de IA en " + filePath, e);
        }
    }

    public record StoredMessage(String role, String content, String at) {
    }

    private record MessageRecord(String conversationId, String role, String content, String at) {
    }

    private record ScoredMessage(int index, int score) {
    }
}
