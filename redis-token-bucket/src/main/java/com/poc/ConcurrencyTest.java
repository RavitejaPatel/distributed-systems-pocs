package com.poc;

import redis.clients.jedis.Jedis;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrencyTest {
    public static void main(String[] args) throws InterruptedException {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.del("bucket:racetest");

            TokenBucketLimiter limiter = new TokenBucketLimiter(jedis, 5, 1.0); // capacity 5

            int threadCount = 20; // 20 concurrent requests, but only 5 tokens available
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            AtomicInteger allowedCount = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try (Jedis threadJedis = new Jedis("localhost", 6379)) {
                        TokenBucketLimiter threadLimiter = new TokenBucketLimiter(threadJedis, 5, 1.0);
                        if (threadLimiter.allowRequest("racetest")) {
                            allowedCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            pool.shutdown();

            System.out.println("Allowed: " + allowedCount.get() + " out of " + threadCount + " (capacity was 5)");
        }
    }
}