package discriminebot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Modelos persistentes de la capa de memoria. No contienen mensajes completos. */
final class MemoryModels {
    private MemoryModels() {
    }

    enum PrivacyLevel { PUBLIC, PERSONAL, PRIVATE, PROHIBITED }

    enum MemoryLayer { EPISODIC, SOCIAL, CULTURAL, HISTORICAL }

    enum MemoryStatus { PROVISIONAL, CONFIRMED, IRRELEVANT, DELETED }

    enum RelationType { RELATED_TO, GENERATED, CAUSED, MENTIONED_AFTER, PRECEDED, MERGED_FROM }

    enum TrendStatus { EMERGING, POPULAR, HISTORICAL, FADING, LOST }

    static final class MemorySource {
        String channelId;
        String channelName;
        int messageCount;
        String firstSeenAt;
        String lastSeenAt;

        MemorySource() {
        }

        MemorySource(String channelId, String channelName, int messageCount, String firstSeenAt, String lastSeenAt) {
            this.channelId = channelId;
            this.channelName = channelName;
            this.messageCount = messageCount;
            this.firstSeenAt = firstSeenAt;
            this.lastSeenAt = lastSeenAt;
        }
    }

    static final class MemoryRelation {
        String targetId;
        RelationType type = RelationType.RELATED_TO;
        double confidence;
        String evidence;

        MemoryRelation() {
        }

        MemoryRelation(String targetId, RelationType type, double confidence, String evidence) {
            this.targetId = targetId;
            this.type = type;
            this.confidence = clamp(confidence);
            this.evidence = evidence;
        }
    }

    static final class ConversationDigest {
        String id;
        String channelId;
        String startedAt;
        String endedAt;
        int messageCount;
        Set<String> participantIds = new LinkedHashSet<>();
        Set<String> topics = new LinkedHashSet<>();
        String summary;
    }

    static final class SessionDigest {
        String id;
        String startedAt;
        String endedAt;
        List<String> conversationIds = new ArrayList<>();
        String summary;
    }

    static final class MemoryFact {
        String subject;
        String predicate;
        String object;
        String sourceChannelId;
        String observedAt;
        double confidence;
    }

    static final class CollectiveMemory {
        String id;
        String guildId;
        String channelId;
        String title;
        String summary;
        String startedAt;
        String endedAt;
        int participantCount;
        int messageCount;
        int relevance;
        int futureReferences;
        String lastReferenceAt;
        PrivacyLevel privacy = PrivacyLevel.PUBLIC;
        MemoryStatus status = MemoryStatus.PROVISIONAL;
        Set<MemoryLayer> layers = new LinkedHashSet<>();
        Set<String> participantIds = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        Set<String> relatedMemoryIds = new LinkedHashSet<>();
        /** Fuentes trazables; channelId se conserva para migrar datos antiguos. */
        Map<String, MemorySource> sources = new LinkedHashMap<>();
        List<MemoryRelation> relations = new ArrayList<>();
        List<String> conversationSummaries = new ArrayList<>();
        Map<String, ConversationDigest> conversations = new LinkedHashMap<>();
        List<SessionDigest> sessions = new ArrayList<>();
        List<MemoryFact> facts = new ArrayList<>();
        String factualSummary;
        String interpretation;
        double interpretationConfidence;
        String eraId;
        String createdAt = Instant.now().toString();
        String updatedAt = createdAt;
    }

    static final class Preference {
        double value;
        double confidence;
        boolean explicit;
        String updatedAt;

        Preference() {
        }

        Preference(double value, double confidence, boolean explicit) {
            this.value = clamp(value);
            this.confidence = clamp(confidence);
            this.explicit = explicit;
            this.updatedAt = Instant.now().toString();
        }
    }

    static final class ConversationProfile {
        String guildId;
        String userId;
        PrivacyLevel privacy = PrivacyLevel.PERSONAL;
        boolean personalizationEnabled = true;
        boolean learningEnabled = true;
        long interactions;
        double familiarity;
        double sharedHumor;
        String firstSeenAt;
        String lastSeenAt;
        Map<String, Integer> channelActivity = new LinkedHashMap<>();
        Map<String, Integer> topicActivity = new LinkedHashMap<>();
        List<String> milestones = new ArrayList<>();
        String generationId;
        Map<String, Preference> preferences = new LinkedHashMap<>();
        String createdAt = Instant.now().toString();
        String updatedAt = createdAt;
    }

    static final class Era {
        String id;
        String guildId;
        String name;
        String description;
        String startedAt;
        String endedAt;
        boolean confirmed;
        boolean proposedAutomatically;
        List<String> memoryIds = new ArrayList<>();
        String updatedAt = Instant.now().toString();
    }

    static final class CulturalTrend {
        String id;
        String guildId;
        String marker;
        TrendStatus status = TrendStatus.EMERGING;
        String firstSeenAt;
        String lastSeenAt;
        String peakAt;
        int totalUses;
        int recentUses;
        int previousUses;
        double growthPercent;
        Set<String> participantIds = new LinkedHashSet<>();
        Set<String> channelIds = new LinkedHashSet<>();
        String originUserId;
        String originChannelId;
        String linkedEventId;
        String updatedAt = Instant.now().toString();
    }

    static final class Generation {
        String id;
        String guildId;
        String name;
        String startedAt;
        String endedAt;
        String boundaryEventId;
        boolean proposedAutomatically;
        boolean confirmed;
        Set<String> memberIds = new LinkedHashSet<>();
        Set<String> sourceChannelIds = new LinkedHashSet<>();
        String updatedAt = Instant.now().toString();
    }

    static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
