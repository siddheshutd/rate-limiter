#!/usr/bin/env bash
# One gateway per port, from the repackaged jar. Two processes is the point: the buckets are shared
# through Redis, so the only way to show that (and to show the race) is to run more than one JVM.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar"
RUN="$ROOT/.run"

usage() {
  echo "usage: $0 {build | start [ports...] | stop | status}" >&2
  exit 2
}

# Maven, in the order a stranger is likely to have it: an explicit MVN= override, then PATH, then the
# newest wrapper distribution already cached under ~/.m2. The cached path cannot be hardcoded — the
# version and the download-hash directory name differ per machine, and nothing but this repo's own
# author has them in the same place twice.
resolve_mvn() {
  if [ -n "${MVN:-}" ]; then
    printf '%s\n' "$MVN"
  elif command -v mvn >/dev/null 2>&1; then
    command -v mvn
  else
    ls -1t "$HOME"/.m2/wrapper/dists/apache-maven-*/*/bin/mvn 2>/dev/null | head -1
  fi
}

mkdir -p "$RUN"

case "${1:-}" in
  build)
    # Resolve lazily: start/stop/status need no maven, and build must not need the jar it is about to
    # write — otherwise the first command in the README fails on a fresh clone.
    MVN_BIN="$(resolve_mvn || true)"
    if [ -z "$MVN_BIN" ] || [ ! -x "$MVN_BIN" ]; then
      echo "maven not found - install it (brew install maven) or run: MVN=/path/to/mvn $0 build" >&2
      exit 1
    fi
    (cd "$ROOT" && "$MVN_BIN" -B -q -DskipTests package)
    # Check the artifact, not maven's exit code: a wrapper that prints something and produces no jar
    # would otherwise report a build that did not happen.
    [ -f "$JAR" ] || { echo "maven ($MVN_BIN) exited 0 but $JAR is missing" >&2; exit 1; }
    echo "built $JAR (with $MVN_BIN)"
    ;;
  start)
    [ -f "$JAR" ] || { echo "jar missing - run: $0 build" >&2; exit 1; }
    ports=("${@:2}")
    [ ${#ports[@]} -gt 0 ] || ports=(8080 8081)
    for port in "${ports[@]}"; do
      pidfile="$RUN/gw-$port.pid"
      if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "gw :$port already running (pid $(cat "$pidfile"))"
        continue
      fi
      # GW_ARGS is how a demo gets flags to the JVM without editing the script, e.g.
      #   GW_ARGS=--spring.profiles.active=cluster   (stage 3: talk to the six-node cluster)
      #   GW_ARGS=--ratelimiter.store=rmw            (stage 1: put the racy store back)
      # It is word-split on purpose; quoting a whole flag with spaces is not supported.
      nohup java -jar "$JAR" --server.port="$port" --ratelimiter.instance="gw-$port" ${GW_ARGS:-} \
        > "$RUN/gw-$port.log" 2>&1 &
      echo $! > "$pidfile"
      echo "starting gw :$port (pid $!)"
    done
    for port in "${ports[@]}"; do
      for _ in $(seq 1 60); do
        curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$port/healthz" && break
        sleep 0.5
      done
      curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$port/healthz" \
        || { echo "gw :$port did not come up; see $RUN/gw-$port.log" >&2; exit 1; }
      echo "gw :$port up"
    done
    ;;
  stop)
    for pidfile in "$RUN"/gw-*.pid; do
      [ -e "$pidfile" ] || continue
      kill "$(cat "$pidfile")" 2>/dev/null && echo "stopped $(basename "$pidfile" .pid)"
      rm -f "$pidfile"
    done
    ;;
  status)
    for port in 8080 8081 8082; do
      if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$port/healthz"; then
        echo ":$port $(curl -s --max-time 2 "http://127.0.0.1:$port/debug/rl" | tr -d '\n' | cut -c1-120)"
      fi
    done
    ;;
  *) usage ;;
esac
