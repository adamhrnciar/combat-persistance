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

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Saving combat and auth data synchronously for server shutdown...");
            tracker.stop();
            authManager.flush();
        });

        LOGGER.info("Combat tag duration is set to {} seconds.", config.combatTagDurationSeconds);
    }
}
