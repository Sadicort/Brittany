package discriminebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static discriminebot.MemoryModels.CollectiveMemory;
import static discriminebot.MemoryModels.ConversationProfile;
import static discriminebot.MemoryModels.Era;
import static discriminebot.MemoryModels.CulturalTrend;
import static discriminebot.MemoryModels.Generation;

/**
 * Almacenamiento append-only de memorias extraidas con indices en memoria.
 * Cada linea es un upsert/tombstone; la compactacion elimina versiones antiguas.
 */
final class MemoryStore {
    private static final int COMPACT_AFTER_WRITES = 5_000;
    private final Path directory;
    private final Path eventsFile;
    private final Path profilesFile;
    private final Path erasFile;
    private final Path trendsFile;
    private final Path generationsFile;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<String, CollectiveMemory> events = new LinkedHashMap<>();
    private final Map<String, ConversationProfile> profiles = new LinkedHashMap<>();
    private final Map<String, Era> eras = new LinkedHashMap<>();
    private final Map<String, CulturalTrend> trends = new LinkedHashMap<>();
    private final Map<String, Generation> generations = new LinkedHashMap<>();
    private final Map<String, Map<String, Set<String>>> eventTermIndex = new HashMap<>();
    private final Map<String, Set<String>> guildEventIndex = new HashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicLong eraSequence = new AtomicLong();
    private final AtomicLong trendSequence = new AtomicLong();
    private final AtomicLong generationSequence = new AtomicLong();
    private int writesSinceCompaction;

    MemoryStore(Path directory) {
        this.directory = directory;
        this.eventsFile = directory.resolve("collective-events.jsonl");
        this.profilesFile = directory.resolve("personal-profiles.jsonl");
        this.erasFile = directory.resolve("eras.jsonl");
        this.trendsFile = directory.resolve("cultural-trends.jsonl");
        this.generationsFile = directory.resolve("generations.jsonl");
        load();
    }

    synchronized String nextEventId() {
        return "E" + String.format(Locale.ROOT, "%06d", eventSequence.incrementAndGet());
    }

    synchronized String nextEraId() {
        return "ERA" + String.format(Locale.ROOT, "%04d", eraSequence.incrementAndGet());
    }

    synchronized String nextTrendId() {
        return "T" + String.format(Locale.ROOT, "%06d", trendSequence.incrementAndGet());
    }

    synchronized String nextGenerationId() {
        return "GEN" + String.format(Locale.ROOT, "%04d", generationSequence.incrementAndGet());
    }

    synchronized void saveEvent(CollectiveMemory memory) {
        memory.updatedAt = Instant.now().toString();
        CollectiveMemory previous = events.put(memory.id, memory);
        if (previous != null) removeFromEventIndex(previous);
        index(memory);
        append(eventsFile, "upsert", memory.id, gson.toJsonTree(memory));
        maybeCompact();
    }

    synchronized CollectiveMemory getEvent(String guildId, String id) {
        CollectiveMemory memory = events.get(id == null ? "" : id.toUpperCase(Locale.ROOT));
        return memory != null && memory.guildId.equals(guildId) ? memory : null;
    }

    synchronized CollectiveMemory getEventById(String id) {
        return events.get(id == null ? "" : id.toUpperCase(Locale.ROOT));
    }

    synchronized List<CollectiveMemory> listEvents(String guildId, int limit) {
        return guildEventIndex.getOrDefault(guildId, Set.of()).stream()
                .map(events::get)
                .filter(this::visible)
                .sorted(Comparator.comparingInt((CollectiveMemory item) -> item.relevance).reversed()
                        .thenComparing(item -> item.startedAt, Comparator.reverseOrder()))
                .limit(streamLimit(limit))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    synchronized List<CollectiveMemory> findEvents(String guildId, String query, int limit) {
        return findEvents(guildId, query, limit, null);
    }

    synchronized List<CollectiveMemory> findEvents(String guildId, String query, int limit, Set<String> readableChannelIds) {
        Set<String> terms = terms(query);
        if (terms.isEmpty()) return listEvents(guildId, limit, readableChannelIds);
        Map<String, Integer> scores = new HashMap<>();
        Map<String, Set<String>> index = eventTermIndex.getOrDefault(guildId, Map.of());
        for (String term : terms) {
            for (String id : index.getOrDefault(term, Set.of())) {
                scores.merge(id, 1, Integer::sum);
            }
        }
        return scores.entrySet().stream()
                .map(entry -> Map.entry(events.get(entry.getKey()), entry.getValue()))
                .filter(entry -> visible(entry.getKey()))
                .filter(entry -> readable(entry.getKey(), readableChannelIds))
                .sorted(Comparator.<Map.Entry<CollectiveMemory, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().relevance, Comparator.reverseOrder()))
                .limit(streamLimit(limit))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    synchronized List<CollectiveMemory> listEvents(String guildId, int limit, Set<String> readableChannelIds) {
        return guildEventIndex.getOrDefault(guildId, Set.of()).stream()
                .map(events::get)
                .filter(this::visible)
                .filter(memory -> readable(memory, readableChannelIds))
                .sorted(Comparator.comparingInt((CollectiveMemory item) -> item.relevance).reversed()
                        .thenComparing(item -> item.startedAt, Comparator.reverseOrder()))
                .limit(streamLimit(limit))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    synchronized boolean deleteEvent(String guildId, String id) {
        CollectiveMemory memory = getEvent(guildId, id);
        if (memory == null) return false;
        removeFromEventIndex(memory);
        events.remove(memory.id);
        append(eventsFile, "delete", memory.id, null);
        // La eliminacion administrativa borra tambien versiones anteriores.
        compact(eventsFile, events);
        return true;
    }

    synchronized ConversationProfile getProfile(String guildId, String userId) {
        return profiles.get(profileKey(guildId, userId));
    }

    synchronized ConversationProfile getOrCreateProfile(String guildId, String userId) {
        return profiles.computeIfAbsent(profileKey(guildId, userId), ignored -> {
            ConversationProfile profile = new ConversationProfile();
            profile.guildId = guildId;
            profile.userId = userId;
            return profile;
        });
    }

    synchronized void saveProfile(ConversationProfile profile) {
        profile.updatedAt = Instant.now().toString();
        String key = profileKey(profile.guildId, profile.userId);
        profiles.put(key, profile);
        append(profilesFile, "upsert", key, gson.toJsonTree(profile));
        maybeCompact();
    }

    synchronized void deleteProfile(String guildId, String userId, boolean blockLearning) {
        String key = profileKey(guildId, userId);
        profiles.remove(key);
        append(profilesFile, "delete", key, null);
        if (blockLearning) {
            ConversationProfile consentOnly = getOrCreateProfile(guildId, userId);
            consentOnly.learningEnabled = false;
            consentOnly.personalizationEnabled = false;
            saveProfile(consentOnly);
        }
        compactProfiles();
    }

    synchronized void saveEra(Era era) {
        era.updatedAt = Instant.now().toString();
        eras.put(era.id, era);
        append(erasFile, "upsert", era.id, gson.toJsonTree(era));
        maybeCompact();
    }

    synchronized boolean deleteEra(String guildId, String id) {
        Era era = getEra(guildId, id);
        if (era == null) return false;
        eras.remove(era.id);
        append(erasFile, "delete", era.id, null);
        compact(erasFile, eras);
        return true;
    }

    synchronized Era getEra(String guildId, String id) {
        Era era = eras.get(id == null ? "" : id.toUpperCase(Locale.ROOT));
        return era != null && era.guildId.equals(guildId) ? era : null;
    }

    synchronized List<Era> listEras(String guildId) {
        return eras.values().stream()
                .filter(era -> guildId.equals(era.guildId))
                .sorted(Comparator.comparing(era -> era.startedAt == null ? "" : era.startedAt))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    synchronized CulturalTrend getTrendByMarker(String guildId, String marker) {
        return trends.values().stream()
                .filter(item -> guildId.equals(item.guildId) && marker.equals(item.marker))
                .findFirst().orElse(null);
    }

    synchronized void saveTrend(CulturalTrend trend) {
        trend.updatedAt = Instant.now().toString();
        trends.put(trend.id, trend);
        append(trendsFile, "upsert", trend.id, gson.toJsonTree(trend));
        maybeCompact();
    }

    synchronized List<CulturalTrend> listTrends(String guildId, Set<String> readableChannelIds) {
        return trends.values().stream().filter(item -> guildId.equals(item.guildId))
                .filter(item -> readableChannelIds == null || readableChannelIds.containsAll(item.channelIds))
                .sorted(Comparator.comparingInt((CulturalTrend item) -> item.totalUses).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    synchronized void saveGeneration(Generation generation) {
        generation.updatedAt = Instant.now().toString();
        generations.put(generation.id, generation);
        append(generationsFile, "upsert", generation.id, gson.toJsonTree(generation));
        maybeCompact();
    }

    synchronized List<Generation> listGenerations(String guildId) {
        return generations.values().stream().filter(item -> guildId.equals(item.guildId))
                .sorted(Comparator.comparing(item -> item.startedAt == null ? "" : item.startedAt))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    synchronized Collection<CollectiveMemory> allGuildEvents(String guildId) {
        return new ArrayList<>(guildEventIndex.getOrDefault(guildId, Set.of()).stream()
                .map(events::get).filter(this::visible).toList());
    }

    synchronized void flush() {
        compact(eventsFile, events);
        compact(profilesFile, profiles);
        compact(erasFile, eras);
        compact(trendsFile, trends);
        compact(generationsFile, generations);
        writesSinceCompaction = 0;
    }

    private boolean visible(CollectiveMemory memory) {
        return memory != null && memory.privacy == MemoryModels.PrivacyLevel.PUBLIC
                && memory.status != MemoryModels.MemoryStatus.DELETED
                && memory.status != MemoryModels.MemoryStatus.IRRELEVANT;
    }

    private boolean readable(CollectiveMemory memory, Set<String> readableChannelIds) {
        if (readableChannelIds == null) return true;
        Set<String> sourceIds = memory.sources == null || memory.sources.isEmpty()
                ? Set.of(memory.channelId == null ? "" : memory.channelId)
                : memory.sources.keySet();
        return sourceIds.stream().filter(id -> !id.isBlank()).allMatch(readableChannelIds::contains);
    }

    private void load() {
        try {
            Files.createDirectories(directory);
            loadRecords(eventsFile, (operation, id, data) -> {
                if ("delete".equals(operation)) events.remove(id);
                else events.put(id, gson.fromJson(data, CollectiveMemory.class));
            });
            loadRecords(profilesFile, (operation, id, data) -> {
                if ("delete".equals(operation)) profiles.remove(id);
                else profiles.put(id, gson.fromJson(data, ConversationProfile.class));
            });
            loadRecords(erasFile, (operation, id, data) -> {
                if ("delete".equals(operation)) eras.remove(id);
                else eras.put(id, gson.fromJson(data, Era.class));
            });
            loadRecords(trendsFile, (operation, id, data) -> {
                if ("delete".equals(operation)) trends.remove(id);
                else trends.put(id, gson.fromJson(data, CulturalTrend.class));
            });
            loadRecords(generationsFile, (operation, id, data) -> {
                if ("delete".equals(operation)) generations.remove(id);
                else generations.put(id, gson.fromJson(data, Generation.class));
            });
            events.values().forEach(memory -> {
                index(memory);
                eventSequence.set(Math.max(eventSequence.get(), numericSuffix(memory.id)));
            });
            eras.values().forEach(era -> eraSequence.set(Math.max(eraSequence.get(), numericSuffix(era.id))));
            trends.values().forEach(item -> trendSequence.set(Math.max(trendSequence.get(), numericSuffix(item.id))));
            generations.values().forEach(item -> generationSequence.set(Math.max(generationSequence.get(), numericSuffix(item.id))));
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo iniciar la memoria en " + directory, e);
        }
    }

    private void loadRecords(Path path, RecordConsumer consumer) throws IOException {
        if (!Files.exists(path)) return;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                    consumer.accept(record.get("operation").getAsString(), record.get("id").getAsString(), record.get("data"));
                } catch (RuntimeException malformedTail) {
                    System.err.println("Registro de memoria ignorado en " + path + ": " + malformedTail.getMessage());
                }
            }
        }
    }

    private void append(Path path, String operation, String id, Object data) {
        JsonObject record = new JsonObject();
        record.addProperty("operation", operation);
        record.addProperty("id", id);
        record.addProperty("at", Instant.now().toString());
        if (data != null) record.add("data", (com.google.gson.JsonElement) data);
        try {
            Files.writeString(path, gson.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writesSinceCompaction++;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo persistir la memoria en " + path, e);
        }
    }

    private void maybeCompact() {
        if (writesSinceCompaction < COMPACT_AFTER_WRITES) return;
        compact(eventsFile, events);
        compact(profilesFile, profiles);
        compact(erasFile, eras);
        compact(trendsFile, trends);
        compact(generationsFile, generations);
        writesSinceCompaction = 0;
    }

    private void compactProfiles() {
        compact(profilesFile, profiles);
    }

    private void compact(Path path, Map<String, ?> current) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, ?> entry : current.entrySet()) {
            JsonObject record = new JsonObject();
            record.addProperty("operation", "upsert");
            record.addProperty("id", entry.getKey());
            record.addProperty("at", Instant.now().toString());
            record.add("data", gson.toJsonTree(entry.getValue()));
            output.append(gson.toJson(record)).append(System.lineSeparator());
        }
        try {
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo compactar " + path, e);
        }
    }

    private void index(CollectiveMemory memory) {
        if (memory == null) return;
        guildEventIndex.computeIfAbsent(memory.guildId, ignored -> new LinkedHashSet<>()).add(memory.id);
        Set<String> indexedTerms = new LinkedHashSet<>();
        indexedTerms.addAll(terms(memory.title));
        indexedTerms.addAll(terms(memory.summary));
        memory.tags.forEach(tag -> indexedTerms.addAll(terms(tag)));
        Map<String, Set<String>> guildTerms = eventTermIndex.computeIfAbsent(memory.guildId, ignored -> new HashMap<>());
        indexedTerms.forEach(term -> guildTerms.computeIfAbsent(term, ignored -> new LinkedHashSet<>()).add(memory.id));
    }

    private void removeFromEventIndex(CollectiveMemory memory) {
        Set<String> guildIds = guildEventIndex.get(memory.guildId);
        if (guildIds != null) guildIds.remove(memory.id);
        Map<String, Set<String>> indexedTerms = eventTermIndex.get(memory.guildId);
        if (indexedTerms != null) indexedTerms.values().forEach(ids -> ids.remove(memory.id));
    }

    static Set<String> terms(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (term.length() >= 3 && !STOP_WORDS.contains(term)) result.add(term);
        }
        return result;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "que", "como", "con", "por", "para", "una", "uno", "unos", "unas", "del", "las", "los",
            "esto", "esta", "este", "fue", "son", "hay", "aqui", "desde", "sobre", "donde", "cuando"
    );

    private static String profileKey(String guildId, String userId) {
        return guildId + ":" + userId;
    }

    private static long numericSuffix(String id) {
        if (id == null) return 0;
        String digits = id.replaceAll("\\D+", "");
        try {
            return digits.isBlank() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long streamLimit(int requested) {
        return requested <= 0 ? Long.MAX_VALUE : requested;
    }

    @FunctionalInterface
    private interface RecordConsumer {
        void accept(String operation, String id, com.google.gson.JsonElement data);
    }
}
