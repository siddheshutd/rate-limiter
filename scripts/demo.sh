#!/usr/bin/env bash
# Behaviour, measured instead of asserted. Each section prints the number and what it means.
#
#   ./scripts/demo.sh                          against the standalone node (stage 0-2)
#   RL_TOPOLOGY=cluster ./scripts/demo.sh     against the six-node cluster (stage 3)
#   RL_TOPOLOGY=cluster FAILOVER=1 ./scripts/demo.sh   + kill the master holding alice's slot
#   ./scripts/demo.sh --outage                also stops the store to show fail-open vs fail-closed
#
# The store (rmw vs lua) is whatever the gateways are configured with; section 3 reads it from
# /debug/rl and prints the matching conclusion, so the same script documents both stages.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW1="${GW1:-http://127.0.0.1:8080}"
GW2="${GW2:-http://127.0.0.1:8081}"
ALICE="tok_free_alice"
BOB="tok_paid_bob"
OUTAGE=0
[ "${1:-}" = "--outage" ] && OUTAGE=1

TOPOLOGY="${RL_TOPOLOGY:-standalone}"
CLUSTER_SEED_PORT="${RL_CLUSTER_SEED_PORT:-7100}"

# A bucket key lives on whichever master owns its slot, so reading one needs a cluster-aware client.
# `redis-cli -c` follows the MOVED redirect and answers with the real value; without it the reply is an
# error, which is the difference the stage-3 drills are about.
rcli() {
  if [ "$TOPOLOGY" = cluster ]; then
    redis-cli -c -h 127.0.0.1 -p "$CLUSTER_SEED_PORT" "$@"
  else
    redis-cli "$@"
  fi
}

rule() { # which gateway to alternate on
  if [ $((RANDOM % 2)) -eq 0 ]; then echo "$GW1"; else echo "$GW2"; fi
}

status() { # status <url> <token>
  # The \n matters: curl's -w does not add one, and codes written to files without a newline concatenate
  # into "200200429...", which makes every counter below read as 1.
  curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 -H "Authorization: Bearer $2" "$1"
}

section() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

count_codes() { sort | uniq -c | sed 's/^ *//'; }

admitted() { grep -c '^200$'; }

flush() {
  # In cluster mode FLUSHALL on one node clears only that node's keys, which would leave stale buckets
  # on the other two masters and make every count below unreadable. --cluster call runs it everywhere.
  if [ "$TOPOLOGY" = cluster ]; then
    redis-cli --cluster call "127.0.0.1:$CLUSTER_SEED_PORT" flushall > /dev/null 2>&1
  else
    redis-cli flushall > /dev/null
  fi
}

bucket_tokens() { rcli hget "rl:{user:alice:feed_free}" tokens; }

store_name() { curl -s --max-time 5 "$GW1/debug/rl" | python3 -c 'import json,sys; print(json.load(sys.stdin)["store"])'; }

# Does the gateway hear from Redis again? An allow with RateLimit-* headers proves a real decision; an
# allow without them is the fallback path. After the store has been restarted (or a shard has moved) the
# client needs a moment to relearn the topology, so anything that expects state has to wait for that.
waits_for_store() {
  for _ in $(seq 1 40); do
    [ "$(redis_answered "$ALICE")" = 1 ] && return 0
    sleep 0.5
  done
  return 1
}

redis_answered() { # 1 if a request to $GW1 got RateLimit-* headers back
  curl -s -D - -o /dev/null --max-time 5 -H "Authorization: Bearer $1" "$GW1/api/v1/feed" \
    | grep -ci 'ratelimit-limit' | tr -d '\r'
}

nice_num() { # Redis stores tokens as text with full float precision; print what a human can compare
  awk '{ printf "%.2f", $1 }'
}

# --------------------------------------------------------------------------- preflight
for url in "$GW1" "$GW2"; do
  curl -s -o /dev/null --max-time 2 "$url/healthz" || {
    echo "gateway $url is not up."
    echo "start the store and the edge first:"
    if [ "$TOPOLOGY" = cluster ]; then
      echo "  $ROOT/scripts/redis-cluster.sh start && $ROOT/scripts/redis-cluster.sh create"
      echo "  $ROOT/scripts/two-gateways.sh start 8080 8081   # with --spring.profiles.active=cluster"
    else
      echo "  $ROOT/scripts/redis-standalone.sh start"
      echo "  $ROOT/scripts/two-gateways.sh build && $ROOT/scripts/two-gateways.sh start"
    fi
    exit 1
  }
done

# --------------------------------------------------------------------------- 1. the limit itself
section "1. one user, sequential: burst of 10 then refusal (rule feed_free)"
flush
codes=()
for _ in $(seq 1 25); do codes+=("$(status "$GW1/api/v1/feed" "$ALICE")"); done
printf '25 requests -> %s\n' "$(printf '%s\n' "${codes[@]}" | count_codes | tr '\n' ' ')"
echo "capacity is 10 and refill is 1/s, so ~10 admitted is the limit working as designed."

echo
printf 'the rejection, verbatim:\n'
curl -s -D - -o /dev/null --max-time 5 -H "Authorization: Bearer $ALICE" "$GW1/api/v1/feed" \
  | grep -iE '^HTTP/|^RateLimit|^Retry-After' | sed 's/^/  /'
echo "  429 with Retry-After, not 201 and not a bare 429 - a rejection the client can act on."

# --------------------------------------------------------------------------- 2. shared state
section "2. two gateways, sequential: one bucket, not two"
flush
codes=()
for _ in $(seq 1 20); do codes+=("$(status "$(rule)/api/v1/feed" "$ALICE")"); done
admitted=$(printf '%s\n' "${codes[@]}" | admitted)
printf '20 requests split across %s and %s -> admitted=%s (capacity 10)\n' "$GW1" "$GW2" "$admitted"
echo "If each instance kept its own bucket this would be ~20. Shared state in Redis is why it is 10."

# --------------------------------------------------------------------------- 3. atomicity
STORE=$(store_name)
section "3. two gateways, CONCURRENT: 30 requests against a 10-token bucket (store=$STORE)"
flush
pids=(); out=$(mktemp -d)
for i in $(seq 1 30); do
  gw=$GW1; [ $((i % 2)) -eq 0 ] && gw=$GW2
  ( status "$gw/api/v1/feed" "$ALICE" > "$out/$i" ) &
  pids+=($!)
done
wait "${pids[@]}" 2>/dev/null
admitted=$(cat "$out"/* | admitted)
tokens_left=$(bucket_tokens)
[ -n "$tokens_left" ] && tokens_left=$(nice_num <<< "$tokens_left") || tokens_left="unknown"
printf '30 concurrent requests -> admitted=%s, bucket spent down to tokens=%s\n' \
  "$admitted" "$tokens_left"
if [ "$STORE" = lua ]; then
  echo "This is what the stage-1 store admitted 30 of. One EVALSHA per rule means the refill, the check,"
  echo "the decrement and the TTL happen in a single operation Redis will not interleave, so 30 requests"
  echo "compete for 10 tokens and 20 are refused. Re-run with --ratelimiter.store=rmw to see the 30."
else
  echo "Every reader saw ~10 tokens before any writer landed, so all 30 were admitted for ~10 tokens of"
  echo "state. Sizing that as '2 M ops/s (get + update per request)' is arithmetic about a race, not a"
  echo "capacity plan. Stage 2's Lua script closes it: refill, check, decrement and TTL in one operation"
  echo "Redis will not interleave."
fi
rm -rf "$out"

# --------------------------------------------------------------------------- 4. tiers and cost
section "4. tiers: paid and free are different buckets"
flush
curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $ALICE" "$GW1/api/v1/feed"
curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $BOB" "$GW1/api/v1/feed"
# The TTL is Redis' own expiry, not a hash field: `HGET key ttl` returns an empty string, so read it
# with PTTL. A bucket whose expiry you cannot see is a bucket that can be evicted without you noticing.
printf 'alice (free) bucket: tokens=%s ttl=%sms\n' \
  "$(nice_num <<< "$(rcli hget 'rl:{user:alice:feed_free}' tokens)")" \
  "$(rcli pttl 'rl:{user:alice:feed_free}')"
printf 'bob   (paid) bucket: tokens=%s ttl=%sms\n' \
  "$(nice_num <<< "$(rcli hget 'rl:{user:bob:feed_paid}' tokens)")" \
  "$(rcli pttl 'rl:{user:bob:feed_paid}')"
printf 'what bob sees in his header: '; curl -s -o /dev/null -D - --max-time 5 -H "Authorization: Bearer $BOB" "$GW1/api/v1/feed" | grep -i '^RateLimit-Limit' | tr -d '\r'
echo "Two separate keys, capacities 10 and 100, so free traffic cannot spend paid headroom. But bob's"
echo "header reads 20, not 100: the response always reports the BINDING rule, and for him that is the"
echo "IP backstop (anon_ip, capacity 20). Stacked limits mean the tightest one is what a client sees."

section "5. cost weighting: search costs 5 tokens, a feed read costs 1"
flush
codes=()
for _ in $(seq 1 5); do codes+=("$(status "$GW1/api/v1/search" "$ALICE")"); done
printf '5 searches (capacity 10, cost 5) -> %s\n' "$(printf '%s\n' "${codes[@]}" | count_codes | tr '\n' ' ')"
echo "Two pass, the rest are refused: an expensive endpoint is priced, not counted as one request."

section "6. global scope: every caller shares one key"
flush
codes=()
for _ in $(seq 1 60); do codes+=("$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$GW1/api/v1/report")"); done
printf '60 report requests -> %s\n' "$(printf '%s\n' "${codes[@]}" | count_codes | tr '\n' ' ')"
printf 'the one shared key  : rl:{global:all:report_global} tokens=%s\n' \
  "$(nice_num <<< "$(rcli hget 'rl:{global:all:report_global}' tokens)")"
printf 'alice+bob see limit : '; curl -s -o /dev/null -D - --max-time 5 "$GW1/api/v1/report" | grep -i '^RateLimit-Limit' | tr -d '\r'
echo "report_global has capacity 50 and is ONE key for the entire fleet - the hot key by construction:"
echo "per-user buckets shard naturally by key, a global count does not. The header reports 20 because"
echo "the anon_ip backstop is tighter for this caller - the awkward part of stacked limits, shown as a"
echo "number instead of described in prose."

section "7. identity cannot be asserted"
flush
before=$(curl -s --max-time 5 "$GW1/debug/rl" | python3 -c 'import json,sys; print(json.load(sys.stdin)["spoofAttempts"])')
curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $ALICE" -H "X-User-Id: bob" "$GW1/api/v1/feed"
after=$(curl -s --max-time 5 "$GW1/debug/rl" | python3 -c 'import json,sys; print(json.load(sys.stdin)["spoofAttempts"])')
printf 'spoofAttempts went %s -> %s, and the charge still landed on alice\n' "$before" "$after"
echo "A header naming a different user is ignored and counted. A client that could pick the identity it"
echo "is charged under is a client that is not rate limited."

# --------------------------------------------------------------------------- 8. what it cost
section "8. what the decision cost"
curl -s --max-time 5 "$GW1/debug/rl" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("  store            :", d["store"])
print("  round trips/rule :", d["roundTripsPerRule"])
print("  latency          :", json.dumps(d["storeLatency"], sort_keys=True))
print("  breaker          :", json.dumps(d["breaker"], sort_keys=True))
for rule, c in d["perRule"].items():
    if any(c.values()):
        print("  %-14s admitted=%s denied=%s fallbackAdmitted=%s fallbackDenied=%s unmetered=%s"
              % (rule, c["admitted"], c["denied"], c["fallbackAdmitted"], c["fallbackDenied"], c["unmetered"]))
'
echo "Read the p99 next to the round-trip count above. Each binding rule costs the request that many"
echo "client round trips, and two rules bind most requests in this config."
if [ "$STORE" = lua ]; then
  echo "One EVALSHA per rule: the HGET/HSET/PEXPIRE/TIME work still happens, but inside the script on the"
  echo "server, so it never crosses the network. Prove it with:"
  echo "  redis-cli info commandstats   # cmdstat_evalsha grows once per rule per request"
  echo "  redis-cli monitor             # one EVALSHA line per decision, six per request in stage 1"
else
  echo "Three commands per rule (HGETALL, HMSET, PEXPIRE) is the stage-1 store; stage 2 folds them into"
  echo "one EVALSHA, which is both the latency win and the atomicity win."
fi

# --------------------------------------------------------------------------- 9. outage
if [ "$OUTAGE" = "1" ]; then
  section "9. store unreachable: fail-open for reads, fail-closed for login"
  # Killing the whole store in both topologies: this section is about the gateway's fallback path, not
  # about which shard died. The partial-shard case (one master down, its replica promoting) is section 11.
  if [ "$TOPOLOGY" = cluster ]; then "$ROOT/scripts/redis-cluster.sh" stop; else "$ROOT/scripts/redis-standalone.sh" stop; fi
  printf 'feed  (fail-mode OPEN):   '; status "$GW1/api/v1/feed" "$ALICE"
  printf 'login (fail-mode CLOSED): '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 -X POST "$GW1/auth/login"
  printf 'feed body: '; curl -s --max-time 5 -H "Authorization: Bearer $ALICE" "$GW1/api/v1/feed"
  echo
  curl -s --max-time 5 "$GW1/debug/rl" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("  breaker :", json.dumps(d["breaker"], sort_keys=True))
print("  headers on a degraded allow are withheld; Retry-After on a deny is not.")
for rule in ("feed_free", "login_guard"):
    c = d["perRule"].get(rule)
    if c:
        print("  %-12s fallbackAdmitted=%s fallbackDenied=%s" % (rule, c["fallbackAdmitted"], c["fallbackDenied"]))
'
  if [ "$TOPOLOGY" = cluster ]; then "$ROOT/scripts/redis-cluster.sh" start >/dev/null; else "$ROOT/scripts/redis-standalone.sh" start >/dev/null; fi
  echo "Redis is back. The counters stay: an outage that is not counted is a bypass nobody saw."
fi

# --------------------------------------------------------------------------- 10. slot routing
if [ "$TOPOLOGY" = cluster ]; then
  section "10. where the buckets actually live"
  flush
  if waits_for_store; then
    echo "  the gateway is hearing from Redis again (a decision came back with RateLimit-* headers)"
  else
    echo "  WARNING: still failing open after 20 s — the client has not relearned the topology."
    echo "  Nothing below will have state to read; check '$ROOT/scripts/redis-cluster.sh status'."
  fi
  curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $ALICE" "$GW1/api/v1/feed"
  curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $BOB" "$GW1/api/v1/feed"
  curl -s -o /dev/null --max-time 5 "$GW1/api/v1/report"
  for key in 'rl:{user:alice:feed_free}' 'rl:{user:bob:feed_paid}' 'rl:{global:all:report_global}' 'rl:{ip:127.0.0.1:anon_ip}' 'rl:user:alice:feed_free'; do
    "$ROOT/scripts/redis-cluster.sh" keyslot "$key" | sed 's/^/  /'
  done
  echo "  ^ the last key is the same bucket without the hash tag: a different slot, because the rule id"
  echo "    now participates in the hash. With the tag, one principal's bucket is decided by"
  echo "    scope:subject:rule and nothing else."
  echo "  keys per master (read from the cluster's own view, not assumed from the port list):"
  redis-cli -h 127.0.0.1 -p "$CLUSTER_SEED_PORT" cluster nodes \
    | awk '$3 ~ /master/ {split($2,a,"@"); split(a[1],b,":"); print b[2]}' \
    | while read -r port; do
        printf '    :%-6s dbsize=%s\n' "$port" "$(redis-cli -h 127.0.0.1 -p "$port" dbsize | tr -d '\r')"
      done
  echo "Per-user buckets shard by key, so 100 M principals spread over the masters instead of pressing on"
  echo "one node. rl:{global:all:report_global} is the exception by construction: one key, one slot, one"
  echo "node taking the whole fleet's contention - which is what the hot-key section 6 means in cluster."
fi

# --------------------------------------------------------------------------- 11. one shard dies
if [ "${FAILOVER:-0}" = "1" ] && [ "$TOPOLOGY" = cluster ]; then
  section "11. the master holding alice's slot dies: promotion, and what the gateway did about it"
  flush
  waits_for_store || echo "  WARNING: gateway is not talking to Redis yet; this drill would prove nothing"
  curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $ALICE" "$GW1/api/v1/feed"
  owner=$("$ROOT/scripts/redis-cluster.sh" keyslot 'rl:{user:alice:feed_free}' | sed -n 's/.*node :\([0-9]*\).*/\1/p')
  echo "  alice's bucket is served by :$owner; her replica partner is a different node, and it holds a copy"
  for burst in 1 2 3 4 5; do
    if [ "$burst" = "2" ]; then
      kill "$(cat "$ROOT/.redis-cluster/$owner/redis.pid")" 2>/dev/null
      rm -f "$ROOT/.redis-cluster/$owner/redis.pid"
      echo "  killed :$owner"
    fi
    codes=()
    hdr=0
    for _ in $(seq 1 10); do codes+=("$(status "$GW1/api/v1/feed" "$ALICE")"); done
    # A deny or an allow WITH RateLimit-* headers means Redis answered. An allow WITHOUT them is the
    # gateway failing open, which is why the status code alone cannot tell you the limiter is working.
    hdr=$(redis_answered "$ALICE")
    state=$(redis-cli -h 127.0.0.1 -p "$CLUSTER_SEED_PORT" cluster info 2>/dev/null | grep cluster_state | tr -d '\r' | cut -d: -f2)
    printf '  burst %d -> %s cluster=%s redisAnswered=%s\n' "$burst" \
      "$(printf '%s\n' "${codes[@]}" | count_codes | tr '\n' ' ')" "$state" \
      "$([ "$hdr" -gt 0 ] && echo yes || echo 'NO - failing open')"
    sleep 4
  done
  curl -s --max-time 5 "$GW1/debug/rl" | python3 -c '
import json, sys
d = json.load(sys.stdin)
c = d["perRule"].get("feed_free", {})
print("  gateway side: breaker=%s fallbackAdmitted=%s denied=%s" % (d["breaker"], c.get("fallbackAdmitted"), c.get("denied")))
'
  printf '  alice'"'"'s bucket after the promotion: tokens=%s\n' \
    "$(nice_num <<< "$(rcli hget 'rl:{user:alice:feed_free}' tokens)")"
  echo "  The bucket is not back at capacity 10: a replica carries the keys, so the limit survives the"
  echo "  failover. What is lost is up to one second of spends (appendfsync everysec) and anything the"
  echo "  master had not yet propagated."
  echo "  who serves alice now:"
  "$ROOT/scripts/redis-cluster.sh" status | tail -7
  "$ROOT/scripts/redis-cluster.sh" start >/dev/null
  echo "  :$owner restarted. Run '$ROOT/scripts/redis-cluster.sh status' again: it returns as a REPLICA and"
  echo "  the promoted node keeps the slots. Promotion is never reversed on its own, so the node mix after"
  echo "  a failover is not the layout you planned - rebalance with '--cluster reshard' by hand."
fi

printf '\n\033[1mdemo complete\033[0m\n'
