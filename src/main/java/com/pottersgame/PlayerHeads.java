package com.pottersgame;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side player heads in chat and the tab list, visible on vanilla clients.
 *
 * Minecraft 26.x text components can draw a player head sprite
 * ({@link PlayerSprite}). The sprite carries the player's resolved profile,
 * including the textures property that {@link SkinManager} puts there, so the
 * client renders the real skin even on offline-mode servers.
 *
 * Vanilla clients only draw tab-list heads on encrypted (online-mode)
 * connections, so on offline-mode servers the head goes into the tab display
 * name instead. A custom tab display name replaces the client's own team
 * formatting, so the name is rebuilt with the team prefix, suffix and colour,
 * and resent when it changes.
 */
public final class PlayerHeads {

    private static final int TAB_REFRESH_INTERVAL_TICKS = 20;
    private static final Map<UUID, Component> lastSentTabNames = new HashMap<>();

    private PlayerHeads() {}

    public static boolean chatEnabled() {
        CombatConfig c = Combatpersistence.config;
        return c != null && c.showHeadsInChat;
    }

    public static boolean tabEnabled() {
        CombatConfig c = Combatpersistence.config;
        return c != null && c.showHeadsInTab;
    }

    /** The player's head (with the hat layer) as an inline text sprite. */
    public static Component head(ServerPlayer player) {
        return Component.object(new PlayerSprite(ResolvableProfile.createResolved(player.getGameProfile()), true));
    }

    /** {@code [head] name}, keeping the name's own style. */
    public static Component withHead(ServerPlayer player, Component name) {
        return Component.empty().append(head(player)).append(" ").append(name);
    }

    /**
     * Tab-list name with head. {@code original} is what another mod already set
     * as the display name; when none is set, the vanilla client would show the
     * team-formatted name, so that is rebuilt here.
     */
    public static Component tabName(ServerPlayer player, Component original) {
        Component name = original != null ? original : PlayerTeam.formatNameForTeam(player.getTeam(), player.getName());
        return withHead(player, name);
    }

    /**
     * Resends tab display names that changed (team colour or prefix, skin) once
     * a second. Runs on the server thread from the end-of-tick hook.
     */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % TAB_REFRESH_INTERVAL_TICKS != 0) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (!tabEnabled()) {
            if (!lastSentTabNames.isEmpty()) {
                // Turned off at runtime: send the plain names back once.
                lastSentTabNames.clear();
                broadcastDisplayNames(server, players);
            }
            return;
        }
        List<ServerPlayer> changed = new ArrayList<>();
        Map<UUID, Component> current = new HashMap<>();
        for (ServerPlayer player : players) {
            Component name = player.getTabListDisplayName();
            current.put(player.getUUID(), name);
            if (!name.equals(lastSentTabNames.get(player.getUUID()))) {
                changed.add(player);
            }
        }
        // Drops players who left, so the map never grows past the online count.
        lastSentTabNames.clear();
        lastSentTabNames.putAll(current);
        broadcastDisplayNames(server, changed);
    }

    private static void broadcastDisplayNames(MinecraftServer server, List<ServerPlayer> players) {
        if (players.isEmpty()) return;
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), players));
    }
}
