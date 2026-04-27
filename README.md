# Caching Server

A lightweight Java HTTP caching proxy that forwards requests to an origin server and caches full HTTP responses in memory.

It now supports binary-safe responses (for example JSON, images, PDFs) by handling response bodies as bytes/streams instead of converting the full response to `String`.

## Features

- Simple proxy server over `ServerSocket`
- In-memory cache with LRU eviction (default max size: `100` entries)
- Cache status header:
  - `X-Cache: MISS` for first request
  - `X-Cache: HIT` for cached request
- Binary-safe response handling (no body corruption for non-text content)
- Graceful handling for malformed request lines (`400 Bad Request`)
- Upstream failure handling (`502 Bad Gateway`) to avoid hanging client connections
- Structured logging with `SLF4J` + `Logback` (key-value fields)

## Project Structure

- `src/main/java/org/project/CachingApplication.java`
  - Entry point, socket accept loop, request parsing, cache decision, response write
- `src/main/java/org/project/cacheclient/CacheClient.java`
  - Calls origin server, builds raw HTTP response bytes, injects `X-Cache` header safely
- `src/main/java/org/project/cache/CacheStore.java`
  - Thread-safe in-memory LRU cache store (`Map<String, byte[]>`) with max-size eviction
- `src/test/java/org/project/cache/CacheStoreTest.java`
  - Verifies LRU eviction and recency update behavior
- `src/test/java/org/project/cacheclient/CacheClientTest.java`
  - Verifies header injection preserves binary body bytes
- `src/main/resources/logback.xml`
  - Console logging pattern for structured key-value logs

## Requirements

- Java (project is configured for source/target `25` in `pom.xml`)
- Maven 3.9+

## Build and Test

```bash
mvn clean test
```

## Run

```bash
mvn -q exec:java -Dexec.mainClass=org.project.CachingApplication -Dexec.args="--port 8080 --origin https://jsonplaceholder.typicode.com"
```

Arguments:

- `--port`: local proxy port
- `--origin`: upstream base URL

## Logging

The application uses `SLF4J` with `Logback` and emits structured key-value logs.

Example log line:

```text
2026-04-27T16:19:54.559+05:30 level=INFO logger=org.project.CachingApplication thread=... msg="cache lookup" cache_status="MISS" cache_key="https://jsonplaceholder.typicode.com/photos"
```

## How It Works

1. Client connects to proxy and sends request line.
2. Proxy extracts request target path and builds cache key as `origin + path`.
3. If key exists in cache:
   - return cached response + `X-Cache: HIT`
4. If key does not exist:
   - fetch from origin
   - store full response bytes in cache
   - if cache exceeds max size, least-recently-used entry is evicted
   - return response + `X-Cache: MISS`
5. Response bytes are streamed to socket output.

## Quick Validation

### 1) JSON endpoint (cache HIT/MISS)

```bash
curl -i http://localhost:8080/posts/1
curl -i http://localhost:8080/posts/1
```

Expected:

- First response includes `X-Cache: MISS`
- Second response includes `X-Cache: HIT`

### 2) Image endpoint

Run server against an image-capable origin:

```bash
mvn -q exec:java -Dexec.mainClass=org.project.CachingApplication -Dexec.args="--port 8080 --origin https://httpbin.org"
```

Then:

```bash
curl -i http://localhost:8080/image/png -o /tmp/proxy-image-1.png
curl -i http://localhost:8080/image/png -o /tmp/proxy-image-2.png
cmp /tmp/proxy-image-1.png /tmp/proxy-image-2.png && echo "binary match"
```

### 3) PDF endpoint

Run server against a PDF-capable origin:

```bash
mvn -q exec:java -Dexec.mainClass=org.project.CachingApplication -Dexec.args="--port 8080 --origin https://www.w3.org"
```

Then:

```bash
curl -i "http://localhost:8080/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf" -o /tmp/proxy.pdf
file /tmp/proxy.pdf
```

Expected `file` output to identify it as a PDF document.

## Notes and Limitations

- Cache is in-memory only (no persistence across process restart).
- LRU cache max size is currently fixed to `100` entries in code.
- Cache key is `origin + path` only.
- Request handling is minimal (primarily GET use case).
- Does not forward client request headers/body.
- Single-process local proxy, intended as a learning/simple caching server.

## Next Improvements

- Add TTL-based cache expiry and configurable cache max size (for example via CLI args)
- Stream-to-disk cache for very large payloads
- Support more HTTP methods and request headers
- Add cache and request metrics (hit ratio, latency, error rates)
- Add integration tests with embedded origin server
