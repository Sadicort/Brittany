package discriminebot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recupera solo los fragmentos del conocimiento de DISCRIMINE pertinentes a cada pregunta. */
final class DiscrimineKnowledgeBase {
    private static final String FILE_NAME = "ConocimientoDISCRIMINE.txt";
    private static final String DEFAULT_DIRECTORY = "src/main/java/personalizacion";
    private static final int MAX_CONTEXT_CHARS = 7_000;
    private final List<String> paragraphs;

    DiscrimineKnowledgeBase() {
        this.paragraphs = splitParagraphs(loadKnowledge());
        System.out.println("Conocimiento de DISCRIMINE cargado: " + paragraphs.size() + " fragmentos.");
    }

    String contextFor(String query) {
        if (!isAboutDiscrimine(query) || paragraphs.isEmpty()) return "";

        Set<String> queryTerms = expandedTerms(query);
        Set<Integer> selected = new LinkedHashSet<>();
        // La definicion inicial siempre acompana cualquier pregunta sobre el proyecto.
        for (int index = 0; index < Math.min(10, paragraphs.size()); index++) selected.add(index);

        List<ScoredParagraph> scored = new ArrayList<>();
        for (int index = 0; index < paragraphs.size(); index++) {
            int score = score(paragraphs.get(index), queryTerms);
            if (score > 0) scored.add(new ScoredParagraph(index, score));
        }
        scored.sort(Comparator.comparingInt(ScoredParagraph::score).reversed()
                .thenComparingInt(ScoredParagraph::index));
        StringBuilder context = new StringBuilder("""
                --- CONOCIMIENTO OFICIAL RELEVANTE SOBRE EL PROYECTO DISCRIMINE ---
                El usuario pregunta por el proyecto DISCRIMINE, no por tu identidad como Brittany.
                Debes contestar su pregunta usando los datos siguientes. Da al menos un dato concreto,
                responde de forma natural con la voz de Brittany y no digas que careces de informacion.
                No inventes detalles que no aparezcan aqui ni recites todo el documento.

                """);
        for (int index : selected) {
            String paragraph = paragraphs.get(index);
            if (context.length() + paragraph.length() + 2 > MAX_CONTEXT_CHARS) continue;
            context.append(paragraph).append("\n\n");
        }
        // Despues de la definicion, los fragmentos mas relevantes van primero,
        // aunque se encuentren al final del documento (por ejemplo Android).
        for (ScoredParagraph item : scored.stream().limit(18).toList()) {
            if (!selected.add(item.index)) continue;
            String paragraph = paragraphs.get(item.index);
            if (context.length() + paragraph.length() + 2 > MAX_CONTEXT_CHARS) continue;
            context.append(paragraph).append("\n\n");
        }
        return context.toString().trim();
    }

    static boolean isAboutDiscrimine(String query) {
        String normalized = normalize(query);
        return normalized.contains("discrimine") || normalized.contains("discriminé");
    }

    private static int score(String paragraph, Set<String> terms) {
        String normalized = normalize(paragraph);
        int score = 0;
        for (String term : terms) {
            if (normalized.contains(term)) score += term.length() >= 7 ? 3 : 1;
        }
        return score;
    }

    private static Set<String> expandedTerms(String query) {
        String normalized = normalize(query);
        Set<String> terms = terms(normalized);
        if (containsAny(normalized, "survival", "recursos", "caballero", "reino", "dragon", "heroe")) {
            terms.addAll(Set.of("survival", "recursos", "caballero", "reino", "dragon", "heroe", "jugador"));
        }
        if (containsAny(normalized, "combate", "competitivo", "pvp", "arma", "arco", "flecha")) {
            terms.addAll(Set.of("combate", "competitivo", "pvp", "armas", "arco", "flechas", "habilidad"));
        }
        if (containsAny(normalized, "clase", "lore", "magia", "duende", "samurai")) {
            terms.addAll(Set.of("clases", "lore", "magia", "duendes", "samurais", "habilidades"));
        }
        if (containsAny(normalized, "android", "movil", "telefono", "bedrock", "java", "launcher", "control", "ram")) {
            terms.addAll(Set.of("android", "movil", "telefono", "bedrock", "java", "launchers", "control", "ram"));
        }
        if (containsAny(normalized, "torneo", "ranking", "premio", "profesional")) {
            terms.addAll(Set.of("torneos", "ranking", "premios", "profesional", "competitivo"));
        }
        return terms;
    }

    private static Set<String> terms(String normalized) {
        Set<String> result = new LinkedHashSet<>();
        for (String term : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (term.length() >= 3 && !STOP_WORDS.contains(term)) result.add(term);
        }
        return result;
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) if (text.contains(candidate)) return true;
        return false;
    }

    private static List<String> splitParagraphs(String knowledge) {
        if (knowledge.isBlank()) return List.of();
        return List.of(knowledge.split("(?:\\r?\\n){2,}"))
                .stream().map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static String loadKnowledge() {
        String configuredDirectory = Environment.get("AI_PERSONALITY_DIR", "");
        Path directory = Path.of(configuredDirectory == null || configuredDirectory.isBlank()
                ? DEFAULT_DIRECTORY : configuredDirectory.trim());
        Path file = directory.resolve(FILE_NAME);
        try {
            if (Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            System.err.println("No se pudo leer el conocimiento de DISCRIMINE: " + error.getMessage());
        }
        try (InputStream stream = DiscrimineKnowledgeBase.class.getResourceAsStream("/personalizacion/" + FILE_NAME)) {
            return stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            System.err.println("No se pudo cargar el conocimiento empaquetado de DISCRIMINE: " + error.getMessage());
            return "";
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "que", "como", "con", "por", "para", "una", "uno", "del", "las", "los", "algo",
            "informacion", "dime", "sobre", "cual", "cuando", "donde", "esto", "esta", "este"
    );

    private record ScoredParagraph(int index, int score) {
    }
}
