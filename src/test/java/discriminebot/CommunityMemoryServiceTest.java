package discriminebot;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.time.Instant;
import java.util.HashSet;

public class CommunityMemoryServiceTest extends TestCase {
    public void testRelevanceGrowsWithActivity() {
        int small = CommunityMemoryService.relevance(3, 15, 10, 0);
        int large = CommunityMemoryService.relevance(20, 1_800, 221, 8);
        assertTrue(small > 0);
        assertTrue(large > small);
        assertTrue(large >= 95);
    }

    public void testSensitiveTextIsProhibited() {
        assertTrue(CommunityMemoryService.isProhibited("mi correo es persona@example.com"));
        assertTrue(CommunityMemoryService.isProhibited("mi password es secreto"));
        assertFalse(CommunityMemoryService.isProhibited("prefiero respuestas cortas"));
    }

    public void testDiscrimineKnowledgeIsRetrievedForProjectQuestions() {
        DiscrimineKnowledgeBase knowledge = new DiscrimineKnowledgeBase();
        String definition = knowledge.contextFor("¿Que es DISCRIMINE?");
        String mobile = knowledge.contextFor("¿DISCRIMINE funciona en Android con 4 GB de RAM?");

        assertTrue(definition.contains("DISCRIMINE ES UNA EXPERIENCIA"));
        assertTrue(mobile.contains("4 GB DE RAM"));
        assertEquals("", knowledge.contextFor("¿Como estas hoy?"));
    }

    public void testCollectiveIndexAndPhysicalDeletion() throws Exception {
        Path directory = Files.createTempDirectory("brittany-memory-test-");
        try {
            MemoryStore store = new MemoryStore(directory);
            MemoryModels.CollectiveMemory event = new MemoryModels.CollectiveMemory();
            event.id = store.nextEventId();
            event.guildId = "guild";
            event.channelId = "channel";
            event.title = "La Guerra de los Stickers";
            event.summary = "Un evento publico con stickers.";
            event.startedAt = "2026-01-01T00:00:00Z";
            event.endedAt = "2026-01-01T01:00:00Z";
            event.relevance = 92;
            event.layers.add(MemoryModels.MemoryLayer.EPISODIC);
            event.tags.add("stickers");
            store.saveEvent(event);

            assertEquals(event.id, store.findEvents("guild", "stickers", 5).get(0).id);
            assertTrue(store.deleteEvent("guild", event.id));
            assertTrue(store.findEvents("guild", "stickers", 5).isEmpty());
            assertFalse(Files.readString(directory.resolve("collective-events.jsonl"))
                    .contains("Guerra de los Stickers"));
        } finally {
            deleteTree(directory);
        }
    }

    public void testPersonalProfileNeverAppearsInCollectiveSearch() throws Exception {
        Path directory = Files.createTempDirectory("brittany-profile-test-");
        try {
            MemoryStore store = new MemoryStore(directory);
            MemoryModels.ConversationProfile profile = store.getOrCreateProfile("guild", "private-user");
            profile.preferences.put("brevity", new MemoryModels.Preference(1, 0.98, true));
            store.saveProfile(profile);

            assertTrue(store.findEvents("guild", "private-user brevity", 10).isEmpty());
            store.deleteProfile("guild", "private-user", false);
            assertNull(store.getProfile("guild", "private-user"));
            assertFalse(Files.readString(directory.resolve("personal-profiles.jsonl")).contains("brevity"));
        } finally {
            deleteTree(directory);
        }
    }

    public void testCrossChannelMemoryRequiresAllSourcesToBeReadable() throws Exception {
        Path directory = Files.createTempDirectory("brittany-permissions-test-");
        try {
            MemoryStore store = new MemoryStore(directory);
            MemoryModels.CollectiveMemory event = new MemoryModels.CollectiveMemory();
            event.id = store.nextEventId();
            event.guildId = "guild";
            event.channelId = "general";
            event.title = "Torneo 2026";
            event.summary = "Evento reconstruido entre general y gaming.";
            event.startedAt = "2026-03-01T00:00:00Z";
            event.endedAt = "2026-03-02T00:00:00Z";
            event.relevance = 90;
            event.sources.put("general", new MemoryModels.MemorySource("general", "general", 12,
                    event.startedAt, event.endedAt));
            event.sources.put("gaming", new MemoryModels.MemorySource("gaming", "gaming", 20,
                    event.startedAt, event.endedAt));
            event.tags.add("torneo");
            store.saveEvent(event);

            assertEquals(1, store.findEvents("guild", "torneo", 5, Set.of("general", "gaming")).size());
            assertTrue(store.findEvents("guild", "torneo", 5, Set.of("general")).isEmpty());
            assertTrue(store.findEvents("guild", "torneo", 5, Set.of("gaming")).isEmpty());
        } finally {
            deleteTree(directory);
        }
    }

    public void testGraphTrendAndGenerationSurviveRestart() throws Exception {
        Path directory = Files.createTempDirectory("brittany-history-test-");
        try {
            MemoryStore store = new MemoryStore(directory);
            MemoryModels.CulturalTrend trend = new MemoryModels.CulturalTrend();
            trend.id = store.nextTrendId();
            trend.guildId = "guild";
            trend.marker = "eso fue calculado";
            trend.totalUses = 43;
            trend.firstSeenAt = "2026-08-03T00:00:00Z";
            trend.lastSeenAt = "2026-08-25T00:00:00Z";
            trend.originUserId = "user-1";
            trend.originChannelId = "general";
            trend.channelIds.add("general");
            store.saveTrend(trend);

            MemoryModels.Generation generation = new MemoryModels.Generation();
            generation.id = store.nextGenerationId();
            generation.guildId = "guild";
            generation.name = "Generacion fundadora observada";
            generation.startedAt = "2025-01-01T00:00:00Z";
            generation.memberIds.add("user-1");
            store.saveGeneration(generation);

            MemoryStore reloaded = new MemoryStore(directory);
            assertEquals(43, reloaded.getTrendByMarker("guild", "eso fue calculado").totalUses);
            assertEquals("user-1", reloaded.getTrendByMarker("guild", "eso fue calculado").originUserId);
            assertEquals(1, reloaded.listGenerations("guild").size());
            assertTrue(reloaded.listGenerations("guild").get(0).memberIds.contains("user-1"));
        } finally {
            deleteTree(directory);
        }
    }

    public void testSemanticFactsAreValidatedAndStructured() {
        String json = """
                ```json
                {"title":"Torneo de agosto","summary":"Pedro organizo el torneo.",
                 "interpretation":"Fue importante.","tags":["Torneo","gaming","torneo"],
                 "facts":[
                   {"subject":"<@123>","predicate":"organizo","object":"el torneo","confidence":0.91},
                   {"subject":"Rumor","predicate":"causo","object":"todo","confidence":0.20}
                 ]}
                ```
                """;
        CommunityMemoryService.SemanticPayload payload = CommunityMemoryService.parseSemanticPayload(
                json, "gaming", Instant.parse("2026-08-25T12:00:00Z"));

        assertEquals("Torneo de agosto", payload.title());
        assertEquals(2, payload.tags().size());
        assertEquals(1, payload.facts().size());
        assertEquals("<@123>", payload.facts().get(0).subject);
        assertEquals("gaming", payload.facts().get(0).sourceChannelId);
        assertEquals(0.91, payload.facts().get(0).confidence, 0.001);
    }

    public void testSlashCommandCatalogIsValidAndHasNoDuplicates() {
        var commands = MemoryCommandHandler.commands();
        Set<String> names = new HashSet<>();
        commands.forEach(command -> {
            assertTrue(command.getName().matches("[a-z0-9_-]{1,32}"));
            assertTrue("Comando duplicado: " + command.getName(), names.add(command.getName()));
        });
        assertTrue(names.contains("viajar"));
        assertTrue(names.contains("memestado"));
        assertEquals("La consulta no produjo resultados.", MemoryCommandHandler.safeResponse(""));
        assertEquals(2_000, MemoryCommandHandler.safeResponse("x".repeat(2_100)).length());
        assertTrue(MemoryCommandHandler.shouldCleanGlobalDuplicates("guild", "true"));
        assertFalse(MemoryCommandHandler.shouldCleanGlobalDuplicates("global", "true"));
        assertFalse(MemoryCommandHandler.shouldCleanGlobalDuplicates("guild", "false"));
    }

    public void testUnlimitedConversationMemoryNeverTrimsStoredMessages() throws Exception {
        Path directory = Files.createTempDirectory("brittany-unlimited-test-");
        try {
            Path file = directory.resolve("conversation.json");
            ConversationMemoryStore store = new ConversationMemoryStore(file, 0);
            for (int index = 0; index < 250; index++) {
                store.append("guild:channel", "user", "mensaje-" + index);
            }
            assertEquals(250, store.get("guild:channel").size());
            ConversationMemoryStore reloaded = new ConversationMemoryStore(file, 0);
            assertEquals(250, reloaded.get("guild:channel").size());
            assertEquals("mensaje-0", reloaded.get("guild:channel").get(0).content());
        } finally {
            deleteTree(directory);
        }
    }

    public void testLegacyConversationJsonMigratesToJsonlWithoutLoss() throws Exception {
        Path directory = Files.createTempDirectory("brittany-legacy-memory-test-");
        try {
            Path file = directory.resolve("ai-memory.json");
            Files.writeString(file, """
                    {"guild:channel":[
                      {"role":"user","content":"hola desde el formato anterior","at":"2026-01-01T00:00:00Z"},
                      {"role":"assistant","content":"hola","at":"2026-01-01T00:00:01Z"}
                    ]}
                    """);
            ConversationMemoryStore migrated = new ConversationMemoryStore(file, 0);
            assertEquals(2, migrated.get("guild:channel").size());
            assertTrue(Files.readString(file).contains("\"conversationId\":\"guild:channel\""));
            assertEquals(2, new ConversationMemoryStore(file, 0).get("guild:channel").size());
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
