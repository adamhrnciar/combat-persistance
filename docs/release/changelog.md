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

All fixes from 1.10.0 are included. See CHANGELOG.md for the full history.
