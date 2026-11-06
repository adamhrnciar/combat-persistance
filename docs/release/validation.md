# Release validation

Target: Combat Persistence `1.10.0`, Minecraft `26.3`, Java `25`, Fabric Loader `0.20.1`, Fabric API `0.165.2+26.3`.

The release was built from the reviewed source with the pinned toolchain. Final packaged JAR SHA-256: `738c8695234ef324fe8a643dd371c276ccc201585be17b963f74a0f5dc519212`. All **28 JUnit tests pass**. The full live matrix and final authentication checks are distinguished below. Final live tests and publication verification are recorded below; an unchecked item remains incomplete.

## Build and artifact

- [x] Build the final source with the pinned toolchain and run the regression suite.
- [x] Check every mixin against the target Minecraft version.
- [x] Confirm the JAR's `fabric.mod.json` version, Minecraft range, Java requirement and server environment.
- [x] Confirm BCrypt is bundled. Publication must declare Fabric API as required; verify the public API after upload.
- [x] Record the final JAR path, SHA-256 and build/test logs.

## Disposable server

- [x] Reach full world startup with the production JAR and required Fabric API.
- [x] Join using unmodified Minecraft 26.3 protocol without a client mod. The probe is not a graphical client.
- [x] Reject duplicate UUID/name connections without disconnecting the owner.
- [x] Reject stale password results after rapid reconnect or operator reset.
- [x] Verify login hiding from the first play packets and through respawn/dead-player login.
- [x] Verify combat logout with crafting ingredients/result preview, cursor, armor and offhand.
- [x] Kill an offline NPC and verify one loot release plus death on rejoin.
- [x] Restart and crash-test recovery; check item counts and document loss windows.
- [x] Test full-inventory overflow, authentication lockout/reset and failed storage writes.
- [x] Test authentication timeout expiry on the final candidate. A disposable one-second limit disconnected the anonymous client with the configured login-timeout message.

## Publication

- [x] Attach the verified JAR only to tested Minecraft versions.
- [x] Upload release notes from `CHANGELOG.md` for this version.
- [x] Apply metadata and the `MODRINTH.md` listing body.
- [x] Confirm published dependency, environment, artifact hash, download and links.
- [x] Update the security review with actual final results and remaining limits.

## Evidence and scope

- Production JAR: `build/libs/combatpersistence-1.10.0.jar`.
- Complete corresponding source: `build/libs/combatpersistence-1.10.0-sources.jar`; includes build scripts, wrapper, tests, license and validation tools.
- JUnit XML/HTML: `build/test-results/test/` and `build/reports/tests/test/`.
- Live command/probe evidence: `build/security-live/`, including `probe.jsonl`, `final-candidate-console.log` and `reports/`.
- The separate validation mod is installed only in the ignored disposable `run/` directory. It is excluded from the primary JAR.
- Vanilla 26.3, required Fabric API and localhost protocol clients were tested. Graphical rendering, additional gameplay mods, proxies and optional skin HTTP requests were not live-tested.
- Initial privacy checks cover inventory, position and last-death-location handling; this is not a claim that every vanilla metadata field is hidden.
- Crash recovery is not a transaction across recovery JSON, player files and world chunks. Dropped loot can still be lost before its chunk is saved.

## Live matrix results

The complete matrix ran on menu/inventory candidate `cebcf3f43ec9cc93848a1ea4180bc75d2a3414117f5850473cea874cdd710d1b`:

| Scenario | Result |
| --- | --- |
| Duplicate name, alternate case and concurrent configuration | Incoming duplicates refused; one player reaches play; owner remains connected. |
| Pre-auth movement, namespaced command and malicious teleport acknowledgement | Empty inventory and lobby position remain authoritative. |
| Crafting, cursor, equipment and anvil input recovery | Seven inventory diamonds, three cursor apples, one crafting log and equipment restore once; anvil adds exactly two diamonds and three iron ingots; no preview planks. |
| NPC death called twice | Exactly seven world diamonds; pending death applies on authenticated rejoin; respawn retains authentication. |
| Legitimate tagged death and reconnect while dead | Tag clears, no NPC appears, dead player can log in and respawn. |
| Full-inventory overflow | 2,304 cobblestone and equipment retained; exactly seven diamonds and one log released as overflow after snapshot consumption. |
| Forced process kill after durable combat logout | Snapshot survives restart; exactly seven diamonds, three apples, one log and equipment recover. |
| Blocked player-data target during recovery | Authentication refused; snapshot retained. Restoring storage allows exact recovery. |
| Disconnect during registration or login hashing | Replacement connection remains unauthenticated; abandoned registration does not reserve an account. |
| Wrong-password lockout across reconnect | Attempts kick, locked identity cannot join, correct login works after expiry. |
| Operator reset | Account is removed durably and requires coordinated registration. |

The final production-code change after this matrix only makes failed operator resets retain the account and existing session. Its dedicated regression passed in the final 28-test suite. A binary comparison confirms that only `AuthManager.class` and its four anonymous Gson type-token classes differ from the full-matrix JAR. Inventory, packet and NPC classes remain byte-for-byte identical. The narrow live rerun is recorded below.

## Final authentication rerun

Final artifact `738c8695234ef324fe8a643dd371c276ccc201585be17b963f74a0f5dc519212` passed:

- Anonymous login timeout with a one-second disposable configuration.
- Valid owner login and refused duplicate join.
- A nonempty directory blocking the account store: operator reset reported failure, registration stayed intact, the original committed account stayed unchanged, and the owner kept its authenticated session.
- After removing the injected storage fault, normal reset durably removed the account and disconnected its session. Rejoin required registration.
- New registration and another real combat logout/rejoin restored exactly seven diamonds, three apples, one crafting log and equipment, with no recipe preview loot.

The final suite passed **28 tests**. The server and protocol probes were stopped after validation. Detailed local evidence is preserved in `build/security-live/reports/validation-summary.md`, `final-reset-timeout.log`, `final-reset-auth.log` and `network-events.jsonl`.

## Publication

Published [Combat Persistence 1.10.0](https://modrinth.com/mod/combat-persistence/version/YhaLEZdw) for Minecraft 26.3 and Fabric. The public API reports `listed`, `dedicated_server_only`, the required Fabric API dependency and both primary/source artifacts. Downloaded primary bytes match the local SHA-512. Listing body and summary match the reviewed files; project monetization remains enabled. The machine-readable [publication receipt](published.json) records download links and hashes.

The uploaded source JAR contains the source and build inputs used for the release. This receipt and final publication checkboxes were added afterward; production classes and build inputs did not change.
