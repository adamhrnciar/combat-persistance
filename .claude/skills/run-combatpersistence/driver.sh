#!/usr/bin/env bash
# Driver for the Combat Persistence Fabric dev server.
#
# Launches `gradlew runServer` headless with its console wired to a FIFO,
# so an agent can send server console commands and assert on the log.
# No tmux/xvfb needed — the Minecraft dedicated server is fully headless.
#
# Usage (from the repo root):
#   .claude/skills/run-combatpersistence/driver.sh start        # boot, wait until ready
#   .claude/skills/run-combatpersistence/driver.sh send <cmd..> # run a console command
#   .claude/skills/run-combatpersistence/driver.sh await <re>   # wait for regex in log (30s)
#   .claude/skills/run-combatpersistence/driver.sh log [n]      # tail the server log
#   .claude/skills/run-combatpersistence/driver.sh stop         # graceful stop
#   .claude/skills/run-combatpersistence/driver.sh smoke        # full start->poke->stop check
#   .claude/skills/run-combatpersistence/driver.sh clean        # wipe run/ state (world, config)
set -u

cd "$(dirname "$0")/../../.." || exit 1   # repo root
DRIVER_DIR=run/driver
LOG=$DRIVER_DIR/server.log
FIFO=$DRIVER_DIR/console.in
GRADLE_PID=$DRIVER_DIR/gradle.pid
HOLDER_PID=$DRIVER_DIR/holder.pid
READY_TIMEOUT=${READY_TIMEOUT:-300}   # first boot compiles + downloads; later boots take ~15s

alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

fail() { echo "driver: $*" >&2; exit 1; }

cmd_start() {
    alive "$GRADLE_PID" && fail "server already running (pid $(cat "$GRADLE_PID")); use stop first"
    mkdir -p run "$DRIVER_DIR"
    echo "eula=true" > run/eula.txt
    rm -f "$FIFO"; mkfifo "$FIFO"
    # Holder keeps one writer on the FIFO forever so the server's stdin
    # never sees EOF between `send` invocations.
    ( exec sleep infinity > "$FIFO" ) & echo $! > "$HOLDER_PID"
    ( bash gradlew runServer --console=plain < "$FIFO" > "$LOG" 2>&1 ) & echo $! > "$GRADLE_PID"
    echo "driver: waiting for server (up to ${READY_TIMEOUT}s)..."
    local waited=0
    while [ "$waited" -lt "$READY_TIMEOUT" ]; do
        grep -q 'Done (' "$LOG" 2>/dev/null && { echo "driver: server ready"; return 0; }
        alive "$GRADLE_PID" || { tail -20 "$LOG"; fail "server process died during startup (log above)"; }
        sleep 2; waited=$((waited + 2))
    done
    tail -20 "$LOG"; fail "server not ready after ${READY_TIMEOUT}s (log above)"
}

cmd_send() {
    alive "$GRADLE_PID" || fail "server not running"
    printf '%s\n' "$*" > "$FIFO"
}

cmd_await() {
    local pattern=$1 timeout=${2:-30} waited=0
    while [ "$waited" -lt "$timeout" ]; do
        grep -qE "$pattern" "$LOG" && return 0
        sleep 1; waited=$((waited + 1))
    done
    echo "driver: pattern not found: $pattern" >&2
    tail -10 "$LOG" >&2
    return 1
}

cmd_log() { tail -n "${1:-30}" "$LOG"; }

cmd_stop() {
    if alive "$GRADLE_PID"; then
        printf 'stop\n' > "$FIFO"
        local waited=0
        while [ "$waited" -lt 60 ] && alive "$GRADLE_PID"; do sleep 2; waited=$((waited + 2)); done
        alive "$GRADLE_PID" && { kill "$(cat "$GRADLE_PID")"; echo "driver: had to kill server" >&2; }
    fi
    [ -f "$HOLDER_PID" ] && kill "$(cat "$HOLDER_PID")" 2>/dev/null
    rm -f "$GRADLE_PID" "$HOLDER_PID" "$FIFO"
    echo "driver: stopped"
}

cmd_clean() {
    alive "$GRADLE_PID" && fail "server running; stop first"
    rm -rf run
    echo "driver: run/ wiped"
}

cmd_smoke() {
    set -e
    trap 'cmd_stop >/dev/null 2>&1 || true' EXIT
    cmd_start

    echo "driver: [1/4] mod initialized"
    grep -q 'Initializing Combat Persistence Mod' "$LOG" || fail "mod init line missing"
    grep -q 'Combat tag duration is set to' "$LOG" || fail "config log line missing"

    echo "driver: [2/4] no mixin errors"
    grep -iE 'MixinApplyError|InvalidInjectionException|Mixin apply failed' "$LOG" && fail "mixin errors in log"

    echo "driver: [3/4] mod command responds on console"
    cmd_send "authreset SmokeTestPlayer"
    cmd_await 'SmokeTestPlayer is not registered' 15

    echo "driver: [4/4] clean shutdown writes mod data"
    cmd_stop
    grep -q 'Saving combat and auth data synchronously' "$LOG" || fail "shutdown flush line missing"
    for f in combatpersistence.json combat_persistence_data.json auth_data.json \
             pending_inventories.json pending_locations.json; do
        [ -f "run/config/$f" ] || fail "expected run/config/$f after shutdown"
    done
    trap - EXIT
    echo "driver: SMOKE PASS"
}

case "${1:-}" in
    start) cmd_start ;;
    send)  shift; cmd_send "$@" ;;
    await) shift; cmd_await "$@" ;;
    log)   shift || true; cmd_log "${1:-30}" ;;
    stop)  cmd_stop ;;
    smoke) cmd_smoke ;;
    clean) cmd_clean ;;
    *) grep '^#   ' "$0" | sed 's/^#   //'; exit 2 ;;
esac
