package me.avacuoss;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class DSwhitelist extends JavaPlugin {

    private volatile JDA jda;
    private String token;
    private boolean useGlobal;
    private String guildId;
    private ExecutorService worker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        token = getConfig().getString("discord-token", "").trim();
        guildId = getConfig().getString("discord-guild-id", "").trim();
        useGlobal = getConfig().getBoolean("use-global-commands", true);

        if (token.isEmpty()) {
            getLogger().severe("[DSwhitelist] No Discord token in config.yml");
            return;
        }

        worker = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

        try {
            JDABuilder builder = JDABuilder.createDefault(token)
                    .setActivity(Activity.playing("Whitelist Manager"))
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .addEventListeners(new BotListener(this));

            jda = builder.build();

            jda.addEventListener(new ListenerAdapter() {
                @Override
                public void onReady(@Nonnull ReadyEvent event) {
                    registerCommands(event.getJDA());
                    getLogger().info("[DSwhitelist] Discord bot is ready.");
                }
            });

        } catch (Exception ex) {
            getLogger().severe("[DSwhitelist] Failed to start bot: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (jda != null) try { jda.shutdown(); } catch (Exception ignored) {}
        if (worker != null) {
            worker.shutdownNow();
            try { worker.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    private void registerCommands(@Nonnull JDA jda) {
        CommandData wl = Commands.slash("wl", "Whitelist manager")
                .addSubcommands(new SubcommandData("add", "Add player")
                        .addOption(OptionType.STRING, "player", "Minecraft username", true));

        CommandData panel = Commands.slash("panel", "Send whitelist panel");

        if (useGlobal) {
            jda.updateCommands().addCommands(wl, panel).queue();
        } else if (!guildId.isEmpty() && jda.getGuildById(guildId) != null) {
            jda.getGuildById(guildId).updateCommands().addCommands(wl, panel).queue();
        } else {
            jda.updateCommands().addCommands(wl, panel).queue();
        }
    }

    public static class MojangRateLimiter {
        private final int capacity;
        private final double refillPerSec;
        private double tokens;
        private long lastRefillMs;

        public MojangRateLimiter(int capacity, double refillPerSec) {
            this.capacity = capacity;
            this.refillPerSec = refillPerSec;
            this.tokens = capacity;
            this.lastRefillMs = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() <= deadline) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                try { Thread.sleep(50); }
                catch (InterruptedException ignored) { return false; }
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double delta = (now - lastRefillMs) / 1000.0;
            if (delta <= 0) return;
            tokens = Math.min(capacity, tokens + delta * refillPerSec);
            lastRefillMs = now;
        }
    }

    private static class CachedUUID {
        final UUID uuid;
        final Instant expiresAt;
        CachedUUID(UUID uuid, Instant expiresAt) {
            this.uuid = uuid;
            this.expiresAt = expiresAt;
        }
    }

    public static class BotListener extends ListenerAdapter {

        private final DSwhitelist plugin;

        private final HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        private final MojangRateLimiter rateLimiter = new MojangRateLimiter(20, 8);
        private final Map<String, CachedUUID> cache = new ConcurrentHashMap<>();

        public BotListener(DSwhitelist plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent e) {

            if (e.getName().equalsIgnoreCase("panel")) {
                sendPanel(e);
                return;
            }

            if (!e.getName().equalsIgnoreCase("wl")) return;
            if (!e.getSubcommandName().equalsIgnoreCase("add")) {
                e.reply("Usage: /wl add <player>").setEphemeral(true).queue();
                return;
            }

            String player = e.getOption("player").getAsString();

            e.deferReply(true).queue(hook -> plugin.worker.submit(() -> {
                try {
                    UUID uuid = resolveUUID(player);
                    if (uuid == null) {
                        Bukkit.getScheduler().runTask(plugin, () -> hook.sendMessage("Player `" + player + "` not found.").queue());
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:whitelist add " + player);

                        if (plugin.getConfig().getBoolean("auto-save-whitelist", true)) {
                            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:whitelist reload");
                        }

                        hook.sendMessage("Player **" + player + "** added.").queue();
                    });

                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            hook.sendMessage("Internal error: " + ex.getMessage()).queue());
                }
            }));
        }

        @Override
        public void onButtonInteraction(@Nonnull ButtonInteractionEvent event) {
            if (!event.getComponentId().equals("wl_add")) return;

            TextInput input = TextInput.create("player_name", "Minecraft Username", TextInputStyle.SHORT)
                    .setRequired(true)
                    .build();

            Modal modal = Modal.create("wl_add_modal", "Add to Whitelist")
                    .addActionRow(input)
                    .build();

            event.replyModal(modal).queue();
        }

        @Override
        public void onModalInteraction(@Nonnull ModalInteractionEvent event) {
            if (!event.getModalId().equals("wl_add_modal")) return;

            String player = event.getValue("player_name").getAsString();

            event.deferReply(true).queue(hook -> plugin.worker.submit(() -> {
                try {
                    UUID uuid = resolveUUID(player);
                    if (uuid == null) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                hook.sendMessage("Player `" + player + "` not found.").queue());
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:whitelist add " + player);

                        if (plugin.getConfig().getBoolean("auto-save-whitelist", true)) {
                            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:whitelist reload");
                        }

                        hook.sendMessage("Player **" + player + "** added via modal.").queue();
                    });

                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            hook.sendMessage("Internal error: " + ex.getMessage()).queue());
                }
            }));
        }

        private UUID resolveUUID(String username) throws IOException, InterruptedException {
            String key = username.toLowerCase();

            CachedUUID c = cache.get(key);
            if (c != null && Instant.now().isBefore(c.expiresAt)) return c.uuid;

            if (!rateLimiter.tryConsume(1500)) return null;

            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;

            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            String id = json.get("id").getAsString();

            String uuidStr = id.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                    "$1-$2-$3-$4-$5");

            UUID uuid = UUID.fromString(uuidStr);
            cache.put(key, new CachedUUID(uuid, Instant.now().plus(Duration.ofMinutes(30))));
            return uuid;
        }

        private void sendPanel(SlashCommandInteractionEvent event) {
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Minecraft Whitelist Panel")
                    .setDescription("Press **Add Account** to add a player.")
                    .setColor(0x00A8FF);

            event.getChannel().sendMessageEmbeds(embed.build())
                    .setActionRow(Button.success("wl_add", "Add Account"))
                    .queue();

            event.reply("Panel sent.").setEphemeral(true).queue();
        }
    }
}
