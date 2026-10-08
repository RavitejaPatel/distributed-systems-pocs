package com.teja.pocs.retry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * A "payment provider" that is DOWN for the first 3 seconds, then healthy but with LIMITED capacity.
 *  - invalid request (amountCents <= 0)  : 400 Bad Request          (Step 7: never worth retrying)
 *  - first 3s after the first request    : 503 Service Unavailable  (outage)
 *  - after that, over capacity           : 429 Too Many Requests + "Retry-After: 1"  (Step 7)
 *  - otherwise                           : 200 OK  (at most 10 per 250ms slice)
 * When clients go quiet for 2s, it prints a chart per 250ms slice:  o = 200, x = 503, r = 429, ! = 400
 */
public class FlakyServer {

    static final int PORT = 8090;
    static final long OUTAGE_MS = 3000;          // how long the "outage" lasts
    static final int CAPACITY_PER_SLICE = 10;    // healthy capacity: 10 requests per 250ms (= 40/sec)
    static final long SLICE_MS = 250;

    static final int OK = 0, DOWN = 1, TOO_MANY = 2, BAD = 3;     // index into the per-slice counters
    static final int[] STATUS = { 200, 503, 429, 400 };
    static final String[] SYMBOL = { "o", "x", "r", "!" };

    static long firstRequestAt = 0;              // the clock starts at the first request of a run
    static long lastRequestAt = 0;
    static final TreeMap<Long, int[]> slices = new TreeMap<>();   // slice index -> counts per outcome

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
        int outcome = record(System.currentTimeMillis(), isValid(ex));
        if (outcome == TOO_MANY) ex.getResponseHeaders().add("Retry-After", "1");   // "come back in 1 second"
        byte[] body = ("HTTP " + STATUS[outcome]).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(STATUS[outcome], body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    /** A request is invalid if it says amountCents=0 or negative. No amount at all = valid (older steps). */
    static boolean isValid(HttpExchange ex) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return true;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals("amountCents")) return Integer.parseInt(kv[1]) > 0;
        }
        return true;
    }

    /** Decides the outcome for a request arriving at time 'now', and counts it. */
    static synchronized int record(long now, boolean valid) {
        if (firstRequestAt == 0) firstRequestAt = now;
        lastRequestAt = now;
        long elapsed = now - firstRequestAt;
        int[] counts = slices.computeIfAbsent(elapsed / SLICE_MS, k -> new int[4]);

        int outcome;
        if (!valid)                                   outcome = BAD;
        else if (elapsed < OUTAGE_MS)                 outcome = DOWN;
        else if (counts[OK] >= CAPACITY_PER_SLICE)    outcome = TOO_MANY;
        else                                          outcome = OK;
        counts[outcome]++;
        return outcome;
    }

    static synchronized void printReportIfIdle() {
        if (firstRequestAt == 0 || System.currentTimeMillis() - lastRequestAt < 2000) return;

        int[] totals = new int[4];
        System.out.println("\n=== Requests per " + SLICE_MS + " ms  (o = 200, x = 503, r = 429, ! = 400) ===");
        for (long slice = 0; slice <= slices.lastKey(); slice++) {        // every slice, even empty ones
            int[] c = slices.getOrDefault(slice, new int[4]);
            StringBuilder bar = new StringBuilder();
            int sum = 0;
            for (int i = 0; i < 4; i++) {
                totals[i] += c[i];
                sum += c[i];
                bar.append(SYMBOL[i].repeat(Math.min(c[i], 120)));
            }
            String phase = slice * SLICE_MS < OUTAGE_MS ? "DOWN" : "up  ";
            System.out.printf("%5.2fs %s %4d | %s%n", slice * SLICE_MS / 1000.0, phase, sum, bar);
        }
        System.out.printf("Total: %d   200: %d   503: %d   429: %d   400: %d%n%n",
                totals[0] + totals[1] + totals[2] + totals[3], totals[OK], totals[DOWN], totals[TOO_MANY], totals[BAD]);
        slices.clear();
        firstRequestAt = 0;
    }
}