# Rate Limiter Prototype

A working rate limiter you can run, break, and measure. Every request to the gateway is checked against a limit **before** it reaches your API. If the caller is over the limit, they get a `429 Too Many Requests` with clear instructions on when to retry.

> **Before you dive in:** read [ASSUMPTIONS.md](ASSUMPTIONS.md) for the requirements and design choices behind this prototype.

---

## Contents

1. [The short version](#the-short-version)
2. [Quick start](#quick-start)
3. [How it works](#how-it-works)
4. [The rules](#the-rules)
5. [The four stages](#the-four-stages)
6. [Hands-on experiments](#hands-on-experiments)
7. [The demo script](#the-demo-script)
8. [Project layout](#project-layout)
9. [Troubleshooting](#troubleshooting)
10. [What's next](#whats-next)
11. [Appendix: deeper notes](#appendix-deeper-notes)

---

## The short version

**What it is:** a gateway that rate-limits requests using a **token bucket**, with all counters stored in Redis so that multiple gateway servers share the same limits.

**What it's really about:** the interesting questions aren't "how do I count requests?" but:

- How cheap can each check be?
- How exact is it when many requests arrive at once?
- What happens when Redis is slow, sharded across machines, or fails over?

**What you get back as a caller:**

- `429` when you're over the limit
- `Retry-After` telling you when to come back
- `RateLimit-Limit`, `-Remaining`, `-Reset`, `-Policy` headers so well-behaved clients can pace themselves

**What's real and what's fake:**

| Component | Status |
|---|---|
| Redis | **Real.** One node (stages 0-2), then a six-node cluster (stage 3), including real redirects and real failover |
| Gateway | **Real.** Two separate JVMs, so "both instances share one limit" is something you can count |
| Origin API | **Fake.** `MockApiController` returns canned responses |
| Auth service | **Fake.** A token table in `application.yaml` (e.g. `tok_free_alice` → user `alice`, tier `FREE`) |

**Caveat on all numbers in this document:** they were measured on one laptop with Redis on localhost (about 1 ms per round trip). The *ratios* and *failure behaviors* carry over to real systems. The absolute latencies don't.

---

## Quick start

### Prerequisites

| Tool | Check | Notes |
|---|---|---|
| JDK 21+ | `java -version` | |
| Maven | `command -v mvn` | The build script also finds a cached Maven wrapper; override with `MVN=/path/to/mvn` |
| Redis | `redis-server --version` | Tested with 8.10.3. Install with `brew install redis`, but don't use `brew services`. The scripts start Redis themselves |
| `redis-cli`, `curl`, `python3` | | `python3` is only used to pretty-print JSON in the demo |

### Run on a single Redis (stages 0-2)

```bash
./scripts/redis-standalone.sh start     # Redis on :6379
./scripts/two-gateways.sh build         # compile (tests skipped, see below)
./scripts/two-gateways.sh start         # gateways on :8080 and :8081

# Make one request and look at the headers
curl -s -D - -o /dev/null \
  -H "Authorization: Bearer tok_free_alice" \
  http://127.0.0.1:8080/api/v1/feed

./scripts/demo.sh                       # run all measured demos
./scripts/demo.sh --outage              # also test what happens when Redis is down
```

### Run on a Redis Cluster (stage 3)

Same jar, one profile switch:

```bash
./scripts/redis-cluster.sh start        # six nodes on ports 7100-7105
./scripts/redis-cluster.sh create       # forms 3 masters + 3 replicas (interactive)
./scripts/two-gateways.sh stop
GW_ARGS=--spring.profiles.active=cluster ./scripts/two-gateways.sh start

RL_TOPOLOGY=cluster ./scripts/demo.sh
RL_TOPOLOGY=cluster FAILOVER=1 ./scripts/demo.sh   # also kills a master mid-test
```

### Switch between the "broken" and "fixed" versions

The storage strategy is a single property, so you can A/B test the same jar:

```bash
# Stage 1 (deliberately racy):
GW_ARGS="--spring.profiles.active=cluster --ratelimiter.store=rmw" ./scripts/two-gateways.sh start

# Stage 2 (atomic Lua script) is the default: --ratelimiter.store=lua
```

### Shut down

```bash
./scripts/two-gateways.sh stop
./scripts/redis-standalone.sh stop
./scripts/redis-cluster.sh stop         # or: cleanup --force to delete node data
```

`two-gateways.sh start 8080` runs one instance; `start 8080 8081 8082` runs three.

### Handy commands

```bash
./scripts/redis-standalone.sh status    # ping + key settings
./scripts/redis-standalone.sh cli       # redis-cli against this repo's node
./scripts/redis-standalone.sh flush     # forget every bucket
./scripts/redis-cluster.sh status       # cluster health and slot layout
./scripts/redis-cluster.sh keyslot KEY  # which slot / node holds KEY
./scripts/redis-cluster.sh check        # full cluster consistency check
./scripts/redis-cluster.sh cli 7101     # redis-cli to one node (no -c, so you'll see MOVED)
curl -s localhost:8080/debug/rl | python3 -m json.tool
```

### Why there are no unit tests

This is deliberate. A mocked Redis can't lose a race, can't answer `MOVED`, and can't be killed halfway through a transaction. Every claim here is about behavior that only exists with a real Redis over a real socket. Instead, verification comes from:

- **`./scripts/demo.sh`**: each section prints a measurement and the conclusion it supports.
- **The drills** in this README: `redis-cli` commands you run yourself, so you *see* the behavior instead of taking it on faith.

---

## How it works

### The token bucket, in plain terms

Imagine every caller carries a wallet of tokens:

- The wallet holds at most **`capacity`** tokens.
- It refills at a steady **`refill-per-second`**.
- Each request costs **`cost`** tokens (usually 1).
- Enough tokens? The request goes through. Not enough? `429`, with a `Retry-After` for when there will be.

Example: `feed_free` has capacity 10, refill 1/s. Fire 10 requests in one second and all pass. The 11th waits about a second. After that it's one per second. **Burst size and sustained rate are separate dials.** A plain "N requests per minute" counter can't express that.

**`cost`** lets you price expensive endpoints. `search_free` has capacity 10 and costs 5 tokens per call, so a caller can burst 2 searches. Without `cost`, you'd need five rules, five keys, and five Redis round trips to say the same thing.

### What's stored per caller

Just two fields:

| Field | Meaning |
|---|---|
| `tokens` | the balance, as of the last update |
| `last_refill_ms` | when that balance was computed |

There is no background refill job. Refill is calculated on demand when a request arrives: `tokens + elapsed_time × refill_rate`, capped at `capacity`. This means memory use doesn't grow with traffic: one request or a hundred million, it's still two fields.

A **TTL** (expiry) is set on each key so idle callers' buckets disappear, and they come back at full capacity next time. The TTL is `time-to-fill + 60s grace`. For `feed_free`: 10 s + 60 s = 70 s.

**Measured size:** about **142 bytes per bucket** in a live Redis. That's roughly 3× what a "50 bytes per counter" back-of-envelope guess predicts.

### Why a token bucket and not something else?

| Option | Behavior | Why not |
|---|---|---|
| Fixed window counter | "N per calendar second" | Boundary trick: N requests at the end of one window plus N at the start of the next lets through **2N** |
| Sliding window counter | Weighted estimate over a trailing window | Smoother, but still no way to price an expensive endpoint |
| Sliding window log | Stores a timestamp per request | Exact, but memory grows with traffic. At 100M users that's the whole problem |
| Leaky bucket | Requests drip out at a constant rate | No bursts allowed, so unused headroom is wasted |
| **Token bucket** | Burst to `capacity`, then sustained `refill` rate | **Chosen:** constant-size state, bursts are intentional, and `cost` handles pricing |

### Life of a request

```
client → RateLimitFilter → IdentityResolver → RuleEngine → BucketStore (Redis) → origin API
                                 who are you?    which limits?   can you afford it?
```

1. **`RateLimitFilter`** intercepts every request. Nothing reaches the API until it says yes.
2. **`IdentityResolver`** converts the `Authorization: Bearer …` token into a user. Callers can't pick who gets charged: if an `X-User-Id` header disagrees with the token, the header is ignored and the attempt is counted (`spoofAttempts` in `/debug/rl`).
3. **`RuleEngine`** selects the rules that apply, by path prefix and tier. **Every** matching rule is charged. One request from alice to `/api/v1/feed` spends from both her `feed_free` bucket and the shared `anon_ip` bucket. The client is told about the tightest limit.
4. **`BucketStore`** asks Redis for permission to spend. This is where stages 1 and 2 differ (see below).
5. **The response.** Allowed: the request goes to the API, and the response carries `RateLimit-*` headers. Denied: `429` plus `Retry-After`, with no call to the API.
6. **If Redis can't answer:** each rule declares a `fail-mode`:
   - `OPEN`: let the request through. Used for reads, since a limiter outage shouldn't break the product.
   - `CLOSED`: reject. Used for `/auth/`, since failing open there means attackers can try unlimited passwords.

   Both paths are counted (`fallbackAdmitted`, `fallbackDenied`). Fail-open that nobody measures is just a bypass.

---

## The rules

Six rules in `application.yaml`. A request is charged by **every** rule that matches it.

| ID | Scope | Tier | Path | Capacity | Refill/s | Cost | Fail mode |
|---|---|---|---|---|---|---|---|
| `feed_free` | per user | FREE | `/api/v1/feed` | 10 | 1 | 1 | OPEN |
| `feed_paid` | per user | PAID | `/api/v1/feed` | 100 | 20 | 1 | OPEN |
| `search_free` | per user | FREE | `/api/v1/search` | 10 | 1 | **5** | OPEN |
| `anon_ip` | per IP | any | `/api/v1/` | 20 | 2 | 1 | OPEN |
| `login_guard` | per IP | any | `/auth/` | 5 | 0.5 | 1 | **CLOSED** |
| `report_global` | whole fleet | any | `/api/v1/report` | 50 | 5 | 1 | OPEN |

Mock endpoints: `/api/v1/feed`, `/api/v1/search`, `POST /auth/login`, `/api/v1/report`.
`/healthz` is never limited (a rate-limited health check is a self-inflicted outage). `/debug/**` never consumes tokens.

**Two things that will surprise you:**

- **`anon_ip` applies even to logged-in users.** Alice's single request spends from two buckets, so your first burst will hit the IP limit (20) before you discover the user limit (10). That's the price of layered limits, not a bug.
- **Response headers show the tightest rule.** Bob is a PAID user with capacity 100, but his header says `RateLimit-Limit: 20`, because the IP rule is tighter and that's what a client needs to know.

---

## The four stages

The same jar runs every stage. What changes is how Redis is used, and therefore what you can measure.

| Stage | What changes | Key takeaway |
|---|---|---|
| **0** | One Redis node, configured from this repo | `noeviction`, AOF on, how to inspect memory |
| **1** | Spend tokens with 3 separate Redis commands (**deliberately wrong**) | 3 round trips per rule, and **30 requests admitted by a 10-token bucket** |
| **2** | One Lua script does the whole check atomically | 1 round trip per rule, **exactly 10 admitted and 20 rejected** |
| **3** | Same jar on a six-node Redis Cluster | Slot routing, `MOVED` redirects, failover in ~8 s with the bucket surviving |
| **4** | *(planned)* Local bucket state plus periodic sync | How much over-admission the sync interval costs |

### Stage 1 vs Stage 2 at a glance

| | Stage 1 (read, compute, write) | Stage 2 (Lua) | Stage 3 (Lua on cluster) |
|---|---|---|---|
| Round trips per rule | 3 | **1** | 1 (+1 on a stale redirect) |
| 30 concurrent requests vs. a 10-token bucket | **30 admitted**, bucket ends at 9.0 | **10 admitted, 20 × 429** | same as stage 2 |
| One shard's master dies | n/a | n/a | ~8 s failover, absorbed as latency, bucket survives |

**Why stage 1 is wrong:** it reads the bucket, calculates in Java, then writes it back. If many requests arrive together, they all read the same "10 tokens left", each subtracts one, and each writes back 9. All of them are allowed. Stage 2 fixes this by running the read-refill-check-spend-expire sequence as one Lua script, which Redis never interleaves with other commands.

---

## Hands-on experiments

Each experiment shows something specific. Run them in order for stage 0, then pick what interests you.

Useful debug endpoints throughout:

| Endpoint | Shows |
|---|---|
| `GET /debug/rl` | Store type, round trips per rule, latency (p50/p99/max), circuit breaker state, spoof attempts, per-rule counters |
| `GET /debug/rl/bucket?scope=user&subject=alice&rule=feed_free` | One bucket's tokens, last refill time, and TTL straight from Redis |
| `GET /debug/rl/rules` | The rules as loaded at startup, the first place to look if a limit isn't what you expect |

### Stage 0: meet the Redis node

Open a shell with `./scripts/redis-standalone.sh cli`.

**1. Confirm your config is active** (not Homebrew's):

```
CONFIG GET maxmemory-policy     # expect: noeviction
CONFIG GET save                 # expect: "" (snapshots off)
CONFIG GET appendonly           # expect: yes
CONFIG GET slowlog-log-slower-than   # expect: 5000
```

`noeviction` matters for correctness. With `allkeys-lru`, Redis could delete a live bucket under memory pressure. For a rate limiter, that isn't a cache miss; it hands a fresh allowance to the exact caller you're trying to limit. With `noeviction`, running out of memory is loud (writes error, the breaker opens, `fail-mode` decides what happens).

**2. Inspect it like an operator:**

```
INFO memory
INFO persistence
INFO clients
SLOWLOG GET 10
DBSIZE
```

**3. Watch the limiter work in real time.** In one terminal run `MONITOR`. In another:

```bash
curl -s -o /dev/null -H "Authorization: Bearer tok_free_alice" http://127.0.0.1:8080/api/v1/feed
```

On the stage-1 store you'll see **six** commands, three per rule (two rules matched):

```
"HGETALL" "rl:{user:alice:feed_free}"
"HMSET"   "rl:{user:alice:feed_free}" "tokens" "9.0" "last_refill_ms" "..."
"PEXPIRE" "rl:{user:alice:feed_free}" "70000"
"HGETALL" "rl:{ip:127.0.0.1:anon_ip}"
"HMSET"   "rl:{ip:127.0.0.1:anon_ip}" "tokens" "19.0" "last_refill_ms" "..."
"PEXPIRE" "rl:{ip:127.0.0.1:anon_ip}" "70000"
```

Notice there is no `WATCH`, no `MULTI/EXEC`, no `EVAL`. Nothing stops another request sneaking in between the read and the write. (`HMSET` is a deprecated alias for `HSET`; Redis still accepts it.)

**4. Look at a stored bucket:**

```
HGETALL rl:{user:alice:feed_free}
PTTL    rl:{user:alice:feed_free}
OBJECT ENCODING rl:{user:alice:feed_free}
MEMORY USAGE    rl:{user:alice:feed_free}
```

Sample output:

```
tokens            9.0
last_refill_ms    1793863383965
PTTL              69987        ← ≈70 s: 10 s to refill + 60 s grace
OBJECT ENCODING   listpack
MEMORY USAGE      98
```

Bob's `feed_paid` (capacity 100, 20/s) gets a 65 s TTL (5 s + 60 s). Different rules, different TTLs. The TTL lives on the key, not in the hash, so `HGET key ttl` returns empty, not an error.

**5. Hand-edit a bucket:**

```
DEL rl:{user:alice:feed_free}
HSET rl:{user:alice:feed_free} tokens 3 last_refill_ms <epoch-millis>
PEXPIRE rl:{user:alice:feed_free} 60000
```

Get the timestamp with `python3 -c 'import time;print(int(time.time()*1000))'`. (On macOS, `date +%s%3N` prints a literal `3N`, and Redis will happily store it.) Then fire requests: about 3 pass, plus whatever refilled while you typed. A garbage timestamp is treated as "now", so the bucket neither refills nor errors.

**6. Why not just `HINCRBYFLOAT`?** It's atomic on the value, but a token bucket needs "read timestamp → compute refill → check → decrement → set TTL" as one unit. An atomic decrement of a stale number is still wrong, and it can't refuse the spend and return the retry time in the same step. That's why stage 2 is a script.

**7. Measure memory yourself:**

```bash
redis-cli flushall
redis-cli info memory | grep used_memory:        # note the value
python3 -c "
import time; now=int(time.time()*1000)
for i in range(100000):
    print(f'HSET rl:{{user:u{i}:feed_free}} tokens 9.0 last_refill_ms {now}')
    print(f'PEXPIRE rl:{{user:u{i}:feed_free}} 70000')
" | redis-cli --pipe
redis-cli info memory | grep used_memory:        # (new - old) / 100000
```

Result: **~142 bytes/bucket** (even though `MEMORY USAGE` says 98; it omits dictionary overhead). So 100 M *concurrent* buckets ≈ **14 GB**, not 5 GB. That's a working set (buckets expire), not a per-day total. At 14 GB it won't comfortably fit one node, which is the case for sharding in stage 3.

### Stage 1: the race, live

**A. See the stale read.** Seed alice with **one** token and fire 20 concurrent requests:

```bash
K='rl:{user:alice:feed_free}'
NOW=$(python3 -c 'import time;print(int(time.time()*1000))')
redis-cli flushall
redis-cli hset "$K" tokens 1 last_refill_ms "$NOW"; redis-cli pexpire "$K" 60000
redis-cli monitor > /tmp/mon.out &
for i in $(seq 1 20); do
  ( curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer tok_free_alice" \
      http://127.0.0.1:8080/api/v1/feed >> /tmp/race.out ) &
done; wait; sleep 2; pkill -f 'redis-cli monitor'
sort /tmp/race.out | uniq -c
grep -oE '"(HGETALL|HMSET|PEXPIRE)"' /tmp/mon.out | uniq -c | head
```

Our result: **8 × 200, 12 × 429** from a single token. Roughly five of those admissions were created out of nothing. `MONITOR` shows why: eight `HGETALL`s landed within 27 microseconds, all before the first write. Two adjacent writes even stored the *same* value, meaning two requests saw the same bucket and each thought it had spent a token.

**B. Two JVMs make it worse.** `demo.sh` section 3 sends 30 concurrent requests alternating between :8080 and :8081 → **30 admitted**.

**C. Prove the bucket really is shared.** `demo.sh` section 2 sends 20 *sequential* requests alternating between the gateways → **10 admitted**. If each gateway kept its own bucket, you'd get 20 (limit × number of servers). This number is the whole reason to use Redis, and what stage 4 has to defend.

**D. Count the round trips.** `/debug/rl` shows `roundTripsPerRule: 3` and latency (`p50 1.0 ms`, `p99 25 ms`, `max 234 ms`). Since most requests bind two rules, that's ~6 network hops per request.

### Stage 2: one script, one round trip

`lua/token_bucket.lua` does refill → check → spend → set TTL as one uninterruptible step. `LuaBucketStore` calls it via `EVALSHA`.

**A. The A/B test.** Thirty concurrent requests against a 10-token bucket, same jar and topology, flag flipped:

```bash
K='rl:{user:alice:feed_free}'
./scripts/two-gateways.sh stop
GW_ARGS="--spring.profiles.active=cluster --ratelimiter.store=rmw" ./scripts/two-gateways.sh start 8080
redis-cli -c -p 7100 flushall
for i in $(seq 1 30); do ( curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 \
  -H "Authorization: Bearer tok_free_alice" http://127.0.0.1:8080/api/v1/feed >> /tmp/r.out ) & done
wait; sort /tmp/r.out | uniq -c; redis-cli -c -p 7100 hget "$K" tokens
```

| Store | Responses | Bucket left behind |
|---|---|---|
| `rmw` (stage 1) | **30 × 200** | `tokens 9.0` |
| `lua` (stage 2) | **10 × 200, 20 × 429** | `tokens ≈ 0.002` |

Look at the `rmw` row twice: thirty requests were let through, yet the bucket ended at 9. Twenty-nine admissions were invented by the race, and Redis never saw them. No log, no error, no counter.

**B. Run the script by hand.** Arguments are `capacity`, `refill-per-millisecond`, `cost`, `ttl-ms`. `10 0.001` means 10 tokens at 1/s.

```
redis-cli --eval gateway-service/src/main/resources/lua/token_bucket.lua 'rl:{user:drill:feed_free}' , 10 0.001 1 70000
→ 1|9.000000|0|1000
```

Output format is `allowed | tokens | retry_after_ms | reset_ms`. Note the comma in `--eval`: it separates keys from arguments.

- **Rejection:** with 0.013 tokens left and cost 1, you get `0|0.030000|970|9970`. `retry_after_ms` = 970 ms becomes `Retry-After: 1` (seconds, rounded up).
- **The clamp:** a bucket whose last refill was 10,000 seconds ago still returns `9.000000` tokens, not 9,999. A caller who sleeps for a day gets one burst, not a day's worth of requests.
- **Gotcha: `cost` larger than `capacity`.** The clamp bounds refill, not the request. Asking for 99,999 tokens returns `retry_after_ms = 99998988` (27 hours) for a balance that can never exist. The limiter won't catch this misconfiguration; its symptom is a caller who believes they're locked out until tomorrow. `search_free` (cost 5, capacity 10) is the safe version.
- **Gotcha: missing arguments.** Dropping the arguments gives `attempt to perform arithmetic on local 'refill_per_ms' (a nil value)`, reported several lines *after* the actual mistake. The bucket is left untouched, since the script aborts before writing.

**C. The script cache and `NOSCRIPT`.** The gateway sends the script body once per node, then calls it by hash. If you flush the cache:

```
redis-cli -p 7104 script flush
redis-cli -c -p 7100 evalsha <sha> 1 'rl:{user:noscript:x}' 10 0.001 1 70000
→ NOSCRIPT No matching script. Please use EVAL.
```

Gateway traffic still works: the client catches `NOSCRIPT`, re-sends the body, and retries. It costs one extra round trip. (If you compute the SHA yourself, it won't match the one in `MONITOR`; `"$(cat file)"` strips the trailing newline. Copy the hash from real traffic.)

**D. What `MONITOR` shows now.** One client command, five server-side commands:

```
"EVALSHA" "6b8e21b1..." "1" "rl:{user:alice:feed_free}" "10.0" "0.001" "1.0" "70000"
[0 lua] "TIME"
[0 lua] "HGET" ... "tokens"
[0 lua] "HGET" ... "last_refill_ms"
[0 lua] "HSET" ... "tokens" "9" "last_refill_ms" "..."
[0 lua] "PEXPIRE" ... "70000"
```

What to notice:

- **The work didn't shrink; the gap did.** The correctness win is that nothing can slip in between the steps. "3 → 1 round trips" does not mean "3× faster".
- **The clock is Redis's.** `TIME` runs inside the script, so all gateways agree on "now" regardless of their own clock skew.
- **`tokens "9"` not `"9.0"`.** Lua drops the trailing zero. Both parse fine, but your hand-seeded values and the script's writes won't look byte-identical.
- **`PEXPIRE` is inside the atomic step.** In stage 1 a crashed process could skip it, leaking an immortal key per user. That bug class is gone.

**E. Latency, honestly.** After 40 sequential requests: `roundTripsPerRule 1`, `p50 1.0 ms`, `mean 8.09 ms`, `max 359 ms`, and `p99 359 ms`.

- **That p99 is an artifact.** The latency histogram tops out at 100 ms and reports the max when the percentile lands beyond it. "p99 = 359" really means "p99 is somewhere above 100 ms".
- **The p50 didn't change from stage 1.** On localhost one hop is one hop, so the savings only show across a real network or under load.
- **The 359 ms outlier wasn't Redis.** `SLOWLOG` had no `EVALSHA` entries. The delay was on the client (waiting behind a reconnect or topology refresh), which the 50 ms command timeout doesn't cover. The timeout you configure isn't necessarily the p99 you get.

### Stage 3: six nodes

`application-cluster.yaml` points Spring at 127.0.0.1:7100-7105 and Boot switches to cluster mode. There is **no limiter code change**. The `rl:{scope:subject:rule}` key design means each bucket is one key, one key is one slot, and one slot lives on one node. The script was cluster-safe from the start.

**A. Where keys live** (`./scripts/redis-cluster.sh keyslot KEY`):

| Key | Slot | Master |
|---|---|---|
| `rl:{ip:127.0.0.1:login_guard}` | 1293 | :7100 |
| `rl:{user:bob:feed_paid}` | 6908 | :7101 |
| `rl:{user:alice:search_free}` | 6990 | :7101 |
| `rl:{ip:127.0.0.1:anon_ip}` | 10592 | :7101 |
| `rl:{user:alice:feed_free}` | 11602 | :7104 |
| `rl:{global:all:report_global}` | 15718 | :7104 |

Different users land on different masters, which is the point. But `report_global` is a single key for the whole fleet, so it's a **hot shard by design**.

**B. The `{braces}` matter.** They're a "hash tag": only the text inside is hashed.

```
rl:{user:alice:feed_free}  → slot 11602 (:7104)
rl:user:alice:feed_free    → slot 2014  (:7100)   ← braces removed, different shard
```

**C. `MOVED` isn't an error.**

```
redis-cli -p 7100 set rl:{user:alice:feed_free} poke   → MOVED 11602 127.0.0.1:7104
redis-cli -c -p 7100 hset rl:{user:probe:feed_free} tokens 5   → 1 (followed the redirect)
```

Use `-c` so `redis-cli` follows redirects. Following one costs a round trip, never a wrong token count, because the spend happens on the owning node. `max-redirects: 2` covers one `MOVED` plus one `ASK` (mid-reshard).

**D. Cross-slot atomicity doesn't exist.**

```
redis-cli -c -p 7100 eval "return 1" 2 rl:{user:x:a} rl:{user:y:b}
→ CROSSSLOT Keys in request don't hash to the same slot
```

So each matching rule is charged in its own call. A request can be admitted by `feed_free` and rejected by `anon_ip`, **and the first bucket stays spent**. That's the cost of sharding, not a bug to patch.

**E. Kill one master (its replica is alive).**

```
redis-cli -c -p 7100 hset rl:{user:alice:feed_free} tokens 0.5 last_refill_ms <now-4000>   # marker
kill $(cat .redis-cluster/7104/redis.pid)
./scripts/redis-cluster.sh status
```

Observed:

- **~8 seconds to promotion** (5 s `cluster-node-timeout` plus the failover round).
- **State survived:** `tokens 0.5` was still there on the promoted node.
- **No bypass:** `fallbackAdmitted=0`, breaker stayed closed. The failover showed up as latency (a 342 ms store sample against a 50 ms timeout), not as free requests.
- **No automatic fail-back.** The restarted node comes back as a replica. To restore the original layout: `redis-cli -p 7104 cluster failover` (~3 s). Automatic failover is a rescue; manual is a policy.
- **Caveat:** replication is async. If the replica hadn't received the latest write, that's where a free burst would sneak in.

**F. Kill a whole shard (master *and* replica).** This is the scary one.

```
kill $(cat .redis-cluster/7104/redis.pid) $(cat .redis-cluster/7102/redis.pid)
```

```
t+20s  cluster_state:fail    → CLUSTERDOWN on every node, even healthy ones
       feed → 200 with NO RateLimit-* headers (fail-open)
       login → 429 (fail-closed)
```

With the default `cluster-require-full-coverage yes`, losing one third of the shards costs **100%** of enforcement. The gateway keeps returning 200s, which looks fine on a dashboard while every limit is quietly off. `fallbackAdmitted` is the only metric that tells the truth.

The mitigation, applied to the surviving nodes:

```
for p in 7100 7101 7103 7105; do redis-cli -p $p config set cluster-require-full-coverage no; done
```

Now only keys in the dead slot range lose enforcement (alice `/feed`: `fallbackAdmitted=1`); keys on live shards work normally (bob: still limited). This is runtime-only and resets on restart. It's also a policy choice: you're deciding the lost shard goes unlimited, which matters depending on your abuse surface.

*Trap worth knowing:* a probe key (`probe_own_slot`) that hashed to a *different* node's slot made it look like the setting hadn't worked. The node was redirecting, not serving. Check `CLUSTER KEYSLOT` before calling any key "its own".

**G. See what each master holds:**

```bash
./scripts/redis-cluster.sh status
redis-cli -p 7101 cluster nodes
for p in 7100 7101 7104; do printf ':%s %s\n' $p "$(redis-cli -p $p dbsize)"; done
```

Key counts are uneven by nature, since sharding is by key, not load. ~14 GB total for 100M buckets is ~5 GB per master across three shards, which fits.

---

## The demo script

`./scripts/demo.sh` runs these sections. Each prints what it measured and the conclusion.

| # | Measured | What it shows |
|---|---|---|
| 1 | 25 requests → 10 × 200, 15 × 429; headers on the 429 | Burst equals capacity, then the refill rate. Rejections tell clients what to do |
| 2 | 20 split across 2 gateways → 10 admitted | State is genuinely shared |
| 3 | 30 concurrent → **30** (`rmw`) or **10** (`lua`) admitted | The race and its fix. The store name is read from `/debug/rl`, so the conclusion matches what ran |
| 4 | alice `tokens=9 ttl≈69981`, bob `tokens=99 ttl≈64981` | Tiers are separate keys; TTL is per rule |
| 5 | 5 searches → 2 × 200, 3 × 429 | `cost` prices expensive endpoints |
| 6 | 60 anonymous `/report` → 20 admitted | The global key is one key for the fleet; `anon_ip` binds first |
| 7 | `spoofAttempts` +1, charge still on alice | Identity comes from the token, never a header |
| 8 | Store, round trips, p50/p99/max, per-rule counters | The cost of the decision |
| 9 | (`--outage`) feed → 200 without headers; login → 429 `degraded:true` | Fail-open vs fail-closed, per route |
| 10 | (cluster) key-slot table, tagged vs. untagged keys, per-master `dbsize` | Sharding is by key; the global key is one shard |
| 11 | (cluster, `FAILOVER=1`) kill the master holding alice's bucket | Promotion, state survival, fail-back asymmetry |

**Run section 9 carefully.** The breaker opens after 3 consecutive failures and stays open for 5 s, so the script stops Redis, waits, then restarts it. Before reading the results, confirm `./scripts/redis-standalone.sh status` says "not running". Otherwise you're measuring a Redis that's still alive (this happened once and produced a fake fail-open with real headers and `Remaining: 9`).

---

## Project layout

```
redis/
  redis-standalone.conf        stage-0 node config
  redis-cluster-node.conf      stage-3 node template (__PORT__ substituted per node)
scripts/
  redis-standalone.sh          start | stop | status | cli | flush
  redis-cluster.sh             start | create | stop | status | check | nodes | keyslot | flush | cli | cleanup
  two-gateways.sh              build | start [ports] | stop | status   (GW_ARGS passes JVM flags)
  demo.sh                      the eleven measured demos
gateway-service/src/main/java/com/hld/ratelimiter/gateway/
  GatewayConfig.java           picks the store bean from ratelimiter.store
  config/                      RateLimiterProperties, StoreMode, Rule, RuleEngine, BucketPolicy
  ratelimit/
    RateLimitFilter.java       the edge: skips /debug and /healthz, stamps headers
    IdentityResolver.java      token → principal; counts spoof attempts
    RuleEngine, BucketKeys     which rules apply; builds rl:{scope:subject:rule}
    BucketStore.java           the interface: tryAcquire / name / roundTripsPerAcquire / peek
    ReadModifyWriteBucketStore stage 1 (racy on purpose, still selectable)
    LuaBucketStore.java        stage 2: EVALSHA + NOSCRIPT retry + result parsing
    RateLimitService.java      charges each rule; breaker; fail-mode fallback
    CircuitBreaker, LatencyRecorder, RateLimitMetrics
  web/                         MockApiController, DebugController, error responses
gateway-service/src/main/resources/
  lua/token_bucket.lua         the atomic bucket script
  application.yaml             standalone Redis, store: lua (default)
  application-cluster.yaml     cluster nodes, max-redirects, topology refresh
```

`BucketStore` exists so moving from stage 1 to stage 2 is a single bean swap. Nothing above that interface changed for stage 3; clustering is client configuration, not a limiter feature.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Requests are treated as anonymous; only `anon_ip` applies | The `ratelimiter.auth.tokens` map didn't bind. Nested `@ConfigurationProperties` need JavaBean **getters**; without them Spring silently uses defaults |
| `tokens=` or `ttl=` blank in `demo.sh` | The bucket expired, or the key is wrong. Global rules use subject `all` (`rl:{global:all:report_global}`). TTL comes from `PTTL`, not a hash field |
| Demo counts show `1` when 30 requests ran | `curl -w '%{http_code}'` adds no newline, so codes run together (`200200429…`). `demo.sh` emits `\n` for this reason |
| Headers still present during `--outage` | Redis came back too soon, or the kill raced shutdown. Check `redis-standalone.sh status` first |
| `hmset` appears in `MONITOR` | Lettuce uses it for map writes. Deprecated but still served; stage 2's script removes it |
| Build needs the network | First build downloads Spring Boot 4.1.2, plugins, and Lettuce into `~/.m2`; afterward it works offline. "maven not found" means no `mvn` on `PATH` and no cached wrapper; `brew install maven` or set `MVN=` |
| `MOVED <slot> <node>` from `redis-cli` | You're on a cluster without `-c`. The key is fine |
| `CROSSSLOT` | A command touched keys in two slots. The script takes exactly one key by design |
| `cluster_state:fail` and `CLUSTERDOWN` on healthy nodes | A slot range has no reachable master and `cluster-require-full-coverage` is `yes`. See stage-3 drill F |
| Cluster nodes exit at startup: `Unresolved Configuration … aborting` | A `cluster-*` directive was removed in this Redis version (Redis 8 dropped `cluster-allow-replica-migration-option`). Verify with `CONFIG GET <name>` |
| `create` reports `cluster_state:fail` right after succeeding | Gossip hasn't settled. Wait a few seconds if forming the cluster by hand |
| `script load` SHA differs from `MONITOR` | Expected; SHA1 is over exact bytes and shell substitution strips whitespace. Copy the hash from real traffic |
| Buckets missing from the node you expect | Slot ownership: use `keyslot KEY`. After a failover, the range moved. Check `cluster nodes` |

---

## What's next

**Stage 4: local state plus sync.** Keep a local copy of each bucket and sync with Redis periodically. This gives up the single source of truth. Re-run demo section 2 afterward:

- `admitted=10` is what shared state costs (exact, but every request needs Redis).
- `admitted≈20` is what purely local state costs (fast, but limit × replicas).

The sync interval has to earn back the gap between them. A local bucket also can't be made atomic by Lua, so the real question is how much over-admission you're willing to accept.

---

## Appendix: deeper notes

Details that explain *why* things behave as they do, for readers who want them.

**Why `anon_ip` stays in the picture.** It acts as a backstop: even if a user's identity is spoofed or abused, the source IP is still limited.

**Why failover didn't reset buckets.** The write reached the replica before the master died. AOF makes a restarted node keep its own keys; replication is what makes the *promoted* node hold the debt.

**Why `breaker.open-millis` (5000) and `cluster-node-timeout` (5000) belong together.** Promotion takes about the node timeout plus a failover round, so the breaker's window should be read alongside it.

**Why `CLOSED` still answered during the shard-loss test.** `login_guard`'s key lived on a healthy master, so it kept enforcing normally. Fail-closed only matters when the store can't answer.

**On `spring.data.redis.timeout: 50ms`.** It bounds a command once it's on the wire. It doesn't bound time spent waiting in a buffer behind a reconnect or a topology refresh, which is why observed latency (342-359 ms) exceeded the configured timeout.

**On Lua and `TIME`.** Older Redis versions forbade non-deterministic commands in scripts because scripts were replicated verbatim. Effect replication removed that limit, which is why `TIME` works here.

**On stage-0 memory arithmetic.** 100,000 buckets used 14,284,160 B of `used_memory` (≈142 B each) vs 98 B from `MEMORY USAGE`. Use the `used_memory` delta for capacity planning.