package com.teja.pocs.retry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Many checkout clients calling the flaky payment provider at the same time.
 * HOW LONG a client waits between attempts is the retry STRATEGY (passed as the first argument).
 *
 * Step 3: "immediate"  -> retry with no wait at all (the retry storm)
 * Step 4: "fixed"      -> always wait exactly 1 second (synchronized waves)
 * Step 5: "exponential"-> wait 100, 200, 400, 800 ... ms, capped at 2s (still synchronized)
 * Step 6: "jitter"     -> wait a RANDOM time between 0 and the exponential delay ("full jitter")
 * Step 7: "smart"      -> jitter + only retry transient errors, honor Retry-After, 10s time budget
 */
public class RetryClients {

    static final int CLIENTS = 50;
    static final int MAX_ATTEMPTS = 2000;                  // safety limit so nothing runs forever
    static final long BASE_DELAY_MS = 100;                // first backoff wait
    static final long MAX_DELAY_MS = 2000;                // never wait longer than this (the "cap")
    static final long TIME_BUDGET_MS = 10_000;            // Step 7: give up after 10s in total
    static final int INVALID_CLIENTS = 5;                 // Step 7: the first 5 clients send a bad amount
    static final HttpClient http = HttpClient.newHttpClient();

    static final AtomicInteger totalRequests = new AtomicInteger();
    static final AtomicInteger maxAttemptsByOneClient = new AtomicInteger();
    static final AtomicInteger stoppedOn400 = new AtomicInteger();      // Step 7 outcomes
    static final AtomicInteger outOfTime = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String strategy = args.length > 0 ? args[0] : "immediate";
        System.out.println(CLIENTS + " clients, strategy = " + strategy);

        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CLIENTS);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < CLIENTS; i++) {
            int clientNo = i;
            new Thread(() -> {
                try {
                    startLine.await();
                    boolean ok = strategy.equals("smart")
                            ? payWithPolicy(clientNo < INVALID_CLIENTS ? -1 : 4999)   // Step 7
                            : payWithRetries(strategy);                                // Steps 3-6
                    if (ok) succeeded.incrementAndGet();
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
        if (strategy.equals("smart")) {
            System.out.println("Stopped on 400 (no retry): " + stoppedOn400.get());
            System.out.println("Gave up (time budget)    : " + outOfTime.get());
        }
        System.out.println("Total requests sent      : " + totalRequests.get());
        System.out.println("Most attempts by 1 client: " + maxAttemptsByOneClient.get());
        System.out.println("Time until all finished  : " + tookMs + " ms");
    }

    /** Steps 3-6: call the provider, and on any non-200 wait (per strategy) and try again. */
    static boolean payWithRetries(String strategy) throws Exception {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            totalRequests.incrementAndGet();
            int status = call(4999).statusCode();
            if (status == 200) {
                maxAttemptsByOneClient.accumulateAndGet(attempt, Math::max);
                return true;
            }
            Thread.sleep(delayBeforeRetry(strategy, attempt));   // <-- the only thing that changes per step
        }
        maxAttemptsByOneClient.accumulateAndGet(MAX_ATTEMPTS, Math::max);
        return false;
    }

    /**
     * Step 7: a production-style retry policy.
     *   200            -> done
     *   400            -> the request itself is wrong: NEVER retry
     *   429            -> wait exactly what the server asks (Retry-After header)
     *   503 / network  -> transient: wait with exponential backoff + full jitter
     *   time budget    -> if the next wait would pass the 10s budget, give up
     */
    static boolean payWithPolicy(int amountCents) throws Exception {
        long deadline = System.currentTimeMillis() + TIME_BUDGET_MS;

        for (int attempt = 1; ; attempt++) {
            totalRequests.incrementAndGet();
            maxAttemptsByOneClient.accumulateAndGet(attempt, Math::max);

            long waitMs;
            try {
                HttpResponse<Void> response = call(amountCents);
                int status = response.statusCode();

                if (status == 200) return true;
                if (status == 400) {                                       // permanent: retrying can't help
                    stoppedOn400.incrementAndGet();
                    return false;
                }
                if (status == 429) {                                       // server says when to come back
                    waitMs = response.headers().firstValue("Retry-After")
                            .map(seconds -> Long.parseLong(seconds) * 1000)
                            .orElse(jitterDelay(attempt));
                } else {                                                   // 503 or other 5xx: transient
                    waitMs = jitterDelay(attempt);
                }
            } catch (IOException networkError) {                           // refused / timed out: transient
                waitMs = jitterDelay(attempt);
            }

            if (System.currentTimeMillis() + waitMs > deadline) {          // out of time: stop cleanly
                outOfTime.incrementAndGet();
                return false;
            }
            Thread.sleep(waitMs);
        }
    }

    /** How long to wait after a failed attempt (Steps 3-6). */
    static long delayBeforeRetry(String strategy, int attempt) {
        switch (strategy) {
            case "immediate":
                return 0;                                   // Step 3: no wait at all
            case "fixed":
                return 1000;                                // Step 4: always exactly 1 second
            case "exponential":
                return exponentialDelay(attempt);           // Step 5: 100, 200, 400, 800 ... capped
            case "jitter":
                return jitterDelay(attempt);                // Step 6: random 0..delay
            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }

    /** base * 2^(attempt-1), but never more than the cap.  attempt 1 -> 100, 2 -> 200, 3 -> 400 ... */
    static long exponentialDelay(int attempt) {
        long delay = BASE_DELAY_MS * (1L << Math.min(attempt - 1, 20));   // 1L << n  is  2^n
        return Math.min(delay, MAX_DELAY_MS);
    }

    /** Full jitter: a random wait between 0 and the exponential delay. */
    static long jitterDelay(int attempt) {
        return ThreadLocalRandom.current().nextLong(exponentialDelay(attempt) + 1);
    }

    static HttpResponse<Void> call(int amountCents) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:8090/charge?amountCents=" + amountCents))
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding());
    }
}