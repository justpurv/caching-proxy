package org.project.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe in-memory byte-response cache with LRU eviction.
 *
 * <p>Internally uses an access-order {@link LinkedHashMap} and evicts the least recently used entry
 * when the configured maximum size is exceeded.
 */
public class CacheStore {

    private static final int DEFAULT_MAX_SIZE = 100;

    private final int maxSize;
    private final Map<String, byte[]> cache;

    /** Creates a cache store with default maximum capacity. */
    public CacheStore() {
        this(DEFAULT_MAX_SIZE);
    }

    /**
     * Creates a cache store with custom maximum capacity.
     *
     * @param maxSize maximum number of entries retained in cache
     * @throws IllegalArgumentException when {@code maxSize <= 0}
     */
    public CacheStore(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be greater than 0");
        }
        this.maxSize = maxSize;
        this.cache =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                        return size() > CacheStore.this.maxSize;
                    }
                };
    }

    /**
     * Returns cached value for key and updates recency ordering when present.
     *
     * @param key cache key
     * @return cached response bytes, or {@code null} if absent
     */
    public byte[] get(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    /**
     * Stores value by key and triggers LRU eviction when capacity is exceeded.
     *
     * @param key cache key
     * @param value response bytes
     */
    public void put(String key, byte[] value) {
        synchronized (cache) {
            cache.put(key, value);
        }
    }

    /**
     * Checks whether key currently exists in cache.
     *
     * @param key cache key
     * @return {@code true} when key is present
     */
    public boolean contains(String key) {
        synchronized (cache) {
            return cache.containsKey(key);
        }
    }

    /** Removes all entries from cache. */
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
