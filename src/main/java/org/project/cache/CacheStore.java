package org.project.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStore {

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public byte[] get(String key) {
        return cache.get(key);
    }

    public void put(String key, byte[] value) {
        cache.put(key, value);
    }

    public boolean contains(String key) {
        return cache.containsKey(key);
    }

    public void clear() {
        cache.clear();
    }
}
