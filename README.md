# Combat Persistence

Combat Persistence is a server-side Fabric mod for Minecraft that prevents combat logging by spawning a persistent NPC (Mannequin) when a player disconnects during a fight. It is built for production servers where competitive integrity and security are priority.

## Features

*   **Combat Tagging**: Automatically tags players when they engage in PvP.
*   **Persistent NPCs**: When a tagged player disconnects, a Mannequin NPC spawns at their location.
    *   **Anti-Dupe**: Clears the player's inventory and cursor stack on logout to prevent duplication exploits.
    *   **Visual Mirroring**: NPCs mirror the player's skin, armor, and held items.
    *   **Persistence**: The player's inventory is persisted to disk the moment the NPC spawns, surviving chunk unloads, server restarts, and crashes. (The NPC *entity* is intentionally never written to chunk data — if it despawns with the world, the items are still restored to the player on their next join.)
*   **Offline Death**: If the NPC is killed, the player's items drop and they will die upon rejoining the server.
*   **Authentication System**:
    *   **BCrypt Hashing**: Passwords are hashed, never stored in plaintext.
    *   **Session Autologin**: Remembers a session for a configurable window (default 24 hours). Optional IP-based autologin is **off by default** (weak behind shared NAT/proxies).
    *   **Brute-Force Lockout**: A wrong `/login` password kicks the player; after too many failed attempts the account — and the attacking IP — is locked and refused at join until the lockout expires.
    *   **Inventory Protection**: Hides a player's inventory and coordinates until they enter their password, so an impostor sharing their offline UUID cannot view their items.
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
| `minPasswordLength` | 4 | Minimum password length accepted by `/register`. |
| `maxPasswordLength` | 64 | Maximum password length (BCrypt truncates beyond 72 bytes, so long passwords are refused rather than silently cut). |
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
*   **Cursor Stack Dupe**: Prevents item duplication via cursor holding during logout.
*   **Ghost Inventory**: Ensures inventory state is cleared before the player fully disconnects.
*   **Spoofing Protection**: Uses session secrets rather than just IP-based autologin.
*   **Signed-Command Bypass**: Auth and combat command blocking cover both the plain and the signed chat-command packet paths.
*   **Thread Safety**: All data files are written through a single serialized writer thread, so concurrent saves can never overwrite newer data with a stale snapshot; critical snapshots (inventories) are flushed synchronously before the live copy is destroyed.

## Support

If you find this mod useful, you can support development here:
[ko-fi.com/pottersgame](https://ko-fi.com/pottersgame)

## Installation

1.  Download the latest release from [GitHub](https://github.com/PottersGame/combat-persistance/releases).
2.  Place the `.jar` in your server's `mods` folder.
3.  Ensure **Fabric API** is installed.

## License

This project is licensed under the GPL-3.0 License.
