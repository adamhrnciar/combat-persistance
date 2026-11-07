# Combat Persistence

**Keep PvP fights fair when players disconnect. A server-side Fabric anti-combat-logging mod for survival and PvP servers.**

A player leaves mid-fight. Their opponent still has a chance to finish it: Combat Persistence leaves a vulnerable NPC mannequin behind for the rest of the combat timer. If the mannequin dies, the player's items drop and the player dies when they return.

**Install on your server only. Players need no client mod.**

[**Download on Modrinth**](https://modrinth.com/mod/combat-persistence/versions) · [Configuration](#configuration) · [Report a bug](https://github.com/adamhrnciar/combat-persistance/issues)

Current release: **1.10.0** for **Minecraft 26.3**, **Fabric Loader 0.20.1+** and **Java 25+**. Requires **Fabric API**.

## How combat logging protection works

1. **Players fight.** PvP damage tags both players. The action bar shows the remaining time; the default tag lasts 15 seconds.
2. **A tagged player disconnects.** A mannequin stays at their location, wearing their equipment and available skin.
3. **The fight can continue.** Killing the mannequin drops the tracked items and records a death for the player's return.
4. **The player returns.** If the mannequin survived, the saved inventory is restored. The mannequin disappears when the tag expires.

Only PvP damage starts a combat tag. Mob attacks and environmental damage do not. A connection loss during combat follows the same rules as deliberately logging out.

## Installation

1.  Use a Minecraft **26.3** dedicated server with **Java 25 or later** and **Fabric Loader 0.20.1 or later**.
2.  Download the matching release from [Modrinth](https://modrinth.com/mod/combat-persistence/versions).
3.  Place the `.jar` and [Fabric API for Minecraft 26.3](https://modrinth.com/mod/fabric-api/versions?g=26.3) in your server's `mods` folder.
4.  Start the server, review `config/combatpersistence.json`, and test recovery on a disposable world before migration.

Use Modrinth's version selector for older Minecraft releases. Each release supports only its listed game versions.

## Server rules you control

- **Combat timer and messages:** choose the tag duration, action bar text, NPC name prefix and death announcement.
- **Combat command blocking:** block escapes such as `/home`, `/spawn` and `/tp`. Add any aliases your server provides.
- **Inventory recovery:** disk snapshots retain equipment, cursor items and owned vanilla menu inputs for recovery after a restart or chunk unload.
- **Optional ender chest restriction:** disable ender chests server-wide for your arena or SMP rules.
- **Offline-mode authentication:** built-in registration, BCrypt password hashing, login timeouts and lockouts. Online-mode players never need `/register` or `/login`.

The mannequin itself is not saved in chunks. Restarting the server does not recreate the offline fight. Recovery has [documented crash and compatibility limits](#security).

## Common questions

**Do players need Fabric or this mod on their client?** No. Install Combat Persistence and Fabric API on the dedicated Fabric server only.

**Is this a Paper, Spigot, Forge or NeoForge plugin?** No. This release targets Fabric servers. Do not place it in a Bukkit-style `plugins` folder.

**Does it work on older Minecraft versions?** Use the [Modrinth version selector](https://modrinth.com/mod/combat-persistence/versions). Install only a release explicitly marked for your server's Minecraft version.

**Does it work with every modpack or authentication proxy?** Compatibility with custom inventories, commands, packets and proxies needs testing on your server. See the [security review](SECURITY_REVIEW.md) for the tested scope.

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

[Download or update on Modrinth](https://modrinth.com/mod/combat-persistence/versions). Follow the project there to keep track of releases.

For a bug report, include your Minecraft version, Fabric Loader version, mod version, other mods and steps to reproduce. Remove passwords, addresses and player data from logs before sharing them in [GitHub Issues](https://github.com/adamhrnciar/combat-persistance/issues).

Optional donations: [ko-fi.com/pottersgame](https://ko-fi.com/pottersgame).

## License

This project is licensed under the GPL-3.0 License.
