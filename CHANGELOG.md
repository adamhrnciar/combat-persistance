# Changelog

## 1.11.0+26.2

### Compatibility

- Minecraft 26.2 build, Fabric Loader 0.20.1 and Fabric API 0.161.0+26.2. Java 25 or later.
- Existing `combat_persistence_data.json` files load unchanged; the new `keptItems` section is added on the next save.

### API for other mods

- New `com.pottersgame.api` package with documented Fabric events.
- `CombatNpcEvents.NPC_KILLED` fires once when a combat-log NPC dies and its death has been saved.
- `CombatNpcEvents.KEEP_ON_NPC_DEATH` lets listeners keep chosen items when an NPC dies. Kept items are written to the recovery file in the same synchronous save as the owner's death record, before any loot drops, and return to the owner after the join-death and respawn. With no listeners nothing changes.
- `CombatPersistenceApi.isInCombat(ServerPlayer)`.

### Teams

- The NPC joins its owner's scoreboard team and leaves it when it is discarded, dies or unloads.
- A player on the same team cannot damage the NPC when that team has friendly fire turned off, so teammates cannot kill or loot it.

### Inventory safety

- Each kept-item batch is placed at most once: the player save that holds the items also holds a marker, and only then is the batch consumed. Overflow is dropped only after the player save succeeds.
- New config message `keptItemsRestoredMessage`.

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
