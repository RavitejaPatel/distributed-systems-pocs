package com.teja.pocs.retry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Many checkout clients calling the flaky payment provider at the same time.
 * Each client keeps retrying until it gets 200 (or hits MAX_ATTEMPTS).
 * HOW LONG a client waits between attempts is the retry STRATEGY (passed as the first argument).
 *
 * Step 3: "immediate"  -> retry with no wait at all (the retry storm)
 * Step 4: "fixed"      -> always wait exactly 1 second (synchronized waves)
 * Step 5: "exponential"-> wait 100, 200, 400, 800 ... ms, capped at 2s (still synchronized)
 * Step 6: "jitter"     -> wait a RANDOM time between 0 and the exponential delay ("full jitter")
 */
public class RetryClients {

    static final long BASE_DELAY_MS = 100;                // first backoff wait
    static final long MAX_DELAY_MS = 2000;                // never wait longer than this (the "cap")

    static final int CLIENTS = 50;
    static final int MAX_ATTEMPTS = 2000;                  // safety limit so nothing runs forever
    static final URI PROVIDER = URI.create("http://localhost:8090/charge");
    static final HttpClient http = HttpClient.newHttpClient();

    static final AtomicInteger totalRequests = new AtomicInteger();
    static final AtomicInteger maxAttemptsByOneClient = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String strategy = args.length > 0 ? args[0] : "immediate";
        System.out.println(CLIENTS + " clients, strategy = " + strategy);

        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CLIENTS);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < CLIENTS; i++) {
            new Thread(() -> {
                try {
                    startLine.await();
                    if (payWithRetries(strategy)) succeeded.incrementAndGet();
                } catch (Exception e) {
                    System.out.println("client error: " + e);
                } finally {
                    finished.countDown();
                }
            }).start();
        }

        long start = System.currentTimeMillis();
        startLine.countDown();                              // all clients start together
        finished.await();
        long tookMs = System.currentTimeMillis() - start;

        System.out.println("Clients succeeded        : " + succeeded.get() + " / " + CLIENTS);
        System.out.println("Total requests sent      : " + totalRequests.get());
        System.out.println("Most attempts by 1 client: " + maxAttemptsByOneClient.get());
        System.out.println("Time until all finished  : " + tookMs + " ms");
    }

    /** One client: call the provider, and on 503 wait (per strategy) and try again. */
    static boolean payWithRetries(String strategy) throws Exception {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            totalRequests.incrementAndGet();
            int status = call();
            if (status == 200) {
                maxAttemptsByOneClient.accumulateAndGet(attempt, Math::max);
                return true;
            }
            Thread.sleep(delayBeforeRetry(strategy, attempt));   // <-- the only thing that changes per step
        }
        maxAttemptsByOneClient.accumulateAndGet(MAX_ATTEMPTS, Math::max);
        return false;
    }

    /** How long to wait after a failed attempt. Steps 4-6 add new strategies here. */
    static long delayBeforeRetry(String strategy, int attempt) {
        switch (strategy) {
            case "immediate":
                return 0;  
            case "fixed":
                return 1000;                                 // Step 3: no wait at all
            case "exponential":
                return exponentialDelay(attempt);           // Step 5: 100, 200, 400, 800 ... capped
            case "jitter":
                return ThreadLocalRandom.current().nextLong(exponentialDelay(attempt) + 1);  // Step 6: random 0..delay
            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }

    /** base * 2^(attempt-1), but never more than the cap.  attempt 1 -> 100, 2 -> 200, 3 -> 400 ... */
    static long exponentialDelay(int attempt) {
        long delay = BASE_DELAY_MS * (1L << Math.min(attempt - 1, 20));   // 1L << n  is  2^n
        return Math.min(delay, MAX_DELAY_MS);
    }

    static int call() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(PROVIDER)
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}