package discriminebot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Carga la voz de Brittany desde los archivos de personalizacion.
 *
 * Principalpers.txt contiene las reglas y los demas archivos son ejemplos.
 * Se intenta primero la carpeta del proyecto para que los cambios se apliquen
 * sin recompilar; el classpath permite que tambien funcione desde un JAR.
 */
public final class BrittanyPersonalityLoader {
    private static final String PRINCIPAL_FILE = "Principalpers.txt";
    private static final String ADDITIONAL_RULES_FILE = "DirectricesBrittany.txt";
    private static final String DISCRIMINE_KNOWLEDGE_FILE = "ConocimientoDISCRIMINE.txt";
    private static final String PERSONALITY_DIRECTORY_ENV = "AI_PERSONALITY_DIR";
    private static final String DEFAULT_DIRECTORY = "src/main/java/personalizacion";

    private BrittanyPersonalityLoader() {
    }

    public static String load() {
        String directory = environmentOrDefault(PERSONALITY_DIRECTORY_ENV, DEFAULT_DIRECTORY);
        Path directoryPath = Path.of(directory);

        String principal = readFileOrResource(
                directoryPath.resolve(PRINCIPAL_FILE),
                "personalizacion/" + PRINCIPAL_FILE
        );
        String additionalRules = readFileOrResource(
                directoryPath.resolve(ADDITIONAL_RULES_FILE),
                "personalizacion/" + ADDITIONAL_RULES_FILE
        );
        List<NamedText> examples = readExamples(directoryPath);

        if (principal.isBlank() && additionalRules.isBlank() && examples.isEmpty()) {
            System.err.println("No se encontro la personalizacion de Brittany en " + directoryPath);
            return "";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                --- PERSONALIDAD DE BRITTANY ---
                Usa las siguientes instrucciones para escribir todas tus respuestas.
                Principalpers.txt es la fuente de verdad de la personalidad y tiene prioridad.
                DirectricesBrittany.txt contiene directrices complementarias y obligatorias para
                aplicar la personalidad, la emocion y el uso de emojis.
                Los archivos restantes son ejemplos de estilo: aprende su ritmo, calidez y
                forma de expresarse, pero no copies sus hechos, anuncios ni menciones literalmente.
                Responde al mensaje actual y no inventes informacion solo porque aparezca en un ejemplo.
                Mantente en primera persona cuando hables como Brittany.
                No incluyas etiquetas como @everyone ni menciones masivas salvo que el usuario lo pida
                explicitamente y sea apropiado.

                """);

        if (!principal.isBlank()) {
            prompt.append("[REGLAS PRINCIPALES: Principalpers.txt]\n");
            prompt.append(principal.trim()).append("\n\n");
        }

        if (!additionalRules.isBlank()) {
            prompt.append("[DIRECTRICES COMPLEMENTARIAS: DirectricesBrittany.txt]\n");
            prompt.append(additionalRules.trim()).append("\n\n");
        }

        if (!examples.isEmpty()) {
            prompt.append("[EJEMPLOS DE COMO ESCRIBE BRITTANY]\n");
            for (NamedText example : examples) {
                prompt.append("--- ").append(example.name()).append(" ---\n")
                        .append(example.content().trim()).append("\n\n");
            }
        }

        return prompt.toString().trim();
    }

    private static List<NamedText> readExamples(Path directoryPath) {
        if (!Files.isDirectory(directoryPath)) {
            return readExamplesFromClasspath();
        }

        try (Stream<Path> files = Files.list(directoryPath)) {
            List<NamedText> examples = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase(PRINCIPAL_FILE))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase(ADDITIONAL_RULES_FILE))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase(DISCRIMINE_KNOWLEDGE_FILE))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(path -> new NamedText(path.getFileName().toString(), readFile(path)))
                    .filter(example -> !example.content().isBlank())
                    .toList();
            if (!examples.isEmpty()) {
                return examples;
            }
        } catch (IOException e) {
            System.err.println("No se pudieron leer los ejemplos de Brittany en "
                    + directoryPath + ": " + e.getMessage());
        }
        return readExamplesFromClasspath();
    }

    private static List<NamedText> readExamplesFromClasspath() {
        try (InputStream ignored = BrittanyPersonalityLoader.class
                .getResourceAsStream("/personalizacion/" + PRINCIPAL_FILE)) {
            // La existencia del archivo principal indica que los recursos fueron empaquetados.
            if (ignored == null) {
                return List.of();
            }
        } catch (IOException e) {
            return List.of();
        }

        return List.of("personalizacion1.txt", "personalizacion2.txt", "personalizacion3.txt", "personalizacion4.txt")
                .stream()
                .map(name -> new NamedText(name, readResource("/personalizacion/" + name)))
                .filter(example -> !example.content().isBlank())
                .toList();
    }

    private static String readFileOrResource(Path path, String resourcePath) {
        if (Files.isRegularFile(path)) {
            return readFile(path);
        }
        return readResource("/" + resourcePath);
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("No se pudo leer " + path + ": " + e.getMessage());
            return "";
        }
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = BrittanyPersonalityLoader.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("No se pudo leer el recurso de personalizacion " + resourcePath
                    + ": " + e.getMessage());
            return "";
        }
    }

    private static String environmentOrDefault(String key, String fallback) {
        return Environment.get(key, fallback);
    }

    private record NamedText(String name, String content) {
        private NamedText {
            Objects.requireNonNull(name);
            Objects.requireNonNull(content);
        }
    }
}
