package com.poc;

import redis.clients.jedis.Jedis;
import java.util.Arrays;
import java.util.List;

public class TokenBucketLimiter {
    private final Jedis jedis;
    private final int capacity;
    private final double refillRatePerSec;

    private static final String SCRIPT =
        "local key = KEYS[1] " +
        "local capacity = tonumber(ARGV[1]) " +
        "local refillRate = tonumber(ARGV[2]) " +
        "local now = tonumber(ARGV[3]) " +

        "local state = redis.call('HMGET', key, 'tokens', 'last_refill_timestamp') " +
        "local tokens = tonumber(state[1]) " +
        "local lastRefillTimestamp = tonumber(state[2]) " +

        "if tokens == nil then " +
        "    tokens = capacity " +
        "    lastRefillTimestamp = now " +
        "end " +

        "local elapsedSeconds = (now - lastRefillTimestamp) / 1000.0 " +
        "local tokensToAdd = elapsedSeconds * refillRate " +
        "local newTokens = math.min(capacity, tokens + tokensToAdd) " +

        "local allowed = 0 " +
        "if newTokens >= 1 then " +
        "    allowed = 1 " +
        "    newTokens = newTokens - 1 " +
        "end " +

        "redis.call('HSET', key, " +
        "    'tokens', tostring(newTokens), " +
        "    'capacity', tostring(capacity), " +
        "    'refill_rate_per_sec', tostring(refillRate), " +
        "    'last_refill_timestamp', tostring(now)) " +

        "return allowed";

    public TokenBucketLimiter(Jedis jedis, int capacity, double refillRatePerSec) {
        this.jedis = jedis;
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
    }

    public boolean allowRequest(String userId) {
        String key = "bucket:" + userId;
        long now = System.currentTimeMillis();

        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(capacity),
            String.valueOf(refillRatePerSec),
            String.valueOf(now)
        );

        Object result = jedis.eval(SCRIPT, keys, args);
        long allowed = (Long) result;
        return allowed == 1;
    }
}