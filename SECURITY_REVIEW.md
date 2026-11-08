# Security review

Scope: Combat Persistence 1.10.0 source, Minecraft 26.3, Java 25, Fabric Loader 0.20.1 and Fabric API 0.165.2+26.3. Reviewed inventory ownership, logout/rejoin/death ordering, authentication, duplicate connections, packet guards, local data files and external skin requests.

The original source had exploitable duplication and authentication races. The fixes below improve the mod substantially. This review does not certify that every exploit is absent.

## Confirmed findings and fixes

| Severity | Finding | Fix |
| --- | --- | --- |
| Critical | A crafting snapshot copied the recipe result preview and its unconsumed ingredients. Repeated combat logouts could create extra crafted items. | Snapshot only owned ingredients, cursor and inventory items. Do not mutate live crafting slots until a complete snapshot reaches disk. |
| Critical | Background password work authenticated a UUID before checking whether the original connection still existed. A replacement connection could inherit a completed login or registration. | Workers only hash captured strings. The main thread checks the request, connection, current player and account generation before committing authentication. Sessions bind to the packet listener, surviving respawn without transferring to a reconnect. |
| High | Vanilla duplicate-login handling could kick the owner before password verification. Two configuration-phase connections could race into play. | Reject an incoming matching UUID or case-insensitive player name before vanilla disconnects the existing player. Check again immediately before entering play. Reject alternate casing of an already registered offline account. |
| High | The JOIN event hid inventory and position after vanilla had already constructed the initial play packets. | Hide inventory, real position and last-death coordinates at the start of `placeNewPlayer`, before the listener and initial packets are built. Use raw relocation before the connection exists. |
| High | Operator/creative, movement, attack, spectator, query and other packet paths lacked authentication checks. | Gate the vanilla state-changing and private-query packet handlers before authentication. Gate both command packet forms and recheck queued command execution. Reject damage from unauthenticated players. |
| High | Snapshot writes logged failures but returned success; live inventory could then be cleared without a valid backup. Direct truncating writes could corrupt the previous snapshot. | Atomic temporary-file replacement, file/directory flushes, synchronous critical writes, propagated errors and rollback of in-memory ownership changes. Refuse startup with unreadable account/recovery state. |
| High | NPC loot appeared before the death record was saved. A crash could expose loot and later restore the same inventory. | Commit the death record synchronously before releasing loot. The authoritative entry also prevents repeated death callbacks from dropping it twice. |
| High | Recovery consumed a snapshot before player data was durably saved. Partial decoding restored items additively over stale inventory. | Require a complete decode, replace stale live inventory, explicitly commit player data, then consume the snapshot. Vanilla's normal save method swallows errors, so critical handoffs use a checked atomic player-data write. |
| High | A pending combat death could consume its marker before player data was saved, or restore a login stash after death. | Restore the real location, consume stale login inventory, clear the player, execute/save death, then consume both death markers. |
| Medium | BCrypt work and skin HTTP requests could grow unbounded queues. Password limits counted characters rather than BCrypt's 72-byte UTF-8 limit. | Bound worker queues and outstanding work; check UTF-8 bytes; bound/expire attempt maps. New configurations require at least eight password characters. |
| Medium | Skin lookups disclosed names and the server IP automatically, followed redirects and read unbounded responses. Cached names also grew without a fixed limit. | Disable external lookups by default. Bound cache, queue and response sizes; merge duplicate lookups; reject redirects and invalid identifiers; ignore stale application callbacks. |
| Medium | Sensitive JSON files inherited ordinary filesystem permissions; documentation claimed session-secret protection that did not exist. | Atomic writes create owner-only files on POSIX. Avoid persisting the last login IP when IP autologin is disabled. Correct the authentication/privacy documentation. |

Build tooling pins Loom 1.19.3, Gradle 9.8.2 with a distribution checksum, and Minotaur 2.10.0. JAR entries use reproducible order and timestamps. Complete corresponding source includes Gradle scripts, wrapper, license and validation tools. Configuration validation also repairs null command lists/messages and invalid numeric settings.

## Release validation findings

Live testing caught issues that a successful compile and mixin transformation alone did not detect:

- Clearing a crafting slot during early join recalculated a recipe and tried to send a packet before the listener existed. Clear owned inputs and result previews with `removeItemNoUpdate` instead.
- A dead player can remain the current player-list entry after removal from the level. Requiring `!isRemoved()` silently blocked password login before respawn. Require the current player object and accepting listener instead.
- `ServerPlayer.die` does not invoke the `LivingEntity.die` hook. A player-only Fabric death event clears the combat tag on legitimate death.
- Vanilla temporary menus return or drop their owned inputs when closed. Snapshot explicit vanilla input slots before closing; clear them after the durable snapshot, then close the empty menu. Anvil, smithing, merchant, stonecutter, grindstone, loom, cartography, enchantment and beacon inputs are covered. Result previews and shared block/entity storage are excluded.
- Minecraft 26.3 adds punch and internal position-change paths. Guard both, including the movement path invoked by teleport acknowledgements. Keep legitimate acknowledgement bookkeeping operational.
- Reject malformed recovery collection shapes, missing item snapshots, conflicting death/inventory ownership and duplicate NPC identifiers. Retain ownership when a removal write fails.
- A failed `addFreshEntity` now returns failure and retains the durable recovery snapshot instead of reporting a spawned NPC.
- A failed operator-reset write restores the account in memory and keeps the valid session. Registration cannot replace its owner after a failed reset.

## Verification

- A clean Gradle build followed by final incremental builds compiled the target version. All **28 JUnit tests pass** on JDK 25.
- Tests cover atomic-write failure preservation and ordering, propagated errors, POSIX permissions, UTF-8 password limits, corrupt account/recovery stores and failed recovery-entry removal.
- Authentication regressions exercise real BCrypt work with delayed main-thread commits: disconnect, replacement player, account reset and abandoned registration cannot authenticate a replacement connection. Authentication survives respawn on the same listener, and a late logout cannot revoke a newer session. A removed dead player with the current connection can authenticate. A failed operator-reset write preserves registration, the password hash and the live session.
- Inventory regressions use real vanilla anvil and chest menus. They check owned inputs, ingredients, cursor, armor and offhand; exclude recipe previews and chest contents; preserve the live copy until explicit clear; and reject recipe callbacks before a connection exists.
- Packaged release startup uses the normal Fabric server launcher, required Fabric API and bundled BCrypt. Test commands live in a separate disposable validation mod, excluded from the distributed mod JAR.
- Local network probes use the actual Minecraft 26.3 vanilla protocol without a client mod. They are protocol probes, not graphical Minecraft clients. The test world uses localhost offline mode to exercise password authentication.
- Completed live checks include initial inventory/position hiding, blocked movement and commands, a malicious teleport acknowledgement, duplicate name/case joins retaining the owner, the configuration-phase race allowing one player into play, crafting/cursor/equipment and anvil recovery, overflow, repeated NPC death releasing loot once, pending death, respawn/dead-player login, process-kill recovery, failed player writes, password races and lockout/reset.
- Final timeout and failed-reset fault injection passed on the release artifact. The original account and authenticated session remained intact after the refused reset; a later successful reset and registration worked. Another combat inventory round trip passed.
- The test tooling is documented in [tools/validation](tools/validation/README.md).
- No third-party gameplay mod or proxy compatibility is certified. Graphical client rendering and optional external skin lookups are outside the live matrix.

## Remaining limits and deployment requirements

1. **Offline-mode traffic is not encrypted by this mod.** BCrypt protects stored hashes only. Prefer online mode; otherwise use an independently secured encrypted tunnel or authentication proxy. Never reuse important passwords. Other mods, proxies, clients or diagnostic tools may log commands.
2. **Keep IP autologin disabled.** Shared NAT, VPN or proxy addresses are not proof of identity. Enabling it deliberately permits password-free access from a matching address. There is no session-secret mechanism.
3. **First registration does not prove account ownership.** Whitelist and arrange registration for existing offline players. Resetting an account permits new registration; operators must coordinate the reset with the owner. Lockouts can temporarily deny the real owner access, and attempt counters reset on restart.
4. **Crash safety spans several files and world chunks.** Loot can still be lost after a committed death or snapshot consumption but before the resulting world items are durably saved. Preventing repeated releases takes priority. There is no transaction spanning recovery files, player files and chunk data.
5. **Use consistent backups.** Restore world/player data and every recovery/authentication file together. Mismatched backups can undo ownership decisions. Restrict access to files and backups containing hashes, inventories and coordinates.
6. **Corrupt data requires operator repair.** The mod stops startup or refuses a restoration rather than silently discarding authoritative data or releasing a partial inventory. Atomic writes also require a filesystem supporting atomic replacement.
7. **Compatibility is bounded.** Custom mod inventories, additional command aliases and custom network payloads need separate testing. Combat command aliases must still be listed explicitly. This mod cannot guarantee another mod's authentication behavior.
8. **Existing configuration remains configurable.** Existing minimum password lengths are preserved. New registration rejects passwords exceeding 72 UTF-8 bytes; users affected by an older truncated password may need a coordinated operator reset. Disabling inventory/coordinate hiding weakens privacy.

Repeat the disposable-world matrix when changing Minecraft versions, adding gameplay mods or changing a proxy setup. Preserve the documented inventory and backup ownership rules during migration.
