package com.teja.pocs.idempotency;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Step 7b: 10 identical requests (same key) at the SAME instant -> exactly 1 charge. */
public class ConcurrentRetryDemo {

    public static void main(String[] args) throws Exception {
        PaymentService server = new PaymentService();
        String key = UUID.randomUUID().toString();       // one purchase = one key, shared by all 10 copies
        int copies = 10;

        CountDownLatch startLine = new CountDownLatch(1); // the "starting gun"
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < copies; i++) {
            results.add(pool.submit(() -> {
                startLine.await();                        // every thread waits here...
                return server.payIdempotent(key, "cust-42", 4999);
            }));
        }

        System.out.println("Releasing " + copies + " requests with key " + key + " at the same instant...");
        startLine.countDown();                            // ...and all start together

        Set<String> paymentIds = new TreeSet<>();
        for (Future<String> f : results) paymentIds.add(f.get());
        pool.shutdown();

        System.out.println("Distinct payment ids returned: " + paymentIds.size() + "  " + paymentIds);
        System.out.println("Payments in MySQL for cust-42: " + NaiveRetryDemo.countPayments("cust-42"));
    }
}