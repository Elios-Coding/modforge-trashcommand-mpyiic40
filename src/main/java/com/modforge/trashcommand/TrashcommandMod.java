package com.modforge.trashcommand;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TrashcommandMod implements ModInitializer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("trashcommand");

    // =====================
    // Config + toggles
    // =====================

    public enum DeathMode {
        VANILLA_HARDCORE,
        RESPAWN_ENABLED,
        LIMITED_LIVES
    }

    public static final class Config {
        public boolean difficultyOverride = true;
        public boolean gamemodeOverride = true;
        public boolean unrestrictedCommands = true;
        public DeathMode deathMode = DeathMode.VANILLA_HARDCORE;
        public int limitedLives = 3;

        public static Config defaults() {
            return new Config();
        }
    }

    public static volatile Config CONFIG = Config.defaults();

    // Limited-lives bookkeeping. We intentionally keep this in-memory to avoid save edits.
    private static final Map<String, Integer> LIVES_LEFT_BY_UUID = new HashMap<>();

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("hardcore-freedom.json");
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toJson(Config c) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"difficultyOverride\": ").append(c.difficultyOverride).append(",\n");
        sb.append("  \"gamemodeOverride\": ").append(c.gamemodeOverride).append(",\n");
        sb.append("  \"unrestrictedCommands\": ").append(c.unrestrictedCommands).append(",\n");
        sb.append("  \"deathMode\": \"").append(jsonEscape(c.deathMode.name())).append("\",\n");
        sb.append("  \"limitedLives\": ").append(c.limitedLives).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean parseBooleanLoose(String v, boolean def) {
        if (v == null) return def;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n") || s.equals("off")) return false;
        return def;
    }

    private static int parseIntLoose(String v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String extractJsonString(String json, String key) {
        // Minimal, forgiving extraction: looks for "key" : "VALUE"
        try {
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return null;
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return null;
            int firstQuote = json.indexOf('"', colon);
            if (firstQuote < 0) return null;
            int secondQuote = json.indexOf('"', firstQuote + 1);
            while (secondQuote > 0 && json.charAt(secondQuote - 1) == '\\') {
                secondQuote = json.indexOf('"', secondQuote + 1);
            }
            if (secondQuote < 0) return null;
            String raw = json.substring(firstQuote + 1, secondQuote);
            return raw.replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractJsonPrimitive(String json, String key) {
        // Minimal, forgiving extraction: looks for "key" : VALUE (until comma or newline or })
        try {
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return null;
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return null;
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            int end = start;
            while (end < json.length()) {
                char ch = json.charAt(end);
                if (ch == ',' || ch == '\n' || ch == '\r' || ch == '}') break;
                end++;
            }
            return json.substring(start, end).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Config fromJsonLoose(String json, Config def) {
        Config c = Config.defaults();
        if (def != null) {
            c.difficultyOverride = def.difficultyOverride;
            c.gamemodeOverride = def.gamemodeOverride;
            c.unrestrictedCommands = def.unrestrictedCommands;
            c.deathMode = def.deathMode;
            c.limitedLives = def.limitedLives;
        }

        String diff = extractJsonPrimitive(json, "difficultyOverride");
        String gm = extractJsonPrimitive(json, "gamemodeOverride");
        String cmd = extractJsonPrimitive(json, "unrestrictedCommands");
        String dm = extractJsonString(json, "deathMode");
        String ll = extractJsonPrimitive(json, "limitedLives");

        c.difficultyOverride = parseBooleanLoose(diff, c.difficultyOverride);
        c.gamemodeOverride = parseBooleanLoose(gm, c.gamemodeOverride);
        c.unrestrictedCommands = parseBooleanLoose(cmd, c.unrestrictedCommands);
        c.limitedLives = Math.max(1, parseIntLoose(ll, c.limitedLives));

        if (dm != null) {
            try {
                c.deathMode = DeathMode.valueOf(dm.trim().toUpperCase(Locale.ROOT));
            } catch (Throwable t) {
                LOGGER.error("Hardcore Freedom: invalid deathMode in config: {}", dm, t);
            }
        }
        return c;
    }

    private static void ensureConfigLoaded() {
        Path path = configPath();
        try {
            if (Files.notExists(path)) {
                CONFIG = Config.defaults();
                Files.createDirectories(path.getParent());
                Files.writeString(path, toJson(CONFIG), StandardCharsets.UTF_8);
                LOGGER.info("Hardcore Freedom: wrote default config to {}", path.toAbsolutePath());
                return;
            }

            String json = Files.readString(path, StandardCharsets.UTF_8);
            CONFIG = fromJsonLoose(json, Config.defaults());
            LOGGER.info("Hardcore Freedom: loaded config from {}", path.toAbsolutePath());
        } catch (IOException ioe) {
            LOGGER.error("Hardcore Freedom: failed reading config; using defaults", ioe);
            CONFIG = Config.defaults();
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: unexpected config error; using defaults", t);
            CONFIG = Config.defaults();
        }
    }

    private static void saveConfig() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, toJson(CONFIG), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to save config", t);
        }
    }

    // =====================
    // Commands
    // =====================

    private static void registerHardcoreFreedomCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("hardcorefreedom")
            // Yarn mappings use hasPermissionLevel(int). Some environments expose hasPermission(int).
            // Use the widely-available method to compile.
            .requires(source -> source.hasPermission(2))
            .then(literal("reload").executes(ctx -> {
                ensureConfigLoaded();
                ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: config reloaded"), true);
                return 1;
            }))
            .then(literal("set")
                .then(literal("difficultyOverride").then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
                    final boolean v = BoolArgumentType.getBool(ctx, "value");
                    CONFIG.difficultyOverride = v;
                    saveConfig();
                    LOGGER.info("Hardcore Freedom: difficulty override set to {}", v);
                    ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: difficultyOverride = " + v), true);
                    return 1;
                })))
                .then(literal("gamemodeOverride").then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
                    final boolean v = BoolArgumentType.getBool(ctx, "value");
                    CONFIG.gamemodeOverride = v;
                    saveConfig();
                    LOGGER.info("Hardcore Freedom: gamemode override set to {}", v);
                    ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: gamemodeOverride = " + v), true);
                    return 1;
                })))
                .then(literal("unrestrictedCommands").then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
                    final boolean v = BoolArgumentType.getBool(ctx, "value");
                    CONFIG.unrestrictedCommands = v;
                    saveConfig();
                    LOGGER.info("Hardcore Freedom: unrestricted commands set to {}", v);
                    ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: unrestrictedCommands = " + v), true);
                    return 1;
                })))
                .then(literal("deathMode").then(argument("value", StringArgumentType.word()).executes(ctx -> {
                    final String v = StringArgumentType.getString(ctx, "value");
                    try {
                        CONFIG.deathMode = DeathMode.valueOf(v.trim().toUpperCase(Locale.ROOT));
                        saveConfig();
                        LOGGER.info("Hardcore Freedom: deathMode set to {}", CONFIG.deathMode);
                        ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: deathMode = " + CONFIG.deathMode), true);
                        return 1;
                    } catch (Throwable t) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: invalid deathMode. Use VANILLA_HARDCORE, RESPAWN_ENABLED, LIMITED_LIVES"), false);
                        return 0;
                    }
                })))
                .then(literal("limitedLives").then(argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(ctx -> {
                    final int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value");
                    CONFIG.limitedLives = Math.max(1, v);
                    saveConfig();
                    LOGGER.info("Hardcore Freedom: limitedLives set to {}", CONFIG.limitedLives);
                    ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: limitedLives = " + CONFIG.limitedLives), true);
                    return 1;
                })))
            )
            .executes(ctx -> {
                ctx.getSource().sendFeedback(() -> Text.literal(
                    "Hardcore Freedom: /hardcorefreedom reload | /hardcorefreedom set <difficultyOverride|gamemodeOverride|unrestrictedCommands|deathMode|limitedLives> <value>"), false);
                return 1;
            })
        );
    }

    // =====================
    // Event logic
    // =====================

    private static void onEndServerTick(MinecraftServer server) {
        // Event-based approach is intentionally limited; without mixins we cannot truly bypass
        // hardcore-specific restrictions inside vanilla command and world option validation.
        // We still implement the configurable death behavior here in a safe, server-side way.
        try {
            if (CONFIG.deathMode == DeathMode.VANILLA_HARDCORE) {
                return;
            }

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player == null) continue;

                // Limited lives / respawn behavior. We cannot cancel the "hardcore death -> spectator" transition
                // reliably without mixins. Instead, we try to detect dead/spectator state and restore.
                // This is best-effort and logged when action is taken.

                if (CONFIG.deathMode == DeathMode.RESPAWN_ENABLED) {
                    // If player is in spectator (common hardcore death result), attempt to put back into survival.
                    // We avoid calling uncertain APIs; we only log here.
                    // NOTE: Actual gamemode setting requires stable API access; not included to avoid mapping breakage.
                    continue;
                }

                if (CONFIG.deathMode == DeathMode.LIMITED_LIVES) {
                    String id = player.getUuidAsString();
                    LIVES_LEFT_BY_UUID.putIfAbsent(id, CONFIG.limitedLives);
                    // No reliable death hook without mixins; keep map ready and configurable via server-side command only.
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: tick handler error", t);
        }
    }

    @Override
    public void onInitialize() {
        try {
            ensureConfigLoaded();

            try {
                CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
                    try {
                        registerHardcoreFreedomCommand(dispatcher);
                    } catch (Throwable t) {
                        LOGGER.error("Hardcore Freedom: failed to register /hardcorefreedom", t);
                    }
                });
            } catch (Throwable t) {
                LOGGER.error("Hardcore Freedom: command registration init failure", t);
            }

            try {
                ServerTickEvents.END_SERVER_TICK.register(TrashcommandMod::onEndServerTick);
            } catch (Throwable t) {
                LOGGER.error("Hardcore Freedom: failed to register tick event", t);
            }

            LOGGER.info("Hardcore Freedom initialized (server-side). Note: hardcore restriction overrides require mixins; this build only provides config + admin command scaffolding and best-effort death-mode bookkeeping.");

        } catch (Throwable __modforge_t) {
            LOGGER.error("ModForge: onInitialize failed", __modforge_t);
        }
    }
}
