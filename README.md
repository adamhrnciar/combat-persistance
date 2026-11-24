# Combat Persistence

A server-side Fabric mod that stops combat logging. If a player quits in the middle of a fight, a copy of them stays behind with their gear until the combat timer runs out. Kill it and their items drop. When they log back in, they're dead.

Players don't need to install anything. It only goes on the server.

[Download on Modrinth](https://modrinth.com/mod/combat-persistence/versions) · [Config](#configuration) · [Report a bug](https://github.com/adamhrnciar/combat-persistance/issues)

Current release: 1.10.0 for Minecraft 26.3 (Fabric Loader 0.20.1+, Java 25+, needs Fabric API).

![A player logs out mid-fight, their mannequin gets killed and drops the loot](docs/media/combat-log.gif)

## How it works

1. Hitting another player puts you both in combat for 15 seconds (configurable). The countdown shows above the hotbar.
2. If you log out during combat, an `[OFFLINE]` mannequin with your gear stays where you were.
3. If someone kills it, your items drop and you die the next time you join.
4. If nobody kills it before the timer runs out, it disappears and you get everything back when you log in.

Only PvP starts the timer. Mobs and environmental damage don't. Losing connection counts the same as quitting.

| Nobody kills the mannequin | Escape commands are blocked |
| --- | --- |
| ![The mannequin survives and the player logs back in with all their gear](docs/media/survive.gif) | ![Trying /spawn and /tp during combat](docs/media/blocked-commands.gif) |

## Installing

1. Run a Minecraft 26.3 Fabric server with Fabric Loader 0.20.1 or newer and Java 25.
2. Put the mod and [Fabric API for 26.3](https://modrinth.com/mod/fabric-api/versions?g=26.3) in the `mods` folder.
3. Start the server once and edit `config/combatpersistence.json` if you want to change anything.

For older Minecraft versions, use the version list on [Modrinth](https://modrinth.com/mod/combat-persistence/versions). Each file only supports the versions listed for it.

## What else it does

- Blocks `/spawn`, `/home`, `/tp`, `/tpa`, `/warp` and `/back` during combat. Add your own commands or aliases to the list.
- Saves the inventory to disk when someone combat logs, so a restart or chunk unload doesn't eat their items.
- Can turn off ender chests server-wide if you run an arena.
- Puts the mannequin on its owner's scoreboard team. With friendly fire off, teammates can't damage it directly, with projectiles, through their pets or with TNT they lit, so they can't kill or loot it. Environmental damage (lava, fire, redstone-lit TNT, suffocation) still hurts it.
- Lets other server mods react to mannequin kills and keep chosen items. See [API for other mods](#api-for-other-mods).
- Has an optional `/register` and `/login` system for offline-mode servers. Online-mode players never see it.

The mannequin itself isn't saved with the world, so a restart ends the fight. The inventory is still restored on the next join.

## Questions

**Do players need the mod?** No, only the server.

**Is this a Paper, Spigot, Forge or NeoForge plugin?** No. It's a Fabric mod and goes in the `mods` folder.

**Older Minecraft versions?** Check the [Modrinth version list](https://modrinth.com/mod/combat-persistence/versions) and only use a file marked for your version.

**Does it work with other mods?** Vanilla inventories are covered. Mods with their own inventory slots, custom commands or auth proxies haven't been tested, so try it on a test world first.

## Configuration

The configuration file is located at `config/combatpersistence.json`.

### Combat Settings
| Setting | Default | Description |
| :--- | :--- | :--- |
| `combatTagDurationSeconds` | 15 | Duration of the combat tag in seconds. |
| `npcNamePrefix` | `§7[OFFLINE] §f` | The prefix applied to the NPC's name tag. |
| `playSpawnSound` | `true` | Plays a thunder sound when an NPC spawns. |
| `combatMessage` | `§c§lIN COMBAT: §f%s s remaining` | The action bar message shown to tagged players. Use `%s` for time remaining. |
| `npcDeathBroadcast` | `§c[PvP] §7%s combat logged and was slain!` | The message broadcast when an NPC is killed. |
| `debugLogging` | `false` | Extra INFO logging for snapshots, restores and mannequins. Useful when debugging a live server. Errors are always logged. |
| `inventoryRestoredMessage` | `§aYour inventory has been restored.` | Message sent when a player's inventory is restored. |
| `blockedCommands` | `[...]` | List of commands disabled during combat (e.g., /spawn, /home). Namespaced forms like `/minecraft:tp` are blocked automatically; command *aliases* (e.g. `/teleport` for `/tp`) need their own entry. |
| `commandBlockedInCombatMessage` | `§cYou cannot use this command while in combat!` | Message shown when a blocked command is used in combat. |

### Arena / SMP Settings
| Setting | Default | Description |
| :--- | :--- | :--- |
| `disableEnderChests` | `false` | Blocks ender chest use server-wide (via mixin). |
| `enderChestDisabledMessage` | `§cEnder chests are disabled on this server.` | Message shown when a blocked ender chest is opened. |

### Authentication Settings
| Setting | Default | Description |
| :--- | :--- | :--- |
| `enableAuth` | `true` | Turns on the built-in login system. It only applies to offline-mode servers. On online-mode servers Mojang already verifies accounts, so nobody is asked to register. |
| `forceAuthInOfflineMode` | `true` | Requires authentication when the server is in offline mode. Set to `false` to opt an offline-mode server out of password auth entirely. |
| `enableIpAutoLogin` | `false` | Optional IP-based autologin. Off by default (weak behind shared NAT/proxies). |
| `sessionDurationHours` | 24 | How long an autologin session lasts for standard players. |
| `maxLoginAttempts` | 5 | Failed `/login` attempts before lockout (tracked per account and per source IP). |
| `loginLockoutSeconds` | 300 | Lockout duration after too many failed attempts. While locked out, the player is refused at join. |
| `kickOnWrongPassword` | `true` | Kick the player immediately on a wrong `/login` password. |
| `minPasswordLength` | 8 | Minimum password length accepted by `/register`. |
| `maxPasswordLength` | 64 | Maximum password length (BCrypt truncates beyond 72 bytes, so long passwords are refused rather than silently cut). |
| `enableSkinLookups` | `false` | Opt in to external Mojang skin requests. Requests disclose the server IP and requested name/UUID. |
| `showHeadsInChat` | `true` | Show the sender's head before their name in chat, `/say`, `/me` and `/msg`. Works on vanilla clients. |
| `showHeadsInTab` | `true` | Show each player's head before their name in the tab list, keeping team colours. Works on vanilla clients and offline-mode servers. |
| `skinCommandCooldownSeconds` | 30 | Cooldown between `/skin` uses, protecting the server's shared Mojang API rate limit. |
| `hideInventoryBeforeAuth` | `true` | Conceals the player's inventory until they log in. |
| `hideCoordinatesBeforeAuth`| `true` | Conceals the player's location from unauthenticated users. |
| `authTimeoutSeconds` | 60 | Time in seconds before an unauthenticated player is kicked. |
| `loginTimeoutMessage` | `§cLogin timeout!` | Kick message when authentication times out. |
| `registerPrompt` | `§6Please register using /register <password> <confirmPassword>` | Message prompting for registration. |
| `loginPrompt` | `§6Please log in using /login <password>` | Message prompting for login. |
| `registerSuccess` | `§aSuccessfully registered and logged in!` | Message sent upon successful registration. |
| `loginSuccess` | `§aSuccessfully logged in!` | Message sent upon successful login. |
| `alreadyLoggedIn` | `§cYou are already logged in!` | Error message when already logged in. |
| `alreadyRegistered` | `§cYou are already registered!` | Error message when already registered. |
| `passwordMismatch` | `§cPasswords do not match!` | Error message for password mismatch. |
| `incorrectPassword` | `§cIncorrect password!` | Error message for incorrect password. |
| `authRequiredForCommand` | `§cYou must log in to use commands!` | Error message when using commands without auth. |
| `authRequiredForChat` | `§cYou must log in to chat!` | Error message when chatting without auth. |
| `authRequiredForSkin` | `§cYou must log in to change your skin!` | Error message when changing skins without auth. |
| `skinAppliedMessage` | `§aApplied skin: %s` | Message confirming skin application. |
| `lobbyDimension` | `minecraft:overworld` | The dimension where unauthenticated players are held. |
| `lobbyX, lobbyY, lobbyZ` | `0, 1000, 0` | The coordinates for the authentication lobby. |

## Commands

*   **/register <password> <confirmPassword>**: Register your account.
*   **/login <password>**: Login to your account.
*   **/skin <name>**: Change your skin using a Mojang account name.
*   **/authreset <player>**: *(Operator only)* Reset a player's account so they must register again.

## API for other mods

Since 1.11.0, other server-side Fabric mods can hook into combat-log NPCs through the `com.pottersgame.api` package. Compile against the Combat Persistence jar as `compileOnly` (never bundle it), and only touch these classes when `FabricLoader.getInstance().isModLoaded("combatpersistence")` is true, so your mod still runs without it.

| API | What it does |
| :--- | :--- |
| `CombatNpcEvents.NPC_KILLED` | Fired once when a combat-log NPC dies and its death is saved: `(ServerLevel level, UUID ownerUuid, String ownerName, LivingEntity npc, DamageSource source)`. `source.getEntity()` is the killer, if any. |
| `CombatNpcEvents.KEEP_ON_NPC_DEATH` | Asked for each stored item before anything drops: `boolean shouldKeep(UUID ownerUuid, ItemStack stack, int slot, boolean isEquipmentSlot)`. If any listener returns `true`, the item does not drop. It is saved together with the owner's death and given back after their next join, once they have died and respawned. |
| `CombatPersistenceApi.isInCombat(ServerPlayer)` | Whether the player is combat-tagged right now. |

Slots are vanilla inventory indices: `0-35` main inventory, `36-39` armor (feet to head), `40` offhand, `-1` (`CombatNpcEvents.NO_SLOT`) for the cursor and crafting grid. The stack passed to `shouldKeep` is a copy. Listeners run on the server thread; one that throws is logged and ignored. With no listeners, every item drops as before.

```java
// Only call this when FabricLoader.getInstance().isModLoaded("combatpersistence") is true.
public final class CombatPersistenceCompat {
    public static void register() {
        // Armor stays with the player, like a keep-armor death rule.
        CombatNpcEvents.KEEP_ON_NPC_DEATH.register((owner, stack, slot, isEquipmentSlot) ->
            isEquipmentSlot && slot != Inventory.SLOT_OFFHAND);

        // Pay a bounty for killing a combat logger.
        CombatNpcEvents.NPC_KILLED.register((level, ownerUuid, ownerName, npc, source) -> {
            if (source.getEntity() instanceof ServerPlayer killer) {
                killer.sendSystemMessage(Component.literal("You killed " + ownerName + "'s combat logger"));
            }
        });
    }
}
```

Kept items come back only when the whole batch fits in the player's empty inventory slots. Otherwise the player is told how many slots to free, and the items wait until their next join, login or respawn. They are never dropped on the ground.

The NPC joins its owner's scoreboard team. If the owner's team has friendly fire off, teammates can't damage it directly, with projectiles, through their pets or with TNT they lit. Environmental damage is not blocked.

Only downgrade to 1.10.x when `combat_persistence_data.json` has no pending `keptItems`. Older versions drop that section.

## Security

A few things are worth knowing before you put this on a public server.

**Offline mode.** Offline mode doesn't encrypt traffic, so `/register` and `/login` passwords can be read by anyone who can see the connection. Hashing only protects the stored passwords. Use online mode for public servers if you can, or put the server behind an encrypted tunnel. Players shouldn't reuse real passwords.

**Recommended settings.** Keep `enableIpAutoLogin=false`, `hideInventoryBeforeAuth=true` and `hideCoordinatesBeforeAuth=true`. In offline mode, the first person to register a name owns it, so whitelist your existing players and have them register before you open the server. Lockouts can lock out the real owner for a while, and a restart resets the attempt counters.

**Skins.** Custom skin lookups are off by default. Turning on `enableSkinLookups` sends the requested name or UUID and your server's IP to Mojang.

**Backups.** Back up the world together with `config/auth_data.json`, `pending_locations.json`, `pending_inventories.json` and `combat_persistence_data.json`. They contain password hashes, inventories and coordinates, so keep the backups private. If one of these files can't be read, the server refuses to start instead of quietly treating everyone as unregistered.

**Known limits.** If the server crashes right after a mannequin dies or an overflow snapshot is used, the items can be lost before the world saves. The mod is built to avoid giving items out twice, but there's no single transaction across player files, recovery files and chunks. It also guards against duplicate logins, crafting-grid and cursor dupes, and the signed-command bypass. See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) for what was tested.

## Support

Bug reports go in [GitHub Issues](https://github.com/adamhrnciar/combat-persistance/issues). Include your Minecraft, Fabric Loader and mod versions, your other mods, and how to reproduce it. Remove passwords, IP addresses and player data from logs first.

If the mod is useful to you, a follow on [Modrinth](https://modrinth.com/mod/combat-persistence) or a coffee on [Ko-fi](https://ko-fi.com/pottersgame) is appreciated.

## License

GPL-3.0.
