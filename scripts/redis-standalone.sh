#!/usr/bin/env bash
# Stage 0: one node, run from this repo's own config, so the settings above are ours to change.
# Deliberately NOT `brew services start redis`: that runs Homebrew's /opt/homebrew/etc/redis.conf as
# a background daemon, and we would be learning about a configuration file we never edited.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="$ROOT/redis/redis-standalone.conf"
DATA="$ROOT/.redis-standalone"
LOG="$DATA/redis.log"
PIDFILE="$DATA/redis.pid"
PORT="${RL_REDIS_PORT:-6379}"

cmd="${1:-status}"

case "$cmd" in
  start)
    mkdir -p "$DATA"
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "already running (pid $(cat "$PIDFILE")) on :$PORT"
      exit 0
    fi
    # The trailing --dir overrides the config's `dir .`, so AOF land in .redis-standalone/ and the
    # repo stays clean. Command line always wins over the file.
    redis-server "$CONF" --port "$PORT" --dir "$DATA" --pidfile "$PIDFILE" --logfile "$LOG" --daemonize yes
    sleep 0.4
    redis-cli -h 127.0.0.1 -p "$PORT" ping
    echo "redis up on :$PORT (log: $LOG)"
    ;;
  stop)
    if [ -f "$PIDFILE" ]; then
      kill "$(cat "$PIDFILE")" && rm -f "$PIDFILE"
      # Wait for the port to actually close. Redis finishes in-flight commands and flushes the AOF, so a
      # curl issued immediately after the kill still gets a real answer — and a demo that measures a
      # fail-open path that never ran is worse than one that fails.
      for _ in $(seq 1 50); do
        redis-cli -h 127.0.0.1 -p "$PORT" ping >/dev/null 2>&1 || break
        sleep 0.1
      done
      echo "stopped (ping now: $(redis-cli -h 127.0.0.1 -p "$PORT" ping 2>&1))"
    else
      echo "no pidfile; nothing to stop"
    fi
    ;;
  status)
    redis-cli -h 127.0.0.1 -p "$PORT" ping 2>/dev/null \
      && redis-cli -h 127.0.0.1 -p "$PORT" info server persistence memory \
         | grep -E '^(redis_version|run_id|tcp_port|configured_.*|aof_enabled|rdb_last_bgsave_status|maxmemory_human|maxmemory_policy):' \
      || echo "not running (start with: $0 start)"
    ;;
  cli)
    exec redis-cli -h 127.0.0.1 -p "$PORT" "${@:2}"
    ;;
  flush)
    redis-cli -h 127.0.0.1 -p "$PORT" flushall
    echo "all buckets and rules dropped (they will be recreated on the next request)"
    ;;
  *)
    echo "usage: $0 {start|stop|status|cli [args]|flush}" >&2
    exit 2
    ;;
esac
