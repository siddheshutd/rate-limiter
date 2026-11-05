package com.hld.ratelimiter.gateway.ratelimit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Store round-trip latency, in a fixed histogram, because the <10 ms promise needs a number attached.
 *
 * <p>Stage 1 spends every request on one read plus two writes; the p50/p99 here is the baseline stage 2
 * has to beat with a single script call. Measuring it is not decoration: a limiter in the request path
 * is only affordable if its own cost is known and bounded, and the answer to "why is p99 rising" is
 * either the store, the timeout, or the number of rules per request.
 *
 * <p>Bucketed counts rather than a stored sample list: keeping every observation to compute an exact
 * percentile is the same mistake as a sliding log — memory growing with request count.
 */
public final class LatencyRecorder {

    /** Upper bounds in microseconds. 10 ms is the budget, so the top buckets are where it is lost. */
    private static final long[] LIMITS_NANOS = {
            100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000, 25_000_000,
            50_000_000, 100_000_000
    };

    private final AtomicLongArray buckets = new AtomicLongArray(LIMITS_NANOS.length + 1);
    private final LongAdder count = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong maxNanos = new AtomicLong();

    public void record(long elapsedNanos) {
        count.increment();
        totalNanos.add(elapsedNanos);
        maxNanos.accumulateAndGet(elapsedNanos, Math::max);
        int index = 0;
        while (index < LIMITS_NANOS.length && elapsedNanos > LIMITS_NANOS[index]) {
            index++;
        }
        buckets.incrementAndGet(index);
    }

    public long count() {
        return count.sum();
    }

    public double meanMillis() {
        long observations = count.sum();
        return observations == 0 ? 0 : totalNanos.sum() / (double) observations / 1_000_000;
    }

    public long maxMillis() {
        return maxNanos.get() / 1_000_000;
    }

    /** Nearest observed bucket upper bound; an estimate on purpose, which is what a histogram is for. */
    public double percentileMillis(double percentile) {
        long observations = count.sum();
        if (observations == 0) {
            return 0;
        }
        long target = (long) Math.ceil(observations * percentile / 100.0);
        long cumulative = 0;
        for (int i = 0; i < LIMITS_NANOS.length; i++) {
            cumulative += buckets.get(i);
            if (cumulative >= target) {
                return LIMITS_NANOS[i] / 1_000_000.0;
            }
        }
        return maxMillis();
    }

    public long[] bucketCounts() {
        long[] out = new long[buckets.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = buckets.get(i);
        }
        return out;
    }

    public long[] bucketLimitsMicros() {
        long[] out = new long[LIMITS_NANOS.length + 1];
        for (int i = 0; i < LIMITS_NANOS.length; i++) {
            out[i] = LIMITS_NANOS[i] / 1000;
        }
        out[LIMITS_NANOS.length] = -1;
        return out;
    }
}
