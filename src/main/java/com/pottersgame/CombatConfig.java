package com.pottersgame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CombatConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "combatpersistence.json");

    // Combat Settings
    public int combatTagDurationSeconds = 15;
    public String npcNamePrefix = "§7[OFFLINE] §f";
    public boolean playSpawnSound = true;
    public String combatMessage = "§c§lIN COMBAT: §f%s s remaining";
    public String npcDeathBroadcast = "§c[PvP] §7%s combat logged and was slain!";
    public String inventoryRestoredMessage = "§aYour inventory has been restored.";
    public String commandBlockedInCombatMessage = "§cYou cannot use this command while in combat!";
    public String loginTimeoutMessage = "§cLogin timeout!";
    public String registerPrompt = "§6Please register using /register <password> <confirmPassword>";
    public String loginPrompt = "§6Please log in using /login <password>";
    public String registerSuccess = "§aSuccessfully registered and logged in!";
    public String loginSuccess = "§aSuccessfully logged in!";
    public String alreadyLoggedIn = "§cYou are already logged in!";
    public String alreadyRegistered = "§cYou are already registered!";
    public String passwordMismatch = "§cPasswords do not match!";
    public String passwordTooShort = "§cPassword must be at least %s characters long.";
    public String passwordTooLong = "§cPassword must be at most %s characters long.";
    public String incorrectPassword = "§cIncorrect password!";
    public String authRequiredForCommand = "§cYou must log in to use commands!";
    public String authRequiredForChat = "§cYou must log in to chat!";
    public String authRequiredForSkin = "§cYou must log in to change your skin!";
    public String skinAppliedMessage = "§aApplied skin: %s";
    public String skinCooldownMessage = "§cPlease wait %s seconds before changing your skin again.";
    public String accountResetKickMessage = "§eYour account was reset by an operator. Please reconnect and register again.";
    public String accountResetSuccess = "§aReset account for %s. They must register again.";
    public String accountResetNotRegistered = "§c%s is not registered.";
    public List<String> blockedCommands = new ArrayList<>();

    // SMP / Arena Settings
    public boolean disableEnderChests = false;
    public String enderChestDisabledMessage = "§cEnder chests are disabled on this server.";

    // Auth Settings
    public boolean enableAuth = true;
    // Password auth only guards offline-mode servers (anyone can join under any
    // name there). Online-mode servers already have Mojang-verified identities,
    // so the auth flow never applies to them. Set this to false to opt an
    // offline-mode server out of password auth entirely.
    public boolean forceAuthInOfflineMode = true;
    // IP-based auto-login is convenient but weak (shared NAT/VPN, and every player
    // shares the proxy IP behind BungeeCord/Velocity). Off by default: require the
    // password every session. Only enable on direct-connect servers you trust.
    public boolean enableIpAutoLogin = false;
    public int sessionDurationHours = 24;
    // Brute-force protection for /login: lock the account (and the source IP)
    // after this many consecutive wrong passwords, for this many seconds.
    public int maxLoginAttempts = 5;
    public int loginLockoutSeconds = 300;
    public String loginLockedOutMessage = "§cToo many failed attempts. Try again in %s seconds.";
    // Kick the player on a wrong /login password. While locked out they are
    // also refused at join time until the lockout expires.
    public boolean kickOnWrongPassword = true;
    // BCrypt silently truncates passwords beyond 72 bytes, so the maximum stays
    // safely below that.
    public int minPasswordLength = 4;
    public int maxPasswordLength = 64;
    // Cooldown between /skin uses, so one player can't burn the server's shared
    // Mojang API rate limit by spamming lookups of unique names.
    public int skinCommandCooldownSeconds = 30;
    public boolean hideCoordinatesBeforeAuth = true;
    public boolean hideInventoryBeforeAuth = true;
    public double lobbyX = 0, lobbyY = 1000, lobbyZ = 0;
    public String lobbyDimension = "minecraft:overworld";
    public int authTimeoutSeconds = 60;

    private transient ResourceKey<Level> lobbyKey;

    public CombatConfig() {
        // Default blocked commands
        blockedCommands.add("/spawn");
        blockedCommands.add("/home");
        blockedCommands.add("/tp");
        blockedCommands.add("/tpa");
        blockedCommands.add("/warp");
        blockedCommands.add("/back");
    }

    /** Parsed-once lobby dimension key; falls back to the overworld if the configured id is invalid. */
    public ResourceKey<Level> lobbyDimensionKey() {
        if (lobbyKey == null) {
            try {
                lobbyKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(lobbyDimension));
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Invalid lobbyDimension '{}' in config, falling back to the overworld", lobbyDimension, e);
                lobbyKey = Level.OVERWORLD;
            }
        }
        return lobbyKey;
    }

    public static CombatConfig load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                CombatConfig loaded = GSON.fromJson(reader, CombatConfig.class);
                if (loaded != null) {
                    return loaded;
                }
                Combatpersistence.LOGGER.warn("Combat config file is empty, regenerating defaults");
            } catch (Exception e) {
                // Gson throws unchecked JsonSyntaxException on malformed JSON — a broken
                // config must not crash the server. Run on defaults and leave the file
                // untouched so the admin can fix it instead of losing their settings.
                Combatpersistence.LOGGER.error("Failed to load combat config, running with defaults (file left untouched)", e);
                return new CombatConfig();
            }
        }

        CombatConfig config = new CombatConfig();
        save(config);
        return config;
    }

    public static void save(CombatConfig config) {
        DiskIO.async(() -> {
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(config, writer);
            } catch (IOException e) {
                Combatpersistence.LOGGER.error("Failed to save combat config in background", e);
            }
        });
    }
}
