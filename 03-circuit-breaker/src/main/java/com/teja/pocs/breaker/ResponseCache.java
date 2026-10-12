package com.teja.pocs.breaker;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Goal 4: remembers the last GOOD answer per product id, to serve (stale) when the store can't be used. */
public class ResponseCache {

    /** One cached answer: the JSON body + when we saved it. */
    record Entry(String body, long savedAtMs) {
        long ageMs() { return System.currentTimeMillis() - savedAtMs; }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();   // thread-safe map, 50 threads use it

    /** Save the latest good answer for this id (overwrites the older one). */
    public void put(String id, String body) {
        entries.put(id, new Entry(body, System.currentTimeMillis()));
    }

    /** The last good answer for this id, or empty if we never had one. */
    public Optional<Entry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }
}