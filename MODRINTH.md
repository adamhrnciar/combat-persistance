# Combat Persistence

**Keep PvP fights fair when players disconnect. Server-side combat logging protection for Fabric survival and PvP servers.**

Your opponent logs out mid-fight. The fight does not have to end there: Combat Persistence leaves a vulnerable NPC mannequin behind for the remaining combat timer. Kill the mannequin, and the player's items drop. The player dies when they return.

**No client mod required. Install on your server only.**

[**Download for your Minecraft version**](https://modrinth.com/mod/combat-persistence/versions) · [Full configuration](https://github.com/adamhrnciar/combat-persistance#configuration) · [Report a bug](https://github.com/adamhrnciar/combat-persistance/issues)

Current release: **1.10.0 for Minecraft 26.3** · **Fabric Loader 0.20.1+** · **Java 25+** · **Fabric API required**

## From combat tag to reconnect

1. **Fight:** PvP damage tags both players. The action bar shows the countdown, with a 15-second default.
2. **Disconnect:** a mannequin stays behind with the player's equipment and available skin.
3. **Resolve:** opponents can kill it before the combat tag expires. If it survives, the player recovers their saved inventory on return.

Mob attacks and environmental damage do not start a tag. A connection loss during combat follows the same rules as deliberately logging out.

## Installation

1. Use a dedicated Minecraft **26.3** server with **Java 25 or later**.
2. Install **Fabric Loader 0.20.1 or later** and the [Fabric API build for Minecraft 26.3](https://modrinth.com/mod/fabric-api/versions?g=26.3).
3. Download the Combat Persistence version marked for **26.3** and put its JAR in the server's `mods` folder.
4. Start the server, then configure `config/combatpersistence.json`.
5. Test combat logout, reconnect and recovery on a disposable world before using your existing world.

Players need no client installation. Use the version selector for older Minecraft releases; compatibility labels apply to each uploaded version.

## Combat logging protection

- **PvP combat tags:** configurable duration, with remaining time in the action bar.
- **Offline mannequins:** copy the player's equipment and available skin while the combat tag remains active.
- **Inventory recovery:** disk snapshots retain inventory, equipment, cursor items and owned vanilla menu inputs for the next login if the mannequin disappears during a restart or chunk unload. Result previews and shared containers stay excluded.
- **Offline death:** committing a death record prevents the same tracked inventory from being released again.
- **Combat command blocking:** configure commands such as `/home`, `/spawn` and `/tp`. Add aliases used by your server.
- **Optional ender chest restriction:** disable ender chests server-wide for arena or SMP rules.

The mannequin entity itself is not saved in chunks. Inventory recovery persists separately. Restarting the server does not recreate the offline fight.

## Before you install

**Do my players need to install anything?** No. Combat Persistence and Fabric API go on the dedicated Fabric server only.

**Will online-mode players need a password?** No. Minecraft already verifies their accounts. The optional password system is for offline-mode servers.

**Does this work on Paper, Spigot, Forge or NeoForge?** This release is for Fabric. It is not a Bukkit-style plugin.

**What happens when the timer expires?** The mannequin disappears. If it survived, the saved inventory remains available for the player's return.

**What if the server restarts?** The mannequin does not respawn. Inventory recovery uses separate disk snapshots; read the recovery limits below.

## Optional offline-mode authentication

Online-mode servers use Minecraft account verification. The built-in password flow applies to offline-mode servers when enabled.

- BCrypt password hashes, with bounded password verification work.
- Inventory, position and last-death coordinates hidden before initial play packets when their protection settings are enabled.
- Authentication tied to the current connection.
- Duplicate UUID and case-insensitive name checks that reject the newcomer before replacing an existing connection.
- Login timeout, failed-attempt lockout and an operator account reset command.
- IP autologin disabled by default.

**Use online mode for public servers where possible.** This mod does not encrypt offline-mode network traffic. Password commands require an independently secured connection; never reuse an important password. First registration of an offline name does not prove ownership. Whitelist existing players and arrange registration with them.

## Useful settings

| Setting | Default | Purpose |
| --- | --- | --- |
| `combatTagDurationSeconds` | `15` | Time before a combat tag expires. |
| `blockedCommands` | See generated config | Commands refused during combat. |
| `disableEnderChests` | `false` | Disable ender chests server-wide. |
| `enableAuth` | `true` | Enable the built-in offline-mode password flow. |
| `forceAuthInOfflineMode` | `true` | Require the password flow in offline mode. |
| `enableIpAutoLogin` | `false` | Allow password-free login from a matching recent address. Keep disabled for stronger protection. |
| `hideInventoryBeforeAuth` | `true` | Hide inventory before login. |
| `hideCoordinatesBeforeAuth` | `true` | Hide position and last-death coordinates before login. |
| `enableSkinLookups` | `false` | Opt in to external Mojang skin requests. |

External skin requests disclose the requested name or UUID and the server's IP to Mojang. Clients may fetch the returned textures. This setting does not control requests made by Minecraft or other mods.

## Commands

| Command | Purpose |
| --- | --- |
| `/register <password> <confirmPassword>` | Register an offline-mode account. |
| `/login <password>` | Authenticate the current connection. |
| `/skin <name>` | Request a Mojang skin when external lookups are enabled. |
| `/authreset <player>` | Operator: reset an account for coordinated registration. |

## Recovery and compatibility

Back up the world and player data together with the mod's authentication and recovery JSON files. Restrict access: these files contain password hashes, inventory contents and coordinates.

Recovery prevents repeated inventory releases where possible. A crash between committing a death and saving dropped world items can still lose loot. No transaction spans all player files, recovery files and world chunks. Custom inventories, proxy authentication and other mods' packets need separate compatibility testing.

See the [configuration and security notes](https://github.com/adamhrnciar/combat-persistance#security) and [security review](https://github.com/adamhrnciar/combat-persistance/blob/main/SECURITY_REVIEW.md) for scope and limitations.

## Get updates and support

[Download the matching version](https://modrinth.com/mod/combat-persistence/versions) and follow Combat Persistence on Modrinth for future releases.

Report reproducible bugs with your Minecraft version, mod version and relevant server log. Remove passwords, addresses and player data before sharing logs.

[Report an issue](https://github.com/adamhrnciar/combat-persistance/issues) · [Source code](https://github.com/adamhrnciar/combat-persistance) · [Support on Ko-fi](https://ko-fi.com/pottersgame)
