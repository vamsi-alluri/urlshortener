package com.urlshortener.user;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * The per-key creation allowance (issue #7, D15): an in-memory token bucket per API
 * Key — full when first seen, one slot spent per Short Link creation, one slot
 * refilled per slice of the hour. In-memory on purpose: the store is one node with
 * rare restarts (ADR-0005), and a reset allowance on restart is accepted. No lifetime
 * quota exists anywhere (Q18) — the allowance refills forever.
 *
 * <p>Keyed by the keyholder's {@code users.id}: a User holds exactly one API Key
 * (D8), so the identity is the same — and regenerating the key must not reset the
 * allowance.
 */
@Service
final class CreationRateLimiter {

    private final long capacity;

    private final long refillIntervalNanos;

    /** One bucket per keyholder, built on first sight. Never evicted: the one node's Users are few. */
    private final Map<Long, TokenBucket> allowances = new ConcurrentHashMap<>();

    CreationRateLimiter(RateLimitProperties config) {
        this.capacity = config.creationsPerHour();
        this.refillIntervalNanos = Duration.ofHours(1).dividedBy(capacity).toNanos();
    }

    /**
     * Takes one creation slot for the keyholder — the allowance is full when the key is
     * first seen. Empty: the creation may proceed. Present: the creation is denied,
     * carrying the whole seconds until the next slot refills — the Retry-After signal
     * (the plan's item 2).
     */
    Optional<Denial> tryAcquireCreationSlot(long keyholderId) {
        return allowances.computeIfAbsent(keyholderId, keyholder -> new TokenBucket()).takeSlot();
    }

    /** A denied creation: the whole seconds to wait for the next refilled slot. */
    record Denial(long retryAfterSeconds) {
    }

    /**
     * One API Key's allowance: D15's token bucket, spent and refilled one slot at a
     * time. Refill is lazy — computed on each visit from the monotonic clock, so an
     * idle key returns to a full allowance without anyone touching it in between.
     */
    private final class TokenBucket {

        private long slots = capacity;

        private long lastRefill = System.nanoTime();

        /**
         * Takes one slot if the allowance holds one. Synchronized because the one node
         * serves concurrent creations — the map's {@code computeIfAbsent} only guards
         * the bucket's creation, not its use.
         */
        private synchronized Optional<Denial> takeSlot() {
            long now = System.nanoTime();
            long refills = (now - lastRefill) / refillIntervalNanos;
            if (refills > 0) {
                slots = Math.min(capacity, slots + refills);
                lastRefill += refills * refillIntervalNanos;
            }
            if (slots > 0) {
                slots--;
                return Optional.empty();
            }
            // no refill tick has passed, so the next slot is one partial tick away
            long nanosToNextSlot = lastRefill + refillIntervalNanos - now;
            return Optional.of(new Denial(Math.ceilDiv(nanosToNextSlot, 1_000_000_000L)));
        }
    }
}
