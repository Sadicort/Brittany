package discriminebot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;

/** Comandos nuevos; se registran con upsert para conservar los comandos existentes. */
public final class MemoryCommandHandler extends ListenerAdapter {
    private static final Set<String> PRIVATE_COMMANDS = Set.of(
            "miperfil", "mievolucion", "memoria", "olvidarme", "memadmin", "eraadmin", "memestado"
    );
    private static final Set<String> SUPPORTED_COMMANDS = Set.of(
            "historia", "cronica", "eventos", "evento", "eras", "museo", "veteranos", "viajar",
            "tendencias", "perdidos", "identidad", "generaciones", "mievolucion", "origen",
            "miperfil", "memoria", "olvidarme", "memadmin", "eraadmin", "memestado"
    );
    private final CommunityMemoryService memory;

    public MemoryCommandHandler(CommunityMemoryService memory) {
        this.memory = memory;
    }

    @Override
    public void onReady(ReadyEvent event) {
        String scope = env("MEMORY_COMMAND_SCOPE", "guild").toLowerCase(Locale.ROOT);
        if ("global".equals(scope) || "both".equals(scope)) {
            for (CommandData command : commands()) registerGlobal(event, command);
        }
        if (shouldCleanGlobalDuplicates(scope, env("MEMORY_CLEAN_DUPLICATE_GLOBALS", "true"))) {
            cleanGlobalDuplicates(event);
        }
        if (!"global".equals(scope)) {
            for (Guild guild : event.getJDA().getGuilds()) {
                registerGuildCommands(guild);
            }
        }
        System.out.println("Registro de comandos de memoria iniciado. Alcance=" + scope
                + ", servidores=" + event.getJDA().getGuilds().size() + ", comandos=" + commands().size());
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        String scope = env("MEMORY_COMMAND_SCOPE", "guild").toLowerCase(Locale.ROOT);
        if (!"global".equals(scope)) registerGuildCommands(event.getGuild());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!SUPPORTED_COMMANDS.contains(event.getName())) return;
        boolean privateReply = PRIVATE_COMMANDS.contains(event.getName());
        event.deferReply(privateReply).queue(
                ignored -> executeDeferred(event),
                error -> System.err.println("No se pudo reconocer /" + event.getName() + ": " + error.getMessage())
        );
    }

    private void executeDeferred(SlashCommandInteractionEvent event) {
        try {
            String response = execute(event);
            event.getHook().editOriginal(safeResponse(response)).queue(
                    ignored -> { },
                    error -> System.err.println("No se pudo responder /" + event.getName() + ": " + error.getMessage())
            );
        } catch (RuntimeException error) {
            System.err.println("Fallo ejecutando /" + event.getName() + " en "
                    + (event.isFromGuild() ? event.getGuild().getId() : "dm") + ": " + error.getMessage());
            error.printStackTrace(System.err);
            event.getHook().editOriginal("Ocurrio un error al consultar la memoria. El fallo fue registrado; intenta de nuevo.")
                    .queue(ignored -> { }, replyError -> System.err.println("No se pudo enviar el error: " + replyError.getMessage()));
        }
    }

    private String execute(SlashCommandInteractionEvent event) {
        String guildId = event.isFromGuild() ? event.getGuild().getId() : "dm";
        String userId = event.getUser().getId();
        String response;
        Set<String> readableChannelIds = readableChannels(event);

        switch (event.getName()) {
            case "historia", "cronica", "eventos" -> {
                if (!event.isFromGuild()) {
                    response = "La memoria colectiva solo existe dentro de una comunidad.";
                } else {
                    response = memory.historyAuthorized(guildId, option(event, "tema"), readableChannelIds,
                            intOption(event, "pagina", 1));
                }
            }
            case "evento" -> response = event.isFromGuild()
                    ? memory.eventDetailsAuthorized(guildId, option(event, "id"), readableChannelIds)
                    : "La memoria colectiva solo existe dentro de una comunidad.";
            case "eras" -> response = event.isFromGuild() ? memory.erasAuthorized(guildId, readableChannelIds) : "Este comando requiere una comunidad.";
            case "museo" -> response = event.isFromGuild() ? memory.museumAuthorized(guildId, readableChannelIds) : "Este comando requiere una comunidad.";
            case "veteranos" -> response = event.isFromGuild() ? memory.veteransAuthorized(guildId, readableChannelIds) : "Este comando requiere una comunidad.";
            case "viajar" -> response = event.isFromGuild()
                    ? memory.timeTravel(guildId, option(event, "momento"), readableChannelIds) : "Este comando requiere una comunidad.";
            case "tendencias" -> response = event.isFromGuild()
                    ? memory.trends(guildId, readableChannelIds, false) : "Este comando requiere una comunidad.";
            case "perdidos" -> response = event.isFromGuild()
                    ? memory.trends(guildId, readableChannelIds, true) : "Este comando requiere una comunidad.";
            case "identidad" -> response = event.isFromGuild()
                    ? memory.identity(guildId, readableChannelIds) : "Este comando requiere una comunidad.";
            case "generaciones" -> response = event.isFromGuild()
                    ? memory.generations(guildId, readableChannelIds) : "Este comando requiere una comunidad.";
            case "mievolucion" -> {
                response = memory.evolution(guildId, userId);
            }
            case "origen" -> response = event.isFromGuild()
                    ? memory.origin(guildId, option(event, "frase"), readableChannelIds) : "Este comando requiere una comunidad.";
            case "miperfil" -> {
                response = memory.profile(guildId, userId);
            }
            case "memoria" -> {
                String action = option(event, "accion").toLowerCase(Locale.ROOT);
                response = switch (action) {
                    case "ver" -> memory.profile(guildId, userId);
                    case "activar" -> memory.setPersonalization(guildId, userId, true);
                    case "desactivar" -> memory.setPersonalization(guildId, userId, false);
                    case "aprender" -> memory.setLearning(guildId, userId, true);
                    case "noaprender" -> memory.setLearning(guildId, userId, false);
                    default -> "Accion valida: ver, activar, desactivar, aprender o noaprender.";
                };
            }
            case "olvidarme" -> {
                String confirmation = option(event, "confirmacion");
                if (!"CONFIRMAR".equals(confirmation)) {
                    response = "No cambie nada. Escribe exactamente `CONFIRMAR` para eliminar tu perfil.";
                } else {
                    response = memory.forgetUser(guildId, userId, boolOption(event, "bloquear", true));
                }
            }
            case "memadmin" -> {
                if (!isMemoryAdministrator(event.getMember())) {
                    response = "Necesitas el permiso Administrador o Gestionar servidor.";
                } else {
                    response = memory.administer(guildId, option(event, "accion"), option(event, "id"),
                            option(event, "valor"), option(event, "segundo_id"));
                }
            }
            case "eraadmin" -> {
                if (!isMemoryAdministrator(event.getMember())) {
                    response = "Necesitas el permiso Administrador o Gestionar servidor.";
                } else {
                    response = memory.administerEra(guildId, option(event, "accion"), option(event, "id"),
                            option(event, "nombre"), option(event, "descripcion"));
                }
            }
            case "memestado" -> response = memory.health(guildId, readableChannelIds);
            default -> response = "Comando de memoria desconocido.";
        }
        return response;
    }

    static List<CommandData> commands() {
        return List.of(
                Commands.slash("historia", "Consulta la historia colectiva")
                        .addOption(OptionType.STRING, "tema", "Fecha, evento, meme o tema", false)
                        .addOption(OptionType.INTEGER, "pagina", "Pagina de resultados, desde 1", false),
                Commands.slash("cronica", "Muestra la cronologia de la comunidad")
                        .addOption(OptionType.STRING, "tema", "Tema opcional", false)
                        .addOption(OptionType.INTEGER, "pagina", "Pagina de resultados, desde 1", false),
                Commands.slash("eventos", "Lista eventos relevantes")
                        .addOption(OptionType.STRING, "tema", "Filtro opcional", false)
                        .addOption(OptionType.INTEGER, "pagina", "Pagina de resultados, desde 1", false),
                Commands.slash("evento", "Muestra un evento historico")
                        .addOption(OptionType.STRING, "id", "ID como E000001", true),
                Commands.slash("eras", "Muestra las eras de la comunidad"),
                Commands.slash("museo", "Explora el museo de la comunidad"),
                Commands.slash("veteranos", "Participantes frecuentes en eventos publicos"),
                Commands.slash("viajar", "Reconstruye una epoca de la comunidad")
                        .addOption(OptionType.STRING, "momento", "Fecha: 2025, 2025-08 o 2025-08-14", true),
                Commands.slash("tendencias", "Muestra memes y frases que estan naciendo"),
                Commands.slash("perdidos", "Muestra recuerdos culturales que se estan perdiendo"),
                Commands.slash("identidad", "Resume la identidad cultural actual"),
                Commands.slash("generaciones", "Muestra generaciones y fronteras historicas"),
                Commands.slash("mievolucion", "Muestra tu evolucion privada en la comunidad"),
                Commands.slash("memestado", "Diagnostica el estado de la memoria en este servidor"),
                Commands.slash("origen", "Busca la primera aparicion observada de una frase o meme")
                        .addOption(OptionType.STRING, "frase", "Frase o nombre exacto del meme", true),
                Commands.slash("miperfil", "Muestra tu perfil conversacional privado"),
                Commands.slash("memoria", "Controla tu personalizacion")
                        .addOption(OptionType.STRING, "accion", "ver, activar, desactivar, aprender o noaprender", true),
                Commands.slash("olvidarme", "Elimina tu memoria conversacional personal")
                        .addOption(OptionType.STRING, "confirmacion", "Escribe CONFIRMAR", true)
                        .addOption(OptionType.BOOLEAN, "bloquear", "Impide crear futuras memorias (por defecto si)", false),
                Commands.slash("memadmin", "Administra un evento de memoria colectiva")
                        .addOption(OptionType.STRING, "accion", "confirmar, renombrar, editar, irrelevante, eliminar o fusionar", true)
                        .addOption(OptionType.STRING, "id", "ID principal del evento", true)
                        .addOption(OptionType.STRING, "valor", "Nuevo nombre cuando corresponda", false)
                        .addOption(OptionType.STRING, "segundo_id", "Segundo ID para fusionar", false),
                Commands.slash("eraadmin", "Crea o edita eras de la comunidad")
                        .addOption(OptionType.STRING, "accion", "crear, renombrar, editar, confirmar o eliminar", true)
                        .addOption(OptionType.STRING, "id", "ID para editar; no se usa al crear", false)
                        .addOption(OptionType.STRING, "nombre", "Nombre al crear o renombrar", false)
                        .addOption(OptionType.STRING, "descripcion", "Descripcion al crear o editar", false)
        );
    }

    private static boolean isMemoryAdministrator(Member member) {
        return member != null && (member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER));
    }

    private static String option(SlashCommandInteractionEvent event, String name) {
        OptionMapping option = event.getOption(name);
        return option == null ? "" : option.getAsString().trim();
    }

    private static boolean boolOption(SlashCommandInteractionEvent event, String name, boolean fallback) {
        OptionMapping option = event.getOption(name);
        return option == null ? fallback : option.getAsBoolean();
    }

    private static int intOption(SlashCommandInteractionEvent event, String name, int fallback) {
        OptionMapping option = event.getOption(name);
        if (option == null) return fallback;
        long value = option.getAsLong();
        return value < 1 ? fallback : (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static Set<String> readableChannels(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        for (GuildChannel channel : event.getGuild().getChannels()) {
            boolean botCanRead = event.getGuild().getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL);
            boolean userCanRead = event.getMember() != null && event.getMember().hasPermission(channel, Permission.VIEW_CHANNEL);
            if (botCanRead && userCanRead) ids.add(channel.getId());
        }
        return ids;
    }

    private static void registerGlobal(ReadyEvent event, CommandData command) {
        event.getJDA().upsertCommand(command).queue(
                registered -> System.out.println("Comando global listo: /" + registered.getName()),
                error -> System.err.println("No se pudo registrar globalmente /" + command.getName()
                        + ": " + error.getMessage())
        );
    }

    private static void registerGuild(Guild guild, CommandData command) {
        guild.upsertCommand(command).queue(
                registered -> System.out.println("Comando listo en " + guild.getName() + ": /" + registered.getName()),
                error -> System.err.println("No se pudo registrar /" + command.getName() + " en " + guild.getName()
                        + ": " + error.getMessage()
                        + ". Verifica que el bot fue invitado con el scope applications.commands.")
        );
    }

    private static void registerGuildCommands(Guild guild) {
        for (CommandData command : commands()) registerGuild(guild, command);
    }

    private static void cleanGlobalDuplicates(ReadyEvent event) {
        event.getJDA().retrieveCommands().queue(existing -> {
            Set<String> managedNames = commands().stream().map(CommandData::getName)
                    .collect(java.util.stream.Collectors.toSet());
            existing.stream().filter(command -> managedNames.contains(command.getName())).forEach(command ->
                    event.getJDA().deleteCommandById(command.getId()).queue(
                            ignored -> System.out.println("Copia global duplicada eliminada: /" + command.getName()),
                            error -> System.err.println("No se pudo eliminar la copia global /" + command.getName()
                                    + ": " + error.getMessage())
                    ));
        }, error -> System.err.println("No se pudieron revisar los comandos globales duplicados: " + error.getMessage()));
    }

    static boolean shouldCleanGlobalDuplicates(String scope, String enabled) {
        return "guild".equalsIgnoreCase(scope) && Boolean.parseBoolean(enabled);
    }

    static String safeResponse(String response) {
        String text = response == null || response.isBlank() ? "La consulta no produjo resultados." : response.trim();
        return text.length() <= 2_000 ? text : text.substring(0, 1_997) + "...";
    }

    private static String env(String key, String fallback) {
        return Environment.get(key, fallback);
    }
}
