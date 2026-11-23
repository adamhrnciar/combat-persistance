package com.pottersgame;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Combatpersistence implements ModInitializer {
    public static final String MOD_ID = "combatpersistence";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static CombatConfig config;
    public static final CombatTracker tracker = new CombatTracker();
    public static final AuthManager authManager = new AuthManager();

    /**
     * Verbose diagnostics gated on config.debugLogging, emitted at INFO so a
     * production server can enable them with a config edit instead of a log4j
     * override. Null-safe because tracker/authManager (and their data loads)
     * initialize before the config is assigned in onInitialize. Errors and
     * warnings never go through here — they always log unconditionally.
     */
    public static void debug(String message, Object... args) {
        CombatConfig c = config;
        if (c != null && c.debugLogging) {
            LOGGER.info(message, args);
        }
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Combat Persistence Mod...");

        config = CombatConfig.load();

        CombatEvents.register(tracker, config);
        AuthCommands.register();

        ServerLifecycleEvents.SERVER_STARTED.register(CombatEvents::sweepStaleNpcTeams);

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveOnShutdown(tracker, authManager));

        LOGGER.info("Combat tag duration is set to {} seconds.", config.combatTagDurationSeconds);
    }

    /**
     * Runs at the head of MinecraftServer.stopServer, before vanilla saves
     * players and worlds. A failed write here must not escape and abort those
     * saves, and one failed store must not skip the other.
     */
    static void saveOnShutdown(CombatTracker tracker, AuthManager auth) {
        LOGGER.info("Saving combat and auth data synchronously for server shutdown...");
        try {
            tracker.stop();
        } catch (RuntimeException error) {
            LOGGER.error("Could not save combat data on shutdown", error);
        }
        try {
            auth.flush();
        } catch (RuntimeException error) {
            LOGGER.error("Could not save auth data on shutdown", error);
        }
    }
}
