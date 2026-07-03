# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A server-side Minecraft Fabric mod (MC 26.2, Java 25) with two features that share infrastructure:
1. **Combat logging prevention** — disconnecting mid-PvP spawns a Mannequin NPC holding the player's items.
2. **Offline-mode authentication** — /register + /login with BCrypt, inventory/coordinate hiding, lobby confinement.

## Commands

```bash
bash gradlew build          # jar → build/libs/  (wrapper is NOT executable; use `bash gradlew`)
bash gradlew runServer      # interactive dev server (generates run/; needs run/eula.txt)
.claude/skills/run-combatpersistence/driver.sh smoke   # headless boot + assert + clean stop
```

- There is **no unit test suite** (`test` is NO-SOURCE). The smoke driver is the executable check; see the `run-combatpersistence` skill for driving the dev server (console via FIFO, `send`/`await`/`log`).
- The `(0.4) is not valid semver for dependency org.mindrot:jbcrypt` build warning is harmless.
- `run/` is the dev-server working dir, gitignored, safe to wipe.

**Release:** bump `mod_version` in `gradle.properties`, add a section to MODRINTH.md, commit, tag `v<version>`, push the tag. CI (`.github/workflows/build.yml`) builds, creates the GitHub release, and publishes to Modrinth (`MODRINTH_TOKEN` secret). The workflow runs from the *tagged* commit — tag after the CI/build files are correct. `build.gradle` sets `uploadFile = tasks.jar` because this Loom setup has no `remapJar` task; don't remove it, Minotaur can't auto-detect the jar without it.

## Architecture

Entry point `Combatpersistence` holds three static singletons: `config` (CombatConfig), `tracker` (CombatTracker), `authManager` (AuthManager). Event wiring lives in `CombatEvents.register()`; packet-level enforcement lives in the mixins (`combatpersistence.mixins.json`).

**Combat-log flow:** `ALLOW_DAMAGE` tags both players → on DISCONNECT while tagged, the full inventory (cursor stack and transient crafting slots included — never open-container slots, that would steal chest contents) is snapshotted into the tracker **and saved to disk synchronously before the live inventory is cleared** → `CombatNPC.spawn` creates a Mannequin subclass. The NPC entity is deliberately **never persisted** (`shouldBeSaved() = false`); the tracker JSON is the durable copy, and stale mannequins are discarded on ENTITY_LOAD and by the NPC's own tick. NPC death (via `CombatDeathMixin` on `LivingEntity.die`) drops the items and marks an offline death; the player is killed on next join.

**Auth flow:** `AuthManager.authRequired(server)` is the single gate — auth applies only on offline-mode servers with `enableAuth` + `forceAuthInOfflineMode`. `isAuthenticated()` returns true whenever auth doesn't apply, and **every** check (events and mixins) must go through `isAuthenticated`, never the raw authenticated set — gating only the join flow while other checks consult the set is exactly the bug that froze online-mode servers. Pre-auth players get their inventory stashed, are teleported to a lobby and re-confined every tick, and have interaction/chat/command/click packets cancelled — including the **signed** chat-command packet path (`handleSignedChatCommand`), which bypasses the plain one.

**Durability invariants** (cross-file, easy to break):
- Every data-file write goes through `DiskIO` (single writer thread). `DiskIO.sync` enqueues and joins, so a sync save can never be overwritten by an earlier queued stale snapshot. Don't replace with `CompletableFuture.runAsync` on the common pool.
- Ordering policy is prefer-loss-of-nothing: persist the snapshot synchronously **before** destroying the live copy (stash, NPC spawn), and hand items back **before** removing the durable entry (restores). A crash may produce a dupe window, never item loss.
- `CombatDeathMixin` injects at HEAD of `die()`, which is **before** vanilla's re-entry guard — death handling runs on double-`die()` calls, so drop paths must self-clear (e.g. `storedInventory.clear()` after dropping).
- Corrupt/empty JSON (config or data files) must never crash startup and must not be overwritten with defaults — load falls back, file is left for the admin.

**Mixins & mappings:** MC 26.x Fabric runs on Mojang mappings at runtime, but still use accessor mixins (`PlayerAccessor` for `Player.gameProfile`), not field-name reflection. `ServerGamePacketListenerImpl` mixins only class-load when a player connects — a clean server boot does *not* prove them; the other mixin targets load at boot.

**Skins:** `SkinManager` fetches from the Mojang API off-thread (dedicated executor, never the common pool), with strict identifier validation (SSRF guard — keep it), TTL + negative caching, and a per-player `/skin` cooldown. Applying a skin swaps the GameProfile and re-sends tracker/tab-list packets via `refreshPlayer` — only to tracking players, broadcasting to the whole dimension creates ghost clones.

**Threading:** BCrypt hashing runs async (see `AuthCommands`); anything touching the world/player must hop back via `server.execute()` and re-check `player.connection != null && !player.isRemoved()`. `AuthManager.onAuthenticated` must run on the server thread.

## Config & messages

All player-facing strings and tunables live in `CombatConfig` (gson, defaults from field initializers, file `config/combatpersistence.json`). When adding a feature, add its message/tunable there and document it in the README + MODRINTH.md tables — both files carry full config tables that are kept in sync with the code.
