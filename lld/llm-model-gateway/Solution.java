import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/*
 * LLM Model Gateway / Router — LLD.
 *
 * Two Strategy hierarchies (ModelProvider, RoutingStrategy) plus a pipeline
 * (rate-limit -> cache -> route -> call -> fallback -> account). Adding a
 * provider, routing policy, or cache type = adding a class (Open/Closed).
 *
 * Single-node and thread-safe. The distributed version swaps in Redis-backed
 * cache and token counters behind the same interfaces. See DESIGN.md.
 */

// ── Request / Response value objects ────────────────────────────────────────
class Request {
    final String tenant;
    final String prompt;
    final String requestedModel;   // may be null -> router decides
    final int estimatedInputTokens;

    Request(String tenant, String prompt, String requestedModel, int estimatedInputTokens) {
        this.tenant = tenant;
        this.prompt = prompt;
        this.requestedModel = requestedModel;
        this.estimatedInputTokens = estimatedInputTokens;
    }
}

class Response {
    final String text;
    final String servedByModel;
    final int inputTokens;
    final int outputTokens;

    Response(String text, String servedByModel, int inputTokens, int outputTokens) {
        this.text = text;
        this.servedByModel = servedByModel;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }
}

// Thrown by a provider when it fails transiently (timeout, 5xx, rate-limited).
class ProviderException extends RuntimeException {
    ProviderException(String msg) { super(msg); }
}

// ── Strategy #1: ModelProvider (a backend behind a uniform contract) ────────
interface ModelProvider {
    Response complete(Request req);   // may throw ProviderException
    String name();
    double costPerKToken();           // for cost-based routing
    long typicalLatencyMs();          // for latency-based routing
}

// Example providers. Real ones would call an external API or self-hosted model.
class OpenAIProvider implements ModelProvider {
    @Override public Response complete(Request req) {
        return new Response("[openai answer]", "openai-model", req.estimatedInputTokens, 120);
    }
    @Override public String name() { return "openai"; }
    @Override public double costPerKToken() { return 0.010; }
    @Override public long typicalLatencyMs() { return 800; }
}

class AnthropicProvider implements ModelProvider {
    @Override public Response complete(Request req) {
        return new Response("[anthropic answer]", "anthropic-model", req.estimatedInputTokens, 130);
    }
    @Override public String name() { return "anthropic"; }
    @Override public double costPerKToken() { return 0.012; }
    @Override public long typicalLatencyMs() { return 700; }
}

class SelfHostedProvider implements ModelProvider {
    @Override public Response complete(Request req) {
        return new Response("[self-hosted answer]", "self-hosted", req.estimatedInputTokens, 110);
    }
    @Override public String name() { return "self-hosted"; }
    @Override public double costPerKToken() { return 0.002; }
    @Override public long typicalLatencyMs() { return 1200; }
}

// ── Strategy #2: RoutingStrategy (pick a provider for a request) ────────────
interface RoutingStrategy {
    ModelProvider select(Request req, List<ModelProvider> providers);
}

class CostOptimizedRouting implements RoutingStrategy {
    @Override public ModelProvider select(Request req, List<ModelProvider> providers) {
        ModelProvider best = null;
        for (ModelProvider p : providers) {
            if (best == null || p.costPerKToken() < best.costPerKToken()) best = p;
        }
        return best;
    }
}

class LatencyOptimizedRouting implements RoutingStrategy {
    @Override public ModelProvider select(Request req, List<ModelProvider> providers) {
        ModelProvider best = null;
        for (ModelProvider p : providers) {
            if (best == null || p.typicalLatencyMs() < best.typicalLatencyMs()) best = p;
        }
        return best;
    }
}

class FixedModelRouting implements RoutingStrategy {
    @Override public ModelProvider select(Request req, List<ModelProvider> providers) {
        for (ModelProvider p : providers) {
            if (p.name().equals(req.requestedModel)) return p;
        }
        // Fall back to the first provider if the requested one isn't found.
        return providers.isEmpty() ? null : providers.get(0);
    }
}

// ── Token-based rate limiter (reuses the Rate Limiter LLD, cost = tokens) ───
class TokenRateLimiter {
    private final long capacity;          // max tokens per window
    private final double refillPerMs;     // tokens restored per ms
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();
    // bucket = [tokensAvailable, lastRefillMs]

    TokenRateLimiter(long capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerMs = refillPerSecond / 1000.0;
    }

    // Returns true if `tokens` are available for this key (and consumes them).
    boolean allow(String key, long tokens) {
        long now = System.currentTimeMillis();
        long[] b = buckets.computeIfAbsent(key, k -> new long[]{capacity, now});
        synchronized (b) {
            long elapsed = now - b[1];
            double refilled = Math.min(capacity, b[0] + elapsed * refillPerMs);
            b[1] = now;
            if (refilled >= tokens) {
                b[0] = (long) (refilled - tokens);
                return true;
            }
            b[0] = (long) refilled;
            return false;
        }
    }
}

// ── Response cache (Strategy: exact vs semantic) ────────────────────────────
interface ResponseCache {
    Response get(Request req);      // null on miss
    void put(Request req, Response resp);
}

// Exact-match cache keyed by tenant + prompt (permission-aware via tenant).
class ExactCache implements ResponseCache {
    private final ConcurrentHashMap<String, Response> store = new ConcurrentHashMap<>();
    private String key(Request req) { return req.tenant + "::" + req.prompt; }
    @Override public Response get(Request req) { return store.get(key(req)); }
    @Override public void put(Request req, Response resp) { store.put(key(req), resp); }
}

/*
 * SemanticCache (sketch): embed the prompt and return a cached response if a
 * prior prompt is within a similarity threshold. Keyed within a tenant scope so
 * one tenant's cached answer is never served to another (permission-aware).
 * Implemented here as an interface stub to keep the file self-contained; a real
 * version calls an embedding model + vector index. See NOTES.md.
 */
class SemanticCache implements ResponseCache {
    private final double threshold;
    SemanticCache(double threshold) { this.threshold = threshold; }
    @Override public Response get(Request req) {
        // Real impl: embed(req.prompt), nearest-neighbor within req.tenant scope,
        // return the hit's response if similarity >= threshold, else null.
        return null;
    }
    @Override public void put(Request req, Response resp) {
        // Real impl: embed(req.prompt), store (vector -> response) under tenant scope.
    }
}

// ── Usage accounting (observability: tokens, cost, provider) ────────────────
class UsageAccountant {
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> tokensByTenant = new ConcurrentHashMap<>();

    void record(Request req, Response resp) {
        totalInputTokens.addAndGet(resp.inputTokens);
        totalOutputTokens.addAndGet(resp.outputTokens);
        tokensByTenant
            .computeIfAbsent(req.tenant, k -> new AtomicLong())
            .addAndGet(resp.inputTokens + resp.outputTokens);
    }

    long tenantTokens(String tenant) {
        AtomicLong a = tokensByTenant.get(tenant);
        return a == null ? 0 : a.get();
    }
}

// ── The gateway (context / pipeline) ────────────────────────────────────────
class ModelGateway {
    private final List<ModelProvider> providers;
    private final RoutingStrategy router;
    private final TokenRateLimiter rateLimiter;
    private final ResponseCache cache;
    private final UsageAccountant accountant;
    private final int maxRetriesPerProvider;

    ModelGateway(List<ModelProvider> providers, RoutingStrategy router,
                 TokenRateLimiter rateLimiter, ResponseCache cache,
                 UsageAccountant accountant, int maxRetriesPerProvider) {
        this.providers = providers;
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.cache = cache;
        this.accountant = accountant;
        this.maxRetriesPerProvider = maxRetriesPerProvider;
    }

    Response complete(Request req) {
        // 1. Token-based rate limiting (the resource is tokens, not requests).
        if (!rateLimiter.allow(req.tenant, req.estimatedInputTokens)) {
            throw new RuntimeException("Rate limit exceeded for tenant " + req.tenant);
        }

        // 2. Cache lookup (exact or semantic behind the interface).
        Response cached = cache.get(req);
        if (cached != null) return cached;

        // 3. Route to a provider, then 4. call with retry + fallback.
        ModelProvider primary = router.select(req, providers);
        Response resp = callWithFallback(req, primary);

        // 5. Cache and 6. account.
        cache.put(req, resp);
        accountant.record(req, resp);
        return resp;
    }

    // Retry the chosen provider on transient errors, then fall back to others.
    private Response callWithFallback(Request req, ModelProvider primary) {
        List<ModelProvider> order = new ArrayList<>();
        if (primary != null) order.add(primary);
        for (ModelProvider p : providers) {
            if (p != primary) order.add(p);   // remaining providers as fallbacks
        }

        RuntimeException last = null;
        for (ModelProvider provider : order) {
            for (int attempt = 0; attempt <= maxRetriesPerProvider; attempt++) {
                try {
                    return provider.complete(req);
                } catch (ProviderException e) {
                    last = e;   // transient — retry, then fall through to next provider
                }
            }
        }
        throw new RuntimeException("All providers failed", last);
    }
}

// ── Factory: assemble a gateway from config ─────────────────────────────────
class GatewayFactory {
    enum Routing { COST, LATENCY, FIXED }

    static ModelGateway create(Routing routing) {
        List<ModelProvider> providers = Arrays.asList(
            new SelfHostedProvider(), new AnthropicProvider(), new OpenAIProvider());

        RoutingStrategy router;
        switch (routing) {
            case COST:    router = new CostOptimizedRouting();    break;
            case LATENCY: router = new LatencyOptimizedRouting(); break;
            case FIXED:   router = new FixedModelRouting();       break;
            default: throw new IllegalArgumentException("Unknown routing: " + routing);
        }

        TokenRateLimiter limiter = new TokenRateLimiter(1_000_000, 10_000); // tokens
        ResponseCache cache = new ExactCache();                             // or SemanticCache
        UsageAccountant accountant = new UsageAccountant();
        return new ModelGateway(providers, router, limiter, cache, accountant, 2);
    }
}

/*
 * Usage:
 *   ModelGateway gw = GatewayFactory.create(GatewayFactory.Routing.COST);
 *   Response r = gw.complete(new Request("tenantA", "Explain RAG", null, 1200));
 *
 * Adding a provider (Open/Closed):
 *   1. class MistralProvider implements ModelProvider { ... }
 *   2. add it to the providers list (or config)
 *   No change to ModelGateway, RoutingStrategy, or existing providers — and it is
 *   automatically available as a fallback target.
 *
 * Adding a routing policy (Open/Closed):
 *   1. class CapabilityRouting implements RoutingStrategy { ... }
 *   2. add a case to the factory
 *   No change to the gateway or providers.
 *
 * Concurrency: per-key token buckets guarded individually; accounting via atomics.
 * Distributed: swap ExactCache/SemanticCache and TokenRateLimiter for Redis-backed
 * implementations behind the same interfaces; the gateway is unchanged.
 */
