---
name: run-combatpersistence
description: Build, run, and drive the Combat Persistence Fabric mod in a live Minecraft dev server. Use when asked to run the mod, start the server, smoke-test a change, send server console commands, or verify the mod loads (mixins, config, commands) after an edit.
---

# Run: Combat Persistence (Fabric server mod)

This is a server-side Minecraft Fabric mod (MC 26.2, Java 25). There is no
GUI and no test suite — the way to see a change working is to boot the Fabric
dev server and poke it through its console. The committed driver at
`.claude/skills/run-combatpersistence/driver.sh` does that: it launches
`gradlew runServer` headless with stdin wired to a FIFO so you can send
console commands and grep the log.

All paths below are relative to the repo root.

## Prerequisites

- Java 25 on PATH (`java -version` → `openjdk 25.x`). Nothing else — the
  server is fully headless, no xvfb/tmux needed.
- First run needs network for the Gradle wrapper, Loom, and Minecraft jars
  (~several minutes). Everything is cached afterwards.

## Build

```bash
bash gradlew build
# → build/libs/combatpersistence-<version>.jar
```

Note `bash gradlew`, not `./gradlew` — the wrapper is not executable in this
repo. A `(0.4) is not valid semver for dependency org.mindrot:jbcrypt` line
in the output is a harmless Loom warning.

## Run (agent path) — the driver

Quickest full check after a change (boots the server, asserts mod init + no
mixin errors + a mod command responds on the console + clean shutdown writes
all five data files under `run/config/`):

```bash
.claude/skills/run-combatpersistence/driver.sh smoke
# → driver: SMOKE PASS
```

Interactive session for deeper poking:

```bash
.claude/skills/run-combatpersistence/driver.sh start          # boots, blocks until "server ready"
.claude/skills/run-combatpersistence/driver.sh send help      # any server console command
.claude/skills/run-combatpersistence/driver.sh send authreset Nobody
.claude/skills/run-combatpersistence/driver.sh await 'Nobody is not registered' 10
.claude/skills/run-combatpersistence/driver.sh log 30         # tail the server log
.claude/skills/run-combatpersistence/driver.sh stop           # graceful stop (fires the mod's shutdown flush)
.claude/skills/run-combatpersistence/driver.sh clean          # wipe run/ (world + mod config) for a from-scratch boot
```

- Server log: `run/driver/server.log`. Mod data files: `run/config/*.json`.
- `start` waits up to 300 s (override: `READY_TIMEOUT=600 ... start`); warm
  boots are ~10–15 s.
- Useful log lines to assert on: `Initializing Combat Persistence Mod`,
  `Combat tag duration is set to`, `Saving combat and auth data synchronously`
  (shutdown hook).

## Run (human path)

`bash gradlew runServer` in a terminal gives the same server with an
interactive console; Ctrl-C or `stop` to quit. Useless from a non-interactive
shell — stdin is why the driver exists.

## Test

There is no unit test suite (`gradlew test` is NO-SOURCE). `driver.sh smoke`
is the executable check. CI (`.github/workflows/build.yml`) runs
`gradlew build` on PRs; pushing a `v*` tag additionally creates a GitHub
release and publishes to Modrinth (needs the `MODRINTH_TOKEN` repo secret).

## Gotchas

- **Console can't be a player.** `/register`, `/login`, `/skin` and the
  combat-tag flow need a `ServerPlayer`; from the console you can exercise
  command registration (`help` lists `/authreset`, `/skin`, …), operator
  commands (`authreset <name>`), config generation, and the
  lifecycle hooks. There is no headless MC client for this Minecraft
  version, so full player flows need a real client joining `localhost:25565`.
- **Mod messages carry raw `§` color codes in the log** — grep for the text
  (`is not registered`), not the whole line.
- **First-boot ERROR is normal:** `Failed to load properties from file:
  server.properties` appears once, then vanilla generates defaults and boots
  to `Done (…s)!`. Don't treat it as a failure.
- **One instance at a time** — the server binds 25565; the driver refuses to
  double-start via its pidfile.
- **`Server empty for 60 seconds, pausing`** shows up when no player is
  connected; console commands still work while paused.
- **Mixin failures appear at class-load time, not boot time** for
  lazily-loaded targets (`ServerGamePacketListenerImpl` loads when a player
  connects). A clean boot proves `Player`/`LivingEntity`/`ItemEntity`/
  `EnderChestBlock` mixins; smoke's log grep would only catch the rest on a
  player join.
- **`run/` is gitignored** and safe to delete; the driver writes
  `run/eula.txt` for you.

## Troubleshooting

- `Permission denied` running `./gradlew` → use `bash gradlew` (or
  `chmod +x gradlew`).
- `driver: server already running (pid …)` → `driver.sh stop` (or the pid is
  stale after a crash: `rm run/driver/*.pid`).
- `start` times out on a cold cache → re-run with `READY_TIMEOUT=900`; watch
  progress via `tail -f run/driver/server.log` (Gradle downloads before any
  log lines appear).
- Server boots but mod lines missing → you're running a stale build;
  `runServer` compiles the mod itself, so check the top of
  `run/driver/server.log` for compile errors.
