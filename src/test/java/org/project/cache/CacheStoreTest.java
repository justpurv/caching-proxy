package org.project.cache;

import junit.framework.TestCase;

public class CacheStoreTest extends TestCase {

    public void testEvictsLeastRecentlyUsedWhenMaxSizeExceeded() {
        CacheStore store = new CacheStore(2);

        store.put("a", new byte[] {1});
        store.put("b", new byte[] {2});
        store.put("c", new byte[] {3});

        assertFalse(store.contains("a"));
        assertTrue(store.contains("b"));
        assertTrue(store.contains("c"));
    }

    public void testGetPromotesEntryAsRecentlyUsed() {
        CacheStore store = new CacheStore(2);

        store.put("a", new byte[] {1});
        store.put("b", new byte[] {2});
        assertNotNull(store.get("a")); // promote "a" to most recently used
        store.put("c", new byte[] {3});

        assertTrue(store.contains("a"));
        assertFalse(store.contains("b"));
        assertTrue(store.contains("c"));
    }

    public void testRejectsNonPositiveMaxSize() {
        try {
            new CacheStore(0);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("maxSize must be greater than 0", expected.getMessage());
        }
    }
}
