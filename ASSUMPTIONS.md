# Requirements, assumptions, decisions

What the limiter must do, what was assumed, what was chosen over what. Read this before the README.

## 1. Requirements

**Functional:**
- Check every request at the gateway, before it reaches the API
- Identity from the credential, never from a caller-supplied header
- Burst and sustained rate as two numbers; an expensive endpoint priced by `cost`
- Per-tier allowances, plus a fleet-wide limit ("10k/s across everyone")
- All gateway instances share one count; concurrent requests cannot both spend the last token
- Deny = 429 + `Retry-After` + `RateLimit-*`
- Redis down: reads fail open, login fails closed, both counted

**Non-functional:**
- Scale: 1M req/s, 100M active callers; adds <10ms at p99
- Fixed state per caller (two numbers); a live bucket is never evicted
- A node dying keeps allowances and the fleet up; adding nodes changes no code
- Health and debug paths are never limited

## 2. Assumptions

| Quantity | Value | Why |
|---|---|---|
| Requests/s | 1M peak | the number a reviewer aims at; nothing here was tested at it |
| Active callers | 100M | |
| Global rule | 10k/s | |
| Source of truth | Redis only | no gateway cache — that is what holds the shared count together |
| Clients | obey `Retry-After` | one that doesn't is an abuse problem, not a limit problem |
| Auth | one credential = one caller | verified outside; the token table here is a stand-in |
| Topology | one region | cross-region timings not measured |
| Proxy headers | ignored | trusting one lets a caller mint unlimited IP buckets |

## 3. Back-of-envelope

- Bucket ≈ 140 B — loaded real buckets and divided the memory change; `MEMORY USAGE` says 98 and under-reports
- 100M × 140 B ≈ 14 GB → memory is not the constraint; decisions per second per node are
- A fleet-wide rule is one key on one node, forever
- Failover loses ~1s of spends = one second of refill per caller
- Three round trips → one did not move typical latency here; the win was correctness and the tail

## 4. Trade-offs

| Decision | Chose | Alternative | Why |
|---|---|---|---|
| Algorithm | token bucket | window counters, leaky bucket, request log | burst and rate as two dials, fixed state; windows double at the boundary, a log grows with traffic |
| Spending a bucket | one script | read + write + expire | three commands is a race: both read the last token, both allow |
| Placement | inline in the gateway | separate limiter service | extra hop, second fleet, and a client can skip it |
| Key naming | hash tag from the start | plain keys | same jar on six nodes, no code change |
| Redis down | open for reads, closed for login | one policy | an outage is not a reason to break the product; credential stuffing is |
| Time | Redis clock | gateway clock | gateways then cannot disagree by skew |
| Rules | config, restart to change | control plane | out of scope — "change without redeploy" is not met, on purpose |
| Checking | real Redis, two JVMs, six nodes | mocked tests | a mock cannot lose a race, redirect, or die |

## 5. Failure modes

| Failure | Impact | Handling |
|---|---|---|
| A master dies | ~1s of spends lost, ~8s to serve again | replica promoted, buckets survive, counted |
| One shard unreachable | `CLUSTERDOWN` (full coverage required) | flag off → the other shards keep serving |
| Redis slow or gone | requests take the fallback path | per-route mode + counters; fail-open nobody counts is a bypass |
| Spoofed user header | nothing | ignored, and counted |
| `cost` above `capacity` | caller locked out a long time | no shipped rule does it; nothing enforces it |

## 6. Not shown here

- Nothing load-tested at 1M/s, and latencies are one laptop with Redis on localhost. The ratios and the
  failure behaviour port; the absolute numbers don't.
- Auth and the origin API are stubs; there is no control plane.
- Stage 4 (local state, periodic sync) is unbuilt, so one-source-of-truth vs a faster decision has only
  one side measured.
