package org.project.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public class CacheStore {

    private static final int DEFAULT_MAX_SIZE = 100;

    private final int maxSize;
    private final Map<String, byte[]> cache;

    public CacheStore() {
        this(DEFAULT_MAX_SIZE);
    }

    public CacheStore(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be greater than 0");
        }
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > CacheStore.this.maxSize;
            }
        };
    }

    public byte[] get(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    public void put(String key, byte[] value) {
        synchronized (cache) {
            cache.put(key, value);
        }
    }

    public boolean contains(String key) {
        synchronized (cache) {
            return cache.containsKey(key);
        }
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
