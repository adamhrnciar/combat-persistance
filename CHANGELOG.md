# Changelog

## 1.12.0+26.2

### Player heads in chat and tab list

- The server draws each player's head before their name in chat (also `/say`, `/me`, `/msg`, `/teammsg`) and in the tab list, using 26.x player-head text sprites. Vanilla clients see them; no client mod is needed.
- Works on offline-mode servers, where vanilla clients otherwise show no tab-list heads. Heads use the skin from the player's profile, so they show the skin set by the skin lookup (`enableSkinLookups`).
- The tab name keeps the team prefix, suffix and colour. A display name set by another mod is kept and only gets the head added. Changes (team, skin) are resent once a second.
- New config options `showHeadsInChat` and `showHeadsInTab`, both on by default.

## 1.11.0+26.2

### Compatibility

- Minecraft 26.2 build, Fabric Loader 0.20.1 and Fabric API 0.161.0+26.2. Java 25 or later.
- Existing `combat_persistence_data.json` files load unchanged; the new `keptItems` and `npcTeamEntries` sections are added on the next save.

### API for other mods

- New `com.pottersgame.api` package with documented Fabric events.
- `CombatNpcEvents.NPC_KILLED` fires once when a combat-log NPC dies and its death has been saved.
- `CombatNpcEvents.KEEP_ON_NPC_DEATH` lets listeners keep chosen items when an NPC dies. Kept items are written to the recovery file in the same synchronous save as the owner's death record, before any loot drops, and return to the owner after the join-death and respawn. With no listeners nothing changes.
- `CombatPersistenceApi.isInCombat(ServerPlayer)`.

### Teams

- The NPC joins its owner's scoreboard team. It leaves the team on any removal, including chunk unload. Entries left behind by a crash or server stop are removed at the next startup; only NPC UUIDs this mod recorded are touched.
- When the owner's current team has friendly fire off, a teammate cannot damage the NPC directly, with projectiles, through their pets or with TNT they lit, so teammates cannot kill or loot it. Environmental damage (lava, fire, redstone-lit TNT, suffocation) is not blocked.

### Inventory safety

- Kept items are returned only when the whole batch fits into empty inventory slots. Otherwise the batch stays pending, the player is told how many slots to free, and it is retried on the next join, login or respawn. Nothing is ever dropped on the ground.
- Each batch is placed at most once. A marker tag is added before any item is placed, and the player data holding items and marker is saved before the batch is consumed. If that save fails, the placement and marker are undone and the batch stays pending. If the tag cannot be added (vanilla's 1024-tag limit), nothing is placed.
- New config messages `keptItemsRestoredMessage` and `keptItemsNeedSpaceMessage`.

### Upgrade and downgrade notes

- Back up the world together with `config/combat_persistence_data.json` before upgrading.
- Only downgrade to 1.10.x when no `keptItems` are pending in `combat_persistence_data.json`. 1.10.x ignores that section and drops it on its next save, which loses those items.

## 1.10.0

### Compatibility

- Update to Minecraft 26.3, Fabric Loader 0.20.1 and Fabric API 0.165.2+26.3.
- Require Java 25 or later. This build supports Minecraft 26.3; use older releases for other Minecraft versions.

### Inventory and recovery

- Exclude crafting result previews from inventory snapshots while preserving owned ingredients, cursor items, equipment and offhand items.
- Snapshot owned inputs from vanilla crafting, anvil, smithing, merchant and other temporary menus without copying result previews or shared containers.
- Complete a durable inventory snapshot before clearing the disconnecting player's inventory.
- Reject incomplete inventory decoding instead of restoring a partial snapshot over stale inventory.
- Retain durable recovery state when NPC creation or a critical removal write fails.
- Save recovered player data before consuming its recovery record.
- Commit NPC death records before releasing loot.
- Use serialized atomic file replacement with propagated write failures and owner-only permissions on supported POSIX filesystems.
- Refuse startup with unreadable account or recovery data instead of silently discarding it.

### Authentication and privacy

- Bind authentication to the current connection and reject stale asynchronous password results after disconnect, account reset or replacement.
- Reject duplicate UUIDs and case-insensitive names before vanilla replaces the existing connection; recheck before entering play.
- Hide inventory, position and last-death coordinates before initial play packets when the corresponding settings are enabled.
- Guard additional unauthenticated packet paths, including Minecraft 26.3 movement and punch paths, and recheck queued commands.
- Allow authenticated recovery and respawn after reconnecting while dead; clear combat tags on legitimate player death.
- Clear early-login crafting state without sending packets before the listener exists.
- Bound password workers, failed-attempt records and skin request queues.
- Reject passwords beyond BCrypt's 72-byte UTF-8 limit. New configurations use an eight-character minimum.
- Disable external Mojang skin lookups by default; enable them explicitly with `enableSkinLookups`.
- Avoid storing login IP addresses when IP autologin is disabled.
- Keep the registered account and its live session intact when an operator reset cannot be persisted.

### Upgrade notes

Back up world/player data and all authentication/recovery files together before upgrading. Keep backups private.

External custom skins now require `enableSkinLookups=true`; this discloses requested names or UUIDs and the server IP to Mojang. Existing password limits remain configurable. Accounts affected by older BCrypt truncation may need an operator-coordinated reset.

Prefer online mode. This mod does not encrypt offline-mode password traffic. Keep IP autologin disabled, and arrange first registration for existing offline players.

Recovery cannot transact across player files, recovery files and world chunks. A crash can still lose dropped loot. Custom mod inventories, proxy setups and network payloads require separate testing. See `SECURITY_REVIEW.md` for validation scope and limits.
