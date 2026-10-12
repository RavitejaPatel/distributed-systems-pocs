package com.teja.pocs.breaker;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The "Catalog API" (App Service in Microsoft's diagram).
 *   Goal 3: CircuitBreaker (fail fast when the store keeps failing).
 *   Goal 4: ResponseCache fallback (serve the last good answer, marked STALE).
 *   GET /catalog/{id} -> reads the product from ThrottledStore.
 * Like the Cosmos DB SDK, it retries a 429 after "x-ms-retry-after-ms" (max 3 retries).
 */
public class CatalogApi {

    private static final String STORE_URL = System.getenv().getOrDefault("STORE_URL", "http://localhost:8091");
    private static final int MAX_RETRIES = 3;
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))                      // give up connecting after 2s
        .build();

    // Step 4: counters for the once-per-second log line (Atomic = safe across 50 threads)
    static final AtomicInteger requests = new AtomicInteger();      // user requests received
    static final AtomicInteger ok = new AtomicInteger();            // answered 200
    static final AtomicInteger failed = new AtomicInteger();        // answered non-200
    static final AtomicInteger storeCalls = new AtomicInteger();    // calls made TO the store (incl. retries)
    static final AtomicLong totalLatencyMs = new AtomicLong();      // sum of user wait times
    static final AtomicInteger shortCircuited = new AtomicInteger(); // Goal 3: blocked by the breaker (no store call)
    static final AtomicInteger fromCache = new AtomicInteger();      // Goal 4: answered from the cache (stale)

    // Goal 3: ONE breaker shared by all 50 threads. Trips after 5 failures in a row.
    static final CircuitBreaker breaker = new CircuitBreaker(5);
    // Goal 4: last good answer per product, used as fallback
    static final ResponseCache cache = new ResponseCache();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8092"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/catalog/", CatalogApi::handle);
        server.setExecutor(Executors.newFixedThreadPool(50));       // Step 5: 50 threads (each may sleep while retrying)
        server.start();
        System.out.println("Catalog API on http://localhost:" + port + "/catalog/{id}   store = " + STORE_URL + "   (circuit breaker + cache fallback)");

        Executors.newSingleThreadScheduledExecutor()                // Step 5: print stats every second
                .scheduleAtFixedRate(CatalogApi::printSecond, 1, 1, TimeUnit.SECONDS);
    }

    /** Arrow 1 (in): a user calls GET /catalog/{id}. Arrow 4 (out): we answer the user. */
    private static void handle(HttpExchange ex) throws IOException {
        long start = System.currentTimeMillis();
        requests.incrementAndGet();
        String id = ex.getRequestURI().getPath().substring("/catalog/".length());   // "/catalog/sku-1" -> "sku-1"

        int status;
        String body;
        if (!breaker.allowRequest()) {                               // Goal 3: OPEN -> don't call the store
            shortCircuited.incrementAndGet();
            status = 503;
            body = "{\"error\":\"circuit open, try again later\"}";
        } else {
            try {
                HttpResponse<String> res = getFromStore(id);         // arrows 2 + 3: ask the store (with retries)
                if (res.statusCode() == 429) {                       // still throttled after all retries
                    breaker.recordFailure();
                    status = 503;
                    body = "{\"error\":\"store is busy, try again later\"}";
                } else {
                    breaker.recordSuccess();                         // 200 or 404: the store is healthy
                    status = res.statusCode();
                    body = res.body();
                    if (status == 200) cache.put(id, body);          // Goal 4: remember the last good answer
                }
            } catch (Exception e) {
                breaker.recordFailure();                             // store down / not reachable
                status = 503;
                body = "{\"error\":\"store unavailable\"}";
            }
        }

        // Goal 4: FALLBACK. Store not usable (503)? Serve the last good answer if we have one.
        if (status == 503) {
            var cached = cache.get(id);
            if (cached.isPresent()) {
                fromCache.incrementAndGet();
                status = 200;
                body = cached.get().body();
                ex.getResponseHeaders().add("X-Cache", "STALE");    // tell the caller it's not fresh
                ex.getResponseHeaders().add("X-Cache-Age-Ms", String.valueOf(cached.get().ageMs()));
            }
        }

        if (status == 200) ok.incrementAndGet(); else failed.incrementAndGet();
        totalLatencyMs.addAndGet(System.currentTimeMillis() - start);
        reply(ex, status, body);                                     // ONE reply at the end
    }

    /** Arrow 2 (out) / 3 (back). Step 2: on 429 wait "x-ms-retry-after-ms" and retry (max 3 retries). */
    private static HttpResponse<String> getFromStore(String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()               // built ONCE, reused for every attempt
            .uri(URI.create(STORE_URL + "/products/" + id))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();

        HttpResponse<String> res = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {  // attempts 0,1,2,3 = 1 call + 3 retries
            storeCalls.incrementAndGet();
            res = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 429) return res;                 // 200 / 404: done
            long waitMs = res.headers().firstValueAsLong("x-ms-retry-after-ms").orElse(100);
            if (attempt < MAX_RETRIES) Thread.sleep(waitMs);         // wait as the store asked (not after the last try)
        }
        return res;                                                  // still 429 after all retries
    }

    /** Step 4: once per second print what happened, then reset. Quiet when no traffic. */
    static void printSecond() {
        int r = requests.getAndSet(0);                               // read AND reset in one step
        if (r == 0) return;
        long avg = totalLatencyMs.getAndSet(0) / r;
        System.out.printf("[api] state=%s  requests=%d  ok=%d  failed(503)=%d  shortCircuited=%d  fromCache=%d  storeCalls=%d  avgLatency=%dms%n",
                breaker.getState(), r, ok.getAndSet(0), failed.getAndSet(0), shortCircuited.getAndSet(0),
                fromCache.getAndSet(0), storeCalls.getAndSet(0), avg);
    }

    /** Helper: writes status + JSON body back to whoever called us. */
    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}