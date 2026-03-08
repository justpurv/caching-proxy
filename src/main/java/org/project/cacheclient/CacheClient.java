package org.project.cacheclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class CacheClient {
    private final HttpClient client;

    public CacheClient() {
        client = HttpClient.newHttpClient();
    }

    public byte[] getResponse(String origin, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(origin + path)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // status line
        String statusLine = "HTTP/1.1 " + response.statusCode() + " OK\r\n";
        output.write(statusLine.getBytes());
        // headers
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {

            String headerLine =
                    header.getKey() + ": " +
                            String.join(",", header.getValue()) +
                            "\r\n";
            output.write(headerLine.getBytes());
        }
        // end headers
        output.write("\r\n".getBytes());
        // body
        output.write(response.body());
        return output.toByteArray();
    }
}
