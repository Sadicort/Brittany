package discriminebot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {


    private static final BotContextStore CONTEXT = new BotContextStore(Path.of("data", "bot-context.properties"));
    private static final Random RANDOM = new Random();
    private static final String STARTUP_ANNOUNCEMENT_CHANNEL_ID = "1399538983892942959";
    private static final String DOT_DOT_MESSAGE = "";

    public static void main(String[] args) throws Exception {
        String token = getenvOrDefault("DISCRIMINE_BOT_TOKEN", "MTM5MDE0NzQ3MDk2NDU1NTkwNw.Gube6O.wHKXb0btqgY0ZHKk_xsAnUcpKU-qN38Rd8yz54");
        if (token.isBlank()) {
            throw new IllegalStateException("Falta DISCRIMINE_BOT_TOKEN. Configurala como variable de entorno, "
                    + "propiedad Java -DDISCRIMINE_BOT_TOKEN=... o dentro del archivo local .env.");
        }
        OllamaConfig ollamaConfig = OllamaConfig.load();
        CommunityMemoryService communityMemory = new CommunityMemoryService();
        AiConversationService aiService = new AiConversationService(ollamaConfig, communityMemory);
        BotController controller = new BotController(token, aiService, communityMemory);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            controller.stop();
            aiService.close();
            communityMemory.close();
        }, "discrimine-shutdown"));

        controller.start();
    }

    public static class BotController {
        private final String token;
        private final AiConversationService aiService;
        private final CommunityMemoryService communityMemory;
        private final Object lifecycleLock = new Object();
        private volatile JDA jda;
        private volatile AutomaticSender automaticSender;
        private volatile boolean online;

        public BotController(String token, AiConversationService aiService, CommunityMemoryService communityMemory) {
            this.token = token;
            this.aiService = aiService;
            this.communityMemory = communityMemory;
        }

        public void start() throws Exception {
            synchronized (lifecycleLock) {
                if (online) return;

                CONTEXT.incrementCounter("startup.count");
                CONTEXT.set("startup.last_at", Instant.now().toString());
                AutomaticSender nextAutomaticSender = new AutomaticSender(CONTEXT);
                JDA nextJda = null;
                try {
                    nextJda = JDABuilder.createDefault(
                                    token,
                                    GatewayIntent.GUILD_MESSAGES,
                                    GatewayIntent.MESSAGE_CONTENT,
                                    GatewayIntent.GUILD_MEMBERS,
                                    GatewayIntent.DIRECT_MESSAGES
                            )
                            .disableCache(
                                    CacheFlag.VOICE_STATE,
                                    CacheFlag.EMOJI,
                                    CacheFlag.STICKER,
                                    CacheFlag.SCHEDULED_EVENTS
                            )
                            .addEventListeners(
                                    new MessageListener(CONTEXT, aiService),
                                    new MemoryCommandHandler(communityMemory),
                                    nextAutomaticSender
                            )
                            .build()
                            .awaitReady();
                    automaticSender = nextAutomaticSender;
                    jda = nextJda;
                    online = true;
                    CONTEXT.set("bot.status", "online");
                    System.out.println("Bot listo y conectado.");
                } catch (Exception error) {
                    nextAutomaticSender.close();
                    if (nextJda != null) nextJda.shutdownNow();
                    throw error;
                }
            }
        }

        public void stop() {
            synchronized (lifecycleLock) {
                online = false;
                CONTEXT.set("bot.status", "offline");
                AutomaticSender sender = automaticSender;
                automaticSender = null;
                if (sender != null) sender.close();
                JDA current = jda;
                jda = null;
                if (current != null) current.shutdownNow();
                System.out.println("Bot desconectado.");
            }
        }

        public boolean isOnline() {
            return online;
        }
    }

    // BLOQUE DE ENVIO DE MULTIMEDIA
    public static class MessageListener extends ListenerAdapter {
        private static final String NEWS_TEXT = ".";
        private static final String NEWS_TEXT_EN = "@everyone DISCRIMINE keeps moving forward. Thanks for supporting the project.";
        private static final String NEWS_IMAGE = "media/DISCRIMINEcaptura.png";
        private static final String BRITANNY_TEXT = "Se menciono Britanny.";
        private static final String HERODES_MEDIA_PATH = "C:\\Users\\Herodes\\Desktop\\DISCRIMINE construciones\\image.png";
        /*
         * =====================================================================
         * BLOQUE DE SOLO TEXTO PARA EL MENSAJE DE INICIO
         * =====================================================================
         * Este es el unico bloque que se envia automaticamente cuando el Bot
         * inicia. No adjunta imagenes, GIFs ni ningun otro archivo multimedia.
         *
         * Reemplaza el texto de STARTUP_TEXT_ONLY por el mensaje que quieras
         * enviar al iniciar el Bot.
         * =====================================================================
         */
        private static final int STARTUP_TEXT_LIMIT = 1900;
        private static final String STARTUP_TEXT_ONLY = "@everyone \uD835\uDC6F\uD835\uDC86 \uD835\uDC91\uD835\uDC86\uD835\uDC8F\uD835\uDC94\uD835\uDC82\uD835\uDC85\uD835\uDC90 \uD835\uDC97\uD835\uDC82\uD835\uDC93\uD835\uDC8A\uD835\uDC82\uD835\uDC94 \uD835\uDC97\uD835\uDC86\uD835\uDC84\uD835\uDC86\uD835\uDC94 \uD835\uDC86\uD835\uDC8F \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC82\uD835\uDC93 \uD835\uDC85\uD835\uDC86 \uD835\uDC86\uD835\uDC8F\uD835\uDC97\uD835\uDC8A\uD835\uDC82\uD835\uDC93\uD835\uDC8D\uD835\uDC90\uD835\uDC94.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC96\uD835\uDC86\uD835\uDC8D\uD835\uDC86 \uD835\uDC8E\uD835\uDC8A\uD835\uDC93\uD835\uDC82\uD835\uDC93 \uD835\uDC82\uD835\uDC8D\uD835\uDC88\uD835\uDC90 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC94\uD835\uDC8A\uD835\uDC88\uD835\uDC8F\uD835\uDC8A\uD835\uDC87\uD835\uDC8A\uD835\uDC84\uD835\uDC82 \uD835\uDC95\uD835\uDC82\uD835\uDC8F\uD835\uDC95\uD835\uDC90 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC8Eí \uD835\uDC9A \uD835\uDC94\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC8A\uD835\uDC93 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC90 \uD835\uDC89\uD835\uDC82\uD835\uDC9A \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC82\uD835\uDC8D \uD835\uDC90\uD835\uDC95\uD835\uDC93\uD835\uDC90 \uD835\uDC8D\uD835\uDC82\uD835\uDC85\uD835\uDC90 \uD835\uDC8D\uD835\uDC86\uD835\uDC9A\uD835\uDC86\uD835\uDC8F\uD835\uDC85\uD835\uDC90 \uD835\uDC85\uD835\uDC86 \uD835\uDC97\uD835\uDC86\uD835\uDC93\uD835\uDC85\uD835\uDC82\uD835\uDC85 \uD83D\uDC94\n" +
                "\n" +
                "\uD835\uDC68 \uD835\uDC97\uD835\uDC86\uD835\uDC84\uD835\uDC86\uD835\uDC94 \uD835\uDC94\uD835\uDC86 \uD835\uDC94\uD835\uDC8A\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC86 \uD835\uDC84\uD835\uDC90\uD835\uDC8E\uD835\uDC90 \uD835\uDC89\uD835\uDC82\uD835\uDC83\uD835\uDC8D\uD835\uDC82\uD835\uDC93\uD835\uDC8D\uD835\uDC86 \uD835\uDC82 \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC94\uD835\uDC82\uD835\uDC8D\uD835\uDC82 \uD835\uDC97\uD835\uDC82\uD835\uDC84í\uD835\uDC82.\n" +
                "\n" +
                "\uD835\uDC6A\uD835\uDC90\uD835\uDC8E\uD835\uDC90 \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC82\uD835\uDC93 \uD835\uDC87\uD835\uDC8D\uD835\uDC90\uD835\uDC93\uD835\uDC86\uD835\uDC94 \uD835\uDC86\uD835\uDC8F \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC91\uD835\uDC96\uD835\uDC86\uD835\uDC93\uD835\uDC95\uD835\uDC82 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC82\uD835\uDC83\uD835\uDC93\uD835\uDC86.\n" +
                "\n" +
                "\uD835\uDC6A\uD835\uDC90\uD835\uDC8E\uD835\uDC90 \uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC86\uD835\uDC93\uD835\uDC82\uD835\uDC93 \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC93\uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC96\uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC82 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC96\uD835\uDC8F\uD835\uDC84\uD835\uDC82 \uD835\uDC8D\uD835\uDC8D\uD835\uDC86\uD835\uDC88\uD835\uDC82 \uD83C\uDF27\uFE0F\n" +
                "\n" +
                "\uD835\uDC77\uD835\uDC86\uD835\uDC93\uD835\uDC90 \uD835\uDC82\uD835\uDC96\uD835\uDC8F \uD835\uDC82\uD835\uDC94í \uD835\uDC94\uD835\uDC8A\uD835\uDC88\uD835\uDC90 \uD835\uDC82\uD835\uDC92\uD835\uDC96í.\n" +
                "\n" +
                "\uD835\uDC7B\uD835\uDC82\uD835\uDC8D \uD835\uDC97\uD835\uDC86\uD835\uDC9B \uD835\uDC91\uD835\uDC90\uD835\uDC93\uD835\uDC92\uD835\uDC96\uD835\uDC86, \uD835\uDC82\uD835\uDC96\uD835\uDC8F\uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC8D\uD835\uDC90\uD835\uDC94 \uD835\uDC8D\uD835\uDC86\uD835\uDC82, \uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC90\uD835\uDC94 \uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC94\uD835\uDC82\uD835\uDC8B\uD835\uDC86\uD835\uDC94 \uD835\uDC94\uD835\uDC8A\uD835\uDC93\uD835\uDC97\uD835\uDC86\uD835\uDC8F \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC85\uD835\uDC90\uD835\uDC84\uD835\uDC96\uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC82\uD835\uDC93 \uD835\uDC86\uD835\uDC8D \uD835\uDC91\uD835\uDC93\uD835\uDC90\uD835\uDC88\uD835\uDC93\uD835\uDC86\uD835\uDC94\uD835\uDC90 \uD835\uDC85\uD835\uDC86 \uD835\uDC6B\uD835\uDC70\uD835\uDC7A\uD835\uDC6A\uD835\uDC79\uD835\uDC70\uD835\uDC74\uD835\uDC70\uD835\uDC75\uD835\uDC6C.\n" +
                "\n" +
                "\uD835\uDC7A\uD835\uDC8A\uD835\uDC93\uD835\uDC97\uD835\uDC86\uD835\uDC8F \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC82\uD835\uDC93 \uD835\uDC84\uD835\uDC90\uD835\uDC8F\uD835\uDC94\uD835\uDC95\uD835\uDC82\uD835\uDC8F\uD835\uDC84\uD835\uDC8A\uD835\uDC82 \uD835\uDC85\uD835\uDC86 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC90 \uD835\uDC86\uD835\uDC99\uD835\uDC8A\uD835\uDC94\uD835\uDC95\uD835\uDC8Aó.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC86 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC82\uD835\uDC97\uD835\uDC82\uD835\uDC8F\uD835\uDC9Bó.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC86 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC82\uD835\uDC8D\uD835\uDC88\uD835\uDC96\uD835\uDC8A\uD835\uDC86\uD835\uDC8F \uD835\uDC8D\uD835\uDC90 \uD835\uDC84\uD835\uDC96\uD835\uDC8A\uD835\uDC85ó, \uD835\uDC8A\uD835\uDC8F\uD835\uDC84\uD835\uDC8D\uD835\uDC96\uD835\uDC94\uD835\uDC90 \uD835\uDC86\uD835\uDC8F \uD835\uDC8D\uD835\uDC90\uD835\uDC94 \uD835\uDC85í\uD835\uDC82\uD835\uDC94 \uD835\uDC85\uD835\uDC90\uD835\uDC8F\uD835\uDC85\uD835\uDC86 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC86\uD835\uDC84í\uD835\uDC82 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC82 \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC8D\uD835\uDC86 \uD835\uDC8A\uD835\uDC8E\uD835\uDC91\uD835\uDC90\uD835\uDC93\uD835\uDC95\uD835\uDC82\uD835\uDC83\uD835\uDC82 \uD835\uDC85\uD835\uDC86\uD835\uDC8E\uD835\uDC82\uD835\uDC94\uD835\uDC8A\uD835\uDC82\uD835\uDC85\uD835\uDC90.\n" +
                "\n" +
                "\uD835\uDC80 \uD835\uDC83\uD835\uDC96\uD835\uDC86\uD835\uDC8F\uD835\uDC90...\n" +
                "\n" +
                "\uD835\uDC75\uD835\uDC90 \uD835\uDC92\uD835\uDC96\uD835\uDC8A\uD835\uDC86\uD835\uDC93\uD835\uDC90 \uD835\uDC82\uD835\uDC8D\uD835\uDC82\uD835\uDC93\uD835\uDC88\uD835\uDC82\uD835\uDC93 \uD835\uDC8Eá\uD835\uDC94 \uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC82 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC95\uD835\uDC86.\n" +
                "\n" +
                "\uD835\uDC68\uD835\uDC92\uD835\uDC96í \uD835\uDC8D\uD835\uDC86\uD835\uDC94 \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC90 \uD835\uDC86\uD835\uDC8D \uD835\uDC82\uD835\uDC8F\uD835\uDC95\uD835\uDC86\uD835\uDC94 \uD835\uDC9A \uD835\uDC85\uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC96é\uD835\uDC94 \uD835\uDC85\uD835\uDC86 \uD835\uDC8F\uD835\uDC96\uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC93\uD835\uDC90 \uD835\uDC8D\uD835\uDC90\uD835\uDC88\uD835\uDC90";
        /*
         * =====================================================================
         * BLOQUE DE MULTIMEDIA CON TEXTO PARA EL MENSAJE DE INICIO
         * =====================================================================
         * Este bloque se envia cuando termina el bloque de solo texto. El texto
         * de STARTUP_MEDIA_TEXT y las dos imagenes se envian juntos en el mismo
         * mensaje.
         *
         * Reemplaza STARTUP_MEDIA_TEXT por el mensaje que debe acompanar las
         * imagenes al iniciar el Bot.
         * =====================================================================
         */
        private static final String STARTUP_MEDIA_TEXT = "@everyone  \uD835\uDC6F\uD835\uDC86 \uD835\uDC91\uD835\uDC86\uD835\uDC8F\uD835\uDC94\uD835\uDC82\uD835\uDC85\uD835\uDC90 \uD835\uDC97\uD835\uDC82\uD835\uDC93\uD835\uDC8A\uD835\uDC82\uD835\uDC94 \uD835\uDC97\uD835\uDC86\uD835\uDC84\uD835\uDC86\uD835\uDC94 \uD835\uDC86\uD835\uDC8F \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC82\uD835\uDC93 \uD835\uDC85\uD835\uDC86 \uD835\uDC86\uD835\uDC8F\uD835\uDC97\uD835\uDC8A\uD835\uDC82\uD835\uDC93\uD835\uDC8D\uD835\uDC90\uD835\uDC94.\n" +
                "\n" +
                "\uD835\uDC77\uD835\uDC90\uD835\uDC93\uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC85\uD835\uDC96\uD835\uDC86\uD835\uDC8D\uD835\uDC86 \uD835\uDC86\uD835\uDC94\uD835\uDC84\uD835\uDC93\uD835\uDC8A\uD835\uDC83\uD835\uDC8A\uD835\uDC93 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC96\uD835\uDC86\uD835\uDC8D\uD835\uDC86 \uD835\uDC86\uD835\uDC8E\uD835\uDC90\uD835\uDC84\uD835\uDC8A\uD835\uDC90\uD835\uDC8F\uD835\uDC82\uD835\uDC93\uD835\uDC94\uD835\uDC86 \uD835\uDC94\uD835\uDC90\uD835\uDC8D\uD835\uDC82.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC96\uD835\uDC86\uD835\uDC8D\uD835\uDC86 \uD835\uDC84\uD835\uDC86\uD835\uDC8D\uD835\uDC86\uD835\uDC83\uD835\uDC93\uD835\uDC82\uD835\uDC93 \uD835\uDC94\uD835\uDC90\uD835\uDC8D\uD835\uDC82.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC96\uD835\uDC86\uD835\uDC8D\uD835\uDC86 \uD835\uDC8E\uD835\uDC8A\uD835\uDC93\uD835\uDC82\uD835\uDC93 \uD835\uDC82\uD835\uDC8D\uD835\uDC88\uD835\uDC90 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC94\uD835\uDC8A\uD835\uDC88\uD835\uDC8F\uD835\uDC8A\uD835\uDC87\uD835\uDC8A\uD835\uDC84\uD835\uDC82 \uD835\uDC95\uD835\uDC82\uD835\uDC8F\uD835\uDC95\uD835\uDC90 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC8Eí \uD835\uDC9A \uD835\uDC94\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC8A\uD835\uDC93 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC90 \uD835\uDC89\uD835\uDC82\uD835\uDC9A \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC82\uD835\uDC8D \uD835\uDC90\uD835\uDC95\uD835\uDC93\uD835\uDC90 \uD835\uDC8D\uD835\uDC82\uD835\uDC85\uD835\uDC90 \uD835\uDC8D\uD835\uDC86\uD835\uDC9A\uD835\uDC86\uD835\uDC8F\uD835\uDC85\uD835\uDC90 \uD835\uDC85\uD835\uDC86 \uD835\uDC97\uD835\uDC86\uD835\uDC93\uD835\uDC85\uD835\uDC82\uD835\uDC85 \uD83D\uDC94\n" +
                "\n" +
                "\uD835\uDC68 \uD835\uDC97\uD835\uDC86\uD835\uDC84\uD835\uDC86\uD835\uDC94 \uD835\uDC94\uD835\uDC86 \uD835\uDC94\uD835\uDC8A\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC86 \uD835\uDC84\uD835\uDC90\uD835\uDC8E\uD835\uDC90 \uD835\uDC89\uD835\uDC82\uD835\uDC83\uD835\uDC8D\uD835\uDC82\uD835\uDC93\uD835\uDC8D\uD835\uDC86 \uD835\uDC82 \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC94\uD835\uDC82\uD835\uDC8D\uD835\uDC82 \uD835\uDC97\uD835\uDC82\uD835\uDC84í\uD835\uDC82.\n" +
                "\n" +
                "\uD835\uDC6A\uD835\uDC90\uD835\uDC8E\uD835\uDC90 \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC82\uD835\uDC93 \uD835\uDC87\uD835\uDC8D\uD835\uDC90\uD835\uDC93\uD835\uDC86\uD835\uDC94 \uD835\uDC86\uD835\uDC8F \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC91\uD835\uDC96\uD835\uDC86\uD835\uDC93\uD835\uDC95\uD835\uDC82 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC82\uD835\uDC83\uD835\uDC93\uD835\uDC86.\n" +
                "\n" +
                "\uD835\uDC6A\uD835\uDC90\uD835\uDC8E\uD835\uDC90 \uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC86\uD835\uDC93\uD835\uDC82\uD835\uDC93 \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC93\uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC96\uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC82 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC96\uD835\uDC8F\uD835\uDC84\uD835\uDC82 \uD835\uDC8D\uD835\uDC8D\uD835\uDC86\uD835\uDC88\uD835\uDC82 \uD83C\uDF27\uFE0F\n" +
                "\n" +
                "\uD835\uDC77\uD835\uDC86\uD835\uDC93\uD835\uDC90 \uD835\uDC82\uD835\uDC96\uD835\uDC8F \uD835\uDC82\uD835\uDC94í \uD835\uDC94\uD835\uDC8A\uD835\uDC88\uD835\uDC90 \uD835\uDC82\uD835\uDC92\uD835\uDC96í.\n" +
                "\n" +
                "\uD835\uDC7B\uD835\uDC82\uD835\uDC8D \uD835\uDC97\uD835\uDC86\uD835\uDC9B \uD835\uDC91\uD835\uDC90\uD835\uDC93\uD835\uDC92\uD835\uDC96\uD835\uDC86, \uD835\uDC82\uD835\uDC96\uD835\uDC8F\uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC8D\uD835\uDC90\uD835\uDC94 \uD835\uDC8D\uD835\uDC86\uD835\uDC82, \uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC90\uD835\uDC94 \uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC94\uD835\uDC82\uD835\uDC8B\uD835\uDC86\uD835\uDC94 \uD835\uDC94\uD835\uDC8A\uD835\uDC93\uD835\uDC97\uD835\uDC86\uD835\uDC8F \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC85\uD835\uDC90\uD835\uDC84\uD835\uDC96\uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC82\uD835\uDC93 \uD835\uDC86\uD835\uDC8D \uD835\uDC91\uD835\uDC93\uD835\uDC90\uD835\uDC88\uD835\uDC93\uD835\uDC86\uD835\uDC94\uD835\uDC90 \uD835\uDC85\uD835\uDC86 \uD835\uDC6B\uD835\uDC70\uD835\uDC7A\uD835\uDC6A\uD835\uDC79\uD835\uDC70\uD835\uDC74\uD835\uDC70\uD835\uDC75\uD835\uDC6C.\n" +
                "\n" +
                "\uD835\uDC7A\uD835\uDC8A\uD835\uDC93\uD835\uDC97\uD835\uDC86\uD835\uDC8F \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC82\uD835\uDC93 \uD835\uDC84\uD835\uDC90\uD835\uDC8F\uD835\uDC94\uD835\uDC95\uD835\uDC82\uD835\uDC8F\uD835\uDC84\uD835\uDC8A\uD835\uDC82 \uD835\uDC85\uD835\uDC86 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC90 \uD835\uDC86\uD835\uDC99\uD835\uDC8A\uD835\uDC94\uD835\uDC95\uD835\uDC8Aó.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC86 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC82\uD835\uDC97\uD835\uDC82\uD835\uDC8F\uD835\uDC9Bó.\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC86 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC82\uD835\uDC8D\uD835\uDC88\uD835\uDC96\uD835\uDC8A\uD835\uDC86\uD835\uDC8F \uD835\uDC8D\uD835\uDC90 \uD835\uDC84\uD835\uDC96\uD835\uDC8A\uD835\uDC85ó, \uD835\uDC8A\uD835\uDC8F\uD835\uDC84\uD835\uDC8D\uD835\uDC96\uD835\uDC94\uD835\uDC90 \uD835\uDC86\uD835\uDC8F \uD835\uDC8D\uD835\uDC90\uD835\uDC94 \uD835\uDC85í\uD835\uDC82\uD835\uDC94 \uD835\uDC85\uD835\uDC90\uD835\uDC8F\uD835\uDC85\uD835\uDC86 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC86\uD835\uDC84í\uD835\uDC82 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC82 \uD835\uDC8F\uD835\uDC82\uD835\uDC85\uD835\uDC8A\uD835\uDC86 \uD835\uDC8D\uD835\uDC86 \uD835\uDC8A\uD835\uDC8E\uD835\uDC91\uD835\uDC90\uD835\uDC93\uD835\uDC95\uD835\uDC82\uD835\uDC83\uD835\uDC82 \uD835\uDC85\uD835\uDC86\uD835\uDC8E\uD835\uDC82\uD835\uDC94\uD835\uDC8A\uD835\uDC82\uD835\uDC85\uD835\uDC90.\n" +
                "\n" +
                "\uD835\uDC80 \uD835\uDC83\uD835\uDC96\uD835\uDC86\uD835\uDC8F\uD835\uDC90...\n" +
                "\n" +
                "\uD835\uDC75\uD835\uDC90 \uD835\uDC92\uD835\uDC96\uD835\uDC8A\uD835\uDC86\uD835\uDC93\uD835\uDC90 \uD835\uDC82\uD835\uDC8D\uD835\uDC82\uD835\uDC93\uD835\uDC88\uD835\uDC82\uD835\uDC93 \uD835\uDC8Eá\uD835\uDC94 \uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC82 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC95\uD835\uDC86.\n" +
                "\n" +
                "\uD835\uDC68\uD835\uDC92\uD835\uDC96í \uD835\uDC8D\uD835\uDC86\uD835\uDC94 \uD835\uDC85\uD835\uDC86\uD835\uDC8B\uD835\uDC90 \uD835\uDC86\uD835\uDC8D \uD835\uDC82\uD835\uDC8F\uD835\uDC95\uD835\uDC86\uD835\uDC94 \uD835\uDC9A \uD835\uDC85\uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC96é\uD835\uDC94 \uD835\uDC85\uD835\uDC86 \uD835\uDC8F\uD835\uDC96\uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC93\uD835\uDC90 \uD835\uDC8D\uD835\uDC90\uD835\uDC88\uD835\uDC90 \uD83D\uDDA4";
        private static final String[] STARTUP_MEDIA_PATHS = {
                "media/DISCRIMINElogodefinitivo3.png",
                "media/DISCRIMINELOGO999.jpg"
        };
        /*
         * =====================================================================
         * BLOQUE PARA MENCIONAR USUARIOS DESPUES DEL COMUNICADO
         * =====================================================================
         * Este bloque menciona a usuarios especificos y envia el texto de disculpa
         * despues de terminar la estructura principal del comunicado.
         * =====================================================================
         */
        private static final String STARTUP_USER_MENTIONS_APOLOGY_TEXT = "<@318559115825643522>  \uD835\uDC8E\uD835\uDC96\uD835\uDC84\uD835\uDC89\uD835\uDC82\uD835\uDC94 \uD835\uDC88\uD835\uDC93\uD835\uDC82\uD835\uDC84\uD835\uDC8A\uD835\uDC82\uD835\uDC94 \uD835\uDC91\uD835\uDC90\uD835\uDC93 \uD835\uDC95\uD835\uDC96\uD835\uDC94 \uD835\uDC91\uD835\uDC82\uD835\uDC8D\uD835\uDC82\uD835\uDC83\uD835\uDC93\uD835\uDC82\uD835\uDC94 \uD83D\uDE14\uD83C\uDF27\uFE0F\n" +
                "\n" +
                "\uD835\uDC6B\uD835\uDC86 \uD835\uDC97\uD835\uDC86\uD835\uDC93\uD835\uDC85\uD835\uDC82\uD835\uDC85... \uD835\uDC86\uD835\uDC94 \uD835\uDC89\uD835\uDC86\uD835\uDC93\uD835\uDC8E\uD835\uDC90\uD835\uDC94\uD835\uDC90 \uD835\uDC94\uD835\uDC82\uD835\uDC83\uD835\uDC86\uD835\uDC93 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC95\uD835\uDC90\uD835\uDC85\uD835\uDC82\uD835\uDC97í\uD835\uDC82 \uD835\uDC89\uD835\uDC82\uD835\uDC9A \uD835\uDC91\uD835\uDC86\uD835\uDC93\uD835\uDC94\uD835\uDC90\uD835\uDC8F\uD835\uDC82\uD835\uDC94 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC94\uD835\uDC86 \uD835\uDC95\uD835\uDC90\uD835\uDC8E\uD835\uDC82\uD835\uDC8F \uD835\uDC86\uD835\uDC8D \uD835\uDC95\uD835\uDC8A\uD835\uDC86\uD835\uDC8E\uD835\uDC91\uD835\uDC90 \uD835\uDC85\uD835\uDC86 \uD835\uDC91\uD835\uDC86\uD835\uDC8F\uD835\uDC94\uD835\uDC82\uD835\uDC93 \uD835\uDC86\uD835\uDC8F \uD835\uDC8D\uD835\uDC90\uD835\uDC94 \uD835\uDC94\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC8A\uD835\uDC8E\uD835\uDC8A\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC90\uD835\uDC94 \uD835\uDC85\uD835\uDC86 \uD835\uDC8D\uD835\uDC90\uD835\uDC94 \uD835\uDC85\uD835\uDC86\uD835\uDC8Eá\uD835\uDC94.\n" +
                "\n" +
                "\uD835\uDC68\uD835\uDC84\uD835\uDC95\uD835\uDC96\uD835\uDC82\uD835\uDC8D\uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC86 \uD835\uDC86\uD835\uDC94\uD835\uDC90 \uD835\uDC94\uD835\uDC86 \uD835\uDC97\uD835\uDC86 \uD835\uDC8E\uD835\uDC96\uD835\uDC9A \uD835\uDC91\uD835\uDC90\uD835\uDC84\uD835\uDC90.\n" +
                "\n" +
                "\uD835\uDC68 \uD835\uDC97\uD835\uDC86\uD835\uDC84\uD835\uDC86\uD835\uDC94 \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC93\uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC96\uD835\uDC86\uD835\uDC94\uD835\uDC95\uD835\uDC82 \uD835\uDC94\uD835\uDC86\uD835\uDC8F\uD835\uDC84\uD835\uDC8A\uD835\uDC8D\uD835\uDC8D\uD835\uDC82 \uD835\uDC91\uD835\uDC96\uD835\uDC86\uD835\uDC85\uD835\uDC86 \uD835\uDC94\uD835\uDC8A\uD835\uDC88\uD835\uDC8F\uD835\uDC8A\uD835\uDC87\uD835\uDC8A\uD835\uDC84\uD835\uDC82\uD835\uDC93 \uD835\uDC8Eá\uD835\uDC94 \uD835\uDC85\uD835\uDC86 \uD835\uDC8D\uD835\uDC90 \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC86\uD835\uDC84\uD835\uDC86, \uD835\uDC86\uD835\uDC94\uD835\uDC91\uD835\uDC86\uD835\uDC84\uD835\uDC8A\uD835\uDC82\uD835\uDC8D\uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC86 \uD835\uDC84\uD835\uDC96\uD835\uDC82\uD835\uDC8F\uD835\uDC85\uD835\uDC90 \uD835\uDC96\uD835\uDC8F\uD835\uDC82 \uD835\uDC8F\uD835\uDC90 \uD835\uDC86\uD835\uDC94\uD835\uDC95á \uD835\uDC91\uD835\uDC82\uD835\uDC94\uD835\uDC82\uD835\uDC8F\uD835\uDC85\uD835\uDC90 \uD835\uDC91\uD835\uDC90\uD835\uDC93 \uD835\uDC94\uD835\uDC96 \uD835\uDC8E\uD835\uDC86\uD835\uDC8B\uD835\uDC90\uD835\uDC93 \uD835\uDC8E\uD835\uDC90\uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC95\uD835\uDC90 \uD83D\uDC94\n" +
                "\n" +
                "\uD835\uDC68\uD835\uDC94í \uD835\uDC92\uD835\uDC96\uD835\uDC86 \uD835\uDC88\uD835\uDC93\uD835\uDC82\uD835\uDC84\uD835\uDC8A\uD835\uDC82\uD835\uDC94, \uD835\uDC86\uD835\uDC8F \uD835\uDC94\uD835\uDC86\uD835\uDC93\uD835\uDC8A\uD835\uDC90.\n" +
                "\n" +
                "\uD835\uDC7B\uD835\uDC96 \uD835\uDC8E\uD835\uDC86\uD835\uDC8F\uD835\uDC94\uD835\uDC82\uD835\uDC8B\uD835\uDC86 \uD835\uDC94\uD835\uDC8A\uD835\uDC88\uD835\uDC8F\uD835\uDC8A\uD835\uDC87\uD835\uDC8A\uD835\uDC84ó \uD835\uDC8E\uD835\uDC96\uD835\uDC84\uD835\uDC89\uD835\uDC90 \uD835\uDC91\uD835\uDC82\uD835\uDC93\uD835\uDC82 \uD835\uDC8Eí \uD83D\uDDA4\uD83C\uDF27\uFE0F" ;
        private static final String HERODES_MEDIA_TEXT = "@everyone ";
        private final BotContextStore context;
        private final AiConversationService aiService;

        public MessageListener(BotContextStore context, AiConversationService aiService) {
            this.context = context;
            this.aiService = aiService;
        }

        @Override
        public void onReady(ReadyEvent event) {
            // Anuncio automatico de inicio deshabilitado temporalmente.
            // El bot se conecta sin enviar @everyone ni mensajes al canal Spanish.
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;

            String msg = event.getMessage().getContentRaw().trim();
            String normalized = msg.toLowerCase(Locale.ROOT);
            // Una mencion explicita siempre se considera una conversacion con la IA.
            // Asi, por ejemplo, "@bot habla de Herodes" no dispara el comando multimedia.
            if (aiService.isBotMentioned(event)) {
                aiService.respondToMention(event, msg);
                return;
            }

            boolean handled = false;

            if (containsExactWord(normalized, "herodes")) {
                sendMediaWithText(event.getChannel(), HERODES_MEDIA_PATH, HERODES_MEDIA_TEXT);
                context.incrementCounter("manual.herodes.media.count");
                context.set("manual.last_command", "HERODES_MEDIA");
                context.set("manual.last_at", Instant.now().toString());
                handled = true;
            }

            if (!handled && normalized.contains("britanny")) {
                event.getChannel().sendMessage(BRITANNY_TEXT).queue();
                context.incrementCounter("manual.britanny.count");
                context.set("manual.last_command", "BRITANNY");
                context.set("manual.last_at", Instant.now().toString());
                handled = true;
            }

            if (!handled && "discrimine".equals(normalized)) {
                sendTextWithOptionalImage(event.getChannel(), NEWS_TEXT, NEWS_IMAGE);
                context.incrementCounter("manual.discrimine.count");
                context.set("manual.last_command", "DISCRIMINE");
                context.set("manual.last_at", Instant.now().toString());
                handled = true;
            }

            if (!handled && "discrimine2".equals(normalized)) {
                sendTextWithOptionalImage(event.getChannel(), NEWS_TEXT_EN, NEWS_IMAGE);
                context.incrementCounter("manual.discrimine2.count");
                context.set("manual.last_command", "DISCRIMINE2");
                context.set("manual.last_at", Instant.now().toString());
                handled = true;
            }

            if (!handled && ".".equals(msg)) {
                event.getChannel().sendMessage("Hola " + event.getAuthor().getAsMention() + "!").queue();
                context.incrementCounter("manual.dot.count");
                handled = true;
            }

            if (!handled && "..".equals(msg)) {
                event.getChannel().sendMessage(DOT_DOT_MESSAGE).queue();
                context.incrementCounter("manual.dotdot.count");
                handled = true;
            }

            // Se recuerdan tambien los mensajes normales para que una futura
            // mencion pueda usar el contexto de la conversacion.
            aiService.recordUserMessage(event, msg);
        }

        private void sendTextWithOptionalImage(MessageChannel channel, String text, String imagePath) {
            File image = new File(imagePath);
            if (image.exists() && image.isFile()) {
                channel.sendMessage(text)
                        .addFiles(FileUpload.fromData(image, image.getName()))
                        .queue();
                return;
            }
            channel.sendMessage(text).queue();
        }

        private void sendMediaWithText(MessageChannel channel, String mediaPath, String text) {
            File media = new File(mediaPath);
            if (!media.exists() || !media.isFile()) {
                channel.sendMessage("No se encontro el archivo multimedia configurado para Herodes.").queue();
                return;
            }
            channel.sendMessage(text)
                    .addFiles(FileUpload.fromData(media, media.getName()))
                    .queue();
        }

        /*
         * =====================================================================
         * ENVIO DE SOLO TEXTO DEL MENSAJE DE INICIO
         * =====================================================================
         * Este metodo envia solo texto. Si el texto pasa el limite de Discord,
         * lo divide en varios mensajes de texto, sin adjuntar multimedia.
         * =====================================================================
         */
        private void sendStartupTextOnly(MessageChannel channel, String text, Runnable afterSend) {
            sendTextChunks(channel, splitTextForDiscord(text), 0, afterSend);
            context.set("startup.announcement.mode", "text_then_media");
            context.set("startup.media.last_file", "none");
            context.set("startup.text.last_at", Instant.now().toString());
        }

        private void sendTextChunks(MessageChannel channel, java.util.List<String> chunks, int index, Runnable afterSend) {
            if (index >= chunks.size()) {
                afterSend.run();
                return;
            }

            channel.sendMessage(chunks.get(index)).queue(message -> sendTextChunks(channel, chunks, index + 1, afterSend));
        }

        private static java.util.List<String> splitTextForDiscord(String text) {
            java.util.List<String> chunks = new java.util.ArrayList<>();
            int start = 0;

            while (start < text.length()) {
                int end = findTextChunkEnd(text, start);
                chunks.add(text.substring(start, end));
                start = end;
            }

            if (chunks.isEmpty()) {
                chunks.add("insertar texto aqui");
            }

            return chunks;
        }

        private static int findTextChunkEnd(String text, int start) {
            int limit = Math.min(text.length(), start + STARTUP_TEXT_LIMIT);
            if (limit == text.length()) {
                return limit;
            }

            int end = text.lastIndexOf("\n\n", limit);
            if (end <= start) {
                end = text.lastIndexOf('\n', limit);
            }
            if (end <= start) {
                end = limit;
            }
            if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }

            return end;
        }

        /*
         * =====================================================================
         * ENVIO DE MULTIMEDIA CON TEXTO DEL MENSAJE DE INICIO
         * =====================================================================
         * Este metodo envia STARTUP_MEDIA_TEXT y adjunta en ese mismo mensaje
         * solo DISCRIMINElogodefinitivo3.png y DISCRIMINELOGO999.jpg.
         * =====================================================================
         */
        private void sendStartupMediaWithText(MessageChannel channel, String text, Runnable afterSend) {
            FileUpload[] uploads = new FileUpload[STARTUP_MEDIA_PATHS.length];
            StringBuilder sentFiles = new StringBuilder();

            for (int i = 0; i < STARTUP_MEDIA_PATHS.length; i++) {
                File media = new File(STARTUP_MEDIA_PATHS[i]);
                if (!media.exists() || !media.isFile()) {
                    channel.sendMessage(text).queue(message -> afterSend.run());
                    context.set("startup.media.last_file", "none");
                    return;
                }

                uploads[i] = FileUpload.fromData(media, media.getName());
                if (sentFiles.length() > 0) {
                    sentFiles.append(",");
                }
                sentFiles.append(media.getName());
            }

            channel.sendMessage(text)
                    .addFiles(uploads)
                    .queue(message -> afterSend.run());
            context.set("startup.media.last_file", sentFiles.toString());
            context.set("startup.media.last_at", Instant.now().toString());
        }

        private void sendStartupUserMentionsApology(MessageChannel channel) {
            channel.sendMessage(STARTUP_USER_MENTIONS_APOLOGY_TEXT).queue();
            context.incrementCounter("startup.user_mentions.apology.count");
            context.set("startup.user_mentions.apology.last_at", Instant.now().toString());
        }

        private boolean containsExactWord(String normalizedMessage, String word) {
            return normalizedMessage.matches(".*\\b" + java.util.regex.Pattern.quote(word) + "\\b.*");
        }
    }

    public static class AutomaticSender extends ListenerAdapter {
        private static final String DEFAULT_CHANNEL_ID = "1427773746474647663";
        private static final String MEDIA_FOLDER = "media";
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final BotContextStore context;

        public AutomaticSender(BotContextStore context) {
            this.context = context;
        }

        public void close() {
            scheduler.shutdownNow();
        }

        @Override
        public void onReady(ReadyEvent event) {
            JDA jda = event.getJDA();
            String channelId = getenvOrDefault("DISCRIMINE_CHANNEL_ID", DEFAULT_CHANNEL_ID);
            String targetUserId = getenvOrDefault("DISCRIMINE_TARGET_USER_ID", "");
            long periodHours = parsePositiveLong(getenvOrDefault("DISCRIMINE_AUTO_INTERVAL_HOURS", "6"), 6L);

            scheduler.scheduleAtFixedRate(() -> {
                MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
                if (channel == null) return;

                // NO ELIMINAR: bloque para mencionar a un usuario en especifico
                String mention = targetUserId.isBlank() ? "" : "<@" + targetUserId + "> ";
                String content = mention + "DISCRIMINE sigue creciendo. Gracias por formar parte.";

                File folder = new File(MEDIA_FOLDER);
                File[] files = folder.listFiles(File::isFile);

                if (files != null && files.length > 0) {
                    File picked = files[RANDOM.nextInt(files.length)];
                    channel.sendMessage(content).addFiles(FileUpload.fromData(picked, picked.getName())).queue();
                    context.set("auto.last_media", picked.getName());
                } else {
                    channel.sendMessage(content).queue();
                    context.set("auto.last_media", "none");
                }

                context.incrementCounter("auto.send.count");
                context.set("auto.last_at", Instant.now().toString());
            }, 1, periodHours, TimeUnit.HOURS);
        }
    }

    private static String getenvOrDefault(String key, String defaultValue) {
        return Environment.get(key, defaultValue);
    }

    private static long parsePositiveLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
