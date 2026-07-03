# Combat Persistence 🛡️

**Combat Persistence** is a professional-grade, **100% server-side** Fabric mod for competitive Minecraft servers. It kills combat logging by spawning a vulnerable NPC whenever a player disconnects mid-fight — so closing the game is never an escape. It also ships a hardened, built-in authentication system to lock down offline-mode and cracked servers.

> No client install required. Your players just connect and play.

---

## ⚔️ Combat Logging Protection

- **Smart Combat Tagging** — Players are tagged only on genuine PvP damage. Configurable duration with live action-bar feedback.
- **Persistent NPCs** — Disconnecting while tagged spawns a `Mannequin` NPC at your exact location that mirrors your **skin, armor, and held items**.
- **Survives Anything** — The player's inventory is written to disk the instant the NPC spawns, so it survives chunk unloads, restarts, and crashes; items are always restored on the player's next join.
- **Reliable Offline Deaths** — If your NPC is slain while you're away, you rejoin straight to the death screen. No ghost items, no survival glitches.
- **Advanced Dupe Protection** — Clears both the inventory **and the cursor stack** the instant you disconnect, closing the duplication exploits that plague other combat-log mods.
- **Command Blocking** — Stop players escaping a fight with `/tp`, `/home`, `/spawn`, `/warp`, `/back`, and any custom commands you add. Namespaced forms (`/minecraft:tp`) and the signed command packet path are covered too.

## 🔐 Built-in Secure Authentication

- **BCrypt password hashing** — Passwords are never stored in plaintext.
- **Inventory & coordinate hiding** — Until a player logs in, their inventory and location are concealed, so nobody joining under their name (a real risk in offline mode) can peek at their items or position.
- **Brute-force lockout** — A wrong password kicks the player on the spot; too many failures lock the account *and* the attacking IP, refusing the connection at join until the cooldown expires.
- **Operator account reset** — `/authreset <player>` lets staff wipe a forgotten/compromised account so the player can re-register.
- **Conservative IP auto-login** — Optional and **off by default** (IP auto-login is weak behind shared NAT / BungeeCord / Velocity proxies). Enable only on trusted direct-connect servers.
- **Safe limbo** — Unauthenticated players are held in a lobby with hidden coordinates to prevent world-leaking.

## 🪓 Arena / SMP Extras

- **Ender chest toggle** — Optionally disable ender chests server-wide (reliably blocked via mixin) so players can't stash loot mid-fight on arena/SMP setups.

---

## 🆕 What's New in 1.8.0

- 🚨 **Fixed online-mode servers** — auth correctly never applies there; players were previously frozen, blocked, and kicked with "Login timeout".
- 🧰 **Fixed open-container theft** — disconnecting in combat with a chest open no longer pulls the chest's contents into the combat-log NPC.
- 🔒 **Harder brute-force protection** — a wrong `/login` password kicks the player; lockouts now also track the attacking IP and refuse the connection at join until the cooldown expires.
- 🛡️ **Closed command bypasses** — blocked/pre-auth commands are also caught on the signed command packet path and in namespaced form (`/minecraft:tp`).
- 💾 **Crash-safe data** — all saves go through one serialized writer (no stale-snapshot overwrites), critical snapshots flush synchronously, auth data flushes on shutdown, and a corrupt config no longer crashes startup.
- 🧪 **Dupe & loss fixes** — NPC double-death drops closed; NPC inventory persisted before the live copy is cleared; combat tags end on real deaths.
- 🎨 **`/skin` reliability** — profile swap works in production builds, Mojang lookups are cached (including failures) and rate-limited with a per-player cooldown; IPv6 players get working IP auto-login.
- 🔑 Minimum/maximum password length for `/register`.

## 🗒️ What's New in 1.7.0

- ✅ **Updated for Minecraft 26.2** (Java 25).
- 🔒 Patched offline-mode identity exploits (premium-name bypass, inventory exposure, login-fall).
- 🔑 Added operator account reset (`/authreset`) and hardened IP auto-login.
- 🪓 Added a toggle to disable ender chests for arena/SMP servers, now reliably blocked via mixin.
- 🛡️ Combat tags now trigger only on real player damage; skin identifiers are validated and Mojang fetches isolated from the common thread pool.
- 🔐 Added brute-force login lockout.

## ⌨️ Commands

| Command | Description |
| :--- | :--- |
| `/register <password> <confirm>` | Register your account. |
| `/login <password>` | Log in to your account. |
| `/skin <name>` | Apply a skin from a Mojang account name. |
| `/authreset <player>` | **(Operator)** Reset a player's account so they must register again. |

## 🔧 Configuration

Everything lives in `config/combatpersistence.json` and generates on first launch. Highlights:

| Setting | Default | What it does |
| :--- | :--- | :--- |
| `combatTagDurationSeconds` | `15` | Length of the combat tag. |
| `blockedCommands` | `/spawn, /home, /tp, /tpa, /warp, /back` | Commands disabled while tagged. |
| `disableEnderChests` | `false` | Block ender chests server-wide (arena/SMP). |
| `enableAuth` | `true` | Toggle the whole authentication system. |
| `forceAuthInOfflineMode` | `true` | Require auth on offline-mode servers (auth never applies on online-mode servers — Mojang already verifies those identities). |
| `enableIpAutoLogin` | `false` | Optional IP-based auto-login (use only on trusted servers). |
| `maxLoginAttempts` / `loginLockoutSeconds` | `5` / `300` | Brute-force lockout threshold and cooldown. |
| `sessionDurationHours` | `24` | How long an auto-login session lasts. |
| `hideInventoryBeforeAuth` | `true` | Conceal inventory until login. |
| `hideCoordinatesBeforeAuth` | `true` | Conceal coordinates until login. |

> All in-game messages are fully customizable (color codes supported).

## 📦 Requirements

- **Fabric Loader** + **Fabric API**
- **Minecraft 26.2**
- **Java 25+**

## 💎 Why Combat Persistence?

Built with an **exploit-first** mindset. We've manually patched duplication glitches (cursor stacks, inventory ghosting), offline-mode identity spoofing, and authentication bypasses that affect other similar mods. All data files are written through a serialized background writer — thread-safe and lag-free under load, with critical snapshots flushed synchronously before the only live copy is destroyed.

## ❤️ Support

If this mod helps your server, you can support development here: **[ko-fi.com/pottersgame](https://ko-fi.com/pottersgame)**

---

[Source Code](https://github.com/PottersGame/combat-persistance) · [Issue Tracker](https://github.com/PottersGame/combat-persistance/issues) · [GPL-3.0 License](https://github.com/PottersGame/combat-persistance/blob/main/LICENSE)
