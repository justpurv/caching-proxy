package org.project.cacheclient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class CacheClient {
    private final HttpClient client;
    public CacheClient() {
        client = HttpClient.newHttpClient();
    }
    public String getResponse(String origin, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(origin + path)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
