package com.teja.pocs.breaker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated shoppers. Sends a FIXED number of requests per second to the Catalog API
 * (like real users: they keep arriving even if the site is slow), then prints one line per second.
 *
 *   args: [requestsPerSecond] [seconds] [url]     default: 20 10 http://localhost:8092/catalog/sku-1
 */
public class LoadGen {

    public static void main(String[] args) throws Exception {
        // ---- Part 1: read args ----
        int rps     = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        String url  = args.length > 2 ? args[2] : "http://localhost:8092/catalog/sku-1";

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))          // long: CatalogApi may take ~2s while retrying
                .GET().build();

        // ---- Part 2: one bucket per second (Atomic: answers arrive on many threads) ----
        AtomicInteger[] okCount   = new AtomicInteger[seconds];
        AtomicInteger[] failCount = new AtomicInteger[seconds];
        AtomicLong[]    latency   = new AtomicLong[seconds];
        for (int s = 0; s < seconds; s++) {
            okCount[s]   = new AtomicInteger();
            failCount[s] = new AtomicInteger();
            latency[s]   = new AtomicLong();
        }

        // ---- Part 3: send loop (open-loop: never wait for answers) ----
        System.out.println("Sending " + rps + " requests/sec for " + seconds + "s to " + url);
        List<CompletableFuture<Void>> all = new ArrayList<>();
        long begin = System.currentTimeMillis();

        for (int s = 0; s < seconds; s++) {
            for (int i = 0; i < rps; i++) {
                long sendAt = begin + s * 1000L + i * 1000L / rps;   // spread evenly inside the second
                long sleep = sendAt - System.currentTimeMillis();
                if (sleep > 0) Thread.sleep(sleep);

                final int second = s;                                // lambdas need (effectively) final copies
                final long start = System.currentTimeMillis();
                all.add(http.sendAsync(req, HttpResponse.BodyHandlers.ofString())   // returns immediately
                        .handle((res, err) -> {                      // runs LATER, when the answer arrives
                            boolean success = err == null && res.statusCode() == 200;
                            if (success) okCount[second].incrementAndGet();
                            else         failCount[second].incrementAndGet();
                            latency[second].addAndGet(System.currentTimeMillis() - start);
                            return null;
                        }));
            }
        }

        // ---- Part 4: wait for every answer, then report ----
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();

        int totalOk = 0, totalFailed = 0;
        System.out.println("\nsecond   ok  failed  avgLatency");
        for (int s = 0; s < seconds; s++) {
            int ok = okCount[s].get(), f = failCount[s].get();
            totalOk += ok;
            totalFailed += f;
            System.out.printf("%5d  %4d  %6d  %7dms%n", s + 1, ok, f, latency[s].get() / Math.max(1, ok + f));
        }
        System.out.printf("TOTAL  ok=%d  failed=%d  (%.0f%% success)%n",
                totalOk, totalFailed, 100.0 * totalOk / Math.max(1, totalOk + totalFailed));
    }
}