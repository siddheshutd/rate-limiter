#!/usr/bin/env bash
# Stage 3: a six-node cluster (3 masters + 3 replicas) on one host, from this repo's own template.
#
#   ./scripts/redis-cluster.sh start      generate per-node configs and start 7100-7105
#   ./scripts/redis-cluster.sh create     assign the 16384 slots and pair each replica with a master
#   ./scripts/redis-cluster.sh status     cluster_state, slot coverage, per-node roles
#   ./scripts/redis-cluster.sh keyslot <key>   which slot, and therefore which node, a bucket lands on
#
# Ports start at 7100, not the usual 7000: on macOS ControlCenter already listens on 7000.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT/redis/redis-cluster-node.conf"
DATA="$ROOT/.redis-cluster"
PORTS=(${RL_CLUSTER_PORTS:-7100 7101 7102 7103 7104 7105})
HOST=127.0.0.1
SEED="$HOST:${PORTS[0]}"

cmd="${1:-status}"
shift || true

nodes_up() {
  local up=0
  for port in "${PORTS[@]}"; do
    redis-cli -h "$HOST" -p "$port" ping >/dev/null 2>&1 && up=$((up + 1))
  done
  echo "$up"
}

formed() {
  redis-cli -h "$HOST" -p "${PORTS[0]}" cluster info 2>/dev/null | grep -q 'cluster_state:ok'
}

case "$cmd" in
  start)
    [ -f "$TEMPLATE" ] || { echo "missing $TEMPLATE" >&2; exit 1; }
    for port in "${PORTS[@]}"; do
      dir="$DATA/$port"
      mkdir -p "$dir"
      if [ ! -f "$dir/redis.conf" ] || [ "$TEMPLATE" -nt "$dir/redis.conf" ]; then
        sed "s/__PORT__/$port/" "$TEMPLATE" > "$dir/redis.conf"
      fi
      pidfile="$dir/redis.pid"
      if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "node :$port already running (pid $(cat "$pidfile"))"
        continue
      fi
      # Absolute --dir/--pidfile/--logfile override the template's relative ones, so each node owns one
      # directory. Two nodes sharing a dir would share an appendonlydir and corrupt each other's AOF.
      redis-server "$dir/redis.conf" --port "$port" --dir "$dir" --pidfile "$pidfile" \
        --logfile "$dir/redis.log" --daemonize yes
    done
    for _ in $(seq 1 40); do [ "$(nodes_up)" -eq "${#PORTS[@]}" ] && break; sleep 0.2; done
    echo "$(nodes_up)/${#PORTS[@]} nodes answering ping on ${PORTS[*]}"
    if formed; then
      echo "cluster is already formed. status: $0 status"
    else
      echo "nodes are up but unassigned (cluster_state:fail is correct here)."
      echo "next: $0 create"
    fi
    ;;
  create)
    [ "$(nodes_up)" -eq "${#PORTS[@]}" ] || { echo "start the nodes first: $0 start" >&2; exit 1; }
    if formed; then
      echo "already formed; use '$0 check' to verify and '$0 nodes' for the layout"
      exit 0
    fi
    # No --cluster-yes here: the whole lesson of this step is the assignment it prints before asking.
    # Read which master takes which slot range and which replica follows which master, then type yes.
    redis-cli --cluster create "${PORTS[@]/#/$HOST:}" --cluster-replicas 1
    echo
    # cluster_state goes ok only after gossip settles; printing status immediately shows a misleading
    # fail, so wait for the nodes to agree rather than reporting the intermediate state as a problem.
    for _ in $(seq 1 30); do formed && break; sleep 0.3; done
    $0 status
    ;;
  stop)
    for port in "${PORTS[@]}"; do
      pidfile="$DATA/$port/redis.pid"
      [ -f "$pidfile" ] || continue
      kill "$(cat "$pidfile")" 2>/dev/null && echo "stopped :$port"
      rm -f "$pidfile"
    done
    # Wait for the ports to close. A demo that measures a fail-over window while the old master is still
    # answering commands measures nothing, and stage 1 already got fooled once that way.
    for _ in $(seq 1 50); do [ "$(nodes_up)" -eq 0 ] && break; sleep 0.1; done
    echo "nodes answering ping now: $(nodes_up)"
    ;;
  status)
    echo "nodes answering: $(nodes_up)/${#PORTS[@]}"
    redis-cli -h "$HOST" -p "${PORTS[0]}" cluster info 2>/dev/null \
      | grep -E '^cluster_(state|enabled|slots_assigned|slots_ok|size|my_master_uuid):' \
      | tr -d '\r'
    echo "--- layout (id prefix / role / port / slots served):"
    # Field 7 of `cluster nodes` is the link state (connected/fail?); slot ranges start at field 8.
    redis-cli -h "$HOST" -p "${PORTS[0]}" cluster nodes 2>/dev/null \
      | awk '{split($2,a,"@"); split(a[1],b,":"); role=($3 ~ /master/) ? "master" : "replica";
              slots=""; for (i=8;i<=NF;i++) if ($i !~ /^[(]/ && $i !~ /^(connected|fail\?|handshake|wait_sync|none|self)$/) slots = slots " " $i;
              printf "  %-8s %-7s :%-6s %s\n", substr($1,1,8), role, b[2], slots}'
    ;;
  check)
    redis-cli --cluster check "$SEED"
    ;;
  nodes)
    redis-cli -h "$HOST" -p "${PORTS[0]}" cluster shards | tr -d '\r'
    ;;
  keyslot)
    key="${1:-}"
    [ -n "$key" ] || { echo "usage: $0 keyslot <key>   e.g. 'rl:{user:alice:feed_free}'" >&2; exit 2; }
    slot=$(redis-cli -h "$HOST" -p "${PORTS[0]}" cluster keyslot "$key" | tr -d '\r')
    owner=$(redis-cli -h "$HOST" -p "${PORTS[0]}" cluster nodes | awk -v s="$slot" '
      /master/ { for (i = 7; i <= NF; i++) { if ($i ~ /^[0-9]+-[0-9]+$/) { split($i, r, "-");
        if (s >= r[1] && s <= r[2]) { split($2, a, "@"); split(a[1], b, ":"); print b[2] } } } }')
    echo "$key -> slot $slot -> node :${owner:-?}"
    ;;
  flush)
    redis-cli --cluster call "$SEED" flushall >/dev/null 2>&1
    echo "flushed every node in the cluster"
    ;;
  cli)
    port="${1:-${PORTS[0]}}"
    exec redis-cli -c -h "$HOST" -p "$port" "${@:2}"
    ;;
  cleanup)
    if [ "${1:-}" != "--force" ]; then
      echo "refusing: this deletes $DATA (every node's config, AOF and log). Re-run with --force." >&2
      exit 2
    fi
    $0 stop
    rm -rf "$DATA"
    echo "removed $DATA"
    ;;
  *)
    echo "usage: $0 {start|create|stop|status|check|nodes|keyslot <key>|flush|cli [port] [args]|cleanup --force}" >&2
    exit 2
    ;;
esac
