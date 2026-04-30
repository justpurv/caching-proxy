package org.project.cacheclient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Upstream HTTP client adapter for proxy request forwarding and raw-response construction.
 *
 * <p>This class forwards GET/POST requests to the configured origin, serializes upstream status,
 * headers, and body into raw HTTP response bytes, and provides helpers for cache header injection.
 */
public class CacheClient {
    private final HttpClient client;
    private static final byte[] HEADER_SEPARATOR = new byte[] {'\r', '\n', '\r', '\n'};

    /** Creates a cache client with default connect timeout configuration. */
    public CacheClient() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Convenience method for GET request forwarding.
     *
     * @param origin upstream origin base URL
     * @param path normalized request path
     * @return raw HTTP response bytes (status line + headers + body)
     * @throws IOException when network or I/O fails
     * @throws InterruptedException when request thread is interrupted
     */
    public byte[] getResponse(String origin, String path) throws IOException, InterruptedException {
        return getResponse(origin, path, "GET", new byte[0], Map.of());
    }

    /**
     * Forwards request to upstream origin and returns full raw HTTP response bytes.
     *
     * @param origin upstream origin base URL
     * @param path normalized request path
     * @param method HTTP method (currently GET/POST behavior)
     * @param requestBody request payload bytes for POST
     * @param headers inbound headers map used for selected header forwarding
     * @return raw HTTP response bytes (status line + headers + body)
     * @throws IOException when network or I/O fails
     * @throws InterruptedException when request thread is interrupted
     */
    public byte[] getResponse(
            String origin,
            String path,
            String method,
            byte[] requestBody,
            Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder =
                HttpRequest.newBuilder()
                        .uri(URI.create(origin + path))
                        .timeout(Duration.ofSeconds(15));
        if ("POST".equalsIgnoreCase(method)) {
            byte[] body = requestBody == null ? new byte[0] : requestBody;
            requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            requestBuilder.GET();
        }

        copyForwardableHeaders(requestBuilder, headers);
        HttpRequest request = requestBuilder.build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // status line
        String statusLine = "HTTP/1.1 " + response.statusCode() + "\r\n";
        output.write(statusLine.getBytes(StandardCharsets.ISO_8859_1));
        // headers
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            String headerLine =
                    header.getKey() + ": " + String.join(",", header.getValue()) + "\r\n";
            output.write(headerLine.getBytes(StandardCharsets.ISO_8859_1));
        }
        // end headers
        output.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        // body
        try (InputStream bodyStream = response.body()) {
            bodyStream.transferTo(output);
        }
        return output.toByteArray();
    }

    /**
     * Copies a conservative subset of inbound headers to upstream request.
     *
     * @param requestBuilder target request builder
     * @param headers source headers map
     */
    private void copyForwardableHeaders(
            HttpRequest.Builder requestBuilder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        // Keep forwarding conservative; avoid hop-by-hop and Host headers.
        copyHeaderIfPresent(requestBuilder, headers, "content-type");
        copyHeaderIfPresent(requestBuilder, headers, "accept");
    }

    /**
     * Copies one header to request builder if present and non-blank.
     *
     * @param requestBuilder target request builder
     * @param headers source headers map
     * @param headerName lowercase header name
     */
    private void copyHeaderIfPresent(
            HttpRequest.Builder requestBuilder, Map<String, String> headers, String headerName) {
        String value = headers.get(headerName);
        if (value != null && !value.isBlank()) {
            requestBuilder.header(headerName, value);
        }
    }

    /**
     * Injects {@code X-Cache} header into raw HTTP response bytes.
     *
     * @param response raw HTTP response bytes
     * @param value header value (for example {@code HIT} or {@code MISS})
     * @return response bytes with injected cache header when header section is detected
     */
    public byte[] addCacheHeader(byte[] response, String value) {
        int headerIndex = findHeaderSeparatorIndex(response);

        if (headerIndex == -1) {
            return response;
        }

        byte[] cacheHeader = ("\r\nX-Cache: " + value).getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(response.length + cacheHeader.length);
        output.write(response, 0, headerIndex);
        output.write(cacheHeader, 0, cacheHeader.length);
        output.write(response, headerIndex, response.length - headerIndex);
        return output.toByteArray();
    }

    /**
     * Backward-compatible misspelled alias retained for existing callers.
     *
     * @param response raw HTTP response bytes
     * @param value header value
     * @return response bytes with injected cache header
     */
    public byte[] addCahceHeader(byte[] response, String value) {
        return addCacheHeader(response, value);
    }

    /**
     * Wraps response bytes in an {@link InputStream} for socket streaming.
     *
     * @param responseBytes response bytes
     * @return input stream over response bytes
     */
    public InputStream asInputStream(byte[] responseBytes) {
        return new ByteArrayInputStream(responseBytes);
    }

    /**
     * Finds index of CRLFCRLF header separator in raw HTTP response bytes.
     *
     * @param response raw HTTP response bytes
     * @return index of separator start, or {@code -1} when not found
     */
    private int findHeaderSeparatorIndex(byte[] response) {
        for (int i = 0; i <= response.length - HEADER_SEPARATOR.length; i++) {
            if (response[i] == HEADER_SEPARATOR[0]
                    && response[i + 1] == HEADER_SEPARATOR[1]
                    && response[i + 2] == HEADER_SEPARATOR[2]
                    && response[i + 3] == HEADER_SEPARATOR[3]) {
                return i;
            }
        }
        return -1;
    }
}
