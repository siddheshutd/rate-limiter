package com.hld.ratelimiter.gateway.ratelimit;

/**
 * Stops the gateway from waiting on a counter store that is already known to be failing.
 *
 * <p>The breaker is why "Redis is down" is survivable instead of a slow outage. Without it, every
 * request pays the command timeout while the store is unreachable, and the gateway's own worker
 * threads are the resource that runs out: a limiter meant to protect the backend has taken the
 * frontend down. With it, one bounded probe is enough to learn the answer for a whole window.
 *
 * <p>Open duration must exceed a replica promotion, or the half-open probe keeps landing on a node
 * that is still electing and the breaker oscillates exactly when traffic most needs it stable.
 */
public final class CircuitBreaker {

    public enum State {
        /** Store is answering; requests go to it. */
        CLOSED,
        /** Store is failing; requests take the rule's failure mode without asking. */
        OPEN,
        /** Open window elapsed; one request is allowed through to test the store. */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openNanos;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtNanos;
    private long trips;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openNanos = Math.max(0, openMillis) * 1_000_000L;
    }

    /**
     * Whether this request may spend a round trip on the store. A half-open transition grants exactly
     * one probe because {@code HALF_OPEN} is sticky until that request reports back.
     */
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.HALF_OPEN) {
            return false;
        }
        if (System.nanoTime() - openedAtNanos >= openNanos) {
            state = State.HALF_OPEN;
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
        }
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            if (state != State.OPEN) {
                trips++;
            }
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
            consecutiveFailures = 0;
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long trips() {
        return trips;
    }
}
