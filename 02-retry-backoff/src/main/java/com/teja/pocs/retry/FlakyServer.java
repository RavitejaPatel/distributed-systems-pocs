package com.teja.pocs.retry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * Step 2: a "payment provider" that is DOWN for the first 3 seconds, then healthy but with LIMITED capacity.
 *  - first 3s after the first request : every request -> 503 (outage)
 *  - after that                       : at most 10 requests per 250ms slice succeed (200), extra -> 503 (overloaded)
 * When clients go quiet for 2s, it prints a bar chart of requests per 250ms slice:  o = 200 OK,  x = 503
 */
public class FlakyServer {

    static final int PORT = 8090;
    static final long OUTAGE_MS = 3000;          // how long the "outage" lasts
    static final int CAPACITY_PER_SLICE = 10;    // healthy capacity: 10 requests per 250ms (= 40/sec)
    static final long SLICE_MS = 250;

    static long firstRequestAt = 0;              // the clock starts at the first request of a run
    static long lastRequestAt = 0;
    static final TreeMap<Long, int[]> slices = new TreeMap<>();   // slice index -> {ok, fail}

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/charge", FlakyServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("Flaky payment provider on http://localhost:" + PORT + "/charge");
        System.out.println("Outage for the first " + OUTAGE_MS + " ms of each run, then capacity "
                + CAPACITY_PER_SLICE + " requests per " + SLICE_MS + " ms. (Ctrl+C to stop)");

        // Report thread: when clients have been quiet for 2s, print the chart and reset for the next run
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                FlakyServer::printReportIfIdle, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static void handle(HttpExchange ex) throws java.io.IOException {
        boolean ok = record(System.currentTimeMillis());
        byte[] body = (ok ? "OK" : "503 Service Unavailable").getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(ok ? 200 : 503, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    /** Decides 200 vs 503 for a request arriving at time 'now', and counts it. */
    static synchronized boolean record(long now) {
        if (firstRequestAt == 0) firstRequestAt = now;
        lastRequestAt = now;
        long elapsed = now - firstRequestAt;
        int[] counts = slices.computeIfAbsent(elapsed / SLICE_MS, k -> new int[2]);

        boolean ok = elapsed >= OUTAGE_MS && counts[0] < CAPACITY_PER_SLICE;
        if (ok) counts[0]++; else counts[1]++;
        return ok;
    }

    static synchronized void printReportIfIdle() {
        if (firstRequestAt == 0 || System.currentTimeMillis() - lastRequestAt < 2000) return;

        int total = 0, okTotal = 0;
        System.out.println("\n=== Requests per " + SLICE_MS + " ms  (o = 200 OK, x = 503) ===");
        for (long slice = 0; slice <= slices.lastKey(); slice++) {        // every slice, even empty ones
            int[] c = slices.getOrDefault(slice, new int[2]);
            int ok = c[0], fail = c[1];
            total += ok + fail; okTotal += ok;
            String phase = slice * SLICE_MS < OUTAGE_MS ? "DOWN" : "up  ";
            System.out.printf("%5.2fs %s %4d | %s%s%n", slice * SLICE_MS / 1000.0, phase, ok + fail,
                    "o".repeat(ok), "x".repeat(Math.min(fail, 120)) + (fail > 120 ? "..." : ""));
        }
        System.out.printf("Total requests: %d   succeeded: %d   rejected: %d%n%n", total, okTotal, total - okTotal);
        slices.clear();
        firstRequestAt = 0;
    }
}