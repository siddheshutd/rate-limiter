# Rate limiter — prototype (stages 0 to 3)

> **Read [ASSUMPTIONS.md](ASSUMPTIONS.md) first** — the requirements, the assumptions, and what was chosen over what.

## What this is

A rate limiter you can run. Every request into the gateway is checked **before** it reaches the code
behind it, and the check itself is what this prototype is about: how cheap it can be, how exact it can
be, and what happens when the thing holding the state (Redis) is slow, contended, sharded, or on the
other side of a failover.

The answer a caller gets is designed rather than incidental: a `429`, a `Retry-After` saying when to
come back, and `RateLimit-Limit / -Remaining / -Reset / -Policy` so a well-behaved client can pace
itself instead of guessing.

| | real here | faked here |
|---|---|---|
| the store | one Redis (stages 0-2), then a six-node Redis Cluster (stage 3) — real network hops, real `MOVED`, real failover | |
| the edge | two separate gateway JVMs, so "both instances share one bucket" is something you count, not something you claim | |
| the origin API | | `MockApiController`: `/api/v1/feed` and friends return a canned body |
| the auth service | | a token table in `application.yaml` (`tok_free_alice` → `alice:FREE`) |

---

## The algorithm: a token bucket

Picture a small wallet of tokens that every caller carries.

- The wallet holds at most **`capacity`** tokens and refills at a steady **`refill-per-second`**.
- Each request pays a **`cost`** in tokens. Enough tokens → the request goes through and the wallet is
  lighter. Not enough → `429`, and `Retry-After` says how long until there are tokens again.
- Because tokens only pile up as far as `capacity`, one caller can **burst** and is then held to a
  **sustained rate**. `feed_free` is capacity 10 at refill 1/s: fire 10 requests in the same second and
  all 10 pass, the 11th waits about a second, and from then on it is one per second. Burst and rate are
  two separate dials — which is precisely what a plain "N per window" counter cannot express.
- **`cost`** turns "this endpoint is five times the work" into a number instead of extra code.
  `search_free` has capacity 10 and costs 5, so a burst is 2 searches. Without it you would model the
  same pricing as five rules, five keys and five round trips.

What the store keeps per caller is **two fields**: `tokens`, and `last_refill_ms` — the moment that
balance was last computed. Refilling is not a background job; it is arithmetic done when a request
arrives (`tokens + elapsed × refill-per-ms`, clamped at `capacity`). So memory does not grow with
traffic: one request or a hundred million, the bucket is still two fields. A third number, the TTL, lets
an idle caller's key disappear and come back at full capacity — and the size of that key is measured,
not estimated: **~142 bytes** per bucket on a live node ([stage-0 drill 7](#stage-0-drills--meet-the-node)),
which is about 3× what a "50 bytes per counter" back-of-envelope figure predicts.

### Why a token bucket, and not the other obvious answers

| option | how it behaves | why this prototype does not use it |
|---|---|---|
| fixed window counter | "up to N per calendar second" | the boundary trick: N in the last half-second plus N in the next first half-second admits **2N** |
| sliding window counter | weighted estimate over the trailing window | smoother, but still one counter per window and no way to price an expensive endpoint |
| sliding window log | a timestamp per request, count those inside the window | exact, and its memory grows with traffic — at 100 M principals that is the design, not a detail |
| leaky bucket | requests drip out at a constant rate | no burst at all, so headroom a caller earned goes unused |
| **token bucket** | burst to `capacity`, then the sustained `refill` rate | chosen: O(1) state, burst as a property rather than an accident, and `cost` for pricing |

---

## How one request gets decided

```
client → RateLimitFilter → IdentityResolver → RuleEngine → BucketStore (Redis) → origin (mock controller)
                                  ↑ who          ↑ which      ↑ may I spend?
```

1. **`RateLimitFilter`** intercepts the request. Nothing reaches the API until this says so.
2. **`IdentityResolver`** turns the `Authorization: Bearer …` credential into the principal that gets
   counted. A caller cannot choose who is charged: an `X-User-Id` header disagreeing with the
   credential is ignored *and counted*, visible as `spoofAttempts` in `/debug/rl`.
3. **`RuleEngine`** picks the rules that bind, by path prefix and tier. **Every** binding rule is
   charged, so one request from alice to `/api/v1/feed` spends two buckets — `feed_free` and the
   `anon_ip` backstop — and the tightest of them is the limit she is told about.
4. **`BucketStore`** asks the store for permission. Stage 1 does it with three separate commands (read,
   write, expire) and loses the race; stage 2 does it in one Lua script that refills, checks, spends
   and sets the TTL as a single operation Redis will not interleave.
5. **The answer.** Allow → the request reaches the origin and the response carries `RateLimit-*`.
   Deny → `429` with `Retry-After`, and no origin call at all.
6. **And when Redis cannot answer?** Each rule declares its own `fail-mode`: `OPEN` for reads (a
   limiter outage is not a reason to break the product) and `CLOSED` for `/auth/` (where what is being
   bypassed is credential stuffing). Both paths are counted — `fallbackAdmitted`, `fallbackDenied` —
   because fail-open that nobody measures is just a bypass with better spelling.

---

## Built in stages, one Redis idea at a time

The same jar runs every stage; what changes is what Redis is doing, and therefore what you can measure.
Stage 1 is **deliberately wrong in one specific way** — a bucket spent with three commands, so
concurrent requests decide from a stale snapshot — because that is the failure a figure like
"2 M ops/s (get + update per request)" hides: arithmetic about a race, presented as a cost. Those
stage-1 numbers are the baseline stages 2 and 3 have to beat:

| | stage 1 (read-modify-write) | stage 2 (Lua) | stage 3 (that, on a cluster) |
|---|---|---|---|
| round trips per rule | 3 | **1** | 1, plus a `MOVED` redirect when the cache is stale |
| 30 concurrent requests against a 10-token bucket | **30 admitted**, bucket ends at 9.0 | **10 admitted, 20 × 429** | same as stage 2 (measured on the cluster) |
| one shard's master dies | n/a | n/a | failover in ~8 s, absorbed as latency; the bucket survives it |

| stage | what is new | the number you take away |
|---|---|---|
| 0 | one Redis node, started from this repo's own config file | `maxmemory-policy`, AOF, `DBSIZE`, `MEMORY USAGE` |
| 1 | buckets spent with read-modify-write, from two JVMs | `roundTripsPerRule=3`, and 30 admitted for 10 tokens |
| 2 | one Lua script; the spend is atomic | `roundTripsPerRule=1`, 10 admitted and 20 × `429`, p50/p99 |
| 3 | six nodes, 16 384 slots, identical jar | `MOVED`/`CROSSSLOT`, per-master `dbsize`, promotion in ~8 s |
| 4 | *planned:* local bucket state plus a sync interval | how much over-admission that interval buys back |

---

## What the tests do, and what they measure

**There are no unit tests, deliberately.** A mocked Redis cannot lose a race, cannot answer `MOVED`, and
cannot be killed between its read and its write — every claim in this file is about behaviour that only
exists when the store is a real process across a real socket. Checking happens in two forms instead:

- **`./scripts/demo.sh`** — one command, and every section prints a measurement plus the conclusion that
  measurement earns. Eight sections run against a standalone node; `--outage` adds the fail-open /
  fail-closed section, `RL_TOPOLOGY=cluster` adds slot routing, and `FAILOVER=1` adds the promotion
  drill — eleven in all. Section by section: [What `demo.sh` checks](#what-demosh-checks-section-by-section).
- **The stage drills** — `redis-cli` commands you type yourself, one per idea, so the mechanism is
  something you saw in `MONITOR` rather than read about. Each stage section lists them with the real
  output from a run.

The numbers both forms read come from two places:

| where | what it tells you |
|---|---|
| `GET /debug/rl` | `store`, `roundTripsPerRule`, `storeLatency` (p50 / p99 / max), `breaker` state and trips, `spoofAttempts`, and per-rule `admitted` / `denied` / `fallbackAdmitted` / `fallbackDenied` / `unmetered` |
| `GET /debug/rl/bucket?scope=user&subject=alice&rule=feed_free` | one bucket's own `tokens`, `lastRefillMillis` and `ttlMillis`, exactly as Redis holds them |
| `GET /debug/rl/rules` | the rules as bound at startup — the place to look when a limit is not what you expect |
| Redis itself | `DBSIZE`, `INFO keyspace`, `MEMORY USAGE`, `SLOWLOG GET`, `INFO commandstats`, `MONITOR`, `CLUSTER KEYSLOT`, `CLUSTER INFO`/`NODES` |

One caution that applies to every number below: they were measured on a single laptop with the store on
localhost, where one round trip is about 1 ms. The *ratios* and the failure behaviours are the portable
findings; the absolute latencies are this machine's, and a limiter placed across a real network or under
real load will look different.

---

## How this file is organised

Before any of it: [ASSUMPTIONS.md](ASSUMPTIONS.md) — the requirements, the assumptions nothing here
proves, and what was chosen over what.

1. [Prerequisites](#prerequisites) and [Run it](#run-it) — the commands to a working demo.
2. [What the rules are](#what-the-rules-are) — the six limits and the two traps hidden in them.
3. [Stage 0 drills](#stage-0-drills--meet-the-node) — configure and question one Redis node.
4. [Stage 1 drills](#stage-1-drills--the-race-in-two-terminals) — the read-modify-write race, live.
5. [Stage 2 drills](#stage-2-drills--one-script-one-round-trip) — the Lua fix, and what it costs.
6. [Stage 3 drills](#stage-3-drills--the-same-jar-on-six-nodes) — slots, redirects, failover.
7. [What `demo.sh` checks](#what-demosh-checks-section-by-section), [Layout](#layout),
   [Next stage](#next-stage-not-built-yet), [Troubleshooting](#troubleshooting).

---

## Prerequisites

| Thing | Check | Notes |
|---|---|---|
| JDK 21+ | `java -version` | `java.version=21` in the parent pom |
| Maven | `command -v mvn` | `build` tries `$MVN`, then `PATH`, then the newest cached wrapper distribution under `~/.m2/wrapper/dists` — so an empty check above still builds. Override with `MVN=/path/to/mvn` |
| Redis | `redis-server --version` | 8.10.3 here (`brew install redis`). We start it ourselves — **not** `brew services` |
| `redis-cli`, `curl`, `python3` | | `python3` is only used by `demo.sh` to pretty-print `/debug/rl` |

Redis is run from `redis/redis-standalone.conf` in this repo, so every setting below is one you can
edit and restart.

---

## Run it

Standalone (stages 0-2) — one Redis on :6379:

```bash
./scripts/redis-standalone.sh start          # stage 0: the node, on :6379
./scripts/two-gateways.sh build              # mvn package (skipTests)
./scripts/two-gateways.sh start              # gw on :8080 and :8081, store from application.yaml
curl -s -D - -o /dev/null -H "Authorization: Bearer tok_free_alice" \
     http://127.0.0.1:8080/api/v1/feed       # first answer, with RateLimit-* headers
./scripts/demo.sh                            # eight sections, each a measurement plus its conclusion
./scripts/demo.sh --outage                   # + fail-open vs fail-closed with Redis stopped
```

Cluster (stage 3) — six nodes on 7100-7105, same jar, one profile switch:

```bash
./scripts/redis-cluster.sh start             # 6 nodes, cluster-enabled, AOF on, noeviction
./scripts/redis-cluster.sh create            # interactive: 3 masters + 3 replicas, all 16384 slots
./scripts/two-gateways.sh stop
GW_ARGS=--spring.profiles.active=cluster ./scripts/two-gateways.sh start
RL_TOPOLOGY=cluster ./scripts/demo.sh
RL_TOPOLOGY=cluster FAILOVER=1 ./scripts/demo.sh    # + kill the master holding alice's bucket
```

The store is a property, so stage 1 and stage 2 are one flag apart — the same jar, the same topology,
only the bean changes. That is the A/B the stage-2 drills are built on:

```bash
GW_ARGS="--spring.profiles.active=cluster --ratelimiter.store=rmw" ./scripts/two-gateways.sh start
curl -s localhost:8080/debug/rl | python3 -m json.tool | grep -E 'store|roundTrips'   # "rmw", 3
```

Tear down with `./scripts/two-gateways.sh stop`, `./scripts/redis-standalone.sh stop` and
`./scripts/redis-cluster.sh stop` (or `cleanup --force` to delete the node directories).
`two-gateways.sh start 8080` takes ports if you want one instance; `start 8080 8081 8082` takes more.

Useful one-liners:

```bash
./scripts/redis-standalone.sh status   # ping + version/maxmemory-policy/aof_enabled
./scripts/redis-standalone.sh cli      # redis-cli against our node (not Homebrew's)
./scripts/redis-standalone.sh flush    # forget every bucket
./scripts/redis-cluster.sh status      # cluster_state, slots_ok, and the role/slot layout per node
./scripts/redis-cluster.sh keyslot KEY # which slot, and which node serves it
./scripts/redis-cluster.sh check       # redis-cli --cluster check, including replica coverage
./scripts/redis-cluster.sh cli 7101    # redis-cli against one node (no -c: see the MOVED reply)
curl -s localhost:8080/debug/rl | python3 -m json.tool
```

---

## What the rules are

Six rules in `application.yaml`, evaluated in declaration order, and a request is charged by **every**
rule that binds it. The tiers are the two tokens in the table below.

| id | scope | tier | path | capacity | refill/s | cost | fail mode |
|---|---|---|---|---|---|---|---|
| `feed_free` | USER | FREE | `/api/v1/feed` | 10 | 1 | 1 | OPEN |
| `feed_paid` | USER | PAID | `/api/v1/feed` | 100 | 20 | 1 | OPEN |
| `search_free` | USER | FREE | `/api/v1/search` | 10 | 1 | **5** | OPEN |
| `anon_ip` | IP | any | `/api/v1/` | 20 | 2 | 1 | OPEN |
| `login_guard` | IP | any | `/auth/` | 5 | 0.5 | 1 | **CLOSED** |
| `report_global` | GLOBAL | any | `/api/v1/report` | 50 | 5 | 1 | OPEN |

`/api/v1/feed`, `/api/v1/search`, `POST /auth/login`, `/api/v1/report` are mock origin endpoints;
`/healthz` is unmetered on purpose (a health check that can be rate limited is a self-inflicted
outage), and `/debug/**` never consumes a bucket.

Two things to notice before touching Redis:

- **`anon_ip` binds even for authenticated callers.** One alice request spends two buckets, so the
  first burst you fire hits the *IP* ceiling (20) before you ever learn what a user ceiling feels like.
  That is the honest price of layered limits, not a bug — see `DBSIZE` in the drills.
- **`/debug/rl` reports the binding rule.** bob is PAID with capacity 100 and his header says
  `RateLimit-Limit: 20`, because the tightest rule that bound the request is the one a client is told.

---

## Stage 0 drills — meet the node

`./scripts/redis-standalone.sh cli` opens a shell against our config. Do these in order; each one is a
design decision in `redis/redis-standalone.conf` made visible.

**1. Prove you are talking to the config you wrote, not Homebrew's.**

```
CONFIG GET maxmemory-policy
CONFIG GET save
CONFIG GET appendonly
CONFIG GET slowlog-log-slower-than
```

Expected: `noeviction`, `""` (snapshots off), `yes`, `5000`. This is the line that decides correctness
over memory: `allkeys-lru` would evict a live bucket, and evicting a bucket is not a cache miss — it is
the limiter handing a fresh allowance back to the principal it is supposed to be limiting. A silent
bypass. `noeviction` makes the real failure (out of memory) loud: writes error, the breaker opens, and
`fail-mode` decides what the caller sees.

**2. Look at the node the way an operator does.**

```
INFO memory      # used_memory_human, maxmemory_human
INFO persistence # aof_enabled, rdb_last_bgsave_status
INFO clients     # connected_clients
LATENCY HISTORY
SLOWLOG GET 10
DBSIZE
```

`SLOWLOG GET` is how you find the p99 the gateway measured being spent on one fat command or a fork.
With `save ""` there should be no `rdb_bgsave` rows at all; if there are, you edited the wrong file.

**3. Watch the limiter work in real time.** In one terminal:

```
MONITOR
```

In another:

```bash
curl -s -o /dev/null -H "Authorization: Bearer tok_free_alice" http://127.0.0.1:8080/api/v1/feed
```

You will see **six** commands for one request — three per rule that bound it, and two rules bound it
(`feed_free` for alice, then the `anon_ip` backstop for her source address):

```
1793863638.068476 [0 127.0.0.1:55413] "HGETALL" "rl:{user:alice:feed_free}"
1793863638.069487 [0 127.0.0.1:55413] "HMSET"   "rl:{user:alice:feed_free}" "tokens" "9.0" "last_refill_ms" "1793863638066"
1793863638.069975 [0 127.0.0.1:55413] "PEXPIRE" "rl:{user:alice:feed_free}" "70000"
1793863638.070350 [0 127.0.0.1:55413] "HGETALL" "rl:{ip:127.0.0.1:anon_ip}"
1793863638.070670 [0 127.0.0.1:55413] "HMSET"   "rl:{ip:127.0.0.1:anon_ip}" "tokens" "19.0" "last_refill_ms" "1793863638070"
1793863638.071000 [0 127.0.0.1:55413] "PEXPIRE" "rl:{ip:127.0.0.1:anon_ip}" "70000"
```

`roundTripsPerRule=3` in `/debug/rl` is that trio, and the `70000` on the second key is the TTL formula
again from a different rule (capacity 20 at refill 2/s = 10 s + 60 s grace). Note what is *not* in this
log: no `WATCH`, no `MULTI`/`EXEC`, no `EVAL` — nothing that would stop another writer landing between
the `HGETALL` and the `HMSET`. One script replaces all six lines in stage 2, which is both the latency
win and the atomicity win. Note too that Lettuce sends `HMSET` for the map write (deprecated since
Redis 4 but still served).


**4. Read a bucket the way the store actually writes it.** After one alice request:

```
HGETALL rl:{user:alice:feed_free}
HGET    rl:{user:alice:feed_free} tokens
PTTL    rl:{user:alice:feed_free}
TTL     rl:{user:alice:feed_free}
OBJECT ENCODING rl:{user:alice:feed_free}
MEMORY USAGE    rl:{user:alice:feed_free}
KEYS rl:*
```

Real output from this machine:

```
tokens            9.0
last_refill_ms    1793863383965
PTTL              69987
OBJECT ENCODING   listpack
MEMORY USAGE      98
```

Two things to read off that:

- **The state is O(1).** Two fields, regardless of how many requests the principal made. A sliding-window
  log keeps one entry per request; a token bucket keeps a number and a timestamp, and this key is that
  claim measured rather than asserted.
- **`PTTL` ≈ 70 000 ms is the TTL formula, computed.** `feed_free` is capacity 10 at refill 1/s, so
  empty→full is 10 s, plus `bucket.grace-seconds: 60`. bob's `feed_paid` is 100 at 20/s → 5 s + 60 =
  **65 000 ms**, and you can see the two different numbers side by side in the two keys. TTL shorter
  than time-to-full and a paused principal's debt is forgiven (a free burst); longer and every cold
  key in the fleet sits in memory for nothing.

`EXPIRE`/`PTTL` are also the only place the TTL lives: there is **no `ttl` field in the hash**, so
`HGET key ttl` returns an empty string rather than an error. That silent empty is what a demo that
prints `ttl=` with nothing after it looks like.

**5. Persist a bucket by hand and watch the gateway accept it.** The store treats an unreadable or
absent `tokens` field as *absent*, which recreates the bucket at full capacity:

```
DEL rl:{user:alice:feed_free}
HSET rl:{user:alice:feed_free} tokens 3 last_refill_ms <epoch-millis>
PEXPIRE rl:{user:alice:feed_free} 60000
curl -s -D - -o /dev/null -H "Authorization: Bearer tok_free_alice" http://127.0.0.1:8080/api/v1/feed
```

Use `python3 -c 'import time;print(int(time.time()*1000))'` for the timestamp — macOS `date +%s%3N`
prints a literal `3N` and Redis happily stores it. A garbage `last_refill_ms` parses to "now", so
elapsed becomes 0 and the bucket neither refills nor errors. That is the boundary between a
misconfigured limiter and a broken one, and it is worth seeing once.

Now fire four requests in a row and stop when the answer flips to 429: three should pass (3 tokens)
plus whatever refilled at 1/s while you typed.

**6. Type the two commands the store does *not* use, and say why.**

```
HSET rl:{user:alice:feed_free} tokens 5
HINCRBYFLOAT rl:{user:alice:feed_free} tokens -1
```

`HINCRBYFLOAT` is atomic on the *value*, which looks like it solves the race — but a token bucket must
refill before it spends, and refill depends on elapsed time since the last refill. So you need
"read timestamp → compute → decrement → set TTL" as one unit. An atomic increment on a stale number is
still a wrong number, and it also cannot refuse the spend and *return* the retry time in the same
operation. That is why stage 2 is a script and not a `HINCRBYFLOAT`.

**7. Memory, and the back-of-envelope estimate, checked.** A "50 b per counter" figure makes
100 M users look like 5 GB/day. Load 100 k real buckets and measure:

```bash
redis-cli flushall
redis-cli info memory | grep used_memory:            # note it
python3 -c "
import time; now=int(time.time()*1000)
for i in range(100000):
    print(f'HSET rl:{{user:u{i}:feed_free}} tokens 9.0 last_refill_ms {now}')
    print(f'PEXPIRE rl:{{user:u{i}:feed_free}} 70000')
" | redis-cli --pipe
redis-cli info memory | grep used_memory:            # delta / 100000
```

Measured on this machine: **~142 bytes per bucket** (the `used_memory` delta for 100 000 keys was
14 284 160 B), while `MEMORY USAGE` on a single one of those keys reports **98 B**. The gap is the
dictionary entry and object overhead `MEMORY USAGE` does not attribute to the key — so the cheap way
to estimate a working set is the delta, not the per-key call.

Which means 100 M *concurrent* buckets ≈ **14 GB**, not 5. Two corrections to the back-of-envelope
version of that figure: it is a **working set**, not "per day" (buckets expire on TTL, so
footprint = active principals × bytes/key, and `noeviction` means that number has to fit), and the
bytes/key was ~3× optimistic. At 14 GB this does not fit one node with room to breathe, which is the
sharding argument stage 3 exists for.

---

## Stage 1 drills — the race, in two terminals

**A. `MONITOR` shows the stale read.** Seed alice with **one** token, then fire 20 concurrent requests
and watch the command order:

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

Real result here: **8 × 200, 12 × 429** for a bucket seeded with 1 token, with ~2 s of wall clock
(≈3 tokens' worth of refill), so roughly **five admissions came out of nothing**. And `MONITOR` confirms
the mechanism directly:

```
      8 "HGETALL"     ← eight readers, in 27 microseconds, all before the first write
      8 "HMSET"
```

Eight requests read the same snapshot and each one subtracted from it independently. That is the
textbook race diagram — `A: HGETALL → 1, B: HGETALL → 1, both write 0, both allow` — appearing in a
command log instead of in prose. If you scroll further into `/tmp/mon.out`, two adjacent writes even
carry the **same value** (`tokens 0.6059999999999999`, same `last_refill_ms`), which is two requests
that saw the same bucket and both thought they had just spent a token:

```
1793863459.880100 ... "HMSET" "rl:{user:alice:feed_free}" "tokens" "0.6059999999999999" "last_refill_ms" "1793863459876"
1793863459.880113 ... "HMSET" "rl:{user:alice:feed_free}" "tokens" "0.6059999999999999" "last_refill_ms" "1793863459876"
```


**B. Make it worse with two JVMs.** `demo.sh` section 3 fires 30 concurrent requests alternating
between :8080 and :8081: **admitted=30** while the bucket only spent down to ~0–3 tokens. One process
with 200 Tomcat threads is already enough to race; two processes make it undeniable, because there is
now no shared memory that could have accidentally serialised them.

**C. Prove the shared bucket is really shared.** `demo.sh` section 2 sends 20 *sequential* requests
alternating between the two gateways: **admitted=10**. If each instance kept its own in-memory bucket
this would be 20 — the local-state design would hand every caller `limit × replicas`. This is the
number that justifies depending on Redis at all, and the one stage 4 (local state + async sync) has to
re-earn.

**D. Count the round trips you are paying.** `curl -s localhost:8080/debug/rl` prints
`roundTripsPerRule: 3` next to `storeLatency` (`p50 1.0 ms`, `p99 25 ms`, `max 234 ms` here). The max
is the interesting one: a fork, a cold key, or a GC pause in the *limiter* lands on your request path
because the limiter is now in the request path. Two rules bind most requests, so a request pays
~6 hops; a p99 budget of 10 ms starts looking ambitious at 1 M req/s, which is the second thing
stage 2 fixes.

---

## Stage 2 drills — one script, one round trip

`lua/token_bucket.lua` does refill → check → spend → `PEXPIRE` in one operation Redis will not
interleave, and `LuaBucketStore` calls it with `EVALSHA`. Switching is a property, not a code change:
`ratelimiter.store: lua` (default) or `rmw`.

**A. The A/B you came for: 30 concurrent requests against alice's 10-token bucket.** Same jar, same
topology, one flag different, with the buckets flushed first:

```bash
K='rl:{user:alice:feed_free}'
./scripts/two-gateways.sh stop
GW_ARGS="--spring.profiles.active=cluster --ratelimiter.store=rmw" ./scripts/two-gateways.sh start 8080
redis-cli -c -p 7100 flushall
for i in $(seq 1 30); do ( curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 \
  -H "Authorization: Bearer tok_free_alice" http://127.0.0.1:8080/api/v1/feed >> /tmp/r.out ) & done
wait; sort /tmp/r.out | uniq -c; redis-cli -c -p 7100 hget "$K" tokens
```

Real numbers from this machine, seconds apart:

| store | answers | bucket left behind |
|---|---|---|
| `rmw` | **30 × 200** | `tokens 9.0` |
| `lua` | **10 × 200, 20 × 429** | `tokens 0.0019999999999999` |

Read the `rmw` row twice. Thirty requests were admitted by a bucket that holds ten tokens, and the
bucket *finished at nine* — 29 of those 30 admissions were minted by the race, and Redis never saw a
spend for them. There is no log line, no counter, no error: the limiter answered 200 and moved on. The
`lua` row is the same load with the read-modify-write gap closed: the arithmetic is identical, and
exactly the capacity was handed out.

**B. Type the script yourself before you trust a client to call it.** `--eval` sends the body inline,
which is the `EVAL` half of the `EVALSHA`/`EVAL` pair:

```
redis-cli --eval gateway-service/src/main/resources/lua/token_bucket.lua 'rl:{user:drill:feed_free}' , 10 0.001 1 70000
→ 1|9.000000|0|1000
redis-cli --eval gateway-service/src/main/resources/lua/token_bucket.lua 'rl:{user:drill:feed_free}' , 10 0.001 9 70000
→ 1|0.013000|0|9987
```

`allowed|tokens|retry_after_ms|reset_ms`. The second call spent 9 of the remaining 9.013 and got a
`1`. A third, against the 0.013 that was left (seeded again so the numbers line up):

```
redis-cli -c -p 7100 hset 'rl:{user:drill:feed_free}' tokens 0.013 last_refill_ms <now>
redis-cli -c -p 7100 --eval .../token_bucket.lua 'rl:{user:drill:feed_free}' , 10 0.001 1 70000
→ 0|0.030000|970|9970
```

`retry_after_ms` is `ceil((cost - tokens) / refill_per_ms)`: the bucket held 0.030 by the time the script
ran (0.013 plus 17 ms of refill), so 1 token needs 970 ms — which is what becomes the `Retry-After: 1`
header (seconds, rounded up). `reset_ms` is the same arithmetic to capacity: 9 970 ms to full.

Now prove the clamp, with a timestamp 10 000 seconds in the past so "refill" would mean thousands of
tokens:

```
redis-cli -c -p 7100 hset 'rl:{user:drill:feed_free}' tokens 0 last_refill_ms <now - 10000000>
redis-cli -c -p 7100 --eval .../token_bucket.lua 'rl:{user:drill:feed_free}' , 10 0.001 1 70000
→ 1|9.000000|0|1000        # not 1|9999.000000|0|1000
```

`tokens > capacity` is clamped before the spend, which is the difference between a bucket and a meter:
a principal who sleeps for a day gets one burst, not a day of requests.

The clamp is on the refill, though, not on the request. Empty the bucket and ask for more than it can
ever hold:

```
redis-cli -c -p 7100 hset 'rl:{user:drill:feed_free}' tokens 0 last_refill_ms <now>
redis-cli -c -p 7100 --eval .../token_bucket.lua 'rl:{user:drill:feed_free}' , 10 0.001 99999 70000
→ 0|0.012000|99998988|9988
```

`retry_after_ms` is `ceil((cost - tokens) / refill_per_ms)`, and it will happily promise 99 998 988 ms —
27 hours — for a balance no bucket can reach. `reset_ms` stays honest at 9 987 because it is measured to
`capacity`, which the clamp bounds; `retry_after_ms` is measured to `cost`, which nobody bounds. So a
`cost` above a `capacity` is a configuration error the limiter will not catch, and its symptom is a
caller who believes they are locked out until tomorrow. `cost: 5` on `capacity: 10` (`search_free`) is
the well-behaved version of the same line, and is why the 5-search drill gives 2 × 200 and 3 × 429
instead of a lockout. The invariant belongs where both numbers are visible —
[Rule.java:32](gateway-service/src/main/java/com/hld/ratelimiter/gateway/config/Rule.java#L32).

The four arguments are `capacity`, `refill-per-millisecond`, `cost`, `ttl-ms` — and `10 0.001` is
`feed_free`'s 10 tokens at 1/s expressed in millis, because `TIME` reports seconds and microseconds and
the bucket stores millis. The `--eval` syntax is `<file> <keys> , <argv>`: the comma separates the two
lists. Drop the arguments and see what Lua does with a missing `ARGV`:

```
redis-cli --eval .../token_bucket.lua 'rl:{user:drill:feed_free}' ,
→ ERR user_script:53: attempt to perform arithmetic on local 'refill_per_ms' (a nil value)
```

`tonumber(nil)` does not raise; it returns nil, and the script fails eight lines later at the first
arithmetic. The line number in the error is therefore *not* where the mistake is — worth knowing before
you debug a script over a socket. Note too that the bucket was untouched: the abort happened before the
only `HSET`, so a failed script leaves state alone rather than half-spending it.

**C. The script cache, and `NOSCRIPT`.** The gateway ships the body once per node and then calls the
hash:

```
redis-cli script load "$(cat gateway-service/src/main/resources/lua/token_bucket.lua)"
→ e0ce97235bde839303eb85b032c81622892c283c
```

Compare that with the hash in `MONITOR` below: `6b8e21b157d673bd61edb7f2d8f5cf8f2b40f45b`. **Different,
for the same file** — SHA1 is over exact bytes and `"$(cat ...)"` strips the trailing newline. If you
ever want to reproduce what the app sends, take the hash out of the traffic, not out of a shell.

Now break it on purpose:

```
redis-cli -p 7104 script flush
redis-cli -c -p 7100 evalsha 6b8e21b157d673bd61edb7f2d8f5cf8f2b40f45b 1 'rl:{user:noscript:x}' 10 0.001 1 70000
→ NOSCRIPT No matching script. Please use EVAL.
```

…and send gateway traffic anyway: `200`, `RateLimit-Remaining: 9` — Lettuce catches `NOSCRIPT`, ships
the body again, and retries. `SCRIPT EXISTS <sha>` answers `1` on the masters that were touched, `0` on
`:7100`, which never served a bucket. So `SCRIPT FLUSH` is a hiccup, not an outage — but note what it
cost you: one extra full-body round trip per node per script, which is why the client keeps the body
around instead of assuming the cache is warm.

**D. `MONITOR` on the node that owns the key.** One line from the client instead of stage 1's three per
rule, and the server's own work is visible as `[0 lua]`:

```
1793875261.189793 [0 127.0.0.1:63591] "EVALSHA" "6b8e21b1..." "1" "rl:{user:alice:feed_free}" "10.0" "0.001" "1.0" "70000"
1793875261.189830 [0 lua] "TIME"
1793875261.189867 [0 lua] "HGET" "rl:{user:alice:feed_free}" "tokens"
1793875261.189885 [0 lua] "HGET" "rl:{user:alice:feed_free}" "last_refill_ms"
1793875261.189899 [0 lua] "HSET" "rl:{user:alice:feed_free}" "tokens" "9" "last_refill_ms" "1793875261189"
1793875261.189916 [0 lua] "PEXPIRE" "rl:{user:alice:feed_free}" "70000"
```

Four things to take from those lines:

- **One client round trip, five commands of server work** (`commandstats` agrees: `evalsha:calls=1`,
  `hget:calls=2`, `hset:calls=1`, `time:calls=1`, `pexpire:calls=1`). The refill did not get cheaper —
  the *interleaving window* disappeared. Anyone who reads "3 → 1" as "three times faster" has the wrong
  lesson; the latency win is the network hops, the correctness win is the gap between them.
- **The clock is the server's.** `TIME` is called inside the script, so refill and spend share one
  instant, and the two gateways can no longer disagree by their own clock skew. The `TIME` line in the
  log above is the proof it is permitted on this node (8.10.3); older Redis refused random commands in
  scripts because the script text was replicated verbatim, and effect replication is what lifted that.
- **`tokens "9"`, not `"9.0"`.** Lua's `tostring` drops the trailing zero the Java store wrote in stage
  1. `tonumber` reads either, so a stored-format change needs no migration — but it does mean your
  hand-seeded `HSET ... tokens 3` and the script's own writes will not look byte-identical in
  `HGETALL`, and a `MATCH "9.0"` audit query silently finds nothing.
- **`PEXPIRE` is inside the atomic step.** In stage 1 it was the command a killed process most often
  skipped, leaking one immortal key per principal. That class of leak is gone; check with `DBSIZE` after
  a burst plus a kill.

**E. What it did to the latency, honestly.** Restart a gateway so the histogram starts empty, run 40
sequential requests through it, read `/debug/rl`: `roundTripsPerRule 1`, `observations 50` (40 requests,
some binding two rules), `p50Millis 1.0`, `meanMillis 8.09`, `maxMillis 359`, and a
`p99Millis` that also says 359. Read that last one carefully, because it is a claim about the
instrument as much as about Redis: `LatencyRecorder` is a fixed histogram whose buckets top out at
100 ms, and `percentileMillis()` returns `maxMillis()` when the target falls in the overflow bucket
(`LatencyRecorder.java:61-69`). So "p99 = 359 ms" means *the p99 is somewhere above 100 ms and the worst
sample was 359* — the number your own instrumentation invents when the tail escapes its ceiling. The
budget this prototype aims at is p99 < 10 ms, so the buckets are placed for that question; a store that gets slower than 100 ms
is reported as one number, and that is a thing to know before you promise a tail.

Two more readings from the same line:

- **p50 is unchanged from stage 1** — on localhost one hop is one hop, so "3 → 1 round trips" is nearly
  invisible in the median here. It would show up across a real network, under load, or in the tail.
  Anyone quoting "3× faster" from this number has the wrong lesson.
- **The outlier was not Redis.** `SLOWLOG GET` on all three masters held **no `EVALSHA`** at all (the one
  entry was a 6.6 ms `PSYNC` between replicas, and the slowlog threshold is 5 ms). So 359 ms was spent on
  the client: `spring.data.redis.timeout: 50ms` bounds a command once it has been written, and does not
  bound time sitting in a buffer behind a reconnect or a topology refresh. The timeout you configure is
  not the p99 you get, and the only way to find that out is to measure both.

## Stage 3 drills — the same jar on six nodes

`application-cluster.yaml` sets `spring.data.redis.cluster.nodes` (127.0.0.1:7100-7105) and Boot switches
to cluster mode; there is no limiter code in this stage at all. That is the payoff for spending stages
0-2 with `rl:{scope:subject:rule}` keys: a bucket is one key, one key is one slot, one slot is one node,
so the script was cluster-safe from the day the braces went in.

**A. Where your keys actually live.** `./scripts/redis-cluster.sh keyslot KEY` prints the slot and the
node; measured for every key the demo uses:

| key | slot | master |
|---|---|---|
| `rl:{ip:127.0.0.1:login_guard}` | 1293 | :7100 |
| `rl:{user:bob:feed_paid}` | 6908 | :7101 |
| `rl:{user:alice:search_free}` | 6990 | :7101 |
| `rl:{ip:127.0.0.1:anon_ip}` | 10592 | :7101 |
| `rl:{user:alice:feed_free}` | 11602 | :7104 |
| `rl:{global:all:report_global}` | 15718 | :7104 |

Two principals' ordinary feed reads land on two different masters, which is the whole point — and
`report_global`, one key for the fleet, sits alone on one master taking every request's contention.
The GLOBAL scope is not "shared state", it is a **hot shard by construction**.

**B. The hash tag is load-bearing.** Same characters, one pair of braces missing:

```
rl:{user:alice:feed_free}  → slot 11602 (:7104)
rl:user:alice:feed_free    → slot 2014  (:7100)
```

Without the tag Redis hashes the whole key, so nothing about a principal's buckets is colocated and a
"one key per rule" layout becomes "one random shard per rule". With it, only `scope:subject:rule` is
hashed. `demo.sh` section 10 prints exactly this pair.

**C. `MOVED`, and why every cluster drill here uses `-c`.**

```
redis-cli -p 7100 set rl:{user:alice:feed_free} poke   → MOVED 11602 127.0.0.1:7104
redis-cli -c -p 7100 hset rl:{user:probe:feed_free} tokens 5   → 1   (it followed the redirect)
```

A `MOVED` is not a failure and not a retry of the spend: the refill-check-spend happens on the owning
node, so following the redirect costs a round trip and never a wrong token count. `max-redirects: 2` is
the budget — one `MOVED` (slot lives elsewhere) and one `ASK` (slot is mid-reshard). An operator without
`-c` sees an error for a request that is perfectly routable, which is how "the cluster is broken" tickets
start.

**D. Cross-slot atomicity does not exist. Go on, try it.**

```
redis-cli -c -p 7100 mset rl:{user:alice:feed_free} 1 rl:{user:bob:feed_free} 1
→ CROSSSLOT Keys in request don't hash to the same slot
redis-cli -c -p 7100 eval "return 1" 2 rl:{user:x:a} rl:{user:y:b}
→ CROSSSLOT Keys in request don't hash to the same slot
```

The second one is the command the design could have written: "charge both rules in one script". On a
standalone node it would have worked; on a cluster it does not exist. So `RateLimitService` charges each
binding rule as its own call, which means a request that binds `feed_free` and `anon_ip` can be admitted
by one and refused by the other, and **the first bucket stays spent**. That is not a bug to fix — it is
the cost of sharding, and the reason the multi-rule charge order is declaration order and the response
reports the tightest binder.

**E. Kill one master (its replica is alive) — the drill that should reassure you.**

```
redis-cli -c -p 7100 hset rl:{user:alice:feed_free} tokens 0.5 last_refill_ms <now-4000>   # a marker
kill $(cat .redis-cluster/7104/redis.pid)      # :7104 owns slot 11602, alice's bucket
./scripts/redis-cluster.sh status              # watch it
```

Measured, from the kill:

```
t+8s   cluster_state:ok      :7102 now master for 10923-16383   :7104 shown as master,fail
       bucket on the promoted node: tokens 0.5 last_refill_ms 1793875295068
       gateway: 200 + RateLimit-Limit/Remaining headers, breaker CLOSED, trips 0,
                feed_free fallbackAdmitted = 0,   store latency max = 342 ms
```

- **State survives promotion** because the write reached the replica before the master died: `tokens 0.5`
  read back as `0.5` on `:7102`. The "failover resets everyone's bucket and hands out a free burst"
  scenario is disproved by the marker, not by argument. (AOF is what makes the *node itself* come back
  with its keys; it is replication that makes the *promoted* node hold alice's debt. If the replica had
  been unreachable at write time, this is exactly where a free burst would come from — replication is
  async, so the newest write can be lost.)
- **Promotion took ~8 s**, which is `cluster-node-timeout 5000` plus the failover round, not zero — the
  window a gateway has to survive is that long, and it is why `breaker.open-millis: 5000` and the node
  timeout are numbers that have to be read together.
- **The limiter absorbed it as latency, not as a bypass**: `fallbackAdmitted=0`, the breaker never opened,
  enforcement continuous — at the cost of a 342 ms store sample against a configured 50 ms timeout. Same
  mechanism as stage-2 drill E, now with a reason: while Lettuce waits for the slot map to change hands
  the command is not on the wire, so the per-command timeout has nothing to bound.
- **Fail-back never happens on its own.** Restart `:7104` and it returns as `myself,slave` of `:7102`,
  which keeps the slots. If you want the original layout back you ask for it:
  `redis-cli -p 7104 cluster failover` — measured: slots handed back in ~3 s, cleanly, because *someone*
  decided. Automatic failover is a rescue; manual failover is a policy. Run the automatic one once and
  then the manual one, and the asymmetry is obvious in `cluster nodes`.

**F. Kill a whole shard (master *and* replica) — the one that should worry you.**

```
kill $(cat .redis-cluster/7104/redis.pid) $(cat .redis-cluster/7102/redis.pid)   # all of 10923-16383
```

```
t+8s   cluster_state:ok        keys in the dead range: MOVED 16264 127.0.0.1:7104 (unreachable)
t+20s  cluster_state:fail      redis-cli -p 7100 set probe_own 1 → CLUSTERDOWN The cluster is down
       gateway feed → 200 with NO RateLimit-*  (fail-open)      login → 429  (fail-closed)
```

No replica means no promotion, and after `cluster-node-timeout` the cluster marks the slot range `fail`;
with the default `cluster-require-full-coverage yes` **every node refuses every command** — including
`:7100` answering for a slot it owns and is healthy. Losing one third of the shards costs 100% of the
enforcement. The gateway stayed up (200s), which is the part that will look fine on a dashboard while
every limit in the product is quietly off; `fallbackAdmitted` is the only number that tells the truth.

Then the switch, on the four live nodes:

```
for p in 7100 7101 7103 7105; do redis-cli -p $p config set cluster-require-full-coverage no; done
```

```
cluster_state:ok
alice /feed   → 200, no RateLimit-*        feed_free     fallbackAdmitted=1   (her key is in the dead range)
bob   /feed   → 200, RateLimit-Limit: 20   feed_paid     fallbackAdmitted=0   (his key is on a live shard)
alice /report → 200, no RateLimit-*        report_global fallbackAdmitted=1
login         → 200                        login_guard   on live :7100, still enforcing
```

That is the real shape of the trade: coverage-off converts a total loss of enforcement into a loss
scoped to the keys in the dead slot range, and everything else keeps working normally. It is a
`CONFIG SET` — runtime only, and the restart that follows a recovery puts `yes` back (it is also what the
conf file says). Note this is a *limiter* decision, not a Redis decision: with `no`, the shard you lost
stops being rate limited at all, so whether you prefer that over a fleet-wide fail-open is a question
about your abuse surface. `login_guard` is `CLOSED` either way, and it kept answering.

One trap from this drill: my first probe, `redis-cli -p 7100 set probe_own_slot 1`, answered
`CLUSTERDOWN` and I concluded the flip had failed. `probe_own_slot` hashes to slot 7373 — owned by
`:7101`. The node was *redirecting*, not serving. Check `CLUSTER KEYSLOT` before you call any reply
"its own slot"; `probe_own` (slot 2424, owned by `:7100`) wrote `OK`.

**G. Which master holds what, live.**

```bash
./scripts/redis-cluster.sh status          # cluster_state, slots_ok, role + slot ranges per node
redis-cli -p 7101 cluster nodes            # the same as one machine sees it, including fail flags
for p in 7100 7101 7104; do printf ':%s %s\n' $p "$(redis-cli -p $p dbsize)"; done
```

Per-master `dbsize` after a demo run is uneven, and it will stay uneven: the sharding is by key, not by
load. That is also the working-set estimate from stage 0 divided by the number of masters — ~14 GB for
100 M buckets is ~5 GB per master at three shards, which fits.

## What `demo.sh` checks, section by section

| Section | Measured here | The line it earns |
|---|---|---|
| 1 | 25 requests → 10 × 200, 15 × 429 | burst = capacity, then refill rate |
| 1 | `429`, `Retry-After: 1`, `RateLimit-Limit/Remaining/Reset/Policy` | a rejection contract a client can act on |
| 2 | 20 split across 2 gateways → 10 | shared state |
| 3 | 30 concurrent → **30 admitted** (`rmw`) / **10 admitted** (`lua`); the store name is read from `/debug/rl`, so the conclusion matches what ran | the race, and its fix |
| 4 | alice `tokens=9 ttl=69981`, bob `tokens=99 ttl=64981` | tiers are separate keys; TTL is per-rule |
| 5 | 5 searches → 2 × 200, 3 × 429 | cost weighting: an expensive endpoint is priced, not counted |
| 6 | 60 anonymous `/report` → 20 admitted | the GLOBAL key is one key for the fleet, and `anon_ip` binds first |
| 7 | `spoofAttempts` +1, charge still on alice | identity comes from the credential, never from `X-User-Id` |
| 8 | store / round trips / p50-p99-max / per-rule counters; under `lua`, `commandstats` + `MONITOR` prove 1 `EVALSHA` per rule | the cost of the decision, measured |
| 9 | `feed` → 200 with **no** `RateLimit-*`; `login` → 429 `degraded:true` | fail-open vs fail-closed, per route |
| 10 | cluster only: the keyslot table, tagged vs untagged, per-master `dbsize` | sharding is by key, and the GLOBAL key is one shard |
| 11 | cluster only, `FAILOVER=1`: kill the master holding alice's bucket, 5 bursts, then restart it | promotion, state survival, fail-back asymmetry |

Section 9 is the one to run twice. `fallbackAdmitted` counts requests that passed *because the store
could not answer* — an outage that is not counted is a bypass nobody saw. The breaker opens after
`consecutive-failures: 3` and stays open `open-millis: 5000`, so the demo stops Redis, waits, and
restarts it; check `./scripts/redis-standalone.sh status` reports "not running" before you read the
fail-open numbers, otherwise you are measuring a node that is still alive (this happened, and it
produced a fake fail-open with real headers and `Remaining: 9`). Under `RL_TOPOLOGY=cluster` the same
section stops a whole shard instead of one node, and section 11 stops only the master.

---

## Layout

```
redis/redis-standalone.conf        stage-0 node config; every line is a defended decision
redis/redis-cluster-node.conf      stage-3 node template (__PORT__ is substituted per node)
scripts/redis-standalone.sh        start|stop|status|cli|flush  (ours, not brew services)
scripts/redis-cluster.sh           start|create|stop|status|check|nodes|keyslot|flush|cli|cleanup
scripts/two-gateways.sh            build|start [ports]|stop|status  (GW_ARGS passes flags to the JVM)
scripts/demo.sh                    the eleven measured behaviours above; RL_TOPOLOGY=cluster for 10-11
gateway-service/src/main/java/com/hld/ratelimiter/gateway/
  GatewayConfig.java               wiring: the store bean is chosen by ratelimiter.store
  config/                          RateLimiterProperties, StoreMode, Rule, RuleEngine, BucketPolicy
  ratelimit/RateLimitFilter.java   the edge: never limits /debug or /healthz, stamps headers
  ratelimit/IdentityResolver.java  credential → principal; spoof attempts counted
  ratelimit/RuleEngine+BucketKeys  which rules bind; rl:{scope:subject:rule} (the braces are a hash tag)
  ratelimit/BucketStore.java       the port: tryAcquire / name / roundTripsPerAcquire / peek
  ratelimit/ReadModifyWriteBucketStore.java   stage 1, racy on purpose, still selectable
  ratelimit/LuaBucketStore.java    stage 2: EVALSHA + NOSCRIPT + the '|'-split answer
  ratelimit/RateLimitService.java  charge every binding rule; breaker; fail-mode fallback
  ratelimit/CircuitBreaker.java + LatencyRecorder.java + RateLimitMetrics.java
  web/                             MockApiController, DebugController, error contract
gateway-service/src/main/resources/
  lua/token_bucket.lua             one bucket, spent in one operation Redis will not interleave
  application.yaml                 standalone node + store: lua (default)
  application-cluster.yaml         stage 3: cluster.nodes, max-redirects, Lettuce topology refresh
```

`BucketStore` exists so the stage-1 → stage-2 change is one bean in `GatewayConfig`, and
`roundTripsPerAcquire()` exists so `/debug/rl` can print the cost of the choice next to the latency it
produced. Nothing above the port changed for stage 3 either — the cluster is a client configuration, not
a limiter feature.

---

## Next stage (not built yet)

- **Stage 4 — local state + sync:** the hard part of the design, not yet attempted: keeping a local
  copy of the bucket means giving up a single source of truth. Re-run section 2 afterwards:
  `admitted=10` is what shared state costs, and `admitted≈20` is what
  local caching costs — the gap between those two numbers is the drift budget the sync interval has to
  earn back. Stage 2's script is the other half of the argument: a local bucket cannot be made atomic by
  Lua, so the honesty of this stage depends on how much over-admission you are willing to price.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Requests are anonymous, only `anon_ip` binds | The `ratelimiter.auth.tokens` map did not bind. Nested `@ConfigurationProperties` objects need JavaBean **getters**; without one Spring silently leaves the defaults, so the token table is empty and `IdentityResolver` throws at startup. |
| `tokens=` or `ttl=` blank in `demo.sh` | The bucket expired or the key is wrong. GLOBAL uses subject `all` (`rl:{global:all:report_global}`), and TTL is `PTTL`, not a hash field. |
| Counts read `1` when 30 requests ran | `curl -w '%{http_code}'` adds no newline, so codes concatenate into `200200429…`. `demo.sh`'s `status()` emits `\n` for this reason. |
| Headers present during the `--outage` run | Redis came back up before the burst, or the kill raced shutdown and answered the request anyway. Confirm with `redis-standalone.sh status` before reading the fail-open numbers. |
| `hmset` in `MONITOR` | Lettuce's `putAll`; Redis still serves it though it has been deprecated since 4. Stage 2's single `EVAL` removes it. |
| Build wants the network | The first `build` downloads the Boot 4.1.2 parent, the plugins and Lettuce into `~/.m2`; after that it works offline. `maven not found` is the other failure: no `mvn` on `PATH` and nothing under `~/.m2/wrapper/dists` — install it (`brew install maven`) or pass `MVN=/path/to/mvn`. |
| `MOVED <slot> <node>` from `redis-cli` | You are on a cluster and did not use `-c`. The key is fine; the client is not following redirects. Same reply from the gateway would mean `max-redirects` is set to 0. |
| `CROSSSLOT Keys in request don't hash to the same slot` | One command touched keys in two slots. On a cluster that is not a bug to work around: the bucket script takes exactly one key, and `RateLimitService` charges rules as separate calls for this reason. |
| `cluster_state:fail` and every command answers `CLUSTERDOWN`, on healthy nodes | A slot range has no reachable master and `cluster-require-full-coverage` is `yes` (the default). See stage-3 drill F; `./scripts/redis-cluster.sh status` shows which range is `fail`. |
| Cluster nodes exit at start with `Unresolved Configuration ... aborting` | A `cluster-*` directive that this Redis version removed. Redis 8 dropped `cluster-allow-replica-migration-option`; confirm any cluster knob with `redis-cli CONFIG GET <name>` before putting it in the template. |
| `create` says `cluster_state:fail` right after it succeeds | Gossip has not settled. The script waits for it; if you form the cluster by hand, sleep a few seconds before reading `cluster info`. |
| `script load` gives a different SHA than `MONITOR` shows | Expected. SHA1 is over exact bytes and shell substitution strips whitespace. Copy the hash from the traffic. |
| Buckets missing from the node you expect | Slot ownership, not a bug: `./scripts/redis-cluster.sh keyslot KEY` answers where a key lives. After a failover the range moved; check `cluster nodes` for which master holds it now. |
