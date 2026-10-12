package com.teja.pocs.breaker;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A "fake Cosmos DB": a few products + a throughput limit (requests per second).
 *
 *   GET  /products/{id}            -> 200 product | 404 not found | 429 + x-ms-retry-after-ms (over the limit)
 *   GET  /admin/throughput         -> shows the current limit
 *   POST /admin/throughput?rps=50  -> changes the limit (= Cosmos "AddProvisionedThroughput")
 */
public class ThrottledStore {

    static final Map<String, String> PRODUCTS = Map.of(
        "sku-1", "{\"id\":\"sku-1\",\"name\":\"Product 1\"}",
        "sku-2", "{\"id\":\"sku-2\",\"name\":\"Product 2\"}",
        "sku-3", "{\"id\":\"sku-3\",\"name\":\"Product 3\"}"
    );

    static volatile int capacityPerSecond = 5;   // the limit
    static long currentSecond = 0;               // which second we're counting in
    static int usedThisSecond = 0;               // how many requests we've allowed in that second
    static int served = 0, throttled = 0;        // totals for the log line (printSecond)

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8091"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/products/", ThrottledStore::handleProduct);
        server.createContext("/admin/throughput", ThrottledStore::handleAdmin);
        server.setExecutor(Executors.newFixedThreadPool(16));       // 16 threads handle requests in parallel
        server.start();
        System.out.println("ThrottledStore (fake Cosmos DB) on http://localhost:" + port
                + "   capacity = " + capacityPerSecond + " requests/sec");

        // once per second: print what happened in the last second
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(ThrottledStore::printSecond, 1, 1, TimeUnit.SECONDS);
    }

    /** GET /products/{id}: check the limit FIRST, then look up the product. */
    private static void handleProduct(HttpExchange ex) throws IOException {
        long waitMs = tryAcquire();                                       // 1. ask the throttle
        if (waitMs > 0) {                                                 // over the limit -> 429
            ex.getResponseHeaders().add("x-ms-retry-after-ms", String.valueOf(waitMs));
            reply(ex, 429, "{\"error\":\"Request rate is large. Retry after " + waitMs + " ms\"}");
            return;
        }
        String id = ex.getRequestURI().getPath().substring("/products/".length());  // "/products/sku-1" -> "sku-1"
        String product = PRODUCTS.get(id);                                // JSON string, or null
        if (product == null) reply(ex, 404, "{\"error\":\"not found\"}");
        else                 reply(ex, 200, product);
    }

    /** Fixed 1-second window. Returns 0 = allowed, otherwise ms until the next second. */
    static synchronized long tryAcquire() {
        long now = System.currentTimeMillis();
        long second = now / 1000;

        if (second != currentSecond) {           // a new second started: reset
            currentSecond = second;
            usedThisSecond = 0;
        }
        if (usedThisSecond < capacityPerSecond) {
            usedThisSecond++;
            served++;
            return 0;                            // allowed
        } else {
            throttled++;
            return 1000 - (now % 1000);          // ms until the next second
        }
    }

    /** GET shows the limit; POST ?rps=N changes it (= Cosmos "AddProvisionedThroughput"). */
    static void handleAdmin(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {                    // only POST changes anything
            String query = ex.getRequestURI().getQuery();               // e.g. "rps=50", or null
            if (query == null || !query.startsWith("rps=")) {
                reply(ex, 400, "{\"error\":\"use POST /admin/throughput?rps=50\"}");
                return;                                                 // stop: nothing changed
            }
            int old = capacityPerSecond;
            capacityPerSecond = Integer.parseInt(query.substring(4));  // "rps=50" -> "50" -> 50
            System.out.println(">>> THROUGHPUT CHANGED: " + old + " -> " + capacityPerSecond + " requests/sec");
        }
        // GET, or a successful POST: report the current limit
        reply(ex, 200, "{\"capacityPerSecond\":" + capacityPerSecond + "}");
    }

    /** Once per second: print what happened, then reset the totals. Quiet when there is no traffic. */
    static synchronized void printSecond() {
        if (served + throttled == 0) return;
        System.out.printf("[store] capacity=%d/s   served=%d   throttled(429)=%d%n",
                capacityPerSecond, served, throttled);
        served = 0;
        throttled = 0;
    }

    /** Helper: writes status + JSON body back to whoever called us. */
    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}