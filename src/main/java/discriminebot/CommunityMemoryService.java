package discriminebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static discriminebot.MemoryModels.CollectiveMemory;
import static discriminebot.MemoryModels.ConversationProfile;
import static discriminebot.MemoryModels.Era;
import static discriminebot.MemoryModels.MemoryLayer;
import static discriminebot.MemoryModels.MemoryStatus;
import static discriminebot.MemoryModels.Preference;
import static discriminebot.MemoryModels.CulturalTrend;
import static discriminebot.MemoryModels.Generation;
import static discriminebot.MemoryModels.MemoryRelation;
import static discriminebot.MemoryModels.MemorySource;
import static discriminebot.MemoryModels.RelationType;
import static discriminebot.MemoryModels.TrendStatus;
import static discriminebot.MemoryModels.ConversationDigest;
import static discriminebot.MemoryModels.SessionDigest;
import static discriminebot.MemoryModels.MemoryFact;

/**
 * Capa adicional alrededor de la conversacion existente. Extrae senales utiles,
 * descarta el texto bruto y mantiene separadas la memoria publica y la personal.
 */
public final class CommunityMemoryService implements AutoCloseable {
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:([A-Za-z0-9_]{2,32}):\\d+>");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[ .-]?){8,15}(?!\\d)");
    private static final Pattern TOKEN = Pattern.compile("(?i)(password|contrasena|contraseña|token|api[_ -]?key|clave privada|direccion|diagnostico|tarjeta|cvv)");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final Set<String> HISTORICAL_INTENT = Set.of(
            "historia", "historico", "historica", "antes", "recuerdan", "recuerdo", "paso", "ocurrio",
            "meme", "emoji", "veteranos", "epoca", "era", "origen", "aquella", "nuevo", "año", "ano"
    );

    private final MemoryStore store;
    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ActivityWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, TrendCandidate> trendCandidates = new LinkedHashMap<>();
    private final Map<String, Deque<RecentSignal>> liveGuildContext = new ConcurrentHashMap<>();
    private final Duration eventGap;
    private final int minEventMessages;
    private final int minEventParticipants;
    private final boolean enabled;
    private final boolean semanticExtractionEnabled;
    private final boolean unlimitedMemory;
    private final OllamaClient semanticClient;
    private volatile boolean closed;

    public CommunityMemoryService() {
        this.store = new MemoryStore(java.nio.file.Path.of(env("MEMORY_DIRECTORY", "data/memory")));
        this.eventGap = Duration.ofMinutes(intEnv("MEMORY_EVENT_GAP_MINUTES", 20, 5, 240));
        this.minEventMessages = intEnv("MEMORY_EVENT_MIN_MESSAGES", 12, 4, 10_000);
        this.minEventParticipants = intEnv("MEMORY_EVENT_MIN_PARTICIPANTS", 3, 2, 10_000);
        this.enabled = boolEnv("MEMORY_ENABLED", true);
        this.semanticExtractionEnabled = boolEnv("MEMORY_SEMANTIC_EXTRACTION", true);
        this.unlimitedMemory = boolEnv("MEMORY_UNLIMITED", true);
        this.semanticClient = new OllamaClient(OllamaConfig.load());
        this.worker = Executors.newSingleThreadExecutor(r -> daemon(r, "discrimine-memory"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "discrimine-memory-consolidator"));
        scheduler.scheduleAtFixedRate(() -> submit(this::finalizeInactiveWindows), 1, 1, TimeUnit.MINUTES);
    }

    public void observe(MessageReceivedEvent event, String content) {
        if (!enabled || closed || event.getAuthor().isBot() || content == null || content.isBlank()) return;
        Observation observation = new Observation(
                event.isFromGuild() ? event.getGuild().getId() : "dm",
                event.isFromGuild(),
                event.getChannel().getId(),
                safeChannelName(event),
                event.getAuthor().getId(),
                unlimitedMemory ? content : limit(content, 1_000),
                Instant.now()
        );
        submit(() -> process(observation));
    }

    /** Contexto pequeno y ya autorizado que se anade al prompt del usuario actual. */
    public String contextFor(String guildId, String userId, String query) {
        return contextFor(guildId, userId, query, null);
    }

    public String contextFor(MessageReceivedEvent event, String query) {
        return contextFor(event.isFromGuild() ? event.getGuild().getId() : "dm", event.getAuthor().getId(),
                query, readableChannels(event));
    }

    String contextFor(String guildId, String userId, String query, Set<String> readableChannelIds) {
        if (!enabled) return "";
        StringBuilder context = new StringBuilder("--- MEMORIA RECUPERADA Y AUTORIZADA ---\n");
        ConversationProfile profile = store.getProfile(guildId, userId);
        if (profile != null && profile.personalizationEnabled && !profile.preferences.isEmpty()) {
            context.append("Perfil conversacional PERSONAL del usuario actual (no revelar a terceros): ");
            profile.preferences.forEach((name, preference) -> context.append(name)
                    .append('=')
                    .append(String.format(Locale.ROOT, "%.2f", preference.value))
                    .append(" (confianza ")
                    .append(String.format(Locale.ROOT, "%.2f", preference.confidence))
                    .append(preference.explicit ? ", explicita" : ", inferida")
                    .append("); "));
            context.append("familiaridad=")
                    .append(String.format(Locale.ROOT, "%.2f", profile.familiarity)).append(".\n");
            context.append("Interpretacion: valores cercanos a 1 aumentan ese estilo; brevity=1 pide respuestas cortas.\n");
        }

        boolean historicalIntent = shouldRetrieveHistory(query);
        List<CollectiveMemory> collective = historicalIntent
                ? store.findEvents(guildId, query, 5, readableChannelIds)
                : store.findEvents(guildId, query, 2, readableChannelIds);
        if (collective.isEmpty() && historicalIntent) collective = store.listEvents(guildId, 5, readableChannelIds);
        if (!collective.isEmpty()) {
            context.append("Memoria colectiva PUBLICA relevante:\n");
            for (CollectiveMemory memory : collective) {
                context.append("- ").append(memory.id).append(" | ").append(memory.title)
                        .append(" | ").append(memory.summary)
                        .append(" | fecha ").append(memory.startedAt)
                        .append(" | fuentes ").append(sourceNames(memory))
                        .append(" | relevancia ").append(memory.relevance).append("/100\n");
                if (memory.interpretation != null && !memory.interpretation.isBlank()) {
                    context.append("  INTERPRETACION (no hecho, confianza ")
                            .append(String.format(Locale.ROOT, "%.2f", memory.interpretationConfidence))
                            .append("): ").append(memory.interpretation).append('\n');
                }
                if (memory.facts != null && !memory.facts.isEmpty()) {
                    context.append("  HECHOS ESTRUCTURADOS:\n");
                    memory.facts.stream().limit(8).forEach(fact -> context.append("  * ").append(fact.subject)
                            .append(' ').append(fact.predicate).append(' ').append(fact.object)
                            .append(" (confianza ").append(String.format(Locale.ROOT, "%.2f", fact.confidence))
                            .append(", fuente <#").append(fact.sourceChannelId).append(">)\n"));
                }
            }
        }
        appendLiveCrossChannelContext(context, guildId, query, readableChannelIds);
        context.append("Usa solo estos datos como recuerdos. No inventes hechos ni perfiles. ")
                .append("Nunca reveles memoria personal de otra persona. Si no hay datos suficientes, dilo.");
        return context.toString();
    }

    public void recordCompletedInteraction(String guildId, String userId, String userText, String answer) {
        if (!enabled || closed) return;
        submit(() -> {
            ConversationProfile profile = store.getProfile(guildId, userId);
            if (profile == null || !profile.learningEnabled) return;
            if (looksHumorous(userText) && looksHumorous(answer)) {
                profile.sharedHumor = evolve(profile.sharedHumor, 1.0, 0.05);
            }
            profile.familiarity = Math.min(1.0, Math.log1p(profile.interactions) / Math.log(500));
            if (profile.interactions % 10 == 0) store.saveProfile(profile);
        });
    }

    private void process(Observation observation) {
        learnProfile(observation);
        if (!observation.guildMessage) return; // Los DM nunca alimentan la memoria colectiva.
        rememberLive(observation);
        observeCulturalSignals(observation);
        updateHistoricalReferences(observation);

        String key = observation.guildId + ":" + observation.channelId;
        ActivityWindow window = windows.get(key);
        if (window != null && Duration.between(window.lastAt, observation.at).compareTo(eventGap) > 0) {
            finalizeWindow(window);
            windows.remove(key);
            window = null;
        }
        if (window == null) {
            window = new ActivityWindow(observation.guildId, observation.channelId, observation.channelName, observation.at);
            windows.put(key, window);
        }
        window.accept(observation);
    }

    private void learnProfile(Observation observation) {
        ConversationProfile profile = store.getProfile(observation.guildId, observation.userId);
        if (profile != null && !profile.learningEnabled) return;
        if (profile == null) profile = store.getOrCreateProfile(observation.guildId, observation.userId);
        if (profile.channelActivity == null) profile.channelActivity = new LinkedHashMap<>();
        if (profile.topicActivity == null) profile.topicActivity = new LinkedHashMap<>();
        if (profile.milestones == null) profile.milestones = new ArrayList<>();
        profile.interactions++;
        if (profile.firstSeenAt == null) {
            profile.firstSeenAt = observation.at.toString();
            profile.milestones.add("Se unio a la memoria de la comunidad el " + observation.at.toString());
            if (observation.guildMessage) assignGeneration(profile, observation.guildId, observation.channelId, observation.at);
        }
        profile.lastSeenAt = observation.at.toString();
        if (observation.guildMessage) profile.channelActivity.merge(observation.channelId, 1, Integer::sum);

        boolean sensitive = isProhibited(observation.content);
        boolean changed = false;
        if (!sensitive) {
            String text = normalize(observation.content);
            changed |= explicitPreference(profile, "brevity", text,
                    List.of("no me gustan las respuestas largas", "respuestas cortas", "ve al grano", "se breve", "sé breve"), 1.0);
            changed |= explicitPreference(profile, "brevity", text,
                    List.of("prefiero respuestas largas", "respuestas detalladas", "explicame con detalle", "explícame con detalle"), 0.0);
            changed |= explicitPreference(profile, "humor", text,
                    List.of("me gusta el humor", "puedes bromear", "hazme reir", "hazme reír"), 1.0);
            changed |= explicitPreference(profile, "humor", text,
                    List.of("sin bromas", "no me gusta el humor", "habla en serio"), 0.0);
            changed |= explicitPreference(profile, "sarcasm", text,
                    List.of("me gusta el sarcasmo", "puedes ser sarcastica", "puedes ser sarcástica"), 1.0);
            changed |= explicitPreference(profile, "sarcasm", text,
                    List.of("sin sarcasmo", "no uses sarcasmo", "no me gusta el sarcasmo"), 0.0);
            changed |= explicitPreference(profile, "technical_detail", text,
                    List.of("prefiero explicaciones tecnicas", "prefiero explicaciones técnicas", "explica tecnicamente", "explica técnicamente"), 1.0);
            changed |= explicitPreference(profile, "formality", text,
                    List.of("hablame formal", "háblame formal", "prefiero un tono formal"), 1.0);
            changed |= explicitPreference(profile, "formality", text,
                    List.of("hablame casual", "háblame casual", "no seas formal"), 0.0);

            // Senales observadas pesan poco y nunca sustituyen una preferencia explicita.
            if (text.contains("explica") && (text.contains("detalle") || text.contains("paso a paso"))) {
                changed |= inferredPreference(profile, "technical_detail", 0.85);
                changed |= inferredPreference(profile, "brevity", 0.2);
            } else if (text.contains("rapido") || text.contains("rápido") || text.contains("resumen")) {
                changed |= inferredPreference(profile, "brevity", 0.8);
            }
            if (looksHumorous(text)) changed |= inferredPreference(profile, "humor", 0.75);
            for (String topic : significantTerms(text)) profile.topicActivity.merge(topic, 1, Integer::sum);
        }
        profile.familiarity = Math.min(1.0, Math.log1p(profile.interactions) / Math.log(500));
        if (changed || profile.interactions == 1 || profile.interactions % 20 == 0) store.saveProfile(profile);
    }

    private boolean explicitPreference(ConversationProfile profile, String key, String text, List<String> phrases, double value) {
        if (phrases.stream().noneMatch(text::contains)) return false;
        profile.preferences.put(key, new Preference(value, 0.98, true));
        return true;
    }

    private boolean inferredPreference(ConversationProfile profile, String key, double observation) {
        Preference current = profile.preferences.get(key);
        if (current != null && current.explicit && current.confidence >= 0.80) return false;
        double value = current == null ? observation : evolve(current.value, observation, 0.15);
        double confidence = current == null ? 0.35 : Math.min(0.70, current.confidence + 0.02);
        profile.preferences.put(key, new Preference(value, confidence, false));
        return true;
    }

    private void updateHistoricalReferences(Observation observation) {
        String normalized = normalize(observation.content);
        boolean referenceLanguage = normalized.contains("otra vez") || normalized.contains("de nuevo")
                || normalized.contains("como antes") || normalized.contains("recuerdan")
                || normalized.contains("se acuerdan");
        if (!referenceLanguage) return;
        for (CollectiveMemory memory : store.findEvents(observation.guildId, normalized, 2)) {
            Instant last = parseInstant(memory.lastReferenceAt);
            if (last != null && Duration.between(last, observation.at).toHours() < 6) continue;
            memory.futureReferences++;
            memory.lastReferenceAt = observation.at.toString();
            memory.relevance = Math.min(100, memory.relevance + 2);
            if (memory.relevance >= 70) memory.layers.add(MemoryLayer.HISTORICAL);
            store.saveEvent(memory);
        }
    }

    private void finalizeInactiveWindows() {
        Instant cutoff = Instant.now().minus(eventGap);
        List<String> completed = windows.entrySet().stream()
                .filter(entry -> entry.getValue().lastAt.isBefore(cutoff))
                .map(Map.Entry::getKey).toList();
        for (String key : completed) {
            finalizeWindow(windows.remove(key));
        }
    }

    private void finalizeWindow(ActivityWindow window) {
        if (window == null) return;
        if (window.messageCount < minEventMessages || window.participantIds.size() < minEventParticipants) return;

        CollectiveMemory memory = new CollectiveMemory();
        memory.id = store.nextEventId();
        memory.guildId = window.guildId;
        memory.channelId = window.channelId;
        memory.sources.put(window.channelId, new MemorySource(window.channelId, window.channelName,
                window.messageCount, window.firstAt.toString(), window.lastAt.toString()));
        memory.startedAt = window.firstAt.toString();
        memory.endedAt = window.lastAt.toString();
        memory.participantIds.addAll(window.participantIds);
        memory.participantCount = window.participantIds.size();
        memory.messageCount = window.messageCount;
        memory.layers.add(MemoryLayer.EPISODIC);
        if (window.participantIds.size() >= 5) memory.layers.add(MemoryLayer.SOCIAL);

        String cultural = window.topCulturalMarker();
        if (cultural != null) {
            memory.layers.add(MemoryLayer.CULTURAL);
            memory.tags.add(cultural);
        }
        window.emojiCounts.keySet().forEach(memory.tags::add);
        window.repeatedPhrases().forEach(memory.tags::add);
        window.topTopics().forEach(memory.tags::add);
        memory.relevance = relevance(window);
        if (memory.relevance >= 70) memory.layers.add(MemoryLayer.HISTORICAL);
        long minutes = Math.max(1, Duration.between(window.firstAt, window.lastAt).toMinutes());
        memory.title = provisionalTitle(window, cultural);
        memory.summary = "Actividad publica en #" + window.channelName + " con " + memory.participantCount
                + " participantes, " + memory.messageCount + " mensajes y una duracion aproximada de " + minutes
                + " minutos" + (cultural == null ? "." : "; destaco «" + cultural + "». ");
        memory.factualSummary = memory.summary;
        memory.conversationSummaries.add(memory.summary);
        ConversationDigest conversation = new ConversationDigest();
        conversation.id = memory.id + "-C1";
        conversation.channelId = window.channelId;
        conversation.startedAt = memory.startedAt;
        conversation.endedAt = memory.endedAt;
        conversation.messageCount = memory.messageCount;
        conversation.participantIds.addAll(memory.participantIds);
        conversation.topics.addAll(memory.tags);
        conversation.summary = memory.summary;
        memory.conversations.put(conversation.id, conversation);
        SessionDigest session = new SessionDigest();
        session.id = memory.id + "-S1";
        session.startedAt = memory.startedAt;
        session.endedAt = memory.endedAt;
        session.conversationIds.add(conversation.id);
        session.summary = "Sesion publica condensada en #" + window.channelName + ".";
        memory.sessions.add(session);
        memory.interpretation = interpretationFor(memory);
        memory.interpretationConfidence = 0.55;
        CollectiveMemory crossChannel = findCrossChannelEvent(memory);
        if (crossChannel != null) {
            mergeCrossChannel(crossChannel, memory);
            relate(crossChannel);
            store.saveEvent(crossChannel);
            linkTrends(crossChannel);
            requestSemanticEnrichment(crossChannel.id, window);
            proposeGenerationBoundary(crossChannel);
            proposeEraIfNeeded(crossChannel.guildId);
            return;
        }
        relate(memory);
        store.saveEvent(memory);
        linkTrends(memory);
        requestSemanticEnrichment(memory.id, window);
        proposeGenerationBoundary(memory);
        proposeEraIfNeeded(memory.guildId);
    }

    private void relate(CollectiveMemory memory) {
        Set<String> terms = new LinkedHashSet<>(memory.tags);
        terms.addAll(MemoryStore.terms(memory.title));
        if (terms.isEmpty()) return;
        List<CollectiveMemory> candidates = store.findEvents(memory.guildId, String.join(" ", terms),
                unlimitedMemory ? 0 : intEnv("MEMORY_GRAPH_CANDIDATES", 100, 4, 10_000));
        for (CollectiveMemory other : candidates) {
            if (other.id.equals(memory.id)) continue;
            Set<String> overlap = new LinkedHashSet<>(memory.participantIds);
            overlap.retainAll(other.participantIds);
            Set<String> tagOverlap = new LinkedHashSet<>(memory.tags);
            tagOverlap.retainAll(other.tags);
            if (overlap.size() >= 3 || !tagOverlap.isEmpty()) {
                memory.relatedMemoryIds.add(other.id);
                other.relatedMemoryIds.add(memory.id);
                if (!hasRelation(memory, other.id, RelationType.RELATED_TO))
                    memory.relations.add(new MemoryRelation(other.id, RelationType.RELATED_TO, 0.72,
                            tagOverlap.isEmpty() ? "participantes compartidos" : "marcadores compartidos: " + tagOverlap));
                if (!hasRelation(other, memory.id, RelationType.RELATED_TO))
                    other.relations.add(new MemoryRelation(memory.id, RelationType.RELATED_TO, 0.72,
                            tagOverlap.isEmpty() ? "participantes compartidos" : "marcadores compartidos: " + tagOverlap));
                store.saveEvent(other);
            }
        }
    }

    private CollectiveMemory findCrossChannelEvent(CollectiveMemory incoming) {
        for (CollectiveMemory candidate : store.allGuildEvents(incoming.guildId)) {
            if (candidate.channelId.equals(incoming.channelId)) continue;
            Instant end = parseInstant(candidate.endedAt);
            if (end == null || Math.abs(Duration.between(end, Instant.parse(incoming.startedAt)).toHours()) > 48) continue;
            Set<String> shared = new LinkedHashSet<>(candidate.tags);
            shared.retainAll(incoming.tags);
            if (shared.stream().anyMatch(term -> term.length() >= 4)) return candidate;
        }
        return null;
    }

    private static boolean hasRelation(CollectiveMemory memory, String targetId, RelationType type) {
        return memory.relations != null && memory.relations.stream()
                .anyMatch(relation -> targetId.equals(relation.targetId) && type == relation.type);
    }

    private void mergeCrossChannel(CollectiveMemory target, CollectiveMemory incoming) {
        if (target.sources == null) target.sources = new LinkedHashMap<>();
        if (target.sources.isEmpty()) target.sources.put(target.channelId,
                new MemorySource(target.channelId, target.channelId, target.messageCount, target.startedAt, target.endedAt));
        target.sources.putAll(incoming.sources);
        target.startedAt = target.startedAt.compareTo(incoming.startedAt) <= 0 ? target.startedAt : incoming.startedAt;
        target.endedAt = target.endedAt.compareTo(incoming.endedAt) >= 0 ? target.endedAt : incoming.endedAt;
        target.messageCount += incoming.messageCount;
        target.participantIds.addAll(incoming.participantIds);
        target.participantCount = target.participantIds.size();
        target.tags.addAll(incoming.tags);
        target.layers.addAll(incoming.layers);
        target.conversationSummaries.addAll(incoming.conversationSummaries);
        if (target.conversations == null) target.conversations = new LinkedHashMap<>();
        if (target.sessions == null) target.sessions = new ArrayList<>();
        target.conversations.putAll(incoming.conversations);
        target.sessions.addAll(incoming.sessions);
        target.relevance = Math.min(100, Math.max(target.relevance, incoming.relevance) + 8);
        target.title = "Historia multicanal: " + bestTopic(target.tags);
        target.summary = "Acontecimiento reconstruido a partir de " + target.sources.size() + " canales ("
                + sourceNames(target) + "), " + target.participantCount + " participantes y "
                + target.messageCount + " mensajes agregados.";
        target.factualSummary = target.summary;
        target.interpretation = interpretationFor(target);
        target.interpretationConfidence = Math.min(0.85, 0.55 + target.sources.size() * 0.05);
        target.relations.add(new MemoryRelation(incoming.id, RelationType.MERGED_FROM, 1.0,
                "ventana conversacional del canal " + incoming.channelId));
    }

    private static String interpretationFor(CollectiveMemory memory) {
        if (memory.relevance >= 85) return "Probablemente fue un punto importante en la cultura de la comunidad.";
        if (memory.participantCount >= 10) return "Parece haber conectado a una parte amplia de la comunidad.";
        return "Parece un momento representativo, aunque todavia necesita referencias posteriores para considerarse historico.";
    }

    private void requestSemanticEnrichment(String memoryId, ActivityWindow window) {
        if (!semanticExtractionEnabled || window.semanticLines.isEmpty()) return;
        String transcript = String.join("\n", window.semanticLines);
        String instruction = """
                Extrae memoria historica verificable de una conversacion publica de Discord.
                Devuelve SOLO JSON valido con: title, summary, tags (array), interpretation y facts (array).
                Cada fact debe tener subject, predicate, object y confidence entre 0 y 1.
                Conserva IDs <@usuario> cuando aparezcan. No inventes nombres, causas, fechas ni resultados.
                Distingue planes de hechos consumados en predicate. Resume; no copies frases completas.
                Si no hay un hecho concreto, facts debe ser []. Maximo 8 hechos y 8 tags.
                Conversacion:
                """ + limit(transcript, 24_000);
        semanticClient.chat(List.of(new ConversationMemoryStore.StoredMessage("user", instruction, "")), 0.1)
                .thenAccept(json -> applySemanticEnrichment(memoryId, window.channelId, window.lastAt, json, transcript))
                .exceptionally(error -> {
                    System.err.println("Extraccion semantica omitida para " + memoryId + ": " + error.getMessage());
                    return null;
                });
    }

    private void applySemanticEnrichment(String memoryId, String sourceChannelId, Instant observedAt,
                                         String response, String sourceTranscript) {
        if (closed) return;
        try {
            SemanticPayload payload = parseSemanticPayload(response, sourceChannelId, observedAt);
            CollectiveMemory memory = store.getEventById(memoryId);
            if (memory == null) return;
            String normalizedSource = normalize(sourceTranscript);
            if (!payload.title.isBlank() && groundedTerms(payload.title, normalizedSource)) memory.title = payload.title;
            if (!payload.interpretation.isBlank()) {
                memory.interpretation = payload.interpretation;
                memory.interpretationConfidence = 0.65;
            }
            for (String value : payload.tags) {
                if (groundedTerms(value, normalizedSource)) memory.tags.add(value);
            }
            if (memory.facts == null) memory.facts = new ArrayList<>();
            List<MemoryFact> newlyGrounded = new ArrayList<>();
            for (MemoryFact fact : payload.facts) {
                if (!groundedFact(fact, normalizedSource)) continue;
                boolean duplicate = memory.facts.stream().anyMatch(existing -> normalize(existing.subject).equals(normalize(fact.subject))
                        && normalize(existing.predicate).equals(normalize(fact.predicate))
                        && normalize(existing.object).equals(normalize(fact.object)));
                if (!duplicate && fact.confidence >= 0.50) {
                    memory.facts.add(fact);
                    newlyGrounded.add(fact);
                }
            }
            if (!newlyGrounded.isEmpty()) {
                String extracted = newlyGrounded.stream().map(fact -> fact.subject + " " + fact.predicate + " " + fact.object)
                        .collect(java.util.stream.Collectors.joining("; "));
                String base = memory.factualSummary == null ? memory.summary : memory.factualSummary;
                memory.factualSummary = limit(base + " Hechos verificados en la fuente: " + extracted + ".", 900);
                memory.summary = memory.factualSummary;
            }
            store.saveEvent(memory);
        } catch (RuntimeException malformed) {
            System.err.println("JSON semantico invalido para " + memoryId + ": " + malformed.getMessage());
        }
    }

    static SemanticPayload parseSemanticPayload(String response, String sourceChannelId, Instant observedAt) {
        String clean = response == null ? "" : response.trim();
        if (clean.startsWith("```")) clean = clean.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        JsonObject root = JsonParser.parseString(clean).getAsJsonObject();
        List<String> parsedTags = new ArrayList<>();
        JsonArray tags = root.has("tags") && root.get("tags").isJsonArray() ? root.getAsJsonArray("tags") : new JsonArray();
        for (JsonElement tag : tags) {
            String value = limit(normalize(tag.getAsString()), 60);
            if (value.length() >= 3 && !parsedTags.contains(value)) parsedTags.add(value);
            if (parsedTags.size() >= 8) break;
        }
        List<MemoryFact> parsedFacts = new ArrayList<>();
        JsonArray facts = root.has("facts") && root.get("facts").isJsonArray() ? root.getAsJsonArray("facts") : new JsonArray();
        for (JsonElement element : facts) {
            if (!element.isJsonObject() || parsedFacts.size() >= 8) break;
            JsonObject factJson = element.getAsJsonObject();
            MemoryFact fact = new MemoryFact();
            fact.subject = jsonString(factJson, "subject", 100);
            fact.predicate = jsonString(factJson, "predicate", 80);
            fact.object = jsonString(factJson, "object", 180);
            if (fact.subject.isBlank() || fact.predicate.isBlank() || fact.object.isBlank()) continue;
            fact.sourceChannelId = sourceChannelId;
            fact.observedAt = observedAt.toString();
            try {
                fact.confidence = factJson.has("confidence")
                        ? MemoryModels.clamp(factJson.get("confidence").getAsDouble()) : 0.60;
            } catch (RuntimeException ignored) {
                fact.confidence = 0.60;
            }
            if (fact.confidence >= 0.50) parsedFacts.add(fact);
        }
        return new SemanticPayload(jsonString(root, "title", 120), jsonString(root, "summary", 600),
                jsonString(root, "interpretation", 400), parsedTags, parsedFacts);
    }

    private static String jsonString(JsonObject object, String field, int max) {
        try {
            return object.has(field) && !object.get(field).isJsonNull() ? limit(object.get(field).getAsString(), max) : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean groundedFact(MemoryFact fact, String normalizedSource) {
        return groundedTerms(fact.subject, normalizedSource) && groundedTerms(fact.object, normalizedSource);
    }

    private static boolean groundedTerms(String value, String normalizedSource) {
        String normalizedValue = normalize(value);
        if (normalizedValue.matches("<@!?\\d+>") && normalizedSource.contains(normalizedValue)) return true;
        Set<String> terms = significantTerms(normalizedValue);
        return !terms.isEmpty() && terms.stream().anyMatch(normalizedSource::contains);
    }

    private void observeCulturalSignals(Observation observation) {
        if (isProhibited(observation.content)) return;
        Set<String> markers = new LinkedHashSet<>();
        Matcher emojis = CUSTOM_EMOJI.matcher(observation.content);
        while (emojis.find()) markers.add(":" + emojis.group(1).toLowerCase(Locale.ROOT) + ":");
        String phrase = normalize(observation.content).replaceAll("<a?:[a-z0-9_]+:\\d+>", "").trim();
        int words = phrase.isBlank() ? 0 : phrase.split("\\s+").length;
        if (words >= 2 && words <= 7 && phrase.length() <= 80) markers.add(phrase);
        for (String marker : markers) updateTrend(observation, marker);
    }

    private void rememberLive(Observation observation) {
        if (isProhibited(observation.content)) return;
        Deque<RecentSignal> signals = liveGuildContext.computeIfAbsent(observation.guildId, ignored -> new ArrayDeque<>());
        synchronized (signals) {
            signals.addLast(new RecentSignal(observation.channelId, observation.channelName, observation.userId,
                    limit(observation.content, 300), observation.at));
            Instant cutoff = observation.at.minus(Duration.ofHours(2));
            while (!signals.isEmpty() && (signals.size() > 200 || signals.peekFirst().at.isBefore(cutoff)))
                signals.removeFirst();
        }
    }

    private void appendLiveCrossChannelContext(StringBuilder context, String guildId, String query,
                                               Set<String> readableChannelIds) {
        Deque<RecentSignal> signals = liveGuildContext.get(guildId);
        if (signals == null) return;
        Set<String> queryTerms = significantTerms(normalize(query));
        List<RecentSignal> matches;
        synchronized (signals) {
            List<RecentSignal> authorized = signals.stream()
                    .filter(item -> readableChannelIds == null || readableChannelIds.contains(item.channelId))
                    .toList();
            LinkedHashSet<RecentSignal> selected = new LinkedHashSet<>();
            authorized.subList(Math.max(0, authorized.size() - 4), authorized.size()).forEach(selected::add);
            List<RecentSignal> lexical = authorized.stream()
                    .filter(item -> significantTerms(item.content).stream().anyMatch(queryTerms::contains)).toList();
            lexical.subList(Math.max(0, lexical.size() - 4), lexical.size()).forEach(selected::add);
            matches = selected.stream().sorted(Comparator.comparing(RecentSignal::at)).limit(8).toList();
        }
        if (matches.isEmpty()) return;
        context.append("Contexto efimero de canales autorizados (no es memoria historica ni debe citarse fuera de sus permisos):\n");
        matches.forEach(item -> context.append("- #").append(item.channelName).append(" <@").append(item.userId)
                .append(">: ").append(item.content).append('\n'));
    }

    private void updateTrend(Observation observation, String marker) {
        CulturalTrend trend = store.getTrendByMarker(observation.guildId, marker);
        String key = observation.guildId + ":" + marker;
        if (trend == null) {
            TrendCandidate candidate = trendCandidates.computeIfAbsent(key,
                    ignored -> new TrendCandidate(observation.userId, observation.channelId, observation.at));
            candidate.accept(observation);
            if (!unlimitedMemory && trendCandidates.size() > 20_000)
                trendCandidates.remove(trendCandidates.keySet().iterator().next());
            if (candidate.uses < 5) return;
            trend = new CulturalTrend();
            trend.id = store.nextTrendId();
            trend.guildId = observation.guildId;
            trend.marker = marker;
            trend.firstSeenAt = candidate.firstSeenAt.toString();
            trend.originUserId = candidate.originUserId;
            trend.originChannelId = candidate.originChannelId;
            trend.totalUses = candidate.uses;
            trend.recentUses = candidate.uses;
            trend.participantIds.addAll(candidate.participantIds);
            trend.channelIds.addAll(candidate.channelIds);
            trendCandidates.remove(key);
        } else {
            Instant previousSeen = parseInstant(trend.lastSeenAt);
            if (previousSeen != null && Duration.between(previousSeen, observation.at).toDays() >= 7) {
                trend.previousUses = trend.recentUses;
                trend.recentUses = 0;
            }
            trend.totalUses++;
            trend.recentUses++;
            trend.participantIds.add(observation.userId);
            trend.channelIds.add(observation.channelId);
        }
        trend.lastSeenAt = observation.at.toString();
        trend.growthPercent = trend.previousUses == 0 ? 100.0 :
                (trend.recentUses - trend.previousUses) * 100.0 / trend.previousUses;
        trend.status = classifyTrend(trend, observation.at);
        if (trend.peakAt == null || trend.recentUses >= trend.previousUses) trend.peakAt = observation.at.toString();
        if (trend.totalUses <= 10 || trend.totalUses % 5 == 0) store.saveTrend(trend);
    }

    private static TrendStatus classifyTrend(CulturalTrend trend, Instant now) {
        Instant last = parseInstant(trend.lastSeenAt);
        long inactiveDays = last == null ? 0 : Math.max(0, Duration.between(last, now).toDays());
        if (inactiveDays >= 270) return TrendStatus.LOST;
        if (inactiveDays >= 90) return TrendStatus.FADING;
        if (trend.totalUses >= 200) return TrendStatus.HISTORICAL;
        if (trend.totalUses >= 30) return TrendStatus.POPULAR;
        return TrendStatus.EMERGING;
    }

    private void linkTrends(CollectiveMemory memory) {
        boolean linked = false;
        for (CulturalTrend trend : store.listTrends(memory.guildId, null)) {
            if (!memory.tags.contains(trend.marker)) continue;
            trend.linkedEventId = memory.id;
            store.saveTrend(trend);
            if (!hasRelation(memory, trend.id, RelationType.GENERATED)) {
                memory.relations.add(new MemoryRelation(trend.id, RelationType.GENERATED, 0.80,
                        "marcador cultural observado durante el evento"));
                linked = true;
            }
        }
        if (linked) store.saveEvent(memory);
    }

    private void assignGeneration(ConversationProfile profile, String guildId, String channelId, Instant at) {
        List<Generation> generations = store.listGenerations(guildId);
        Generation current;
        if (generations.isEmpty()) {
            current = new Generation();
            current.id = store.nextGenerationId();
            current.guildId = guildId;
            current.name = "Generacion fundadora observada";
            current.startedAt = at.toString();
            current.confirmed = false;
            current.proposedAutomatically = true;
        } else current = generations.get(generations.size() - 1);
        current.memberIds.add(profile.userId);
        current.sourceChannelIds.add(channelId);
        profile.generationId = current.id;
        store.saveGeneration(current);
    }

    private void proposeGenerationBoundary(CollectiveMemory event) {
        if (event.relevance < 85 || event.participantCount < 20) return;
        List<Generation> generations = store.listGenerations(event.guildId);
        Generation current = generations.isEmpty() ? null : generations.get(generations.size() - 1);
        if (current != null && event.id.equals(current.boundaryEventId)
                && event.endedAt.equals(current.startedAt)) return;
        if (current != null && current.boundaryEventId == null) {
            current.endedAt = event.endedAt;
            current.boundaryEventId = event.id;
            store.saveGeneration(current);
        }
        Generation next = new Generation();
        next.id = store.nextGenerationId();
        next.guildId = event.guildId;
        next.name = "Generacion posterior a " + event.title;
        next.startedAt = event.endedAt;
        next.boundaryEventId = event.id;
        next.proposedAutomatically = true;
        next.sourceChannelIds.addAll(event.sources == null || event.sources.isEmpty()
                ? Set.of(event.channelId) : event.sources.keySet());
        store.saveGeneration(next);
    }

    private void proposeEraIfNeeded(String guildId) {
        if (!store.listEras(guildId).isEmpty()) return;
        List<CollectiveMemory> chronological = new ArrayList<>(store.allGuildEvents(guildId));
        chronological.sort(Comparator.comparing(memory -> memory.startedAt));
        if (chronological.size() < 6) return;
        int split = chronological.size() - 3;
        double oldAverage = chronological.subList(0, split).stream().mapToInt(memory -> memory.messageCount).average().orElse(1);
        double newAverage = chronological.subList(split, chronological.size()).stream().mapToInt(memory -> memory.messageCount).average().orElse(0);
        if (newAverage < oldAverage * 1.75) return;
        Era era = new Era();
        era.id = store.nextEraId();
        era.guildId = guildId;
        era.name = "Nueva etapa (propuesta)";
        era.description = "Cambio de actividad detectado automaticamente; requiere confirmacion administrativa.";
        era.startedAt = chronological.get(split).startedAt;
        era.proposedAutomatically = true;
        chronological.subList(split, chronological.size()).forEach(memory -> era.memoryIds.add(memory.id));
        store.saveEra(era);
    }

    public String history(String guildId, String query) {
        List<CollectiveMemory> events = store.findEvents(guildId, query, 10);
        if (events.isEmpty() && shouldRetrieveHistory(query)) events = store.listEvents(guildId, 10);
        if (events.isEmpty()) return "Todavia no hay memorias colectivas relevantes para esa consulta.";
        StringBuilder result = new StringBuilder("**Cronica de la comunidad**\n");
        events.stream().sorted(Comparator.comparing(memory -> memory.startedAt)).forEach(memory -> result
                .append("• ").append(SHORT_DATE.format(Instant.parse(memory.startedAt)))
                .append(" — **").append(memory.title).append("** (`").append(memory.id).append("`, ")
                .append(memory.relevance).append("/100)\n"));
        return limit(result.toString(), 1_900);
    }

    public String eventDetails(String guildId, String id) {
        CollectiveMemory memory = store.getEvent(guildId, id);
        if (memory == null || memory.status == MemoryStatus.IRRELEVANT) return "No encontre ese evento publico.";
        return limit("**" + memory.id + " — " + memory.title + "**\n" + memory.summary
                + "\nInicio: " + SHORT_DATE.format(Instant.parse(memory.startedAt))
                + " UTC\nParticipantes: " + memory.participantCount + " | Mensajes: " + memory.messageCount
                + " | Relevancia: " + memory.relevance + "/100"
                + "\nCapas: " + memory.layers + (memory.relatedMemoryIds.isEmpty() ? "" : "\nRelacionado con: " + memory.relatedMemoryIds), 1_900);
    }

    public String museum(String guildId) {
        List<CollectiveMemory> events = store.listEvents(guildId, 20);
        if (events.isEmpty()) return "El museo aun esta vacio. Se llenara solo con acontecimientos que superen el umbral de relevancia.";
        Map<MemoryLayer, Long> counts = new LinkedHashMap<>();
        for (MemoryLayer layer : MemoryLayer.values()) {
            counts.put(layer, events.stream().filter(memory -> memory.layers.contains(layer)).count());
        }
        StringBuilder result = new StringBuilder("**Museo de la comunidad**\nColeccion: ").append(counts).append("\n");
        events.stream().limit(8).forEach(memory -> result.append("• `").append(memory.id).append("` **")
                .append(memory.title).append("** — ").append(memory.relevance).append("/100\n"));
        return limit(result.toString(), 1_900);
    }

    public String historyAuthorized(String guildId, String query, Set<String> readableChannelIds) {
        return historyAuthorized(guildId, query, readableChannelIds, 1);
    }

    public String historyAuthorized(String guildId, String query, Set<String> readableChannelIds, int requestedPage) {
        final int pageSize = 8;
        int page = Math.max(1, requestedPage);
        List<CollectiveMemory> events = store.findEvents(guildId, query, 0, readableChannelIds);
        if (events.isEmpty() && shouldRetrieveHistory(query)) events = store.listEvents(guildId, 0, readableChannelIds);
        if (events.isEmpty()) return "Todavia no hay memorias autorizadas relevantes para esa consulta.";
        List<CollectiveMemory> allChronological = events.stream().sorted(Comparator.comparing(memory -> memory.startedAt)).toList();
        int totalPages = Math.max(1, (allChronological.size() + pageSize - 1) / pageSize);
        if (page > totalPages) return "Esa pagina no existe. Paginas disponibles: 1-" + totalPages + ".";
        int from = (page - 1) * pageSize;
        List<CollectiveMemory> chronological = allChronological.subList(from, Math.min(allChronological.size(), from + pageSize));
        StringBuilder result = new StringBuilder("**Historia viva de la comunidad** — pagina ")
                .append(page).append('/').append(totalPages).append(" (total: ").append(allChronological.size()).append(")\n");
        String previousChapter = "";
        for (int index = 0; index < chronological.size(); index++) {
            String chapter = chapterName(from + index, allChronological.size(), chronological.get(index), store.listEras(guildId));
            if (!chapter.equals(previousChapter)) result.append("\n**").append(chapter).append("**\n");
            CollectiveMemory memory = chronological.get(index);
            result.append("- ").append(SHORT_DATE.format(Instant.parse(memory.startedAt)))
                    .append(" - **").append(memory.title).append("** (`").append(memory.id).append("`, ")
                    .append(memory.relevance).append("/100, fuentes: ").append(sourceNames(memory)).append(")\n");
            previousChapter = chapter;
        }
        return limit(result.toString(), 1_900);
    }

    public String eventDetailsAuthorized(String guildId, String id, Set<String> readableChannelIds) {
        CollectiveMemory memory = store.getEvent(guildId, id);
        if (memory == null || memory.status == MemoryStatus.IRRELEVANT
                || !store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds).contains(memory))
            return "No encontre ese evento entre las fuentes que puedes consultar.";
        return limit("**" + memory.id + " - " + memory.title + "**\n" + memory.summary
                + "\nInicio: " + SHORT_DATE.format(Instant.parse(memory.startedAt))
                + " UTC\nParticipantes: " + memory.participantCount + " | Mensajes: " + memory.messageCount
                + " | Relevancia: " + memory.relevance + "/100\nFuentes: " + sourceNames(memory)
                + "\n**HECHOS:** " + (memory.factualSummary == null ? memory.summary : memory.factualSummary)
                + "\n**INTERPRETACION:** " + (memory.interpretation == null ? interpretationFor(memory) : memory.interpretation)
                + "\nHechos estructurados: " + formatFacts(memory)
                + "\nCapas: " + memory.layers, 1_900);
    }

    public String museumAuthorized(String guildId, Set<String> readableChannelIds) {
        List<CollectiveMemory> events = store.listEvents(guildId, 20, readableChannelIds);
        if (events.isEmpty()) return "El museo autorizado aun esta vacio.";
        StringBuilder result = new StringBuilder("**Museo de la comunidad**\n");
        events.stream().filter(item -> item.layers.contains(MemoryLayer.HISTORICAL) || item.relevance >= 60).limit(10)
                .forEach(item -> result.append("- `").append(item.id).append("` **").append(item.title)
                        .append("** - ").append(item.relevance).append("/100\n"));
        return limit(result.toString(), 1_900);
    }

    public String trends(String guildId, Set<String> readableChannelIds, boolean lostOnly) {
        Instant now = Instant.now();
        List<CulturalTrend> items = store.listTrends(guildId, readableChannelIds);
        items.forEach(item -> item.status = classifyTrend(item, now));
        items = items.stream().filter(item -> lostOnly
                        ? item.status == TrendStatus.FADING || item.status == TrendStatus.LOST
                        : item.status != TrendStatus.FADING && item.status != TrendStatus.LOST)
                .limit(12).toList();
        if (items.isEmpty()) return lostOnly ? "No detecte recuerdos culturales perdidos todavia."
                : "Aun no hay tendencias que hayan superado el umbral de cinco usos.";
        StringBuilder out = new StringBuilder(lostOnly ? "**Recuerdos perdidos**\n" : "**Radar cultural**\n");
        items.forEach(item -> out.append("- **").append(item.marker).append("** - ")
                .append(item.status).append(", ").append(item.totalUses).append(" usos, ")
                .append(item.participantIds.size()).append(" usuarios, crecimiento ")
                .append(String.format(Locale.ROOT, "%+.0f%%", item.growthPercent)).append('\n'));
        return limit(out.toString(), 1_900);
    }

    public String origin(String guildId, String marker, Set<String> readableChannelIds) {
        CulturalTrend trend = store.getTrendByMarker(guildId, normalize(marker));
        if (trend == null || (readableChannelIds != null && !readableChannelIds.containsAll(trend.channelIds)))
            return "No encontre un origen verificable entre los canales autorizados.";
        return "**HECHO**\nPrimera aparicion observada de **" + trend.marker + "**: " + trend.firstSeenAt
                + " por <@" + trend.originUserId + "> en <#" + trend.originChannelId + ">.\nUsos registrados: "
                + trend.totalUses + ". Estado: " + classifyTrend(trend, Instant.now()) + ".";
    }

    public String timeTravel(String guildId, String moment, Set<String> readableChannelIds) {
        Instant target = parseMoment(moment);
        if (target == null) return "Usa una fecha como `2025`, `2025-08` o `2025-08-14`.";
        List<CollectiveMemory> events = store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds).stream()
                .filter(item -> !Instant.parse(item.startedAt).isAfter(target)).toList();
        if (events.isEmpty()) return "No tengo evidencia autorizada de esa epoca.";
        Set<String> members = new LinkedHashSet<>();
        Set<String> channels = new LinkedHashSet<>();
        Map<String, Integer> topics = new HashMap<>();
        events.forEach(item -> {
            members.addAll(item.participantIds);
            channels.addAll(item.sources == null || item.sources.isEmpty() ? Set.of(item.channelId) : item.sources.keySet());
            item.tags.forEach(tag -> topics.merge(tag, 1, Integer::sum));
        });
        String mainTopic = topics.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("sin datos");
        return "**Viaje temporal - " + SHORT_DATE.format(target) + " UTC**\n"
                + "**HECHOS observados hasta esa fecha:** " + members.size() + " participantes conocidos, "
                + channels.size() + " canales fuente y " + events.size() + " eventos.\n"
                + "Tema cultural mas repetido: **" + mainTopic + "**.\n"
                + "**LIMITACION:** son cifras reconstruidas; no equivalen al total real de Discord.";
    }

    public String identity(String guildId, Set<String> readableChannelIds) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        List<CollectiveMemory> recent = store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds).stream()
                .filter(item -> Instant.parse(item.endedAt).isAfter(cutoff)).toList();
        Map<String, Integer> topics = new HashMap<>();
        Set<String> active = new LinkedHashSet<>();
        recent.forEach(item -> { item.tags.forEach(tag -> topics.merge(tag, 1, Integer::sum)); active.addAll(item.participantIds); });
        List<String> top = topics.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3).map(Map.Entry::getKey).toList();
        long activeTrends = store.listTrends(guildId, readableChannelIds).stream()
                .filter(item -> classifyTrend(item, Instant.now()) != TrendStatus.LOST).count();
        Set<String> readableEventIds = store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds).stream()
                .map(item -> item.id).collect(java.util.stream.Collectors.toSet());
        String currentEra = store.listEras(guildId).stream()
                .filter(item -> item.memoryIds == null || item.memoryIds.isEmpty() || readableEventIds.containsAll(item.memoryIds))
                .reduce((first, second) -> second).map(item -> item.name).orElse("etapa aun sin nombre");
        return "**Identidad actual (ultimos 90 dias)**\nEra: **" + currentEra + "**.\n**HECHOS:** " + recent.size() + " eventos, "
                + active.size() + " participantes observados y " + activeTrends + " tendencias culturales.\n"
                + "Temas principales: " + (top.isEmpty() ? "sin evidencia suficiente" : top) + ".\n"
                + "**INTERPRETACION:** " + (recent.size() >= 8 ? "la comunidad atraviesa una etapa de actividad alta."
                : "la identidad actual aun se esta formando o la actividad observada es moderada.");
    }

    public String generations(String guildId, Set<String> readableChannelIds) {
        Instant activeCutoff = Instant.now().minus(Duration.ofDays(90));
        Set<String> activeMembers = new LinkedHashSet<>();
        store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds).stream()
                .filter(item -> Instant.parse(item.endedAt).isAfter(activeCutoff))
                .forEach(item -> activeMembers.addAll(item.participantIds));
        List<Generation> items = store.listGenerations(guildId).stream()
                .filter(item -> readableChannelIds == null || item.sourceChannelIds == null
                        || readableChannelIds.containsAll(item.sourceChannelIds))
                .toList();
        if (items.isEmpty()) return "Aun no hay generaciones observadas.";
        StringBuilder out = new StringBuilder("**Generaciones de la comunidad**\n");
        items.forEach(item -> {
            long active = item.memberIds.stream().filter(activeMembers::contains).count();
            out.append("- `").append(item.id).append("` **").append(item.name)
                .append("** - ").append(item.memberIds.size()).append(" miembros observados, ").append(active).append(" activos")
                .append(item.boundaryEventId == null ? "" : ", frontera: " + item.boundaryEventId)
                .append(item.confirmed ? " [confirmada]" : " [propuesta]").append('\n');
        });
        return limit(out.toString(), 1_900);
    }

    public String veteransAuthorized(String guildId, Set<String> readableChannelIds) {
        Map<String, Integer> appearances = new HashMap<>();
        store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds)
                .forEach(memory -> memory.participantIds.forEach(id -> appearances.merge(id, 1, Integer::sum)));
        if (appearances.isEmpty()) return "Aun no hay suficientes eventos autorizados para calcular participantes historicos.";
        StringBuilder result = new StringBuilder("**Participantes historicos en fuentes autorizadas**\n");
        appearances.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
                .forEach(entry -> result.append("- <@").append(entry.getKey()).append("> - ")
                        .append(entry.getValue()).append(" eventos\n"));
        return result.toString();
    }

    public String erasAuthorized(String guildId, Set<String> readableChannelIds) {
        Set<String> readableEvents = store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds).stream()
                .map(item -> item.id).collect(java.util.stream.Collectors.toSet());
        List<Era> items = store.listEras(guildId).stream()
                .filter(item -> item.memoryIds == null || item.memoryIds.isEmpty() || readableEvents.containsAll(item.memoryIds))
                .toList();
        if (items.isEmpty()) return "No hay eras reconstruibles con tus fuentes autorizadas.";
        StringBuilder result = new StringBuilder("**Eras de la comunidad**\n");
        items.forEach(item -> result.append("- `").append(item.id).append("` **").append(item.name).append("** - ")
                .append(item.description).append(item.confirmed ? " [confirmada]" : " [provisional]").append('\n'));
        return limit(result.toString(), 1_900);
    }

    public String evolution(String guildId, String userId) {
        ConversationProfile profile = store.getProfile(guildId, userId);
        if (profile == null) return "Todavia no tengo una trayectoria personal para ti.";
        List<String> topics = profile.topicActivity.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5).map(Map.Entry::getKey).toList();
        return "**Tu evolucion en la comunidad (privada)**\nPrimera observacion: " + profile.firstSeenAt
                + "\nUltima actividad: " + profile.lastSeenAt + "\nGeneracion: " + profile.generationId
                + "\nCanales en los que participaste: " + profile.channelActivity.size()
                + "\nTemas habituales: " + topics + "\nHitos: " + profile.milestones;
    }

    public String health(String guildId, Set<String> readableChannelIds) {
        long openWindows = windows.values().stream().filter(window -> guildId.equals(window.guildId)).count();
        int events = store.listEvents(guildId, Integer.MAX_VALUE, readableChannelIds).size();
        int trends = store.listTrends(guildId, readableChannelIds).size();
        int eras = store.listEras(guildId).size();
        int generations = store.listGenerations(guildId).size();
        Deque<RecentSignal> live = liveGuildContext.get(guildId);
        int liveSignals;
        if (live == null) liveSignals = 0;
        else synchronized (live) { liveSignals = live.size(); }
        return "**Estado de la memoria**\n"
                + "Servicio: " + (enabled ? "activo" : "desactivado")
                + " | Persistencia: " + (unlimitedMemory ? "ilimitada" : "acotada")
                + " | Extraccion semantica: " + (semanticExtractionEnabled ? "activa" : "desactivada") + "\n"
                + "Eventos autorizados: " + events + " | Tendencias: " + trends + " | Eras: " + eras
                + " | Generaciones: " + generations + "\n"
                + "Ventanas abiertas: " + openWindows + " | Contextos efimeros: " + liveSignals + "\n"
                + "Un evento se consolida tras " + eventGap.toMinutes() + " minutos de pausa y requiere al menos "
                + minEventMessages + " mensajes de " + minEventParticipants + " participantes."
                + (events == 0 ? "\nTodavia no hay eventos consolidados; los comandos historicos devolveran resultados vacios hasta superar esos umbrales." : "");
    }

    public String veterans(String guildId) {
        Map<String, Integer> appearances = new HashMap<>();
        store.allGuildEvents(guildId).forEach(memory -> memory.participantIds.forEach(id -> appearances.merge(id, 1, Integer::sum)));
        if (appearances.isEmpty()) return "Aun no hay suficientes eventos para calcular participantes historicos.";
        StringBuilder result = new StringBuilder("**Participantes historicos por presencia en eventos publicos**\n");
        appearances.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
                .forEach(entry -> result.append("• <@").append(entry.getKey()).append("> — ")
                        .append(entry.getValue()).append(" eventos\n"));
        return result.toString();
    }

    public String eras(String guildId) {
        List<Era> eras = store.listEras(guildId);
        if (eras.isEmpty()) return "Todavia no se detectaron eras. Se proponen cuando aparece un cambio sostenido de actividad.";
        StringBuilder result = new StringBuilder("**Eras de la comunidad**\n");
        eras.forEach(era -> result.append("• `").append(era.id).append("` **").append(era.name).append("** — ")
                .append(era.description).append(era.confirmed ? " [confirmada]" : " [provisional]").append('\n'));
        return limit(result.toString(), 1_900);
    }

    public String profile(String guildId, String userId) {
        ConversationProfile profile = store.getProfile(guildId, userId);
        if (profile == null) return "No tengo preferencias conversacionales guardadas para ti.";
        StringBuilder result = new StringBuilder("**Tu perfil conversacional (solo visible para ti)**\n")
                .append("Personalizacion: ").append(profile.personalizationEnabled ? "activa" : "desactivada")
                .append(" | Aprendizaje: ").append(profile.learningEnabled ? "activo" : "bloqueado")
                .append("\nInteracciones: ").append(profile.interactions)
                .append(" | Familiaridad tecnica: ").append(String.format(Locale.ROOT, "%.2f", profile.familiarity)).append('\n');
        if (profile.preferences.isEmpty()) result.append("Aun no hay preferencias de estilo.");
        else profile.preferences.forEach((name, preference) -> result.append("• ").append(name).append(": ")
                .append(String.format(Locale.ROOT, "%.2f", preference.value)).append(" (confianza ")
                .append(String.format(Locale.ROOT, "%.2f", preference.confidence))
                .append(preference.explicit ? ", explicita" : ", inferida").append(")\n"));
        return limit(result.toString(), 1_900);
    }

    public String setPersonalization(String guildId, String userId, boolean enabled) {
        ConversationProfile profile = store.getOrCreateProfile(guildId, userId);
        profile.personalizationEnabled = enabled;
        store.saveProfile(profile);
        return "Personalizacion " + (enabled ? "activada." : "desactivada. Tus datos no se usaran al responderte.");
    }

    public String setLearning(String guildId, String userId, boolean enabled) {
        ConversationProfile profile = store.getOrCreateProfile(guildId, userId);
        profile.learningEnabled = enabled;
        store.saveProfile(profile);
        return "Creacion de nuevas memorias personales " + (enabled ? "activada." : "bloqueada.");
    }

    public String forgetUser(String guildId, String userId, boolean blockLearning) {
        store.deleteProfile(guildId, userId, blockLearning);
        return blockLearning
                ? "Elimine tu perfil y preferencias. Solo queda un control tecnico de exclusion para no volver a aprender sobre ti."
                : "Elimine completamente tu perfil y preferencias personales.";
    }

    public String administer(String guildId, String action, String id, String value, String secondId) {
        CollectiveMemory memory = store.getEvent(guildId, id);
        if (memory == null) return "No encontre el evento " + id + ".";
        switch (normalize(action)) {
            case "confirmar" -> memory.status = MemoryStatus.CONFIRMED;
            case "renombrar" -> {
                if (value == null || value.isBlank()) return "Debes indicar el nuevo nombre en `valor`.";
                memory.title = limit(value, 120);
            }
            case "editar" -> {
                if (value == null || value.isBlank()) return "Debes indicar el nuevo resumen en `valor`.";
                memory.summary = limit(value, 600);
            }
            case "irrelevante" -> memory.status = MemoryStatus.IRRELEVANT;
            case "eliminar" -> {
                return store.deleteEvent(guildId, id) ? "Evento eliminado." : "No se pudo eliminar.";
            }
            case "fusionar" -> {
                CollectiveMemory other = store.getEvent(guildId, secondId);
                if (other == null) return "No encontre el segundo evento.";
                memory.startedAt = memory.startedAt.compareTo(other.startedAt) <= 0 ? memory.startedAt : other.startedAt;
                memory.endedAt = memory.endedAt.compareTo(other.endedAt) >= 0 ? memory.endedAt : other.endedAt;
                memory.messageCount += other.messageCount;
                memory.participantIds.addAll(other.participantIds);
                memory.participantCount = memory.participantIds.size();
                memory.tags.addAll(other.tags);
                memory.layers.addAll(other.layers);
                memory.relatedMemoryIds.addAll(other.relatedMemoryIds);
                memory.relatedMemoryIds.remove(memory.id);
                memory.relatedMemoryIds.remove(other.id);
                memory.summary = memory.summary + " Fusionado con " + other.id + ".";
                memory.relevance = Math.max(memory.relevance, other.relevance);
                store.deleteEvent(guildId, other.id);
            }
            default -> {
                return "Accion valida: confirmar, renombrar, editar, irrelevante, eliminar o fusionar.";
            }
        }
        store.saveEvent(memory);
        return "Evento " + memory.id + " actualizado.";
    }

    public String administerEra(String guildId, String action, String id, String name, String description) {
        String normalizedAction = normalize(action);
        if ("crear".equals(normalizedAction)) {
            if (name == null || name.isBlank()) return "Debes indicar `nombre`.";
            Era era = new Era();
            era.id = store.nextEraId();
            era.guildId = guildId;
            era.name = limit(name, 100);
            era.description = limit(description, 500);
            era.startedAt = Instant.now().toString();
            era.confirmed = true;
            store.saveEra(era);
            return "Era " + era.id + " creada.";
        }

        Era era = store.getEra(guildId, id);
        if (era == null) return "No encontre esa era.";
        switch (normalizedAction) {
            case "renombrar" -> {
                if (name == null || name.isBlank()) return "Debes indicar `nombre`.";
                era.name = limit(name, 100);
            }
            case "editar" -> {
                if (description == null || description.isBlank()) return "Debes indicar `descripcion`.";
                era.description = limit(description, 500);
            }
            case "confirmar" -> era.confirmed = true;
            case "eliminar" -> {
                return store.deleteEra(guildId, id) ? "Era eliminada." : "No se pudo eliminar.";
            }
            default -> {
                return "Accion valida: crear, renombrar, editar, confirmar o eliminar.";
            }
        }
        store.saveEra(era);
        return "Era " + era.id + " actualizada.";
    }

    static int relevance(int participants, int messages, long durationMinutes, int culturalSignals) {
        double participantScore = Math.min(30, participants * 3.0);
        double messageScore = Math.min(30, Math.log1p(messages) / Math.log(500) * 30);
        double durationScore = Math.min(20, durationMinutes / 12.0);
        double cultureScore = Math.min(20, culturalSignals * 2.5);
        return (int) Math.round(Math.min(100, participantScore + messageScore + durationScore + cultureScore));
    }

    private static int relevance(ActivityWindow window) {
        int culture = window.emojiCounts.values().stream().mapToInt(Integer::intValue).sum()
                + window.repeatedPhrases().size() * 2;
        return relevance(window.participantIds.size(), window.messageCount,
                Duration.between(window.firstAt, window.lastAt).toMinutes(), culture);
    }

    static boolean isProhibited(String content) {
        if (content == null) return false;
        return EMAIL.matcher(content).find() || PHONE.matcher(content).find() || TOKEN.matcher(normalize(content)).find();
    }

    private static boolean shouldRetrieveHistory(String query) {
        Set<String> terms = MemoryStore.terms(normalize(query));
        return terms.stream().anyMatch(HISTORICAL_INTENT::contains);
    }

    private static String provisionalTitle(ActivityWindow window, String cultural) {
        if (cultural != null) return "El momento de " + limit(cultural, 60);
        return "Encuentro en #" + limit(window.channelName, 50);
    }

    private static String safeChannelName(MessageReceivedEvent event) {
        try {
            return event.getChannel().getName();
        } catch (RuntimeException ignored) {
            return event.getChannel().getId();
        }
    }

    private static Set<String> readableChannels(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return Set.of(event.getChannel().getId());
        Set<String> readable = new LinkedHashSet<>();
        for (GuildChannel channel : event.getGuild().getChannels()) {
            boolean botCanRead = event.getGuild().getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL);
            boolean userCanRead = event.getMember() != null && event.getMember().hasPermission(channel, Permission.VIEW_CHANNEL);
            if (botCanRead && userCanRead) readable.add(channel.getId());
        }
        return readable;
    }

    private static Set<String> significantTerms(String text) {
        Set<String> terms = new LinkedHashSet<>(MemoryStore.terms(text));
        terms.removeAll(Set.of("hola", "jaja", "ajaj", "mucho", "bien", "solo", "tiene", "hacer", "ahora",
                "dice", "dijo", "puede", "vamos", "porque", "pero", "todo", "algo", "tambien"));
        return terms;
    }

    private static String bestTopic(Set<String> tags) {
        return tags.stream().filter(item -> item.length() >= 4 && !item.startsWith(":"))
                .findFirst().orElseGet(() -> tags.stream().findFirst().orElse("la comunidad"));
    }

    private static String sourceNames(CollectiveMemory memory) {
        if (memory.sources == null || memory.sources.isEmpty()) return "<#" + memory.channelId + ">";
        return memory.sources.values().stream().map(source -> "#" + source.channelName)
                .distinct().collect(java.util.stream.Collectors.joining(", "));
    }

    private static String formatFacts(CollectiveMemory memory) {
        if (memory.facts == null || memory.facts.isEmpty()) return "sin hechos concretos extraidos";
        return memory.facts.stream().limit(6)
                .map(fact -> fact.subject + " " + fact.predicate + " " + fact.object
                        + " [" + String.format(Locale.ROOT, "%.2f", fact.confidence) + "]")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String chapterName(int index, int total, CollectiveMemory memory, List<Era> eras) {
        Instant at = parseInstant(memory.startedAt);
        for (Era era : eras) {
            Instant start = parseInstant(era.startedAt);
            Instant end = parseInstant(era.endedAt);
            if (at != null && (start == null || !at.isBefore(start)) && (end == null || !at.isAfter(end)))
                return "Capitulo: " + era.name + (era.confirmed ? "" : " (provisional)");
        }
        if (total <= 3 || index < Math.max(1, total / 3)) return "Capitulo I - Los comienzos observados";
        if (index < Math.max(2, total * 2 / 3)) return "Capitulo II - El crecimiento";
        return "Capitulo III - La historia reciente";
    }

    private static Instant parseMoment(String value) {
        String text = value == null ? "" : value.trim();
        try {
            if (text.matches("\\d{4}")) return Year.parse(text).atMonth(12).atEndOfMonth().atTime(23, 59).toInstant(ZoneOffset.UTC);
            if (text.matches("\\d{4}-\\d{2}")) return YearMonth.parse(text).atEndOfMonth().atTime(23, 59).toInstant(ZoneOffset.UTC);
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(text).atTime(23, 59).toInstant(ZoneOffset.UTC);
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean looksHumorous(String text) {
        String normalized = normalize(text);
        return normalized.contains("jaja") || normalized.contains("xd") || normalized.contains("😂")
                || normalized.contains("🤣") || normalized.contains(":v");
    }

    private static double evolve(double current, double observation, double weight) {
        return MemoryModels.clamp(current * (1.0 - weight) + observation * weight);
    }

    private void submit(Runnable operation) {
        if (closed) return;
        try {
            worker.execute(() -> {
                try {
                    operation.run();
                } catch (RuntimeException e) {
                    System.err.println("La memoria continuo en modo degradado: " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // El bot se esta apagando; la conversacion no debe fallar por esto.
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String env(String key, String fallback) {
        return Environment.get(key, fallback);
    }

    private static int intEnv(String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(env(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean boolEnv(String key, boolean fallback) {
        return Boolean.parseBoolean(Environment.get(key, Boolean.toString(fallback)));
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        try {
            worker.submit(() -> windows.values().forEach(this::finalizeWindow)).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        worker.shutdownNow();
        store.flush();
    }

    private record Observation(
            String guildId,
            boolean guildMessage,
            String channelId,
            String channelName,
            String userId,
            String content,
            Instant at
    ) {
    }

    private record RecentSignal(
            String channelId,
            String channelName,
            String userId,
            String content,
            Instant at
    ) {
    }

    record SemanticPayload(
            String title,
            String summary,
            String interpretation,
            List<String> tags,
            List<MemoryFact> facts
    ) {
    }

    private static final class ActivityWindow {
        final String guildId;
        final String channelId;
        final String channelName;
        final Instant firstAt;
        Instant lastAt;
        int messageCount;
        final Set<String> participantIds = new LinkedHashSet<>();
        final Map<String, Integer> emojiCounts = new LinkedHashMap<>();
        final Map<String, Integer> phraseCounts = new LinkedHashMap<>();
        final Map<String, Integer> topicCounts = new LinkedHashMap<>();
        final List<String> semanticLines = new ArrayList<>();

        ActivityWindow(String guildId, String channelId, String channelName, Instant firstAt) {
            this.guildId = guildId;
            this.channelId = channelId;
            this.channelName = channelName;
            this.firstAt = firstAt;
            this.lastAt = firstAt;
        }

        void accept(Observation observation) {
            messageCount++;
            lastAt = observation.at;
            participantIds.add(observation.userId);
            if (isProhibited(observation.content)) return;
            if (semanticLines.size() < 80) semanticLines.add("[user:<@" + observation.userId + ">] "
                    + limit(observation.content.replaceAll("\\s+", " "), 2_000));
            Matcher emoji = CUSTOM_EMOJI.matcher(observation.content);
            while (emoji.find()) emojiCounts.merge(emoji.group(1).toLowerCase(Locale.ROOT), 1, Integer::sum);
            String phrase = normalize(observation.content).replaceAll("<a?:[a-z0-9_]+:\\d+>", "").trim();
            int words = phrase.isBlank() ? 0 : phrase.split("\\s+").length;
            if (words >= 2 && words <= 8 && phrase.length() <= 100) phraseCounts.merge(phrase, 1, Integer::sum);
            significantTerms(phrase).forEach(term -> topicCounts.merge(term, 1, Integer::sum));
        }

        List<String> repeatedPhrases() {
            return phraseCounts.entrySet().stream().filter(entry -> entry.getValue() >= 4)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        }

        String topCulturalMarker() {
            Map.Entry<String, Integer> emoji = emojiCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() >= 5)
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (emoji != null) return ":" + emoji.getKey() + ":";
            return repeatedPhrases().stream().findFirst().orElse(null);
        }

        List<String> topTopics() {
            return topicCounts.entrySet().stream().filter(entry -> entry.getValue() >= 2)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(Map.Entry::getKey).toList();
        }
    }

    private static final class TrendCandidate {
        final String originUserId;
        final String originChannelId;
        final Instant firstSeenAt;
        int uses;
        final Set<String> participantIds = new LinkedHashSet<>();
        final Set<String> channelIds = new LinkedHashSet<>();

        TrendCandidate(String originUserId, String originChannelId, Instant firstSeenAt) {
            this.originUserId = originUserId;
            this.originChannelId = originChannelId;
            this.firstSeenAt = firstSeenAt;
        }

        void accept(Observation observation) {
            uses++;
            participantIds.add(observation.userId);
            channelIds.add(observation.channelId);
        }
    }
}
