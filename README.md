# Combat Persistence

Combat Persistence is a server-side Fabric mod for Minecraft PvP and SMP servers. When a tagged player disconnects, a vulnerable mannequin keeps the fight open for the remaining combat tag. Players need no client mod.

## Features

*   **Combat Tagging**: Automatically tags players when they engage in PvP.
*   **Persistent NPCs**: When a tagged player disconnects, a Mannequin NPC spawns at their location.
    *   **Anti-Dupe**: Clears the player's inventory and cursor stack on logout to prevent duplication exploits.
    *   **Visual Mirroring**: NPCs mirror the player's skin, armor, and held items.
    *   **Persistence**: The player's inventory is persisted to disk the moment the NPC spawns, surviving chunk unloads, server restarts, and crashes. (The NPC *entity* is intentionally never written to chunk data — if it despawns with the world, the items are still restored to the player on their next join.)
*   **Offline Death**: If the NPC is killed, the player's items drop and they will die upon rejoining the server.
*   **Authentication System**:
    *   **BCrypt Hashing**: Passwords are hashed, never stored in plaintext.
    *   **Optional IP Autologin**: Disabled by default. When enabled, an address match within the configured window (default 24 hours) bypasses password entry. Shared NAT, VPNs and proxies make this unsafe.
    *   **Brute-Force Lockout**: A wrong `/login` password kicks the player; after too many failed attempts the account — and the attacking IP — is locked and refused at join until the lockout expires.
    *   **Inventory Protection**: Hides inventory, position, and last-death coordinates before the initial play packets. Authentication applies to one connection, so a reconnect cannot inherit another connection's login.
    *   **Operator Reset**: `/authreset <player>` lets staff wipe a forgotten or compromised account.
    *   **Lobby Support**: Teleports unauthenticated players to a safe lobby dimension.
*   **Arena/SMP Tools**: Optionally disable ender chests server-wide (blocked via mixin).
*   **Command Blocking**: Configurable list of commands blocked while in combat.
*   **Server-Side**: No client-side installation required.

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
| `debugLogging` | `false` | Verbose INFO-level diagnostics for combat-log snapshots, restores and NPC lifecycle — for debugging on production servers. Errors are always logged regardless. |
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
| `enableAuth` | `true` | Enables the built-in authentication system. Auth only ever applies to **offline-mode** servers — online-mode servers already have Mojang-verified identities, so players there are never asked to register or log in. |
| `forceAuthInOfflineMode` | `true` | Requires authentication when the server is in offline mode. Set to `false` to opt an offline-mode server out of password auth entirely. |
| `enableIpAutoLogin` | `false` | Optional IP-based autologin. Off by default (weak behind shared NAT/proxies). |
| `sessionDurationHours` | 24 | How long an autologin session lasts for standard players. |
| `maxLoginAttempts` | 5 | Failed `/login` attempts before lockout (tracked per account and per source IP). |
| `loginLockoutSeconds` | 300 | Lockout duration after too many failed attempts. While locked out, the player is refused at join. |
| `kickOnWrongPassword` | `true` | Kick the player immediately on a wrong `/login` password. |
| `minPasswordLength` | 8 | Minimum password length accepted by `/register`. |
| `maxPasswordLength` | 64 | Maximum password length (BCrypt truncates beyond 72 bytes, so long passwords are refused rather than silently cut). |
| `enableSkinLookups` | `false` | Opt in to external Mojang skin requests. Requests disclose the server IP and requested name/UUID. |
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

## Security

This mod addresses several common vulnerabilities:
*   **Crafting and Cursor Protection**: Snapshots include owned crafting and vanilla menu inputs, cursor items and equipment. Recipe result previews never become extra loot.
*   **Ghost Inventory**: Ensures inventory state is cleared before the player fully disconnects.
*   **Connection Protection**: Rejects duplicate player names/UUIDs before vanilla can kick the existing connection. Password verification results apply only to the original, still-connected player.
*   **Signed-Command Bypass**: Auth and combat command blocking cover both the plain and the signed chat-command packet paths.
*   **Durable Handoffs**: Serialized, atomic snapshot writes propagate failures. Inventory recovery commits player data before consuming its backup. NPC death commits its death record before exposing loot.

### Deployment limits

Prefer Minecraft online mode for public servers. Offline-mode password commands travel without Minecraft transport encryption; BCrypt protects stored hashes, not network traffic. Use a trusted encrypted tunnel or a correctly secured authentication proxy if offline mode is necessary. Never reuse an important password.

Keep `enableIpAutoLogin=false`, `hideInventoryBeforeAuth=true` and `hideCoordinatesBeforeAuth=true` for the strongest built-in protection. An unregistered offline name belongs to whoever registers it first; use a whitelist and prearranged registration for existing players. Account lockouts can temporarily deny the real owner access, and restart clears the in-memory attempt counters.

Custom skin requests are disabled by default. Enabling `enableSkinLookups` sends requested names/UUIDs and the server's IP to Mojang. Clients may also fetch the returned texture from Mojang. This setting does not control Minecraft's own network requests or other mods' behavior.

Back up the world/player data together with `config/auth_data.json`, `pending_locations.json`, `pending_inventories.json` and `combat_persistence_data.json`. These files contain password hashes, inventory contents and coordinates; new atomic writes restrict them to the owner on POSIX filesystems. Restrict backup access too. Unreadable recovery or account files stop startup rather than silently treating players as unregistered.

A crash after committing an NPC death or consuming an overflow recovery snapshot can still lose loot before the world saves it. Recovery favors preventing duplicate releases; it does not provide a transaction spanning player files, recovery files and world chunks. Other mods' custom inventories, commands and network packets require separate compatibility testing. Review [SECURITY_REVIEW.md](SECURITY_REVIEW.md) for tested scope and remaining checks.

## Support

If you find this mod useful, you can support development here:
[ko-fi.com/pottersgame](https://ko-fi.com/pottersgame)

## Installation

1.  Use a Minecraft **26.3** dedicated server with **Java 25 or later** and **Fabric Loader 0.20.1 or later**.
2.  Download the matching release from [Modrinth](https://modrinth.com/mod/combat-persistence/versions).
3.  Place the `.jar` and **Fabric API for Minecraft 26.3** in your server's `mods` folder.
4.  Start the server, review `config/combatpersistence.json`, and test recovery on a disposable world before migration.

Use Modrinth's version selector for older Minecraft releases. Each release supports only its listed game versions.

## License

This project is licensed under the GPL-3.0 License.
