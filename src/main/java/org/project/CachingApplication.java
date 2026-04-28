package org.project;

import org.project.cache.CacheStore;
import org.project.cacheclient.CacheClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Main entry point for the caching proxy server.
 *
 * <p>This application accepts inbound HTTP requests over a {@link ServerSocket}, forwards supported
 * requests to an origin server, and caches eligible responses in memory. Cache behavior includes LRU
 * eviction and optional POST-path allowlist support with request-body-hash cache keys.
 */
public class CachingApplication {
    private static final Logger logger = LoggerFactory.getLogger(CachingApplication.class);
    private static final String CACHEABLE_POST_PATHS_ARG = "--cacheable-post-paths";
    private static final String PORT = "--port";
    private static final String ORIGIN = "--origin";

    /**
     * Starts the proxy server and runs the connection accept loop.
     *
     * <p>Supported CLI arguments:
     * <ul>
     *   <li>{@code --port}: local port to bind</li>
     *   <li>{@code --origin}: upstream origin base URL</li>
     *   <li>{@code --cacheable-post-paths}: comma-separated POST paths eligible for caching</li>
     * </ul>
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int port = 0;
        String origin = null;
        Set<String> cacheablePostPaths = Collections.emptySet();
        CacheClient client = new CacheClient();
        CacheStore store = new CacheStore();

        for (int i = 0; i < args.length; i++) {
            if (PORT.equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
            }
            if (ORIGIN.equals(args[i]) && i + 1 < args.length) {
                origin = args[i + 1];
            }
            if (CACHEABLE_POST_PATHS_ARG.equals(args[i]) && i + 1 < args.length) {
                cacheablePostPaths = parseCacheablePostPaths(args[i + 1]);
            }
        }
        logger.info(
                "starting proxy server port={} origin={} cacheable_post_paths={}",
                port,
                origin,
                cacheablePostPaths);

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            logger.info("proxy server running port={}", port);
        } catch (IOException e) {
            logger.error("failed to start proxy server port={}", port, e);
            return;
        }
        boolean running = true;
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                logger.info("client connected client_ip={}", socket.getInetAddress());
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isBlank()) {
                    logger.warn("empty request line");
                    continue;
                }
                String[] parts = requestLine.trim().split("\\s+");
                if (parts.length < 2) {
                    logger.warn("malformed request line request_line={}", requestLine);
                    writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request");
                    continue;
                }
                Map<String, String> headers = readHeaders(reader);
                String method = parts[0];
                String path;
                try {
                    path = normalizePath(parts[1]);
                } catch (IllegalArgumentException invalidRequestTarget) {
                    logger.warn(
                            "invalid request target request_target={}",
                            parts[1],
                            invalidRequestTarget);
                    writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request");
                    continue;
                }
                boolean cacheable = isCacheable(method, path, cacheablePostPaths);
                byte[] requestBody = readRequestBody(reader, headers);
                String cacheKey = buildCacheKey(origin, path, method, cacheable, requestBody);
                boolean postBodyHashedKey = cacheable && "POST".equalsIgnoreCase(method);
                logger.info(
                        "request received method={} path={} cache_key={} cacheable={} request_body_bytes={} post_body_hashed_key={}",
                        method,
                        path,
                        cacheKey,
                        cacheable,
                        requestBody.length,
                        postBodyHashedKey);
                byte[] response;
                if (cacheable && store.contains(cacheKey)) {
                    logger.info("cache lookup cache_status=HIT cache_key={}", cacheKey);
                    response = store.get(cacheKey);
                    response = client.addCacheHeader(response, "HIT");
                } else {
                    logger.info(
                            "cache lookup cache_status={} cache_key={}",
                            cacheable ? "MISS" : "BYPASS",
                            cacheKey);
                    try {
                        response = client.getResponse(origin, path, method, requestBody, headers);
                        if (cacheable) {
                            store.put(cacheKey, response);
                            response = client.addCacheHeader(response, "MISS");
                        }
                    } catch (IOException | InterruptedException upstreamFailure) {
                        if (upstreamFailure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        logger.warn(
                                "upstream request failed origin={} path={}",
                                origin,
                                path,
                                upstreamFailure);
                        writeSimpleResponse(socket.getOutputStream(), 502, "Bad Gateway");
                        continue;
                    }
                }
                OutputStream outputStream = socket.getOutputStream();
                logger.info("writing response to socket path={}", path);
                try (InputStream responseStream = client.asInputStream(response)) {
                    responseStream.transferTo(outputStream);
                }
                outputStream.flush();
            } catch (IOException e) {
                logger.error("proxy request handling failed", e);
            }
        }
    }

    /**
     * Evaluates whether a request is cacheable under current policy.
     *
     * <p>GET requests are always cacheable. POST requests are cacheable only if the request path is
     * present in the configured allowlist.
     *
     * @param method incoming HTTP method
     * @param path normalized request path
     * @param cacheablePostPaths configured POST allowlist
     * @return {@code true} when request should participate in cache lookup/store; otherwise {@code false}
     */
    private static boolean isCacheable(String method, String path, Set<String> cacheablePostPaths) {
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }
        return "POST".equalsIgnoreCase(method) && cacheablePostPaths.contains(path);
    }

    /**
     * Builds a cache key for a request.
     *
     * <p>GET requests use {@code origin + path}. Cacheable POST requests append a SHA-256 hash of the
     * request body to avoid collisions across different payloads for the same endpoint.
     *
     * @param origin upstream origin URL
     * @param path normalized request path
     * @param method incoming HTTP method
     * @param cacheable whether request is cacheable under policy
     * @param requestBody request body bytes
     * @return deterministic cache key
     */
    private static String buildCacheKey(
            String origin, String path, String method, boolean cacheable, byte[] requestBody) {
        if (!cacheable || !"POST".equalsIgnoreCase(method)) {
            return origin + path;
        }
        return origin + path + "#body-sha256=" + sha256Hex(requestBody);
    }

    /**
     * Reads HTTP headers from the inbound client request stream.
     *
     * <p>Header names are normalized to lowercase. Reading stops at the first blank line.
     *
     * @param reader buffered reader positioned after the request line
     * @return map of header name to value
     * @throws IOException if stream reading fails
     */
    private static Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                break;
            }
            int separatorIndex = line.indexOf(':');
            if (separatorIndex <= 0) {
                continue;
            }
            String name = line.substring(0, separatorIndex).trim().toLowerCase();
            String value = line.substring(separatorIndex + 1).trim();
            headers.put(name, value);
        }
        return headers;
    }

    /**
     * Reads request body bytes based on {@code Content-Length}.
     *
     * <p>If {@code Content-Length} is missing, invalid, or non-positive, an empty byte array is returned.
     * Body text is decoded from chars and re-encoded as UTF-8 bytes for downstream usage.
     *
     * @param reader buffered reader positioned after headers
     * @param headers parsed request headers
     * @return request body bytes, or empty when not available
     * @throws IOException if stream reading fails
     */
    private static byte[] readRequestBody(BufferedReader reader, Map<String, String> headers)
            throws IOException {
        String contentLengthValue = headers.get("content-length");
        if (contentLengthValue == null) {
            logger.info("request body not present content_length_header=missing");
            return new byte[0];
        }
        int contentLength;
        try {
            contentLength = Integer.parseInt(contentLengthValue);
        } catch (NumberFormatException invalidLength) {
            logger.warn("invalid content-length header content_length={}", contentLengthValue);
            return new byte[0];
        }
        if (contentLength <= 0) {
            logger.info("request body not present content_length={}", contentLength);
            return new byte[0];
        }
        char[] bodyChars = new char[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = reader.read(bodyChars, offset, contentLength - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        if (offset < contentLength) {
            logger.warn(
                    "request body truncated while reading expected_bytes={} actual_bytes={}",
                    contentLength,
                    offset);
        }
        logger.info("request body read bytes={}", offset);
        return new String(bodyChars, 0, offset).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Computes SHA-256 hash for provided bytes and returns lowercase hex.
     *
     * @param value input bytes to hash
     * @return lowercase hexadecimal SHA-256 digest
     * @throws IllegalStateException when SHA-256 is unavailable in the runtime
     */
    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    /**
     * Parses a comma-separated list of POST paths that are cacheable.
     *
     * <p>Each item is trimmed and normalized to start with {@code /}. Empty items are ignored.
     *
     * @param rawPaths raw CLI argument value
     * @return normalized set of cacheable POST paths
     */
    private static Set<String> parseCacheablePostPaths(String rawPaths) {
        if (rawPaths == null || rawPaths.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> paths = new HashSet<>();
        String[] values = rawPaths.split(",");
        for (String value : values) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            paths.add(trimmed.startsWith("/") ? trimmed : "/" + trimmed);
        }
        return paths;
    }

    /**
     * Normalizes the request target into a path + optional query.
     *
     * <p>Absolute URLs are converted to path/query form. Empty targets are mapped to {@code /}.
     *
     * @param requestTarget raw request target from request line
     * @return normalized path or path+query
     */
    private static String normalizePath(String requestTarget) {
        if (requestTarget == null || requestTarget.isBlank()) {
            return "/";
        }
        if (requestTarget.startsWith("http://") || requestTarget.startsWith("https://")) {
            URI uri = URI.create(requestTarget);
            String path =
                    uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        }
        return requestTarget;
    }

    /**
     * Writes a minimal plain-text HTTP response to the client.
     *
     * @param outputStream client output stream
     * @param statusCode HTTP status code
     * @param message status reason and body text
     * @throws IOException if writing fails
     */
    private static void writeSimpleResponse(
            OutputStream outputStream, int statusCode, String message) throws IOException {
        String statusLine = "HTTP/1.1 " + statusCode + " " + message + "\r\n";
        byte[] body = (message + "\n").getBytes(StandardCharsets.ISO_8859_1);
        String headers =
                "Content-Type: text/plain\r\nContent-Length: "
                        + body.length
                        + "\r\nConnection: close\r\n\r\n";
        outputStream.write(statusLine.getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(body);
        outputStream.flush();
    }
}
