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

public class CacheClient {
    private final HttpClient client;
    private static final byte[] HEADER_SEPARATOR = new byte[] {'\r', '\n', '\r', '\n'};

    public CacheClient() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public byte[] getResponse(String origin, String path) throws IOException, InterruptedException {
        return getResponse(origin, path, "GET", new byte[0], Map.of());
    }

    public byte[] getResponse(
            String origin, String path, String method, byte[] requestBody, Map<String, String> headers)
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
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // status line
        String statusLine = "HTTP/1.1 " + response.statusCode() + "\r\n";
        output.write(statusLine.getBytes(StandardCharsets.ISO_8859_1));
        // headers
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            String headerLine =
                    header.getKey() + ": " +
                            String.join(",", header.getValue()) +
                            "\r\n";
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

    private void copyForwardableHeaders(HttpRequest.Builder requestBuilder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        // Keep forwarding conservative; avoid hop-by-hop and Host headers.
        copyHeaderIfPresent(requestBuilder, headers, "content-type");
        copyHeaderIfPresent(requestBuilder, headers, "accept");
    }

    private void copyHeaderIfPresent(
            HttpRequest.Builder requestBuilder, Map<String, String> headers, String headerName) {
        String value = headers.get(headerName);
        if (value != null && !value.isBlank()) {
            requestBuilder.header(headerName, value);
        }
    }

    public byte[] addCacheHeader(byte[] response, String value) {
        int headerIndex = findHeaderSeparatorIndex(response);

        if (headerIndex == -1) {
            return response;
        }

        byte[] cacheHeader = ("\r\nX-Cache: " + value).getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream output = new ByteArrayOutputStream(response.length + cacheHeader.length);
        output.write(response, 0, headerIndex);
        output.write(cacheHeader, 0, cacheHeader.length);
        output.write(response, headerIndex, response.length - headerIndex);
        return output.toByteArray();
    }

    // Keep compatibility with existing callers.
    public byte[] addCahceHeader(byte[] response, String value) {
        return addCacheHeader(response, value);
    }

    public InputStream asInputStream(byte[] responseBytes) {
        return new ByteArrayInputStream(responseBytes);
    }

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
