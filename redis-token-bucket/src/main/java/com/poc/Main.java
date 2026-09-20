package com.poc;

import redis.clients.jedis.Jedis;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.del("bucket:user123"); // start from a clean bucket

            TokenBucketLimiter limiter = new TokenBucketLimiter(jedis, 10, 2.0);

            System.out.println("--- t0: 3 requests ---");
            fireRequests(jedis, limiter, "user123", 3);

            Thread.sleep(1000);
            System.out.println("--- t1: 1 request ---");
            fireRequests(jedis, limiter, "user123", 1);

            Thread.sleep(1000);
            System.out.println("--- t2: 12 requests ---");
            fireRequests(jedis, limiter, "user123", 12);

            System.out.println("--- t3: idle, no requests ---");
            Thread.sleep(2000); // covers the idle second + the gap to t4

            System.out.println("--- t4: 4 requests ---");
            fireRequests(jedis, limiter, "user123", 4);
        }
    }

    private static void fireRequests(Jedis jedis, TokenBucketLimiter limiter, String userId, int count) {
        for (int i = 1; i <= count; i++) {
            boolean allowed = limiter.allowRequest(userId);
            String tokensLeft = jedis.hget("bucket:" + userId, "tokens");
            System.out.printf("Request %d: %-8s (tokens now: %s)%n",
                    i, allowed ? "ALLOWED" : "REJECTED", tokensLeft);
        }
    }
}
