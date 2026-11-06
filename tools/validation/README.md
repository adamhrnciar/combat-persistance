# Disposable server validation

These tools are test instrumentation. Never install the validation mod on a real
server or include it in a Modrinth artifact. Its console-only commands alter
inventories, kill players and NPCs, and create test combat tags.

Run all validation on a disposable localhost server with offline authentication
enabled, IP autologin disabled, and inventory and coordinate hiding enabled.

1. Extract the official server JAR and run `protocol_ids.py server.jar` to create
   `protocol-ids.json`. This reads actual protocol registration order without
   starting another JVM. Review generated IDs after a Minecraft update.
2. Compile `LiveValidationMod.java` against the built mod, Minecraft, Fabric API
   and Fabric Loader. Package its classes and this directory's `fabric.mod.json`
   into a separate JAR. Put both release and validation JARs into test `mods/`.
3. Start the server, then run `node tools/validation/raw_probe.mjs protocol-ids.json
   25585 777` for Minecraft 26.3. Send one JSON control object per stdin line.
4. Connect with `{"action":"connect","id":"owner","name":"ReleaseOwner"}`.
   Confirm the first `position` reports the lobby, and first `inventory` is empty.
   Send `/register` through `{"action":"command","id":"owner","text":"register disposable-password disposable-password"}`.
5. From the server console, run `securityprobe seed ReleaseOwner`, then
   `securityprobe tag ReleaseOwner`. Disconnect with
   `{"action":"disconnect","id":"owner"}`. Confirm snapshot and NPC exist.
6. Reconnect under a fresh control ID. Confirm the first inventory stays empty,
   log in, and run `securityprobe assert ReleaseOwner 7` from the console. This
   checks diamonds, equipment, cursor, ingredients and absence of owned preview
   planks. Repeat after a clean restart and a deliberate process kill.
7. While the owner stays connected, connect a second client under the same
   player name and then alternate letter casing. Both must disconnect while
   the owner remains connected. Send a pre-auth movement packet and a vanilla
   command; server-side `securityprobe status` must retain lobby coordinates and
   unauthenticated state.
   Also connect two clients for an offline identity with `"holdConfiguration":true`.
   Wait for both `configuration-ready` events, then send
   `{"action":"release","id":"first"}` and the same action for the second.
   Exactly one client may join play; the second must be rejected.
   A connection with `"ackPosition":[300,65,300]` forges its first teleport
   acknowledgement. Authentication must keep the player at the lobby.
8. Repeat tagged disconnect, then run `securityprobe killnpc ReleaseOwner 7`.
   The harness invokes death twice and requires exactly seven world diamonds
   and a pending death marker. Rejoin, authenticate, request respawn with
   `{"action":"respawn","id":"new-owner"}`, then assert zero diamonds.
9. Exercise wrong-password lockout, registration disconnect while hashing,
   login disconnect while hashing, and reconnect while dead. Verify a new
   connection never inherits authentication. Also inject storage failure and
   require a refused restore with the authoritative file retained.
   A command with `"disconnectAfterMs":70` closes the connection while password
   work may still run. Use deterministic unit tests to control completion timing.
10. Tag an authenticated player, run `securityprobe kill ReleaseOwner`, request
    respawn, and run `securityprobe assertnotag ReleaseOwner`. Disconnect and
    run `securityprobe absentnpc ReleaseOwner`. Legitimate player death must
    clear the combat tag and must not create a new NPC after respawn.
11. Clear existing item entities on the disposable world, then run
    `securityprobe seedoverflow ReleaseOwner`. Tag, disconnect, reconnect and
    authenticate. Run `securityprobe assertoverflow ReleaseOwner`. The main
    inventory must retain 2,304 cobblestone, armor and offhand; overflow must
    contain exactly seven diamonds and one log, with no recipe preview planks.
12. Run `securityprobe seedanvil ReleaseOwner`, tag, disconnect, reconnect and
    authenticate. `securityprobe assertanvil ReleaseOwner` must find the original
    seven diamonds plus two anvil-input diamonds, three anvil-input iron ingots,
    cursor apples, crafting ingredients and equipment. No output preview may
    become an owned item.
13. Set a one-second authentication timeout in the disposable configuration,
    restart, and connect without sending authentication commands. Require a
    login-timeout disconnect. Restore the normal timeout before other scenarios.

For a live account-reset fault, first authenticate an owner. Move the disposable
`auth_data.json` to a backup and replace the path with a nonempty directory.
`authreset` must fail while the owner remains authenticated and registered.
Restore the file, verify its original bytes, and repeat a successful reset.

`raw_probe.mjs` sends only enough protocol traffic to reach and maintain play.
It is not a full Minecraft client. It ignores chunk contents, counts empty
inventory packets, acknowledges teleports, and emits JSON status lines. Its
inventory parser deliberately stops at the first nonempty stack. Use harness
assertions for restored item counts. Keep the probe alive while validating;
send `{"action":"quit"}` to close every probe connection.

Record exact server logs and command/probe outputs. A prepared harness does not
mean the scenarios have passed.
