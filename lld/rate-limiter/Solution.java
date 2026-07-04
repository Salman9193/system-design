import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Rate Limiter — LLD with the Strategy pattern.
 *
 * The design supports multiple interchangeable algorithms behind one interface.
 * Adding a new algorithm = adding a new class (Open/Closed principle) — no
 * existing class changes.
 *
 * Everything here is single-node and thread-safe. The distributed version moves
 * the per-key state into a shared store (Redis) with atomic operations; the
 * interface is unchanged. See DESIGN.md.
 */

// ── The abstraction every algorithm implements ──────────────────────────────
interface RateLimitStrategy {
    boolean allow(String key);   // true = within limit, false = reject
}

// ── The context: holds a strategy, delegates to it (Dependency Inversion) ───
class RateLimiter {
    private final RateLimitStrategy strategy;

    RateLimiter(RateLimitStrategy strategy) {
        this.strategy = strategy;   // depends on the interface, not a concretion
    }

    boolean allow(String key) {
        return strategy.allow(key);
    }
}

// ── Strategy 1: Token Bucket ────────────────────────────────────────────────
//
// Tokens refill at a fixed rate up to a capacity. Each request consumes one
// token; if the bucket is empty, reject. Allows controlled bursts (up to
// capacity) while enforcing a long-run average rate. O(1) time, O(1) memory
// per key. This is the most common production choice.
class TokenBucketStrategy implements RateLimitStrategy {
    private final long capacity;        // max tokens (max burst)
    private final double refillPerMs;   // tokens added per millisecond
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    TokenBucketStrategy(long capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerMs = refillPerSecond / 1000.0;
    }

    // Per-key state. Guarded by synchronizing on the bucket instance so
    // concurrent requests for the SAME key update tokens atomically.
    private static class Bucket {
        double tokens;
        long lastRefillMs;
        Bucket(double tokens, long now) { this.tokens = tokens; this.lastRefillMs = now; }
    }

    @Override
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        // computeIfAbsent lazily creates the bucket, full, on first sight of key
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));

        synchronized (b) {                       // atomic per-key update
            // Refill based on elapsed time since last refill
            long elapsed = now - b.lastRefillMs;
            b.tokens = Math.min(capacity, b.tokens + elapsed * refillPerMs);
            b.lastRefillMs = now;

            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;                 // consume one token
                return true;
            }
            return false;                        // empty → reject
        }
    }
}

// ── Strategy 2: Fixed Window ────────────────────────────────────────────────
//
// Count requests per fixed clock window. Simple and O(1) memory, but suffers
// the BOUNDARY BURST problem: a client can send `limit` requests at the end of
// one window and `limit` more at the start of the next — up to 2× the limit
// across the boundary. See PROBLEM.md.
class FixedWindowStrategy implements RateLimitStrategy {
    private final int limit;
    private final long windowMs;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowStrategy(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    private static class Window {
        long windowStart;
        int count;
        Window(long start) { this.windowStart = start; this.count = 0; }
    }

    @Override
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(key, k -> new Window(now));

        synchronized (w) {
            // If we've rolled into a new window, reset the counter
            if (now - w.windowStart >= windowMs) {
                w.windowStart = now;
                w.count = 0;
            }
            if (w.count < limit) {
                w.count++;
                return true;
            }
            return false;
        }
    }
}

// ── Strategy 3: Sliding Window Counter ──────────────────────────────────────
//
// Fixes Fixed Window's boundary burst at O(1) memory by weighting the previous
// window's count by how much of it still overlaps the rolling window. Near-exact
// without storing every timestamp (which the Sliding Window LOG variant does at
// O(N) memory).
class SlidingWindowCounterStrategy implements RateLimitStrategy {
    private final int limit;
    private final long windowMs;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    SlidingWindowCounterStrategy(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    private static class Counter {
        long currentWindowStart;
        int currentCount;
        int previousCount;
        Counter(long start) { this.currentWindowStart = start; }
    }

    @Override
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Counter c = counters.computeIfAbsent(key, k -> new Counter(now));

        synchronized (c) {
            long elapsed = now - c.currentWindowStart;

            if (elapsed >= windowMs) {
                // Advance the window. If we skipped more than one window, the
                // previous count is stale → treat as 0.
                if (elapsed < 2 * windowMs) {
                    c.previousCount = c.currentCount;
                } else {
                    c.previousCount = 0;
                }
                c.currentCount = 0;
                c.currentWindowStart = now - (elapsed % windowMs);
                elapsed = now - c.currentWindowStart;
            }

            // Weight the previous window by the fraction still inside the
            // rolling window. e.g. 30% into the current window → 70% of the
            // previous window still counts.
            double prevWeight = (double) (windowMs - elapsed) / windowMs;
            double estimated = c.previousCount * prevWeight + c.currentCount;

            if (estimated < limit) {
                c.currentCount++;
                return true;
            }
            return false;
        }
    }
}

// ── Factory: build the right strategy from config (Factory pattern) ─────────
class RateLimiterFactory {
    enum Type { TOKEN_BUCKET, FIXED_WINDOW, SLIDING_WINDOW_COUNTER }

    static RateLimiter create(Type type, int limit, long windowMs) {
        RateLimitStrategy strategy;
        switch (type) {
            case TOKEN_BUCKET:
                // capacity = limit, refill rate = limit per window
                strategy = new TokenBucketStrategy(limit, limit * 1000.0 / windowMs);
                break;
            case FIXED_WINDOW:
                strategy = new FixedWindowStrategy(limit, windowMs);
                break;
            case SLIDING_WINDOW_COUNTER:
                strategy = new SlidingWindowCounterStrategy(limit, windowMs);
                break;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
        return new RateLimiter(strategy);
    }
}

/*
 * Usage:
 *   RateLimiter limiter =
 *       RateLimiterFactory.create(RateLimiterFactory.Type.TOKEN_BUCKET, 100, 60_000);
 *   if (limiter.allow(userId)) { handle(request); }
 *   else { reject(request); }   // HTTP 429 Too Many Requests
 *
 * Adding a new algorithm (Open/Closed in action):
 *   1. class LeakyBucketStrategy implements RateLimitStrategy { ... }
 *   2. add a case to the factory
 *   No change to RateLimiter, RateLimitStrategy, or any existing strategy.
 *
 * Concurrency:
 *   Per-key state is created via ConcurrentHashMap.computeIfAbsent (atomic),
 *   and each per-key update synchronizes on that key's own bucket/window/counter
 *   object — so different keys never contend, and same-key requests are serialized
 *   only for the brief update. For higher throughput, the synchronized block can
 *   be replaced with atomic/CAS operations.
 *
 * Memory:
 *   The per-key maps grow with distinct keys. In production, evict stale keys via
 *   a TTL or a periodic sweep (e.g. remove buckets untouched for > 10 minutes) so
 *   memory stays bounded.
 */
